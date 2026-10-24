package local.clashfarmer.accounts

import local.clashfarmer.core.DEFAULT_DAMAGE_MAX
import local.clashfarmer.core.DEFAULT_DAMAGE_MIN
import local.clashfarmer.core.EarlyEndRules
import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.OpponentRules
import local.clashfarmer.core.Resource
import local.clashfarmer.core.WallStorageRules
import local.clashfarmer.core.percentOfFloor
import local.clashfarmer.core.wallSpendable
import org.json.JSONObject
import java.util.Locale

data class AccountSettings(
    val attacks: Int = 15,
    val maxMinutes: Int = 120,
    val wallsEnabled: Boolean = false,
    /** A wall phase starts once gold or elixir is at this percentage of its capacity. */
    val triggerPercent: Int = 90,
    /** ...and buys until the resource would be at or under this percentage. */
    val resumePercent: Int = 75,
    val maxWallLevel: Int = 17,
    /** Retired constructor fields. Saved values are ignored and spending always uses zero. */
    val reserveGold: Long = 0L,
    val reserveElixir: Long = 0L,
    val currencyPreference: String = "auto",
    val maxWallsPerPhase: Int = 20,
    /** No targets means no goal: the run ends on its attack quota alone. */
    val goal: FarmingGoal = FarmingGoal(),
    /** Storage per resource, typed in by the player. A reading above it is a misread. */
    val capacities: Map<Resource, Long> = emptyMap(),
    /** Which bases are worth attacking. Off by default: every base found is attacked. */
    val opponent: OpponentRules = OpponentRules(),
    /** Tap the calibrated collectors on the village before the first search. */
    val collectBeforeFarming: Boolean = false,
    /** Tap them again once the last battle is home. */
    val collectAfterFarming: Boolean = false,
    /** When a battle is surrendered rather than waited out. Off by default. */
    val earlyEnd: EarlyEndRules = EarlyEndRules(),
    /**
     * The Saved Recipes card, by its title, to load before a run's first attack. Blank leaves the
     * army as it is.
     */
    val savedArmy: String = "",
    /**
     * Why walls were switched off on load, or null. Set only by [fromJsonForLoad] when saved
     * settings fail the wall checks; never written as such, [toJson] keeps the saved
     * wallsEnabled so fixing the setup does not lose what the player asked for.
     */
    val wallSetupRequired: String? = null
) {

    /** Every rule, wall spending included. Throws with the first problem. */
    fun validate() {
        validateSettings()
        wallSetupProblem()?.let { throw IllegalArgumentException(it) }
    }

    /** Manual walls need capacities even when automatic wall spending is off. */
    fun wallCapacityProblem(): String? = wallSpendable(currencyPreference)
        .sortedBy { it.ordinal }
        .firstOrNull { (capacities[it] ?: 0L) <= 0L }
        ?.let { "Wall spending needs the ${it.key} capacity." }

    /**
     * What stops wall spending from working with these settings, or null. Spending and saving
     * work against each other when a goal sits above the level at which walls start spending,
     * so targets above the trigger are refused. Only the
     * resources the currency preference lets walls spend are checked.
     */
    fun wallSetupProblem(): String? {
        if (!wallsEnabled) return null
        val rules = WallStorageRules(triggerPercent, resumePercent)
        for (resource in wallSpendable(currencyPreference).sortedBy { it.ordinal }) {
            val name = resource.key.replaceFirstChar { it.uppercase() }
            val capacity = capacities[resource]
                ?: return "Wall spending needs the ${resource.key} capacity."
            val triggerLevel = percentOfFloor(capacity, rules.triggerPercent)
            val target = goal.targets[resource]
            if (target != null && target > triggerLevel) {
                val other = if (resource == Resource.GOLD) "elixir" else "gold"
                return "$name goal ${amount(target)} is above the wall trigger ${amount(triggerLevel)}. " +
                    "Lower the goal, raise the trigger, or set walls to $other only."
            }
        }
        return null
    }

    private fun amount(value: Long) = String.format(Locale.US, "%,d", value)

    /** Everything except the wall-spending checks, which [fromJsonForLoad] forgives. */
    private fun validateSettings() {
        require(attacks in 1..100) { "Attacks must be between 1 and 100 (got: $attacks)" }
        require(maxMinutes in 1..120) { "Max minutes must be between 1 and 120 (got: $maxMinutes)" }
        require(triggerPercent in 1..100) { "Trigger percent must be between 1 and 100 (got: $triggerPercent)" }
        require(resumePercent in 0 until triggerPercent) {
            "Resume percent must be between 0 and ${triggerPercent - 1} (got: $resumePercent)"
        }
        require(maxWallLevel in 1..25) { "Max wall level must be between 1 and 25 (got: $maxWallLevel)" }
        require(currencyPreference in VALID_CURRENCY_PREFERENCES) {
            "Currency preference must be one of $VALID_CURRENCY_PREFERENCES (got: '$currencyPreference')"
        }
        require(maxWallsPerPhase in 1..100) {
            "Max walls per phase must be between 1 and 100 (got: $maxWallsPerPhase)"
        }
        for ((resource, capacity) in capacities) {
            require(capacity > 0L) { "Capacity for ${resource.key} must be positive (got: $capacity)" }
        }
        for ((resource, target) in goal.targets) {
            require(target > 0L) { "Goal for ${resource.key} must be positive (got: $target)" }
            val capacity = capacities[resource]
                ?: throw IllegalArgumentException("Goal for ${resource.key} needs that resource's capacity")
            require(target <= capacity) {
                "Goal for ${resource.key} ($target) is more than its capacity ($capacity)"
            }
        }
        for ((resource, minimum) in opponent.minimums) {
            require(minimum > 0L) { "Minimum ${resource.key} loot must be positive (got: $minimum)" }
        }
        opponent.minimumGoldAndElixir?.let {
            require(it > 0L) { "Minimum gold plus elixir must be positive (got: $it)" }
        }
        require(opponent.maxSkips in 0..MAX_SKIPS) {
            "Max skips must be between 0 and $MAX_SKIPS (got: ${opponent.maxSkips})"
        }
        require(opponent.maxSearchMs in MIN_SEARCH_MS..MAX_SEARCH_MS) {
            "Search time must be between ${MIN_SEARCH_MS / 1000} and ${MAX_SEARCH_MS / 1000} seconds (got: ${opponent.maxSearchMs} ms)"
        }
        require(opponent.maxSearchGold >= 0L) { "Search gold must be non-negative (got: ${opponent.maxSearchGold})" }
        require(earlyEnd.afterDeploySeconds in 0..MAX_BATTLE_SECONDS) {
            "Seconds after deploy must be between 0 and $MAX_BATTLE_SECONDS (got: ${earlyEnd.afterDeploySeconds})"
        }
        require(earlyEnd.battleSecondsLeft in 0 until MAX_BATTLE_SECONDS) {
            "Battle seconds left must be between 0 and ${MAX_BATTLE_SECONDS - 1} (got: ${earlyEnd.battleSecondsLeft})"
        }
        for ((resource, below) in earlyEnd.lootLeftBelow) {
            require(below > 0L) { "Loot left for ${resource.key} must be positive (got: $below)" }
        }
        require(earlyEnd.damageMin in 1..100 && earlyEnd.damageMax in 1..100) {
            "Damage percents must be between 1 and 100 (got: ${earlyEnd.damageMin} to ${earlyEnd.damageMax})"
        }
        require(earlyEnd.damageMin <= earlyEnd.damageMax) {
            "Lowest damage ${earlyEnd.damageMin}% is above the highest ${earlyEnd.damageMax}%"
        }
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("attacks", attacks)
        obj.put("maxMinutes", maxMinutes)
        obj.put("wallsEnabled", wallsEnabled || wallSetupRequired != null)
        obj.put("triggerPercent", triggerPercent)
        obj.put("resumePercent", resumePercent)
        obj.put("maxWallLevel", maxWallLevel)
        obj.put("currencyPreference", currencyPreference)
        obj.put("maxWallsPerPhase", maxWallsPerPhase)
        obj.put("goal", JSONObject().apply {
            put("match", goal.match.key)
            put("targets", amountsJson(goal.targets))
        })
        obj.put("capacities", amountsJson(capacities))
        obj.put("opponent", JSONObject().apply {
            put("enabled", opponent.enabled)
            put("minimums", amountsJson(opponent.minimums))
            opponent.minimumGoldAndElixir?.let { put("minimumGoldAndElixir", it) }
            put("maxSkips", opponent.maxSkips)
            put("maxSearchMs", opponent.maxSearchMs)
            put("maxSearchGold", opponent.maxSearchGold)
        })
        obj.put("collectBeforeFarming", collectBeforeFarming)
        obj.put("collectAfterFarming", collectAfterFarming)
        obj.put("earlyEnd", JSONObject().apply {
            put("enabled", earlyEnd.enabled)
            put("afterDeploySeconds", earlyEnd.afterDeploySeconds)
            put("battleSecondsLeft", earlyEnd.battleSecondsLeft)
            put("lootLeftBelow", amountsJson(earlyEnd.lootLeftBelow))
            put("atDamage", earlyEnd.atDamage)
            put("damageMin", earlyEnd.damageMin)
            put("damageMax", earlyEnd.damageMax)
            put("onStall", earlyEnd.onStall)
            put("stallShadow", earlyEnd.stallShadow)
        })
        obj.put("savedArmy", savedArmy)
        return obj
    }

    companion object {
        val VALID_CURRENCY_PREFERENCES = setOf("auto", "gold", "elixir")
        const val MAX_SKIPS = 50
        const val MIN_SEARCH_MS = 1_000L
        const val MAX_SEARCH_MS = 300_000L
        /** A battle lasts three minutes. */
        const val MAX_BATTLE_SECONDS = 180

        private fun parseStrictInt(obj: JSONObject, key: String, default: Int): Int {
            if (!obj.has(key)) return default
            val v = obj.get(key)
            if (v is Int) return v
            if (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) return v.toInt()
            if (v is Number) {
                val d = v.toDouble()
                if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) {
                    return d.toInt()
                }
            }
            throw IllegalArgumentException("Field '$key' must be an integer, got: $v")
        }

        private fun parseStrictLong(obj: JSONObject, key: String, default: Long): Long {
            if (!obj.has(key)) return default
            val v = obj.get(key)
            if (v is Long) return v
            if (v is Int) return v.toLong()
            if (v is Number) {
                val d = v.toDouble()
                if (d == Math.floor(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                    return d.toLong()
                }
            }
            throw IllegalArgumentException("Field '$key' must be an integer/long, got: $v")
        }

        private fun parseStrictBoolean(obj: JSONObject, key: String, default: Boolean): Boolean {
            if (!obj.has(key)) return default
            val v = obj.get(key)
            if (v is Boolean) return v
            throw IllegalArgumentException("Field '$key' must be a boolean, got: $v")
        }

        private fun parseStrictString(obj: JSONObject, key: String, default: String): String {
            if (!obj.has(key)) return default
            val v = obj.get(key)
            if (v is String) return v
            throw IllegalArgumentException("Field '$key' must be a string, got: $v")
        }

        private fun amountsJson(amounts: Map<Resource, Long>) = JSONObject().apply {
            for ((resource, amount) in amounts) put(resource.key, amount)
        }

        /** Resource amounts keyed by resource name. Unknown names and non-integers are refused. */
        private fun parseAmounts(obj: JSONObject, key: String): Map<Resource, Long> {
            if (!obj.has(key)) return emptyMap()
            val amounts = obj.optJSONObject(key)
                ?: throw IllegalArgumentException("Field '$key' must be a JSON object")
            val parsed = linkedMapOf<Resource, Long>()
            for (name in amounts.keys()) {
                val resource = Resource.fromKey(name)
                    ?: throw IllegalArgumentException("Unknown resource '$name' in '$key'")
                parsed[resource] = parseStrictLong(amounts, name, 0L)
            }
            return parsed
        }

        private fun parseGoal(obj: JSONObject): FarmingGoal {
            if (!obj.has("goal")) return FarmingGoal()
            val goal = obj.optJSONObject("goal")
                ?: throw IllegalArgumentException("Field 'goal' must be a JSON object")
            val matchKey = parseStrictString(goal, "match", GoalMatch.ALL.key)
            val match = GoalMatch.fromKey(matchKey)
                ?: throw IllegalArgumentException("Goal match must be 'all' or 'any' (got: '$matchKey')")
            return FarmingGoal(parseAmounts(goal, "targets"), match)
        }

        private fun parseOpponent(obj: JSONObject): OpponentRules {
            if (!obj.has("opponent")) return OpponentRules()
            val o = obj.optJSONObject("opponent")
                ?: throw IllegalArgumentException("Field 'opponent' must be a JSON object")
            val defaults = OpponentRules()
            return OpponentRules(
                enabled = parseStrictBoolean(o, "enabled", defaults.enabled),
                minimums = parseAmounts(o, "minimums"),
                minimumGoldAndElixir = if (o.has("minimumGoldAndElixir")) parseStrictLong(o, "minimumGoldAndElixir", 0L) else null,
                maxSkips = parseStrictInt(o, "maxSkips", defaults.maxSkips),
                maxSearchMs = parseStrictLong(o, "maxSearchMs", defaults.maxSearchMs),
                maxSearchGold = parseStrictLong(o, "maxSearchGold", defaults.maxSearchGold)
            )
        }

        private fun parseEarlyEnd(obj: JSONObject): EarlyEndRules {
            if (!obj.has("earlyEnd")) return EarlyEndRules()
            val e = obj.optJSONObject("earlyEnd")
                ?: throw IllegalArgumentException("Field 'earlyEnd' must be a JSON object")
            return EarlyEndRules(
                enabled = parseStrictBoolean(e, "enabled", false),
                afterDeploySeconds = parseStrictInt(e, "afterDeploySeconds", 0),
                battleSecondsLeft = parseStrictInt(e, "battleSecondsLeft", 0),
                lootLeftBelow = parseAmounts(e, "lootLeftBelow"),
                atDamage = parseStrictBoolean(e, "atDamage", false),
                damageMin = parseStrictInt(e, "damageMin", DEFAULT_DAMAGE_MIN),
                damageMax = parseStrictInt(e, "damageMax", DEFAULT_DAMAGE_MAX),
                onStall = parseStrictBoolean(e, "onStall", false),
                stallShadow = parseStrictBoolean(e, "stallShadow", true)
            )
        }

        /** Strict: settings that fail any rule, wall setup included, are refused. */
        fun fromJson(obj: JSONObject): AccountSettings {
            val settings = parse(obj)
            settings.validate()
            return settings
        }

        /**
         * For loading saved accounts. A wall-setup problem does not discard the account: walls
         * are switched off in memory and the reason is kept in [wallSetupRequired] so attacks
         * stay available. Nothing on disk is rewritten here. Any other problem still throws.
         * The retired attacksPerWallPhase field is not read.
         */
        fun fromJsonForLoad(obj: JSONObject): AccountSettings {
            val settings = parse(obj)
            settings.validateSettings()
            val problem = settings.wallSetupProblem() ?: return settings
            return settings.copy(wallsEnabled = false, wallSetupRequired = problem)
        }

        private fun parse(obj: JSONObject): AccountSettings {
            return AccountSettings(
                attacks = parseStrictInt(obj, "attacks", 15),
                maxMinutes = parseStrictInt(obj, "maxMinutes", 120),
                wallsEnabled = parseStrictBoolean(obj, "wallsEnabled", false),
                triggerPercent = parseStrictInt(obj, "triggerPercent", 90),
                resumePercent = parseStrictInt(obj, "resumePercent", 75),
                maxWallLevel = parseStrictInt(obj, "maxWallLevel", 17),
                reserveGold = 0L,
                reserveElixir = 0L,
                currencyPreference = parseStrictString(obj, "currencyPreference", "auto"),
                maxWallsPerPhase = parseStrictInt(obj, "maxWallsPerPhase", 20),
                goal = parseGoal(obj),
                capacities = parseAmounts(obj, "capacities"),
                opponent = parseOpponent(obj),
                collectBeforeFarming = parseStrictBoolean(obj, "collectBeforeFarming", false),
                collectAfterFarming = parseStrictBoolean(obj, "collectAfterFarming", false),
                earlyEnd = parseEarlyEnd(obj),
                savedArmy = parseStrictString(obj, "savedArmy", "")
            )
        }
    }
}
