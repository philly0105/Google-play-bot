package local.clashfarmer.service

import local.clashfarmer.core.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A held swipe's strokes and what its drag's callback sends after it, without a device: the
 * gesture itself needs the system, but the strokes swipeOnce builds and the choice between the
 * hold and the lift alone do not.
 */
class HeldSwipeTest {

    @Test fun aHeldSwipeIsTheDragThenAHoldThatStartsWhereTheDragEnds() {
        val strokes = swipeStrokes(Point(1330, 500), Point(1330, 250), 500L, 300L)

        assertEquals(
            listOf(
                SwipeStroke(Point(1330, 500), Point(1330, 250), 500L, willContinue = true),
                SwipeStroke(Point(1330, 250), Point(1330, 248), 300L, willContinue = false)
            ),
            strokes
        )
        // The system cancels a continuation that does not start where the stroke before it ended.
        assertEquals(strokes[0].to, strokes[1].from)
    }

    @Test fun theHoldCreepsTheWayTheDragWent() {
        val down = swipeStrokes(Point(1330, 250), Point(1330, 500), 500L, 300L)
        assertEquals(SwipeStroke(Point(1330, 500), Point(1330, 502), 300L, willContinue = false), down[1])

        val across = swipeStrokes(Point(900, 400), Point(600, 300), 400L, 200L)
        assertEquals(SwipeStroke(Point(600, 300), Point(598, 298), 200L, willContinue = false), across[1])
    }

    @Test fun aSwipeWithNoHoldIsOneStrokeThatLifts() {
        val strokes = swipeStrokes(Point(1330, 500), Point(1330, 250), 500L, 0L)

        assertEquals(listOf(SwipeStroke(Point(1330, 500), Point(1330, 250), 500L, willContinue = false)), strokes)
        assertFalse(strokes.single().willContinue)
    }

    @Test fun theDragSendsTheHoldOnlyWhenTheActionIsCurrentAndTheSwipeStillWaits() {
        assertEquals(AfterDrag.HOLD, afterDrag(current = true, waiting = true))
        // Every other case still lifts the finger the drag left down.
        assertEquals(AfterDrag.LIFT, afterDrag(current = true, waiting = false))
        assertEquals(AfterDrag.LIFT, afterDrag(current = false, waiting = true))
        assertEquals(AfterDrag.LIFT, afterDrag(current = false, waiting = false))
    }
}
