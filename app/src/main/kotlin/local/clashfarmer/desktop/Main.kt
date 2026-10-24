package local.clashfarmer.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import local.clashfarmer.service.FarmerService
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.SwingUtilities

/**
 * Starts the PC services and FarmerService, then the window. Closing the window stops any run,
 * shuts the service down and ends the sidecar.
 */
fun main() {
    val services = DesktopServices(DesktopServices.defaultFilesDir(), DesktopServices.bundledSidecar())
    // A packaged app's standard error goes nowhere, and the copied code prints its failures there.
    System.setErr(PrintStream(LineOutput(services.log), true, Charsets.UTF_8))
    val service = FarmerService(services.filesDir, services.host, services.surface)
    // Android connected the service on its main thread; the Swing thread is Dispatchers.Main here.
    SwingUtilities.invokeAndWait { service.onServiceConnected() }
    application {
        Window(
            onCloseRequest = {
                service.onDestroy()
                services.close()
                exitApplication()
            },
            title = "Clash Farmer PC"
        ) {}
    }
}

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
