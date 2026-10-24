package local.clashfarmer.ui

import local.clashfarmer.ui.components.ValueFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueFitTest {
    /** "13.6M/h" in bold sans is about 3.77 em wide; px at [sp] for a screen's density and font scale. */
    private fun rateWidth(sp: Float, density: Float, fontScale: Float) = 3.77f * sp * density * fontScale

    /** 720 px wide at density 2 is 360 dp: (360 - 2 x 16 gutter - 2 x 8 gap) / 3 = 104 dp a tile, 80 dp inside. */
    private val room = 80f * 2

    @Test
    fun aTwoDigitMillionsRateFitsWholeAtFontScalesUpTo130Percent() {
        for (scale in listOf(1.0f, 1.1f, 1.15f, 1.2f, 1.3f)) {
            val sp = ValueFit.sp(22f, room) { rateWidth(it, 2f, scale) }
            assertTrue("fits at $scale", rateWidth(sp, 2f, scale) <= room)
            assertTrue("one sp larger would not fit at $scale", rateWidth(sp + 1f, 2f, scale) > room)
            assertTrue(sp >= ValueFit.MIN_SP)
        }
        assertEquals(21f, ValueFit.sp(22f, room) { rateWidth(it, 2f, 1.0f) }, 0f)
        assertEquals(16f, ValueFit.sp(22f, room) { rateWidth(it, 2f, 1.3f) }, 0f)
    }

    @Test
    fun aValueThatFitsKeepsItsCeiling() {
        // "412" on Home: 1.8 em at 32 sp is 115 px.
        assertEquals(32f, ValueFit.sp(32f, room) { 1.8f * it * 2f }, 0f)
    }

    @Test
    fun aValueTooWideEvenAtTheFloorStopsThereAndEllipsizes() {
        assertEquals(ValueFit.MIN_SP, ValueFit.sp(18f, room) { 20f * it * 2f }, 0f)
    }
}
