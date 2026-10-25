package local.clashfarmer.core

import org.json.JSONObject
import java.io.File

/**
 * Village balances the bot last read for one account. Shown on the dashboard, and the next
 * farming run's [HomeBalanceGuard] starts from them: a first reading a factor of five away is
 * held back. Nothing else decides on them.
 */
data class StorageReading(val atMs: Long, val values: Map<Resource, Long>)

/** The latest [StorageReading] per account, in storage.json beside the accounts. */
class StorageStore(root: File) {
    private val file = File(root, "storage.json")

    @Synchronized
    fun record(accountId: String, reading: StorageReading) {
        val root = readRoot()
        val values = JSONObject()
        reading.values.forEach { (resource, value) -> values.put(resource.key, value) }
        root.put(accountId, JSONObject().put("at", reading.atMs).put("values", values))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        AtomicFile.replace(tmp, file) // Desktop: renameTo never replaces on Windows.
    }

    /**
     * Records [reading], keeping the stored value of any resource it leaves out. A balance the
     * guard held back as implausible is left out, and erasing it would leave the next run's
     * [HomeBalanceGuard] nothing to hold a repeat of the misread against.
     */
    @Synchronized
    fun recordKeepingUnread(accountId: String, reading: StorageReading) {
        record(accountId, StorageReading(reading.atMs, latest(accountId)?.values.orEmpty() + reading.values))
    }

    @Synchronized
    fun latest(accountId: String): StorageReading? {
        val entry = readRoot().optJSONObject(accountId) ?: return null
        val values = entry.optJSONObject("values") ?: return null
        val parsed = values.keys().asSequence()
            .mapNotNull { key -> Resource.fromKey(key)?.let { it to values.getLong(key) } }
            .toMap()
        return StorageReading(entry.optLong("at"), parsed)
    }

    private fun readRoot(): JSONObject =
        runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull() ?: JSONObject()
}
