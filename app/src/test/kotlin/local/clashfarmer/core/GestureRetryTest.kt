package local.clashfarmer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRetryTest {

    @Test fun aCancelFollowedByPauseIsHeldAfterResumeWithoutALook() = runTest {
        var sends = 0
        var paused = false
        var resumed = false
        var stoppedWith: String? = null
        val thrown = runCatching {
            sendResendingAfterPause(
                what = "Tap",
                isPaused = { paused },
                awaitResume = { resumed = true; paused = false },
                isStopped = { false },
                stop = { stoppedWith = it }
            ) {
                sends++
                if (sends == 1) {
                    paused = true
                    throw GestureCancelledException("cancelled")
                }
            }
        }.exceptionOrNull()
        // Nothing goes back out blind: the caller's read loop reads again and decides afresh.
        assertEquals(TapAfterHold.HELD, thrown?.message)
        assertEquals(1, sends)
        assertTrue(resumed)
        assertNull("A held tap is not a stop of the run", stoppedWith)
    }

    @Test fun aCancelWithoutPauseStillStops() = runTest {
        var stoppedWith: String? = null
        var looks = 0
        val thrown = runCatching {
            sendResendingAfterPause("Tap", { looks++; false }, {}, { false }, { stoppedWith = it }) {
                throw GestureCancelledException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is StopException)
        assertTrue(stoppedWith!!.contains("cancelled"))
        // Four looks 800 ms apart, about 3.2 s, before the cancel counts as a hand on the phone.
        assertEquals(4, looks)
        assertEquals(3_200L, testScheduler.currentTime)
    }

    @Test fun aPauseSeenOnTheSecondLookIsHeldWithoutALook() = runTest {
        // The shade over immersive Clash opens on the second swipe, after the first look.
        var sends = 0
        var looks = 0
        var stoppedWith: String? = null
        val thrown = runCatching {
            sendResendingAfterPause(
                what = "Tap",
                isPaused = { looks++; looks >= 2 },
                awaitResume = {},
                isStopped = { false },
                stop = { stoppedWith = it }
            ) {
                sends++
                if (sends == 1) throw GestureCancelledException("cancelled")
            }
        }.exceptionOrNull()
        assertEquals(TapAfterHold.HELD, thrown?.message)
        assertEquals(1, sends)
        assertEquals(2, looks)
        assertEquals(1_600L, testScheduler.currentTime)
        assertNull(stoppedWith)
    }

    @Test fun aLookAfterResumeThatThrowsSendsNothingMore() = runTest {
        // S11: a takeover dialog seen after the pause ends the run before the held tap is resent.
        var sends = 0
        var looks = 0
        val thrown = runCatching {
            sendResendingAfterPause(
                what = "Tap",
                isPaused = { true },
                awaitResume = {},
                isStopped = { false },
                stop = {},
                afterResume = { looks++; throw StopException(Policy.VILLAGE_TAKEN) }
            ) {
                sends++
                throw GestureCancelledException("cancelled")
            }
        }.exceptionOrNull()
        assertEquals(Policy.VILLAGE_TAKEN, thrown?.message)
        assertEquals(1, sends)
        assertEquals(1, looks)
    }

    @Test fun aLookAfterResumeThatPassesLetsTheTapBeResent() = runTest {
        var sends = 0
        var looks = 0
        sendResendingAfterPause(
            what = "Tap",
            isPaused = { true },
            awaitResume = {},
            isStopped = { false },
            stop = {},
            afterResume = { looks++ }
        ) {
            sends++
            if (sends == 1) throw GestureCancelledException("cancelled")
        }
        assertEquals(2, sends)
        assertEquals(1, looks)
    }

    @Test fun aStopWhilePausedIsNotResent() = runTest {
        var sends = 0
        val thrown = runCatching {
            sendResendingAfterPause("Tap", { true }, {}, { true }, {}) {
                sends++
                throw GestureCancelledException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is StopException)
        assertEquals(1, sends)
    }

    @Test fun resendsAreBounded() = runTest {
        var sends = 0
        val thrown = runCatching {
            // A look that passes every time, so only the bound ends it.
            sendResendingAfterPause("Tap", { true }, {}, { false }, {}, maxResends = 2, afterResume = {}) {
                sends++
                throw GestureCancelledException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is StopException)
        assertEquals(3, sends)
    }
}
