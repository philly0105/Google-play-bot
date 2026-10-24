package local.clashfarmer.core

import local.clashfarmer.recognition.ImageMatcher
import kotlin.math.abs

/** One card off one frame: its saturation, and for a spell the count its badge read, if any. */
data class CardReading(val saturation: Double, val badge: Int? = null)

/**
 * Whether a card that still reads stocked is dropped again.
 *
 * A card greys a moment after its last troop lands, and the saturation of its slot is the
 * proof it did. Troops retry while attempts last, because a refused drop leaves the whole
 * card stocked. A hero never retries: once out, its card stays coloured while the ability is
 * ready, and a tap on it fires the ability. Between 6 and 8 October that re-tapped all four
 * heroes in 54 of 56 battles. A hero's own fallback drops are its only second chance.
 * A hero card is not even read: on 8 October all four landed and read 100-137, so no reading
 * of a landed hero's card tells it from one still in the tray.
 * A spell lands anywhere - buildings included - so a spell card still
 * reading stocked after its drops is usually not a refused drop at all but a slot that does
 * not measure the card: the Jump card read 120.98 before and after it was spent, battle after
 * battle. A reading that did not move across the drops is no evidence of stock left, so a
 * spell is retried at most once, and only when its reading actually moved. A spell card with
 * stock left after the cast never greys at all (Blood's x11 card), so a spell also counts as
 * spent when its "xNN" badge reads lower than it did before the drops.
 */
object CardRetry {

    /** Reads per card, the first included. Troops keep all of them. */
    const val MAX_ATTEMPTS = 4

    /**
     * How far a spell card's saturation must move across its drops before the reading is
     * believed. Two reads of a static frame differ by a couple of points; a card that spent
     * anything moves by tens.
     */
    const val SPELL_SATURATION_CHANGE = 10.0

    /**
     * True when the card may be dropped again after the [attempt]th read left it looking
     * unspent at [after], against the [baseline] taken while the card was still stocked.
     */
    fun allowsRetry(group: DeploymentGroup, baseline: Double?, after: Double, attempt: Int): Boolean =
        if (group.isHero) {
            false
        } else if (!group.isSpell) {
            attempt < MAX_ATTEMPTS
        } else {
            attempt == 1 && baseline != null && abs(baseline - after) >= SPELL_SATURATION_CHANGE
        }

    /**
     * Whether [group]'s card is proven spent by [reading]: greyed out, or, for a spell, a badge
     * count below the one read at [baseline] before its drops. A troop card's lower count is a
     * partial drop, not a spent card.
     */
    fun spent(group: DeploymentGroup, reading: CardReading, baseline: CardReading?): Boolean {
        if (reading.saturation <= ImageMatcher.SPENT_CARD_SATURATION_THRESHOLD) return true
        if (!group.isSpell) return false
        val before = baseline?.badge ?: return false
        val after = reading.badge ?: return false
        return after < before
    }

    /**
     * Whether a read after the drops OCRs [group]'s badge. Only a spell whose pre-drop read found
     * a badge: [spent] has nothing to compare an after-badge with otherwise, and each badge read
     * costs four OCR passes (Blood's rages ROI sits on a card with no badge).
     */
    fun readsBadge(group: DeploymentGroup, baseline: CardReading?): Boolean =
        group.isSpell && baseline?.badge != null

    /**
     * Reads [groups]' cards until each is spent or no longer allowed a retry, handing the ones
     * still stocked to [retry] between reads. Hero cards are skipped: nothing on them shows
     * whether the hero landed. [read] returns each pending card's reading off one frame;
     * [giveUp] hears every card left stocked without a retry, with its reading and the attempt;
     * [onSpent] hears every card proven spent. Returns whether it read the screen at all.
     */
    inline fun verify(
        groups: List<DeploymentGroup>,
        baselines: Map<String, CardReading>,
        read: (List<DeploymentGroup>) -> Map<String, CardReading>,
        giveUp: (DeploymentGroup, CardReading, Int) -> Unit,
        retry: (List<DeploymentGroup>) -> Unit,
        onSpent: (DeploymentGroup, CardReading) -> Unit = { _, _ -> }
    ): Boolean {
        var pending = groups.filter { !it.isHero }
        if (pending.isEmpty()) return false
        for (attempt in 1..MAX_ATTEMPTS) {
            val readings = read(pending)
            val (done, stocked) = pending.partition { spent(it, readings.getValue(it.name), baselines[it.name]) }
            for (group in done) onSpent(group, readings.getValue(group.name))
            pending = stocked
            if (pending.isEmpty()) return true

            val retryable = pending.filter {
                allowsRetry(it, baselines[it.name]?.saturation, readings.getValue(it.name).saturation, attempt)
            }
            for (group in pending - retryable.toSet()) {
                giveUp(group, readings.getValue(group.name), attempt)
            }
            pending = retryable
            if (pending.isEmpty()) return true
            retry(pending)
        }
        return true
    }
}
