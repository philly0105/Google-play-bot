package local.clashfarmer.service

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.core.AppSwitchLatch
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.ReturnHome
import local.clashfarmer.core.RunGuard
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.sentOrHeld
import local.clashfarmer.desktop.platform.ClasspathAssets
import local.clashfarmer.log.AppLog
import local.clashfarmer.log.FailureSnapshot
import local.clashfarmer.recognition.PhoneProfile
import java.io.File

/**
 * Desktop port of the phone's AccessibilityService: a plain class with the phone's members, so
 * the copied run code calls it unchanged (docs/PLAN.md, class 3).
 *
 * Simple members are the phone's, line for line, the thin start methods included. Bodies that
 * drive the platform or a run are `TODO("stream B: ...")`, each saying what the phone's does: the
 * service's start and end, the sessions the start methods launch, pending starts, the foreground
 * and display reads, and the lock and battery checks. Stream B ports them from the phone file at
 * eeaadba, together with the private helpers only they use, which are left out here. So are the
 * phone's broadcast receivers (screen off, the notification's buttons), onAccessibilityEvent and
 * onInterrupt, which have no desktop counterpart yet.
 *
 * [packageName], [assets], [getSystemService] and [performGlobalAction] stand in for the Android
 * members the copied code reads.
 */
class FarmerService(
    /** The app's files directory, `%LOCALAPPDATA%\ClashFarmerPC\files` on the PC; the phone's Context.filesDir. */
    val filesDir: File
) {

    companion object {
        const val CLASH_PACKAGE = "com.supercell.clashofclans"
        /** The account check, with room for the walk back to the village that comes before it. */
        const val ACCOUNT_CHECK_MS = 180_000L + local.clashfarmer.core.ReturnHome.START_BOUND_MS

        /** Why a start is refused while Inspect or the tray check holds Clash on screen. */
        const val INSPECTING = "The app is reading the game screen for Inspect or the tray check; try again when it ends"

        @Volatile
        var instance: FarmerService? = null
            private set

        fun isConnected(): Boolean = instance != null
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

    fun onServiceConnected(): Unit =
        TODO("stream B: the phone sets instance and transport, starts AppLog, prunes storage, makes the notifier, reports a start left on disk, shows the overlay, starts the remote server with assets' remote.html, and registers the screen-off and notification receivers")

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
    fun scheduleStart(plan: local.clashfarmer.core.RunPlan, atMs: Long): Unit =
        TODO("stream B: the phone checks the plan and its end time as of atMs, saves it as pendingStart, and waits in serviceScope until atMs to start it, rereading the wall clock every 30 s")

    /** The Run screen's Cancel for the start set for later. */
    fun cancelPendingStart(): Unit =
        TODO("stream B: the phone drops the start set for later with the reason \"You cancelled it\" and no notice")

    /** Cancels the start set for later, if there is one, and logs why. */
    private fun dropPendingStart(reason: String, notify: Boolean): Unit =
        TODO("stream B: the phone cancels the pending start's job, clears its file unless the service was replaced, and reports the refusal with the reason")

    /** The frame of the newest snapshot written, so one frame is saved once. */
    private var lastSnapshotFrameAt: Long? = null

    /** When the current run started; LatestFrame's older frame is the previous run's (D3 F4). */
    @Volatile
    private var runStartedAt: Long? = null

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

    /** The sticky battery broadcast, read without registering a receiver. Null when unavailable. */
    fun readDeviceHealth(): DeviceHealth? =
        TODO("stream B: the phone reads the sticky battery broadcast into DeviceHealth.fromExtras, and logs device_health_failed and returns null on an error; PLAN.md maps it to the laptop battery")

    fun startCombinedRun(
        account1: local.clashfarmer.accounts.AccountRecord,
        account2: local.clashfarmer.accounts.AccountRecord,
        quota1: Int,
        quota2: Int,
        globalDeadlineMinutes: Int = 120
    ): Unit =
        TODO("stream B: the phone refuses while busy or a start is pending, then farms both accounts in serviceScope through AccountOrchestrator, each restarting after a hiccup, under one deadline plus grace, and posts the result notice")

    fun startSwitchOnly(target: local.clashfarmer.accounts.AccountRecord): Unit =
        TODO("stream B: the phone refuses while busy, brings Clash to the front and runs AccountSwitcher.ensureAccount for target under a run token of its own, within ACCOUNT_CHECK_MS plus the hold cap")

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
    ): Unit =
        TODO("stream B: the phone refuses while busy or a start is pending, then runs runSingleAccountSession in serviceScope, through farmRestarting for unattended farming, under maxMinutes plus grace, and posts the result notice")

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
    ): Unit =
        TODO("stream B: the phone's legacy start refuses walls, then runs one LocalRunner on profile in serviceScope")

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

    suspend fun ensureClashForeground(timeoutMs: Long = 90_000): Unit =
        TODO("stream B: the phone launches Clash unless clashLooksForeground, waits up to timeoutMs for it and for the window read to name it (awaitClashRead), then captures until a frame is not blank")

    suspend fun captureClashGame(returnToActivityClass: Class<*>? = null): CapturedImage =
        TODO("stream B: the phone brings Clash up filling the display, captures one frame, and reopens returnToActivityClass when given")

    /**
     * Puts the village camera at its limits and captures it, for the frame collection targets
     * are calibrated on. Holds a token of its own so Stop, the screen and the app-switch latches
     * still apply, and refuses anything but the village with nothing selected.
     */
    suspend fun captureNormalizedVillage(profile: PhoneProfile, returnToActivityClass: Class<*>): CapturedImage =
        TODO("stream B: the phone checks the village is on screen, normalizes its camera under a 60 s run token, captures it, and reopens returnToActivityClass")

    fun getActiveForegroundPackage(): String? = readForeground().first

    /** [getActiveForegroundPackage]'s answer and the source that gave it, for the hold's log. */
    private fun readForeground(): Pair<String?, String> =
        TODO("stream B: the phone names the package of the active or focused application window, else of rootInActiveWindow, else the last one a window change announced, with which source answered")

    /**
     * Every window as "type package title flags bounds", for a hold that has not let go: it shows
     * whether the shade's window is still there once the shade looks closed. Diagnostics only.
     */
    private fun windowSummary(): String =
        TODO("stream B: the phone lists every window as type, package, title, active, focused and bounds")

    /** Real display geometry, so a rotation or resize cannot slip past the run guard. */
    fun currentDisplayViewport(): Viewport? =
        TODO("stream B: the phone returns the window metrics' bounds as Viewport(0, 0, width, height), or null when unreadable")

    fun isDeviceUnlocked(): Boolean =
        TODO("stream B: the phone returns true when the keyguard is not locked")

    fun onDestroy(): Unit =
        TODO("stream B: the phone drops an idle pending start, stops the run, unregisters the receivers, cancels the live notice, stops the remote server, clears latestFrame, cancels serviceScope, dismisses the overlay and clears instance")

    /** Desktop: the phone's application id, which [AppSwitchLatch] takes as the helper's own package. */
    val packageName = "local.clashfarmer"

    /** Desktop: the phone's APK assets ship as classpath resources. */
    val assets = ClasspathAssets

    /** Desktop: RemoteServer asks it for `Context.POWER_SERVICE`, a PowerManager. */
    fun getSystemService(name: String): Any? =
        TODO("stream B: the phone returns its PowerManager, whose isInteractive is whether the screen is on; PLAN.md maps screen off to the PC sleeping or capture stopping")

    /**
     * Desktop: Back goes to the game window through [transport]. Home, the phone's only other
     * global action here, came from the takeover wait.
     */
    fun performGlobalAction(action: Int): Boolean =
        if (action == GLOBAL_ACTION_BACK) transport.back()
        else TODO("stream B: the phone's Home put Clash in the background for the takeover wait")
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
