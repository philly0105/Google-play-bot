package local.clashfarmer.desktop.ocr

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One line of recognised text, in pixels of the image it was read from. Right and bottom are
 * exclusive, as in android.graphics.Rect.
 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Reads the text in one image. [bgra] holds [width] x [height] pixels, four bytes each in B, G, R,
 * A order, rows top to bottom with no padding. The ML Kit shim calls it on its own thread, one
 * image at a time.
 */
fun interface OcrBackend {
    fun recognize(width: Int, height: Int, bgra: ByteArray): List<OcrLine>
}

/** The backend the ML Kit shim reads through. Until one is installed, every recognition fails. */
object OcrBackends {
    @Volatile
    var current: OcrBackend? = null
        private set

    fun install(backend: OcrBackend?) {
        current = backend
    }
}

/**
 * [bitmap]'s pixels as [OcrBackend] takes them. A colour int is 0xAARRGGBB, so writing it
 * little-endian puts its bytes in B, G, R, A order.
 */
internal fun bgraOf(bitmap: Bitmap): ByteArray {
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val bytes = ByteBuffer.allocate(argb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(argb)
    return bytes.array()
}
