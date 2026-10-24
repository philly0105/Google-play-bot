package local.clashfarmer.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A wall purchase whose confirmation may have been sent and whose spend was never proven.
 * [expectedBalance] is what the resource bar should read if the purchase went through.
 */
data class PendingWallPurchase(
    val accountId: String,
    val currency: Resource,
    val count: Int,
    val total: Long,
    val expectedBalance: Long,
    val startingBalance: Long,
    val atMs: Long
)

/**
 * The per-account marker that says a wall purchase is unresolved, in
 * accounts/<id>/wall-pending.json beside the account's profile.
 *
 * It is written before the confirmation is sent and removed only by proof that the spend
 * happened, or by [acknowledge], which is the operator saying they looked at the village.
 * Acknowledging never records the purchase as a success. While a marker exists, whether it reads
 * back or not, no wall phase may spend on that account: a marker that cannot be parsed blocks
 * exactly like one that can.
 */
class WallPurchaseStore(private val root: File) {

    private fun file(accountId: String): File {
        require(UUID_REGEX.matches(accountId)) { "Invalid account id: '$accountId'" }
        return File(root, "accounts/$accountId/$FILE_NAME")
    }

    @Synchronized
    fun isPending(accountId: String): Boolean = file(accountId).exists()

    /** The marker if it can be read; null for none or for one that will not parse. */
    @Synchronized
    fun pending(accountId: String): PendingWallPurchase? = runCatching {
        val f = file(accountId)
        if (!f.exists()) return@runCatching null
        val json = JSONObject(f.readText(Charsets.UTF_8))
        PendingWallPurchase(
            accountId = json.getString("accountId"),
            currency = Resource.fromKey(json.getString("currency")) ?: return@runCatching null,
            count = json.getInt("count"),
            total = json.getLong("total"),
            expectedBalance = json.getLong("expectedBalance"),
            startingBalance = json.getLong("startingBalance"),
            atMs = json.getLong("atMs")
        )
    }.getOrNull()

    /**
     * Persists [purchase] before its confirmation is sent. Throws [IOException] when it could not
     * be made durable, and [IllegalStateException] when the account already has a marker, so a
     * second purchase can never overwrite the record of the first.
     */
    @Synchronized
    fun markPending(purchase: PendingWallPurchase) {
        val target = file(purchase.accountId)
        check(!target.exists()) { "A wall purchase is already pending for ${purchase.accountId}" }
        val parent = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create parent directory: $parent")
        }
        val json = JSONObject()
            .put("accountId", purchase.accountId)
            .put("currency", purchase.currency.key)
            .put("count", purchase.count)
            .put("total", purchase.total)
            .put("expectedBalance", purchase.expectedBalance)
            .put("startingBalance", purchase.startingBalance)
            .put("atMs", purchase.atMs)
        val temp = File.createTempFile("$FILE_NAME.", ".tmp", parent)
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(json.toString(2).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw if (e is IOException) e else IOException("Could not write the wall purchase marker", e)
        }
    }

    /**
     * Removes the marker [written] by this phase once its spend is proven. A marker that differs
     * from what the phase wrote is somebody else's and stays.
     */
    @Synchronized
    fun clearProven(written: PendingWallPurchase): Boolean {
        if (pending(written.accountId) != written) return false
        return file(written.accountId).delete()
    }

    /** The operator's clear, after inspecting the village. True if a marker was removed. */
    @Synchronized
    fun acknowledge(accountId: String): Boolean {
        val f = file(accountId)
        return f.exists() && f.delete()
    }

    private companion object {
        const val FILE_NAME = "wall-pending.json"
        val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
