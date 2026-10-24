package local.clashfarmer.service

import local.clashfarmer.core.Length
import local.clashfarmer.core.RunPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

class PendingStartTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val start = PendingStart(RunPlan(listOf("a"), Length.Attacks(10), walls = false), now + hour, now, "Sweat")

    @Test
    fun aPastTimeIsRefused() {
        assertEquals("That time has already passed", PendingStart.problem(now - 1, now))
        assertEquals("That time has already passed", PendingStart.problem(now, now))
    }

    @Test
    fun moreThanTwelveHoursAheadIsRefused() {
        assertEquals("A start can be at most 12 hours ahead", PendingStart.problem(now + 12 * hour + 1, now))
        assertNull(PendingStart.problem(now + 12 * hour, now))
        assertNull(PendingStart.problem(now + 60_000, now))
    }

    @Test
    fun aStartWokenLateIsRefusedNotRun() {
        assertNull(PendingStart.lateProblem(now, now))
        assertNull(PendingStart.lateProblem(now, now + PendingStart.LATE_MS))
        assertEquals("The phone was asleep at the start time", PendingStart.lateProblem(now, now + PendingStart.LATE_MS + 1))
        assertEquals("The phone was asleep at the start time", PendingStart.lateProblem(now, now + 6 * hour))
    }

    @Test
    fun aClockTimeIsTheNextOneAhead() {
        assertEquals(now + 90 * 60_000L, PendingStart.nextAt(13, 30, now, utc))
        assertEquals(now + 23 * hour, PendingStart.nextAt(11, 0, now, utc))
        assertEquals(now + 24 * hour, PendingStart.nextAt(12, 0, now, utc))
    }

    @Test
    fun aStartRoundTripsThroughJson() {
        assertEquals(start, PendingStart.fromJson(JSONObject(start.toJson().toString())))
        val both = start.copy(plan = RunPlan(listOf("a", "b"), Length.Attacks(20), secondAttacks = 30, walls = true), label = "Sweat then Blood")
        assertEquals(both, PendingStart.fromJson(both.toJson()))
    }

    @Test
    fun thePlanAsSetIsKeptBesideTheResolvedOne() {
        val asSet = RunPlan(listOf(RunPlan.SELECTED, RunPlan.OTHER), Length.Attacks(20), walls = false)
        val resolved = asSet.copy(accountIds = listOf("a", "b"))
        val pending = PendingStart(resolved, now + hour, now, "Sweat then Blood", asSet = asSet)
        val read = PendingStart.fromJson(JSONObject(pending.toJson().toString()))
        assertEquals(pending, read)
        assertEquals(listOf(RunPlan.SELECTED, RunPlan.OTHER), read?.asSet?.accountIds)
        // A file without it still reads, saving the plan it runs.
        val old = pending.toJson().apply { remove("asSet") }
        assertEquals(resolved, PendingStart.fromJson(old)?.asSet)
    }

    @Test
    fun aBrokenStartReadsAsNone() {
        assertNull(PendingStart.fromJson(JSONObject().put("atMs", now)))
        assertNull(PendingStart.fromJson(start.toJson().put("plan", JSONObject())))
    }

    @Test
    fun theStoreKeepsOneStartUntilCleared() {
        val store = PendingStartStore(temp.root)
        assertNull(store.read())
        assertTrue(store.write(start))
        assertEquals(start, store.read())
        val later = start.copy(atMs = now + 2 * hour)
        assertTrue(store.write(later))
        assertEquals(later, PendingStartStore(temp.root).read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun aDamagedFileReadsAsNone() {
        File(temp.root, "pending-start.json").writeText("{not json")
        assertNull(PendingStartStore(temp.root).read())
    }
}
