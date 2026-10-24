package local.clashfarmer.core

import kotlin.random.Random

/**
 * How long the village sits at home before the next attack. Attacks went out back to back, one
 * every 72 seconds on average over a two-hour run, which is a rhythm nobody playing keeps. Most
 * waits are short; now and then one is a proper break.
 */
object AttackPacing {
    const val SHORT_MIN_MS = 5_000L
    const val SHORT_MAX_MS = 30_000L
    const val BREAK_MIN_MS = 60_000L
    const val BREAK_MAX_MS = 180_000L
    /** One wait in this many is a break. */
    const val BREAK_ONE_IN = 8

    fun nextWaitMs(rng: Random = Random.Default): Long =
        if (rng.nextInt(BREAK_ONE_IN) == 0) rng.nextLong(BREAK_MIN_MS, BREAK_MAX_MS + 1)
        else rng.nextLong(SHORT_MIN_MS, SHORT_MAX_MS + 1)

    /**
     * Troops went down 30 ms apart after a fixed 100 ms card select. With each tap's own 50 ms
     * press that is one drop every 80 ms, about twelve a second in the same rhythm every battle.
     * These gaps come on top of the press: five to eight a second, never evenly.
     */
    const val SELECT_MIN_MS = 150L
    const val SELECT_MAX_MS = 350L
    const val DROP_GAP_MIN_MS = 70L
    const val DROP_GAP_MAX_MS = 160L

    fun selectWaitMs(rng: Random = Random.Default): Long = rng.nextLong(SELECT_MIN_MS, SELECT_MAX_MS + 1)
    fun dropGapMs(rng: Random = Random.Default): Long = rng.nextLong(DROP_GAP_MIN_MS, DROP_GAP_MAX_MS + 1)
}
