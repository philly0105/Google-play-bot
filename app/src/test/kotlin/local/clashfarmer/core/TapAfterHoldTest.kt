package local.clashfarmer.core

import local.clashfarmer.core.TapAfterHold.Battle
import local.clashfarmer.core.TapAfterHold.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapAfterHoldTest {

    private fun next(
        waited: Boolean = false,
        aged: Boolean = false,
        stopped: Boolean = false,
        once: Boolean = false,
        canLook: Boolean = false,
        looks: Int = 0
    ) = TapAfterHold.next(waited, aged, stopped, once, canLook, looks)

    @Test fun aTapTheGateDidNotHoldIsSent() {
        for (once in listOf(false, true)) for (canLook in listOf(false, true)) {
            assertEquals(Next.SEND, next(once = once, canLook = canLook))
        }
    }

    @Test fun theWallConfirmationIsRefusedAfterAHold() {
        assertEquals(Next.REFUSE, next(waited = true, once = true, canLook = false))
        assertEquals(Next.REFUSE, next(waited = true, once = true, canLook = true))
        assertEquals("Paused as the wall confirmation was about to be sent", TapAfterHold.REFUSED)
    }

    @Test fun aStopDuringTheHoldGoesOnToTheCheckWithItsOwnReason() {
        // Sent on to RunGuard.checkCanAct, which throws the stop's reason rather than the refusal.
        assertEquals(Next.SEND, next(waited = true, stopped = true, once = true, canLook = true))
        assertEquals(Next.SEND, next(aged = true, stopped = true))
    }

    @Test fun aTapWithALookLooksAfterAHoldAndOneWithoutIsRefused() {
        assertEquals(Next.LOOK, next(waited = true, canLook = true))
        // No look, no blind send: the tap is held and its read loop decides afresh.
        assertEquals(Next.REFUSE, next(waited = true, canLook = false))
    }

    @Test fun anAgedTapLooksIfItCanAndIsHeldIfItCannot() {
        assertEquals(Next.LOOK, next(aged = true, canLook = true))
        assertEquals(Next.REFUSE, next(aged = true, canLook = false))
    }

    @Test fun anAgedOnlyConfirmationMayLookButOneAfterAPauseMayNot() {
        assertEquals(Next.LOOK, next(aged = true, once = true, canLook = true))
        assertEquals(Next.REFUSE, next(aged = true, once = true, canLook = false))
        assertEquals(Next.REFUSE, next(waited = true, aged = true, once = true, canLook = true))
    }

    @Test fun twoLooksAreTheMost() {
        assertEquals(Next.LOOK, next(aged = true, canLook = true, looks = 1))
        assertEquals(Next.REFUSE, next(aged = true, canLook = true, looks = 2))
        assertEquals(Next.REFUSE, next(waited = true, canLook = true, looks = 2))
    }

    @Test fun aFreshFrameWithNoPauseIsSentWhateverWasLookedAt() {
        assertEquals(Next.SEND, next(canLook = true, looks = 2))
        assertEquals(Next.SEND, next(once = true, looks = 1))
    }

    @Test fun aTapAgesPastTwoSecondsOrOnAClockThatSteppedBack() {
        assertEquals(2_000L, TapAfterHold.MAX_DECISION_AGE_MS)
        assertFalse("No decision frame, nothing to age", TapAfterHold.aged(null, 50_000L))
        assertFalse(TapAfterHold.aged(10_000L, 10_000L))
        assertFalse(TapAfterHold.aged(10_000L, 11_999L))
        assertFalse(TapAfterHold.aged(10_000L, 12_000L))
        assertTrue(TapAfterHold.aged(10_000L, 12_001L))
        // The 15 October Attack tap went 19 s after its frame.
        assertTrue(TapAfterHold.aged(10_000L, 29_000L))
        assertTrue("A clock that stepped back proves nothing", TapAfterHold.aged(10_000L, 9_999L))
    }

    @Test fun heldIsRecognisedOnlyByItsOwnMessage() {
        assertTrue(TapAfterHold.isHeld(StopException(TapAfterHold.HELD)))
        assertFalse(TapAfterHold.isHeld(StopException(TapAfterHold.REFUSED)))
        assertFalse(TapAfterHold.isHeld(StopException("Session is stopped: Stopped from the overlay")))
        assertFalse(TapAfterHold.isHeld(IllegalStateException(TapAfterHold.HELD)))
    }

    @Test fun aBattleIsStillUpOnlyOnTheBattleOrTheScout() {
        for (afterPause in listOf(false, true)) {
            assertEquals(Battle.GO, TapAfterHold.battle(ScreenState.BATTLE, afterPause))
            assertEquals(Battle.GO, TapAfterHold.battle(ScreenState.SCOUT, afterPause))
            for (state in listOf(ScreenState.HOME, ScreenState.RESULT, ScreenState.MENU)) {
                assertEquals(Battle.LEFT, TapAfterHold.battle(state, afterPause))
            }
        }
        // A spell's flash reads as nothing mid-battle; after a pause it could be anything.
        assertEquals(Battle.GO, TapAfterHold.battle(ScreenState.UNKNOWN, afterPause = false))
        assertEquals(Battle.LEFT, TapAfterHold.battle(ScreenState.UNKNOWN, afterPause = true))
    }

    @Test fun aBattleThatLeftIsARecoverableStop() {
        val left: Throwable = BattleLeft(ScreenState.HOME)
        assertTrue(left is StopException)
        assertEquals("Unexpected transition screen: HOME after the battle left", left.message)
        assertTrue(RecoveryPolicy.isRecoverable(left.message))
    }
}
