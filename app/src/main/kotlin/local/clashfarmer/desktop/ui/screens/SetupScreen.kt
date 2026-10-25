package local.clashfarmer.desktop.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.BuilderStore
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.desktop.BuildInfo
import local.clashfarmer.desktop.RemotePage
import local.clashfarmer.desktop.sidecar.Hello
import local.clashfarmer.desktop.sidecar.SidecarStatus
import local.clashfarmer.desktop.ui.DashboardHost
import local.clashfarmer.desktop.ui.DashboardScreen
import local.clashfarmer.desktop.ui.PcChecklist
import local.clashfarmer.desktop.ui.TrayCheckSheet
import local.clashfarmer.desktop.ui.components.Card
import local.clashfarmer.desktop.ui.components.CheckRow
import local.clashfarmer.desktop.ui.components.PrimaryButton
import local.clashfarmer.desktop.ui.components.ScreenPage
import local.clashfarmer.desktop.ui.components.ScreenTitle
import local.clashfarmer.desktop.ui.components.SecondaryButton
import local.clashfarmer.desktop.ui.components.SectionHeader
import local.clashfarmer.desktop.ui.components.Sheet
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.insight.CalibrationHealth
import local.clashfarmer.insight.Finding
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.remote.RemoteServer
import local.clashfarmer.service.FarmerService
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Setup (spec 2.6): one checklist for this PC and each account, the worst fix as the screen's
 * one primary button, the settings editor per account, and the inspect tool.
 */
class SetupScreen(private val host: DashboardHost) : DashboardScreen {
    private val scroll = ScrollState(0)

    private var state: DashboardState? by mutableStateOf(null)
    /** When the last bind came: the builder rows count "read 5 min ago" from it. */
    private var nowMs by mutableStateOf(System.currentTimeMillis())
    /** The Clash window as the watcher last found it, for the Game window line. */
    private var windowStatus by mutableStateOf(host.windowStatus.value)
    private var trayCheckRunning by mutableStateOf(false)
    /** The tray check's result, while its sheet is open. */
    private var trayResult: TrayCheckSheet.Outcome? by mutableStateOf(null)
    /** The row whose findings sheet is open. */
    private var detail: CheckLine? by mutableStateOf(null)

    /** Tray, battery, text reader and remote page: system calls, read on IO at most every 30 s. */
    private data class Machine(val connected: Boolean, val tray: Boolean, val battery: DeviceHealth?,
                               val serviceOn: Boolean, val remote: RemotePage.Status?, val textReader: Hello?)
    private var machine: Machine? by mutableStateOf(null)
    private var machineAtMs = 0L
    private var readingMachine = false

    /** Builder readings and profile save times: files, loaded on IO and kept 30 s. */
    private var builders: Map<String, BuilderReading?> by mutableStateOf(emptyMap())
    private var savedAt: Map<String, Long> by mutableStateOf(emptyMap())
    /** Whether each account has its identity template ([PcAccount]); empty until the files are read. */
    private var enrolled: Map<String, Boolean> by mutableStateOf(emptyMap())
    private var filesAtMs = 0L
    private var filesFor: List<String>? = null
    private var loading = false

    private var healthKey: Triple<List<AccountRecord>, Map<String, PhoneProfile?>, Map<String, Long>>? = null
    private var health: Map<String, List<Finding>> = emptyMap()

    /** Whether the page is on screen, the phone's view.isShown. */
    private var visible = false

    /** This PC's rows, then each account and its rows, in screen order. */
    private data class Checklist(val machine: List<CheckLine>, val accounts: List<Pair<AccountRecord, List<CheckLine>>>) {
        val all: List<CheckLine> = machine + accounts.flatMap { it.second }
    }

    /** The checklist, rebuilt when the state, the window, the machine reading or the files change. */
    private val current: State<Checklist?> = derivedStateOf(structuralEqualityPolicy()) { state?.let { checklist(it) } }

    override val badge: TabBadge? get() = current.value?.let { SetupChecklist.badge(it.all) }

    init {
        // The Game window line, and the tab dot with it, follow the watcher between ticks.
        host.scope.launch { host.windowStatus.collect { windowStatus = it } }
    }

    override fun bind(state: DashboardState) {
        this.state = state
        nowMs = System.currentTimeMillis()
        readMachine(state.connected)
        if (visible) loadFiles(force = false)
    }

    override fun onShown() {
        loadFiles(force = false)
    }

    @Composable
    override fun Content() {
        DisposableEffect(Unit) {
            visible = true
            // The phone read again when its window took focus back from Android's settings or
            // calibration. Here the page reads again each time it comes on screen.
            machineAtMs = 0L
            state?.let { readMachine(it.connected) }
            loadFiles(force = true)
            onDispose { visible = false }
        }
        val state = this.state ?: return
        val list = current.value ?: return
        // A tray check is reading Clash, so the fixes and tools wait for it as they wait for a run.
        val busy = state.busy || trayCheckRunning
        val (ready, total) = SetupChecklist.ready(list.all)

        ScreenPage(scroll = scroll) {
            ScreenTitle("Setup", "$ready of $total ready")

            SectionHeader("This PC")
            Card {
                list.machine.forEach { LineRow(it, busy) }
                if (list.machine.any { it.key == PcChecklist.REMOTE && it.tone == Tone.OK }) {
                    SecondaryButton("Copy link", Modifier.padding(top = 8.dp), enabled = !trayCheckRunning, compact = true) {
                        copyRemoteLink()
                    }
                }
            }

            AccountBlocks(list.accounts, busy)

            SectionHeader("Tools")
            Card {
                Label("Capture and read the current Clash screen. The report shows the detected state and the text it read.",
                    size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
                SecondaryButton("Inspect the game screen", Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = !busy && state.connected) { host.act("inspect") }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Names the account the check reads: the one picked on Home, which a scout frame cannot tell apart.
                    Label(trayText(state.selected?.displayName), Modifier.weight(1f).padding(end = 12.dp), size = FarmerTheme.SMALL + 1)
                    SecondaryButton(if (trayCheckRunning) "Checking..." else "Check", enabled = !busy && state.connected) {
                        this@SetupScreen.state?.let { latest ->
                            TrayCheckSheet.run(host, latest, { running -> trayCheckRunning = running }, { trayResult = it })
                        }
                    }
                }
            }

            // Spec 1.2: the one primary action sits at the bottom of the content, above the footer.
            SetupChecklist.primaryFix(list.all)?.let { primary ->
                PrimaryButton(SetupChecklist.fixLabel(primary), Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !busy) {
                    primary.fix?.let { runFix(it) }
                }
            }

            Label("Bots violate Supercell's terms and risk a permanent ban.", Modifier.padding(top = 8.dp),
                size = FarmerTheme.LABEL, color = FarmerColors.warn)
            Label("Runs on this PC. The network is used only for the remote control page, which answers on Tailscale alone.",
                Modifier.padding(top = 8.dp), size = FarmerTheme.LABEL, color = FarmerColors.faint)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(BuildInfo.about(host.filesDir), Modifier.weight(1f).padding(end = 12.dp),
                    size = FarmerTheme.LABEL, color = FarmerColors.faint)
                SecondaryButton("Open logs folder", compact = true) { openLogsFolder() }
            }
        }

        detail?.let { DetailSheet(it, state.busy) }
        trayResult?.let { TrayCheckSheet.ResultSheet(it, host) { trayResult = null } }
    }

    /** One block per account: the header with Edit settings, the account's rows, and Open calibration. */
    @Composable
    private fun AccountBlocks(accounts: List<Pair<AccountRecord, List<CheckLine>>>, busy: Boolean) {
        if (accounts.isEmpty()) {
            SectionHeader("Accounts")
            Card {
                Label("No accounts are set up on this PC.", size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            }
            return
        }
        accounts.forEach { (account, lines) ->
            key(account.id) {
                val id = account.id
                // The tag only once the bot has read it off Settings; before that it is a placeholder.
                val title = if (enrolled[id] == true) "${account.displayName} · ${account.playerTag}" else account.displayName
                SectionHeader(title, action = "Edit settings",
                    actionEnabled = !trayCheckRunning) { editAccount(id) }
                Card {
                    lines.forEach { LineRow(it, busy) }
                    // A read that went wrong, or another account in Clash, would stop every start.
                    if (enrolled[id] == true) {
                        SecondaryButton(PcAccount.READ_AGAIN, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) {
                            readAgain(id)
                        }
                    }
                    // A green row offers no fix, yet trays and wall points may still need redoing.
                    // Hidden while a row of the card offers "Open calibration" itself, so the card shows it once.
                    if (!SetupChecklist.rowOffersCalibration(lines)) {
                        SecondaryButton("Open calibration", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) {
                            runFix(CheckLine.Fix("Open calibration", CheckLine.FixKind.CALIBRATION, id))
                        }
                    }
                }
            }
        }
    }

    /**
     * One checklist row. Fixes leave the window or change calibration, so they wait while a run is
     * active. They wait greyed out, in place, so the page keeps its length as a tray check begins.
     */
    @Composable
    private fun LineRow(line: CheckLine, busy: Boolean) {
        val fix = line.fix
        CheckRow(
            title = line.title,
            detail = line.detail,
            chip = line.chip,
            tone = line.tone,
            fixLabel = fix?.label,
            onFix = fix?.let { f -> { runFix(f) } },
            fixEnabled = !busy,
            onClick = if (line.findings.isNotEmpty()) ({ showDetail(line) }) else null
        )
    }

    /** A row's findings in full, with the row's fix under them while it can run. */
    @Composable
    private fun DetailSheet(line: CheckLine, runActive: Boolean) {
        Sheet(listOfNotNull(line.title, line.accountName).joinToString(" · "), onClose = { detail = null }) {
            line.findings.forEach { finding ->
                Label(finding.title, Modifier.padding(top = 16.dp), size = FarmerTheme.BODY, bold = true)
                Label(finding.detail, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL + 1, color = FarmerColors.muted)
            }
            val fix = line.fix
            if (fix != null && (fix.kind == CheckLine.FixKind.EDIT_SETTINGS || !runActive)) {
                SecondaryButton(fix.label, Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    detail = null
                    runFix(fix)
                }
            }
        }
    }

    /** The rows above the accounts: the game window, the text reader and tray, the battery and the remote page. */
    private fun checklist(state: DashboardState): Checklist {
        val m = machine
        val machineLines = listOfNotNull(
            PcChecklist.gameWindow(windowStatus),
            PcChecklist.windowsVersion(windowStatus.pc),
            PcChecklist.textReader(m?.textReader),
            m?.let { PcChecklist.trayIcon(it.tray) } ?: notRead(PcChecklist.TRAY_ICON, "Tray icon"),
            m?.let { SetupChecklist.battery(it.battery) } ?: notRead("battery", "Battery and heat"),
            m?.let { PcChecklist.remote(it.serviceOn, it.remote) } ?: notRead(PcChecklist.REMOTE, "Remote page")
        )
        val findings = health(state)
        val accounts = state.accounts.map { account ->
            account to listOf(PcChecklist.account(account, enrolled[account.id])) +
                SetupChecklist.account(account, state.profiles[account.id], findings[account.id].orEmpty(),
                    state.profileError.takeIf { account.id == state.selectedId }, builders[account.id], nowMs)
        }
        return Checklist(machineLines, accounts)
    }

    /**
     * A machine row before its first reading, outside the count as the phone's unread rows were.
     * The phone read these on the spot; here they come from IO a moment after the page opens.
     */
    private fun notRead(key: String, title: String) =
        CheckLine(key, title, "", SetupChecklist.chip(Tone.NEUTRAL), Tone.NEUTRAL, counts = false)

    private fun trayText(account: String?) =
        if (account == null) "Check the battle tray (open a scout screen first)"
        else "Check $account's battle tray (open $account's scout screen first)"

    private fun health(state: DashboardState): Map<String, List<Finding>> {
        val inputs = Triple(state.accounts, state.profiles, savedAt)
        if (inputs != healthKey) {
            healthKey = inputs
            health = runCatching { CalibrationHealth.check(state.accounts, state.profiles, savedAt) }.getOrDefault(emptyMap())
        }
        return health
    }

    /**
     * Reads the tray, the battery, the text reader and the remote page on IO when the last reading is 30 s
     * old or the service came or went since. The rows show the reading when it arrives.
     */
    private fun readMachine(connected: Boolean) {
        val last = machine
        if (readingMachine || (last != null && last.connected == connected &&
                System.currentTimeMillis() - machineAtMs < CACHE_MS)) return
        readingMachine = true
        host.scope.launch {
            val next = runCatching { withContext(Dispatchers.IO) { machineNow(connected) } }.getOrNull()
            // runCatching also catches cancellation; a closed window's state must not be touched.
            if (!isActive) return@launch
            readingMachine = false
            machineAtMs = System.currentTimeMillis()
            if (next != null) machine = next
            // The service came or went during the read: read again for the state shown now.
            state?.let { if (it.connected != connected) readMachine(it.connected) }
        }
    }

    /** The system calls behind [Machine]; any that fails reads as unknown. Runs on IO. */
    private fun machineNow(connected: Boolean): Machine {
        val service = FarmerService.instance
        val serviceOn = connected && service != null
        return Machine(
            connected = connected,
            tray = runCatching { SystemTray.isSupported() }.getOrDefault(false),
            // The service reads the battery through the sidecar in the background; this is its last reading.
            battery = runCatching { service?.readDeviceHealth() }.getOrNull(),
            serviceOn = serviceOn,
            remote = if (serviceOn) FarmerService.remotePage?.status else null,
            // DesktopServices asks the sidecar; this is its last answer.
            textReader = SidecarStatus.hello
        )
    }

    /** Builder readings, profile save times and identity templates on IO; the rows redraw from them when done. */
    private fun loadFiles(force: Boolean) {
        val state = this.state ?: return
        val ids = state.accounts.map { it.id }
        val now = System.currentTimeMillis()
        if (loading || (!force && ids == filesFor && now - filesAtMs < CACHE_MS)) return
        loading = true
        val files = host.filesDir
        host.scope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val builderStore = BuilderStore(files)
                    val accountStore = AccountStore(files)
                    val readings = ids.associateWith { runCatching { builderStore.latest(it) }.getOrNull() }
                    val saved = ids.mapNotNull { id ->
                        runCatching { accountStore.profileFile(id).takeIf { it.exists() }?.lastModified() }.getOrNull()?.let { id to it }
                    }.toMap()
                    Triple(readings, saved, ids.associateWith { PcAccount.enrolled(files, it) })
                }
            }.getOrNull()
            // runCatching also catches cancellation; a closed window's state must not be touched.
            if (!isActive) return@launch
            loading = false
            filesFor = ids
            filesAtMs = System.currentTimeMillis()
            if (loaded != null) {
                builders = loaded.first
                savedAt = loaded.second
                enrolled = loaded.third
            }
        }
    }

    private fun runFix(fix: CheckLine.Fix) {
        // Silent during a tray check, as on the phone: the check is reading Clash, and a fix would
        // change what it reads.
        if (trayCheckRunning) return
        if (fix.kind == CheckLine.FixKind.EDIT_SETTINGS) {
            editAccount(fix.accountId)
            return
        }
        if (FarmerService.instance?.isBusy == true) {
            host.toast("Wait for the current run to finish.")
            return
        }
        when (fix.kind) {
            // The Game window row's fix (PcChecklist): open Clash in Google Play Games.
            CheckLine.FixKind.ACCESSIBILITY -> host.act("launch_clash")
            // No PC row offers it: the Tray icon row has no fix.
            CheckLine.FixKind.NOTIFICATIONS -> Unit
            // The host also refuses it while an inspection runs.
            CheckLine.FixKind.CALIBRATION -> host.openCalibration(fix.accountId)
            CheckLine.FixKind.EDIT_SETTINGS -> Unit
        }
    }

    /**
     * Drops the account's identity template ([PcAccount.forget]), so the next supervised attack
     * reads the account again. Not during a run, whose account check reads the template.
     */
    private fun readAgain(id: String) {
        if (trayCheckRunning) return
        if (FarmerService.instance?.isBusy == true) {
            host.toast("Wait for the current run to finish.")
            return
        }
        val files = host.filesDir
        host.scope.launch {
            val done = runCatching {
                withContext(Dispatchers.IO) {
                    PcAccount.forget(files, id)
                    AppLog.log("account_identity_forgotten", "where" to "setup")
                }
            }.isSuccess
            if (!isActive) return@launch
            host.toast(if (done) "The next supervised attack will read your account again." else "Could not reset the account. Try again.")
            if (done) enrolled = enrolled + (id to false)
            loadFiles(force = true)
        }
    }

    private fun editAccount(id: String?) {
        if (trayCheckRunning) return
        val account = state?.accounts?.firstOrNull { it.id == id } ?: return
        host.act("edit:${account.id}")
    }

    private fun showDetail(line: CheckLine) {
        // A sheet opened during a tray check would offer a fix that does nothing until the check ends.
        if (trayCheckRunning) return
        detail = line
    }

    /**
     * Puts the full remote link on the clipboard. The key is never shown or logged. Android marks
     * such a clip sensitive; Windows has no such mark for Java, so clipboard history may keep it.
     */
    private fun copyRemoteLink() {
        val service = FarmerService.instance
        val address = machine?.remote?.takeIf { it.listening }?.tailscale
        if (service == null || address == null) {
            host.toast("Remote control is not available.")
            return
        }
        val copied = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection("http://$address:${RemoteServer.PORT}/?key=${service.remoteKey}"), null)
        }.isSuccess
        host.toast(if (copied) "Link copied. Keep it private; the key in it is the password." else "Could not copy the link.")
    }

    /** Opens the logs folder in Explorer, off the UI thread: Desktop.open can wait on the shell. */
    private fun openLogsFolder() {
        val logs = File(host.filesDir, "logs")
        host.scope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { logs.mkdirs(); Desktop.getDesktop().open(logs) }.isSuccess }
            if (!opened) host.toast("Could not open ${logs.path}", long = true)
        }
    }

    private companion object {
        const val CACHE_MS = 30_000L
    }
}
