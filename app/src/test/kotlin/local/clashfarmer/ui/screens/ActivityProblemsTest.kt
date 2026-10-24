package local.clashfarmer.ui.screens

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.insight.ActivityProblems
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.insight.LogFixtures.real
import local.clashfarmer.insight.LogFixtures.shaped
import local.clashfarmer.insight.Problem
import local.clashfarmer.insight.Problems
import local.clashfarmer.insight.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ActivityProblemsTest {
    private val blood = AccountRecord(BLOOD, "Blood", "Philly0105", "#Y2G29YJQ8")
    private val sweat = AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU")
    private val accounts = listOf(sweat, blood)

    @Test
    fun anUnreadableMarkerIsARedProblemOnlyCheckVillageClears() {
        val collected = Problems.collect(listOf(shaped("device_guard_stop"), real("wall_purchase_outcome_unknown")), accounts, emptyList(), zone = ZoneOffset.UTC)
        val problems = ActivityProblems.withUnreadable(collected, listOf(blood))
        val p = problems.first()
        assertEquals("marker:$BLOOD", p.key)
        assertEquals(Tone.BAD, p.tone)
        assertEquals("Blood: wall purchase not confirmed", p.title)
        assertEquals(Problem.Action.CHECK_VILLAGE, p.action)
        assertEquals(BLOOD, p.accountId)
        assertFalse(p.dismissible)
        // The log's own "not confirmed" entry for that account gives way to it; other problems stay.
        assertEquals(listOf("marker:$BLOOD"), problems.filter { it.accountId == BLOOD }.map { it.key })
        assertTrue(problems.any { it.key.startsWith("guard:") })
        assertTrue(Problems.dismissAll(emptyMap(), problems.take(1), 1L).isEmpty())
        assertFalse(Problems.isDismissed(p, mapOf(p.key to Long.MAX_VALUE)))
    }

    @Test
    fun withNoUnreadableMarkerTheListIsUnchanged() {
        val collected = Problems.collect(listOf(real("wall_purchase_outcome_unknown")), accounts, emptyList(), zone = ZoneOffset.UTC)
        assertEquals(collected, ActivityProblems.withUnreadable(collected, emptyList()))
    }
}
