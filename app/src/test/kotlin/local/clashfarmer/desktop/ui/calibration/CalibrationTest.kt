package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    @Test
    fun `the 38 targets keep the phone's names and numbering`() {
        assertEquals(38, CALIBRATION_TARGETS.size)
        assertEquals(38, CALIBRATION_TARGETS.map { it.name }.toSet().size)
        CALIBRATION_TARGETS.forEachIndexed { i, target ->
            assertTrue(target.label, target.label.startsWith("${i + 1}. "))
        }
        assertEquals(COLLECTOR, CALIBRATION_TARGETS.last().name)
    }

    @Test
    fun `no description asks for a tap`() {
        val tap = Regex("\\btap", RegexOption.IGNORE_CASE)
        CALIBRATION_TARGETS.forEach { assertFalse(it.description, tap.containsMatchIn(it.description)) }
    }

    @Test
    fun `every target but the collector changes the profile`() {
        val profile = PhoneProfile.createDefault()
        CALIBRATION_TARGETS.filter { it.name != COLLECTOR }.forEach { target ->
            assertNotEquals(target.name, profile, applyTarget(profile, target.name, Point(700, 300)))
        }
    }

    @Test
    fun `the collector and unknown names leave the profile alone`() {
        val profile = PhoneProfile.createDefault()
        assertSame(profile, applyTarget(profile, COLLECTOR, Point(1, 2)))
        assertSame(profile, applyTarget(profile, "nothing", Point(1, 2)))
    }

    @Test
    fun `the viewport corners move one at a time and clear verification`() {
        val start = PhoneProfile.createDefault(1920, 1080).copy(isVerified = true)
        val tl = applyTarget(start, "vp_tl", Point(10, 20))
        assertEquals(Viewport(10, 20, 1920, 1080), tl.viewport)
        assertFalse(tl.isVerified)
        val br = applyTarget(tl, "vp_br", Point(1900, 1070))
        assertEquals(Viewport(10, 20, 1900, 1070), br.viewport)
    }

    @Test
    fun `a perimeter end alone fills the edges before it and keeps its other end`() {
        val end = Point(300, 400)
        val first = applyTarget(PhoneProfile.createDefault(), "perim_2_end", end)
        assertEquals(listOf(Point(0, 0) to Point(0, 0), end to end), first.perimeterSegments)
        val second = applyTarget(first, "perim_2_start", Point(100, 200))
        assertEquals(Point(100, 200) to end, second.perimeterSegments[1])
    }

    @Test
    fun `a wall button sets its cost box once and then moves alone`() {
        val first = applyTarget(PhoneProfile.createDefault(), "gold_cost_btn", Point(500, 300))
        val gold = first.wallConfig!!.currencies.getValue("gold")
        assertEquals(Region(450, 260, 550, 290), gold.costRoi)
        assertEquals(Point(500, 300), gold.buttonPoint)
        val moved = applyTarget(first, "gold_cost_btn", Point(520, 310)).wallConfig!!.currencies.getValue("gold")
        assertEquals(Region(450, 260, 550, 290), moved.costRoi)
        assertEquals(Point(520, 310), moved.buttonPoint)
    }

    @Test
    fun `the next price box ends at the click`() {
        val profile = applyTarget(PhoneProfile.createDefault(), "scout_fee", Point(1000, 200))
        assertEquals(Region(916, 185, 1004, 215), profile.scoutFeeRoi)
    }

    @Test
    fun `a frame fits the view and centres in the spare height`() {
        val fit = FrameFit(960f, 700f, 1920, 1080)
        assertEquals(0.5f, fit.scale, 0f)
        assertEquals(0f, fit.left, 0f)
        assertEquals(80f, fit.top, 0f)
        assertEquals(Point(960, 540), fit.toImage(480f, 350f))
        assertEquals(480f, fit.x(960), 0f)
        assertEquals(350f, fit.y(540), 0f)
    }

    @Test
    fun `a click off the frame picks nothing and the far edges clamp`() {
        val fit = FrameFit(1200f, 540f, 1920, 1080)
        assertEquals(120f, fit.left, 0f)
        assertNull(fit.toImage(119f, 100f))
        assertNull(fit.toImage(500f, 541f))
        assertEquals(Point(0, 0), fit.toImage(120f, 0f))
        assertEquals(Point(1919, 1079), fit.toImage(1080f, 540f))
    }

    @Test
    fun `a 16 by 10 frame fits by its own shape`() {
        // Google Play Games came back at 1860x1162 after a relaunch; the fit must not assume 16:9.
        val tall = FrameFit(930f, 760f, 1860, 1162)
        assertEquals(0.5f, tall.scale, 0f)
        assertEquals(89.5f, tall.top, 0f)
        assertEquals(Point(930, 581), tall.toImage(465f, 380f))
        val wide = FrameFit(2000f, 581f, 1860, 1162)
        assertEquals(535f, wide.left, 0f)
        assertNull(wide.toImage(534f, 10f))
        assertEquals(Point(1859, 1161), wide.toImage(1465f, 581f))
    }
}
