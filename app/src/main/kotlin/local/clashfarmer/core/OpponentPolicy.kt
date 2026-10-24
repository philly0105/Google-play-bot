package local.clashfarmer.core

data class OpponentRules(
    val enabled: Boolean = false,
    val minimums: Map<Resource, Long> = emptyMap(),
    val minimumGoldAndElixir: Long? = null,
    val maxSkips: Int = 10,
    val maxSearchMs: Long = 90_000,
    val maxSearchGold: Long = 0
)

data class SearchProgress(val skips: Int, val elapsedMs: Long, val spentGold: Long)

enum class ScoutDecision { ATTACK, SKIP, REREAD, ATTACK_FALLBACK }

/** Fewer seconds than this on the scout countdown and a Next press may land in the battle. */
const val MIN_SECONDS_FOR_NEXT = 8

/**
 * What to do with the base on screen. Unread loot is unknown, never zero: a known shortfall
 * still skips, but a base that could pass on an unread value earns one reread. Any budget,
 * fee or timer doubt deploys the army rather than pressing Next.
 */
fun chooseOpponent(
    rules: OpponentRules,
    loot: Map<Resource, Long>,
    progress: SearchProgress,
    nextFee: Long?,
    secondsRemaining: Int?,
    rereadUsed: Boolean
): ScoutDecision {
    if (!rules.enabled) return ScoutDecision.ATTACK
    val verdict = lootVerdict(rules, loot) ?: run {
        return if (!canSkip(rules, progress, nextFee, secondsRemaining)) ScoutDecision.ATTACK_FALLBACK
        else if (!rereadUsed) ScoutDecision.REREAD
        else ScoutDecision.ATTACK_FALLBACK
    }
    if (verdict) return ScoutDecision.ATTACK
    return if (canSkip(rules, progress, nextFee, secondsRemaining)) ScoutDecision.SKIP
    else ScoutDecision.ATTACK_FALLBACK
}

/** True when every rule passes, false on any known shortfall, null when unread values decide. */
private fun lootVerdict(rules: OpponentRules, loot: Map<Resource, Long>): Boolean? {
    var unknown = false
    for ((resource, minimum) in rules.minimums) {
        if (minimum <= 0L) continue
        val value = loot[resource]
        if (value == null) unknown = true else if (value < minimum) return false
    }
    val combined = rules.minimumGoldAndElixir
    if (combined != null && combined > 0L) {
        val gold = loot[Resource.GOLD]
        val elixir = loot[Resource.ELIXIR]
        if (gold == null || elixir == null) unknown = true
        else if (saturatingSum(gold, elixir) < combined) return false
    }
    return if (unknown) null else true
}

private fun saturatingSum(a: Long, b: Long): Long =
    if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

private fun canSkip(rules: OpponentRules, progress: SearchProgress, fee: Long?, seconds: Int?): Boolean =
    progress.skips < rules.maxSkips &&
        progress.elapsedMs < rules.maxSearchMs &&
        fee != null && fee >= 0L &&
        progress.spentGold <= rules.maxSearchGold - fee &&
        seconds != null && seconds >= MIN_SECONDS_FOR_NEXT
