package local.clashfarmer.core

import local.clashfarmer.log.AppLog

/** What one wall phase came to. Nothing but [Bought] means a wall was raised. */
sealed interface WallPhaseOutcome {
    /** [count] walls were bought in one confirmation for [total]; the spend and the next level were proven. */
    data class Bought(val currency: Resource, val count: Int, val total: Long) : WallPhaseOutcome

    /** The whole list was read and no Wall row was both affordable and uncapped. */
    data object Empty : WallPhaseOutcome

    /**
     * The whole list was read and it has no Wall row at all: every wall is at the top level the
     * game offers, so neither currency has anything to buy.
     */
    data object NoWalls : WallPhaseOutcome

    /** The selected wall is at or above the level cap; [listPrice] is its row's price in the list. */
    data class Capped(val listPrice: Long) : WallPhaseOutcome

    /**
     * No builder was free, so the game will not take a wall upgrade. Nothing was spent and no
     * marker is left; it is not a failure.
     */
    data object NoBuilder : WallPhaseOutcome

    /**
     * A confirmation opened "All builders are busy!", which was closed with nothing spent. The top
     * bar's read missed it once, so walls stay off for the rest of the run rather than ten minutes:
     * every retry puts the 212-gem button on screen again.
     */
    data object BuildersBusy : WallPhaseOutcome

    /** Nothing was spent and nothing was concluded about walls. */
    data class Failed(val reason: WallPhaseFailure, val detail: String) : WallPhaseOutcome
}

enum class WallPhaseFailure {
    INVALID_ORDER,
    PENDING_PURCHASE,
    LIST_FAILED,
    PANEL_UNREADABLE,
    PRICE_CHANGED,
    PRICE_UNKNOWN,
    MULTIPLE_SELECTION,
    WRONG_CURRENCY,
    NOT_PAYABLE,
    BALANCE_UNREAD,
    NOTHING_PLANNED,
    SELECTION_REFUSED,
    CONFIRMATION_REFUSED,
    MARKER_WRITE_FAILED,
    PAUSED,
    ERROR
}

/** What a tracker holds, for the caller to persist across an auto-restart. */
data class WallPhaseTrackerState(
    val suppressed: Set<Resource> = emptySet(),
    val failures: Map<Resource, Int> = emptyMap(),
    val retryAtMs: Map<Resource, Long> = emptyMap(),
    val cappedPrices: Set<Long> = emptySet(),
    /** Until when no builder is taken to be free; null when none was found busy. */
    val noFreeBuilderUntilMs: Long? = null,
    /** Why each currency's latest phase failed, until a later phase of it concludes something. */
    val lastFailures: Map<Resource, WallPhaseFailure> = emptyMap(),
    /** Whether a confirmation met the busy dialog in this run; walls stay off until it ends. */
    val buildersBusy: Boolean = false,
    /** Whether a phase read the whole list and found no Wall row: every wall is maxed. */
    val noWalls: Boolean = false
)

/**
 * Which currencies may run another automatic wall phase in this run.
 *
 * An empty phase, or a capped one, suppresses its currency for the rest of the run: storage near
 * capacity that found nothing affordable will not find anything after more loot. A list with no
 * Wall row at all suppresses both currencies. A failed phase
 * (nothing sent, nothing concluded) gets one retry at a HOME checkpoint at least ten minutes
 * later; a second failure suppresses. A pending purchase or an invalid order suppress at once.
 * No free builder bars every currency for the same ten minutes and counts as no failure; the
 * busy dialog after a confirmation bars every currency for the rest of the run.
 * Failures are never reset by a success. Time is passed in; nothing here reads a clock.
 */
class WallPhaseTracker(initial: WallPhaseTrackerState = WallPhaseTrackerState()) {
    private val suppressed = initial.suppressed.toMutableSet()
    private val failures = initial.failures.toMutableMap()
    private val retryAt = initial.retryAtMs.toMutableMap()
    private val capped = initial.cappedPrices.toMutableSet()
    private var noBuilderUntil = initial.noFreeBuilderUntilMs
    private val lastFailures = initial.lastFailures.toMutableMap()
    private var buildersBusy = initial.buildersBusy
    private var noWalls = initial.noWalls

    val cappedPrices: Set<Long> get() = capped.toSet()

    /** A phase in this run found no Wall row in the list, so neither currency has a wall to buy. */
    fun noWallsLeft(): Boolean = noWalls

    /** Why each currency's latest phase failed; a currency whose latest phase concluded is absent. */
    fun lastFailures(): Map<Resource, WallPhaseFailure> = lastFailures.toMap()

    /**
     * A phase found no builder free within the last ten minutes, or a confirmation met the busy
     * dialog in this run; every wall upgrade needs one, so no currency runs.
     */
    fun noFreeBuilder(nowMs: Long): Boolean = buildersBusy || noBuilderUntil?.let { nowMs < it } == true

    fun suppressed(currency: Resource): Boolean = currency in suppressed

    fun canRun(currency: Resource, nowMs: Long): Boolean {
        if (noFreeBuilder(nowMs) || currency in suppressed) return false
        val at = retryAt[currency] ?: return true
        return nowMs >= at
    }

    fun record(currency: Resource, outcome: WallPhaseOutcome, nowMs: Long) {
        when (outcome) {
            is WallPhaseOutcome.Failed -> lastFailures[currency] = outcome.reason
            WallPhaseOutcome.NoBuilder, WallPhaseOutcome.BuildersBusy -> Unit
            // Whatever the other currency's last phase failed on, neither has a wall to buy.
            WallPhaseOutcome.NoWalls -> WALL_CURRENCIES.forEach { lastFailures.remove(it) }
            else -> lastFailures.remove(currency)
        }
        when (outcome) {
            is WallPhaseOutcome.Bought -> retryAt.remove(currency)
            WallPhaseOutcome.Empty -> { retryAt.remove(currency); suppressed += currency }
            WallPhaseOutcome.NoWalls -> { WALL_CURRENCIES.forEach { retryAt.remove(it); suppressed += it }; noWalls = true }
            is WallPhaseOutcome.Capped -> { retryAt.remove(currency); capped += outcome.listPrice; suppressed += currency }
            WallPhaseOutcome.NoBuilder -> noBuilderUntil = nowMs + RETRY_AFTER_MS
            WallPhaseOutcome.BuildersBusy -> if (!buildersBusy) {
                buildersBusy = true
                AppLog.log("walls_off_builders_busy", "currency" to currency.key)
            }
            is WallPhaseOutcome.Failed -> {
                val hard = outcome.reason == WallPhaseFailure.PENDING_PURCHASE ||
                    outcome.reason == WallPhaseFailure.INVALID_ORDER
                val count = (failures[currency] ?: 0) + 1
                failures[currency] = count
                if (hard || count >= 2) {
                    suppressed += currency
                    retryAt.remove(currency)
                } else {
                    retryAt[currency] = nowMs + RETRY_AFTER_MS
                }
            }
        }
    }

    fun state() = WallPhaseTrackerState(suppressed.toSet(), failures.toMap(), retryAt.toMap(), capped.toSet(), noBuilderUntil,
        lastFailures.toMap(), buildersBusy, noWalls)

    companion object {
        const val RETRY_AFTER_MS = 10 * 60_000L

        /** What walls are bought with. */
        private val WALL_CURRENCIES = listOf(Resource.GOLD, Resource.ELIXIR)
    }
}
