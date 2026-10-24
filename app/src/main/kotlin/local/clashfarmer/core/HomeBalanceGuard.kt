package local.clashfarmer.core

/** A balance the guard held back or settled only after more frames, for `balance_implausible`. */
data class ImplausibleBalance(
    val resource: Resource,
    val readings: List<Long?>,
    val trusted: Long?,
    /** What the extra frames settled on, or null when the balance is left unread. */
    val accepted: Long?,
    /** Every mask of every frame read [accepted], which beat the prior. */
    val everyMask: Boolean = false
)

sealed interface BalanceSettlement {
    /** Read one more village frame and settle again with it added. */
    data object NeedFrame : BalanceSettlement

    /** Two samples for [agreedHomeBalances]; a balance left unread is absent from both. */
    data class Settled(val samples: List<ResourceSample>, val flagged: List<ImplausibleBalance>) : BalanceSettlement
}

/**
 * The last balances stored for the account, which a farming run's [HomeBalanceGuard] starts from.
 *
 * None for a wall-only run. It never attacks, so a balance held back against the prior could
 * never be confirmed and the run would buy nothing; the wall phase reads the bar itself anyway,
 * and a low misread only makes it buy less.
 */
fun homeBalancePrior(wallsOnly: Boolean, accountId: String?, storage: StorageStore?): Map<Resource, Long> =
    if (wallsOnly) emptyMap() else accountId?.let { storage?.latest(it)?.values }.orEmpty()

/**
 * Keeps a village balance that cannot be true out of the storage checks for one run.
 *
 * Two frames agreeing is not enough. At 08:16 UTC on 8 October Blood's gold read 2 403 171 in
 * both frames, three minutes after 23 429 258 and one attack later: the true 24 031 171 with the
 * pair of ones either side of the group gap read as one, the collapse [ScreenReader] documents
 * for 11 831 452. The screen did not change, so neither did the misread, and it read again at
 * 08:59. Storage-full, the wall trigger and the goal all saw a tenth of the gold.
 *
 * So a balance is also held against the last one this run trusted, allowing for what an attack
 * can bring in or cost and for anything a wall phase spent. One that falls outside is read on one
 * more frame, never trusted; two readings a factor of ten apart are read on up to two more, and
 * only a plausible value two frames agree on is taken. Anything else is left unread, which keeps
 * the storage-full and wall triggers off until the next checkpoint. Nothing is ever made up.
 *
 * At the run's start the [prior] - the last balance stored for the account, from an earlier run -
 * only rules out a factor of [PRIOR_FACTOR], because anything may have been spent between runs.
 * A balance that every mask read alike on every frame ([ScreenReader.everyMaskRead]) beats it:
 * the prior can be a misread or long out of date, and on 15 October Blood's elixir 387 427 at
 * 04:12 and its gold 262 165 at 05:01 were held for a whole run against 29 000 000 and 9 393 986.
 * Of the 63 balances held from 8 to 15 October, the 12 the masks read that way were all true by
 * the later reads. It never beats a balance this run trusted or a candidate: those come from the
 * run's own frames, and a misread of an unchanged screen repeats on every frame.
 *
 * A held-back balance becomes a candidate. If the trusted one was itself the misread, the true
 * value would otherwise be held back for the rest of the run, so a reading at or above the
 * candidate and consistent with it after at least one attack is taken.
 *
 * That can also take a misread that comes back after an attack, so a dark elixir balance taken
 * outside the reach of the one it replaced is a jump, and keeps that one. Three frames that every
 * mask read alike, on a value the balance before the jump allows, replace the jump. On 15 October
 * Blood's dark elixir went 291 790, 2 984 008, then 309 456 on all three frames through all nine
 * masks, which were dropped. Gold and elixir keep nothing: the gold a jump replaced can itself be
 * a misread, stored by an earlier run, and three frames of it again would bring it back over the
 * truth. Their truth comes back on a candidate one checkpoint later instead.
 *
 * A reading inside the jump's own reach keeps the balance before it, and a balance taken as a
 * jump or on a candidate vouches for nothing, so a jump away from it keeps nothing. Before both,
 * with 470 000 trusted, a misread taken on its candidate and read once more dropped the 470 000;
 * the truth then came back on a candidate as a jump that kept the misread, and three frames of
 * the misread brought it back.
 *
 * The balance a jump kept counts attacks only until the next balance is taken, so a return to it
 * is allowed only as far as it was then. Counting on, a stored misread 2 984 008 kept by a jump to
 * the truth allowed anything up to 6 184 008 sixty checkpoints later, and took 37 200 on three
 * frames, the truth 372 000 with a digit dropped. Three frames of the kept value itself are still
 * taken: the guard cannot tell which side of a jump was the misread.
 *
 * Every mask, because the kept balance can be the low side. Trusted as a dropped digit, 33 000 is
 * kept by the jump to the true 335 000, and its reach then holds a tenth of any balance the account
 * can have: the UO-19 round 2 review on 15 October traced 36 500 on three frames taken over
 * 360 000, and the same after 300 000 gained by hand in a pause. None of the misreads held from 8
 * to 15 October was read alike by every mask. A true return the masks did not all read comes back
 * on its candidate one checkpoint later.
 */
class HomeBalanceGuard(
    private val capacities: Map<Resource, Long>,
    private val prior: Map<Resource, Long> = emptyMap()
) {
    /**
     * A balance and how far it can move. [before] is the balance a jump replaced; it counts attacks
     * only while [jump], the balance taken as the jump, is the trusted one. [vouched] is false
     * when nothing held the balance from both sides - a run's first with nothing stored, one read
     * after a wall phase, when only a rise was ruled out, or one taken outside the reach of the
     * balance before it - so a jump from it is no reason to doubt what replaced it.
     */
    private class Anchor(
        val value: Long,
        val reach: Reach,
        val before: Anchor? = null,
        val vouched: Boolean = true,
        val jump: Boolean = false
    ) {
        var attacks = 0
        var spent = false

        fun allows(v: Long): Boolean =
            v <= value + attacks * reach.gain + reach.slack &&
                (spent || v >= value - attacks * reach.cost - reach.slack)
    }

    private class Reach(val gain: Long, val cost: Long, val slack: Long)

    private val trusted = mutableMapOf<Resource, Anchor>()
    private val candidates = mutableMapOf<Resource, Anchor>()

    private fun anchors(): Set<Anchor> =
        (trusted.values + candidates.values + trusted.values.filter { it.jump }.mapNotNull { it.before }).toSet()

    fun attackCompleted() {
        anchors().forEach { it.attacks++ }
    }

    /**
     * A wall phase ran: gold and elixir may now be lower. Walls never cost dark elixir, so its
     * floor stays, and a dropped digit after the phase is held like any other.
     */
    fun spendingPossible() {
        for (r in listOf(Resource.GOLD, Resource.ELIXIR)) {
            listOfNotNull(trusted[r], candidates[r], trusted[r]?.before).forEach { it.spent = true }
        }
    }

    /** [everyMask] holds, for each frame, the resources whose every mask read that frame's value. */
    fun settle(frames: List<ResourceSample>, everyMask: List<Set<Resource>> = emptyList()): BalanceSettlement {
        require(frames.size >= 2) { "a balance needs two frames" }
        val resources = frames.flatMap { it.values.keys }.toSet()
        val verdicts = resources.associateWith { r ->
            val readings = frames.map { it.values[r] }
            val alike = everyMask.size == frames.size && everyMask.all { r in it } && readings.distinct().size == 1
            decide(r, readings, alike)
        }
        if (frames.size < MAX_FRAMES && verdicts.values.any { it == Verdict.More }) {
            return BalanceSettlement.NeedFrame
        }

        val (first, second) = frames
        val firstValues = mutableMapOf<Resource, Long>()
        val secondValues = mutableMapOf<Resource, Long>()
        val flagged = mutableListOf<ImplausibleBalance>()
        for ((r, verdict) in verdicts) {
            val readings = frames.map { it.values[r] }
            when (verdict) {
                Verdict.Plain -> {
                    first.values[r]?.let { firstValues[r] = it }
                    second.values[r]?.let { secondValues[r] = it }
                    val v = first.values[r]
                    if (v != null && v == second.values[r] && fits(r, v)) trust(r, v)
                }
                is Verdict.Accept -> {
                    firstValues[r] = verdict.value
                    secondValues[r] = verdict.value
                    flagged += ImplausibleBalance(r, readings, trusted[r]?.value, verdict.value, verdict.everyMask)
                    trust(r, verdict.value)
                }
                Verdict.More, Verdict.Unreadable -> {
                    flagged += ImplausibleBalance(r, readings, trusted[r]?.value, null)
                    readings.filterNotNull().groupingBy { it }.eachCount()
                        .filter { (v, n) -> n >= 2 && fits(r, v) && !plausible(r, v) }
                        .keys.lastOrNull()
                        ?.let { candidates[r] = Anchor(it, reach(r)) }
                }
            }
        }
        return BalanceSettlement.Settled(
            listOf(ResourceSample(first.capturedAtMs, firstValues), ResourceSample(second.capturedAtMs, secondValues)),
            flagged
        )
    }

    private sealed interface Verdict {
        /** The two frames stand as read; [agreedHomeBalances] decides as it always has. */
        data object Plain : Verdict
        data object More : Verdict
        data class Accept(val value: Long, val everyMask: Boolean = false) : Verdict
        data object Unreadable : Verdict
    }

    /** [everyMask]: every frame read one value through every mask ([ScreenReader.everyMaskRead]). */
    private fun decide(r: Resource, readings: List<Long?>, everyMask: Boolean): Verdict {
        val (a, b) = readings
        val held = when {
            a != null && a == b -> !plausible(r, a)
            a != null && b != null -> tenfold(a, b)
            else -> false
        }
        if (!held) return Verdict.Plain
        // Only the prior held it: nothing is trusted and nothing is a candidate.
        if (everyMask && a != null && fits(r, a) && trusted[r] == null && r !in candidates) {
            return Verdict.Accept(a, everyMask = true)
        }

        val agreed = readings.filterNotNull().groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
        val good = agreed.filter { fits(r, it) && plausible(r, it) }
        if (good.size == 1) return Verdict.Accept(good.single())
        val run = readings.last()
        if (everyMask && readings.size >= JUMP_RUN && run != null && readings.all { it == run } &&
            fits(r, run) && trusted[r]?.before?.allows(run) == true
        ) return Verdict.Accept(run)
        if (readings.size >= MAX_FRAMES) return Verdict.Unreadable
        // An implausible pair gets one more frame. Only a different reading there earns the last.
        if (readings.size == 3 && a == b && (readings[2] == null || readings[2] == a)) return Verdict.Unreadable
        return Verdict.More
    }

    private fun plausible(r: Resource, v: Long): Boolean {
        if (byCandidate(r, v)) return true
        trusted[r]?.let { return it.allows(v) }
        return byPrior(r, v)
    }

    // Only at or above the held value: on 5 October Sweat's held misread 100 089 would otherwise
    // have vouched for the next misread, 5 010.
    private fun byCandidate(r: Resource, v: Long): Boolean =
        candidates[r]?.let { it.attacks > 0 && v >= it.value && it.allows(v) } == true

    private fun byPrior(r: Resource, v: Long): Boolean {
        val p = prior[r] ?: return true
        return p == 0L || (v * PRIOR_FACTOR > p && p * PRIOR_FACTOR > v)
    }

    private fun fits(r: Resource, v: Long): Boolean = v >= 0L && (capacities[r]?.let { v <= it } ?: true)

    /**
     * A dark elixir balance outside the reach of a vouched one is a jump and keeps it, unless the
     * balance before that one allows it: then it is a return from a jump, not another. One inside
     * the reach keeps whatever that one kept. One taken outside it, or with nothing trusted on a
     * candidate the prior refused, vouches for nothing.
     */
    private fun trust(r: Resource, v: Long) {
        val old = trusted[r]
        val outside = if (old != null) !old.allows(v) && old.before?.allows(v) != true
        else !byPrior(r, v) && byCandidate(r, v)
        val jump = r == Resource.DARK_ELIXIR && old != null && old.vouched && outside
        val before = if (jump) old else old?.takeIf { it.allows(v) }?.before
        val vouched = !outside && (if (old != null) !old.spent else r in prior)
        trusted[r] = Anchor(v, reach(r), before = before, vouched = vouched, jump = jump)
        candidates.remove(r)
    }

    private fun reach(r: Resource): Reach = if (r == Resource.DARK_ELIXIR) DARK_ELIXIR_REACH else REACH

    private companion object {
        /** Two frames, then at most two more. */
        const val MAX_FRAMES = 4

        /** Frames that must all agree, through every mask, before they replace a jump. */
        const val JUMP_RUN = 3

        /**
         * Gold and elixir. What one attack can bring in is loot plus the league bonus: the most
         * either account took on 8 October was 1 823 373 gold, and the bonus added about 0.34M,
         * so the gain is near twice that. What one attack can cost is the army and spells
         * retrained, and the scouting fees. The slack is collection, and anything else small
         * between two reads.
         */
        val REACH = Reach(gain = 4_000_000L, cost = 2_000_000L, slack = 500_000L)

        /**
         * Dark elixir is counted in thousands, not millions. The most a battle took by 15 October
         * was 22 751, the most a storage read rose across one attack was 14 339, and Blood's fell
         * by 93 923 across six attacks. With gold's reach, 291 790 allowed 2 984 008.
         */
        val DARK_ELIXIR_REACH = Reach(gain = 50_000L, cost = 50_000L, slack = 50_000L)

        /** Readings this many times apart are a dropped or doubled digit, not a change. */
        const val PRIOR_FACTOR = 5L

        fun tenfold(a: Long, b: Long): Boolean = maxOf(a, b) >= PRIOR_FACTOR * minOf(a, b)
    }
}
