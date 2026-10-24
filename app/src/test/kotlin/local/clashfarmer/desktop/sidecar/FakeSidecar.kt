package local.clashfarmer.desktop.sidecar

import org.json.JSONObject
import java.io.DataInputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe

/** A sidecar stand-in on in-memory pipes, framing by docs/SIDECAR.md on its own. */
class FakeSidecar {
    private val toSidecar = Pipe.open()
    private val toApp = Pipe.open()
    private val requests = DataInputStream(Channels.newInputStream(toSidecar.source()))
    private val replies = Channels.newOutputStream(toApp.sink())

    val connection = SidecarConnection(Channels.newInputStream(toApp.source()), Channels.newOutputStream(toSidecar.sink()))

    class Request(val json: JSONObject, val payload: ByteArray) {
        val id get() = json.getLong("id")
        val op get() = json.getString("op")
    }

    fun next(): Request = Request(JSONObject(String(readBlock(), Charsets.UTF_8)), readBlock())

    fun reply(json: JSONObject, payload: ByteArray = ByteArray(0)) {
        val head = json.toString().toByteArray(Charsets.UTF_8)
        writeBlock(head)
        writeBlock(payload)
        replies.flush()
    }

    fun ok(request: Request, payload: ByteArray = ByteArray(0), fill: JSONObject.() -> Unit = {}) =
        reply(JSONObject().put("id", request.id).put("ok", true).apply(fill), payload)

    fun die() = toApp.sink().close()

    private fun readBlock(): ByteArray {
        val b = ByteArray(4).also { requests.readFully(it) }
        val length = (b[0].toInt() and 0xFF) or (b[1].toInt() and 0xFF shl 8) or
            (b[2].toInt() and 0xFF shl 16) or (b[3].toInt() and 0xFF shl 24)
        return ByteArray(length).also { requests.readFully(it) }
    }

    private fun writeBlock(bytes: ByteArray) {
        val n = bytes.size
        replies.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
        replies.write(bytes)
    }
}
