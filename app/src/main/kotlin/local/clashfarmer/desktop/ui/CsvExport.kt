package local.clashfarmer.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The PC's stand-in for the phone's MediaStore Downloads write: a save dialog that opens on
 * Downloads with the export's name, then a write through a temporary file beside the target, so a
 * failed write keeps an older file of the same name.
 */
object CsvExport {
    /**
     * Asks where to save [fileName]; null when the user cancels. Call it on the UI thread: the
     * dialog is modal, and the window keeps painting while it is open.
     */
    fun pickFile(fileName: String): File? {
        // The focused window owns the dialog, so it opens over the app rather than anywhere.
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
        val dialog = FileDialog(owner, "Export CSV", FileDialog.SAVE)
        File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }?.let { dialog.directory = it.path }
        dialog.file = fileName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val folder = dialog.directory ?: return null
        return File(folder, if (name.endsWith(".csv", ignoreCase = true)) name else "$name.csv")
    }

    /** Writes [text] to [file] as UTF-8, replacing what is there only once it is written. Call it off the UI thread. */
    fun save(file: File, text: String) {
        val target = file.absoluteFile.toPath()
        val temp = Files.createTempFile(target.parent, ".${file.name}-", ".tmp")
        try {
            Files.write(temp, text.toByteArray(Charsets.UTF_8))
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }
}
