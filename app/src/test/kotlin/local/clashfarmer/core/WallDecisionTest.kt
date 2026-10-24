package local.clashfarmer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallDecisionTest {

    @Test
    fun testWallMaxLevelCapExcludesSpending() {
        val maxCap = Policy.DEFAULT_MAX_WALL_LEVEL // 17
        val level16 = 16
        val level17 = 17
        val level18 = 18

        assertTrue("Level 16 wall should be eligible for upgrade below cap", level16 < maxCap)
        assertFalse("Level 17 wall is capped and must not be upgraded", level17 < maxCap)
        assertFalse("Level 18 wall is capped and must not be upgraded", level18 < maxCap)
    }

    @Test
    fun testWallBudgetAndReserveDecisions() {
        val reserve = 1_000_000L

        // Affordable: balance = 5M, cost = 2M -> remaining = 3M >= 1M reserve -> OK
        assertTrue(Policy.permittedUpgrade("gold", 5_000_000L, 2_000_000L, reserve))
        assertTrue(Policy.permittedUpgrade("elixir", 3_000_000L, 2_000_000L, reserve))

        // Boundary: balance = 3M, cost = 2M -> remaining = 1M == reserve -> OK
        assertTrue(Policy.permittedUpgrade("gold", 3_000_000L, 2_000_000L, reserve))

        // Unaffordable: balance = 2.99M, cost = 2M -> remaining = 0.99M < reserve -> FAIL
        assertFalse(Policy.permittedUpgrade("gold", 2_999_999L, 2_000_000L, reserve))

        // Cost exceeds balance -> FAIL
        assertFalse(Policy.permittedUpgrade("elixir", 1_500_000L, 2_000_000L, reserve))
    }

    @Test
    fun testGemsAndUnknownCurrenciesRejected() {
        for (badCurrency in listOf("gems", "gem", "dark_elixir", "ores", "unknown")) {
            var caught = false
            try {
                Policy.permittedUpgrade(badCurrency, 10_000_000L, 100_000L, 1_000_000L)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for currency: $badCurrency", caught)
        }
    }

    @Test
    fun testAmbiguousPricesRejected() {
        val ambiguousList = listOf("?", "1.5M", "N/A", "10O000", "free", "--", "")
        for (amb in ambiguousList) {
            var caught = false
            try {
                Policy.amount(amb)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for ambiguous price '$amb'", caught)
        }
    }
}
