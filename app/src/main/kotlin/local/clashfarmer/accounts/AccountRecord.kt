package local.clashfarmer.accounts

import org.json.JSONObject
import java.util.UUID

data class AccountLastResult(
    val timestamp: Long = System.currentTimeMillis(),
    val attacksCompleted: Int = 0,
    val attackLimit: Int = 0,
    val wallsUpgraded: Int = 0,
    val goldSpent: Long = 0L,
    val elixirSpent: Long = 0L,
    val purchaseOutcomeUnknown: Boolean = false,
    val unknownPurchaseCurrency: String? = null,
    val unknownPurchaseCost: Long = 0L,
    val unknownPurchaseWalls: Int = 0,
    val status: String = "Complete",
    val stopReason: String? = null,
    /** A [local.clashfarmer.core.CompletionReason] key when the run finished normally; null otherwise. */
    val completionReason: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("timestamp", timestamp)
        obj.put("attacksCompleted", attacksCompleted)
        obj.put("attackLimit", attackLimit)
        obj.put("wallsUpgraded", wallsUpgraded)
        obj.put("goldSpent", goldSpent)
        obj.put("elixirSpent", elixirSpent)
        obj.put("purchaseOutcomeUnknown", purchaseOutcomeUnknown)
        obj.put("unknownPurchaseCurrency", unknownPurchaseCurrency ?: JSONObject.NULL)
        obj.put("unknownPurchaseCost", unknownPurchaseCost)
        obj.put("unknownPurchaseWalls", unknownPurchaseWalls)
        obj.put("status", status)
        obj.put("stopReason", stopReason ?: JSONObject.NULL)
        obj.put("completionReason", completionReason ?: JSONObject.NULL)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): AccountLastResult {
            return AccountLastResult(
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                attacksCompleted = obj.optInt("attacksCompleted", 0),
                attackLimit = obj.optInt("attackLimit", 0),
                wallsUpgraded = obj.optInt("wallsUpgraded", 0),
                goldSpent = obj.optLong("goldSpent", 0L),
                elixirSpent = obj.optLong("elixirSpent", 0L),
                purchaseOutcomeUnknown = obj.optBoolean("purchaseOutcomeUnknown", false),
                unknownPurchaseCurrency = if (obj.isNull("unknownPurchaseCurrency")) null else obj.optString("unknownPurchaseCurrency"),
                unknownPurchaseCost = obj.optLong("unknownPurchaseCost", 0L),
                unknownPurchaseWalls = obj.optInt("unknownPurchaseWalls", 0),
                status = obj.optString("status", "Complete"),
                stopReason = if (obj.isNull("stopReason")) null else obj.optString("stopReason"),
                completionReason = if (obj.isNull("completionReason")) null else obj.optString("completionReason")
            )
        }
    }
}

data class AccountRecord(
    val id: String,
    val villageName: String,
    val supercellName: String,
    val playerTag: String,
    val settings: AccountSettings = AccountSettings(),
    val lastResult: AccountLastResult? = null
) {
    val displayName: String get() = villageName

    fun validate() {
        require(UUID_REGEX.matches(id)) { "Invalid opaque UUID id: '$id'" }
        try {
            UUID.fromString(id)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid opaque UUID id: '$id'", e)
        }
        require(villageName.isNotBlank()) { "Village name cannot be blank" }
        require(supercellName.isNotBlank()) { "Supercell ID name cannot be blank" }
        require(playerTag.isNotBlank()) { "Player tag cannot be blank" }
        settings.validate()
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("villageName", villageName)
        obj.put("supercellName", supercellName)
        obj.put("playerTag", playerTag)
        obj.put("settings", settings.toJson())
        lastResult?.let { obj.put("lastResult", it.toJson()) }
        return obj
    }

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun fromJson(obj: JSONObject): AccountRecord {
            if (!obj.has("id")) throw IllegalArgumentException("Missing required field 'id'")
            if (!obj.has("villageName")) throw IllegalArgumentException("Missing required field 'villageName'")
            if (!obj.has("supercellName")) throw IllegalArgumentException("Missing required field 'supercellName'")
            if (!obj.has("playerTag")) throw IllegalArgumentException("Missing required field 'playerTag'")

            val id = obj.getString("id")
            val villageName = obj.getString("villageName")
            val supercellName = obj.getString("supercellName")
            val playerTag = obj.getString("playerTag")

            val settings = if (obj.has("settings")) {
                val setObj = obj.optJSONObject("settings")
                    ?: throw IllegalArgumentException("Field 'settings' must be a JSON object")
                AccountSettings.fromJsonForLoad(setObj)
            } else {
                AccountSettings()
            }

            val lastResult = if (obj.has("lastResult") && !obj.isNull("lastResult")) {
                val resObj = obj.optJSONObject("lastResult")
                if (resObj != null) AccountLastResult.fromJson(resObj) else null
            } else {
                null
            }

            val record = AccountRecord(
                id = id,
                villageName = villageName,
                supercellName = supercellName,
                playerTag = playerTag,
                settings = settings,
                lastResult = lastResult
            )
            record.validate()
            return record
        }
    }
}
