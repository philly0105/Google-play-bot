package local.clashfarmer.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A grey image as one brightness per pixel, row by row. */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {

    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** Each block of [factor] by [factor] pixels averaged into one. */
    fun downsample(factor: Int): GrayImage {
        if (factor == 1) return this
        val w = width / factor
        val h = height / factor
        val out = FloatArray(w * h)
        val area = (factor * factor).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (yy in 0 until factor) {
                    val row = (y * factor + yy) * width + x * factor
                    for (xx in 0 until factor) sum += pixels[row + xx]
                }
                out[y * w + x] = sum / area
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        /** From ARGB pixels, as Bitmap.getPixels gives them. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = FloatArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                out[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
            }
            return GrayImage(width, height, out)
        }
    }
}

/**
 * Where the camera is against the one the wall lattice was measured at: a zoom about the centre
 * of the screen, then a shift. Maps a point measured on the reference frame onto the live one.
 */
data class CameraTransform(val scale: Double, val dx: Double, val dy: Double, val centreX: Double, val centreY: Double) {

    fun mapX(x: Double) = scale * (x - centreX) + centreX + dx
    fun mapY(y: Double) = scale * (y - centreY) + centreY + dy

    fun apply(p: Point) = Point(mapX(p.x.toDouble()).roundToInt(), mapY(p.y.toDouble()).roundToInt())
}

/** The best transform found, and the mean brightness difference left over after applying it. */
data class Registration(val transform: CameraTransform, val difference: Double) {
    val matched: Boolean get() = difference <= CameraRegistration.MAX_DIFFERENCE
}

/**
 * Finds where the live camera sits against the reference frame the wall lattice was fitted on.
 *
 * Pinching out and dragging against the pan limit was meant to put the camera in the same place
 * every phase, and it does not. On 22 September three walks on Blood, each started from a
 * different pan, came to rest up to 8% apart in zoom and 40 pixels apart in position. A wall is
 * sixteen pixels across, so the lattice missed most of what it tapped. Clash resets its camera
 * whenever it comes back to the front, and the pinches do not always take it all the way out.
 *
 * Rather than trust the gestures, the walk measures where they left it: the live frame is
 * compared with the reference over the middle of the village, at every zoom and shift in range,
 * coarse to fine, and the lattice is moved to match.
 *
 * Measured on the frames from that day: a correct match leaves a mean difference of 4 to 25
 * even with a panel open over part of the village, and a battle screen against the village
 * leaves 76.
 */
object CameraRegistration {

    /** Above this the live frame is not the village the lattice was measured on. */
    const val MAX_DIFFERENCE = 40.0

    /** The part of the screen compared: the village, clear of the side buttons and status box. */
    private const val LEFT = 0.156
    private const val RIGHT = 0.875
    private const val TOP = 0.264
    private const val BOTTOM = 0.778

    private class Pass(val factor: Int, val scaleRange: Double, val scaleStep: Double, val shiftRange: Int, val shiftStep: Int)

    /** Wide and coarse first, then narrower and finer around the best so far. */
    private val PASSES = listOf(
        Pass(factor = 8, scaleRange = 0.12, scaleStep = 0.02, shiftRange = 80, shiftStep = 8),
        Pass(factor = 4, scaleRange = 0.02, scaleStep = 0.01, shiftRange = 8, shiftStep = 4),
        Pass(factor = 2, scaleRange = 0.01, scaleStep = 0.005, shiftRange = 4, shiftStep = 2)
    )

    fun register(reference: GrayImage, live: GrayImage): Registration {
        require(reference.width == live.width && reference.height == live.height) {
            "Reference is ${reference.width}x${reference.height}, live frame is ${live.width}x${live.height}"
        }
        val cx = live.width / 2.0
        val cy = live.height / 2.0
        var best = Registration(CameraTransform(1.0, 0.0, 0.0, cx, cy), Double.MAX_VALUE)

        for (pass in PASSES) {
            val ref = reference.downsample(pass.factor)
            val cur = live.downsample(pass.factor)
            val around = best.transform
            val scales = steps(around.scale, pass.scaleRange, pass.scaleStep)
            for (s in scales) {
                for (ox in -pass.shiftRange..pass.shiftRange step pass.shiftStep) {
                    for (oy in -pass.shiftRange..pass.shiftRange step pass.shiftStep) {
                        val t = CameraTransform(s, around.dx + ox, around.dy + oy, cx, cy)
                        val d = difference(ref, cur, pass.factor, t)
                        if (d < best.difference) best = Registration(t, d)
                    }
                }
            }
        }
        return best
    }

    private fun steps(centre: Double, range: Double, step: Double): List<Double> {
        val n = (range / step).roundToInt()
        return (-n..n).map { centre + it * step }
    }

    /**
     * Mean brightness difference between the live frame and the reference carried through [t],
     * at one level of the pyramid. Too little overlap counts as no match at all.
     */
    private fun difference(ref: GrayImage, cur: GrayImage, factor: Int, t: CameraTransform): Double {
        val x0 = (cur.width * LEFT).toInt()
        val x1 = (cur.width * RIGHT).toInt()
        val y0 = (cur.height * TOP).toInt()
        val y1 = (cur.height * BOTTOM).toInt()
        var sum = 0.0
        var n = 0
        var total = 0
        for (y in y0 until y1) {
            // Live pixel centre, in full-size pixels, taken back through the transform.
            val py = (y + 0.5) * factor
            val qy = (((py - t.centreY - t.dy) / t.scale + t.centreY) / factor).toInt()
            for (x in x0 until x1) {
                total++
                val px = (x + 0.5) * factor
                val qx = (((px - t.centreX - t.dx) / t.scale + t.centreX) / factor).toInt()
                if (qx < 0 || qx >= ref.width || qy < 0 || qy >= ref.height) continue
                sum += abs(ref[qx, qy] - cur[x, y])
                n++
            }
        }
        if (n < total * 0.8) return Double.MAX_VALUE
        return sum / n
    }
}
