package local.clashfarmer.service

import local.clashfarmer.core.DeviceGuard
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunNoticeTest {

    @Test
    fun finishedSummarisesAttacksAndWalls() {
        val notice = RunNotice.finished("Blood", 7, 3, 1_200_000, 0)
        assertEquals(RunNotice.Kind.FINISHED, notice.kind)
        assertEquals("Blood: Run finished", notice.title)
        assertEquals("Complete, 7 attacks, 3 walls, 1.2M gold spent", notice.text)
    }

    /** The 2-hour Blood run of 14 October: a takeover wait split it into two sessions. */
    @Test
    fun finishedFormatsTheSpendLikeActivity() {
        val notice = RunNotice.finished("Blood", 25, 6, 24_000_000, 24_000_000)
        assertEquals("Complete, 25 attacks, 6 walls, 24.0M gold spent, 24.0M elixir spent", notice.text)
        assertEquals("Complete, 1 wall, 950 gold spent, 412k elixir spent",
            RunNotice.finished(null, null, 1, 950, 412_000).text)
    }

    @Test
    fun finishedWithNothingCountedSaysComplete() {
        val notice = RunNotice.finished(null, null, 0, 0, 0)
        assertEquals("Run finished", notice.title)
        assertEquals("Complete", notice.text)
    }

    @Test
    fun finishedWithADetailSaysItInsteadOfComplete() {
        val notice = RunNotice.finished("Sweat", 5, 0, 0, 0,
            detail = "Storage full; walls failed: wall list could not be read")
        assertEquals("Sweat: Run finished", notice.title)
        assertEquals("Storage full; walls failed: wall list could not be read, 5 attacks", notice.text)
    }

    @Test
    fun personStopsAndTimeLimitsAreOrdinary() {
        listOf("Stopped from floating panel", "Stopped from remote", "Session timeout elapsed (120m)",
            "Global session deadline elapsed (120m)").forEach {
            assertEquals(it, RunNotice.Kind.STOPPED, RunNotice.stopped("Blood", it).kind)
        }
    }

    @Test
    fun knownAttentionReasonsGetTheirOwnTitle() {
        assertEquals("Village opened on another device", RunNotice.stopped(null, Policy.VILLAGE_TAKEN).title)
        assertEquals("Login required",
            RunNotice.stopped(null, "Unexpected Supercell ID login required; accounts must be saved without login").title)
        assertEquals("Storage full",
            RunNotice.stopped(null, "Gold and elixir are full and wall spending is off; attacking would waste the loot").title)
        assertEquals("Game update required", RunNotice.stopped(null, "Clash needs an update").title)
    }

    @Test
    fun aDeviceGuardFinishSaysWhyNotRunFinished() {
        val low = RunNotice.deviceGuard("Blood", DeviceGuard.stopReason(DeviceHealth(12, false, 30.0))!!)
        assertEquals(RunNotice.Kind.NEEDS_YOU, low.kind)
        assertEquals("Blood: Battery low", low.title)
        assertEquals("Battery low: 12% and not charging (limit 15%)", low.text)

        val hot = RunNotice.deviceGuard(null, DeviceGuard.stopReason(DeviceHealth(80, true, 46.2))!!)
        assertEquals("Phone too hot", hot.title)
        assertEquals("Phone too hot: 46.2 C (limit 45.0 C)", hot.text)

        // The guard's reason was lost: still not "Run finished".
        assertEquals("Battery or heat guard", RunNotice.deviceGuard(null, null).title)
    }

    @Test
    fun unknownReasonNeedsAPerson() {
        val notice = RunNotice.stopped("Blood", "Run error: something odd")
        assertEquals(RunNotice.Kind.NEEDS_YOU, notice.kind)
        assertEquals("Blood: Run needs you", notice.title)
        assertEquals("Run error: something odd", notice.text)
    }

    @Test
    fun theShadesHoldCapIsAnOrdinaryStop() {
        val reason = "The notification shade stayed open for 10 minutes"
        val notice = RunNotice.stopped("Blood", reason)
        assertEquals(RunNotice.Kind.STOPPED, notice.kind)
        assertEquals("Blood: Run stopped", notice.title)
        assertEquals(reason, notice.text)
        assertEquals(RunNotice.Kind.STOPPED, RunNotice.stopped(null, "Session is stopped: $reason").kind)
    }

    /** Blood at 16:32 UTC on 14 October: the village came back and the run went on under the old notice. */
    @Test
    fun aRetakenVillageWithdrawsOnlyTheTakeoverWait() {
        val wait = RunNotice.takeoverWait("Blood", 10)
        assertTrue(RunNotifier.withdraws(wait, RunNotice.takeoverWait("Blood", 10)))

        // A takeover that ends the run leaves its notice: the person has to act on it.
        val ended = RunNotice.stopped("Blood", Policy.VILLAGE_TAKEN)
        assertEquals(RunNotice.Kind.NEEDS_YOU, ended.kind)
        assertEquals("Blood: Village opened on another device", ended.title)
        assertFalse(RunNotifier.withdraws(ended, wait))

        // Nor is a notice posted during the wait taken down when the wait ends.
        assertFalse(RunNotifier.withdraws(RunNotice.stopped("Blood", "Scheduled start did not run"), wait))
        assertFalse(RunNotifier.withdraws(null, wait))
    }
}
