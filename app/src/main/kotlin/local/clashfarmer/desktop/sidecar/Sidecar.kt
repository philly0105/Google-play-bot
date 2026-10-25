package local.clashfarmer.desktop.sidecar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The app's sidecar, `ClashFarmer.Native.exe serve`, with one call per op in docs/SIDECAR.md.
 * [connect] starts a sidecar; the first call after one dies starts the next.
 */
class Sidecar(private val connect: () -> SidecarConnection) : AutoCloseable {
    private val lock = Mutex()
    private var current: SidecarConnection? = null
    @Volatile private var closed = false

    /** What [keepAwake] last asked for. The power requests die with a sidecar, so a new one is told again. */
    @Volatile private var awake = false

    suspend fun request(op: String, args: JSONObject = JSONObject(), payload: ByteArray? = null, timeoutMs: Long = TIMEOUT_MS): Reply =
        connection().call(op, args, payload, timeoutMs)

    suspend fun hello(): Hello {
        val json = request("hello").json
        // A sidecar from before the field answers without it; one whose reader loaded sends null.
        val ocrError = if (json.isNull("ocrError")) null else json.getString("ocrError")
        return Hello(json.getString("version"), json.getInt("pid"), json.getBoolean("ocrReady"), ocrError)
    }

    /** The Clash window from Google Play Games, looked up again; null when there is none. */
    suspend fun find(): ClashWindow? {
        val json = request("find").json
        if (!json.getBoolean("found")) return null
        return ClashWindow(
            top = json.getLong("top"),
            game = json.getLong("game"),
            title = json.getString("title"),
            iconic = json.getBoolean("iconic"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            foreground = json.getBoolean("foreground"),
            monitorDpi = json.getInt("monitorDpi"),
            // A sidecar from before the field answers without it.
            visible = json.optBoolean("visible", true)
        )
    }

    /** The game surface's newest frame. With [restore], a minimized window is restored without focus first. */
    suspend fun capture(restore: Boolean = true, timeoutMs: Int = 3000): Frame {
        val reply = request(
            "capture",
            JSONObject().put("restore", restore).put("timeoutMs", timeoutMs),
            timeoutMs = timeoutMs + SLACK_MS
        )
        val width = reply.json.getInt("width")
        val height = reply.json.getInt("height")
        if (reply.payload.size != width * height * 4) {
            throw SidecarException("failed", "Frame of ${reply.payload.size} bytes for ${width}x$height")
        }
        return Frame(width, height, reply.json.getLong("timestampMs"), bgraToArgb(reply.payload))
    }

    /** One finger along [strokes] in turn; returns once the button is up. */
    suspend fun gesture(strokes: List<Stroke>) {
        val list = JSONArray()
        for (stroke in strokes) {
            list.put(
                JSONObject()
                    .put("from", JSONArray().put(stroke.fromX).put(stroke.fromY))
                    .put("to", JSONArray().put(stroke.toX).put(stroke.toY))
                    .put("ms", stroke.ms)
            )
        }
        request("gesture", JSONObject().put("strokes", list), timeoutMs = strokes.sumOf { it.ms } + SLACK_MS)
    }

    /** Negative [notches] zoom out around ([x], [y]), positive zoom in. */
    suspend fun wheel(x: Int, y: Int, notches: Int, intervalMs: Int = 40) {
        request(
            "wheel",
            JSONObject().put("x", x).put("y", y).put("notches", notches).put("intervalMs", intervalMs),
            timeoutMs = abs(notches).toLong() * intervalMs + SLACK_MS
        )
    }

    suspend fun key(vk: Int) {
        request("key", JSONObject().put("vk", vk))
    }

    /** The text lines in a BGRA image laid out as a frame is, boxes in its pixels, top to bottom. */
    suspend fun ocr(width: Int, height: Int, bgra: ByteArray): List<TextLine> {
        val reply = request("ocr", JSONObject().put("width", width).put("height", height), bgra, OCR_TIMEOUT_MS)
        val lines = reply.json.getJSONArray("lines")
        return List(lines.length()) { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            TextLine(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3), line.getDouble("score").toFloat())
        }
    }

    /** Sizes the game surface without activating the window; returns the size it ended at, which keeps the surface's aspect. */
    suspend fun resize(width: Int, height: Int): SurfaceSize {
        val json = request("resize", JSONObject().put("width", width).put("height", height), timeoutMs = RESIZE_TIMEOUT_MS).json
        return SurfaceSize(json.getInt("width"), json.getInt("height"))
    }

    suspend fun launch(url: String) {
        request("launch", JSONObject().put("url", url))
    }

    suspend fun power(): PowerState {
        val json = request("power").json
        return PowerState(json.getBoolean("ac"), json.getInt("batteryPercent"), json.getBoolean("saver"))
    }

    suspend fun keepAwake(on: Boolean) {
        // Recorded after sending, so a sidecar started for this call isn't told twice.
        try {
            request("keepAwake", JSONObject().put("on", on))
        } finally {
            awake = on
        }
    }

    suspend fun input(): InputState {
        val json = request("input").json
        return InputState(json.getLong("idleMs"), json.getBoolean("gameForeground"), json.getBoolean("cursorInGame"))
    }

    override fun close() {
        closed = true
        current?.close()
    }

    private suspend fun connection(): SidecarConnection = lock.withLock {
        if (closed) throw SidecarException("failed", "Sidecar closed")
        current?.takeIf { it.isOpen }?.let { return@withLock it }
        val fresh = withContext(Dispatchers.IO) { connect() }
        try {
            if (awake) fresh.call("keepAwake", JSONObject().put("on", true), timeoutMs = TIMEOUT_MS)
        } catch (e: Exception) {
            fresh.close()
            throw e
        }
        current = fresh
        fresh
    }

    companion object {
        const val TIMEOUT_MS = 10_000L

        /** Time on top of a call's own duration, for the lane ahead of it and a capture session starting. */
        private const val SLACK_MS = 10_000L

        /** The first OCR call waits for the models to load. */
        private const val OCR_TIMEOUT_MS = 30_000L
        private const val RESIZE_TIMEOUT_MS = 15_000L

        /** Starts [exe] in serve mode. Its log lines go to [log], from other threads. */
        fun launching(exe: File, log: (String) -> Unit): Sidecar = Sidecar {
            val process = try {
                ProcessBuilder(exe.path, "serve").directory(exe.parentFile).start()
            } catch (e: IOException) {
                throw SidecarException("failed", "Can't start ${exe.path}: ${e.message}")
            }
            process.onExit().thenAccept { log("Sidecar ${it.pid()} exited with code ${it.exitValue()}") }
            thread(name = "sidecar-log", isDaemon = true) {
                runCatching { process.errorStream.bufferedReader().forEachLine(log) }
            }
            SidecarConnection(process.inputStream, process.outputStream) {
                // Its input is closed by now; it exits on its own once a held button is up.
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }
}

/** [ocrError] is why the text reader failed to load, or null while it loads or once it has. */
data class Hello(val version: String, val pid: Int, val ocrReady: Boolean, val ocrError: String? = null)

/**
 * [top] and [game] are window handles; [width] and [height] are the game surface's physical pixels.
 * [visible] is false once the window was closed by hand: Google Play Games hides it and keeps the
 * game running, and starting Clash then is the relaunch that broke input (docs/gpg-spike.md).
 */
data class ClashWindow(
    val top: Long,
    val game: Long,
    val title: String,
    val iconic: Boolean,
    val width: Int,
    val height: Int,
    val foreground: Boolean,
    val monitorDpi: Int,
    val visible: Boolean = true
)

/** [pixels] are ARGB, top row first, the layout Android's Bitmap keeps. [timestampMs] is Unix ms. */
class Frame(val width: Int, val height: Int, val timestampMs: Long, val pixels: IntArray)

/** Points are game surface pixels. A tap is a stroke whose two ends are the same point. */
data class Stroke(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val ms: Long)

data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)

data class SurfaceSize(val width: Int, val height: Int)

/** [batteryPercent] is -1 when Windows doesn't know it. */
data class PowerState(val ac: Boolean, val batteryPercent: Int, val saver: Boolean)

/** [idleMs] counts from the last real mouse or keyboard input anywhere; the bot's own input doesn't count. */
data class InputState(val idleMs: Long, val gameForeground: Boolean, val cursorInGame: Boolean)

/** BGRA bytes read as little-endian ints are ARGB. Alpha is made opaque, as a phone screenshot's is. */
internal fun bgraToArgb(bgra: ByteArray): IntArray {
    val argb = IntArray(bgra.size / 4)
    ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
    for (i in argb.indices) argb[i] = argb[i] or OPAQUE
    return argb
}

private const val OPAQUE = 0xFF shl 24
