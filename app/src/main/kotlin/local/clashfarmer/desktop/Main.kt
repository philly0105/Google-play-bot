package local.clashfarmer.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.desktop.ui.FarmerController
import local.clashfarmer.desktop.ui.FarmerWindowContent
import local.clashfarmer.desktop.ui.StatusPanelWindow
import local.clashfarmer.desktop.ui.theme.farmerIconPainter
import local.clashfarmer.service.FarmerService
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.RunNotice
import local.clashfarmer.service.RunTray
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.SwingUtilities

/**
 * Starts the PC services and FarmerService, then the window, the tray icon and the status panel,
 * which stays hidden until the tray turns it on.
 * Closing the window leaves the bot running behind the tray icon, as the phone's service outlived
 * its screen; the tray's Quit stops any run, shuts the service down and ends the sidecar. Without
 * a notification area, closing the window quits.
 */
fun main() {
    val services = DesktopServices(DesktopServices.defaultFilesDir(), DesktopServices.bundledSidecar())
    // A packaged app's standard error goes nowhere, and the copied code prints its failures there.
    System.setErr(PrintStream(LineOutput(services.log), true, Charsets.UTF_8))
    RunTray.supported = isTraySupported
    // The PC runs one account, so the dashboard opens on it. A fresh install gets that account
    // as a neutral record first, so the phone's seeding of its own two accounts never runs.
    PcAccount.seedIfFresh(services.filesDir)
    AccountStore(services.filesDir).run {
        ensureMigrated()
        select(PcAccount.ID)
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
        if (windowOpen) {
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
                LaunchedEffect(raise) { if (raise > 0) window.toFront() }
                FarmerWindowContent(controller)
            }
        }
        service.overlay?.let { StatusPanelWindow(it, panelShown) }
    }
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
