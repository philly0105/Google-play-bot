package local.clashfarmer.history

import local.clashfarmer.core.Resource
import org.json.JSONObject

/**
 * One deployed battle. [availableLoot] is what the scout offered and [earnedLoot] what the result
 * screen said was taken; they are different readings and neither is ever filled from the other.
 * Anything not read stays null or absent, and is shown as unknown rather than zero.
 */
data class BattleRecord(
    val id: String,
    val sessionId: String,
    val accountId: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val availableLoot: Map<Resource, Long> = emptyMap(),
    val earnedLoot: Map<Resource, Long> = emptyMap(),
    val stars: Int? = null,
    val damagePercent: Int? = null,
    val skippedOpponents: Int = 0,
    val searchGold: Long = 0,
    val outcome: String = IN_PROGRESS,
    val reason: String? = null
) {
    companion object {
        const val IN_PROGRESS = "in_progress"
        /** The battle ran to its end. Says nothing about victory; that is the result's to say. */
        const val COMPLETED = "completed"
        /** The run lost the battle part-way: a stop, a failure, or a process that died. */
        const val INTERRUPTED = "interrupted"
        private val OUTCOMES = setOf(IN_PROGRESS, COMPLETED, INTERRUPTED)

        /** Throws on anything it does not recognise; the store reports such a file as corrupt. */
        fun fromJson(json: JSONObject): BattleRecord {
            val outcome = json.getString("outcome")
            require(outcome in OUTCOMES) { "Unknown battle outcome '$outcome'" }
            return BattleRecord(
                id = json.getString("id"),
                sessionId = json.getString("sessionId"),
                accountId = json.getString("accountId"),
                startedAtMs = json.getLong("startedAtMs"),
                endedAtMs = if (json.isNull("endedAtMs")) null else json.getLong("endedAtMs"),
                availableLoot = loot(json.getJSONObject("availableLoot")),
                earnedLoot = loot(json.getJSONObject("earnedLoot")),
                stars = if (json.isNull("stars")) null else json.getInt("stars").also { require(it in 0..3) },
                damagePercent = if (json.isNull("damagePercent")) null
                    else json.getInt("damagePercent").also { require(it in 0..100) },
                skippedOpponents = json.getInt("skippedOpponents"),
                searchGold = json.getLong("searchGold"),
                outcome = outcome,
                reason = if (json.isNull("reason")) null else json.getString("reason")
            )
        }

        private fun loot(json: JSONObject): Map<Resource, Long> = json.keys().asSequence().associate { key ->
            (Resource.fromKey(key) ?: throw IllegalArgumentException("Unknown resource '$key'")) to json.getLong(key)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sessionId", sessionId)
        .put("accountId", accountId)
        .put("startedAtMs", startedAtMs)
        .put("endedAtMs", endedAtMs ?: JSONObject.NULL)
        .put("availableLoot", lootJson(availableLoot))
        .put("earnedLoot", lootJson(earnedLoot))
        .put("stars", stars ?: JSONObject.NULL)
        .put("damagePercent", damagePercent ?: JSONObject.NULL)
        .put("skippedOpponents", skippedOpponents)
        .put("searchGold", searchGold)
        .put("outcome", outcome)
        .put("reason", reason ?: JSONObject.NULL)

    private fun lootJson(loot: Map<Resource, Long>) = JSONObject().apply {
        loot.forEach { (resource, amount) -> put(resource.key, amount) }
    }
}
