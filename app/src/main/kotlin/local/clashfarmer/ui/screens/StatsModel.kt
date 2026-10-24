package local.clashfarmer.ui.screens

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.SessionStats
import java.time.ZoneId
import java.util.Locale

/** What Stats shows for one period and account filter. No Android here. */
data class StatsModel(
    /** No battle has ever been recorded. */
    val empty: Boolean,
    val totals: SessionStats.Totals,
    /** The chart's last 14 sessions, oldest first. */
    val bars: List<SessionStats.Bar>,
    /** Every account that farmed in the period, whatever the account filter. */
    val byAccount: List<SessionStats.AccountRate>,
    /** Newest first. */
    val sessions: List<SessionStats.SessionRow>,
    val period: SessionStats.Period,
    val accountId: String?,
    /** History files that would not read, left out of everything above. */
    val corrupt: Int = 0,
    /** Set when the store's cap cut the period short; see [coverage]. */
    val coverage: String? = null
) {
    /** "13.6M/h"; [StatTile] shrinks it to fit the tile. */
    val rateText: String get() = Format.rate(totals.lootPerHour)
    val battlesText: String get() = totals.battles.toString()
    val totalText: String get() = Format.amount(totals.lootTotal)

    /** "9 battles not read" under the tiles, or null when every result was read. */
    val unreadChip: String? get() = if (totals.unread > 0) "${Format.count(totals.unread, "battle")} not read" else null

    /** The chart header's note: the best session's rate, or null when no bar has height. */
    val maxText: String? get() = bars.maxOfOrNull { it.lootPerHour }?.takeIf { it > 0 }?.let { "max ${Format.rate(it)}" }

    /**
     * The chart's text when no bar has height, from the charted sessions alone. A bar is flat when
     * nothing was read, when what was read earned 0, or when the session farmed under a minute and so
     * has no rate. Worded per session: short sessions can add up to a rate on the tile.
     */
    val chartEmpty: String
        get() {
            val charted = sessions.take(bars.size)
            val read = charted.filter { it.battles > it.unread }
            return when {
                read.isEmpty() -> "No loot read in this period"
                read.any { (it.loot[Resource.GOLD] ?: 0L) + (it.loot[Resource.ELIXIR] ?: 0L) > 0L } -> "A session needs a minute of farming for a rate"
                charted.any { it.unread > 0 } -> "Nothing earned in the battles read"
                else -> "Nothing earned in this period"
            }
        }

    /** The status line: the history's coverage and any files left out, or null when both are fine. */
    val statusLine: String?
        get() = listOfNotNull(
            coverage,
            if (corrupt > 0) "Left out ${Format.count(corrupt, "history file")} that could not be read." else null
        ).joinToString("\n").ifEmpty { null }

    /** The plain line shown in place of the data, or null when there is data to show. */
    val emptyLine: String?
        get() = when {
            empty && corrupt > 0 -> "No readable raids"
            empty -> "No raids yet"
            sessions.isEmpty() -> "No raids in this period"
            else -> null
        }

    /** Suggests only the choices that would widen what is shown. */
    val emptyDetail: String
        get() {
            if (empty && corrupt > 0) return "Battles from the next run show up here."
            if (empty) return "Battles show up here after the first run."
            val wider = listOfNotNull(
                "a longer period".takeIf { period != SessionStats.Period.DAYS_30 },
                "all accounts".takeIf { accountId != null }
            )
            return if (wider.isEmpty()) "Older raids are still in the CSV export." else "Pick ${wider.joinToString(" or ")}."
        }

    /** A session list row: "Oct 8 06:51 · Sweat" over "12 battles · 1h04 · Stopped", the rate on the right. */
    data class SessionLine(val title: String, val detail: String, val rate: String)

    companion object {
        fun build(
            records: List<BattleRecord>,
            accounts: List<AccountRecord>,
            endings: Map<String, String>,
            period: SessionStats.Period,
            accountId: String?,
            nowMs: Long,
            zone: ZoneId,
            corrupt: Int = 0
        ): StatsModel {
            val since = SessionStats.sinceMs(period, nowMs, zone)
            val sessions = SessionStats.sessions(records, accounts, endings, since, accountId)
            return StatsModel(
                empty = records.isEmpty(),
                totals = SessionStats.summary(records, since, accountId),
                bars = SessionStats.chart(sessions, zone),
                byAccount = SessionStats.byAccount(records, since, accounts),
                sessions = sessions,
                period = period,
                accountId = accountId,
                corrupt = corrupt,
                coverage = coverage(records, since, zone)
            )
        }

        /**
         * "History keeps the newest 1,000 battles: these numbers start Oct 4, 06:12" when the store
         * is full and its oldest battle is newer than [sinceMs], so the period's numbers are short;
         * null otherwise. The remote page shows the same line.
         */
        fun coverage(records: List<BattleRecord>, sinceMs: Long, zone: ZoneId): String? {
            if (records.size < BattleHistoryStore.MAX_BATTLES) return null
            val oldest = records.minOf { it.startedAtMs }
            if (oldest <= sinceMs) return null
            val kept = String.format(Locale.US, "%,d", BattleHistoryStore.MAX_BATTLES)
            return "History keeps the newest $kept battles: these numbers start ${Format.day(oldest, zone)}, ${Format.time(oldest, zone)}"
        }

        fun line(row: SessionStats.SessionRow, zone: ZoneId): SessionLine {
            val cells = SessionStats.sessionCells(row, zone)
            val detail = listOfNotNull(
                cells[2],
                cells[3],
                row.interrupted.takeIf { it > 0 }?.let { "$it interrupted" },
                cells[5].ifEmpty { null }
            ).joinToString(" · ")
            return SessionLine("${cells[0]} · ${cells[1]}", detail, cells[4])
        }
    }
}
