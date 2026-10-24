package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StepTimerTest {

    @Test fun bucketsPlusOtherReconcileWithTotal() {
        var now = 0L
        val timer = StepTimer { now }
        now += 5
        timer.step("zoomOut") { now += 100 }
        timer.step("capture") { now += 40 }
        timer.step("capture") { now += 40 }
        now += 15

        val fields = deployTimingSummary(timer, emptyList()).toMap()
        assertEquals(200L, fields["totalMs"])
        assertEquals(100L, fields["zoomOutMs"])
        assertEquals(80L, fields["captureMs"])
        assertEquals(20L, fields["otherMs"])
        assertEquals(2, fields["captures"])
        val buckets = fields.entries.filter { it.key.endsWith("Ms") && it.key != "totalMs" }
            .sumOf { it.value as Long }
        assertEquals(fields["totalMs"], buckets)
    }

    @Test fun aNestedStepIsChargedOnceToTheInnermost() {
        var now = 0L
        val timer = StepTimer { now }
        timer.step("reward") {
            now += 30
            timer.step("capture") { now += 20 }
            now += 50
        }

        val buckets = timer.bucketMs()
        assertEquals(20L, buckets["capture"])
        assertEquals(80L, buckets["reward"])
        assertEquals(100L, timer.elapsedMs())
    }

    @Test fun aStepIsRecordedWhenItsBlockThrows() {
        var now = 0L
        val timer = StepTimer { now }
        assertThrows(IllegalStateException::class.java) {
            timer.step("boom") {
                now += 12
                throw IllegalStateException("x")
            }
        }
        assertEquals(12L, timer.bucketMs()["boom"])
        assertEquals(1, timer.steps().size)
    }

    @Test fun sameNameStepsAccumulateOneBucket() {
        var now = 0L
        val timer = StepTimer { now }
        timer.step("capture") { now += 10 }
        timer.step("capture") {
            now += 5
            timer.step("capture") { now += 7 }
        }
        // 10 + (5 exclusive) + 7 = 22, no double counting between the nested pair.
        assertEquals(22L, timer.bucketMs()["capture"])
        assertEquals(3, timer.stepCount("capture"))
    }

    @Test fun countersAndPhaseDetailsReachTheSummary() {
        var now = 0L
        val timer = StepTimer { now }
        timer.mark("verifyAttempts")
        timer.mark("verifyAttempts")
        timer.mark("retryGroups", 2)
        timer.step("gestures") { now += 60 }

        val fields = deployTimingSummary(
            timer, listOf("phase=rages:verify=1:ms=60", "phase=jumps:verify=1:ms=40")
        ).toMap()
        assertEquals(2, fields["verifyAttempts"])
        assertEquals(2, fields["retryGroups"])
        assertEquals("phase=rages:verify=1:ms=60|phase=jumps:verify=1:ms=40", fields["phases"])
        assertEquals(0L, fields["otherMs"])
    }

    @Test fun aTimerThatNeverSawAStepReportsAllOfItAsOther() {
        var now = 0L
        val timer = StepTimer { now }
        now += 33
        val fields = deployTimingSummary(timer, emptyList()).toMap()
        assertEquals(33L, fields["totalMs"])
        assertEquals(33L, fields["otherMs"])
        assertTrue(!fields.containsKey("zoomOutMs"))
    }
}
