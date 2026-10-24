package local.clashfarmer.service

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.clashfarmer.core.AppSwitchLatch
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.LocalRunner
import local.clashfarmer.core.MaintenanceWait
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.ReturnHome
import local.clashfarmer.core.RunGuard
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.sentOrHeld
import local.clashfarmer.desktop.platform.ClashHost
import local.clashfarmer.desktop.platform.ClasspathAssets
import local.clashfarmer.desktop.platform.GameSurface
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.log.AppLog
import local.clashfarmer.log.FailureSnapshot
import local.clashfarmer.recognition.ImageMatcher
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop port of the phone's AccessibilityService: a plain class with the phone's members, so
 * the copied run code calls it unchanged (docs/PLAN.md, class 3).
 *
 * The bodies are the phone's, line for line, except where they touched Android. Those go through
 * [host] (the Clash window, starting and closing Clash, the battery, keeping the PC awake) and
 * [surface] (screenshots and input, which [transport] drives); docs/DIVERGENCES.md lists each
 * change. Clash counts as in front whenever its window exists, minimized or behind other windows.
 * The phone's broadcast receivers, onAccessibilityEvent and onInterrupt have no counterpart. The
 * notification's buttons are [pauseFromNotification], [resumeFromNotification] and
 * [stopFromNotification], for the tray.
 *
 * [packageName], [assets], [getSystemService] and [performGlobalAction] stand in for the Android
 * members the copied code reads.
 */
class FarmerService(
    /** The app's files directory, `%LOCALAPPDATA%\ClashFarmerPC\files` on the PC; the phone's Context.filesDir. */
    val filesDir: File,
    /** The Clash window, starting Clash, the battery, and keeping the PC awake. */
    private val host: ClashHost,
    /** The game window's screenshots and input, which [transport] drives. */
    private val surface: GameSurface
) {

    companion object {
        const val CLASH_PACKAGE = "com.supercell.clashofclans"
        /** The account check, with room for the walk back to the village that comes before it. */
        const val ACCOUNT_CHECK_MS = 180_000L + local.clashfarmer.core.ReturnHome.START_BOUND_MS
        /** How long the notification's Resume gives Clash to come back in front. */
        private const val RESUME_AFTER_MS = 1_500L
        /** The longest awake stretch a start set for later waits before rereading the clock. */
        private const val PENDING_SLICE_MS = 30_000L
        /** Desktop: how often the battery is read for [readDeviceHealth]. */
        private const val HEALTH_REFRESH_MS = 30_000L
        /** Desktop: how long a start waits for a window lookup that worked before it decides to launch Clash. */
        private const val WINDOW_LOOKUP_WAIT_MS = 10_000L
        /** Desktop: how far a surface's aspect may stray from its profile's and still count as the same. */
        private const val SAME_ASPECT_TOLERANCE = 0.01
        /** Desktop: how long a resize waits for the window watcher, which looks every 500 ms, to see it. */
        private const val RESIZE_SEEN_MS = 2_000L

        /** Why a start is refused while Inspect or the tray check holds Clash on screen. */
        const val INSPECTING = "The app is reading the game screen for Inspect or the tray check; try again when it ends"

        @Volatile
        var instance: FarmerService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * One per process, not per service. After an install the system can connect a fresh
         * service without ever destroying the old one, which kept the port and answered the page
         * with a dead connection: every remote start failed its first screenshot.
         */
        private var remoteServer: local.clashfarmer.remote.RemoteServer? = null
    }

    /**
     * Whether the helper's own screen is the one being shown.
     *
     * Clash announces nothing when it comes to the front - no node tree, and no window state
     * change either - so waiting to be told it is foreground timed out ninety seconds after it
     * was plainly drawing the village, and the session never started. Only two apps are involved
     * in a run, and this one knows perfectly well when it is the one on screen; when it is not,
     * and nothing else has announced itself, the game we just launched is in front.
     */
    @Volatile
    var helperActivityVisible = false

    /**
     * Set by the app while Inspect or the tray check brings Clash up for one frame. A run started
     * then is stopped by RunGuard when the app comes back, so starts from outside the app check
     * this. Not part of [isBusy], which also drives Stop, the strip and the notification.
     */
    @Volatile
    var inspecting = false

    val runGuard = RunGuard()
    lateinit var transport: AndroidTransport
        private set

    /** The newest capture for the dashboard and the remote page, in memory only. */
    val latestFrame: LatestFrame by lazy { LatestFrame(serviceScope) }

    var overlay: FarmerOverlay? = null
        private set

    private var notifier: RunNotifier? = null

    /** The run set on the Run screen to start by itself later; see [PendingStart]. */
    @Volatile
    var pendingStart: PendingStart? = null
        private set
    private var pendingJob: Job? = null
    private val pendingLock = Any()
    private val pendingStore by lazy { PendingStartStore(filesDir) }

    /** When the current run started, so the overlay can tell the previous run's snapshot from this one's. */
    val runStartedAtMs: Long? get() = runStartedAt

    /** The builder counter last read at home, per account, for the dashboard. */
    val builderStore: local.clashfarmer.core.BuilderStore by lazy { local.clashfarmer.core.BuilderStore(filesDir) }

    val remoteKey: String by lazy {
        local.clashfarmer.remote.RemoteKey.loadOrCreate(File(filesDir, "remote_key.txt"))
    }

    val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile
    private var activeRunnerJob: Job? = null
    val isBusy: Boolean get() {
        val job = activeRunnerJob
        return (job != null && !job.isCompleted) || !runGuard.isStopped()
    }

    /** Desktop: called once the app has wired [host] and [surface], where Android connected the service. */
    fun onServiceConnected() {
        instance = this
        connectTransport()

        AppLog.init(filesDir)
        AppLog.log("service_connected")
        try {
            val pruned = local.clashfarmer.log.StorageRetention.prune(filesDir)
            AppLog.log(
                "retention_pruned",
                "capturesDeleted" to pruned.capturesDeleted,
                "capturesKept" to pruned.capturesKept,
                "logsDeleted" to pruned.logsDeleted,
                "bytesFreed" to pruned.bytesFreed
            )
        } catch (e: Exception) {
            AppLog.log("retention_failed", "error" to e.message)
        }
        notifier = RunNotifier()
        // Nothing keeps a start alive across a dead service, so one still on disk did not run.
        val dropped = pendingStore.read()
        if (dropped != null) {
            pendingStore.clear()
            refusePendingStart(dropped, "The helper stopped before the start time", notify = true)
        } else if (pendingStore.exists()) {
            pendingStore.clear()
            AppLog.log("scheduled_start_unreadable")
        }

        overlay = FarmerOverlay(
            onStopRequested = { stopReason -> stop(stopReason) },
            onPauseToggled = {
                // During the shade's hold the button shows Pause; pressing it keeps the pause.
                if (!claimPause()) {
                    if (runGuard.isPaused()) runGuard.resume() else runGuard.pause()
                }
                // The press is logged where it lands, not where it takes effect, so a press the
                // runner never acts on can be told apart from one that never arrived.
                AppLog.log("pause_button", "paused" to runGuard.isPaused())
            },
            // A replaced service's overlay keeps ticking; only the live one may post, or the
            // notification's buttons would carry a token only the idle old receiver accepts.
            onLiveStatus = { if (!replaced) notifier?.postLive(it) }
        )
        overlay?.show()

        try {
            val page = assets.open("remote.html").bufferedReader().use { it.readText() }
            val api = local.clashfarmer.remote.RemoteApi(remoteKey, local.clashfarmer.remote.ServiceRemoteBackend(this), page)
            remoteServer?.stop()
            remoteServer = local.clashfarmer.remote.RemoteServer(local.clashfarmer.remote.RemoteServer.PORT, api)
                .also { it.start() }
        } catch (e: Exception) {
            AppLog.log("remote_server_failed", "error" to e.message)
        }

        // Desktop: the phone registered its screen-off and notification receivers here. The PC
        // has no screen-off broadcast, so a sleeping or locked PC is left to the run's captures,
        // which stop it when they fail; the notification's buttons are the tray's calls to
        // pauseFromNotification, resumeFromNotification and stopFromNotification. What starts
        // here instead is the battery reading behind readDeviceHealth.
        serviceScope.launch {
            while (true) {
                refreshDeviceHealth()
                delay(HEALTH_REFRESH_MS)
            }
        }
    }

    /** Desktop: builds [transport] over [surface]. [onServiceConnected] does it first; tests call it alone. */
    internal fun connectTransport() {
        transport = AndroidTransport(this, runGuard, surface)
    }

    private fun latchAction(active: String?): AppSwitchLatch.Action = AppSwitchLatch.decide(
        active = active,
        own = packageName,
        unlocked = isDeviceUnlocked(),
        running = !runGuard.isStopped(),
        paused = runGuard.isPaused(),
        held = systemWindowHold,
        restartable = restartableRun,
        waitingForVillage = waitingForVillage
    )

    private fun gateAction(active: String?): AppSwitchLatch.Gate = AppSwitchLatch.gate(
        active = active,
        own = packageName,
        unlocked = isDeviceUnlocked(),
        running = !runGuard.isStopped(),
        paused = runGuard.isPaused(),
        held = systemWindowHold,
        restartable = restartableRun,
        waitingForVillage = waitingForVillage
    )

    /**
     * Whether the current pause is the shade's hold rather than one a person pressed. Only the
     * hold ends by itself when Clash is back in front; a Pause pressed during it takes it over.
     * Set and released on the main thread, in order with the window changes the latch reads.
     */
    @Volatile
    private var systemWindowHold = false

    /** True while the run is paused by the shade's hold, for the overlay and the notification. */
    val isHeldBySystemWindow: Boolean get() = systemWindowHold && runGuard.isPaused()

    /**
     * Pauses the run while the shade covers Clash; see [AppSwitchLatch]. [active] is what the
     * foreground read said, and [at] is what found the shade: the window change, the
     * notification's Resume, or a capture or gesture that asked before its change arrived.
     */
    private fun holdForSystemWindow(active: String?, at: String) {
        // Once per hold, though the notification's Resume can ask again.
        if (isHeldBySystemWindow) return
        runGuard.pause(byPerson = false)
        systemWindowHold = true
        // The guard ignores a pause while it is stopped between phases, and a stop can land
        // between the two lines above.
        if (!runGuard.isPaused()) {
            systemWindowHold = false
            return
        }
        AppLog.log("run_held", "reason" to "system window open", "active" to active, "at" to at)
        overlay?.updateStatus("Paused: notification shade open")
        watchHold()
    }

    /** The current hold's watch; the next hold replaces it. Main thread only. */
    private var holdWatch: Job? = null

    /**
     * Logs a hold still in place after [AppSwitchLatch.STILL_HELD_AFTER_MS], with what the
     * foreground read said and where it came from, and ends the run once the hold has lasted
     * [AppSwitchLatch.HOLD_CAP_MS]. The guard gives paused time back to its deadline, so only the
     * session's outer limit, hours away, would end it otherwise. A release, a stop, or a Pause
     * pressed during the hold (the pause is then the person's) ends the watch.
     */
    private fun watchHold() {
        holdWatch?.cancel()
        val timer = AppSwitchLatch.HoldTimer(android.os.SystemClock.elapsedRealtime())
        holdWatch = serviceScope.launch(Dispatchers.Main) {
            while (true) {
                delay(1_000L)
                when (timer.next(android.os.SystemClock.elapsedRealtime(), isHeldBySystemWindow)) {
                    AppSwitchLatch.HoldTimer.Tick.WAIT -> Unit
                    AppSwitchLatch.HoldTimer.Tick.STILL_HELD -> {
                        val (active, source) = readForeground()
                        AppLog.log("run_still_held", "active" to active, "source" to source, "windows" to windowSummary())
                    }
                    AppSwitchLatch.HoldTimer.Tick.TIMEOUT -> {
                        AppLog.log("run_hold_timeout", "active" to getActiveForegroundPackage())
                        stop(AppSwitchLatch.HOLD_TIMEOUT_REASON)
                        return@launch
                    }
                    AppSwitchLatch.HoldTimer.Tick.OVER -> return@launch
                }
            }
        }
    }

    /**
     * Holds the run if the shade is over Clash now, without waiting for its window change. Read
     * again on the main thread, in order with the window changes the latch acts on.
     */
    internal suspend fun holdIfShadeOver(at: String) {
        withContext(Dispatchers.Main) {
            val active = getActiveForegroundPackage()
            if (latchAction(active) == AppSwitchLatch.Action.HOLD) holdForSystemWindow(active, at)
        }
    }

    /**
     * Asked before every capture and gesture a run makes, and before the early-end Back, ahead of
     * [RunGuard.checkCanAct]. A pause only takes effect at the runner's pause points, and the
     * shade can be down before its window change arrives; the check would read it as another app
     * and end the run. So this asks [AppSwitchLatch.gate] with what is in front now, holds the run
     * under the shade, and waits until the hold lets go. Anything else goes on to the check, which
     * stops on the lock screen and stops or recovers for another app as before.
     *
     * True when the shade was over Clash, so what the caller read before may have changed. The
     * time spent waiting is added to [heldMs].
     */
    suspend fun awaitClearToAct(at: String): Boolean {
        var waited = false
        while (true) {
            when (gateAction(getActiveForegroundPackage())) {
                AppSwitchLatch.Gate.CHECK -> return waited
                AppSwitchLatch.Gate.HOLD -> holdIfShadeOver(at)
                AppSwitchLatch.Gate.WAIT -> Unit
            }
            waited = true
            // The check reports a stop with its reason. A hold that did not take is asked again.
            if (runGuard.isStopped()) return waited
            val since = android.os.SystemClock.elapsedRealtime()
            waitWhilePaused()
            heldMs += android.os.SystemClock.elapsedRealtime() - since
        }
    }

    /**
     * Milliseconds runs have waited, ever: every wait in [awaitClearToAct], the shade's and a
     * person's pause alike, and in [awaitResume] when the pause was the shade's hold. A loop with
     * a deadline of its own reads it at the start and moves the deadline on by what it grew, so a
     * pause does not use up time the loop needed. Since the gate waits out a person's pause with
     * Clash in front (UO-21), that wait counts too; before, only the shade's did.
     */
    @Volatile
    var heldMs = 0L
        private set

    private fun releaseSystemWindowHold() {
        systemWindowHold = false
        runGuard.resume()
        AppLog.log("run_hold_released")
    }

    /**
     * Lets go of the hold if Clash now reads as in front. The latch does this on a window
     * change; [awaitResume] asks again, for a change whose read came late.
     */
    private fun releaseHoldIfClashBack() {
        if (latchAction(getActiveForegroundPackage()) == AppSwitchLatch.Action.RELEASE) releaseSystemWindowHold()
    }

    /** The notification Resume waiting for Clash to draw; a newer press replaces it. */
    private var notificationResume: Job? = null

    /**
     * Called first by every Pause or Resume press on the overlay and in the notification. Drops
     * a notification Resume still waiting, since the newer press replaces it, and makes the
     * shade's hold the person's own pause. True when there was a hold to take over.
     *
     * The guard is told too, so the pause is carried through the internal stops between runs of
     * one job the way a pause pressed outright is; the shade's hold alone is not carried.
     */
    private fun claimPause(): Boolean {
        notificationResume?.cancel()
        notificationResume = null
        val held = isHeldBySystemWindow
        systemWindowHold = false
        if (held) runGuard.pause(byPerson = true)
        return held
    }

    /** Set while an unattended run's restart loop is running; only such a run reopens Clash. */
    @Volatile
    private var restartableRun = false

    /** HOME is intentional during takeover backoff; the pause guard remains active. */
    @Volatile
    private var waitingForVillage = false

    /** Keeps a run's first stop reason; see [FirstStop]. */
    private val firstStop = FirstStop()

    /**
     * An internal stop between two runs of one job. A person's pause standing now, or one already
     * carried, is kept and the next run starts paused, so the next run's first tap does not undo a
     * Pause pressed during the stop; the shade's hold is not kept. The person's Stop and the end
     * of a job go through [RunGuard.stop] without the carry and drop it. LocalRunner's normal
     * finishes come here too, since in a combined run the next account's switch follows them.
     */
    fun stopBetweenRuns(reason: String) {
        runGuard.stop(reason, carryPause = true)
        if (runGuard.isPaused()) AppLog.log("pause_carried", "reason" to reason)
    }

    fun stop(reason: String) {
        val wasBusy = isBusy
        // A start set for later outlives the run before it. With nothing running, Stop cancels it.
        if (!wasBusy) dropPendingStart(reason, notify = !FailureSnapshot.isUserStop(reason))
        val earlierReason = runGuard.stopReason
        runGuard.stop(reason)
        systemWindowHold = false
        val job = activeRunnerJob
        job?.cancel()
        // Nothing ran. A cancelled start has its scheduled_start_refused line, and an idle
        // service's destroy is service_destroyed alone.
        if (!wasBusy) return
        if (!firstStop.isFirst(job)) {
            // Work still in flight failing because of the first stop; it says nothing new.
            AppLog.log("stop_side_effect", "reason" to reason)
            return
        }
        overlay?.updateStatus("Stopped: $reason")
        AppLog.log("run_stopped", "reason" to reason)
        // A Stop press reaches here a second time as the runner's own exception, so the guard's
        // earlier reason is checked too. The shade's hold cap is no failure of the run's either.
        val quiet = { r: String? -> FailureSnapshot.isUserStop(r) || AppSwitchLatch.isHoldTimeout(r) }
        if (wasBusy && !quiet(reason) && !quiet(earlierReason)) {
            saveFailureSnapshot("run_stopped")
        }
        // Posted after the guard has stopped, so the app-switch latch cannot read it as leaving Clash.
        if (wasBusy) notifier?.post(RunNotice.stopped(null, reason))
    }

    /**
     * Sets [plan] to start by itself at [atMs]. The checks a remote start makes run at that time,
     * not now. Throws IllegalStateException with a readable reason when it cannot be set.
     */
    fun scheduleStart(plan: local.clashfarmer.core.RunPlan, atMs: Long) {
        val now = System.currentTimeMillis()
        PendingStart.problem(atMs, now)?.let { throw IllegalStateException(it) }
        val store = local.clashfarmer.accounts.AccountStore(filesDir)
        val accounts = store.load()
        val selected = runCatching { store.selectedId() }.getOrNull()
        val resolved = plan.resolve(accounts, selected)
        val names = resolved.accountIds.map { id ->
            accounts.firstOrNull { it.id == id }?.villageName ?: throw IllegalStateException("No such account")
        }
        // End by is judged from the start time, as firePendingStart will judge it. The start
        // fires a little after its time, up to LATE_MS, and End by's minutes round down.
        (resolved.launch(accounts, atMs, selected) as? local.clashfarmer.core.Launch.Refused)?.let {
            throw IllegalStateException(it.reason)
        }
        if (resolved.launch(accounts, atMs + PendingStart.LATE_MS, selected) is local.clashfarmer.core.Launch.Refused) {
            val least = local.clashfarmer.core.RunPlan.MIN_END_BY_MINUTES + PendingStart.LATE_MS / 60_000L
            throw IllegalStateException("Pick an end time at least $least minutes after the start")
        }
        val start = PendingStart(resolved, atMs, now, names.joinToString(" then "), asSet = plan)
        synchronized(pendingLock) {
            pendingStart?.let {
                throw IllegalStateException("A start is already set for ${clockTime(it.atMs)}; cancel it first")
            }
            pendingStart = start
            pendingStore.write(start)
            pendingJob = serviceScope.launch {
                // The delay does not count while the phone sleeps, so the wall clock is reread
                // at least every slice of awake time; firePendingStart refuses a start woken late.
                while (true) {
                    val left = atMs - System.currentTimeMillis()
                    if (left <= 0) break
                    delay(minOf(left, PENDING_SLICE_MS))
                }
                // Inspect or the tray check holds Clash for one frame; a run started under it is
                // stopped when the app comes back. Wait it out while the start is still on time.
                while (inspecting && System.currentTimeMillis() < atMs + PendingStart.LATE_MS - 30_000L) delay(500)
                firePendingStart(start)
            }
        }
        overlay?.updateStatus("${start.label} starts at ${clockTime(atMs)}")
    }

    /** The Run screen's Cancel for the start set for later. */
    fun cancelPendingStart() {
        dropPendingStart("You cancelled it", notify = false)
    }

    private fun firePendingStart(start: PendingStart) {
        synchronized(pendingLock) {
            // A service replaced without being destroyed leaves its start to the new one's report.
            if (replaced || pendingStart !== start) return
            pendingStart = null
            pendingJob = null
            pendingStore.clear()
        }
        try {
            PendingStart.lateProblem(start.atMs, System.currentTimeMillis())?.let { throw IllegalStateException(it) }
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isInteractive != true) throw IllegalStateException("The phone's screen is off")
            if (!isDeviceUnlocked()) throw IllegalStateException("The phone is locked")
            if (inspecting) throw IllegalStateException(INSPECTING)
            val plan = start.plan
            if (plan.eventTroops && !local.clashfarmer.core.Policy.EVENT_ACTIVE) {
                throw IllegalStateException("Event troops are not available now")
            }
            val store = local.clashfarmer.accounts.AccountStore(filesDir)
            val accounts = store.load()
            val selected = runCatching { store.selectedId() }.getOrNull()
            val profiles = accounts.associate { it.id to runCatching { store.readProfile(it.id) }.getOrNull() }
            val now = System.currentTimeMillis()
            val zone = java.time.ZoneId.systemDefault()
            plan.readiness(accounts, profiles, connected = true, busy = isBusy, selectedId = selected, nowMs = now, zone = zone)
                .reason?.let { throw IllegalStateException(it) }
            val picked = plan.accountIds.map { id ->
                accounts.firstOrNull { it.id == id } ?: throw IllegalStateException("No such account")
            }
            when (val launch = plan.launch(accounts, now, selected, zone)) {
                is local.clashfarmer.core.Launch.Single -> startPlan(picked[0], launch)
                is local.clashfarmer.core.Launch.Combined -> startPlanCombined(picked[0], picked[1], launch)
                is local.clashfarmer.core.Launch.Refused -> throw IllegalStateException(launch.reason)
            }
            AppLog.log("scheduled_start", "account" to start.label, "at" to clockTime(start.atMs), "atMs" to start.atMs)
            runCatching { local.clashfarmer.core.PresetStore(filesDir).setLast(start.asSet) }
        } catch (e: Exception) {
            refusePendingStart(start, e.message ?: "It could not start", notify = true)
        }
    }

    /** Cancels the start set for later, if there is one, and logs why. */
    private fun dropPendingStart(reason: String, notify: Boolean) {
        val start = synchronized(pendingLock) {
            val pending = pendingStart ?: return
            pendingStart = null
            pendingJob?.cancel()
            pendingJob = null
            // A service replaced without being destroyed must not clear the new one's file.
            if (replaced) return
            pendingStore.clear()
            pending
        }
        refusePendingStart(start, reason, notify)
    }

    /**
     * True once another service has connected in this one's place without this one being
     * destroyed (see [remoteServer]). Null counts as not replaced: [instance] is null only after
     * this service's own onDestroy, or before any service connected.
     */
    private val replaced: Boolean get() = instance.let { it != null && it !== this }

    private fun refusePendingStart(start: PendingStart, reason: String, notify: Boolean) {
        val at = clockTime(start.atMs)
        AppLog.log("scheduled_start_refused", "account" to start.label, "at" to at, "atMs" to start.atMs, "reason" to reason)
        if (notify) {
            notifier?.post(RunNotice(RunNotice.Kind.NEEDS_YOU, "Scheduled start did not run", "${start.label} at $at: $reason"))
        }
    }

    /** A start by hand while a start set for later waits would leave that one to fail at its time. */
    private fun refuseWhilePending() {
        pendingStart?.let { throw IllegalStateException("A start is set for ${clockTime(it.atMs)}; cancel it first") }
    }

    /**
     * Desktop: the PC runs only Blood, the user's call on 24 October. A run on another account
     * would have the account check switch accounts in Clash.
     */
    private fun refuseOtherAccounts(vararg accounts: local.clashfarmer.accounts.AccountRecord) {
        accounts.firstOrNull { it.id != local.clashfarmer.accounts.AccountStore.BLOOD_ID }?.let {
            throw IllegalStateException("The PC runs only ${local.clashfarmer.accounts.AccountStore.BLOOD_VILLAGE_NAME}, not ${it.villageName}")
        }
    }

    private fun clockTime(ms: Long): String = local.clashfarmer.insight.Format.time(ms, java.time.ZoneId.systemDefault())

    /**
     * The notification's Pause. It never resumes: a second press before the notice redraws, or
     * one on a stale notice, leaves the run paused. Desktop: public, for the tray.
     */
    fun pauseFromNotification() {
        if (!isBusy) return
        // Pause pressed during the shade's hold: the run stays paused after the shade closes.
        if (claimPause()) {
            AppLog.log("pause_button", "paused" to true, "source" to "notification")
            return
        }
        if (runGuard.isPaused()) return
        runGuard.pause()
        // The guard ignores a pause while it is stopped between phases; log what took.
        AppLog.log("pause_button", "paused" to runGuard.isPaused(), "source" to "notification")
    }

    /**
     * The notification's Resume. It brings Clash back in front first, the way the dashboard's
     * Resume does, and lets the run continue once the game has had time to draw. Only the
     * person's own pause is resumed; the shade's hold lets go by itself when the shade closes.
     *
     * Desktop: public, for the tray, and the dashboard's Resume too, logged as [source]
     * "dashboard": the phone's brought Clash to the front first, and on the PC the only way back
     * is this same start. Clash is started only when its window is gone, since Google Play Games
     * may bring the game it starts to the front, and the start is a sidecar call, so it runs at
     * the head of the wait rather than before it.
     */
    fun resumeFromNotification(source: String = "notification") {
        if (!isBusy || !runGuard.isPaused() || isHeldBySystemWindow) return
        // A newer Resume replaces one still waiting for Clash to draw.
        notificationResume?.cancel()
        notificationResume = serviceScope.launch {
            if (getActiveForegroundPackage() != CLASH_PACKAGE) {
                try {
                    host.launch()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("FarmerService: starting Clash for Resume failed: ${e.message}")
                }
            }
            delay(RESUME_AFTER_MS)
            // On the main thread, so a window change is read either before this or after the hold.
            withContext(Dispatchers.Main) {
                if (isBusy && runGuard.isPaused()) {
                    val active = getActiveForegroundPackage()
                    val covered = AppSwitchLatch.shadeOver(active, isDeviceUnlocked())
                    if (!covered) runGuard.resume()
                    AppLog.log("pause_button", "paused" to false, "source" to source)
                    // Starting Clash may leave the shade down, and a run resumed under it fails its
                    // next capture; the hold takes the pause instead and lets go when the shade closes.
                    if (covered) holdForSystemWindow(active, "$source resume")
                }
            }
        }
    }

    /** Desktop: the notification's Stop, for the tray, with the reason the phone's receiver gave. */
    fun stopFromNotification() = stop(local.clashfarmer.core.UserStop.NOTIFICATION)

    /**
     * Desktop: Setup's Open Clash of Clans. Starts Clash through Google Play Games and returns at
     * once. Sends nothing while a run is active, since the run starts Clash itself, or while a
     * Clash window exists: a start into the running game left its input unanswered.
     */
    fun launchClash() {
        if (isBusy) return
        serviceScope.launch {
            withTimeoutOrNull(WINDOW_LOOKUP_WAIT_MS) { host.window.first { it.checked && it.error == null } }
            val status = host.window.value
            if (!status.checked || status.error != null || status.window != null) return@launch
            try {
                host.launch()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("FarmerService: starting Clash from Setup failed: ${e.message}")
            }
        }
    }

    /** The frame of the newest snapshot written, so one frame is saved once. */
    private var lastSnapshotFrameAt: Long? = null

    /** When the current run started; LatestFrame's older frame is the previous run's (D3 F4). */
    @Volatile
    private var runStartedAt: Long? = null

    private fun beginRunSnapshots() {
        runStartedAt = System.currentTimeMillis()
    }

    /**
     * Writes the newest frame the run already captured to files/captures as a PNG, for a failure
     * named [event]. Never takes a capture: the frame is LatestFrame's scaled copy (480x216).
     */
    fun saveFailureSnapshot(event: String) {
        val frame = latestFrame.scaled ?: return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!FailureSnapshot.shouldSave(frame.atMs, now, lastSnapshotFrameAt, runStartedAt)) return
            lastSnapshotFrameAt = frame.atMs
        }
        val name = FailureSnapshot.fileName(event, now)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "captures").apply { mkdirs() }
                java.io.FileOutputStream(File(dir, name)).use {
                    frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                AppLog.log("failure_snapshot", "trigger" to event, "file" to "captures/$name")
            } catch (e: Exception) {
                AppLog.log("failure_snapshot_failed", "trigger" to event, "error" to e.message)
            }
        }
    }

    /** Desktop: the battery as [refreshDeviceHealth] last read it. */
    @Volatile
    private var deviceHealth: DeviceHealth? = null

    /** Desktop: set while the battery can't be read, so the failure is logged once, not every refresh. */
    @Volatile
    private var healthFailed = false

    /**
     * Desktop: the laptop battery as last read, every [HEALTH_REFRESH_MS] in [serviceScope]. The
     * phone read its sticky battery broadcast on the spot; here the battery is a sidecar call away,
     * and runs and the remote page ask from threads that must not wait on it. Windows reports no
     * battery temperature, so the heat guard never trips. Null until the first reading, and while
     * the battery can't be read.
     */
    fun readDeviceHealth(): DeviceHealth? = deviceHealth

    /** Desktop: reads the battery through [host] for [readDeviceHealth]; a failure leaves null. */
    internal suspend fun refreshDeviceHealth() {
        deviceHealth = try {
            val power = host.power()
            healthFailed = false
            DeviceHealth(levelPercent = power.batteryPercent.takeIf { it >= 0 }, charging = power.ac, tempC = null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!healthFailed) AppLog.log("device_health_failed", "error" to e.message)
            healthFailed = true
            null
        }
    }

    fun startCombinedRun(
        account1: local.clashfarmer.accounts.AccountRecord,
        account2: local.clashfarmer.accounts.AccountRecord,
        quota1: Int,
        quota2: Int,
        globalDeadlineMinutes: Int = 120
    ) {
        if (isBusy) {
            throw IllegalStateException("A farming session is already active")
        }
        refuseWhilePending()
        refuseOtherAccounts(account1, account2)

        val combinedRunId = java.util.UUID.randomUUID().toString()
        beginRunSnapshots()
        val job = serviceScope.launch {
            AppLog.clearActiveAccount()
            AppLog.beginCombinedRun(combinedRunId, quota1 + quota2)
            try {
                // Each account ends on its own time limit at home; this is only the hard stop past it.
                kotlinx.coroutines.withTimeout(globalDeadlineMinutes * 60 * 1000L + local.clashfarmer.core.Policy.TIME_LIMIT_GRACE_MS) {
                    AppLog.log("combined_run_started",
                        "firstAccount" to account1.villageName,
                        "firstQuota" to quota1,
                        "secondAccount" to account2.villageName,
                        "secondQuota" to quota2,
                        "globalDeadlineMinutes" to globalDeadlineMinutes
                    )

                    // One count of server dialogs for the whole run, so the handoff does not reset it.
                    val serverSightings = ArrayDeque<Long>()
                    val driver = object : local.clashfarmer.accounts.AccountRunnerDriver {
                        override suspend fun farmAccount(
                            account: local.clashfarmer.accounts.AccountRecord,
                            quota: Int,
                            maxMinutes: Int
                        ): local.clashfarmer.core.RunOutcome {
                            // Account 2's snapshot must never show account 1's last frame.
                            beginRunSnapshots()
                            val timeLimitAt = android.os.SystemClock.elapsedRealtime() + maxMinutes * 60 * 1000L
                            return farmRestarting(account, quota, serverSightings) { remaining, resuming, tracker ->
                                runSingleAccountSession(
                                    account = account,
                                    supervised = false,
                                    wallOnly = false,
                                    overrideAttacks = remaining,
                                    overrideMaxMinutes = maxMinutes,
                                    resuming = resuming,
                                    wallTracker = tracker,
                                    timeLimitAt = timeLimitAt
                                )
                            }
                        }
                    }
                    val (first, second) = local.clashfarmer.accounts.AccountOrchestrator(driver) { completedBefore, account ->
                        AppLog.beginCombinedAccount(
                            completedBefore,
                            if (account.id == account1.id) account2.villageName else null
                        )
                    }.runCombined(account1, account2, quota1, quota2, globalDeadlineMinutes)
                    // A carry crosses from account 1 to account 2 (decision 8), but not past the job.
                    runGuard.stop("Combined run finished")

                    AppLog.log("combined_run_completed",
                        "firstCompleted" to first.completedAttacks, "firstReason" to first.reason.key,
                        "secondCompleted" to second.completedAttacks, "secondReason" to second.reason.key
                    )
                    val guarded = when (local.clashfarmer.core.CompletionReason.DEVICE_GUARD) {
                        first.reason -> account1 to first
                        second.reason -> account2 to second
                        else -> null
                    }
                    if (guarded != null) {
                        val notice = RunNotice.deviceGuard(guarded.first.villageName, guarded.second.detail)
                        overlay?.updateStatus(notice.text)
                        notifier?.post(notice)
                    } else {
                        overlay?.updateStatus("Combined run complete: both accounts finished")
                        notifier?.post(RunNotice(RunNotice.Kind.FINISHED, "Combined run finished",
                            "${account1.villageName}: ${first.completedAttacks} attacks" +
                                (first.detail?.let { " ($it)" } ?: "") + ", " +
                                "${account2.villageName}: ${second.completedAttacks} attacks" +
                                (second.detail?.let { " ($it)" } ?: "")))
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                stop("Global session deadline elapsed (${globalDeadlineMinutes}m + ${local.clashfarmer.core.Policy.TIME_LIMIT_GRACE_MS / 60_000}m grace)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (runGuard.isStopped()) {
                    AppLog.log("run_cancelled", "reason" to (runGuard.stopReason ?: "Cancelled"))
                } else {
                    stop("Session cancelled")
                }
            } catch (e: StopException) {
                stop(e.message ?: "Run stopped")
            } catch (e: Exception) {
                stop("Run error: ${e.message}")
            } finally {
                AppLog.clearCombinedRun()
                AppLog.clearActiveAccount()
            }
        }
        activeRunnerJob = job
        job.invokeOnCompletion {
            if (activeRunnerJob === job) {
                activeRunnerJob = null
            }
        }
        keepAwakeWhile(job)
    }

    fun startSwitchOnly(target: local.clashfarmer.accounts.AccountRecord) {
        if (isBusy) {
            throw IllegalStateException("A farming session is already active")
        }
        refuseOtherAccounts(target)

        val store = local.clashfarmer.accounts.AccountStore(filesDir)
        val saved = store.readProfile(target.id)

        beginRunSnapshots()
        val job = serviceScope.launch {
            try {
                AppLog.clearActiveAccount()
                // A hold under the shade moves the check's own deadlines on; this one allows its cap.
                kotlinx.coroutines.withTimeout(ACCOUNT_CHECK_MS + AppSwitchLatch.HOLD_CAP_MS) {
                    ensureClashForeground()
                    // Desktop: an account not yet calibrated is checked at the surface's own size,
                    // where the phone's default was the one display its switcher supports.
                    val profile = saved
                        ?: currentDisplayViewport()?.let { PhoneProfile.createDefault(it.width, it.height) }
                        ?: PhoneProfile.createDefault(1600, 720)
                    fitSurface(profile)
                    kotlin.coroutines.coroutineContext.ensureActive()
                    overlay?.updateStatus("Switching to ${target.villageName}...")

                    val switchToken = runGuard.startRun(
                        timeoutMs = ACCOUNT_CHECK_MS,
                        expectedViewport = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight),
                        width = profile.resolutionWidth,
                        height = profile.resolutionHeight
                    )
                    try {
                        ScreenReader().use { reader ->
                            local.clashfarmer.accounts.AccountSwitcher(this@FarmerService, reader)
                                .ensureAccount(target, switchToken)
                        }
                    } finally {
                        // The job's end, so no pause is carried: kept, an idle Pause pressed after
                        // it held the next Start, an unattended plan included, with no Resume
                        // shown (UO-21 review, 15 October).
                        runGuard.stop("Switch only completed")
                    }
                    overlay?.updateStatus("Verified: ${target.villageName}")
                    AppLog.log("switch_only_completed", "village" to target.villageName, "tag" to target.playerTag)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                stop("Switch timed out")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (runGuard.isStopped()) {
                    AppLog.log("run_cancelled", "reason" to (runGuard.stopReason ?: "Cancelled"))
                } else {
                    stop("Switch cancelled")
                }
            } catch (e: StopException) {
                stop(e.message ?: "Switch stopped")
            } catch (e: Exception) {
                stop("Switch error: ${e.message}")
            }
        }
        activeRunnerJob = job
        job.invokeOnCompletion {
            AppLog.clearActiveAccount()
            if (activeRunnerJob === job) {
                activeRunnerJob = null
            }
        }
        keepAwakeWhile(job)
    }

    /**
     * Holds while the floating panel's pause button is down, and reports where it stopped.
     *
     * A run is only ever held between actions, so nothing is captured and nothing is tapped
     * for as long as this waits. A stop while paused falls straight through: the caller's own
     * loop is watching the guard and will end the run on its next turn.
     */
    suspend fun awaitResume(where: String) {
        if (!runGuard.isPaused()) {
            // A hold the guard dropped at a phase edge is over, window change or not.
            if (systemWindowHold) withContext(Dispatchers.Main) { if (!runGuard.isPaused()) systemWindowHold = false }
            return
        }
        AppLog.log("run_paused", "at" to where)
        overlay?.updateStatus("Paused")
        val shade = isHeldBySystemWindow
        val since = android.os.SystemClock.elapsedRealtime()
        waitWhilePaused()
        if (shade) heldMs += android.os.SystemClock.elapsedRealtime() - since
        if (!runGuard.isStopped()) {
            AppLog.log("run_resumed", "at" to where)
            overlay?.updateStatus("Resuming...")
        }
    }

    /** Waits while the run is paused, and lets go of the shade's hold once Clash reads as in front. */
    private suspend fun waitWhilePaused() {
        while (runGuard.isPaused() && !runGuard.isStopped()) {
            if (systemWindowHold) withContext(Dispatchers.Main) { releaseHoldIfClashBack() }
            delay(500)
        }
    }

    fun startFarmingAccount(
        account: local.clashfarmer.accounts.AccountRecord,
        overrideAttacks: Int? = null
    ) {
        startGuardedAccountSession(
            account.copy(settings = local.clashfarmer.core.Policy.farmingRunSettings(account.settings)),
            supervised = false,
            wallOnly = false,
            overrideAttacks = overrideAttacks
        )
    }

    /**
     * Two hours of farming with walls bought once storage is 90% full, whatever the account's own
     * quota, time limit and wall switch say. The saved settings are not changed.
     */
    fun startTimedFarmingAccount(account: local.clashfarmer.accounts.AccountRecord) {
        startGuardedAccountSession(
            account.copy(settings = local.clashfarmer.core.Policy.timedRunSettings(account.settings)),
            supervised = false,
            wallOnly = false,
            overrideAttacks = local.clashfarmer.core.Policy.TIMED_RUN_ATTACKS,
            overrideMaxMinutes = local.clashfarmer.core.Policy.TIMED_RUN_MINUTES,
            overrideWallsEnabled = true
        )
    }

    fun startAttacksOnlyAccount(
        account: local.clashfarmer.accounts.AccountRecord,
        overrideAttacks: Int? = null
    ) {
        startGuardedAccountSession(
            account,
            supervised = false,
            wallOnly = false,
            overrideAttacks = overrideAttacks,
            overrideWallsEnabled = false
        )
    }

    /** Temporary: the farming run with the event army, battles never ended early. */
    fun startEventAccount(
        account: local.clashfarmer.accounts.AccountRecord,
        overrideAttacks: Int? = null
    ) {
        startGuardedAccountSession(
            account.copy(settings = local.clashfarmer.core.Policy.eventRunSettings(account.settings)),
            supervised = false,
            wallOnly = false,
            overrideAttacks = overrideAttacks,
            withEventTroops = true
        )
    }

    fun startSupervisedAccount(account: local.clashfarmer.accounts.AccountRecord) {
        startGuardedAccountSession(
            account,
            supervised = true,
            wallOnly = false,
            overrideAttacks = 1,
            overrideMaxMinutes = 15,
            overrideWallsEnabled = false
        )
    }

    fun startWallPhaseAccount(account: local.clashfarmer.accounts.AccountRecord) {
        account.settings.wallCapacityProblem()?.let { throw StopException(it) }
        startGuardedAccountSession(
            account,
            supervised = true,
            wallOnly = true,
            overrideMaxMinutes = 30
        )
    }

    /**
     * Starts a run composer launch on [account] through the same session start the run buttons
     * use. Every limit is passed as an explicit override, which the session resolves exactly as
     * it resolves the buttons' defaults. The saved settings are not changed.
     */
    fun startPlan(account: local.clashfarmer.accounts.AccountRecord, launch: local.clashfarmer.core.Launch) {
        val single = when (launch) {
            is local.clashfarmer.core.Launch.Single -> launch
            is local.clashfarmer.core.Launch.Refused -> throw StopException(launch.reason)
            is local.clashfarmer.core.Launch.Combined -> throw IllegalArgumentException("Both accounts start with startPlanCombined")
        }
        require(single.accountId == account.id) { "The launch is for another account" }
        if (single.wallOnly) single.settings.wallCapacityProblem()?.let { throw StopException(it) }
        startGuardedAccountSession(
            account.copy(settings = single.settings),
            supervised = single.supervised,
            wallOnly = single.wallOnly,
            overrideAttacks = single.attacks,
            overrideMaxMinutes = single.maxMinutes,
            overrideWallsEnabled = single.wallsEnabled,
            withEventTroops = single.withEventTroops
        )
    }

    /** Starts a both-accounts composer launch through [startCombinedRun]. The saved settings are not changed. */
    fun startPlanCombined(
        first: local.clashfarmer.accounts.AccountRecord,
        second: local.clashfarmer.accounts.AccountRecord,
        launch: local.clashfarmer.core.Launch.Combined
    ) {
        require(launch.firstId == first.id && launch.secondId == second.id) { "The launch is for other accounts" }
        startCombinedRun(
            first.copy(settings = launch.firstSettings),
            second.copy(settings = launch.secondSettings),
            launch.firstAttacks,
            launch.secondAttacks,
            launch.minutes
        )
    }

    private fun startGuardedAccountSession(
        account: local.clashfarmer.accounts.AccountRecord,
        supervised: Boolean,
        wallOnly: Boolean,
        overrideAttacks: Int? = null,
        overrideMaxMinutes: Int? = null,
        overrideWallsEnabled: Boolean? = null,
        withEventTroops: Boolean = false
    ) {
        if (isBusy) {
            throw IllegalStateException("A farming session is already active")
        }
        refuseWhilePending()
        refuseOtherAccounts(account)

        val maxMinutes = overrideMaxMinutes ?: (if (wallOnly) 30 else account.settings.maxMinutes)
        beginRunSnapshots()
        val job = serviceScope.launch {
            try {
                AppLog.clearActiveAccount()
                // The run ends itself at home once the limit passes; restarts keep the same limit.
                val timeLimitAt = android.os.SystemClock.elapsedRealtime() + maxMinutes * 60 * 1000L
                val finished = kotlinx.coroutines.withTimeout(maxMinutes * 60 * 1000L + local.clashfarmer.core.Policy.TIME_LIMIT_GRACE_MS) {
                    if (supervised || wallOnly) {
                        runSingleAccountSession(
                            account = account,
                            supervised = supervised,
                            wallOnly = wallOnly,
                            overrideAttacks = overrideAttacks,
                            overrideMaxMinutes = overrideMaxMinutes,
                            overrideWallsEnabled = overrideWallsEnabled,
                            withEventTroops = withEventTroops,
                            timeLimitAt = timeLimitAt
                        )
                    } else {
                        // Unattended farming picks itself up after a hiccup; a watched run does not.
                        farmRestarting(account, overrideAttacks ?: account.settings.attacks) { remaining, resuming, tracker ->
                            runSingleAccountSession(
                                account = account,
                                supervised = false,
                                wallOnly = false,
                                overrideAttacks = remaining,
                                overrideMaxMinutes = overrideMaxMinutes,
                                overrideWallsEnabled = overrideWallsEnabled,
                                withEventTroops = withEventTroops,
                                resuming = resuming,
                                wallTracker = tracker,
                                timeLimitAt = timeLimitAt
                            )
                        }
                    }
                }
                // The job is over, so a pause carried past its last finish has no run to hold.
                runGuard.stop("Session finished")
                val snapshot = AppLog.sessionSnapshot()
                notifier?.post(
                    if (finished?.reason == local.clashfarmer.core.CompletionReason.DEVICE_GUARD) {
                        RunNotice.deviceGuard(account.villageName, finished.detail)
                    } else {
                        RunNotice.finished(account.villageName,
                            if (wallOnly) null else finished?.completedAttacks,
                            snapshot.wallsUpgraded, snapshot.goldSpent, snapshot.elixirSpent, finished?.detail)
                    }
                )
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                stop("Session timeout elapsed (${maxMinutes}m + ${local.clashfarmer.core.Policy.TIME_LIMIT_GRACE_MS / 60_000}m grace)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (runGuard.isStopped()) {
                    AppLog.log("run_cancelled", "reason" to (runGuard.stopReason ?: "Cancelled"))
                } else {
                    stop("Session cancelled")
                }
            } catch (e: StopException) {
                stop(e.message ?: "Run stopped")
            } catch (e: Exception) {
                stop("Run error: ${e.message}")
            }
        }
        activeRunnerJob = job
        job.invokeOnCompletion {
            AppLog.clearActiveAccount()
            if (activeRunnerJob === job) {
                activeRunnerJob = null
            }
        }
        keepAwakeWhile(job)
    }

    private suspend fun runSingleAccountSession(
        account: local.clashfarmer.accounts.AccountRecord,
        supervised: Boolean,
        wallOnly: Boolean = false,
        overrideAttacks: Int? = null,
        overrideMaxMinutes: Int? = null,
        overrideWallsEnabled: Boolean? = null,
        withEventTroops: Boolean = false,
        /** Set on a restart after a hiccup, which must not take the village back from the iPhone. */
        resuming: Boolean = false,
        wallTracker: local.clashfarmer.core.WallPhaseTracker? = null,
        /** The session's time limit on the monotonic clock, kept across restarts. */
        timeLimitAt: Long? = null
    ): local.clashfarmer.core.RunOutcome? {
        val store = local.clashfarmer.accounts.AccountStore(filesDir)
        val profile = store.readProfile(account.id)
            ?: throw StopException("No calibration profile for ${account.villageName}")
        val profileFile = store.profileFile(account.id)

        val wallsEnabled = if (supervised) false else (overrideWallsEnabled ?: account.settings.wallsEnabled)
        if (wallOnly) profile.validateForWalls() else profile.validateForAttack()
        if (wallsEnabled && !wallOnly) profile.validateForWalls()

        val snapshotBefore = AppLog.sessionSnapshot()
        var completed = 0
        val attackLimit = if (wallOnly) 0 else (overrideAttacks ?: account.settings.attacks)
        // Checked before the account switch rather than after it, so a refused start costs nothing.
        if (!profile.isVerified && !local.clashfarmer.core.Policy.mayStartUnverified(supervised, attackLimit, wallOnly)) {
            throw StopException(local.clashfarmer.core.Policy.verificationRequired(account.villageName))
        }
        var resultStatus = "Failed"
        var resultReason: String? = null
        var outcome: local.clashfarmer.core.RunOutcome? = null
        AppLog.setAccountContext(account.id, account.villageName)
        // The log names the account from here, but the screen may show another village until
        // the switcher has verified this one, so frames name nobody until then.
        latestFrame.accountId = null
        verifiedAccountId = null
        try {
            ensureClashForeground()
            fitSurface(profile)
            kotlin.coroutines.coroutineContext.ensureActive()

            // Step 1: Verify identity and switch if needed
            overlay?.updateStatus("Verifying ${account.villageName} identity...")
            val switchToken = runGuard.startRun(
                timeoutMs = ACCOUNT_CHECK_MS,
                expectedViewport = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight),
                width = profile.resolutionWidth,
                height = profile.resolutionHeight
            )
            try {
                ScreenReader().use { reader ->
                    local.clashfarmer.accounts.AccountSwitcher(this, reader)
                        .ensureAccount(account, switchToken, reclaimTakeover = !resuming)
                }
            } finally {
                stopBetweenRuns("Account identity verified")
            }

            kotlin.coroutines.coroutineContext.ensureActive()
            latestFrame.accountId = account.id
            verifiedAccountId = account.id

            // Step 2: Run farming or walls
            val attacks = overrideAttacks ?: account.settings.attacks
            val maxMinutes = overrideMaxMinutes ?: (if (wallOnly) 30 else account.settings.maxMinutes)

            val runner = LocalRunner(
                service = this,
                profile = profile,
                isSupervised = supervised,
                maxAttacks = attacks,
                maxMinutes = maxMinutes,
                wallsEnabled = wallsEnabled,
                triggerPercent = account.settings.triggerPercent,
                resumePercent = account.settings.resumePercent,
                maxWallLevel = account.settings.maxWallLevel,
                reserveGold = account.settings.reserveGold,
                reserveElixir = account.settings.reserveElixir,
                profileFile = profileFile,
                currencyPreference = account.settings.currencyPreference,
                maxWallsPerPhase = account.settings.maxWallsPerPhase,
                profileUpdater = { update -> store.updateProfile(account.id, update) },
                goal = account.settings.goal,
                capacities = account.settings.capacities,
                opponent = account.settings.opponent,
                history = local.clashfarmer.history.BattleHistoryStore(java.io.File(filesDir, "history/battles")),
                accountId = account.id,
                storage = local.clashfarmer.core.StorageStore(filesDir),
                wallTracker = wallTracker ?: local.clashfarmer.core.WallPhaseTracker(),
                purchaseStore = local.clashfarmer.core.WallPurchaseStore(filesDir),
                collectBefore = !wallOnly && account.settings.collectBeforeFarming,
                collectAfter = !wallOnly && account.settings.collectAfterFarming,
                withEventTroops = withEventTroops,
                earlyEnd = account.settings.earlyEnd,
                timeLimitAt = timeLimitAt
            )

            if (wallOnly) {
                runner.runWalls()
                resultStatus = "Complete"
            } else {
                // A run that ends short of its quota, its goal, a full storage and its time limit throws; this is a normal finish.
                outcome = runner.run()
                completed = outcome.completedAttacks
                resultStatus = "Complete"
            }
            return outcome
        } catch (e: kotlinx.coroutines.CancellationException) {
            resultStatus = "Stopped"
            resultReason = runGuard.stopReason ?: if (e is kotlinx.coroutines.TimeoutCancellationException) {
                "Session deadline elapsed"
            } else {
                "Session cancelled"
            }
            throw e
        } catch (e: Exception) {
            resultStatus = if (e is StopException) "Stopped" else "Failed"
            resultReason = e.message
            throw e
        } finally {
            latestFrame.accountId = null
            recordAccountResult(
                account = account,
                completed = completed,
                limit = attackLimit,
                status = resultStatus,
                // A normal finish keeps no stop reason, but walls that failed or the device guard's
                // reading still need saying.
                reason = if (resultStatus == "Complete") outcome?.detail else resultReason,
                snapshotBefore = snapshotBefore,
                completionReason = outcome?.reason?.key
            )
            AppLog.clearAccountContext()
        }
    }

    /**
     * The account the last session's switcher verified, kept past the session's end for the
     * recovery after it; null from the start of a session's switch until it is verified.
     */
    private var verifiedAccountId: String? = null

    /**
     * Farms [quota] attacks on [account] through [attempt], and after a recoverable stop finds the
     * way back to the village and starts again with the attacks still owed.
     */
    private suspend fun farmRestarting(
        account: local.clashfarmer.accounts.AccountRecord,
        quota: Int,
        serverSightings: ArrayDeque<Long> = ArrayDeque(),
        attempt: suspend (remaining: Int, resuming: Boolean, tracker: local.clashfarmer.core.WallPhaseTracker) -> local.clashfarmer.core.RunOutcome?
    ): local.clashfarmer.core.RunOutcome {
        val wallTracker = local.clashfarmer.core.WallPhaseTracker()
        var before = AppLog.sessionSnapshot()
        var resuming = false
        restartableRun = true
        try {
            return local.clashfarmer.core.farmWithRestarts(
                quota = quota,
                attempt = { remaining ->
                    before = AppLog.sessionSnapshot()
                    attempt(remaining, resuming, wallTracker)
                        ?: throw StopException("${account.villageName} finished without an attack outcome")
                },
                completedIn = {
                    val now = AppLog.sessionSnapshot()
                    // A restart's session carries on the run's start and count, so only its own attacks.
                    if (now.sessionStartedAt != null && now.sessionStartedAt != before.sessionStartedAt) {
                        now.completed - now.completedBefore
                    } else 0
                },
                recover = { reason, restart, remaining ->
                    resuming = true
                    // A combined run's counter goes on from where it stood, not from the new session.
                    val context = AppLog.runContextSnapshot()
                    if (context.combinedRunId != null) {
                        AppLog.beginCombinedAccount(context.combinedCompleted, context.nextAccountName)
                    }
                    if (reason == local.clashfarmer.core.Policy.VILLAGE_TAKEN) {
                        waitForTakenVillage(account, remaining)
                    } else {
                        recoverToVillage(account, reason, restart, remaining)
                    }
                },
                serverSightings = serverSightings
            )
        } finally {
            restartableRun = false
        }
    }

    /**
     * Runs [block] with [account] on every line it logs, and clears it after. The takeover wait
     * and a recovery come between the account's sessions, after a session has cleared its own.
     * Each sets and clears its own here rather than farmRestarting holding one for the whole run,
     * so no line of a later account in a combined run can carry this one.
     */
    private inline fun <T> withAccountContext(account: local.clashfarmer.accounts.AccountRecord, block: () -> T): T {
        AppLog.setAccountContext(account.id, account.villageName)
        try {
            return block()
        } finally {
            AppLog.clearAccountContext()
        }
    }

    private suspend fun waitForTakenVillage(
        account: local.clashfarmer.accounts.AccountRecord,
        remaining: Int
    ) = withAccountContext(account) {
        val profile = local.clashfarmer.accounts.AccountStore(filesDir).readProfile(account.id)
            ?: throw StopException("No calibration profile for ${account.villageName}")
        val viewport = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight)
        stopBetweenRuns("Waiting for village")
        waitingForVillage = true
        val notice = RunNotice.takeoverWait(account.villageName, 10)
        notifier?.post(notice)
        // This guard makes Pause available while the game is backgrounded. The enclosing
        // withTimeout still owns the session deadline, including these ten minutes.
        runGuard.startRun(660_000L, viewport, profile.resolutionWidth, profile.resolutionHeight)
        try {
            AppLog.log("village_retry_wait", "account" to account.villageName,
                "waitMs" to 600_000L, "remaining" to remaining)
            local.clashfarmer.core.waitBeforeVillageRetry(
                background = {
                    if (!isDeviceUnlocked()) throw StopException("Device is locked")
                    if (!withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_HOME) }) {
                        throw StopException("Could not background Clash for village retry")
                    }
                },
                awaitResume = {
                    awaitResume("Waiting for village")
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (runGuard.isStopped()) throw StopException(runGuard.stopReason ?: "Run stopped")
                    if (!isDeviceUnlocked()) throw StopException("Device is locked")
                },
                remaining = { ms ->
                    overlay?.updateStatus("Village opened elsewhere; retry in ${(ms + 999) / 1000}s")
                }
            )
        } finally {
            stopBetweenRuns("Village wait ended")
            waitingForVillage = false
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        AppLog.log("village_retry_reopening", "account" to account.villageName)
        ensureClashForeground()
        fitSurface(profile)
        val token = runGuard.startRun(ACCOUNT_CHECK_MS, viewport, profile.resolutionWidth, profile.resolutionHeight)
        try {
            ScreenReader().use { reader ->
                local.clashfarmer.accounts.AccountSwitcher(this, reader)
                    .ensureAccount(account, token, reclaimTakeover = true)
            }
            // The run goes on, so the notice saying the village was taken no longer holds. One
            // posted since, or the stop of a takeover that ends the run, is left in the shade.
            val cleared = notifier?.withdraw(notice) == true
            AppLog.log("village_retry_verified", "account" to account.villageName, "remaining" to remaining,
                "noticeCleared" to cleared)
        } finally {
            stopBetweenRuns("Village retry finished")
        }
    }

    /**
     * Brings the game back to the village after a stop the run can recover from. Nothing is
     * bought or spent: a result screen gets Return Home, a battle is waited out, and a menu or a
     * window nothing reads gets Back. A popup [PopupGuard] knows is closed by its own tap, and
     * maintenance is waited out without input. The start that follows checks the account as any
     * start does.
     */
    private suspend fun recoverToVillage(
        account: local.clashfarmer.accounts.AccountRecord,
        reason: String,
        restart: Int,
        remaining: Int
    ) = withAccountContext(account) {
        val policy = local.clashfarmer.core.RecoveryPolicy
        stopBetweenRuns("Recovering: $reason")
        AppLog.log("run_auto_restart", "reason" to reason.take(300), "restart" to restart,
            "maxRestarts" to policy.MAX_RESTARTS, "remaining" to remaining)
        saveFailureSnapshot("auto_restart")
        overlay?.updateStatus("Recovering ($restart/${policy.MAX_RESTARTS})...")
        delay(3000)
        kotlin.coroutines.coroutineContext.ensureActive()

        val profile = local.clashfarmer.accounts.AccountStore(filesDir).readProfile(account.id)
            ?: throw StopException("No calibration profile for ${account.villageName}")
        ensureClashForeground()
        fitSurface(profile)
        val token = runGuard.startRun(
            // A maintenance break on the way back is waited out under this same token.
            timeoutMs = policy.RECOVERY_TIMEOUT_MS + PopupGuard.MAINTENANCE_MAX_MS + 30_000L,
            expectedViewport = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight),
            width = profile.resolutionWidth,
            height = profile.resolutionHeight
        )
        // Nothing on the way back changes the village, so its frames are the account's once its
        // session verified it. A stop inside the switch can leave the other village or the picker up.
        latestFrame.accountId = verifiedAccountId?.takeIf { it == account.id }
        try {
            ScreenReader().use { reader ->
                val walk = ReturnHome.forRecovery(System.currentTimeMillis(), reason)
                // The walk's clock leaves out time held under the shade or by a person's pause.
                val held0 = heldMs
                fun walkNow() = System.currentTimeMillis() - (heldMs - held0)
                while (true) {
                    // A pause is waited out before the frame, and a tap held by one (or on an old
                    // frame) sends nothing: the next pass reads the screen again.
                    awaitClearToAct("recovery")
                    val frame = transport.capture(token)
                    val obs = try {
                        reader.observe(frame.bitmap, profile.viewport, frame.timestampMs)
                    } finally {
                        frame.bitmap.recycle()
                    }
                    if (local.clashfarmer.core.Policy.anotherDeviceConnected(obs.rawText)) {
                        AppLog.log("village_taken_elsewhere", "text" to obs.rawText.take(200))
                        throw StopException(local.clashfarmer.core.Policy.VILLAGE_TAKEN)
                    }
                    val unknown = obs.state == local.clashfarmer.core.ScreenState.UNKNOWN
                    val popup = if (unknown) PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)
                        else PopupGuard.Verdict.None
                    val step = walk.next(
                        ReturnHome.Frame(
                            state = obs.state,
                            popup = popup,
                            reloadDialog = local.clashfarmer.recognition.reloadTarget(obs.boxes, obs.viewport) != null,
                            text = obs.rawText,
                            boxes = obs.boxes
                        ),
                        walkNow()
                    )
                    // The text says what an UNKNOWN was; on 14 October four minutes of them said nothing.
                    AppLog.log("recovery_step", "state" to obs.state.name, "step" to step.name,
                        "text" to if (unknown) obs.rawText.take(200) else null)
                    if (walk.clearedAfter > 0) {
                        AppLog.log("popup_dialogue_cleared", "advances" to walk.clearedAfter,
                            "state" to obs.state.name, "where" to "recovery")
                    }
                    when (step) {
                        ReturnHome.Step.Done,
                        ReturnHome.Step.LeaveToStart -> return@use
                        ReturnHome.Step.ReturnHome -> {
                            val button = local.clashfarmer.recognition.returnHomeTarget(
                                obs.boxes, obs.viewport, profile.stateAnchors["return_home"])
                            if (button != null && !sentOrHeld { transport.tapButton(button, token, decidedOn = obs.timestampMs) }) continue
                            delay(3000)
                        }
                        ReturnHome.Step.Back -> {
                            // Back for the store or an offer goes only if one more frame still shows it.
                            if ((popup == PopupGuard.Verdict.Shop || popup is PopupGuard.Verdict.RefusedPremium) &&
                                !shopStillUp("recovery", popup) {
                                    val again = transport.capture(token)
                                    try {
                                        reader.observe(again.bitmap, profile.viewport, again.timestampMs)
                                    } finally {
                                        again.bitmap.recycle()
                                    }
                                }) continue
                            // A withheld Back is waited out by the gate, which adds a person's pause
                            // to heldMs as well as the shade's; awaitResume added only the shade's,
                            // so a pause here ran down the walk's 240 s (UO-21 review, 15 October).
                            if (backIfClear(token, "recovery")) delay(2000) else {
                                awaitClearToAct("recovery")
                                delay(1000)
                            }
                        }
                        ReturnHome.Step.Wait -> delay(2000)
                        ReturnHome.Step.WaitOutMaintenance -> {
                            waitOutMaintenance(reader, token, profile, obs)
                            walk.restartClock(walkNow())
                        }
                        is ReturnHome.Step.ClosePopup -> {
                            if (!sentOrHeld { transport.tapButton(step.tap, token, decidedOn = obs.timestampMs) }) continue
                            AppLog.log("popup_dismissed", "popup" to step.popup, "x" to step.tap.x,
                                "y" to step.tap.y, "where" to "recovery", "text" to obs.rawText.take(200))
                            delay(1500)
                        }
                        is ReturnHome.Step.AdvanceDialogue -> {
                            if (!sentOrHeld { transport.tapButton(step.tap, token, decidedOn = obs.timestampMs) }) continue
                            AppLog.log("popup_dialogue_advanced", "x" to step.tap.x, "y" to step.tap.y,
                                "where" to "recovery", "text" to step.text.take(200))
                            delay(1500)
                        }
                        is ReturnHome.Step.CancelQuitPrompt ->
                            if (cancelQuitPrompt(step.tap, token, obs.timestampMs, "recovery", obs.rawText)) delay(1500) else delay(1000)
                        is ReturnHome.Step.Stop -> throw StopException(step.reason)
                    }
                }
            }
        } catch (e: StopException) {
            // A takeover gets its own wait in farmWithRestarts; it is not a failed recovery.
            if (e.message != local.clashfarmer.core.Policy.VILLAGE_TAKEN) saveFailureSnapshot("recovery_failed")
            throw e
        } finally {
            latestFrame.accountId = null
            stopBetweenRuns("Recovered to the village")
        }
        AppLog.log("run_recovered", "restart" to restart)
    }

    /**
     * Presses Back for a walk to the village, but only while [token]'s run may act. The capture
     * and the reading take about a second, and a stop, a pause or another app in front in that
     * time must not get a Back. The caller's next capture ends a stopped run.
     */
    fun backIfClear(token: Long, where: String): Boolean {
        val paused = runGuard.isPaused()
        val foreground = clashLooksForeground()
        val unlocked = isDeviceUnlocked()
        if (!ReturnHome.mayPressBack(runGuard.isTokenValid(token), paused, foreground, unlocked)) {
            AppLog.log("back_withheld", "where" to where, "stopped" to runGuard.isStopped(),
                "paused" to paused, "foreground" to foreground, "unlocked" to unlocked)
            return false
        }
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * One fresh frame before Back on the gem store or a priced offer, which [decided] was read off
     * an older frame. True only when [look]'s frame still shows it ([PopupGuard.stillShows]) and no
     * pause took effect while it was taken and read; otherwise nothing is sent and the caller reads
     * again. The 04:11:37 UTC store on 15 October was gone about 2 s later with no input, and Back
     * on the bare village opens Clash's quit prompt (UO-21 review). Only a frame already read as
     * the store or an offer gets this look.
     */
    suspend fun shopStillUp(
        where: String,
        decided: PopupGuard.Verdict,
        dialogue: Boolean = true,
        look: suspend () -> local.clashfarmer.recognition.Observation
    ): Boolean {
        val pauses = runGuard.pauses
        val fresh = look()
        if (runGuard.pauses != pauses) {
            AppLog.log("back_withheld", "where" to where, "pausedDuringLook" to true)
            return false
        }
        if (PopupGuard.stillShows(decided, fresh, dialogue)) return true
        AppLog.log("gem_shop_gone", "where" to where, "state" to fresh.state.name, "text" to fresh.rawText.take(200))
        return false
    }

    /**
     * Clash's quit prompt gets Cancel by its own box and never Okay, which quits the game; outside
     * the wall phase nothing answered it (UO-21 review, 15 October). [cancel] was read off the frame
     * taken at [decidedOn], and with it null nothing is sent. True when the tap went out. A tap held
     * by a pause or an old frame is not sent, and the caller reads again.
     */
    suspend fun cancelQuitPrompt(
        cancel: local.clashfarmer.core.Point?,
        token: Long,
        decidedOn: Long,
        where: String,
        text: String
    ): Boolean {
        if (cancel == null) {
            AppLog.log("quit_prompt_no_cancel", "where" to where, "text" to text.take(200))
            return false
        }
        if (!sentOrHeld { transport.tapButton(cancel, token, decidedOn = decidedOn) }) return false
        AppLog.log("quit_prompt_cancelled", "x" to cancel.x, "y" to cancel.y, "where" to where, "text" to text.take(200))
        return true
    }

    /**
     * Waits out a maintenance break on the way back without sending any input, Back included,
     * the same way LocalRunner does during a run, and ends it by the same [MaintenanceWait] rule:
     * the dialog gone on two reads 5 s apart, then a screen something acts on, so the clouds after
     * the break are not handed to the walk's Back. A break that outlasts
     * [PopupGuard.MAINTENANCE_MAX_MS] stops the run.
     */
    private suspend fun waitOutMaintenance(
        reader: ScreenReader,
        token: Long,
        profile: PhoneProfile,
        first: local.clashfarmer.recognition.Observation
    ) {
        val startedAt = System.currentTimeMillis()
        val wait = MaintenanceWait(startedAt)
        var pollMs = PopupGuard.MAINTENANCE_POLL_MS
        var lifting = false
        AppLog.log("maintenance_break", "where" to "recovery", "text" to first.rawText.take(200))
        overlay?.updateStatus("Clash is in maintenance; waiting without tapping...")
        while (true) {
            if (wait.timedOut(System.currentTimeMillis())) {
                AppLog.log("maintenance_timeout", "where" to "recovery",
                    "waitedMs" to System.currentTimeMillis() - startedAt)
                throw StopException(PopupGuard.maintenanceTimedOut(PopupGuard.MAINTENANCE_MAX_MS / 60_000L))
            }
            delay(pollMs)
            awaitResume("Maintenance")
            if (runGuard.isStopped()) throw StopException(runGuard.stopReason ?: "Run stopped")
            val frame = transport.capture(token)
            val obs = try {
                reader.observe(frame.bitmap, profile.viewport, frame.timestampMs)
            } finally {
                frame.bitmap.recycle()
            }
            val verdict = if (obs.state == local.clashfarmer.core.ScreenState.UNKNOWN) {
                PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)
            } else {
                PopupGuard.Verdict.None
            }
            val read = MaintenanceWait.classify(obs.state, verdict, obs.rawText,
                local.clashfarmer.recognition.reloadTarget(obs.boxes, obs.viewport) != null)
            when (val decision = wait.next(read, System.currentTimeMillis())) {
                MaintenanceWait.Decision.Over -> {
                    AppLog.log("maintenance_over", "where" to "recovery",
                        "waitedMs" to System.currentTimeMillis() - startedAt,
                        "state" to obs.state.name, "text" to obs.rawText.take(200))
                    return
                }
                is MaintenanceWait.Decision.Wait -> {
                    val nowLifting = read != MaintenanceWait.Read.MAINTENANCE
                    if (nowLifting != lifting) {
                        AppLog.log(if (nowLifting) "maintenance_lifting" else "maintenance_back",
                            "where" to "recovery", "state" to obs.state.name, "text" to obs.rawText.take(200))
                        lifting = nowLifting
                    }
                    pollMs = decision.pollMs
                }
            }
        }
    }

    private fun recordAccountResult(
        account: local.clashfarmer.accounts.AccountRecord,
        completed: Int,
        limit: Int,
        status: String,
        reason: String?,
        snapshotBefore: local.clashfarmer.core.SessionSnapshot,
        completionReason: String? = null
    ) {
        try {
            val snapshot = AppLog.sessionSnapshot()
            // A restart's session keeps the run's start; its own start tells it apart. Walls and
            // spend are then the run's so far, and the attacks this session's, against its limit.
            val belongsToThisRun = snapshot.sessionStartedAt != null && snapshot.sessionStartedAt != snapshotBefore.sessionStartedAt
            val result = local.clashfarmer.accounts.AccountLastResult(
                timestamp = System.currentTimeMillis(),
                attacksCompleted = if (belongsToThisRun) maxOf(completed, snapshot.completed - snapshot.completedBefore) else completed,
                attackLimit = limit,
                wallsUpgraded = if (belongsToThisRun) snapshot.wallsUpgraded else 0,
                goldSpent = if (belongsToThisRun) snapshot.goldSpent else 0,
                elixirSpent = if (belongsToThisRun) snapshot.elixirSpent else 0,
                purchaseOutcomeUnknown = belongsToThisRun && snapshot.purchaseOutcomeUnknown,
                unknownPurchaseCurrency = if (belongsToThisRun) snapshot.unknownPurchaseCurrency else null,
                unknownPurchaseCost = if (belongsToThisRun) snapshot.unknownPurchaseCost else 0,
                unknownPurchaseWalls = if (belongsToThisRun) snapshot.unknownPurchaseWalls else 0,
                status = status,
                stopReason = reason,
                completionReason = completionReason
            )
            val store = local.clashfarmer.accounts.AccountStore(filesDir)
            val loaded = store.load().firstOrNull { it.id == account.id }
            if (loaded != null) {
                store.update(loaded.copy(lastResult = result))
            }
        } catch (e: Exception) {
            AppLog.log("record_account_result_failed", "error" to e.message)
        }
    }

    /** Legacy compatibility method */
    fun startFarming(
        profile: PhoneProfile,
        supervised: Boolean,
        maxAttacks: Int = 1,
        maxMinutes: Int = 120,
        wallsEnabled: Boolean = false,
        triggerPercent: Int = 90,
        resumePercent: Int = 75,
        maxWallLevel: Int = local.clashfarmer.core.Policy.DEFAULT_MAX_WALL_LEVEL,
        reserveGold: Long = local.clashfarmer.core.Policy.DEFAULT_RESERVE_GOLD,
        reserveElixir: Long = local.clashfarmer.core.Policy.DEFAULT_RESERVE_ELIXIR,
        profileFile: File? = null
    ) {
        if (isBusy) {
            throw IllegalStateException("A farming session is already active")
        }
        if (wallsEnabled) {
            throw StopException("Wall upgrades require an account configuration with capacities; use startFarmingAccount")
        }

        beginRunSnapshots()
        activeRunnerJob = serviceScope.launch {
            try {
                val runner = LocalRunner(
                    service = this@FarmerService,
                    profile = profile,
                    isSupervised = supervised,
                    maxAttacks = maxAttacks,
                    maxMinutes = maxMinutes,
                    wallsEnabled = false,
                    triggerPercent = triggerPercent,
                    resumePercent = resumePercent,
                    maxWallLevel = maxWallLevel,
                    reserveGold = reserveGold,
                    reserveElixir = reserveElixir,
                    profileFile = profileFile
                )
                runner.run()
            } catch (e: Exception) {
                stop("Run error: ${e.message}")
            }
        }
        activeRunnerJob?.invokeOnCompletion {
            activeRunnerJob = null
        }
        activeRunnerJob?.let { keepAwakeWhile(it) }
    }

    /** Legacy compatibility method */
    fun startWallPhase(profile: PhoneProfile, maxMinutes: Int = 30, profileFile: File? = null) {
        if (isBusy) {
            throw IllegalStateException("A farming session is already active")
        }
        throw StopException("Wall upgrades require an account ID and storage capacities; use startWallPhaseAccount")
    }

    /**
     * Whether Clash is the app on screen, by the best evidence available.
     *
     * A read package that says Clash is proof. Failing that, nothing else may be in front: no
     * third-party package has announced itself, and this app's own screen is not showing.
     */
    fun clashLooksForeground(): Boolean {
        // The helper's own word comes first. A run started from it begins while the window list
        // still names Clash, and on 23 September that read as Clash being in front: Clash was
        // never launched and the run waited out the rotation check with the helper on screen.
        if (helperActivityVisible) return false
        val pkg = getActiveForegroundPackage()
        if (pkg == CLASH_PACKAGE) return true
        return (pkg == null || pkg == packageName) && !helperActivityVisible
    }

    suspend fun ensureClashForeground(timeoutMs: Long = 90_000) {
        val deadline = System.currentTimeMillis() + timeoutMs

        // Desktop: the read decides, not clashLooksForeground, which also passes a read of
        // nothing. On the phone that was Clash drawing with no node tree; on the PC it is no
        // Clash window at all. Before the watcher's first lookup, and while the sidecar restarts,
        // the read is nothing even with Clash open, and starting it again may bring it to the
        // front, so the read waits for a lookup that worked.
        withTimeoutOrNull(WINDOW_LOOKUP_WAIT_MS) { host.window.first { it.checked && it.error == null } }
        if (getActiveForegroundPackage() != CLASH_PACKAGE) {
            try {
                host.launch()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StopException("Clash of Clans could not be started through Google Play Games: ${e.message}")
            }
        }

        while (getActiveForegroundPackage() != CLASH_PACKAGE) {
            if (System.currentTimeMillis() >= deadline) {
                throw StopException(
                    "Timed out waiting for Clash of Clans ($CLASH_PACKAGE) to enter foreground " +
                        "(read: ${getActiveForegroundPackage()}, helper on screen: $helperActivityVisible)"
                )
            }
            delay(500)
        }

        // Desktop: the phone waited here for the window read to name Clash (awaitClashRead) once
        // its helper screen had gone. The loop above already has.

        // The window exists long before the game draws anything; it stays black while loading.
        while (System.currentTimeMillis() < deadline) {
            val frame = transport.capture()
            val blank = try {
                ImageMatcher.isBlankFrame(frame.bitmap)
            } finally {
                frame.bitmap.recycle()
            }
            if (!blank) return
            delay(1000)
        }
        throw StopException("Clash of Clans was still drawing a blank screen after ${timeoutMs}ms")
    }

    /**
     * Desktop: one frame of Clash. The PC has no status bar or rotation to wait out before it, so
     * the phone's wait for Clash to fill the display is gone, and no helper screen to reopen after
     * it, so [returnToActivityClass] is not used.
     */
    suspend fun captureClashGame(returnToActivityClass: Class<*>? = null): CapturedImage {
        ensureClashForeground()
        return transport.capture()
    }

    /**
     * Puts the village camera at its limits and captures it, for the frame collection targets
     * are calibrated on. Holds a token of its own so Stop, the screen and the app-switch latches
     * still apply, and refuses anything but the village with nothing selected. Desktop: no helper
     * screen is reopened after it, so [returnToActivityClass] is not used.
     */
    suspend fun captureNormalizedVillage(profile: PhoneProfile, returnToActivityClass: Class<*>? = null): CapturedImage {
        if (isBusy) throw IllegalStateException("A farming session is already active")
        ensureClashForeground(timeoutMs = 90_000)
        fitSurface(profile)
        val expected = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight)
        val rotateDeadline = System.currentTimeMillis() + 60_000L
        while (currentDisplayViewport() != expected && System.currentTimeMillis() < rotateDeadline) delay(500)
        beginRunSnapshots()
        val token = runGuard.startRun(
            timeoutMs = 60_000L,
            expectedViewport = expected,
            width = profile.resolutionWidth,
            height = profile.resolutionHeight
        )
        val captured = try {
            ScreenReader().use { reader ->
                // A pause that left the village behind holds the rest of the camera gestures; the
                // village is read again and the normalisation starts over.
                do {
                    val first = transport.capture(token)
                    val state = try { reader.observe(first.bitmap, profile.viewport, first.timestampMs).state }
                        finally { first.bitmap.recycle() }
                    if (state != local.clashfarmer.core.ScreenState.HOME) {
                        throw StopException("The village is not on screen (read $state); open it with nothing selected")
                    }
                    val normalized = sentOrHeld {
                        local.clashfarmer.core.normalizeVillageCamera(this, profile.viewport, token, afterPause = {
                            local.clashfarmer.core.villageStillUp(this, reader, profile.viewport, token)
                        })
                    }
                } while (!normalized)
            }
            transport.capture(token)
        } finally {
            // The job's end: no pause is carried into the next Start (UO-21 review, 15 October).
            runGuard.stop("Camera reference captured")
        }
        return captured
    }

    fun getActiveForegroundPackage(): String? = readForeground().first

    /**
     * [getActiveForegroundPackage]'s answer and the source that gave it, for the hold's log.
     * Desktop: Clash while its window exists, minimized or behind other windows, since the capture
     * restores a minimized window without focus and input goes to the window directly. Without
     * one the read is null; the PC sees no other app's window.
     */
    private fun readForeground(): Pair<String?, String> =
        if (host.window.value.window != null) CLASH_PACKAGE to "window" else null to "no window"

    /**
     * Desktop: the Clash window as "clash title flags size dpi", or why there is none, for a hold
     * that has not let go. Diagnostics only; the PC sees no window but Clash's.
     */
    private fun windowSummary(): String {
        val status = host.window.value
        val win = status.window
        return when {
            win != null -> listOfNotNull("clash", win.title, "minimized".takeIf { win.iconic },
                "foreground".takeIf { win.foreground }, "${win.width}x${win.height}", "dpi ${win.monitorDpi}").joinToString(" ")
            status.error != null -> "unreadable: ${status.error}"
            !status.checked -> "not looked up yet"
            else -> "none"
        }
    }

    /**
     * Real display geometry, so a rotation or resize cannot slip past the run guard. Desktop: the
     * game surface's size, kept while the window is minimized; null with no window.
     */
    fun currentDisplayViewport(): Viewport? =
        host.window.value.surface?.let { Viewport(0, 0, it.width, it.height) }

    /**
     * Desktop: sizes the game surface back to the size [profile] was calibrated at, which the run
     * guard holds the run to, so a window sized by hand or opened at another size doesn't stop the
     * run. Google Play Games keeps the aspect it chose at launch, so a surface of another aspect is
     * left for the guard to refuse. A resize kept taps landing in the spike, where a relaunch did
     * not (docs/gpg-spike.md, Restarting).
     */
    internal suspend fun fitSurface(profile: PhoneProfile) {
        val surface = host.window.value.surface ?: return
        val width = profile.resolutionWidth
        val height = profile.resolutionHeight
        if (surface.width == width && surface.height == height) return
        val from = "${surface.width}x${surface.height}"
        val stray = surface.width.toDouble() / surface.height * height / width - 1
        if (kotlin.math.abs(stray) > SAME_ASPECT_TOLERANCE) {
            AppLog.log("surface_aspect_differs", "surface" to from, "profile" to "${width}x$height")
            return
        }
        val reached = try {
            host.resize(width, height)
        } catch (e: SidecarException) {
            AppLog.log("surface_resize_failed", "surface" to from, "error" to e.message)
            return
        }
        AppLog.log("surface_resized", "from" to from, "to" to "${reached.width}x${reached.height}")
        withTimeoutOrNull(RESIZE_SEEN_MS) { host.window.first { it.surface == reached } }
    }

    /**
     * Desktop: always true. The PC has no lock screen a run can read; a locked or sleeping PC is
     * left to the run's captures, which stop it when they fail.
     */
    fun isDeviceUnlocked(): Boolean = true

    /** Desktop: called when the app shuts the service down, where Android destroyed it. */
    fun onDestroy() {
        // A busy destroy keeps the file for the next connect to report: stop() posts its own
        // notice on the same id, which would hide this one.
        if (!isBusy) dropPendingStart("The helper service shut down", notify = true)
        stop("Service destroyed")
        // The ongoing notice shares one id across services; a replaced one leaves the new one's.
        if (!replaced) notifier?.cancelLive()
        if (instance === this) {
            remoteServer?.stop()
            remoteServer = null
        }
        latestFrame.clear()
        // The stopped run's hold on keeping the PC awake is let go from its job's end, on
        // awakeScope, which this does not cancel.
        serviceScope.cancel()
        overlay?.dismiss()
        overlay = null
        if (instance === this) instance = null
        AppLog.log("service_destroyed")
    }

    /** Desktop: the phone's application id, which [AppSwitchLatch] takes as the helper's own package. */
    val packageName = "local.clashfarmer"

    /** Desktop: the phone's APK assets ship as classpath resources. */
    val assets = ClasspathAssets

    /**
     * Desktop: RemoteServer and a start set for later ask it for `Context.POWER_SERVICE`, a
     * PowerManager whose isInteractive was the phone's screen being on. Always true here: runs
     * keep the PC awake, and a capture that fails stops a run instead.
     */
    fun getSystemService(name: String): Any? = if (name == Context.POWER_SERVICE) awakePower else null

    private val awakePower = object : PowerManager {
        override val isInteractive = true
    }

    /**
     * Desktop: Back goes to the game window through [transport]. Home, the phone's only other
     * global action here, came from the takeover wait, where it put Clash in the background
     * without stopping it. On the PC it does nothing: Clash stays on the takeover dialog, which
     * holds the game just as the background did, and the account check after the wait presses its
     * Reload. Closing the window instead and starting Clash again brought it back with posted
     * input broken: taps did nothing, and after the link was sent again they landed 40 to 75 px
     * above their targets (docs/gpg-spike.md, Restarting). True, as Android answered once the
     * action was dispatched.
     */
    fun performGlobalAction(action: Int): Boolean = when (action) {
        GLOBAL_ACTION_BACK -> transport.back()
        GLOBAL_ACTION_HOME -> true
        else -> false
    }

    /** Desktop: run jobs holding the PC awake; see [keepAwakeWhile]. */
    private val awakeRuns = AtomicInteger()
    private val awakeLock = Mutex()

    /** Desktop: not [serviceScope], so the release after onDestroy's stop still goes out once that scope is cancelled. */
    private val awakeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Desktop: holds the system and the display awake while [job] runs. The phone held no wake
     * lock and stayed on while it charged; a PC left alone sleeps after its idle timeout, which
     * would end the run. Counted, since one run's end can land after the next one's start.
     */
    private fun keepAwakeWhile(job: Job) {
        awakeRuns.incrementAndGet()
        sendKeepAwake()
        job.invokeOnCompletion {
            awakeRuns.decrementAndGet()
            sendKeepAwake()
        }
    }

    /** Desktop: tells [host] what [awakeRuns] wants now, one call at a time, so the last call sent is the current want. */
    private fun sendKeepAwake() {
        awakeScope.launch {
            awakeLock.withLock {
                val on = awakeRuns.get() > 0
                try {
                    host.keepAwake(on)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("FarmerService: keeping the PC awake ($on) failed: ${e.message}")
                }
            }
        }
    }
}

private const val CLASH_READ_POLL_MS = 250L

/**
 * Reads the foreground with [read] until it names Clash or [boundMs] has passed, and returns the
 * last read. Past the bound the caller carries on, and the run guard judges the read as before.
 */
internal suspend fun awaitClashRead(
    read: () -> String?,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
    boundMs: Long
): String? {
    val until = now() + boundMs
    var pkg = read()
    while (pkg != FarmerService.CLASH_PACKAGE && now() < until) {
        pause(CLASH_READ_POLL_MS)
        pkg = read()
    }
    return pkg
}
