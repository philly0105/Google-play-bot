package local.clashfarmer.desktop.ui

import local.clashfarmer.core.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * The one-time "Before you start" dialog. A fresh install opened straight on Home, with no word
 * of the risk to the account and no order of steps. The dialog shows while files/first-run.json
 * is missing, and only "I understand" writes it, so closing the app with the dialog up shows it
 * again next time. The owner's data folder has no flag either, so the owner sees it once.
 */
object FirstRun {
    const val FILE = "first-run.json"
    const val TITLE = "Before you start"
    const val CONFIRM = "I understand"
    const val QUIT = "Quit"

    /** The risk in one sentence; Setup's warning card says it too. */
    const val BAN_WARNING = "Bots break Supercell's terms of service, and Supercell can permanently ban the account the bot plays."

    val STEPS = listOf(
        "Open Clash of Clans in Google Play Games.",
        "Calibrate your account in Setup.",
        "Run one supervised attack from the Run tab, and watch it."
    )

    /** The dialog's text: the warning, then the steps numbered. */
    val MESSAGE: String = BAN_WARNING + "\n\nTo get started:\n" +
        STEPS.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")

    /** Whether the dialog is still to be confirmed in [filesDir]. */
    fun needed(filesDir: File): Boolean = !File(filesDir, FILE).exists()

    /** Records the confirmation in one step, so a crash mid-write never leaves half a flag. */
    fun confirm(filesDir: File, nowMs: Long = System.currentTimeMillis()) {
        filesDir.mkdirs()
        val tmp = File(filesDir, "$FILE.tmp")
        tmp.writeText(JSONObject().put("confirmedAtMs", nowMs).toString())
        AtomicFile.replace(tmp, File(filesDir, FILE))
    }
}
