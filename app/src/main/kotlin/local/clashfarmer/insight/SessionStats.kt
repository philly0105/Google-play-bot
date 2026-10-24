package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.history.LootSummary
import java.time.Instant
import java.time.ZoneId

/**
 * The numbers on Stats, Home's loot tile and the remote page, all over [BattleRecord]s.
 *
 * Rates follow [LootSummary]: only completed battles count, loot only where the result screen was
 * read, and farming time runs from the first battle to the last of each session, so search and
 * army time are in it. A rate is null, shown as "-", until there is a minute to divide by.
 */
object SessionStats {

    enum class Period(val label: String) { TODAY("Today"), DAYS_7("7 days"), DAYS_30("30 days") }

    /** Local midnight for [Period.TODAY]; otherwise [nowMs] less whole days. */
    fun sinceMs(period: Period, nowMs: Long, zone: ZoneId): Long = when (period) {
        Period.TODAY -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        Period.DAYS_7 -> nowMs - 7 * DAY_MS
        Period.DAYS_30 -> nowMs - 30 * DAY_MS
    }

    /** Period for the remote API's `days` parameter: 1 is today, 7 and 30 as named, else 7. */
    fun periodForDays(days: Int?): Period = when (days) {
        1 -> Period.TODAY
        30 -> Period.DAYS_30
        else -> Period.DAYS_7
    }

    data class Totals(
        val battles: Int,
        /** Completed battles whose result screen was not read: the "N not read" chip. */
        val unread: Int,
        val loot: Map<Resource, Long>,
        val farmingMs: Long,
        /** Gold plus elixir per farming hour; null under a minute of farming. */
        val lootPerHour: Long?
    ) {
        val goldAndElixir: Long get() = (loot[Resource.GOLD] ?: 0L) + (loot[Resource.ELIXIR] ?: 0L)
        /** Null when no battle was read, so a tile shows "-" rather than 0. */
        val lootTotal: Long? get() = if (battles - unread <= 0) null else goldAndElixir
    }

    fun summary(records: List<BattleRecord>, sinceMs: Long, accountId: String? = null): Totals {
        val scoped = if (accountId == null) records else records.filter { it.accountId == accountId }
        return totals(LootSummary.of(scoped, sinceMs))
    }

    /** Home's loot tile: the shown account over the last 7 days. */
    fun lootRate7d(records: List<BattleRecord>, accountId: String, nowMs: Long): Long? =
        summary(records, nowMs - 7 * DAY_MS, accountId).lootPerHour

    data class AccountRate(val accountId: String, val name: String, val lootPerHour: Long?, val farmingMs: Long, val battles: Int)

    /** One row per account that farmed in the window, highest rate first; unknown ids keep a short name. */
    fun byAccount(records: List<BattleRecord>, sinceMs: Long, accounts: List<AccountRecord>): List<AccountRate> {
        val ids = (accounts.map { it.id } + records.map { it.accountId }).distinct()
        return ids.map { id ->
            val t = summary(records, sinceMs, id)
            AccountRate(id, accountName(id, accounts), t.lootPerHour, t.farmingMs, t.battles)
        }.filter { it.battles > 0 }.sortedByDescending { it.lootPerHour ?: -1L }
    }

    data class SessionRow(
        val sessionId: String,
        val accountId: String,
        val accountName: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        /** Completed battles; interrupted ones are in [interrupted] and the raids table. */
        val battles: Int,
        val interrupted: Int,
        val unread: Int,
        val loot: Map<Resource, Long>,
        val farmingMs: Long,
        val lootPerHour: Long?,
        /** Gold plus elixir per hour of battle time alone, search excluded: the sheet's second line. */
        val battleTimeRate: Long?,
        /** "Stopped", "Time", "Done" and so on when the log said how the session ended. */
        val ending: String?,
        /** Oldest first, as the raids table reads. */
        val raids: List<BattleRecord>
    )

    /**
     * Sessions newest first, [limit] of them. [endings] maps a session id to its end word, from
     * [Timeline.endings]; a session the log no longer covers has none.
     */
    fun sessions(
        records: List<BattleRecord>,
        accounts: List<AccountRecord>,
        endings: Map<String, String> = emptyMap(),
        sinceMs: Long = Long.MIN_VALUE,
        accountId: String? = null,
        limit: Int = Int.MAX_VALUE
    ): List<SessionRow> = records
        .filter { it.startedAtMs >= sinceMs && (accountId == null || it.accountId == accountId) }
        .groupBy { it.sessionId }
        .map { (id, battles) -> row(id, battles.sortedBy { it.startedAtMs }, accounts, endings[id]) }
        .sortedByDescending { it.startedAtMs }
        .take(limit)

    private fun row(id: String, raids: List<BattleRecord>, accounts: List<AccountRecord>, ending: String?): SessionRow {
        val summary = LootSummary.of(raids, Long.MIN_VALUE)
        val completed = raids.filter { it.outcome == BattleRecord.COMPLETED }
        val battleMs = completed.sumOf { b -> b.endedAtMs?.let { (it - b.startedAtMs).coerceAtLeast(0L) } ?: 0L }
        val goldAndElixir = (summary.loot[Resource.GOLD] ?: 0L) + (summary.loot[Resource.ELIXIR] ?: 0L)
        val first = raids.first()
        return SessionRow(
            sessionId = id,
            accountId = first.accountId,
            accountName = accountName(first.accountId, accounts),
            startedAtMs = first.startedAtMs,
            endedAtMs = raids.maxOf { it.endedAtMs ?: it.startedAtMs },
            battles = summary.battles,
            interrupted = raids.count { it.outcome == BattleRecord.INTERRUPTED },
            unread = summary.unread,
            loot = summary.loot,
            farmingMs = summary.farmingMs,
            lootPerHour = totals(summary).lootPerHour,
            battleTimeRate = if (battleMs < 60_000L || summary.battles == summary.unread) null else goldAndElixir * 3_600_000L / battleMs,
            ending = ending,
            raids = raids
        )
    }

    /** "Oct 8 06:51  Sweat  12 battles  1h04  17.8M/h  Stopped" as separate cells. */
    fun sessionCells(row: SessionRow, zone: ZoneId): List<String> = listOf(
        Format.dayTime(row.startedAtMs, zone),
        row.accountName,
        Format.count(row.battles, "battle"),
        Format.span(row.endedAtMs - row.startedAtMs),
        Format.rate(row.lootPerHour),
        row.ending ?: ""
    )

    /** "Sweat · Oct 8 06:51-07:55 · 12 battles · 17.8M/h", the raids sheet's title. */
    fun sessionTitle(row: SessionRow, zone: ZoneId): String =
        "${row.accountName} · ${Format.dayTime(row.startedAtMs, zone)}-${Format.time(row.endedAtMs, zone)} · " +
            "${Format.count(row.battles, "battle")} · ${Format.rate(row.lootPerHour)}"

    /** The sheet's second line: the rate counting battle time only. */
    fun battleTimeLine(row: SessionRow): String =
        if (row.battleTimeRate == null) "Battle time only: not enough read"
        else "Battle time only: ${Format.rate(row.battleTimeRate)} (search time left out)"

    data class RaidRow(
        val battleId: String,
        val time: String,
        val gold: String,
        val elixir: String,
        val dark: String,
        val stars: String,
        val damage: String,
        val skips: String,
        /** "interrupted", "in progress" or empty. */
        val note: String
    )

    val RAID_HEADERS = listOf("Time", "Gold", "Elixir", "Dark", "Stars", "Dmg", "Skips")

    fun raidRow(b: BattleRecord, zone: ZoneId): RaidRow = RaidRow(
        battleId = b.id,
        time = Format.time(b.startedAtMs, zone),
        gold = Format.amount(b.earnedLoot[Resource.GOLD]),
        elixir = Format.amount(b.earnedLoot[Resource.ELIXIR]),
        dark = Format.amount(b.earnedLoot[Resource.DARK_ELIXIR]),
        stars = b.stars?.toString() ?: Format.UNKNOWN,
        damage = b.damagePercent?.let { "$it%" } ?: Format.UNKNOWN,
        skips = b.skippedOpponents.toString(),
        note = when (b.outcome) {
            BattleRecord.INTERRUPTED -> "interrupted"
            BattleRecord.IN_PROGRESS -> "in progress"
            else -> ""
        }
    )

    fun raidRows(row: SessionRow, zone: ZoneId): List<RaidRow> = row.raids.map { raidRow(it, zone) }

    data class LastRaid(val battle: BattleRecord, val earned: Long?) {
        /** "+412k", or "-" when the result was not read. */
        val headline: String get() = if (earned == null) Format.UNKNOWN else Format.signed(earned)
        /** "2 stars, 6 min ago". */
        fun detail(nowMs: Long): String {
            val stars = battle.stars?.let { Format.count(it, "star") } ?: "not read"
            return "$stars, ${Format.ago(battle.endedAtMs ?: battle.startedAtMs, nowMs)}"
        }
    }

    /** The account's newest completed battle; [LastRaid.earned] is its gold plus elixir when read. */
    fun lastRaid(records: List<BattleRecord>, accountId: String): LastRaid? {
        val b = records.filter { it.accountId == accountId && it.outcome == BattleRecord.COMPLETED }
            .maxByOrNull { it.startedAtMs } ?: return null
        val earned = if (b.earnedLoot.isEmpty()) null
            else (b.earnedLoot[Resource.GOLD] ?: 0L) + (b.earnedLoot[Resource.ELIXIR] ?: 0L)
        return LastRaid(b, earned)
    }

    /** Chart input: the last [n] sessions oldest first, each as its rate and account. */
    data class Bar(val sessionId: String, val accountId: String, val label: String, val lootPerHour: Long)

    fun chart(rows: List<SessionRow>, zone: ZoneId, n: Int = 14): List<Bar> =
        rows.sortedByDescending { it.startedAtMs }.take(n).reversed().map {
            Bar(it.sessionId, it.accountId, Format.day(it.startedAtMs, zone), it.lootPerHour ?: 0L)
        }

    private fun totals(s: LootSummary): Totals {
        val read = s.battles - s.unread
        val g = s.perHour(Resource.GOLD)
        val e = s.perHour(Resource.ELIXIR)
        val rate = if (g == null || e == null || read <= 0) null else g + e
        return Totals(s.battles, s.unread, s.loot, s.farmingMs, rate)
    }

    internal fun accountName(id: String, accounts: List<AccountRecord>): String =
        accounts.firstOrNull { it.id == id }?.displayName ?: "Removed account"

    private const val DAY_MS = 86_400_000L
}
