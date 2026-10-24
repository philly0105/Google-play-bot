package local.clashfarmer.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ButtonTapTest {
    @Test fun repeatedButtonTapsVaryWithinThreePixels() {
        val random = Random(35)
        val points = List(200) { buttonTapPoint(Point(100, 200), 1600, 720, random) }
        assertTrue(points.toSet().size > 1)
        assertTrue(points.all { it.x in 97..103 && it.y in 197..203 })
    }

    @Test fun cornersAndSinglePixelDisplaysStayInBounds() {
        val random = Random(8)
        for (point in listOf(Point(0, 0), Point(1599, 719))) {
            repeat(200) {
                val tap = buttonTapPoint(point, 1600, 720, random)
                assertTrue(tap.x in 0..1599 && tap.y in 0..719)
                assertTrue(kotlin.math.abs(tap.x - point.x) <= 3)
                assertTrue(kotlin.math.abs(tap.y - point.y) <= 3)
            }
        }
        assertEquals(Point(0, 0), buttonTapPoint(Point(0, 0), 1, 1, random))
    }

    @Test fun invalidCalibrationIsRejectedRatherThanClampedIntoTheScreen() {
        for (point in listOf(Point(-1, 20), Point(1600, 20), Point(20, 720))) {
            assertTrue(runCatching { buttonTapPoint(point, 1600, 720) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
