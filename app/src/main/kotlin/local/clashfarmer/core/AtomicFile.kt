package local.clashfarmer.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop: replaces a store's file with the temp file written beside it in one step.
 *
 * The phone's stores renamed the temp file over the target, and on a failed rename deleted the
 * target first. On Android the first rename replaces; on Windows `File.renameTo` never replaces
 * an existing file, so every save took the delete path, and a crash or power cut between the
 * delete and the second rename lost the saved data. A move with REPLACE_EXISTING replaces in
 * place (MoveFileEx), so a reader sees the old file or the new one, never none.
 */
object AtomicFile {
    /** Moves [tmp] over [target]. Throws when the move fails; [target] is then left as it was. */
    fun replace(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
