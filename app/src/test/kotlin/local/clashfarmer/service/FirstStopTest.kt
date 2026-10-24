package local.clashfarmer.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstStopTest {

    @Test
    fun onlyTheFirstStopOfARunCounts() {
        // 06:28:06 on 8 October: "Stopped from floating panel", then the cancelled pinch's
        // "Pinch ... was cancelled by a touch on the screen" for the same run.
        val latch = FirstStop()
        val run = Any()
        assertTrue(latch.isFirst(run))
        assertFalse("the pinch failing after the stop is a side effect", latch.isFirst(run))
        assertFalse(latch.isFirst(run))
    }

    @Test
    fun aNewRunGetsItsOwnFirstStop() {
        val latch = FirstStop()
        assertTrue(latch.isFirst("run 1"))
        assertTrue(latch.isFirst(Any()))
    }

    @Test
    fun aStopWithNoRunAlwaysCounts() {
        val latch = FirstStop()
        assertTrue(latch.isFirst(null))
        assertTrue(latch.isFirst(null))
    }
}
