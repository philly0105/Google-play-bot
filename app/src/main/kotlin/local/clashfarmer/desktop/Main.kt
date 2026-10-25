package local.clashfarmer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.desktop.ui.FarmerController
import local.clashfarmer.desktop.ui.FarmerWindowContent
import local.clashfarmer.desktop.ui.StatusPanelWindow
import local.clashfarmer.desktop.ui.theme.farmerIconPainter
import local.clashfarmer.service.FarmerService
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.RunNotice
import local.clashfarmer.service.RunTray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Starts the PC services and FarmerService, then the window, the tray icon and the status panel,
 * which stays hidden until the tray turns it on.
 * Closing the window leaves the bot running behind the tray icon, as the phone's service outlived
 * its screen; the tray's Quit stops any run, shuts the service down and ends the sidecar. Without
 * a notification area, closing the window quits.
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
        throw StartupProblem("Clash Farmer can't save its settings in ${filesDir.path} (${e.message}). " +
            "Check that the drive has free space and that your Windows account can write there, then start it again.")
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
    // The PC runs only Blood, so the dashboard opens on it.
    AccountStore(services.filesDir).run {
        ensureMigrated()
        select(AccountStore.BLOOD_ID)
    }
    val service = FarmerService(services.filesDir, services.host, services.surface)
    // Android connected the service on its main thread; the Swing thread is Dispatchers.Main here.
    SwingUtilities.invokeAndWait { service.onServiceConnected() }
    application {
        val controller = remember { FarmerController(services.filesDir, services.host.window) }
        val windowState = rememberWindowState(size = DpSize(1100.dp, 760.dp))
        val trayState = rememberTrayState()
        var windowOpen by remember { mutableStateOf(true) }
        var raise by remember { mutableIntStateOf(0) }
        // The status panel starts hidden; the tray's Status panel turns it on for this launch.
        var panelShown by remember { mutableStateOf(false) }
        var toldAboutTray by remember { mutableStateOf(false) }
        val quit = {
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
        // A second start asks for the window.
        LaunchedEffect(Unit) { raiseRequests.collect { if (it > 0) open() } }
        if (isTraySupported) {
            val live by RunTray.live.collectAsState()
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
                    Item("Quit", onClick = quit)
                }
            )
        }
        // A failure inside the window closes it rather than the app; the tray opens a fresh one.
        val windowFailed = remember {
            WindowExceptionHandlerFactory {
                WindowExceptionHandler { error ->
                    CrashReport.report(error, "Window failed")
                    if (isTraySupported) windowOpen = false else quit()
                }
            }
        }
        if (windowOpen) CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowFailed) {
            Window(
                onCloseRequest = {
                    if (!isTraySupported) {
                        quit()
                    } else {
                        windowOpen = false
                        if (!toldAboutTray) {
                            toldAboutTray = true
                            trayState.sendNotification(Notification("Clash Farmer is still running",
                                "Open it or quit it from its icon in the notification area."))
                        }
                    }
                },
                state = windowState,
                title = "Clash Farmer PC",
                icon = farmerIconPainter()
            ) {
                LaunchedEffect(raise) { if (raise > 0) window.raise() }
                FarmerWindowContent(controller)
            }
        }
        service.overlay?.let { StatusPanelWindow(it, panelShown) }
    }
}

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
