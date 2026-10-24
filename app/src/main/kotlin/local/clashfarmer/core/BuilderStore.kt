package local.clashfarmer.core

import org.json.JSONObject
import java.io.File

/** The builder counter as last read at home: [free] of [total] idle at [atMs]. */
data class BuilderReading(val free: Int, val total: Int, val atMs: Long) {
    /** How old the reading is at [nowMs]; never negative, so a clock step back reads as fresh. */
    fun ageMs(nowMs: Long): Long = (nowMs - atMs).coerceAtLeast(0L)
}

/**
 * The last builder counter read per account, in accounts/<id>/builders.json beside the profile,
 * so Home can say "walls skipped: no builder is free" before a run starts. Written only from
 * frames a run already captured; a counter that did not read writes nothing and keeps the old
 * value. A failed write is never the run's problem: [record] does not throw.
 */
class BuilderStore(private val root: File) {

    private fun file(accountId: String): File? =
        if (UUID_REGEX.matches(accountId)) File(root, "accounts/$accountId/$FILE_NAME") else null

    /** Saves [count] for [accountId]. False when there was nothing to save or it could not be saved. */
    @Synchronized
    fun record(accountId: String, count: BuilderCount?, atMs: Long): Boolean {
        if (count == null) return false
        val target = file(accountId) ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            val json = JSONObject().put("free", count.free).put("total", count.total).put("at", atMs)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("rename failed")
            }
            true
        }.getOrDefault(false)
    }

    /** The last saved reading, or null for none or one that will not parse. */
    @Synchronized
    fun latest(accountId: String): BuilderReading? {
        val target = file(accountId) ?: return null
        return runCatching {
            if (!target.exists()) return null
            val json = JSONObject(target.readText())
            val free = json.getInt("free")
            val total = json.getInt("total")
            if (total < 1 || free !in 0..total) return null
            BuilderReading(free, total, json.getLong("at"))
        }.getOrNull()
    }

    private companion object {
        const val FILE_NAME = "builders.json"
        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
