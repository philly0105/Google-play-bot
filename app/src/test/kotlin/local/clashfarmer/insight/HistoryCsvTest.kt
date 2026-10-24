package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.LogFixtures.SWEAT
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class HistoryCsvTest {
    private val utc = ZoneOffset.UTC
    private val t0 = Format.parseTime("2026-10-08T06:51:04Z")!!
    private val accounts = listOf(AccountRecord(SWEAT, "Sweat, main", "Philly0105", "#8YQCYG2RU"))

    private val read = BattleRecord(
        id = "b2", sessionId = "s1", accountId = SWEAT, startedAtMs = t0 + 300_000, endedAtMs = t0 + 480_000,
        earnedLoot = mapOf(Resource.GOLD to 412_345L, Resource.ELIXIR to 398_000L, Resource.DARK_ELIXIR to 2_100L),
        stars = 2, damagePercent = 71, skippedOpponents = 3, searchGold = 4_500, outcome = BattleRecord.COMPLETED
    )
    private val unread = BattleRecord(
        id = "b1", sessionId = "s1", accountId = "gone", startedAtMs = t0, endedAtMs = null,
        skippedOpponents = 0, searchGold = 0, outcome = BattleRecord.IN_PROGRESS
    )

    @Test
    fun oneRowPerBattleOldestFirstWithEmptyCellsForUnread() {
        val lines = HistoryCsv.of(listOf(read, unread), accounts, utc).split("\r\n")
        assertEquals(
            "battle id,session id,account,started,ended,outcome,gold,elixir,dark,stars,damage,skips,search gold",
            lines[0]
        )
        assertEquals("b1,s1,Removed account,2026-10-08 06:51:04,,in progress,,,,,,0,0", lines[1])
        assertEquals("b2,s1,\"Sweat, main\",2026-10-08 06:56:04,2026-10-08 06:59:04,completed,412345,398000,2100,2,71,3,4500", lines[2])
        assertEquals("", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun sinceLeavesOutOlderBattles() {
        val lines = HistoryCsv.of(listOf(read, unread), accounts, utc, sinceMs = t0 + 1).split("\r\n")
        assertEquals(3, lines.size)
        assertEquals("b2", lines[1].substringBefore(','))
    }

    @Test
    fun quotingFollowsRfc4180() {
        assertEquals("plain", HistoryCsv.cell("plain"))
        assertEquals("\"a,b\"", HistoryCsv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", HistoryCsv.cell("say \"hi\""))
        assertEquals("\"two\nlines\"", HistoryCsv.cell("two\nlines"))
    }

    @Test
    fun fileNameUsesTheLocalDay() {
        assertEquals("clashfarmer-history-20261008.csv", HistoryCsv.fileName(t0, utc))
        assertEquals("clashfarmer-history-20261007.csv", HistoryCsv.fileName(t0, ZoneOffset.ofHours(-8)))
    }
}
