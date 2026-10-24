package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.DAMAGE_FLOOR_WARNING
import local.clashfarmer.core.DEFAULT_DAMAGE_MAX
import local.clashfarmer.core.DEFAULT_DAMAGE_MIN
import local.clashfarmer.core.EarlyEndRules
import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.LEAGUE_BONUS_DAMAGE_FLOOR
import local.clashfarmer.core.OpponentRules
import local.clashfarmer.core.Policy
import local.clashfarmer.core.Resource
import local.clashfarmer.insight.Format
import java.util.Locale

/** The account editor's fields as typed. Amount maps hold only the boxes that have text. */
data class SettingsFields(
    val attacks: String,
    val maxMinutes: String,
    val wallsEnabled: Boolean,
    val triggerPercent: String,
    val resumePercent: String,
    val maxWallLevel: String,
    val reserveGold: String,
    val reserveElixir: String,
    val currencyPreference: String,
    val maxWallsPerPhase: String,
    val goalMatch: GoalMatch,
    val goalTargets: Map<Resource, String>,
    val capacities: Map<Resource, String>,
    val opponentEnabled: Boolean,
    val minimums: Map<Resource, String>,
    val minimumGoldAndElixir: String,
    val maxSkips: String,
    val maxSearchSeconds: String,
    val maxSearchGold: String,
    val collectBeforeFarming: Boolean = false,
    val collectAfterFarming: Boolean = false,
    val earlyEndEnabled: Boolean = false,
    /** Blank turns a trigger off, as 0 does. */
    val endAfterDeploySeconds: String = "",
    val endBattleSecondsLeft: String = "",
    val endLootLeftBelow: Map<Resource, String> = emptyMap(),
    val endAtDamage: Boolean = false,
    val endDamageMin: String = DEFAULT_DAMAGE_MIN.toString(),
    val endDamageMax: String = DEFAULT_DAMAGE_MAX.toString(),
    val endOnStall: Boolean = false,
    /** Not edited here; carried so a stored value survives a save. */
    val stallShadow: Boolean = true,
    /** A Saved Recipes card title; blank keeps the current army. */
    val savedArmy: String = ""
)

sealed class SettingsEdit {
    data class Valid(val settings: AccountSettings) : SettingsEdit()
    /** Errors by field key; "form" holds the rule that failed across fields or on a range. */
    data class Invalid(val errors: Map<String, String>) : SettingsEdit()
}

/** The account editor's collapsible sections, in screen order. */
enum class SettingsSection(val title: String) {
    RUN_LIMITS("Run limits"),
    WALLS("Walls"),
    STORAGE_GOAL("Storage and goal"),
    OPPONENTS("Opponents"),
    ENDING("Ending battles"),
    COLLECTION("Collection"),
    ARMY("Army")
}

/** Where each error shows: under the field it names, or in [top] when no field owns it. */
data class PlacedErrors(val byField: Map<String, String>, val top: List<String>)

/**
 * Turns the editor's text into settings, or names what is wrong. Nothing is clamped and a bad
 * box never falls back to the stored value: the editor stays open until every field reads.
 * A blank required number is an error; a blank goal, capacity or minimum means "not set".
 */
object FarmingSettingsEditor {

    fun resourceName(resource: Resource) = when (resource) {
        Resource.GOLD -> "Gold"
        Resource.ELIXIR -> "Elixir"
        Resource.DARK_ELIXIR -> "Dark elixir"
    }

    fun fieldsOf(s: AccountSettings) = SettingsFields(
        attacks = s.attacks.toString(),
        maxMinutes = s.maxMinutes.toString(),
        // Walls forced off on load still show as on, so saving shows why they cannot run.
        wallsEnabled = s.wallsEnabled || s.wallSetupRequired != null,
        triggerPercent = s.triggerPercent.toString(),
        resumePercent = s.resumePercent.toString(),
        maxWallLevel = s.maxWallLevel.toString(),
        reserveGold = s.reserveGold.toString(),
        reserveElixir = s.reserveElixir.toString(),
        currencyPreference = s.currencyPreference,
        maxWallsPerPhase = s.maxWallsPerPhase.toString(),
        goalMatch = s.goal.match,
        goalTargets = s.goal.targets.mapValues { it.value.toString() },
        capacities = s.capacities.mapValues { it.value.toString() },
        opponentEnabled = s.opponent.enabled,
        minimums = s.opponent.minimums.mapValues { it.value.toString() },
        minimumGoldAndElixir = s.opponent.minimumGoldAndElixir?.toString().orEmpty(),
        maxSkips = s.opponent.maxSkips.toString(),
        // A stored time that is not whole seconds shows as it is and is refused, not rounded.
        maxSearchSeconds = if (s.opponent.maxSearchMs % 1000 == 0L) (s.opponent.maxSearchMs / 1000).toString()
            else (s.opponent.maxSearchMs / 1000.0).toString(),
        maxSearchGold = s.opponent.maxSearchGold.toString(),
        collectBeforeFarming = s.collectBeforeFarming,
        collectAfterFarming = s.collectAfterFarming,
        earlyEndEnabled = s.earlyEnd.enabled,
        endAfterDeploySeconds = s.earlyEnd.afterDeploySeconds.takeIf { it > 0 }?.toString().orEmpty(),
        endBattleSecondsLeft = s.earlyEnd.battleSecondsLeft.takeIf { it > 0 }?.toString().orEmpty(),
        endLootLeftBelow = s.earlyEnd.lootLeftBelow.mapValues { it.value.toString() },
        endAtDamage = s.earlyEnd.atDamage,
        endDamageMin = s.earlyEnd.damageMin.toString(),
        endDamageMax = s.earlyEnd.damageMax.toString(),
        endOnStall = s.earlyEnd.onStall,
        stallShadow = s.earlyEnd.stallShadow,
        savedArmy = s.savedArmy
    )

    private fun amount(value: Long) = String.format(Locale.US, "%,d", value)
    private fun amounts(values: Map<Resource, Long>) =
        values.entries.joinToString(", ") { "${resourceName(it.key)} ${amount(it.value)}" }

    /** What a run on these settings will aim for, skip and fall back to, for the start screen. */
    fun summary(s: AccountSettings): String {
        val goal = if (!s.goal.isSet) "Goal: none; the attack quota ends the run."
            else "Goal: stop when ${if (s.goal.match == GoalMatch.ANY) "any" else "all"} of ${amounts(s.goal.targets)} " +
                "${if (s.goal.targets.size == 1 || s.goal.match == GoalMatch.ANY) "is" else "are"} in storage."
        val o = s.opponent
        val opponents = if (!o.enabled) "Opponents: every base found is attacked."
            else {
                val thresholds = amounts(o.minimums) +
                    (o.minimumGoldAndElixir?.let { (if (o.minimums.isEmpty()) "" else ", ") + "Gold plus elixir ${amount(it)}" } ?: "")
                "Opponents: skip bases below ${thresholds.ifEmpty { "no threshold" }}. " +
                    "Up to ${o.maxSkips} skips, ${o.maxSearchMs / 1000} s and ${amount(o.maxSearchGold)} gold per attack; " +
                    "then the current base is attacked."
            }
        val e = s.earlyEnd
        // The range a battle actually picks from, as pickDamageTarget lifts it to the floor.
        val damageMin = maxOf(e.damageMin, LEAGUE_BONUS_DAMAGE_FLOOR)
        val damage = "at a damage picked between $damageMin% and ${maxOf(e.damageMax, damageMin)}% for each battle" +
            if (e.damageMin < LEAGUE_BONUS_DAMAGE_FLOOR) " (saved ${e.damageMin}% lifted to $damageMin%: $DAMAGE_FLOOR_WARNING)" else ""
        val early = if (!e.enabled || !e.anyTrigger) "Battles: each one runs to its end."
            else "Battles: surrender " + listOfNotNull(
                damage.takeIf { e.atDamage },
                e.afterDeploySeconds.takeIf { it > 0 }?.let { "$it s after the deploy" },
                e.battleSecondsLeft.takeIf { it > 0 }?.let { "with $it s left on the clock" },
                e.lootLeftBelow.takeIf { it.isNotEmpty() }?.let { "once the base has less than ${amounts(it)} left" },
                "when the battle stalls".takeIf { e.onStall }
            ).joinToString(", or ") + "."
        return "$goal\n$opponents\n$early"
    }

    /**
     * What these settings need that the profile lacks. [homeRegions] are the village balances the
     * profile can read; [scoutMissing] names the scout regions opponent rules need and it has not
     * calibrated; [lootMissing] the scout loot rows the loot-left trigger needs and it lacks.
     */
    fun readiness(
        s: AccountSettings,
        homeRegions: Set<Resource>,
        scoutMissing: List<String>,
        lootMissing: List<String> = emptyList()
    ): List<String> {
        val notes = mutableListOf<String>()
        for (r in s.goal.targets.keys - homeRegions) {
            notes += "Goal for ${resourceName(r)} has no village balance region, so it can never be met. Calibrate it in Setup."
        }
        if (s.opponent.enabled && scoutMissing.isNotEmpty()) {
            notes += "Opponent rules are on but the scout ${scoutMissing.joinToString(", ")} " +
                "${if (scoutMissing.size == 1) "is" else "are"} not calibrated; without them the run attacks rather than skips."
        }
        if (s.earlyEnd.enabled && s.earlyEnd.lootLeftBelow.isNotEmpty() && lootMissing.isNotEmpty()) {
            notes += "Ending battles on loot left needs the scout ${lootMissing.joinToString(", ")} " +
                "calibrated; without ${if (lootMissing.size == 1) "it" else "them"} that trigger never fires."
        }
        return notes
    }

    fun parse(f: SettingsFields): SettingsEdit {
        val errors = linkedMapOf<String, String>()

        fun number(key: String, label: String, text: String, required: Boolean, notWhole: String = "$label must be a whole number"): Long? {
            val t = text.trim()
            if (t.isEmpty()) {
                if (required) errors[key] = "$label is required"
                return null
            }
            if (!Regex("-?\\d+").matches(t)) { errors[key] = notWhole; return null }
            val value = t.toLongOrNull()
            if (value == null) errors[key] = "$label is too large"
            return value
        }
        fun int(key: String, label: String, text: String): Int? {
            val value = number(key, label, text, required = true) ?: return null
            if (value !in Int.MIN_VALUE..Int.MAX_VALUE) { errors[key] = "$label is too large"; return null }
            return value.toInt()
        }
        fun amounts(prefix: String, label: String, texts: Map<Resource, String>): Map<Resource, Long> =
            Resource.entries.mapNotNull { r ->
                val text = texts[r] ?: return@mapNotNull null
                number("${prefix}_${r.key}", "${resourceName(r)} $label", text, required = false)?.let { r to it }
            }.toMap()

        val attacks = int("attacks", "Attacks", f.attacks)
        val maxMinutes = int("maxMinutes", "Max minutes", f.maxMinutes)
        val triggerPercent = int("triggerPercent", "Trigger percent", f.triggerPercent)
        val resumePercent = int("resumePercent", "Resume percent", f.resumePercent)
        val maxWallLevel = int("maxWallLevel", "Target wall level", f.maxWallLevel)
        val maxWallsPerPhase = int("maxWallsPerPhase", "Max walls per phase", f.maxWallsPerPhase)
        val targets = amounts("goal", "goal", f.goalTargets)
        val capacities = amounts("capacity", "capacity", f.capacities)
        val minimums = amounts("minimum", "minimum", f.minimums)
        val minimumPair = number("minimumGoldAndElixir", "Gold plus elixir minimum", f.minimumGoldAndElixir, required = false)
        val maxSkips = int("maxSkips", "Max skips", f.maxSkips)
        val searchSeconds = number("maxSearchSeconds", "Search time", f.maxSearchSeconds, required = true,
            notWhole = "Search time must be a whole number of seconds")
        val maxSearchGold = number("maxSearchGold", "Search gold", f.maxSearchGold, required = true)
        val endAfterDeploy = if (f.endAfterDeploySeconds.isBlank()) 0L
            else number("endAfterDeploySeconds", "Seconds after deploy", f.endAfterDeploySeconds, required = true)
        val endSecondsLeft = if (f.endBattleSecondsLeft.isBlank()) 0L
            else number("endBattleSecondsLeft", "Battle seconds left", f.endBattleSecondsLeft, required = true)
        val lootLeftBelow = amounts("lootLeft", "loot left", f.endLootLeftBelow)
        val damageMin = int("endDamageMin", "Lowest damage", f.endDamageMin)
        val damageMax = int("endDamageMax", "Highest damage", f.endDamageMax)
        for ((key, value) in listOf("endAfterDeploySeconds" to endAfterDeploy, "endBattleSecondsLeft" to endSecondsLeft)) {
            if (value != null && value !in 0L..AccountSettings.MAX_BATTLE_SECONDS.toLong()) {
                errors[key] = "Battle times must be between 0 and ${AccountSettings.MAX_BATTLE_SECONDS} seconds"
            }
        }
        if (searchSeconds != null && (searchSeconds > Long.MAX_VALUE / 1000 || searchSeconds < Long.MIN_VALUE / 1000)) {
            errors["maxSearchSeconds"] = "Search time is too large"
        }
        val savedArmy = f.savedArmy.trim()
        if (savedArmy.isNotEmpty() && savedArmy.none { it.isLetterOrDigit() }) {
            errors["savedArmy"] = "Saved army must be a card's title, such as Army 5"
        }
        if (errors.isNotEmpty()) return SettingsEdit.Invalid(errors)

        val settings = AccountSettings(
            attacks = attacks!!,
            maxMinutes = maxMinutes!!,
            wallsEnabled = f.wallsEnabled,
            triggerPercent = triggerPercent!!,
            resumePercent = resumePercent!!,
            maxWallLevel = maxWallLevel!!,
            reserveGold = 0L,
            reserveElixir = 0L,
            currencyPreference = f.currencyPreference,
            maxWallsPerPhase = maxWallsPerPhase!!,
            goal = FarmingGoal(targets, f.goalMatch),
            capacities = capacities,
            opponent = OpponentRules(
                enabled = f.opponentEnabled,
                minimums = minimums,
                minimumGoldAndElixir = minimumPair,
                maxSkips = maxSkips!!,
                maxSearchMs = searchSeconds!! * 1000,
                maxSearchGold = maxSearchGold!!
            ),
            collectBeforeFarming = f.collectBeforeFarming,
            collectAfterFarming = f.collectAfterFarming,
            earlyEnd = EarlyEndRules(
                enabled = f.earlyEndEnabled,
                afterDeploySeconds = endAfterDeploy!!.toInt(),
                battleSecondsLeft = endSecondsLeft!!.toInt(),
                lootLeftBelow = lootLeftBelow,
                atDamage = f.endAtDamage,
                damageMin = damageMin!!,
                damageMax = damageMax!!,
                onStall = f.endOnStall,
                stallShadow = f.stallShadow
            ),
            savedArmy = savedArmy
        )
        return try {
            settings.validate()
            SettingsEdit.Valid(settings)
        } catch (e: IllegalArgumentException) {
            SettingsEdit.Invalid(mapOf("form" to (e.message ?: "Settings are not valid")))
        }
    }

    /**
     * [parse], then the rules the account store adds when it saves (a blank player tag, for one),
     * so the editor refuses what [local.clashfarmer.accounts.AccountStore.update] would.
     */
    fun parseFor(account: AccountRecord, f: SettingsFields): SettingsEdit {
        val edit = parse(f)
        if (edit !is SettingsEdit.Valid) return edit
        return try {
            account.copy(settings = edit.settings).validate()
            edit
        } catch (e: IllegalArgumentException) {
            SettingsEdit.Invalid(mapOf("form" to (e.message ?: "Account is not valid")))
        }
    }

    // ----- The editor sheet: text boxes, error placement and section summaries -----

    /** Every text box's value by field key, the same keys [SettingsEdit.Invalid] reports errors under. */
    fun textsOf(f: SettingsFields): Map<String, String> = buildMap {
        put("attacks", f.attacks)
        put("maxMinutes", f.maxMinutes)
        put("triggerPercent", f.triggerPercent)
        put("resumePercent", f.resumePercent)
        put("maxWallLevel", f.maxWallLevel)
        put("maxWallsPerPhase", f.maxWallsPerPhase)
        for (r in Resource.entries) {
            put("capacity_${r.key}", f.capacities[r].orEmpty())
            put("goal_${r.key}", f.goalTargets[r].orEmpty())
            put("minimum_${r.key}", f.minimums[r].orEmpty())
            put("lootLeft_${r.key}", f.endLootLeftBelow[r].orEmpty())
        }
        put("minimumGoldAndElixir", f.minimumGoldAndElixir)
        put("maxSkips", f.maxSkips)
        put("maxSearchSeconds", f.maxSearchSeconds)
        put("maxSearchGold", f.maxSearchGold)
        put("endAfterDeploySeconds", f.endAfterDeploySeconds)
        put("endBattleSecondsLeft", f.endBattleSecondsLeft)
        put("endDamageMin", f.endDamageMin)
        put("endDamageMax", f.endDamageMax)
        put("savedArmy", f.savedArmy)
    }

    /** [base] with its text boxes replaced from [texts]. A blank amount box means "not set". */
    fun withTexts(base: SettingsFields, texts: Map<String, String>): SettingsFields {
        fun t(key: String, old: String) = texts[key] ?: old
        fun amounts(prefix: String, old: Map<Resource, String>) = Resource.entries.mapNotNull { r ->
            val text = texts["${prefix}_${r.key}"] ?: old[r] ?: return@mapNotNull null
            if (text.isBlank()) null else r to text
        }.toMap()
        return base.copy(
            attacks = t("attacks", base.attacks),
            maxMinutes = t("maxMinutes", base.maxMinutes),
            triggerPercent = t("triggerPercent", base.triggerPercent),
            resumePercent = t("resumePercent", base.resumePercent),
            maxWallLevel = t("maxWallLevel", base.maxWallLevel),
            maxWallsPerPhase = t("maxWallsPerPhase", base.maxWallsPerPhase),
            capacities = amounts("capacity", base.capacities),
            goalTargets = amounts("goal", base.goalTargets),
            minimums = amounts("minimum", base.minimums),
            endLootLeftBelow = amounts("lootLeft", base.endLootLeftBelow),
            minimumGoldAndElixir = t("minimumGoldAndElixir", base.minimumGoldAndElixir),
            maxSkips = t("maxSkips", base.maxSkips),
            maxSearchSeconds = t("maxSearchSeconds", base.maxSearchSeconds),
            maxSearchGold = t("maxSearchGold", base.maxSearchGold),
            endAfterDeploySeconds = t("endAfterDeploySeconds", base.endAfterDeploySeconds),
            endBattleSecondsLeft = t("endBattleSecondsLeft", base.endBattleSecondsLeft),
            endDamageMin = t("endDamageMin", base.endDamageMin),
            endDamageMax = t("endDamageMax", base.endDamageMax),
            savedArmy = t("savedArmy", base.savedArmy)
        )
    }

    /** Message prefixes from [AccountSettings.validate] and the field each one is about. */
    private val formFields: List<Pair<String, String>> by lazy {
        listOf(
            "Attacks " to "attacks",
            "Max minutes " to "maxMinutes",
            "Trigger percent " to "triggerPercent",
            "Resume percent " to "resumePercent",
            "Max wall level " to "maxWallLevel",
            "Max walls per phase " to "maxWallsPerPhase",
            "Minimum gold plus elixir " to "minimumGoldAndElixir",
            "Max skips " to "maxSkips",
            "Search time " to "maxSearchSeconds",
            "Search gold " to "maxSearchGold",
            "Seconds after deploy " to "endAfterDeploySeconds",
            "Battle seconds left " to "endBattleSecondsLeft",
            "Damage percents " to "endDamageMin",
            "Lowest damage " to "endDamageMin"
        ) + Resource.entries.flatMap { r ->
            val k = r.key
            listOf(
                "Capacity for $k " to "capacity_$k",
                "Wall spending needs the $k capacity" to "capacity_$k",
                "Goal for $k " to "goal_$k",
                "${k.replaceFirstChar { it.uppercase() }} goal " to "goal_$k",
                "Minimum $k loot " to "minimum_$k",
                "Loot left for $k " to "lootLeft_$k"
            )
        }
    }

    /** The field a "form" message is about, by the label it starts with; null when none owns it. */
    fun fieldFor(message: String): String? = formFields.firstOrNull { message.startsWith(it.first) }?.second

    /** The section that holds [field]; null for a key no section shows. */
    fun sectionOf(field: String): SettingsSection? = when {
        field == "attacks" || field == "maxMinutes" -> SettingsSection.RUN_LIMITS
        field in setOf("triggerPercent", "resumePercent", "maxWallLevel", "maxWallsPerPhase") -> SettingsSection.WALLS
        field.startsWith("capacity_") || field.startsWith("goal_") -> SettingsSection.STORAGE_GOAL
        field.startsWith("minimum") || field in setOf("maxSkips", "maxSearchSeconds", "maxSearchGold") -> SettingsSection.OPPONENTS
        field.startsWith("end") || field.startsWith("lootLeft_") -> SettingsSection.ENDING
        field == "savedArmy" -> SettingsSection.ARMY
        else -> null
    }

    /** Puts each error under its field; a "form" rule goes under the field it names when it names one. */
    fun place(errors: Map<String, String>): PlacedErrors {
        val byField = linkedMapOf<String, String>()
        val top = mutableListOf<String>()
        for ((key, message) in errors) {
            val field = if (key == "form") fieldFor(message) else key
            val text = readable(message)
            if (field == null || sectionOf(field) == null) top += text else byField.putIfAbsent(field, text)
        }
        return PlacedErrors(byField, top)
    }

    /** The validation messages name dark elixir by its storage key. */
    private fun readable(message: String) = message.replace("dark_elixir", "dark elixir").replace("Dark_elixir", "Dark elixir")

    private fun shown(text: String) = text.trim().ifEmpty { Format.UNKNOWN }

    private fun typedAmounts(values: Map<Resource, String>) = Resource.entries.mapNotNull { r ->
        values[r]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            "${resourceName(r).lowercase(Locale.ROOT)} ${text.toLongOrNull()?.let { Format.amount(it) } ?: text}"
        }
    }

    /**
     * The caption under the resume box. Only a run on both accounts keeps the saved resume level
     * (RunPlan.combined); one-account runs set it to [Policy.TIMED_RUN_RESUME_PERCENT], and Walls
     * only buys what the balance covers without reading it.
     */
    val RESUME_NOTE = "Only runs on both accounts stop spending here. Runs on one account, and Walls only, " +
        "spend down to ${Policy.TIMED_RUN_RESUME_PERCENT}%."

    /** One line under a closed section's title, from the boxes as typed. */
    fun summaryOf(section: SettingsSection, f: SettingsFields): String = when (section) {
        SettingsSection.RUN_LIMITS -> {
            val minutes = f.maxMinutes.trim().toIntOrNull()?.takeIf { it > 0 }?.let { Format.minutes(it) } ?: "${shown(f.maxMinutes)} min"
            "${shown(f.attacks)} attacks, $minutes"
        }
        // No resume level: most runs spend down to 0% whatever it says (RESUME_NOTE).
        SettingsSection.WALLS -> if (!f.wallsEnabled) "Off"
            else "On: buy at ${shown(f.triggerPercent)}% storage, up to level ${shown(f.maxWallLevel)}"
        SettingsSection.STORAGE_GOAL -> {
            val goal = typedAmounts(f.goalTargets)
            val capacities = Resource.entries.count { !f.capacities[it].isNullOrBlank() }
            val goalText = if (goal.isEmpty()) "No goal"
                else "Goal: ${if (f.goalMatch == GoalMatch.ANY) "any" else "all"} of ${goal.joinToString(", ")}"
            "$goalText; ${Format.count(capacities, "capacity", "capacities")} set"
        }
        SettingsSection.OPPONENTS -> if (!f.opponentEnabled) "Off: every base is attacked"
            else {
                val minimums = typedAmounts(f.minimums) +
                    listOfNotNull(f.minimumGoldAndElixir.trim().takeIf { it.isNotEmpty() }?.let { "gold plus elixir ${it.toLongOrNull()?.let { v -> Format.amount(v) } ?: it}" })
                val skips = "up to ${shown(f.maxSkips)} skips"
                if (minimums.isEmpty()) "On: no minimum set, $skips" else "Skip bases under ${minimums.joinToString(", ")}, $skips"
            }
        SettingsSection.ENDING -> if (!f.earlyEndEnabled) "Off: battles run to the end"
            else {
                val triggers = listOfNotNull(
                    "at ${shown(f.endDamageMin)} to ${shown(f.endDamageMax)}% damage".takeIf { f.endAtDamage },
                    "${f.endAfterDeploySeconds.trim()} s after deploy".takeIf { f.endAfterDeploySeconds.isNotBlank() },
                    "with ${f.endBattleSecondsLeft.trim()} s left".takeIf { f.endBattleSecondsLeft.isNotBlank() },
                    "on loot left".takeIf { f.endLootLeftBelow.values.any { it.isNotBlank() } },
                    "when stalled".takeIf { f.endOnStall }
                )
                if (triggers.isEmpty()) "On, but no trigger is set" else "Surrender " + triggers.joinToString(", ")
            }
        SettingsSection.COLLECTION -> when {
            f.collectBeforeFarming && f.collectAfterFarming -> "Before and after farming"
            f.collectBeforeFarming -> "Before farming"
            f.collectAfterFarming -> "After farming"
            else -> "Off"
        }
        SettingsSection.ARMY -> if (f.savedArmy.isBlank()) "Off: the current army is used"
            else "Load ${f.savedArmy.trim()} before the first attack"
    }

    /** The amber note under the lowest damage box when it is under the league bonus floor. */
    fun damageFloorHint(minText: String): String? {
        val min = minText.trim().toIntOrNull() ?: return null
        return if (min < LEAGUE_BONUS_DAMAGE_FLOOR) "$DAMAGE_FLOOR_WARNING, so a run lifts it to $LEAGUE_BONUS_DAMAGE_FLOOR%." else null
    }
}
