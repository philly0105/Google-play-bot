package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.SessionStats.Period
import local.clashfarmer.ui.screens.StatsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class StatsModelTest {
    private val utc = ZoneOffset.UTC
    private val sweat = "sweat-id"
    private val blood = "blood-id"
    private val accounts = listOf(
        AccountRecord(sweat, "Sweat", "Philly0105", "#8YQCYG2RU"),
        AccountRecord(blood, "Blood", "Philly0105", "#Y2G29YJQ8")
    )
    private val now = Format.parseTime("2026-10-08T12:00:00Z")!!
    private val min = 60_000L
    private val day = 86_400_000L

    private fun battle(
        id: String, session: String, account: String, startMs: Long, lengthMin: Long = 3,
        gold: Long? = null, elixir: Long? = null, outcome: String = BattleRecord.COMPLETED, stars: Int? = 2
    ) = BattleRecord(
        id = id, sessionId = session, accountId = account,
        startedAtMs = startMs, endedAtMs = startMs + lengthMin * min,
        earnedLoot = buildMap<Resource, Long> { gold?.let { put(Resource.GOLD, it) }; elixir?.let { put(Resource.ELIXIR, it) } },
        stars = stars, damagePercent = stars?.let { 50 + it * 10 }, skippedOpponents = 2, searchGold = 3_000,
        outcome = outcome
    )

    private val s12 = now - 3 * day
    private val s13 = now - 2 * day
    private val s14 = now - day
    private val s15 = Format.parseTime("2026-10-08T06:51:00Z")!!

    /**
     * 20 battles. s1-s11: one 3-minute battle each, 100k gold and 100k elixir, 21 to 11 days ago,
     * the accounts taking turns. s12 Sweat: three battles over an hour, the middle one unread.
     * s13 Blood: two battles over 30 min. s14 Blood: two read battles that earned nothing.
     * s15 Sweat, today: one battle and one interrupted.
     */
    private val records = (1..11).map { k ->
        battle("o$k", "s$k", if (k % 2 == 1) sweat else blood, now - (22 - k) * day, gold = 100_000, elixir = 100_000)
    } + listOf(
        battle("a", "s12", sweat, s12, gold = 400_000, elixir = 400_000),
        battle("b", "s12", sweat, s12 + 30 * min, stars = null),
        battle("c", "s12", sweat, s12 + 57 * min, gold = 600_000, elixir = 600_000),
        battle("x", "s13", blood, s13, gold = 250_000, elixir = 250_000),
        battle("y", "s13", blood, s13 + 27 * min, gold = 250_000, elixir = 250_000),
        battle("z1", "s14", blood, s14, gold = 0, elixir = 0, stars = 0),
        battle("z2", "s14", blood, s14 + 12 * min, gold = 0, elixir = 0, stars = 0),
        battle("p", "s15", sweat, s15, gold = 300_000, elixir = 300_000),
        battle("q", "s15", sweat, s15 + 5 * min, lengthMin = 1, outcome = BattleRecord.INTERRUPTED, stars = null)
    )

    private fun model(period: Period, accountId: String? = null, endings: Map<String, String> = emptyMap()) =
        StatsModel.build(records, accounts, endings, period, accountId, now, utc)

    @Test
    fun fixtureIsTwentyBattlesOverTwoAccounts() {
        assertEquals(20, records.size)
        assertEquals(setOf(sweat, blood), records.map { it.accountId }.toSet())
    }

    @Test
    fun sevenDayTilesCountOnlyTheLastWeek() {
        val m = model(Period.DAYS_7)
        // s12-s15: 8 completed battles, 3.6M over 108 min of farming (60 + 30 + 15 + 3).
        assertEquals("8", m.battlesText)
        assertEquals("2.0M/h", m.rateText)
        assertEquals("3.6M", m.totalText)
        assertEquals("1 battle not read", m.unreadChip)
        assertEquals(listOf("s15", "s14", "s13", "s12"), m.sessions.map { it.sessionId })
        assertNull(m.emptyLine)
    }

    @Test
    fun thirtyDaysAndTodayMoveTheWindow() {
        val month = model(Period.DAYS_30)
        // Adds s1-s11: 19 battles, 5.8M over 141 min.
        assertEquals("19", month.battlesText)
        assertEquals("2.5M/h", month.rateText)
        assertEquals("5.8M", month.totalText)
        assertEquals(15, month.sessions.size)

        val today = model(Period.TODAY)
        assertEquals(listOf("s15"), today.sessions.map { it.sessionId })
        assertEquals("1", today.battlesText)
        assertEquals("12.0M/h", today.rateText)
        assertEquals("600k", today.totalText)
        assertNull(today.unreadChip)
    }

    @Test
    fun accountFilterScopesTilesButNotTheComparison() {
        val m = model(Period.DAYS_7, accountId = sweat)
        // s12 + s15: 2.6M over 63 min.
        assertEquals("4", m.battlesText)
        assertEquals("2.5M/h", m.rateText)
        assertEquals("2.6M", m.totalText)
        assertEquals(listOf("s15", "s12"), m.sessions.map { it.sessionId })

        assertEquals(listOf("Sweat", "Blood"), m.byAccount.map { it.name })
        assertEquals(2_476_190L, m.byAccount[0].lootPerHour)
        assertEquals(63 * min, m.byAccount[0].farmingMs)
        // Blood's zero-loot session still counts its 15 minutes: 1.0M over 45 min.
        assertEquals(1_333_332L, m.byAccount[1].lootPerHour)
        assertEquals(45 * min, m.byAccount[1].farmingMs)
    }

    @Test
    fun zeroLootSessionReadsZeroNotUnknown() {
        val zero = model(Period.DAYS_7).sessions.first { it.sessionId == "s14" }
        assertEquals(0L, zero.lootPerHour)
        assertEquals(0, zero.unread)
        assertEquals("0/h", StatsModel.line(zero, utc).rate)
        assertEquals(0L, model(Period.DAYS_7).bars.first { it.sessionId == "s14" }.lootPerHour)
    }

    @Test
    fun chartIsTheLastFourteenSessionsOldestFirst() {
        val bars = model(Period.DAYS_30).bars
        assertEquals(14, bars.size)
        assertEquals("s2", bars.first().sessionId)
        assertEquals("s15", bars.last().sessionId)
        assertEquals(blood, bars.first().accountId)
        assertEquals("max 12.0M/h", model(Period.DAYS_30).maxText)
        assertEquals("Oct 8", bars.last().label)
    }

    @Test
    fun sessionLinesAreShortPlainText() {
        val m = model(Period.DAYS_7, endings = mapOf("s12" to "Time", "s15" to "Stopped"))
        val lines = m.sessions.associate { it.sessionId to StatsModel.line(it, utc) }
        assertEquals(StatsModel.SessionLine("Oct 5 12:00 · Sweat", "3 battles · 1h00 · Time", "2.0M/h"), lines["s12"])
        assertEquals(StatsModel.SessionLine("Oct 8 06:51 · Sweat", "1 battle · 6 min · 1 interrupted · Stopped", "12.0M/h"), lines["s15"])
        assertEquals("2 battles · 15 min", lines.getValue("s14").detail)
        val shown = lines.values.flatMap { listOf(it.title, it.detail, it.rate) } + listOfNotNull(m.unreadChip, m.maxText)
        assertTrue(shown.none { it.contains('_') })
    }

    @Test
    fun emptyHistoryAndEmptyPeriodSayDifferentThings() {
        val none = StatsModel.build(emptyList(), accounts, emptyMap(), Period.DAYS_7, null, now, utc)
        assertEquals("No raids yet", none.emptyLine)
        assertEquals("Battles show up here after the first run.", none.emptyDetail)
        assertEquals("-", none.rateText)
        assertEquals("-", none.totalText)
        assertNull(none.maxText)

        val bloodToday = model(Period.TODAY, accountId = blood)
        assertEquals("No raids in this period", bloodToday.emptyLine)
        assertEquals("Pick a longer period or all accounts.", bloodToday.emptyDetail)
    }

    @Test
    fun emptyPeriodHintOffersOnlyWhatWidensIt() {
        fun detail(period: Period, accountId: String?, atMs: Long) =
            StatsModel.build(records, accounts, emptyMap(), period, accountId, atMs, utc).also {
                assertEquals("No raids in this period", it.emptyLine)
            }.emptyDetail
        assertEquals("Pick a longer period.", detail(Period.DAYS_7, null, now + 20 * day))
        // From Oct 8 06:00 the window keeps Sweat's s15 but none of Blood's sessions.
        assertEquals("Pick all accounts.", detail(Period.DAYS_30, blood, Format.parseTime("2026-11-07T06:00:00Z")!!))
        assertEquals("Older raids are still in the CSV export.", detail(Period.DAYS_30, null, now + 60 * day))
    }

    @Test
    fun chartTellsAReadZeroFromLootNotRead() {
        val zeroDay = StatsModel.build(records, accounts, emptyMap(), Period.TODAY, blood, Format.parseTime("2026-10-07T18:00:00Z")!!, utc)
        assertEquals(listOf("s14"), zeroDay.bars.map { it.sessionId })
        assertNull(zeroDay.maxText)
        assertEquals("Nothing earned in this period", zeroDay.chartEmpty)

        val unread = StatsModel.build(listOf(battle("u", "su", sweat, now - day, stars = null)), accounts, emptyMap(), Period.DAYS_7, null, now, utc)
        assertNull(unread.sessions.single().lootPerHour)
        assertEquals("No loot read in this period", unread.chartEmpty)
    }

    @Test
    fun chartUnderAMinuteOfFarmingSaysSoBesideTheTiles() {
        // One read battle that ended at once: the tiles show its loot, but there is no rate to chart.
        val quick = StatsModel.build(listOf(battle("f", "sf", sweat, now - day, lengthMin = 0, gold = 300_000, elixir = 300_000)),
            accounts, emptyMap(), Period.DAYS_7, null, now, utc)
        assertEquals("600k", quick.totalText)
        assertEquals("-", quick.rateText)
        assertNull(quick.maxText)
        assertEquals("A session needs a minute of farming for a rate", quick.chartEmpty)

        // Two read sessions of 55 s and 50 s: 105 s together make a tile rate, yet no bar has one.
        val start = now - day
        val short = StatsModel.build(listOf(
            battle("s1", "sa", sweat, start, gold = 400_000, elixir = 410_000).copy(endedAtMs = start + 55_000),
            battle("s2", "sb", sweat, start + 60 * min, gold = 300_000, elixir = 300_000).copy(endedAtMs = start + 60 * min + 50_000)
        ), accounts, emptyMap(), Period.DAYS_7, null, now, utc)
        assertEquals("48.3M/h", short.rateText)
        assertNull(short.maxText)
        assertEquals("A session needs a minute of farming for a rate", short.chartEmpty)

        // A read zero beside an unread battle: nothing earned in what was read, not in the period.
        val mixed = StatsModel.build(listOf(
            battle("z", "sz", blood, now - day, gold = 0, elixir = 0, stars = 0),
            battle("u", "sz", blood, now - day + 10 * min, stars = null)
        ), accounts, emptyMap(), Period.DAYS_7, null, now, utc)
        assertEquals("Nothing earned in the battles read", mixed.chartEmpty)
    }

    @Test
    fun coverageLineOnlyWhenTheCapCutsThePeriod() {
        assertNull(model(Period.DAYS_30).coverage)
        assertNull(model(Period.DAYS_30).statusLine)

        // A full store whose oldest battle, Oct 4 06:12, is inside 7 and 30 days but before today.
        val oldest = Format.parseTime("2026-10-04T06:12:00Z")!!
        val full = (0 until BattleHistoryStore.MAX_BATTLES).map { k ->
            battle("f$k", "sf${k / 40}", sweat, oldest + k * 5 * min, gold = 100_000, elixir = 100_000)
        }
        val line = "History keeps the newest 1,000 battles: these numbers start Oct 4, 06:12"
        assertEquals(line, StatsModel.build(full, accounts, emptyMap(), Period.DAYS_7, null, now, utc).coverage)
        assertEquals(line, StatsModel.build(full, accounts, emptyMap(), Period.DAYS_30, sweat, now, utc).statusLine)
        assertNull(StatsModel.build(full, accounts, emptyMap(), Period.TODAY, null, now, utc).coverage)
        // One short of the cap nothing was pruned, so the period is whole.
        assertNull(StatsModel.build(full.drop(1), accounts, emptyMap(), Period.DAYS_7, null, now, utc).coverage)
        assertNull(StatsModel.coverage(full, oldest, utc))
        assertEquals(line, StatsModel.coverage(full, oldest - 1, utc))
    }

    @Test
    fun unreadableFilesJoinTheStatusLineAndNameAnEmptyHistory() {
        val partial = StatsModel.build(records, accounts, emptyMap(), Period.DAYS_7, null, now, utc, corrupt = 2)
        assertEquals("Left out 2 history files that could not be read.", partial.statusLine)
        assertNull(partial.emptyLine)

        val none = StatsModel.build(emptyList(), accounts, emptyMap(), Period.DAYS_7, null, now, utc, corrupt = 3)
        assertEquals("No readable raids", none.emptyLine)
        assertEquals("Battles from the next run show up here.", none.emptyDetail)
    }
}
