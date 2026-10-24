package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallPhaseTrackerTest {

    private val minute = 60_000L
    private val gold = Resource.GOLD
    private val elixir = Resource.ELIXIR

    private fun failed(reason: WallPhaseFailure = WallPhaseFailure.LIST_FAILED) = WallPhaseOutcome.Failed(reason, "x")

    @Test
    fun aFreshTrackerAllowsEveryCurrency() {
        val t = WallPhaseTracker()
        assertTrue(t.canRun(gold, 0))
        assertTrue(t.canRun(elixir, 0))
        assertTrue(t.cappedPrices.isEmpty())
    }

    @Test
    fun anEmptyPhaseSuppressesThatCurrencyOnly() {
        val t = WallPhaseTracker()
        t.record(gold, WallPhaseOutcome.Empty, 0)
        assertFalse(t.canRun(gold, 10 * 60 * minute))
        assertTrue(t.suppressed(gold))
        assertTrue(t.canRun(elixir, 0))
    }

    @Test
    fun aListWithNoWallRowSuppressesBothCurrencies() {
        val t = WallPhaseTracker()
        t.record(gold, WallPhaseOutcome.NoWalls, 0)
        assertFalse(t.canRun(gold, 10 * 60 * minute))
        assertFalse(t.canRun(elixir, 10 * 60 * minute))
        assertTrue(t.suppressed(elixir))
    }

    @Test
    fun aListWithNoWallRowLeavesNoFailureBehindForEitherCurrency() {
        val t = WallPhaseTracker()
        t.record(gold, WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "no list"), 0)
        t.record(elixir, WallPhaseOutcome.NoWalls, minute)
        assertEquals(emptyMap<Resource, WallPhaseFailure>(), t.lastFailures())
    }

    @Test
    fun aListWithNoWallRowIsRememberedAcrossARestart() {
        val t = WallPhaseTracker()
        assertFalse(t.noWallsLeft())
        t.record(gold, WallPhaseOutcome.Empty, 0)
        assertFalse("nothing affordable is not every wall maxed", t.noWallsLeft())
        t.record(elixir, WallPhaseOutcome.NoWalls, minute)
        assertTrue(t.noWallsLeft())
        assertTrue(WallPhaseTracker(t.state()).noWallsLeft())
    }

    @Test
    fun aCappedPhaseRemembersThePriceAndSuppressesTheCurrency() {
        val t = WallPhaseTracker()
        t.record(gold, WallPhaseOutcome.Capped(5_600_000L), 0)
        assertEquals(setOf(5_600_000L), t.cappedPrices)
        assertTrue(t.suppressed(gold))
        assertTrue(t.canRun(elixir, 0))
    }

    @Test
    fun aBoughtPhaseSuppressesNothing() {
        val t = WallPhaseTracker()
        t.record(gold, WallPhaseOutcome.Bought(gold, 12, 48_000_000L), 0)
        assertTrue(t.canRun(gold, 0))
    }

    @Test
    fun aFailedPhaseRetriesOnceAfterTenMinutes() {
        val t = WallPhaseTracker()
        t.record(gold, failed(), 1_000)
        assertFalse(t.canRun(gold, 1_000 + 10 * minute - 1))
        assertFalse(t.suppressed(gold))
        assertTrue(t.canRun(gold, 1_000 + 10 * minute))
    }

    @Test
    fun aSecondFailureSuppressesTheCurrency() {
        val t = WallPhaseTracker()
        t.record(gold, failed(), 0)
        t.record(gold, failed(), 11 * minute)
        assertTrue(t.suppressed(gold))
        assertFalse(t.canRun(gold, 100 * minute))
    }

    @Test
    fun aSuccessBetweenTwoFailuresDoesNotGiveAThirdChance() {
        val t = WallPhaseTracker()
        t.record(gold, failed(), 0)
        t.record(gold, WallPhaseOutcome.Bought(gold, 1, 5L), 11 * minute)
        t.record(gold, failed(), 12 * minute)
        assertTrue(t.suppressed(gold))
    }

    @Test
    fun aPendingPurchaseOrAnInvalidOrderSuppressesAtOnce() {
        val t = WallPhaseTracker()
        t.record(gold, failed(WallPhaseFailure.PENDING_PURCHASE), 0)
        t.record(elixir, failed(WallPhaseFailure.INVALID_ORDER), 0)
        assertTrue(t.suppressed(gold))
        assertTrue(t.suppressed(elixir))
    }

    @Test
    fun theRetryClockIsPerCurrency() {
        val t = WallPhaseTracker()
        t.record(gold, failed(), 0)
        assertTrue(t.canRun(elixir, 0))
    }

    @Test
    fun stateRoundTripsSoTheRunnerCanPersistIt() {
        val t = WallPhaseTracker()
        t.record(gold, failed(), 5_000)
        t.record(elixir, WallPhaseOutcome.Capped(8_000_000L), 6_000)
        val copy = WallPhaseTracker(t.state())
        assertEquals(t.state(), copy.state())
        assertFalse(copy.canRun(gold, 5_000 + 10 * minute - 1))
        assertTrue(copy.canRun(gold, 5_000 + 10 * minute))
        assertTrue(copy.suppressed(elixir))
        assertEquals(setOf(8_000_000L), copy.cappedPrices)
    }
}
