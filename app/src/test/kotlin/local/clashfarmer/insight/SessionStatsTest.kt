package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class SessionStatsTest {
    private val utc = ZoneOffset.UTC
    private val accounts = listOf(
        AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU"),
        AccountRecord(BLOOD, "Blood", "Philly0105", "#Y2G29YJQ8")
    )
    private val t0 = Format.parseTime("2026-10-08T06:51:00Z")!!
    private val min = 60_000L

    private fun battle(
        id: String, session: String, account: String, startMin: Long, lengthMin: Long = 3,
        gold: Long? = null, elixir: Long? = null, outcome: String = BattleRecord.COMPLETED, stars: Int? = 2
    ) = BattleRecord(
        id = id, sessionId = session, accountId = account,
        startedAtMs = t0 + startMin * min, endedAtMs = t0 + (startMin + lengthMin) * min,
        earnedLoot = buildMap { gold?.let { put(Resource.GOLD, it) }; elixir?.let { put(Resource.ELIXIR, it) } },
        stars = stars, damagePercent = if (stars == null) null else 71, skippedOpponents = 3, searchGold = 4_500,
        outcome = outcome
    )

    /** Sweat: 3 battles over an hour, one unread and one interrupted. Blood: 2 battles over 30 min. */
    private val records = listOf(
        battle("a", "s1", SWEAT, 0, gold = 400_000, elixir = 400_000),
        battle("b", "s1", SWEAT, 30, gold = null, elixir = null, stars = null),
        battle("c", "s1", SWEAT, 57, gold = 600_000, elixir = 600_000),
        battle("d", "s1", SWEAT, 61, outcome = BattleRecord.INTERRUPTED, gold = null),
        battle("e", "s2", BLOOD, 120, gold = 250_000, elixir = 250_000),
        battle("f", "s2", BLOOD, 147, gold = 250_000, elixir = 250_000)
    )

    @Test
    fun totalsFollowLootSummary() {
        val all = SessionStats.summary(records, Long.MIN_VALUE)
        assertEquals(5, all.battles)
        assertEquals(1, all.unread)
        assertEquals(3_000_000L, all.lootTotal)
        // Sweat 60 min + Blood 30 min of farming: 3.0M over 1.5 h.
        assertEquals(90 * min, all.farmingMs)
        assertEquals(2_000_000L, all.lootPerHour)

        val sweat = SessionStats.summary(records, Long.MIN_VALUE, SWEAT)
        assertEquals(2_000_000L, sweat.lootPerHour)
        assertNull(SessionStats.summary(emptyList(), 0).lootPerHour)
        assertNull(SessionStats.summary(emptyList(), 0).lootTotal)
    }

    @Test
    fun periods() {
        val now = Format.parseTime("2026-10-08T15:30:00Z")!!
        assertEquals(Format.parseTime("2026-10-08T00:00:00Z"), SessionStats.sinceMs(SessionStats.Period.TODAY, now, utc))
        assertEquals(now - 7 * 86_400_000L, SessionStats.sinceMs(SessionStats.Period.DAYS_7, now, utc))
        assertEquals(SessionStats.Period.TODAY, SessionStats.periodForDays(1))
        assertEquals(SessionStats.Period.DAYS_30, SessionStats.periodForDays(30))
        assertEquals(SessionStats.Period.DAYS_7, SessionStats.periodForDays(null))
    }

    @Test
    fun byAccountRanksByRate() {
        val rows = SessionStats.byAccount(records, Long.MIN_VALUE, accounts)
        assertEquals(listOf("Sweat", "Blood"), rows.map { it.name })
        assertEquals(2_000_000L, rows[0].lootPerHour)
        assertEquals(60 * min, rows[0].farmingMs)
        assertEquals(2_000_000L, rows[1].lootPerHour)
        assertEquals(30 * min, rows[1].farmingMs)
    }

    @Test
    fun sessionRowsNewestFirstWithTheirEnding() {
        val rows = SessionStats.sessions(records, accounts, endings = mapOf("s1" to "Stopped"))
        assertEquals(listOf("s2", "s1"), rows.map { it.sessionId })
        val sweat = rows[1]
        assertEquals(3, sweat.battles)
        assertEquals(1, sweat.interrupted)
        assertEquals(1, sweat.unread)
        assertEquals(listOf("Oct 8 06:51", "Sweat", "3 battles", "1h04", "2.0M/h", "Stopped"), SessionStats.sessionCells(sweat, utc))
        assertEquals("Sweat · Oct 8 06:51-07:55 · 3 battles · 2.0M/h", SessionStats.sessionTitle(sweat, utc))
        // Battle time only: 2.0M over 9 min of battles.
        assertEquals(2_000_000L * 60 / 9, sweat.battleTimeRate)
        assertEquals("Battle time only: 13.3M/h (search time left out)", SessionStats.battleTimeLine(sweat))
        assertNull(rows[0].ending)
        assertEquals(1, SessionStats.sessions(records, accounts, limit = 1).size)
        assertEquals(listOf("s1"), SessionStats.sessions(records, accounts, accountId = SWEAT).map { it.sessionId })
    }

    @Test
    fun raidRowsShowUnreadAsDash() {
        val sweat = SessionStats.sessions(records, accounts).first { it.sessionId == "s1" }
        val rows = SessionStats.raidRows(sweat, utc)
        assertEquals(SessionStats.RaidRow("a", "06:51", "400k", "400k", "-", "2", "71%", "3", ""), rows[0])
        assertEquals(SessionStats.RaidRow("b", "07:21", "-", "-", "-", "-", "-", "3", ""), rows[1])
        assertEquals("interrupted", rows[3].note)
    }

    @Test
    fun lastRaidIsTheNewestCompletedBattle() {
        val last = SessionStats.lastRaid(records, SWEAT)!!
        assertEquals("c", last.battle.id)
        assertEquals("+1.2M", last.headline)
        assertEquals("2 stars, 6 min ago", last.detail(t0 + 66 * min))
        assertNull(SessionStats.lastRaid(records, "nobody"))
        assertEquals(2_000_000L, SessionStats.lootRate7d(records, SWEAT, t0 + 2 * 60 * min))
    }

    @Test
    fun chartIsTheLastSessionsOldestFirst() {
        val bars = SessionStats.chart(SessionStats.sessions(records, accounts), utc, n = 14)
        assertEquals(listOf("s1", "s2"), bars.map { it.sessionId })
        assertEquals(BLOOD, bars[1].accountId)
        assertEquals("Oct 8", bars[0].label)
    }
}
