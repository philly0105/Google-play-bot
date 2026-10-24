package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Resource
import local.clashfarmer.history.BattleRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Battle history as CSV for a spreadsheet: one row per battle, oldest first, full numbers.
 * A value that was not read is an empty cell, never 0. Times are local, "2026-10-08 06:51:04".
 */
object HistoryCsv {
    val HEADER = listOf(
        "battle id", "session id", "account", "started", "ended", "outcome",
        "gold", "elixir", "dark", "stars", "damage", "skips", "search gold"
    )

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val FILE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    fun of(
        records: List<BattleRecord>,
        accounts: List<AccountRecord>,
        zone: ZoneId = ZoneId.systemDefault(),
        sinceMs: Long = Long.MIN_VALUE
    ): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (b in records.filter { it.startedAtMs >= sinceMs }.sortedBy { it.startedAtMs }) {
            val cells = listOf(
                b.id,
                b.sessionId,
                SessionStats.accountName(b.accountId, accounts),
                stamp(b.startedAtMs, zone),
                b.endedAtMs?.let { stamp(it, zone) } ?: "",
                b.outcome.replace('_', ' '),
                b.earnedLoot[Resource.GOLD]?.toString() ?: "",
                b.earnedLoot[Resource.ELIXIR]?.toString() ?: "",
                b.earnedLoot[Resource.DARK_ELIXIR]?.toString() ?: "",
                b.stars?.toString() ?: "",
                b.damagePercent?.toString() ?: "",
                b.skippedOpponents.toString(),
                b.searchGold.toString()
            )
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** clashfarmer-history-20261008.csv for the local day of [nowMs]. */
    fun fileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "clashfarmer-history-${FILE_DAY.format(Instant.ofEpochMilli(nowMs).atZone(zone))}.csv"

    private fun stamp(ms: Long, zone: ZoneId) = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Quotes a cell holding a comma, quote or line break, doubling inner quotes (RFC 4180). */
    internal fun cell(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
