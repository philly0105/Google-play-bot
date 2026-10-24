package local.clashfarmer.history

import local.clashfarmer.core.Resource

/**
 * What the battles since some moment earned, and at what rate over the time spent farming.
 * [loot] counts only amounts the result screen gave; a battle whose result was not read adds to
 * [battles] and [unread] but to no total. [farmingMs] runs from the first battle to the last one
 * of each session, so search and army time are in it and the gaps between sessions are not.
 */
data class LootSummary(
    val battles: Int,
    val unread: Int,
    val loot: Map<Resource, Long>,
    val farmingMs: Long
) {
    /** Null until there is at least a minute of farming time to divide by. */
    fun perHour(resource: Resource): Long? =
        if (farmingMs < 60_000L) null else (loot[resource] ?: 0L) * 3_600_000L / farmingMs

    companion object {
        fun of(records: List<BattleRecord>, sinceMs: Long): LootSummary {
            val battles = records.filter { it.startedAtMs >= sinceMs && it.outcome == BattleRecord.COMPLETED }
            val loot = Resource.entries.associateWith { r -> battles.sumOf { it.earnedLoot[r] ?: 0L } }
            val farmingMs = battles.groupBy { it.sessionId }.values.sumOf { session ->
                val first = session.minOf { it.startedAtMs }
                val last = session.maxOf { it.endedAtMs ?: it.startedAtMs }
                (last - first).coerceAtLeast(0L)
            }
            return LootSummary(battles.size, battles.count { it.earnedLoot.isEmpty() }, loot, farmingMs)
        }
    }
}
