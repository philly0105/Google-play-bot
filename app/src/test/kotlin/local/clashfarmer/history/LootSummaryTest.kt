package local.clashfarmer.history

import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LootSummaryTest {
    private val min = 60_000L

    private fun battle(
        session: String, start: Long, end: Long?, gold: Long? = null, elixir: Long? = null,
        outcome: String = BattleRecord.COMPLETED
    ) = BattleRecord(
        id = "$session-$start", sessionId = session, accountId = "a", startedAtMs = start, endedAtMs = end,
        earnedLoot = buildMap {
            gold?.let { put(Resource.GOLD, it) }
            elixir?.let { put(Resource.ELIXIR, it) }
        },
        outcome = outcome
    )

    @Test fun totalsAndRateCoverOnlyTheFarmingTime() {
        val records = listOf(
            battle("s1", 0, 3 * min, gold = 100_000, elixir = 50_000),
            battle("s1", 5 * min, 30 * min, gold = 200_000, elixir = 150_000),
            // An hour's gap, then a second session of half an hour.
            battle("s2", 90 * min, 120 * min, gold = 300_000),
        )
        val s = LootSummary.of(records, sinceMs = 0)
        assertEquals(3, s.battles)
        assertEquals(600_000L, s.loot[Resource.GOLD])
        assertEquals(200_000L, s.loot[Resource.ELIXIR])
        assertEquals(0L, s.loot[Resource.DARK_ELIXIR])
        assertEquals(60 * min, s.farmingMs)
        assertEquals(600_000L, s.perHour(Resource.GOLD))
    }

    @Test fun olderUnfinishedAndUnreadBattlesAreHandled() {
        val records = listOf(
            battle("old", 0, 3 * min, gold = 999_999),
            battle("s", 100 * min, 103 * min, gold = 10_000),
            battle("s", 104 * min, 107 * min),
            battle("s", 108 * min, null, gold = 5, outcome = BattleRecord.INTERRUPTED),
        )
        val s = LootSummary.of(records, sinceMs = 50 * min)
        assertEquals(2, s.battles)
        assertEquals(1, s.unread)
        assertEquals(10_000L, s.loot[Resource.GOLD])
        assertEquals(7 * min, s.farmingMs)
    }

    @Test fun noRateWithoutFarmingTime() {
        assertNull(LootSummary.of(emptyList(), 0).perHour(Resource.GOLD))
    }
}
