package local.clashfarmer.core

import org.junit.Assert.*
import org.junit.Test

class WallBatchPlanTest {
    private val prices = mapOf("gold" to 4_000_000L, "elixir" to 4_000_000L)
    private val reserves = mapOf("gold" to 0L, "elixir" to 0L)

    @Test fun insufficientBalancesProduceNoPurchase() {
        assertNull(WallBatchPlan.choose(prices, mapOf("gold" to 3_696_709L, "elixir" to 3_542_323L), reserves, "auto", 20))
    }
    @Test fun reserveAndExactBudgetDetermineBatch() {
        val plan = WallBatchPlan.choose(prices, mapOf("gold" to 22_000_000L), mapOf("gold" to 2_000_000L), "gold", 20)!!
        assertEquals(5, plan.count)
        assertEquals(20_000_000L, plan.total)
    }
    @Test fun cheaperCurrencyCanFundMoreWallsWithSmallerBalance() {
        val plan = WallBatchPlan.choose(mapOf("gold" to 4L, "elixir" to 2L), mapOf("gold" to 20L, "elixir" to 18L), reserves, "auto", 20)!!
        assertEquals("elixir", plan.currency)
        assertEquals(9, plan.count)
    }
    @Test fun fixedPreferenceNeverFallsBack() {
        assertNull(WallBatchPlan.choose(prices, mapOf("gold" to 0L, "elixir" to 20_000_000L), reserves, "gold", 20))
    }
    @Test fun autoRequiresBothCurrencyReadingsButFixedPreferenceRequiresOnlyItsOwn() {
        assertFalse(WallBatchPlan.hasCompleteEvidence(
            mapOf("gold" to 4_000_000L), mapOf("gold" to 20_000_000L), "auto"
        ))
        assertTrue(WallBatchPlan.hasCompleteEvidence(
            mapOf("gold" to 4_000_000L), mapOf("gold" to 20_000_000L), "gold"
        ))
    }
    @Test fun configuredPreferenceAndCapGovernThePlan() {
        val plan = WallBatchPlan.choose(
            prices,
            mapOf("gold" to 40_000_000L, "elixir" to 80_000_000L),
            reserves,
            "gold",
            1
        )!!
        assertEquals("gold", plan.currency)
        assertEquals(1, plan.count)
    }
    @Test fun phaseCapAndHugeBalancesDoNotOverflow() {
        assertEquals(100, WallBatchPlan.choose(mapOf("gold" to 1L), mapOf("gold" to Long.MAX_VALUE), reserves, "gold", 100)!!.count)
        assertNull(WallBatchPlan.choose(prices, mapOf("gold" to 1L), mapOf("gold" to Long.MAX_VALUE), "gold", 20))
    }
    @Test fun tenOnlyWhenItFitsAndNoUnexpectedGrowth() {
        assertEquals(1, WallBatchPlan.nextStep(1, 10))
        assertEquals(10, WallBatchPlan.nextStep(1, 11))
        assertEquals(0, WallBatchPlan.nextStep(5, 5))
        assertTrue(WallBatchPlan.acceptsGrowth(1, 11, 10, 12))
        assertFalse(WallBatchPlan.acceptsGrowth(1, 12, 10, 12))
        assertFalse(WallBatchPlan.acceptsGrowth(1, 1, 10, 12))
    }
    @Test fun confirmationPriceDoesNotMergeLevelOrOtherNumbers() {
        assertEquals(4_000_000L, WallPanel.namedPrice("Upgrade level 16 selected Walls for 4 000 000 Gold?", "gold"))
        assertNull(WallPanel.namedPrice("for 7000000 Dark Elixir?", "elixir"))
        assertNull(WallPanel.strictPrice("4 000 000 coin 1"))
        assertNull(WallPanel.strictPrice("40 00 000"))
        assertEquals(4_000_000L, WallPanel.strictPrice("4 000 000"))
        assertEquals(4_000_000L, WallPanel.smallPriceCandidate("4000ODDC"))
        assertNull(WallPanel.smallPriceCandidate("J0DDODD"))
    }
    @Test fun otherBuildingsAndUnreadableCountsAreNotWalls() {
        val viewport = Viewport(0, 0, 1600, 720)
        fun read(text: String) = WallPanel.parseTitle(listOf(local.clashfarmer.recognition.RecognizedBox(text, 660, 465, 940, 495)), viewport)
        assertNull(read("Cannon (Level 15)"))
        assertNull(read("Wall (Level 15)x ?"))
        assertNull(read("Wall (Level 15)x 0"))
        assertEquals(1, read("WaII (Level 15)x 1")?.count)
        assertNull(read("Grand Warden (Level 70)"))
    }

    /** Blood's Level 18 title as the reader gave it on 30 September. */
    @Test fun mangledWallTitlesStillRead() {
        val viewport = Viewport(0, 0, 1600, 720)
        fun read(text: String) = WallPanel.parseTitle(listOf(local.clashfarmer.recognition.RecognizedBox(text, 660, 465, 940, 495)), viewport)
        for (text in listOf("Walis(Level 18)", "WaiistLevel 18)", "Waiistkevel. 18)", "Waiitkevel i8)",
            "Wal (Eevel 18)", "Wair (Level 18)y", "Wall(Level l8)", "wail (Level i8)", "Wall (Levelí18)", "Wall_(Level 18)x 1", "WaiscLevel 18)", "walk(Level 18)", "waik(Level i8)", "AWaistLevel i8)", "wais(Level i8)")) {
            assertEquals(text, WallPanel.WallSelection(18, 1), read(text))
        }
    }
}
