package local.clashfarmer.core

import local.clashfarmer.insight.EventText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Why a battle the run ended in the middle of was cut short, as the Raids sheet says it. */
class InterruptReasonTest {

    /** Blood at 16:21 UTC on 14 October: a pause saw the village taken and threw past the guard. */
    @Test
    fun aTakeoverThrownPastTheGuardIsTheReason() {
        val reason = interruptReason(null, StopException(Policy.VILLAGE_TAKEN))
        assertEquals(Policy.VILLAGE_TAKEN, reason)
        assertEquals("the village was opened on another device", EventText.stopLine(reason))
    }

    @Test
    fun theGuardsOwnReasonComesFirst() {
        assertEquals("Stopped from floating panel",
            interruptReason("Stopped from floating panel", StopException("Session is stopped: Stopped from floating panel")))
    }

    @Test
    fun aRunThatEndedWithoutAStopSaysSo() {
        assertEquals("run_ended", interruptReason(null, null))
    }
}
