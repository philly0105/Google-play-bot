package local.clashfarmer.history

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What the history directory held: the battles that parsed, and the files that did not. */
data class HistoryLoad(val records: List<BattleRecord>, val corrupt: List<String>)

/**
 * One JSON file per battle, named by its UUID, replaced whole on every save. A battle saved
 * twice is the same file twice, so a result read on two frames can never become two battles.
 * The newest [MAX_BATTLES] across every account are kept.
 */
class BattleHistoryStore(
    private val directory: File,
    /** Puts a finished temporary file in place of the record; a failure leaves the old one. */
    private val replace: (source: File, target: File) -> Unit = ::atomicReplace
) {
    companion object {
        const val MAX_BATTLES = 1_000
        private val UUID_NAME = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private const val SUFFIX = ".json"

        private fun atomicReplace(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    @Synchronized
    fun save(record: BattleRecord) {
        writeRecord(record)
        prune()
    }

    @Synchronized
    fun load(): HistoryLoad {
        val records = mutableListOf<BattleRecord>()
        val corrupt = mutableListOf<String>()
        for (file in battleFiles()) {
            try {
                val record = BattleRecord.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
                require(file.name == record.id + SUFFIX) { "Filed under another id" }
                records += record
            } catch (e: Exception) {
                corrupt += file.name
            }
        }
        return HistoryLoad(records.sortedWith(compareByDescending<BattleRecord> { it.startedAtMs }.thenBy { it.id }), corrupt)
    }

    /** Newest first; corrupt files are left out here and named by [load]. */
    fun recent(accountId: String? = null, limit: Int = 100): List<BattleRecord> =
        load().records.filter { accountId == null || it.accountId == accountId }.take(limit)

    /**
     * Marks every battle still in progress as interrupted. Call it only when no battle can be
     * running: those records belong to a run that is gone, and nothing about how they ended is
     * known, so nothing else about them changes.
     */
    @Synchronized
    fun interruptUnfinished(reason: String): Int {
        val open = load().records.filter { it.outcome == BattleRecord.IN_PROGRESS }
        open.forEach { writeRecord(it.copy(outcome = BattleRecord.INTERRUPTED, reason = reason)) }
        return open.size
    }

    private fun writeRecord(record: BattleRecord) {
        require(UUID_NAME.matches(record.id)) { "Battle id '${record.id}' is not a UUID" }
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Cannot create $directory")
        val target = File(directory, record.id + SUFFIX)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "Battle file escapes the history directory" }
        val temp = File.createTempFile(record.id + ".", ".tmp", directory)
        try {
            temp.writeText(record.toJson().toString(), Charsets.UTF_8)
            replace(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun prune() {
        val records = load().records
        records.drop(MAX_BATTLES).forEach { File(directory, it.id + SUFFIX).delete() }
    }

    private fun battleFiles(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()
}
