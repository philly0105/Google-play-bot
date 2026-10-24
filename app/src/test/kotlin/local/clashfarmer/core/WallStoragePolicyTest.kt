package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WallStoragePolicyTest {

    private val rules = WallStorageRules()
    private val capacity = 20_000_000L
    private val caps = mapOf(Resource.GOLD to capacity, Resource.ELIXIR to capacity)

    private fun currencies(
        gold: Long? = null,
        elixir: Long? = null,
        preference: String = "auto",
        active: Set<Resource> = emptySet(),
        request: WallPhaseRequest = WallPhaseRequest.AUTOMATIC,
        rules: WallStorageRules = this.rules,
        capacities: Map<Resource, Long> = caps
    ) = wallCurrencies(
        rules,
        listOfNotNull(gold?.let { Resource.GOLD to it }, elixir?.let { Resource.ELIXIR to it }).toMap(),
        capacities, preference, active, request
    )

    @Test fun defaultsAreNinetyAndSeventyFive() {
        assertEquals(90, rules.triggerPercent)
        assertEquals(75, rules.resumePercent)
    }

    @Test fun rulesRefuseOutOfRangePercentages() {
        for ((trigger, resume) in listOf(0 to 0, 101 to 75, 90 to 90, 90 to -1, 50 to 60)) {
            try {
                WallStorageRules(trigger, resume)
                fail("Expected $trigger/$resume to be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        WallStorageRules(1, 0)
        WallStorageRules(100, 99)
    }

    @Test fun justBelowTriggerIsQuietAndAtTriggerSpends() {
        assertEquals(emptySet<Resource>(), currencies(gold = 17_999_999L))
        assertEquals(setOf(Resource.GOLD), currencies(gold = 18_000_000L))
        assertEquals(setOf(Resource.GOLD), currencies(gold = 20_000_000L))
    }

    @Test fun autoTreatsGoldAndElixirIndependently() {
        assertEquals(setOf(Resource.ELIXIR), currencies(gold = 5_000_000L, elixir = 19_000_000L))
        assertEquals(setOf(Resource.GOLD, Resource.ELIXIR), currencies(gold = 18_000_000L, elixir = 19_000_000L))
    }

    @Test fun preferenceRestrictsWhichCurrencyCanTrigger() {
        assertEquals(emptySet<Resource>(), currencies(gold = 19_000_000L, elixir = 19_000_000L, preference = "none"))
        assertEquals(setOf(Resource.GOLD), currencies(gold = 19_000_000L, elixir = 19_000_000L, preference = "gold"))
        assertEquals(setOf(Resource.ELIXIR), currencies(gold = 19_000_000L, elixir = 19_000_000L, preference = "elixir"))
    }

    @Test fun anActiveCurrencyStaysEligibleUntilAtOrBelowResume() {
        val active = setOf(Resource.GOLD)
        // Below the trigger but above resume (15M): keeps spending.
        assertEquals(setOf(Resource.GOLD), currencies(gold = 15_000_001L, active = active))
        // Exactly at resume: done.
        assertEquals(emptySet<Resource>(), currencies(gold = 15_000_000L, active = active))
        assertEquals(emptySet<Resource>(), currencies(gold = 10_000_000L, active = active))
        // Without the active mark the same balance is below the trigger.
        assertEquals(emptySet<Resource>(), currencies(gold = 15_000_001L))
    }

    @Test fun anActiveMarkOnOneCurrencyDoesNotWakeTheOther() {
        assertEquals(setOf(Resource.GOLD), currencies(gold = 16_000_000L, elixir = 16_000_000L, active = setOf(Resource.GOLD)))
    }

    @Test fun missingOrImpossibleBalancesAreNeverEligible() {
        assertEquals(emptySet<Resource>(), currencies(gold = null, elixir = null))
        assertEquals(emptySet<Resource>(), currencies(gold = 20_000_001L)) // above capacity: a misread
        assertEquals(emptySet<Resource>(), currencies(gold = -1L))
        assertEquals(emptySet<Resource>(), currencies(gold = 19_000_000L, capacities = emptyMap()))
        assertEquals(emptySet<Resource>(), currencies(gold = 19_000_000L, capacities = mapOf(Resource.GOLD to 0L)))
        assertEquals(emptySet<Resource>(), currencies(gold = 20_000_001L, active = setOf(Resource.GOLD)))
        assertEquals(emptySet<Resource>(), currencies(gold = 20_000_001L, request = WallPhaseRequest.MANUAL))
    }

    @Test fun manualIgnoresTriggerAndResumeAndUsesAnyPositiveBalance() {
        assertEquals(setOf(Resource.GOLD, Resource.ELIXIR),
            currencies(gold = 1_000L, elixir = 5_000_000L, request = WallPhaseRequest.MANUAL))
        assertEquals(setOf(Resource.GOLD), currencies(gold = 1_000L, elixir = 0L, request = WallPhaseRequest.MANUAL))
        assertEquals(setOf(Resource.ELIXIR),
            currencies(gold = 1_000L, elixir = 5_000_000L, preference = "elixir", request = WallPhaseRequest.MANUAL))
    }

    @Test fun percentageArithmeticDoesNotOverflow() {
        val huge = Long.MAX_VALUE - 5
        val big = mapOf(Resource.GOLD to huge)
        val full = WallStorageRules(100, 75)
        assertEquals(setOf(Resource.GOLD),
            wallCurrencies(full, mapOf(Resource.GOLD to huge), big, "gold", emptySet(), WallPhaseRequest.AUTOMATIC))
        assertEquals(emptySet<Resource>(),
            wallCurrencies(full, mapOf(Resource.GOLD to huge - 1), big, "gold", emptySet(), WallPhaseRequest.AUTOMATIC))
        assertEquals(huge / 100 * 90 + huge % 100 * 90 / 100, percentOfFloor(huge, 90))
        assertEquals(18L, percentOfFloor(21L, 90))
        assertEquals(19L, percentOfCeil(21L, 90))
        assertEquals(18L, percentOfCeil(20L, 90))
    }

    // Bulk count: worked examples from the spec. Capacity 20M, resume 75% (15M), reserve 0.

    @Test fun oneMillionWallsBuyFourToReachResume() {
        assertEquals(4, wallBulkCount(rules, 19_000_000L, capacity, 0L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun threeMillionWallsBuyTwoAndMayCrossBelowResume() {
        assertEquals(2, wallBulkCount(rules, 19_000_000L, capacity, 0L, 3_000_000L, 20, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun theReserveCapsTheCountBelowTheResumeTarget() {
        // Resume would allow 4, but 19M - 16M reserve leaves room for 3 one-million walls.
        assertEquals(3, wallBulkCount(rules, 19_000_000L, capacity, 16_000_000L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
        // 3M walls: floor(3M / 3M) = 1 though resume wants 2.
        assertEquals(1, wallBulkCount(rules, 19_000_000L, capacity, 16_000_000L, 3_000_000L, 20, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun theReserveNeverLetsTheLastWallCrossIt() {
        assertEquals(0, wallBulkCount(rules, 19_000_000L, capacity, 18_500_000L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun theWallLimitCapsTheCount() {
        assertEquals(2, wallBulkCount(rules, 19_000_000L, capacity, 0L, 1_000_000L, 2, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun nothingIsBoughtAtOrBelowResumeOrOnBadInput() {
        assertEquals(0, wallBulkCount(rules, 15_000_000L, capacity, 0L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, 10_000_000L, capacity, 0L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, 19_000_000L, capacity, 0L, 0L, 20, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, 19_000_000L, capacity, 0L, -5L, 20, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, 19_000_000L, 0L, 0L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, 19_000_000L, capacity, 0L, 1_000_000L, 0, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, -1L, capacity, 0L, 1_000_000L, 20, WallPhaseRequest.AUTOMATIC))
    }

    @Test fun manualSpendsDownToTheReserveIgnoringResume() {
        // 6M above a 13M reserve with 1M walls: six, though that is far below resume.
        assertEquals(6, wallBulkCount(rules, 19_000_000L, capacity, 13_000_000L, 1_000_000L, 20, WallPhaseRequest.MANUAL))
        assertEquals(19, wallBulkCount(rules, 19_500_000L, capacity, 0L, 1_000_000L, 20, WallPhaseRequest.MANUAL))
        assertEquals(5, wallBulkCount(rules, 19_500_000L, capacity, 0L, 1_000_000L, 5, WallPhaseRequest.MANUAL))
        assertEquals(0, wallBulkCount(rules, 1_000_000L, capacity, 1_000_000L, 1_000_000L, 20, WallPhaseRequest.MANUAL))
    }

    @Test fun onlyAWallCurrencyCountsAsARead() {
        val darkOnly = mapOf(Resource.DARK_ELIXIR to 180_940L)
        assertEquals(true, wallBalancesUnread(darkOnly, "auto"))
        assertEquals(true, wallBalancesUnread(emptyMap(), "auto"))
        assertEquals(true, wallBalancesUnread(mapOf(Resource.ELIXIR to 5_678_403L), "gold"))
        assertEquals(false, wallBalancesUnread(mapOf(Resource.ELIXIR to 5_678_403L), "auto"))
        assertEquals(false, wallBalancesUnread(mapOf(Resource.GOLD to 0L), "gold"))
    }

    @Test fun bulkCountIsOverflowSafe() {
        val huge = Long.MAX_VALUE - 5
        assertEquals(100, wallBulkCount(WallStorageRules(100, 0), huge, huge, 0L, huge / 1_000, 100, WallPhaseRequest.AUTOMATIC))
        assertEquals(0, wallBulkCount(rules, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1L, 100, WallPhaseRequest.MANUAL))
    }
}
