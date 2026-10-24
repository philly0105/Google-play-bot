package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class CameraRegistrationTest {

    private val w = 1600
    private val h = 720

    /** Texture with detail at several sizes, like a village and unlike a flat colour. */
    private fun village(seed: Int): (Double, Double) -> Float {
        val rnd = Random(seed)
        val waves = List(12) { DoubleArray(4) { rnd.nextDouble() } }
        return { x, y ->
            var v = 128.0
            for (p in waves) {
                val fx = 0.004 + p[0] * 0.06
                val fy = 0.004 + p[1] * 0.06
                v += 22 * sin(fx * x + fy * y + p[2] * 6.28) * (0.5 + p[3])
            }
            v.toFloat()
        }
    }

    private fun render(f: (Double, Double) -> Float, t: CameraTransform? = null): GrayImage {
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // What sits at (x, y) on the live frame came from here on the reference.
            val qx = if (t == null) x.toDouble() else (x - t.centreX - t.dx) / t.scale + t.centreX
            val qy = if (t == null) y.toDouble() else (y - t.centreY - t.dy) / t.scale + t.centreY
            px[y * w + x] = f(qx, qy)
        }
        return GrayImage(w, h, px)
    }

    @Test
    fun recoversTheZoomAndShiftTheGesturesLeft() {
        val scene = village(7)
        val reference = render(scene)
        // The worst of the 22 September walks: 3% further out and 28 pixels up and left.
        val truth = CameraTransform(0.97, -28.0, -12.0, w / 2.0, h / 2.0)
        val reg = CameraRegistration.register(reference, render(scene, truth))

        assertTrue(reg.matched)
        assertEquals(0.97, reg.transform.scale, 0.006)
        assertEquals(-28.0, reg.transform.dx, 3.0)
        assertEquals(-12.0, reg.transform.dy, 3.0)
    }

    @Test
    fun theSameCameraRegistersAsNoChange() {
        val reference = render(village(3))
        val reg = CameraRegistration.register(reference, reference)
        assertEquals(1.0, reg.transform.scale, 1e-9)
        assertEquals(0.0, reg.transform.dx, 1e-9)
        assertEquals(0.0, reg.transform.dy, 1e-9)
        assertEquals(0.0, reg.difference, 1e-6)
    }

    @Test
    fun aDifferentScreenIsNotAMatch() {
        val reference = render(village(1))
        val battle = render({ x, y -> if (((x / 40).toInt() + (y / 40).toInt()) % 2 == 0) 20f else 235f })
        assertFalse(CameraRegistration.register(reference, battle).matched)
    }
}
