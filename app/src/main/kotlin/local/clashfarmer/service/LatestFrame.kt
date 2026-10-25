package local.clashfarmer.service

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.clashfarmer.log.AppLog
import java.io.ByteArrayOutputStream

/**
 * Keeps a small copy of the bot's newest capture for the dashboard and the remote page.
 *
 * The scale happens on the capture thread because callers recycle the full bitmap as soon as
 * capture returns; the JPEG encode runs on Default.
 */
class LatestFrame(private val scope: CoroutineScope) {
    val store = FrameStore()

    /** The newest scaled copy, kept for a failure snapshot. Never recycled; it is only read. */
    class Scaled(val bitmap: Bitmap, val atMs: Long)

    @Volatile var scaled: Scaled? = null
        private set

    /**
     * The account whose session is running, set by the service once the switcher has verified
     * the village and cleared before any switch and when the session ends. Each frame keeps the
     * value it had when captured.
     */
    @Volatile var accountId: String? = null

    /** Called with every successful capture before the caller may recycle [bitmap]. */
    fun offer(bitmap: Bitmap, atMs: Long) {
        val account = accountId
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, WIDTH, heightFor(bitmap.width, bitmap.height), true) }.getOrNull() ?: return
        this.scaled = Scaled(scaled, atMs)
        scope.launch(Dispatchers.Default) {
            runCatching {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                store.offer(out.toByteArray(), atMs, account)
            }.onFailure { AppLog.log("frame_encode_failed", "error" to it.message) }
        }
    }

    fun clear() {
        scaled = null
        store.clear()
    }

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 216

        /**
         * Desktop: the copy's height at [WIDTH] wide, keeping the frame's shape. The phone's
         * 1600x720 gives [HEIGHT]; a 1740x978 game window gives 270, not a squashed 216.
         */
        fun heightFor(width: Int, height: Int): Int =
            if (width <= 0 || height <= 0) HEIGHT else maxOf(1, Math.round(WIDTH.toDouble() * height / width).toInt())
    }
}
