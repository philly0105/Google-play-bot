package local.clashfarmer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.desktop.ui.FarmerController
import local.clashfarmer.desktop.ui.FarmerWindowContent
import local.clashfarmer.desktop.ui.StatusPanelWindow
import local.clashfarmer.desktop.ui.StopAndQuitDialog
import local.clashfarmer.desktop.ui.theme.farmerIconPainter
import local.clashfarmer.service.FarmerService
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.RunNotice
import local.clashfarmer.service.RunTray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Dimension
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import androidx.compose.ui.window.WindowPlacement as ComposePlacement

/**
 * Starts the PC services and FarmerService, then the window, the tray icon and the status panel,
 * which stays hidden until the tray turns it on.
 * Closing the window leaves the bot running behind the tray icon, as the phone's service outlived
 * its screen; the tray's Quit stops any run, shuts the service down and ends the sidecar, asking
 * first in the window while a run is busy. Without a notification area, closing the window quits.
 * The window opens where it was left (WindowPlacement), and its title shows a live run's phase.
 * One app runs per files directory: a second start shows the first one's window and ends. A
 * failure nobody caught is logged and shown (CrashReport.kt).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val filesDir = DesktopServices.defaultFilesDir()
    CrashReport.install(File(filesDir, "logs/desktop.log"))
    val raiseRequests = MutableStateFlow(0)
    val instance = try {
        SingleInstance.acquire(filesDir) { raiseRequests.update { it + 1 } }
    } catch (e: IOException) {
        throw SingleInstance.startupProblem(filesDir, e)
    }
    if (instance == null) {
        if (!SingleInstance.raiseRunning(filesDir)) {
            CrashReport.message("Clash Farmer is already running. Open it from its icon in the notification area.")
        }
        exitProcess(0)
    }
    heldInstance = instance
    val sidecarExe = DesktopServices.bundledSidecar()
    if (!sidecarExe.isFile) {
        throw StartupProblem("A file Clash Farmer needs is missing: ${sidecarExe.path}. Unzip the whole " +
            "download into a folder, then start ClashFarmerPC.exe from that folder.")
    }
    val services = DesktopServices(filesDir, sidecarExe)
    // A packaged app's standard error goes nowhere, and the copied code prints its failures there.
    System.setErr(PrintStream(LineOutput(services.log), true, Charsets.UTF_8))
    services.log("${BuildInfo.name} starting")
    RunTray.supported = isTraySupported
    // The PC runs one account, so the dashboard opens on it. A fresh install gets that account
    // as a neutral record first, so the phone's seeding of its own two accounts never runs.
    PcAccount.seedIfFresh(services.filesDir)
    // Its presets too: no walls and no "Both accounts", which the PC can't run.
    PcAccount.seedPresetsIfFresh(services.filesDir)
    AccountStore(services.filesDir).run {
        ensureMigrated()
        select(PcAccount.ID)
    }
    val service = FarmerService(services.filesDir, services.host, services.surface)
    // Android connected the service on its main thread; the Swing thread is Dispatchers.Main here.
    SwingUtilities.invokeAndWait { service.onServiceConnected() }
    application {
        val controller = remember { FarmerController(services.filesDir, services.host.window) }
        // Where the window was left, fitted to the screens there are now (WindowPlacement).
        val screens = remember { WindowPlacement.screens() }
        val start = remember { WindowPlacement.initial(WindowPlacement.load(services.filesDir), screens) }
        val windowState = rememberWindowState(
            placement = if (start.maximized) ComposePlacement.Maximized else ComposePlacement.Floating,
            position = if (start.x != null && start.y != null) WindowPosition(start.x.dp, start.y.dp)
                else WindowPosition(Alignment.Center),
            size = DpSize(start.width.dp, start.height.dp)
        )
        // The last place the window had while neither maximized nor minimized, which is what a
        // maximized window goes back to; Java has no other way to ask a maximized frame for it.
        var floating by remember { mutableStateOf(start.copy(maximized = false)) }
        LaunchedEffect(Unit) {
            snapshotFlow { listOf(windowState.placement, windowState.isMinimized, windowState.position, windowState.size) }
                .collect {
                    val position = windowState.position
                    val size = windowState.size
                    if (windowState.placement == ComposePlacement.Floating && !windowState.isMinimized &&
                        position is WindowPosition.Absolute && size.isSpecified) {
                        floating = WindowPlacement.Placement(position.x.value.roundToInt(), position.y.value.roundToInt(),
                            size.width.value.roundToInt(), size.height.value.roundToInt())
                    }
                }
        }
        val savePlacement = {
            WindowPlacement.save(services.filesDir, floating.copy(maximized = windowState.placement == ComposePlacement.Maximized))
        }
        val trayState = rememberTrayState()
        var windowOpen by remember { mutableStateOf(true) }
        var raise by remember { mutableIntStateOf(0) }
        // The status panel starts hidden; the tray's Status panel turns it on for this launch.
        var panelShown by remember { mutableStateOf(false) }
        var toldAboutTray by remember { mutableStateOf(false) }
        // Quit asked while a run is busy: the window shows "Stop the run and quit?".
        var askingQuit by remember { mutableStateOf(false) }
        val quit = {
            if (windowOpen) savePlacement()
            controller.close()
            service.onDestroy()
            services.close()
            exitApplication()
        }
        val open: () -> Unit = {
            windowOpen = true
            windowState.isMinimized = false
            raise++
        }
        // Quitting stops a run mid-attack, so a busy run asks first, in the window.
        val requestQuit: () -> Unit = {
            if (service.isBusy) {
                open()
                askingQuit = true
            } else {
                quit()
            }
        }
        // A second start asks for the window.
        LaunchedEffect(Unit) { raiseRequests.collect { if (it > 0) open() } }
        val live by RunTray.live.collectAsState()
        if (isTraySupported) {
            LaunchedEffect(Unit) {
                for (notice in RunTray.notices) trayState.sendNotification(notice.toNotification())
            }
            Tray(
                icon = farmerIconPainter(),
                state = trayState,
                // Windows cuts a tooltip at 127 characters.
                tooltip = live?.let { "${it.title}: ${it.phase}".take(120) } ?: "Clash Farmer",
                onAction = open,
                menu = {
                    Item("Open Clash Farmer", onClick = open)
                    // The phone's ongoing notification: Resume while paused, else Pause, and Stop.
                    live?.let { status ->
                        Separator()
                        if (status.state == LiveStatus.State.PAUSED) {
                            Item("Resume", onClick = service::resumeFromNotification)
                        } else {
                            Item("Pause", onClick = service::pauseFromNotification)
                        }
                        Item("Stop", onClick = service::stopFromNotification)
                    }
                    Separator()
                    CheckboxItem("Status panel", checked = panelShown, onCheckedChange = { panelShown = it })
                    Item("Quit", onClick = requestQuit)
                }
            )
        }
        // A failure inside the window closes it rather than the app; the tray opens a fresh one.
        val windowFailed = remember {
            WindowExceptionHandlerFactory {
                WindowExceptionHandler { error ->
                    // Shown every time: the person opened this window, and it closes again at once.
                    CrashReport.report(error, "Window failed", always = true)
                    if (isTraySupported) windowOpen = false else quit()
                }
            }
        }
        if (windowOpen) CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowFailed) {
            Window(
                onCloseRequest = {
                    if (!isTraySupported) {
                        requestQuit()
                    } else {
                        savePlacement()
                        askingQuit = false
                        windowOpen = false
                        if (!toldAboutTray) {
                            toldAboutTray = true
                            trayState.sendNotification(Notification("Clash Farmer is still running",
                                "Open it or quit it from its icon in the notification area."))
                        }
                    }
                },
                state = windowState,
                title = windowTitle(live),
                icon = farmerIconPainter()
            ) {
                LaunchedEffect(raise) { if (raise > 0) window.raise() }
                LaunchedEffect(Unit) {
                    val (width, height) = WindowPlacement.minimum(screens.firstOrNull()?.work)
                    window.minimumSize = Dimension(width, height)
                }
                FarmerWindowContent(controller, onQuit = requestQuit)
                if (askingQuit) {
                    StopAndQuitDialog(
                        onQuit = {
                            askingQuit = false
                            quit()
                        },
                        onCancel = { askingQuit = false }
                    )
                }
            }
        }
        service.overlay?.let { StatusPanelWindow(it, panelShown) }
    }
}

/**
 * The window's title, which the taskbar shows: what a run is doing while one is live, so the
 * taskbar tells a running bot from an idle one.
 */
internal fun windowTitle(live: LiveStatus?): String =
    live?.phase?.trim()?.takeIf { it.isNotEmpty() }?.let { "Clash Farmer - ${it.take(80)}" } ?: "Clash Farmer"

/** Held for the app's life, so garbage collection can't close the lock's file early. */
@Volatile
private var heldInstance: SingleInstance? = null

/**
 * Brings the window in front of the others. Windows keeps a window that didn't get the click (as
 * when a second start or the tray asks for it) behind the active one and only flashes its button;
 * being on top for a moment lifts it. Only the person's own start or click gets here.
 */
private fun java.awt.Window.raise() {
    isAlwaysOnTop = true
    toFront()
    requestFocus()
    isAlwaysOnTop = false
}

/** A result notice as a Windows notification; one that needs a person is a warning. */
private fun RunNotice.toNotification() = Notification(title, text,
    if (kind == RunNotice.Kind.NEEDS_YOU) Notification.Type.Warning else Notification.Type.Info)

/** Hands each line written to it to [log]. */
private class LineOutput(private val log: (String) -> Unit) : OutputStream() {
    private val line = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            log(line.toString(Charsets.UTF_8).trimEnd('\r'))
            line.reset()
        } else {
            line.write(b)
        }
    }
}
