package local.clashfarmer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureSnapshotTest {
    // 2026-10-08T12:34:56Z
    private val now = 1_791_462_896_000L

    @Test fun namesTheFileByEventAndUtcTime() {
        assertEquals("failure-transition_timeout-20261008-123456.png", FailureSnapshot.fileName("transition_timeout", now))
    }

    @Test fun keepsOnlySafeCharactersInTheEvent() {
        assertEquals("failure-run_stopped_x-20261008-123456.png", FailureSnapshot.fileName("run stopped/x", now))
    }

    @Test fun userStopsAreNotFailures() {
        assertTrue(FailureSnapshot.isUserStop("Stopped from floating panel"))
        assertTrue(FailureSnapshot.isUserStop("Stopped from dashboard"))
        assertTrue(FailureSnapshot.isUserStop("Stopped from remote"))
        assertTrue(FailureSnapshot.isUserStop("Screen turned off (latched stop)"))
        assertFalse(FailureSnapshot.isUserStop("Screen transition timed out waiting for [HOME]"))
        assertFalse(FailureSnapshot.isUserStop("Run error: boom"))
        assertFalse(FailureSnapshot.isUserStop(null))
    }

    @Test fun takesOnlyAFreshFrameNotAlreadySaved() {
        assertTrue(FailureSnapshot.shouldSave(frameAtMs = now - 1000, nowMs = now, lastSavedFrameAtMs = null))
        // The same frame a timeout already saved is not written again by the restart or the stop.
        assertFalse(FailureSnapshot.shouldSave(frameAtMs = now - 1000, nowMs = now, lastSavedFrameAtMs = now - 1000))
        // A frame from long before the failure says nothing about it.
        assertFalse(FailureSnapshot.shouldSave(frameAtMs = now - FailureSnapshot.MAX_FRAME_AGE_MS - 1, nowMs = now, lastSavedFrameAtMs = null))
        assertFalse(FailureSnapshot.shouldSave(frameAtMs = null, nowMs = now, lastSavedFrameAtMs = null))
    }

    @Test fun neverTakesTheFrameOfThePreviousRun() {
        // D3 F4: run A's last frame, 30 s old, is still in LatestFrame when run B fails at once.
        val runB = now - 10_000
        assertFalse(FailureSnapshot.shouldSave(frameAtMs = now - 30_000, nowMs = now, lastSavedFrameAtMs = null, runStartedAtMs = runB))
        assertTrue(FailureSnapshot.shouldSave(frameAtMs = now - 5_000, nowMs = now, lastSavedFrameAtMs = null, runStartedAtMs = runB))
        assertTrue(FailureSnapshot.shouldSave(frameAtMs = runB, nowMs = now, lastSavedFrameAtMs = null, runStartedAtMs = runB))
    }
}
