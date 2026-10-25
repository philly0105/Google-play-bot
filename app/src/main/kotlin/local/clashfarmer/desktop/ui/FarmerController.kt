package local.clashfarmer.desktop.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.core.Launch
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.UserStop
import local.clashfarmer.core.WallPurchaseStore
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.ui.calibration.CalibrationModel
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.service.FarmerService
import local.clashfarmer.ui.DashboardLoader
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.screens.ComposerState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [loaded] with only the account the PC runs, [PcAccount.ID]. Every screen and every start reads
 * the accounts through this, so the owner's second phone account never sits in Setup or Activity;
 * its record and files stay where AccountStore keeps them, and FarmerService refuses it whatever asks.
 */
internal fun pcAccounts(loaded: DashboardLoader.Accounts) = loaded.copy(
    accounts = loaded.accounts.filter { it.id == PcAccount.ID },
    profiles = loaded.profiles.filterKeys { it == PcAccount.ID }
)

/** What an action names an account that is no longer in the store. */
internal const val ACCOUNT_GONE = "That account is gone. Choose one on Home."

/** What a failure the controller did not word itself says: no exception text. */
internal const val SOMETHING_WENT_WRONG = "Something went wrong. The Technical log on the Activity tab has the details."

/**
 * The alert's title and message when [action] fails with [error]. The controller and the checks
 * it calls throw IllegalStateException and IllegalArgumentException with sentences meant for the
 * user, which show as they are; any other exception is a fault, logged in full by the caller,
 * and the alert sends the user to the Technical log instead of showing its text.
 */
internal fun actionFailure(action: String, error: Throwable): Pair<String, String> {
    val title = when {
        action.startsWith("acknowledge_wall:") -> "Could not clear the marker"
        action.startsWith("select:") || action.startsWith("switch_only:") -> "Could not choose that account"
        action.startsWith("plan:") || action == "supervised" || action == "walls" -> "Could not start the run"
        else -> "Something went wrong"
    }
    val worded = (error is IllegalStateException || error is IllegalArgumentException) && !error.message.isNullOrBlank()
    val message = if (worded) error.message!!.trim().let { if (it.last() in ".!?") it else "$it." } else SOMETHING_WENT_WRONG
    return title to message
}

/**
 * A screen state's enum name as the inspect report says it: "HOME" reads "the village". A name
 * this list lacks is lowercased: "VILLAGE_HOME" reads "village home".
 */
internal fun readableState(name: String): String = when (name) {
    "HOME" -> "the village"
    "MENU" -> "the attack menu"
    "ARMY" -> "the army screen"
    "SCOUT" -> "a base to attack (scout screen)"
    "SEARCHING" -> "searching for an opponent"
    "BATTLE" -> "a battle"
    "REWARD" -> "a reward to pick"
    "RESULT" -> "the battle result"
    "WALL" -> "a wall's upgrade panel"
    "UNKNOWN" -> "a screen the bot doesn't know"
    else -> name.lowercase(Locale.ROOT).replace('_', ' ')
}

/**
 * Inspect's report: what the bot takes the screen for, in words, then under Details the raw
 * reading a bug report needs: the state's name, the game area, and the text read, cut at 400
 * characters.
 */
internal fun inspectReport(observation: Observation, imageName: String): String {
    val vp = observation.viewport
    val text = observation.rawText.take(400) + if (observation.rawText.length > 400) "..." else ""
    return listOf(
        "The game shows ${readableState(observation.state.name)}.",
        "Saved picture: $imageName",
        "",
        "Details",
        "State: ${observation.state.name}",
        "Game area: [${vp.left}, ${vp.top}, ${vp.right}, ${vp.bottom}]",
        "Pieces of text read: ${observation.boxes.size}",
        "",
        "Text read:",
        text
    ).joinToString("\n")
}

/** A message at the foot of the window, the phone's Toast. A class, so two equal texts are two toasts. */
class ToastMessage(val text: String, val long: Boolean)

/**
 * An alert over the window, the phone's AlertDialog: [confirm] runs [onConfirm], [dismiss] (when
 * set), Escape and a click outside only close it. [monospace] sets the message as a report.
 */
class Alert(
    val title: String,
    val message: String,
    val confirm: String,
    val dismiss: String? = null,
    val monospace: Boolean = false,
    val onConfirm: () -> Unit = {}
)

/**
 * The phone's MainActivity without the Activity: it owns the dashboard, reads the files once a
 * second while the window is open, and carries out every action string the screens, the tray and
 * the overlay send. Build it on the UI thread (the Swing event thread) once the files folder is
 * known, show it with [FarmerWindowContent], and [close] it when the app exits.
 *
 * Unlike the phone it never sets FarmerService.helperActivityVisible or the overlay's visibility:
 * the overlay is a separate window with its own owner, and the PC bot works in the background.
 */
class FarmerController(
    override val filesDir: File,
    override val windowStatus: StateFlow<WindowStatus>,
    initialTab: Tab = Tab.HOME
) : DashboardHost {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val scope: CoroutineScope get() = controllerScope

    private val accountStore = AccountStore(filesDir)
    private val loader: DashboardLoader
    private var refreshJob: Job? = null
    private var refreshing = false
    private var refreshAgain = false
    private var inspectionRunning = false
    private var lastUiState: List<Any?>? = null

    private var profile: PhoneProfile? = null

    /** The message at the foot of the window, or null. */
    var currentToast: ToastMessage? by mutableStateOf(null)
        private set

    /** Open alerts, oldest first. The window shows the newest on top, as Android stacked them. */
    val alerts = mutableStateListOf<Alert>()

    /** The account editor sheet, while one is open. */
    var editor: AccountEditorModel? by mutableStateOf(null)
        private set

    /** Calibration, shown in place of the tabs while set: the phone's CalibrationActivity. */
    var calibration: CalibrationModel? by mutableStateOf(null)
        private set

    /** The five tabs and the state they show. */
    val dashboard: FarmerDashboard

    init {
        AppLog.init(filesDir)
        AppLog.log("main_activity_opened")
        try {
            accountStore.ensureMigrated()
        } catch (e: Exception) {
            AppLog.log("migration_failed", "error" to e.message)
        }
        loader = DashboardLoader(filesDir, accountStore)
        dashboard = FarmerDashboard(this, initialTab.ordinal)
    }

    override fun act(action: String) = handleAction(action)
    override fun refresh() = refreshStatus()
    override fun openTab(tab: Tab) = dashboard.selectTab(tab.ordinal)

    override fun toast(text: String, long: Boolean) {
        currentToast = ToastMessage(text, long)
    }

    /** Clears [shown] when it is still the toast on screen; the window calls it when its time is up. */
    fun toastDone(shown: ToastMessage) {
        if (currentToast === shown) currentToast = null
    }

    /** Closes [alert] without running its action. */
    fun dismissAlert(alert: Alert) {
        alerts.remove(alert)
    }

    /** Closes the account editor sheet. */
    fun closeEditor() {
        editor = null
    }

    /** The one way into calibration, from Home's card (`calibrate`) and Setup. Never during a run or an inspection. */
    override fun openCalibration(accountId: String?) {
        if (FarmerService.instance?.isBusy == true || inspectionRunning) {
            toast(BUSY)
            return
        }
        val id = accountId ?: runCatching { accountStore.selectedId() }.getOrNull() ?: PcAccount.ID
        calibration?.close()
        calibration = CalibrationModel(this, id, onBack = ::closeCalibration)
    }

    /** Back to the tabs from calibration; every screen is rebound, as the phone's onResume did. */
    fun closeCalibration() {
        calibration?.close()
        calibration = null
        lastUiState = null
        refreshStatus()
    }

    /** Setup's tray check, held off runs and other inspections the way Inspect is. */
    override fun inspection(block: suspend () -> Unit): Boolean {
        val service = FarmerService.instance
        if (service?.isBusy == true || inspectionRunning) return false
        inspectionRunning = true
        // Set inside the coroutine, so a launch cancelled before it starts cannot leave it stuck.
        controllerScope.launch {
            try { service?.inspecting = true; block() } finally { inspectionRunning = false; service?.inspecting = false; refreshStatus() }
        }
        return true
    }

    /**
     * The phone's onResume: rebinds at once, then reads every second. [FarmerWindowContent] calls
     * it when it enters the window; call it again when the window comes back from the tray.
     */
    fun windowShown() {
        refreshJob?.cancel()
        // Rebind even when nothing changed, as the phone did on each resume.
        lastUiState = null
        refreshStatus()
        refreshJob = controllerScope.launch {
            while (true) {
                delay(1000)
                refreshStatus()
            }
        }
    }

    /** The phone's onPause: no reads while nobody can see the window. */
    fun windowHidden() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * An action from outside the window, as the phone's notifications sent one by intent: the
     * tray menu, the overlay or a Windows notification. Call it on the UI thread.
     */
    fun externalAction(action: String) {
        refreshStatus()
        AppLog.log("new_intent_action", "action" to action)
        handleAction(action)
    }

    /** The phone's onDestroy: stops the reads and every screen's loads. */
    fun close() {
        calibration?.close()
        controllerScope.cancel()
    }

    private fun handleAction(action: String) {
        AppLog.log("handle_action", "action" to action)
        val service = FarmerService.instance
        // Reading history changes nothing, so it stays open to a running session.
        if (action == "history") {
            openTab(Tab.STATS)
            return
        }
        // The editor opens during a run too, with Save off until the run ends.
        if (action.startsWith("edit:")) {
            val id = action.removePrefix("edit:")
            runCatching { accountStore.load() }.getOrNull()?.firstOrNull { it.id == id }
                ?.let { editor = AccountEditorModel(this, it) }
            return
        }
        if (action != "stop" && action != "pause" && (service?.isBusy == true || inspectionRunning)) {
            AppLog.log("handle_action_busy", "action" to action, "serviceBusy" to (service?.isBusy == true), "inspection" to inspectionRunning)
            toast(BUSY)
            return
        }
        try {
            when {
                action == "stop" -> service?.stop(UserStop.DASHBOARD)
                action == "pause" -> service?.runGuard?.let { guard ->
                    if (guard.isPaused()) {
                        // Clash must be there before the run takes its next capture, or the
                        // foreground check stops the run the user just asked to continue. The
                        // phone brought it to the front; the PC starts it only when its window is
                        // gone, as the tray's Resume does, and resumes 1.5 s later.
                        service?.resumeFromNotification(source = "dashboard")
                    } else if (service?.isBusy == true) {
                        // With nothing running a Pause is ignored, as the notification's is: kept
                        // between runs, it held the next Start with no Resume shown (UO-21
                        // review, 15 October).
                        guard.pause()
                        AppLog.log("pause_button", "paused" to guard.isPaused(), "source" to "dashboard")
                    }
                }
                // The phone opened Android's accessibility settings; the PC's checks live in Setup.
                action == "settings" -> openTab(Tab.SETUP)
                // Setup's Game window line, when no Clash window is open.
                action == "launch_clash" -> {
                    if (service == null) {
                        toast(NOT_RUNNING, long = true)
                    } else {
                        service.launchClash()
                    }
                }
                action == "calibrate" -> openCalibration(null)
                action == "inspect" -> runInspect()
                action == "supervised" -> startToolRun(walls = false)
                action == "walls" -> startToolRun(walls = true)
                action.startsWith("plan:") -> startPlanRun(action.removePrefix("plan:"))
                action.startsWith("select:") -> {
                    val id = action.removePrefix("select:")
                    accountStore.select(id)
                }
                action.startsWith("switch_only:") -> {
                    val id = action.removePrefix("switch_only:")
                    val acc = accountStore.load().firstOrNull { it.id == id }
                    if (acc != null) {
                        startSwitchOnlyRun(acc)
                    }
                }
                action.startsWith("acknowledge_wall:") -> {
                    val id = action.removePrefix("acknowledge_wall:")
                    val acc = accountStore.load().firstOrNull { it.id == id }
                        ?: throw IllegalArgumentException(ACCOUNT_GONE)
                    alerts += Alert(
                        title = "Acknowledge pending purchase",
                        message = "Check ${acc.villageName} in Clash before clearing this marker. Has the purchase been checked?",
                        confirm = "Clear marker",
                        dismiss = "Cancel"
                    ) {
                        WallPurchaseStore(filesDir).acknowledge(id)
                        AppLog.log("pending_purchase_acknowledged", "accountId" to id)
                        refreshStatus()
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.log("handle_action_error", "action" to action, "error" to (e.message ?: e.toString()))
            val (title, message) = actionFailure(action, e)
            alerts += Alert(title, message, "Close")
        }
        refreshStatus()
    }

    /**
     * Reads the accounts, profiles and log on IO, then binds on the UI thread. A call while a
     * read is running asks for one more read after it, so no change is missed.
     */
    private fun refreshStatus() {
        if (refreshing) {
            refreshAgain = true
            return
        }
        refreshing = true
        controllerScope.launch {
            val tick = runCatching { withContext(Dispatchers.IO) { loader.tick() } }.getOrNull()
            refreshing = false
            // runCatching also catches cancellation; a closed controller must not bind.
            if (!isActive) return@launch
            if (tick != null) bindTick(tick)
            if (refreshAgain) {
                refreshAgain = false
                refreshStatus()
            }
        }
    }

    private fun bindTick(tick: DashboardLoader.Tick) {
        val service = FarmerService.instance
        val accounts = pcAccounts(tick.accounts)
        profile = accounts.selectedId?.let { accounts.profiles[it] }
        val busy = service?.isBusy == true
        val frame = service?.latestFrame?.store?.snapshot
        val paused = service?.runGuard?.isPaused() == true
        val uiState = listOf(tick.snapshot, tick.runContext, tick.events, busy, service != null, profile, accounts.profileError,
            accounts.accounts, accounts.selectedId, accounts.profiles, frame?.atMs, paused)
        // An unchanged idle screen is not rebound every second.
        if (busy || lastUiState != uiState) {
            dashboard.bind(DashboardState(
                snapshot = tick.snapshot,
                runContext = tick.runContext,
                connected = service != null,
                busy = busy,
                paused = paused,
                accounts = accounts.accounts,
                selectedId = accounts.selectedId,
                profiles = accounts.profiles,
                profileError = accounts.profileError,
                events = tick.events,
                frame = frame
            ))
            lastUiState = uiState
        }
    }

    private fun runInspect() {
        val service = FarmerService.instance
        if (service == null) {
            toast(NOT_RUNNING, long = true)
            return
        }

        // The overlay is hidden for capture; its status line says what is happening. The phone's
        // "Switching to Clash" is gone: the PC captures the window where it is.
        service.overlay?.updateStatus("Capturing the game screen...")
        AppLog.log("inspect_started")

        inspectionRunning = true
        controllerScope.launch {
            try {
                service.inspecting = true
                val captured = withContext(Dispatchers.IO) {
                    // The phone passed MainActivity to come back to; the PC window never left.
                    service.captureClashGame()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val capturesDir = File(filesDir, "captures").apply { mkdirs() }
                val captureFile = File(capturesDir, "inspect-$timestamp.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(captureFile).use { out ->
                        captured.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }

                val (observation, balances) = withContext(Dispatchers.IO) {
                    ScreenReader().use { reader ->
                        reader.observe(captured.bitmap, profile?.viewport, captured.timestampMs) to
                            profile?.homeResourceRois?.map { (r, roi) -> "${r.key}=${reader.readBalance(captured.bitmap, roi)}" }
                    }
                }
                captured.bitmap.recycle()

                // Every box with its bounds, so a new screen can become a test fixture as read.
                AppLog.log("inspect_completed", "state" to observation.state.name, "boxes" to observation.boxes.size,
                    "balances" to balances?.joinToString(" "),
                    "ocr" to observation.boxes.joinToString(" | ") { "${it.text}@${it.left},${it.top},${it.right},${it.bottom}" })
                showInspectReport(observation, captureFile)
                refreshStatus()
            } catch (e: Exception) {
                AppLog.log("inspect_failed", "error" to e.message)
                toast("Inspect could not read the game window. Make sure Clash is open, then try again.", long = true)
            } finally {
                inspectionRunning = false
                service.inspecting = false
                refreshStatus()
            }
        }
    }

    /**
     * More tools' "Supervised attack" and "Walls only" for the selected account. Both go through
     * [FarmerService.startPlan] with the launch the old buttons ran. A calibration the validator
     * refuses says so in Run's words, not the validator's, which name anchors and ROIs.
     */
    private fun startToolRun(walls: Boolean) {
        val service = FarmerService.instance
        if (service == null) {
            toast(NOT_RUNNING, long = true)
            return
        }
        val loaded = pcAccounts(loader.accounts())
        val account = loaded.selected ?: throw IllegalStateException(ACCOUNT_GONE)
        val name = account.villageName
        val prof = loaded.profiles[account.id]
            ?: throw IllegalStateException("$name is not calibrated. Open Setup and calibrate it first.")
        if (walls) {
            runCatching { prof.validateForWalls() }.exceptionOrNull()?.let {
                throw IllegalStateException("$name wall calibration is incomplete; finish it in Setup.")
            }
            account.settings.wallCapacityProblem()?.let { throw IllegalStateException(it) }
            service.overlay?.updateStatus("Starting walls only on ${account.displayName}...")
            service.startPlan(account, Launch.wallsOnly(account))
        } else {
            runCatching { prof.validateForAttack() }.exceptionOrNull()?.let {
                throw IllegalStateException("$name calibration is incomplete; finish it in Setup.")
            }
            service.overlay?.updateStatus("Starting a supervised attack on ${account.displayName}...")
            service.startPlan(account, Launch.supervised(account))
        }
        refreshStatus()
    }

    /**
     * Starts the plan Run or Home sent as `plan:<json>`. Readiness is checked again against the
     * files as they are now, and the plan is kept as the last one for Home's Start button.
     */
    private fun startPlanRun(json: String) {
        val service = FarmerService.instance
            ?: throw IllegalStateException(NOT_RUNNING)
        val plan = RunPlan.fromJson(JSONObject(json)) ?: throw IllegalArgumentException("That run could not be read")
        val loaded = pcAccounts(loader.accounts())
        val now = System.currentTimeMillis()
        ComposerState.readiness(plan, loaded.accounts, loaded.profiles, connected = true, busy = service.isBusy,
            selectedId = loaded.selectedId, nowMs = now).firstOrNull()?.let { throw IllegalStateException(it) }
        fun account(id: String) = loaded.accounts.firstOrNull { it.id == id } ?: throw IllegalStateException(ACCOUNT_GONE)
        when (val launch = plan.launch(loaded.accounts, now, loaded.selectedId)) {
            is Launch.Single -> {
                val account = account(launch.accountId)
                toast("Starting on ${account.displayName}...")
                service.startPlan(account, launch)
            }
            is Launch.Combined -> {
                val first = account(launch.firstId)
                val second = account(launch.secondId)
                toast("Starting ${first.displayName}, then ${second.displayName}...")
                service.startPlanCombined(first, second, launch)
            }
            is Launch.Refused -> throw IllegalStateException(launch.reason)
        }
        controllerScope.launch(Dispatchers.IO) { PresetStore(filesDir).setLast(plan) }
        refreshStatus()
    }

    private fun startSwitchOnlyRun(account: AccountRecord) {
        val service = FarmerService.instance
        if (service == null) {
            toast(NOT_RUNNING, long = true)
            return
        }
        toast("Switching to ${account.displayName}...")
        // Started at the PC, so it may read an account not read yet.
        service.startSwitchOnly(account, readAccount = true)
        refreshStatus()
    }

    private fun showInspectReport(observation: Observation, captureFile: File) {
        alerts += Alert("What the bot sees", inspectReport(observation, captureFile.name), "OK", monospace = true)
    }

    private companion object {
        const val BUSY = "Wait for the current operation or stop the bot first."
    }
}
