package local.clashfarmer.core

/**
 * One deliberate ability tap per landed hero, [HERO_ABILITY_DELAY_MS] after that hero's drops.
 *
 * Once a hero is out, its card is the ability button. Until 8 October the card-check retry
 * re-tapped every hero card about four seconds after its drops in 54 of 56 battles, firing all
 * four abilities by accident; that is what the proven loot rate was measured with. The retry no
 * longer touches heroes, so the ability is fired here on purpose, on the same timing, and only
 * once: a second tap on the card would do nothing useful and a mistimed one wastes it.
 *
 * A hero whose drop taps never all went out gets no tap, because its card may still be stocked
 * and a tap would only select it. The clock, waits, banner clearing and taps are handed in so
 * the schedule is testable off the device. Use one instance per deployment.
 */
class HeroAbilities {
    companion object {
        /**
         * From the end of a hero's drop taps to its ability tap. The removed retry tapped each
         * hero's card after the three later heroes' drops (~2.5 s), a 400 ms settle and a ~1.1 s
         * card read: about 4 s for every hero, per the hero phase's ~9.8 s in `deploy_timing`.
         */
        const val HERO_ABILITY_DELAY_MS = 4_000L
    }

    private data class Drop(val selectPoint: Point, val doneAtMs: Long)

    private val dropped = LinkedHashMap<String, Drop>()
    private val used = mutableSetOf<String>()

    /** Records that [group]'s drop taps all went out at [atMs]. Ignores anything but a hero. */
    fun dropped(group: DeploymentGroup, atMs: Long) {
        if (group.isHero) dropped.putIfAbsent(group.name, Drop(group.selectPoint, atMs))
    }

    /**
     * Taps each dropped hero's card once, in drop order, no sooner than its delay. A reward
     * banner is cleared before the first tap, because it takes taps meant for the battle. A hero
     * counts as used before its tap goes out, so a tap that throws is never repeated.
     * [tap] gets the hero, its card and how long after its drops the tap went out.
     */
    suspend fun fire(
        now: () -> Long,
        wait: suspend (Long) -> Unit,
        clearBanner: suspend () -> Unit,
        tap: suspend (String, Point, Long) -> Unit
    ) {
        val due = dropped.filterKeys { it !in used }
        if (due.isEmpty()) return
        var bannerCleared = false
        for ((hero, drop) in due) {
            val at = drop.doneAtMs + HERO_ABILITY_DELAY_MS
            val left = at - now()
            if (left > 0) wait(left)
            if (!bannerCleared) {
                clearBanner()
                bannerCleared = true
            }
            used += hero
            tap(hero, drop.selectPoint, now() - drop.doneAtMs)
        }
    }
}
