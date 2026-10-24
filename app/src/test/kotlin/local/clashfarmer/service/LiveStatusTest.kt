package local.clashfarmer.service

import local.clashfarmer.core.Length
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.log.FailureSnapshot
import local.clashfarmer.log.RunContextSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LiveStatusTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    /** The session started 12:34 ago; the service began the run two seconds before that. */
    private val started = now - 754_000
    private val sweat = RunContextSnapshot(activeAccountName = "Sweat")

    private fun attacks(phase: String = "Attacking") =
        SessionSnapshot(startedAt = started, phase = phase, completed = 12, limit = 40)

    private fun of(
        snapshot: SessionSnapshot,
        context: RunContextSnapshot = sweat,
        busy: Boolean = true,
        paused: Boolean = false,
        runStartedAt: Long? = started - 2_000,
        pending: PendingStart? = null,
        held: Boolean = false
    ) = LiveStatus.of(snapshot, context, busy, paused, runStartedAt, pending, now, utc, held)

    @Test
    fun anAttackRunShowsItsCountPercentAndClock() {
        val s = of(attacks())
        assertEquals(LiveStatus.State.RUNNING, s.state)
        assertEquals("12/40", s.short)
        assertEquals("Sweat · 12 of 40", s.title)
        assertEquals("Attacking", s.phase)
        assertEquals(0.3f, s.fraction!!, 0.001f)
        assertFalse(s.waiting)
        assertEquals("30%  12:34", s.meta)
        assertTrue(s.busy)
    }

    @Test
    fun aPausedRunSaysSoAndKeepsItsProgress() {
        val s = of(attacks(), paused = true)
        assertEquals(LiveStatus.State.PAUSED, s.state)
        assertEquals("Paused", s.phase)
        assertTrue(s.waiting)
        assertEquals(0.3f, s.fraction!!, 0.001f)
        assertEquals("12:34", s.meta)
        assertTrue(s.busy)
    }

    @Test
    fun theShadesHoldSaysWhyItIsPaused() {
        val s = of(attacks(), paused = true, held = true)
        assertEquals(LiveStatus.State.HELD, s.state)
        assertEquals("Paused: notification shade open", s.phase)
        assertTrue(s.waiting)
        assertEquals(0.3f, s.fraction!!, 0.001f)
        assertTrue(s.busy)
        // A hold the guard has already let go of is just a running run.
        assertEquals(LiveStatus.State.RUNNING, of(attacks(), held = true).state)
    }

    @Test
    fun waitsAreSaidInWordsInsteadOfAStillBar() {
        mapOf(
            "Village opened elsewhere; waiting 10 minutes" to "Waiting 10 min, village opened elsewhere",
            "Reopening village" to "Reopening Clash",
            "Waiting for a readable screen" to "Waiting for a readable screen"
        ).forEach { (phase, words) ->
            val s = of(attacks(phase))
            assertEquals(words, s.phase)
            assertTrue(phase, s.waiting)
            assertEquals("12:34", s.meta)
            assertEquals(LiveStatus.State.RUNNING, s.state)
        }
    }

    @Test
    fun thePreviousRunsNumbersDoNotShowWhileANewRunStarts() {
        val old = SessionSnapshot(startedAt = now - 3_600_000, endedAt = now - 3_000_000, phase = "Finished", completed = 40, limit = 40)
        val s = of(old, runStartedAt = now - 5_000)
        assertEquals("Starting", s.phase)
        assertEquals("Sweat", s.title)
        assertEquals("0:05", s.short)
        assertNull(s.fraction)
        assertTrue(s.waiting)
    }

    @Test
    fun aTimedRunCountsByTheClock() {
        val snap = SessionSnapshot(startedAt = started, phase = "Attacking", completed = 7, limit = 100, maxMinutes = 120)
        val s = of(snap)
        assertEquals("12:34", s.short)
        assertEquals("Sweat · 7 attacks", s.title)
        assertEquals("10%  12:34", s.meta)
    }

    @Test
    fun aWallRunCountsWalls() {
        val snap = SessionSnapshot(startedAt = started, phase = "Buying walls", maxMinutes = 30, wallOnly = true, wallsUpgraded = 3)
        val s = of(snap)
        assertEquals("Sweat · 3 walls", s.title)
        assertEquals("12:34", s.short)
        assertEquals("41%  12:34", s.meta)
    }

    @Test
    fun aCombinedRunNamesTheAccountItIsOnAndTheNext() {
        val first = RunContextSnapshot(currentAccountName = "Sweat", combinedRunId = "c1", combinedCompleted = 12,
            combinedTotal = 80, nextAccountName = "Blood")
        val a = of(attacks(), first)
        assertEquals("Sweat 1/2 · 12/80", a.title)
        assertEquals("12/80", a.short)
        assertEquals("Next: Blood", a.meta)
        assertEquals(0.15f, a.fraction!!, 0.001f)

        val second = RunContextSnapshot(currentAccountName = "Blood", combinedRunId = "c1", combinedCompleted = 52, combinedTotal = 80)
        val b = of(attacks(), second)
        assertEquals("Blood 2/2 · 52/80", b.title)
        assertEquals("Next: none", b.meta)
    }

    @Test
    fun aStartSetForLaterShowsItsTime() {
        val plan = RunPlan(listOf("a"), Length.Attacks(10), walls = false)
        val pending = PendingStart(plan, now + 90 * 60_000L, now, "Sweat")
        val s = of(SessionSnapshot(), busy = false, runStartedAt = null, pending = pending)
        assertEquals(LiveStatus.State.PENDING, s.state)
        assertEquals("At 13:30", s.short)
        assertEquals("Sweat · starts 13:30", s.title)
        assertEquals("Waiting for the start time", s.phase)
        assertEquals("in 1 h 30 min", s.meta)
        assertFalse(s.busy)
    }

    @Test
    fun nothingGoingReadsReady() {
        val s = of(SessionSnapshot(), busy = false, runStartedAt = null)
        assertSame(LiveStatus.IDLE, s)
        assertEquals("Ready", s.short)
        assertFalse(s.busy)
    }

    @Test
    fun theLastLineLeadsWithGoldThenTheLargestResource() {
        assertEquals("+412k gold last raid · 3 walls", LiveStatus.lastLine("gold=412000,elixir=900000", 3, wallsOn = true))
        assertEquals("+5.0k elixir last raid", LiveStatus.lastLine("elixir=5000,dark_elixir=100", 0, wallsOn = false))
        assertEquals("No raid read yet", LiveStatus.lastLine(null, 0, wallsOn = false))
        assertEquals("No raid read yet · 0 walls", LiveStatus.lastLine("", 0, wallsOn = true))
    }

    @Test
    fun aStopFromTheNotificationIsAUserStop() {
        assertTrue(FailureSnapshot.isUserStop("Stopped from notification"))
        assertTrue(FailureSnapshot.isUserStop("Session is stopped: Stopped from notification"))
    }
}
