package local.clashfarmer.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import kotlin.math.abs

object ImageMatcher {

    const val SPENT_CARD_SATURATION_THRESHOLD = 60.0

    fun meanSaturation(bitmap: Bitmap, roi: Region): Double {
        val clampLeft = roi.left.coerceIn(0, bitmap.width - 1)
        val clampTop = roi.top.coerceIn(0, bitmap.height - 1)
        val clampRight = roi.right.coerceIn(clampLeft + 1, bitmap.width)
        val clampBottom = roi.bottom.coerceIn(clampTop + 1, bitmap.height)

        var totalSat = 0.0
        var count = 0
        val hsv = FloatArray(3)

        for (y in clampTop until clampBottom) {
            for (x in clampLeft until clampRight) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                totalSat += hsv[1] * 255.0
                count++
            }
        }
        return if (count > 0) totalSat / count else 0.0
    }

    /**
     * Clash renders an all-black screen for tens of seconds while it loads. A frame with
     * almost nothing lit is not a game screen and must not be recognized or acted on.
     */
    fun isBlankFrame(bitmap: Bitmap, sampleStep: Int = 16, litFraction: Double = 0.02): Boolean {
        var lit = 0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum > 24) lit++
                count++
                x += sampleStep
            }
            y += sampleStep
        }
        if (count == 0) return true
        return (lit.toDouble() / count) < litFraction
    }

    fun isUnaffordable(bitmap: Bitmap, costRoi: Region): Boolean {
        val clampLeft = costRoi.left.coerceIn(0, bitmap.width - 1)
        val clampTop = costRoi.top.coerceIn(0, bitmap.height - 1)
        val clampRight = costRoi.right.coerceIn(clampLeft + 1, bitmap.width)
        val clampBottom = costRoi.bottom.coerceIn(clampTop + 1, bitmap.height)

        var redInk = 0
        for (y in clampTop until clampBottom) {
            for (x in clampLeft until clampRight) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 200 && maxOf(g, b) < 180) {
                    redInk++
                }
            }
        }
        return redInk > 50
    }

    fun matches(screen: Bitmap, roi: Region, template: Bitmap, tolerance: Double = 15.0): Boolean {
        return meanDifference(screen, roi, template)?.let { it <= tolerance } ?: false
    }

    fun meanDifference(screen: Bitmap, roi: Region, template: Bitmap): Double? {
        if (roi.width != template.width || roi.height != template.height) return null
        val clampLeft = roi.left.coerceIn(0, screen.width - template.width)
        val clampTop = roi.top.coerceIn(0, screen.height - template.height)

        var totalDiff = 0.0
        val pixelCount = template.width * template.height
        if (pixelCount == 0) return null

        for (dy in 0 until template.height) {
            for (dx in 0 until template.width) {
                val p1 = screen.getPixel(clampLeft + dx, clampTop + dy)
                val p2 = template.getPixel(dx, dy)
                val diffR = abs(Color.red(p1) - Color.red(p2))
                val diffG = abs(Color.green(p1) - Color.green(p2))
                val diffB = abs(Color.blue(p1) - Color.blue(p2))
                val avgDiff = (diffR + diffG + diffB) / 3.0
                totalDiff += avgDiff
            }
        }
        val meanDiff = totalDiff / pixelCount
        return meanDiff
    }

    /**
     * [meanDifference] after scaling the template by the one brightness gain that fits the screen
     * best. Clash 18.600.7 drew the settings panel at about 0.92 of the brightness the identity
     * templates were cut at, which alone raised the right account to 8.5; the gain is held to
     * 0.8..1.25 so a dark frame cannot be brightened into a match.
     */
    fun gainCorrectedDifference(screen: Bitmap, roi: Region, template: Bitmap): Double? {
        if (roi.width != template.width || roi.height != template.height) return null
        val left = roi.left.coerceIn(0, screen.width - template.width)
        val top = roi.top.coerceIn(0, screen.height - template.height)
        val pixels = IntArray(template.width * template.height)
        screen.getPixels(pixels, 0, template.width, left, top, template.width, template.height)
        val reference = IntArray(pixels.size)
        template.getPixels(reference, 0, template.width, 0, 0, template.width, template.height)
        return gainCorrectedDifference(pixels, reference)
    }

    /** The same over ARGB pixels, in step with each other. */
    fun gainCorrectedDifference(screen: IntArray, template: IntArray): Double? {
        if (screen.size != template.size || screen.isEmpty()) return null
        val shifts = intArrayOf(16, 8, 0)
        var cross = 0.0
        var own = 0.0
        for (i in screen.indices) for (s in shifts) {
            val t = (template[i] shr s and 0xFF).toDouble()
            cross += (screen[i] shr s and 0xFF) * t
            own += t * t
        }
        val gain = (if (own > 0) cross / own else 1.0).coerceIn(0.8, 1.25)
        var total = 0.0
        for (i in screen.indices) for (s in shifts) {
            total += abs((screen[i] shr s and 0xFF) - gain * (template[i] shr s and 0xFF))
        }
        return total / (screen.size * 3)
    }

    fun binarizeCrop(bitmap: Bitmap, roi: Region, threshold: Int, scale: Int = 3): Bitmap =
        maskCrop(bitmap, roi, scale) { r, g, b -> (r * 299 + g * 587 + b * 114) / 1000 > threshold }

    /**
     * The village balance's white digits, every channel above [cut], with [gap] blank columns put
     * in front of each glyph. Clash 18.647 draws the storage's fill under these digits, gold at
     * about 211 luminance, so a luminance cut low enough to keep the digits whole inks the fill
     * too; the fill and the coin are coloured and fail a white test. The new font's one is a
     * narrow stroke with a flag, and two of them read as "1S", "US" or "n" until pulled apart.
     */
    fun whiteSpacedCrop(bitmap: Bitmap, roi: Region, cut: Int, gap: Int, scale: Int): Bitmap {
        val left = roi.left.coerceIn(0, bitmap.width - 1)
        val top = roi.top.coerceIn(0, bitmap.height - 1)
        val w = roi.right.coerceIn(left + 1, bitmap.width) - left
        val h = roi.bottom.coerceIn(top + 1, bitmap.height) - top
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        val ink = BooleanArray(pixels.size) {
            val p = pixels[it]
            minOf(Color.red(p), Color.green(p), Color.blue(p)) > cut
        }
        val columns = spacedColumns(BooleanArray(w) { x -> (0 until h).any { y -> ink[y * w + x] } }, gap)
        val outW = columns.size * scale
        val out = IntArray(outW * h * scale) {
            val source = columns[(it % outW) / scale]
            if (source >= 0 && ink[(it / outW / scale) * w + source]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(out, outW, h * scale, Bitmap.Config.ARGB_8888)
    }

    /**
     * The source column each column of a spaced mask copies, -1 for a blank one: two blank
     * columns at each side, and [gap] more in front of every run of inked columns that does not
     * start the strip.
     */
    fun spacedColumns(inked: BooleanArray, gap: Int): IntArray {
        val columns = mutableListOf(-1, -1)
        for (x in inked.indices) {
            if (x > 0 && inked[x] && !inked[x - 1]) repeat(gap) { columns += -1 }
            columns += x
        }
        columns += listOf(-1, -1)
        return columns.toIntArray()
    }

    /**
     * The gold price on a discounted confirm button is saturated yellow, about (230, 230, 0), on
     * a light green whose blue sits near 128 and whose brightness is within a few points of the
     * digits', so no brightness cut separates them. The blue channel does. The struck-through
     * original price under it is white and the black outline is dark, so neither passes.
     */
    fun isYellowInk(r: Int, g: Int, b: Int, blueCut: Int): Boolean =
        r > 170 && g > 170 && b < blueCut && r - b > 120

    fun yellowCrop(bitmap: Bitmap, roi: Region, blueCut: Int, scale: Int): Bitmap =
        maskCrop(bitmap, roi, scale) { r, g, b -> isYellowInk(r, g, b, blueCut) }

    private fun maskCrop(bitmap: Bitmap, roi: Region, scale: Int, ink: (Int, Int, Int) -> Boolean): Bitmap {
        val clampLeft = roi.left.coerceIn(0, bitmap.width - 1)
        val clampTop = roi.top.coerceIn(0, bitmap.height - 1)
        val clampRight = roi.right.coerceIn(clampLeft + 1, bitmap.width)
        val clampBottom = roi.bottom.coerceIn(clampTop + 1, bitmap.height)
        val w = clampRight - clampLeft
        val h = clampBottom - clampTop

        val srcPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, clampLeft, clampTop, w, h)

        val binPixels = IntArray(w * h)
        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            binPixels[i] = if (ink(r, g, b)) Color.BLACK else Color.WHITE
        }

        val intermediate = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        intermediate.setPixels(binPixels, 0, w, 0, 0, w, h)

        if (scale == 1) return intermediate

        val scaled = Bitmap.createScaledBitmap(intermediate, w * scale, h * scale, false)
        intermediate.recycle()
        return scaled
    }
}
