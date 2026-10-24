package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmingGoalTest {

    private val capacities = mapOf(
        Resource.GOLD to 10_000_000L,
        Resource.ELIXIR to 10_000_000L,
        Resource.DARK_ELIXIR to 200_000L
    )

    private fun sample(at: Long, vararg values: Pair<Resource, Long>) = ResourceSample(at, values.toMap())

    @Test fun oneHighReadingCannotCompleteGoal() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val readings = listOf(
            ResourceSample(1_000, mapOf(Resource.GOLD to 900_000L)),
            ResourceSample(2_200, mapOf(Resource.GOLD to 9_000_000L))
        )
        assertFalse(goalReached(goal, readings, mapOf(Resource.GOLD to 10_000_000L)))
    }

    @Test fun twoFreshReadingsAtTheTargetCompleteIt() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val readings = listOf(
            sample(1_000, Resource.GOLD to 2_100_000L),
            sample(1_600, Resource.GOLD to 2_000_000L)
        )
        assertTrue(goalReached(goal, readings, capacities))
    }

    @Test fun onlyTheLatestTwoSamplesCount() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val readings = listOf(
            sample(1_000, Resource.GOLD to 3_000_000L),
            sample(1_600, Resource.GOLD to 3_000_000L),
            sample(9_000, Resource.GOLD to 500_000L),
            sample(9_600, Resource.GOLD to 500_000L)
        )
        assertFalse(goalReached(goal, readings, capacities))
    }

    @Test fun allNeedsEveryTargetInBothSamples() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L, Resource.ELIXIR to 3_000_000L), GoalMatch.ALL)
        val short = listOf(
            sample(1_000, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 2_900_000L),
            sample(1_600, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 3_100_000L)
        )
        assertFalse(goalReached(goal, short, capacities))
        val met = listOf(
            sample(1_000, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 3_000_000L),
            sample(1_600, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 3_100_000L)
        )
        assertTrue(goalReached(goal, met, capacities))
    }

    @Test fun anyNeedsOneTargetInEachSample() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L, Resource.ELIXIR to 3_000_000L), GoalMatch.ANY)
        val readings = listOf(
            sample(1_000, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 100L),
            sample(1_600, Resource.GOLD to 100L, Resource.ELIXIR to 3_000_000L)
        )
        assertTrue(goalReached(goal, readings, capacities))
        val oneFrameOnly = listOf(
            sample(1_000, Resource.GOLD to 2_500_000L, Resource.ELIXIR to 100L),
            sample(1_600, Resource.GOLD to 100L, Resource.ELIXIR to 100L)
        )
        assertFalse(goalReached(goal, oneFrameOnly, capacities))
    }

    @Test fun noTargetsIsNeverReached() {
        val readings = listOf(sample(1_000, Resource.GOLD to 9_000_000L), sample(1_600, Resource.GOLD to 9_000_000L))
        assertFalse(goalReached(FarmingGoal(), readings, capacities))
        assertFalse(goalReached(FarmingGoal(mapOf(Resource.GOLD to 0L)), readings, capacities))
    }

    @Test fun aMissingCurrencyIsNotZeroAndNotMet() {
        // Dark elixir has no region yet, so it is never in a sample. An ANY goal on it must not
        // fire, and an ALL goal that includes it must not either.
        val readings = listOf(sample(1_000, Resource.GOLD to 5_000_000L), sample(1_600, Resource.GOLD to 5_000_000L))
        val dark = FarmingGoal(mapOf(Resource.DARK_ELIXIR to 0L + 1L))
        assertFalse(goalReached(dark, readings, capacities))
        val both = FarmingGoal(mapOf(Resource.GOLD to 1_000_000L, Resource.DARK_ELIXIR to 1L), GoalMatch.ALL)
        assertFalse(goalReached(both, readings, capacities))
        val either = FarmingGoal(mapOf(Resource.GOLD to 1_000_000L, Resource.DARK_ELIXIR to 1L), GoalMatch.ANY)
        assertTrue(goalReached(either, readings, capacities))
    }

    @Test fun aRealZeroIsBelowAnyTarget() {
        val goal = FarmingGoal(mapOf(Resource.ELIXIR to 1L))
        val readings = listOf(sample(1_000, Resource.ELIXIR to 0L), sample(1_600, Resource.ELIXIR to 0L))
        assertFalse(goalReached(goal, readings, capacities))
    }

    @Test fun repeatedOrBackwardTimestampsAreOneSample() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val same = listOf(sample(1_000, Resource.GOLD to 3_000_000L), sample(1_000, Resource.GOLD to 3_000_000L))
        assertFalse(goalReached(goal, same, capacities))
        val backward = listOf(sample(2_000, Resource.GOLD to 3_000_000L), sample(1_000, Resource.GOLD to 3_000_000L))
        assertFalse(goalReached(goal, backward, capacities))
        assertFalse(goalReached(goal, listOf(sample(1_000, Resource.GOLD to 3_000_000L)), capacities))
    }

    @Test fun aReadingBeyondCapacityIsAMisread() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val readings = listOf(sample(1_000, Resource.GOLD to 3_000_000L), sample(1_600, Resource.GOLD to 30_000_000L))
        assertFalse(goalReached(goal, readings, capacities))
        // Beyond capacity in a currency the goal does not name still spoils the frame.
        val spoiled = listOf(
            sample(1_000, Resource.GOLD to 3_000_000L, Resource.ELIXIR to 3_000_000L),
            sample(1_600, Resource.GOLD to 3_000_000L, Resource.ELIXIR to 90_000_000L)
        )
        assertFalse(goalReached(goal, spoiled, capacities))
    }

    @Test fun aTargetWithoutAKnownCapacityIsNeverReached() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 2_000_000L))
        val readings = listOf(sample(1_000, Resource.GOLD to 3_000_000L), sample(1_600, Resource.GOLD to 3_000_000L))
        assertFalse(goalReached(goal, readings, emptyMap()))
    }

    @Test fun resourceKeysAreStrict() {
        assertEquals(Resource.DARK_ELIXIR, Resource.fromKey("dark_elixir"))
        assertEquals("gold", Resource.GOLD.key)
        assertNull(Resource.fromKey("GOLD"))
        assertNull(Resource.fromKey("gems"))
    }
}
