package local.clashfarmer.core

import local.clashfarmer.log.AppLog

/**
 * When to surrender a battle rather than wait out its three minutes. Every trigger is off at 0 or
 * when empty, and they are all off by default: the battle then runs to its own end.
 */
data class EarlyEndRules(
    val enabled: Boolean = false,
    /** Surrender this many seconds after the last troop went down. */
    val afterDeploySeconds: Int = 0,
    /** Surrender once the battle clock shows this many seconds or fewer. */
    val battleSecondsLeft: Int = 0,
    /**
     * Surrender once every listed resource still on the base is below its amount. Read from the
     * scout's loot rows, which the battle keeps in the same place, so it needs them calibrated.
     */
    val lootLeftBelow: Map<Resource, Long> = emptyMap(),
    /**
     * Surrender once the overall damage reaches a percent picked for each battle from
     * [damageMin] to [damageMax], so the battles do not all stop at the same number.
     */
    val atDamage: Boolean = false,
    val damageMin: Int = DEFAULT_DAMAGE_MIN,
    val damageMax: Int = DEFAULT_DAMAGE_MAX,
    /**
     * Surrender once the battle's stall detector has decided nothing is moving. Experimental
     * and off by default: it ships off until the shadow logs from real runs have been read,
     * and a detector that has not decided never fires it.
     */
    val onStall: Boolean = false,
    /**
     * Log what a stall rule would have done during the battle's waits. On by default - it
     * only writes to the session log - and not a trigger: it stays out of [anyTrigger] and
     * runs whether or not [enabled] is set. When [onStall] is on the same decision also arms
     * the surrender, so the log and the trigger can be compared line for line.
     */
    val stallShadow: Boolean = true
) {
    val anyTrigger: Boolean get() =
        afterDeploySeconds > 0 || battleSecondsLeft > 0 || lootLeftBelow.any { it.value > 0L } || atDamage || onStall
}

enum class EarlyEndReason(val key: String) {
    AFTER_DEPLOY("after_deploy"),
    BATTLE_TIMER("battle_timer"),
    LOOT_LEFT("loot_left"),
    DAMAGE("damage"),
    STALL("stall")
}

/** The overall damage range a battle is surrendered in under [EarlyEndRules.atDamage], unless set. */
const val DEFAULT_DAMAGE_MIN = 70
const val DEFAULT_DAMAGE_MAX = 70

/**
 * The league bonus pays min(100, damage + 30) percent, measured across about 120 result frames,
 * so 70% damage is the lowest that keeps all of it. No damage surrender fires below this.
 */
const val LEAGUE_BONUS_DAMAGE_FLOOR = 70
const val DAMAGE_FLOOR_WARNING = "Surrendering under 70% damage loses part of the league bonus"

/**
 * This battle's damage target, from [EarlyEndRules.damageMin] to [EarlyEndRules.damageMax] lifted
 * to [LEAGUE_BONUS_DAMAGE_FLOOR]. A saved range below the floor is left as saved; the lift is
 * reported through [onFloor] whenever the damage trigger is in use.
 */
fun pickDamageTarget(
    rules: EarlyEndRules,
    onFloor: (savedMin: Int, savedMax: Int, target: Int) -> Unit = { savedMin, savedMax, target ->
        AppLog.log("early_end_damage_floor", "damageMin" to savedMin, "damageMax" to savedMax,
            "target" to target, "floor" to LEAGUE_BONUS_DAMAGE_FLOOR, "message" to DAMAGE_FLOOR_WARNING)
    }
): Int {
    val min = maxOf(rules.damageMin, LEAGUE_BONUS_DAMAGE_FLOOR)
    val target = (min..maxOf(rules.damageMax, min)).random()
    if (rules.damageMin < LEAGUE_BONUS_DAMAGE_FLOOR && rules.enabled && rules.atDamage) {
        onFloor(rules.damageMin, rules.damageMax, target)
    }
    return target
}

/**
 * Holds back a battle's damage readings from early end until one proves the damage is past 9.
 *
 * The sign can read as a 9 after a single digit: on 22 September 7% came back as "79%", and 8%
 * as "89%" on four frames over 24 s, so two agreeing reads would not have caught it. A true value
 * from 10 up turns that misread into three digits, which [local.clashfarmer.recognition.BattleDamage]
 * reads back to their first two. So once a reading from 10 to 100 that does not end in 9 has been
 * seen, every later reading is let through. A battle that never shows one runs to its normal end.
 */
class DamageProof {
    private var proven = false

    /** Called at each deploy, next to the battle's damage target. */
    fun reset() {
        proven = false
    }

    /** [read], or null while the battle's damage is not yet proven past 9. */
    fun admit(read: Int?): Int? {
        if (read != null && read in 10..100 && read % 10 != 9) proven = true
        return read?.takeIf { proven }
    }
}

/**
 * The loot panel counts down as buildings fall, but a panel read in the first seconds still shows
 * what the scout did; and a base that was poor to begin with would be surrendered before a single
 * building fell. The loot trigger waits this long after the deploy.
 */
const val MIN_MS_BEFORE_LOOT_END = 15_000L

/**
 * Why the battle on screen should be surrendered now, or null to keep waiting. Unread values
 * decide nothing: an unread clock or loot row never ends a battle. When several triggers hold
 * at once the first in the order below is named: a damage already at its target is the
 * achievement the others only approximate; then a stall, because a measured window of
 * stillness says more about this battle than the countdowns and thresholds that follow it.
 */
fun earlyEndReason(
    rules: EarlyEndRules,
    sinceDeployMs: Long,
    secondsLeft: Int?,
    lootLeft: Map<Resource, Long>,
    damagePercent: Int? = null,
    damageTarget: Int = maxOf(rules.damageMin, LEAGUE_BONUS_DAMAGE_FLOOR),
    stalled: Boolean = false
): EarlyEndReason? {
    if (!rules.enabled) return null
    if (rules.atDamage && damagePercent != null && damagePercent >= damageTarget) {
        return EarlyEndReason.DAMAGE
    }
    if (rules.onStall && stalled) {
        return EarlyEndReason.STALL
    }
    if (rules.afterDeploySeconds > 0 && sinceDeployMs >= rules.afterDeploySeconds * 1000L) {
        return EarlyEndReason.AFTER_DEPLOY
    }
    if (rules.battleSecondsLeft > 0 && secondsLeft != null && secondsLeft <= rules.battleSecondsLeft) {
        return EarlyEndReason.BATTLE_TIMER
    }
    val thresholds = rules.lootLeftBelow.filterValues { it > 0L }
    if (thresholds.isNotEmpty() && sinceDeployMs >= MIN_MS_BEFORE_LOOT_END &&
        thresholds.all { (resource, below) -> lootLeft[resource]?.let { it < below } == true }
    ) {
        return EarlyEndReason.LOOT_LEFT
    }
    return null
}
