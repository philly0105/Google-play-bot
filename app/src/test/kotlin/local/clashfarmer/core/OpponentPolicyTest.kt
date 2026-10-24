package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OpponentPolicyTest {

    private val goldRule = OpponentRules(
        enabled = true, minimums = mapOf(Resource.GOLD to 1_000_000L),
        maxSkips = 10, maxSearchMs = 90_000, maxSearchGold = 20_000
    )
    private val fresh = SearchProgress(skips = 0, elapsedMs = 5_000, spentGold = 0)

    private fun decide(
        rules: OpponentRules = goldRule,
        loot: Map<Resource, Long>,
        progress: SearchProgress = fresh,
        fee: Long? = 1_500,
        seconds: Int? = 25,
        rereadUsed: Boolean = false
    ) = chooseOpponent(rules, loot, progress, fee, seconds, rereadUsed)

    @Test fun skipLimitDeploysInsteadOfSearchingForever() {
        val rules = OpponentRules(true, mapOf(Resource.GOLD to 1_000_000L), maxSkips = 2)
        assertEquals(ScoutDecision.ATTACK_FALLBACK,
            chooseOpponent(rules, mapOf(Resource.GOLD to 1L),
                SearchProgress(2, 20_000, 0), 1_500, 20, false))
    }

    @Test fun disabledSelectionAttacksWhateverIsThere() {
        val off = goldRule.copy(enabled = false)
        assertEquals(ScoutDecision.ATTACK, decide(off, loot = emptyMap(), fee = null, seconds = null))
    }

    @Test fun enabledWithNoMinimumsAttacks() {
        assertEquals(ScoutDecision.ATTACK, decide(goldRule.copy(minimums = emptyMap()), loot = emptyMap()))
    }

    @Test fun eachResourceMinimumIsItsOwnTest() {
        for (resource in Resource.entries) {
            val rules = goldRule.copy(minimums = mapOf(resource to 500L))
            assertEquals(ScoutDecision.ATTACK, decide(rules, loot = mapOf(resource to 500L)))
            assertEquals(ScoutDecision.SKIP, decide(rules, loot = mapOf(resource to 499L)))
        }
    }

    @Test fun everyMinimumMustPass() {
        val rules = goldRule.copy(minimums = mapOf(Resource.GOLD to 500_000L, Resource.ELIXIR to 500_000L))
        assertEquals(ScoutDecision.SKIP,
            decide(rules, loot = mapOf(Resource.GOLD to 900_000L, Resource.ELIXIR to 100L)))
        assertEquals(ScoutDecision.ATTACK,
            decide(rules, loot = mapOf(Resource.GOLD to 900_000L, Resource.ELIXIR to 500_000L)))
    }

    @Test fun aKnownShortfallDecidesEvenWithAnotherResourceUnread() {
        val rules = goldRule.copy(minimums = mapOf(Resource.GOLD to 500_000L, Resource.ELIXIR to 500_000L))
        assertEquals(ScoutDecision.SKIP, decide(rules, loot = mapOf(Resource.GOLD to 10L)))
    }

    @Test fun combinedGoldAndElixirThreshold() {
        val rules = goldRule.copy(minimums = emptyMap(), minimumGoldAndElixir = 1_000_000L)
        assertEquals(ScoutDecision.ATTACK,
            decide(rules, loot = mapOf(Resource.GOLD to 400_000L, Resource.ELIXIR to 600_000L)))
        assertEquals(ScoutDecision.SKIP,
            decide(rules, loot = mapOf(Resource.GOLD to 400_000L, Resource.ELIXIR to 599_999L)))
        // One side unread: the sum is unknown, not the other side alone.
        assertEquals(ScoutDecision.REREAD, decide(rules, loot = mapOf(Resource.GOLD to 999_999_999L)))
    }

    @Test fun theCombinedSumCannotOverflowIntoAPoorTarget() {
        val rules = goldRule.copy(minimums = emptyMap(), minimumGoldAndElixir = 1_000_000L)
        assertEquals(ScoutDecision.ATTACK,
            decide(rules, loot = mapOf(Resource.GOLD to Long.MAX_VALUE, Resource.ELIXIR to Long.MAX_VALUE)))
    }

    @Test fun anUnreadableFeeIsNoReasonToPressNext() {
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = mapOf(Resource.GOLD to 1L), fee = null))
    }

    @Test fun theGoldBudgetMayBeSpentExactly() {
        val nearlySpent = SearchProgress(skips = 3, elapsedMs = 20_000, spentGold = 18_500)
        assertEquals(ScoutDecision.SKIP, decide(loot = mapOf(Resource.GOLD to 1L), progress = nearlySpent, fee = 1_500))
        assertEquals(ScoutDecision.ATTACK_FALLBACK,
            decide(loot = mapOf(Resource.GOLD to 1L), progress = nearlySpent, fee = 1_501))
    }

    @Test fun theSearchTimeLimitEndsSelection() {
        val late = SearchProgress(skips = 1, elapsedMs = 90_000, spentGold = 1_500)
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = mapOf(Resource.GOLD to 1L), progress = late))
    }

    @Test fun unreadableLootIsReadOnceMoreThenAttacked() {
        assertEquals(ScoutDecision.REREAD, decide(loot = emptyMap()))
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = emptyMap(), rereadUsed = true))
    }

    @Test fun underEightSecondsLeftDeploysRatherThanPressNext() {
        assertEquals(ScoutDecision.SKIP, decide(loot = mapOf(Resource.GOLD to 1L), seconds = 8))
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = mapOf(Resource.GOLD to 1L), seconds = 7))
    }

    @Test fun anUnknownTimerDeploysRatherThanPressNext() {
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = mapOf(Resource.GOLD to 1L), seconds = null))
        // A good target is taken whatever the timer says.
        assertEquals(ScoutDecision.ATTACK, decide(loot = mapOf(Resource.GOLD to 2_000_000L), seconds = null))
    }

    @Test fun noRereadWhenASkipCouldNotFollowAnyway() {
        assertEquals(ScoutDecision.ATTACK_FALLBACK, decide(loot = emptyMap(), seconds = 3))
    }
}
