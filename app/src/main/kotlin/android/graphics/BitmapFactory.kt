package android.graphics

import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Desktop stand-in for android.graphics.BitmapFactory, decoding through ImageIO. Decoded bitmaps
 * are immutable, and a failed decode returns null, as on Android.
 *
 * An object rather than a class so that `use(BitmapFactory::decodeStream)` in AccountSwitcher
 * stays a valid callable reference.
 */
object BitmapFactory {
    fun decodeFile(pathName: String): Bitmap? = try {
        File(pathName).inputStream().use { decodeStream(it) }
    } catch (e: Exception) {
        System.err.println("BitmapFactory: Unable to decode stream: $e")
        null
    }

    /** Leaves [input] open, as Android does. */
    fun decodeStream(input: InputStream?): Bitmap? {
        if (input == null) return null
        val image = try {
            ImageIO.read(MemoryCacheImageInputStream(input))
        } catch (_: Exception) {
            null
        }
        return image?.toBitmap()
    }
}
