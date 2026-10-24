package local.clashfarmer.history

import local.clashfarmer.core.Resource
import java.util.UUID

/**
 * Keeps the record of the battle a run is in. [start] is saved before the first troop goes down,
 * [result] only ever adds what was not yet known, and once a battle is finished or interrupted
 * every later call is a no-op, so a screen seen twice or a stop after the fact changes nothing.
 */
class BattleRecorder(
    private val store: BattleHistoryStore,
    private val sessionId: String,
    private val accountId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    var open: BattleRecord? = null
        private set

    fun start(availableLoot: Map<Resource, Long>, skips: Int, searchGold: Long): BattleRecord {
        open?.let { interrupt("next_battle_started") }
        val record = BattleRecord(
            newId(), sessionId, accountId, clock(),
            availableLoot = availableLoot, skippedOpponents = skips, searchGold = searchGold
        )
        store.save(record)
        open = record
        return record
    }

    fun result(earned: Map<Resource, Long>, damagePercent: Int?, stars: Int?) {
        val current = open ?: return
        val updated = current.copy(
            earnedLoot = earned + current.earnedLoot,
            damagePercent = current.damagePercent ?: damagePercent,
            stars = current.stars ?: stars
        )
        if (updated == current) return
        store.save(updated)
        open = updated
    }

    fun finish(reason: String) = close(BattleRecord.COMPLETED, reason)

    fun interrupt(reason: String) = close(BattleRecord.INTERRUPTED, reason)

    private fun close(outcome: String, reason: String) {
        val current = open ?: return
        store.save(current.copy(endedAtMs = clock(), outcome = outcome, reason = reason))
        open = null
    }
}
