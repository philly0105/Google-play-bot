package local.clashfarmer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class StoredFile(val name: String, val modified: Long, val size: Long)

data class PruneResult(val capturesDeleted: Int, val capturesKept: Int, val logsDeleted: Int, val bytesFreed: Long)

/**
 * Bounds the diagnostic files that nothing else ever deletes: Inspect, tray check, wall-stop and failure
 * captures in files/captures, and daily JSONL logs in files/logs. Only names this app writes there are
 * eligible, so calibration frames, accounts/ and history/ are never selected.
 */
object StorageRetention {
    const val KEEP_CAPTURES = 20
    const val KEEP_LOG_DAYS = 30
    /** Failure snapshots are a pool of their own, so routine captures cannot push them out. */
    const val KEEP_FAILURES = 10

    private val capturePrefixes = listOf("inspect-", "wall-bulk-stopped-", "tray-")
    private val logName = Regex("""clashfarmer-(\d{8})\.jsonl""")

    fun capturesToDelete(
        files: List<StoredFile>,
        keep: Int = KEEP_CAPTURES,
        keepFailures: Int = KEEP_FAILURES
    ): List<StoredFile> =
        oldest(files, capturePrefixes, keep) + oldest(files, listOf(FailureSnapshot.PREFIX), keepFailures)

    private fun oldest(files: List<StoredFile>, prefixes: List<String>, keep: Int): List<StoredFile> =
        files.filter { file -> file.name.endsWith(".png") && prefixes.any { file.name.startsWith(it) } }
            .sortedWith(compareByDescending<StoredFile> { it.modified }.thenByDescending { it.name })
            .drop(keep)

    /** Ages logs by the date in the name, not mtime; the newest log is always kept. */
    fun logsToDelete(files: List<StoredFile>, nowMillis: Long, keepDays: Int = KEEP_LOG_DAYS): List<StoredFile> {
        val dated = files.mapNotNull { file -> logName.matchEntire(file.name)?.let { file to it.groupValues[1] } }
        val newest = dated.maxOfOrNull { it.second } ?: return emptyList()
        val cutoff = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis - keepDays * 86_400_000L))
        return dated.filter { (_, day) -> day < cutoff && day != newest }.map { it.first }
    }

    fun prune(filesDir: File, nowMillis: Long = System.currentTimeMillis()): PruneResult {
        val captureDir = File(filesDir, "captures")
        val logDir = File(filesDir, "logs")
        val captures = list(captureDir)
        val captureVictims = capturesToDelete(captures)
        val logVictims = logsToDelete(list(logDir), nowMillis)
        var bytes = 0L
        val capturesDeleted = captureVictims.count { delete(captureDir, it)?.also { size -> bytes += size } != null }
        val logsDeleted = logVictims.count { delete(logDir, it)?.also { size -> bytes += size } != null }
        val eligible = capturesToDelete(captures, keep = 0, keepFailures = 0).size
        return PruneResult(capturesDeleted, eligible - capturesDeleted, logsDeleted, bytes)
    }

    private fun list(dir: File): List<StoredFile> =
        dir.listFiles()?.filter { it.isFile }?.map { StoredFile(it.name, it.lastModified(), it.length()) }.orEmpty()

    private fun delete(dir: File, entry: StoredFile): Long? =
        if (File(dir, entry.name).delete()) entry.size else null
}
