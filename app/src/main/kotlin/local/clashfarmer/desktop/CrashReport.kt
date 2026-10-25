package local.clashfarmer.desktop

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** A failure at start that the person can fix; its message is shown as it is. */
class StartupProblem(message: String) : Exception(message)

/**
 * What a failure nobody caught does: it goes to desktop.log with its stack, and the person sees one
 * plain dialog that says where the log is. A failure on the main thread, which starts the app and
 * then runs its windows, ends the app; one on any other thread (the Swing thread, a coroutine, the
 * sidecar's readers) leaves it running, and shows a dialog at most once every [DIALOG_EVERY_MS].
 */
internal class CrashHandler(
    private val log: (String) -> Unit,
    private val logFile: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val show: (fatal: Boolean, text: String) -> Unit,
    private val exit: (Int) -> Unit
) : Thread.UncaughtExceptionHandler {
    private var lastShownMs: Long? = null

    override fun uncaughtException(thread: Thread, error: Throwable) {
        val fatal = thread.name == "main"
        report(error, fatal, "Uncaught on ${thread.name}")
        if (fatal) exit(1)
    }

    /**
     * Logs [error] and shows it; [where] says what failed, for the log only. [always] skips the
     * once-every-ten-minutes limit, for a failure the person just caused by their own click, such
     * as a window that closes as soon as it opens: without a dialog it would seem to do nothing.
     */
    fun report(error: Throwable, fatal: Boolean, where: String, always: Boolean = false) {
        runCatching { log("$where: ${error.stackTraceToString().trimEnd()}") }
        val due = synchronized(this) {
            val last = lastShownMs
            (fatal || always || last == null || nowMs() - last >= DIALOG_EVERY_MS).also { if (it) lastShownMs = nowMs() }
        }
        if (due) runCatching { show(fatal, text(error, fatal, logFile.takeIf { it.isFile })) }
    }

    companion object {
        const val DIALOG_EVERY_MS = 10 * 60_000L

        /** The dialog's text; [log] is null when nothing was written, so it isn't pointed to. */
        fun text(error: Throwable, fatal: Boolean, log: File?): String {
            val where = log?.let { " Details are in ${it.path}." } ?: ""
            return when {
                error is StartupProblem -> error.message.orEmpty() + where
                fatal -> "Clash Farmer stopped because of an error. Start it again; if this keeps " +
                    "happening, send the log to whoever gave you Clash Farmer.$where"
                else -> "Something in Clash Farmer failed, but it keeps running. If things look wrong, " +
                    "quit it from its icon in the notification area and start it again.$where"
            }
        }
    }
}

/** The app's [CrashHandler], installed at the top of main(). */
object CrashReport {
    private const val TITLE = "Clash Farmer PC"

    @Volatile
    private var handler: CrashHandler? = null

    /** Sends every uncaught failure to [CrashHandler], logging to [logFile]. */
    fun install(logFile: File) {
        val log = DesktopLog.of(logFile)
        handler = CrashHandler(log::write, logFile, show = { fatal, text -> dialog(fatal, text, logFile.parentFile) },
            exit = { kotlin.system.exitProcess(it) }).also { Thread.setDefaultUncaughtExceptionHandler(it) }
    }

    /** For a failure that was caught but still needs the person to know, like a window's; see [CrashHandler.report]. */
    fun report(error: Throwable, where: String, always: Boolean = false) {
        handler?.report(error, fatal = false, where = where, always = always)
    }

    /** A plain message box, for the start that finds Clash Farmer already running. */
    fun message(text: String) {
        if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null, text, TITLE, JOptionPane.INFORMATION_MESSAGE)
    }

    private fun dialog(fatal: Boolean, text: String, logs: File) {
        if (GraphicsEnvironment.isHeadless()) return
        val ask = {
            val choice = JOptionPane.showOptionDialog(
                null, text, TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null,
                arrayOf("OK", "Open logs folder"), "OK"
            )
            if (choice == 1) runCatching { logs.mkdirs(); Desktop.getDesktop().open(logs) }
        }
        // The fatal one blocks its thread, so the app ends only once it is read.
        if (fatal || SwingUtilities.isEventDispatchThread()) ask() else SwingUtilities.invokeLater(ask)
    }
}
