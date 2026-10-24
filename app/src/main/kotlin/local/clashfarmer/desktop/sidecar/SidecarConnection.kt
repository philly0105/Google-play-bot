package local.clashfarmer.desktop.sidecar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** A request the sidecar refused or couldn't finish. [code] is one of the codes in docs/SIDECAR.md. */
class SidecarException(val code: String, message: String) : Exception(message)

/** One response: its JSON, and its payload, empty when there is none. */
class Reply(val json: JSONObject, val payload: ByteArray)

/**
 * The request channel to one running sidecar, framed as docs/SIDECAR.md describes. Calls may
 * overlap: a reader thread hands each response to the call waiting on its id. Once the stream
 * ends, every waiting call fails and [isOpen] stays false.
 */
class SidecarConnection(
    input: InputStream,
    output: OutputStream,
    private val onClose: () -> Unit = {}
) : AutoCloseable {
    private val input = DataInputStream(input.buffered(1 shl 16))
    private val output = BufferedOutputStream(output, 1 shl 16)
    private val nextId = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Reply>>()
    @Volatile private var closedBy: SidecarException? = null

    val isOpen: Boolean get() = closedBy == null

    init {
        thread(name = "sidecar-reader", isDaemon = true) { readReplies() }
    }

    /** Sends [op] with [args], which it adds the id and op to, and waits for the reply. */
    suspend fun call(op: String, args: JSONObject = JSONObject(), payload: ByteArray? = null, timeoutMs: Long): Reply {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<Reply>()
        waiting[id] = reply
        try {
            // Checked after registering, so a close either fails this call or is seen here.
            closedBy?.let { throw it }
            val head = args.put("id", id).put("op", op).toString().toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) { send(head, payload ?: NO_PAYLOAD) }
            return withTimeoutOrNull(timeoutMs) { reply.await() }
                ?: throw SidecarException("timeout", "No reply to $op within $timeoutMs ms")
        } finally {
            waiting.remove(id)
        }
    }

    override fun close() = shutDown("Sidecar closed")

    private fun send(head: ByteArray, payload: ByteArray) {
        try {
            synchronized(output) {
                writeLength(head.size)
                output.write(head)
                writeLength(payload.size)
                output.write(payload)
                output.flush()
            }
        } catch (e: IOException) {
            throw SidecarException("failed", "Sidecar stopped reading: ${e.message}")
        }
    }

    private fun writeLength(length: Int) {
        output.write(length and 0xFF)
        output.write(length ushr 8 and 0xFF)
        output.write(length ushr 16 and 0xFF)
        output.write(length ushr 24 and 0xFF)
    }

    private fun readReplies() {
        val reason = try {
            while (true) {
                val head = readBlock(endAllowed = true) ?: break
                val payload = readBlock(endAllowed = false)!!
                deliver(JSONObject(String(head, Charsets.UTF_8)), payload)
            }
            "Sidecar exited"
        } catch (e: Exception) {
            "Sidecar output broke: ${e.message}"
        }
        shutDown(reason)
    }

    private fun deliver(json: JSONObject, payload: ByteArray) {
        // No waiter means the call already timed out or was cancelled.
        val reply = waiting.remove(json.optLong("id", -1L)) ?: return
        if (json.optBoolean("ok")) {
            reply.complete(Reply(json, payload))
        } else {
            reply.completeExceptionally(SidecarException(json.optString("code", "failed"), json.optString("error")))
        }
    }

    /** One length-prefixed block; null when the stream ends cleanly where a frame would start. */
    private fun readBlock(endAllowed: Boolean): ByteArray? {
        val first = input.read()
        if (first < 0) {
            if (endAllowed) return null
            throw EOFException("Stream ended inside a frame")
        }
        val length = first.toLong() or
            (input.readUnsignedByte().toLong() shl 8) or
            (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 24)
        if (length > MAX_BLOCK_BYTES) throw IOException("Block of $length bytes")
        return ByteArray(length.toInt()).also { input.readFully(it) }
    }

    private fun shutDown(reason: String) {
        val failure = SidecarException("failed", reason)
        synchronized(this) {
            if (closedBy != null) return
            closedBy = failure
        }
        waiting.values.forEach { it.completeExceptionally(failure) }
        runCatching { output.close() }
        onClose()
    }

    private companion object {
        val NO_PAYLOAD = ByteArray(0)

        /** The sidecar's own cap; a 4K BGRA frame is about 33 MB. */
        const val MAX_BLOCK_BYTES = 256L * 1024 * 1024
    }
}
