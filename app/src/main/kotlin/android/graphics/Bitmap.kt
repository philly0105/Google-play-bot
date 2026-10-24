package android.graphics

import java.awt.image.BufferedImage
import java.io.IOException
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Desktop stand-in for android.graphics.Bitmap: ARGB_8888 pixels held as Android colour ints
 * (0xAARRGGBB, not premultiplied) in an IntArray, with the part of the Android API the copied code
 * calls. Argument checks, exceptions and mutability follow AOSP.
 *
 * One difference: [createBitmap] (from a source) and [createScaledBitmap] always return a new
 * mutable bitmap. Android returns the source itself when it is immutable and the request covers
 * all of it unscaled. No copied code relies on that, and ScreenReader.observe would recycle the
 * caller's frame through it when a viewport wider than the frame is clamped to the whole frame.
 */
class Bitmap private constructor(
    val width: Int,
    val height: Int,
    private var pixels: IntArray,
    val isMutable: Boolean
) {
    enum class Config { ARGB_8888 }

    enum class CompressFormat { JPEG, PNG }

    var isRecycled: Boolean = false
        private set

    /** Frees the pixels. Every later pixel access throws, as on Android; a second call does nothing. */
    fun recycle() {
        if (isRecycled) return
        isRecycled = true
        pixels = IntArray(0)
    }

    fun getPixel(x: Int, y: Int): Int {
        checkRecycled("Can't call getPixel() on a recycled bitmap")
        checkPixelAccess(x, y)
        return pixels[y * width + x]
    }

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        checkRecycled("Can't call getPixels() on a recycled bitmap")
        if (width == 0 || height == 0) return
        checkPixelsAccess(x, y, width, height, offset, stride, pixels)
        for (row in 0 until height) {
            System.arraycopy(this.pixels, (y + row) * this.width + x, pixels, offset + row * stride, width)
        }
    }

    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        checkRecycled("Can't call setPixels() on a recycled bitmap")
        if (!isMutable) throw IllegalStateException()
        if (width == 0 || height == 0) return
        checkPixelsAccess(x, y, width, height, offset, stride, pixels)
        for (row in 0 until height) {
            System.arraycopy(pixels, offset + row * stride, this.pixels, (y + row) * this.width + x, width)
        }
    }

    /**
     * Writes the bitmap to [stream] through ImageIO; false when the encoder fails. PNG ignores
     * [quality]. JPEG drops alpha, which the opaque screen frames do not have.
     */
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        checkRecycled("Can't compress a recycled bitmap")
        if (quality < 0 || quality > 100) throw IllegalArgumentException("quality must be 0..100")
        val image = when (format) {
            CompressFormat.PNG -> toBufferedImage()
            CompressFormat.JPEG -> BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
                it.setRGB(0, 0, width, height, pixels, 0, width)
            }
        }
        val writer = ImageIO.getImageWritersByFormatName(format.name.lowercase()).asSequence().firstOrNull()
            ?: return false
        return try {
            MemoryCacheImageOutputStream(stream).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam
                if (format == CompressFormat.JPEG) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
            true
        } catch (_: IOException) {
            false
        } finally {
            writer.dispose()
        }
    }

    private fun checkRecycled(message: String) {
        if (isRecycled) throw IllegalStateException(message)
    }

    private fun checkPixelAccess(x: Int, y: Int) {
        checkXYSign(x, y)
        if (x >= width) throw IllegalArgumentException("x must be < bitmap.width()")
        if (y >= height) throw IllegalArgumentException("y must be < bitmap.height()")
    }

    private fun checkPixelsAccess(x: Int, y: Int, width: Int, height: Int, offset: Int, stride: Int, pixels: IntArray) {
        checkXYSign(x, y)
        if (width < 0) throw IllegalArgumentException("width must be >= 0")
        if (height < 0) throw IllegalArgumentException("height must be >= 0")
        if (x + width > this.width) throw IllegalArgumentException("x + width must be <= bitmap.width()")
        if (y + height > this.height) throw IllegalArgumentException("y + height must be <= bitmap.height()")
        if (Math.abs(stride) < width) throw IllegalArgumentException("abs(stride) must be >= width")
        val lastScanline = offset + (height - 1) * stride
        if (offset < 0 || offset + width > pixels.size || lastScanline < 0 || lastScanline + width > pixels.size) {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    companion object {
        /** A mutable bitmap, every pixel transparent black. */
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            checkWidthHeight(width, height)
            return Bitmap(width, height, IntArray(width * height), isMutable = true)
        }

        /** An immutable bitmap holding a copy of the first [width] x [height] [colors]. */
        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap {
            checkWidthHeight(width, height)
            if (width.toLong() * height > colors.size) throw ArrayIndexOutOfBoundsException()
            return Bitmap(width, height, colors.copyOf(width * height), isMutable = false)
        }

        /** A new mutable bitmap holding the given part of [source]. */
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            checkXYSign(x, y)
            checkWidthHeight(width, height)
            if (x + width > source.width) throw IllegalArgumentException("x + width must be <= bitmap.width()")
            if (y + height > source.height) throw IllegalArgumentException("y + height must be <= bitmap.height()")
            if (source.isRecycled) throw IllegalArgumentException("cannot use a recycled source in createBitmap")
            val out = IntArray(width * height)
            for (row in 0 until height) {
                System.arraycopy(source.pixels, (y + row) * source.width + x, out, row * width, width)
            }
            return Bitmap(width, height, out, isMutable = true)
        }

        /**
         * A new mutable bitmap of [src] scaled to [dstWidth] x [dstHeight]: nearest neighbour, or
         * bilinear with [filter], sampling at pixel centres as Skia does. Skia interpolates
         * premultiplied colours and this does not; the two agree on opaque pixels.
         */
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            if (src.isRecycled) throw IllegalArgumentException("cannot use a recycled source in createBitmap")
            checkWidthHeight(dstWidth, dstHeight)
            val out = IntArray(dstWidth * dstHeight)
            if (filter) {
                bilinear(src, dstWidth, dstHeight, out)
            } else {
                val columns = IntArray(dstWidth) { ((2L * it + 1) * src.width / (2L * dstWidth)).toInt() }
                for (dy in 0 until dstHeight) {
                    val row = ((2L * dy + 1) * src.height / (2L * dstHeight)).toInt() * src.width
                    for (dx in 0 until dstWidth) out[dy * dstWidth + dx] = src.pixels[row + columns[dx]]
                }
            }
            return Bitmap(dstWidth, dstHeight, out, isMutable = true)
        }

        private fun bilinear(src: Bitmap, dstWidth: Int, dstHeight: Int, out: IntArray) {
            val sx = src.width.toDouble() / dstWidth
            val sy = src.height.toDouble() / dstHeight
            for (dy in 0 until dstHeight) {
                val v = (dy + 0.5) * sy - 0.5
                val y0 = floor(v).toInt()
                val fy = v - y0
                val top = y0.coerceIn(0, src.height - 1) * src.width
                val bottom = (y0 + 1).coerceIn(0, src.height - 1) * src.width
                for (dx in 0 until dstWidth) {
                    val u = (dx + 0.5) * sx - 0.5
                    val x0 = floor(u).toInt()
                    val fx = u - x0
                    val left = x0.coerceIn(0, src.width - 1)
                    val right = (x0 + 1).coerceIn(0, src.width - 1)
                    val p00 = src.pixels[top + left]
                    val p10 = src.pixels[top + right]
                    val p01 = src.pixels[bottom + left]
                    val p11 = src.pixels[bottom + right]
                    out[dy * dstWidth + dx] = mix(p00, p10, p01, p11, fx, fy, 24) or
                        mix(p00, p10, p01, p11, fx, fy, 16) or
                        mix(p00, p10, p01, p11, fx, fy, 8) or
                        mix(p00, p10, p01, p11, fx, fy, 0)
                }
            }
        }

        /** One channel, the byte at [shift], of the four pixels weighted by their distance. */
        private fun mix(p00: Int, p10: Int, p01: Int, p11: Int, fx: Double, fy: Double, shift: Int): Int {
            val c = (1 - fx) * (1 - fy) * (p00 ushr shift and 0xFF) +
                fx * (1 - fy) * (p10 ushr shift and 0xFF) +
                (1 - fx) * fy * (p01 ushr shift and 0xFF) +
                fx * fy * (p11 ushr shift and 0xFF)
            return c.roundToInt().coerceIn(0, 255) shl shift
        }

        private fun checkXYSign(x: Int, y: Int) {
            if (x < 0) throw IllegalArgumentException("x must be >= 0")
            if (y < 0) throw IllegalArgumentException("y must be >= 0")
        }

        private fun checkWidthHeight(width: Int, height: Int) {
            if (width <= 0) throw IllegalArgumentException("width must be > 0")
            if (height <= 0) throw IllegalArgumentException("height must be > 0")
        }
    }
}
