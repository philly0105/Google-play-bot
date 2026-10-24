package local.clashfarmer.ui

import local.clashfarmer.ui.components.ChartScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {
    @Test
    fun niceMaxRoundsUpToOneTwoTwoAndAHalfOrFive() {
        assertEquals(1.0, ChartScale.niceMax(1.0), 0.0)
        assertEquals(2.0, ChartScale.niceMax(1.2), 0.0)
        assertEquals(2.5, ChartScale.niceMax(2.1), 0.0)
        assertEquals(5.0, ChartScale.niceMax(3.0), 0.0)
        assertEquals(10.0, ChartScale.niceMax(7.0), 0.0)
        assertEquals(25_000_000.0, ChartScale.niceMax(21_300_000.0), 0.0)
        assertEquals(20_000_000.0, ChartScale.niceMax(17_800_000.0), 0.0)
        assertEquals(0.5, ChartScale.niceMax(0.42), 1e-12)
    }

    @Test
    fun anExactRoundNumberIsItsOwnTop() {
        assertEquals(5_000_000.0, ChartScale.niceMax(5_000_000.0), 0.0)
        assertEquals(100.0, ChartScale.niceMax(100.0), 0.0)
    }

    @Test
    fun allZeroOrEmptyDataStillHasAnAxisAndFlatBars() {
        val zeros = listOf(0.0, 0.0, 0.0)
        assertEquals(1.0, ChartScale.top(zeros), 0.0)
        assertEquals(1.0, ChartScale.top(emptyList()), 0.0)
        assertEquals(1.0, ChartScale.top(listOf(null, null)), 0.0)
        assertEquals(1.0, ChartScale.niceMax(Double.NaN), 0.0)
        assertEquals(1.0, ChartScale.niceMax(-3.0), 0.0)
        zeros.forEach { assertEquals(0f, ChartScale.height(it, 1.0, 200f), 0f) }
        assertNull(ChartScale.tallest(zeros))
        assertEquals(0f, ChartScale.fraction(0.0, 0.0), 0f)
    }

    @Test
    fun barHeightsScaleToTheTopAndClamp() {
        assertEquals(100f, ChartScale.height(12.5, 25.0, 200f), 1e-4f)
        assertEquals(200f, ChartScale.height(25.0, 25.0, 200f), 1e-4f)
        assertEquals(200f, ChartScale.height(40.0, 25.0, 200f), 1e-4f)
        assertEquals(0f, ChartScale.height(-4.0, 25.0, 200f), 0f)
        assertEquals(0f, ChartScale.height(null, 25.0, 200f), 0f)
    }

    @Test
    fun aSingleBarIsCentredAndCappedNotStretched() {
        val slots = ChartScale.slots(1, 300f, maxBar = 28f)
        assertEquals(1, slots.size)
        assertEquals(28f, slots[0].width, 1e-4f)
        assertEquals(150f, slots[0].center, 1e-4f)
        assertEquals(0, ChartScale.tallest(listOf(4.0)))
        assertEquals(1f, ChartScale.fraction(4.0, 4.0), 0f)
    }

    @Test
    fun slotsSpanTheWidthWithoutOverlap() {
        val slots = ChartScale.slots(14, 320f, maxBar = 28f, gap = 0.3f)
        assertEquals(14, slots.size)
        slots.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left) }
        assertTrue(slots.first().left >= 0f)
        assertTrue(slots.last().right <= 320f)
        assertTrue(ChartScale.slots(0, 320f, 28f).isEmpty())
    }

    @Test
    fun twoSeriesSplitOneSlotSideBySide() {
        val halves = ChartScale.split(ChartScale.Span(0f, 22f), 2)
        assertEquals(2, halves.size)
        assertEquals(10f, halves[0].width, 1e-4f)
        assertEquals(12f, halves[1].left, 1e-4f)
        assertEquals(22f, halves[1].right, 1e-4f)
    }

    @Test
    fun xLabelsThinToFit() {
        assertEquals(1, ChartScale.labelEvery(5, 7))
        assertEquals(2, ChartScale.labelEvery(14, 7))
        assertEquals(3, ChartScale.labelEvery(14, 5))
        assertEquals(1, ChartScale.labelEvery(0, 5))
        assertEquals(1, ChartScale.labelEvery(10, 0))
    }

    @Test
    fun onlyTheTallestBarIsLabelled() {
        assertEquals(2, ChartScale.tallest(listOf(3.0, null, 9.0, 9.0, 1.0)))
    }
}
