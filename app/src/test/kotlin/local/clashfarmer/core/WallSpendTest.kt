package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallSpendTest {

    private val viewport = Viewport(0, 0, 1600, 720)

    /** Gold's price is the left of the two on every panel below. */
    private val iconByColumn: (RecognizedBox) -> String? = { if (it.center.x < 1000) "gold" else "elixir" }

    @Test
    fun triesWhicheverCurrencyHasMoreHeadroomFirst() {
        // 22.8M gold against 12.4M elixir: gold buys nearly twice as many walls.
        assertEquals(listOf("gold", "elixir"), WallPanel.currencyOrder(22_823_886, 12_384_822))
    }

    @Test
    fun triesElixirFirstWhenElixirIsTheDeeperPile() {
        assertEquals(listOf("elixir", "gold"), WallPanel.currencyOrder(1_605_162, 16_605_162))
    }

    @Test
    fun reachesForTenWallsBeforeOne() {
        // One wall at a time is how 22 million gold sat untouched for a whole session.
        assertEquals(10, WallPanel.addStep(count = 1, maxWalls = 20))
    }

    @Test
    fun fallsBackToOneWhenTenWouldPassTheCeiling() {
        assertEquals(1, WallPanel.addStep(count = 18, maxWalls = 20))
    }

    @Test
    fun addsNothingAtTheCeiling() {
        assertEquals(0, WallPanel.addStep(count = 20, maxWalls = 20))
    }

    /**
     * The band the reader really returned at 22:39 on 17 September, with Upgrade More split
     * across two boxes. Taking every box with the word "upgrade" in reading order made Upgrade
     * More the gold purchase button, and pressing it would have grown the selection instead of
     * buying it.
     */
    private val splitLabelPanel = listOf(
        RecognizedBox("12", 295, 470, 320, 492),
        RecognizedBox("INfo", 495, 578, 548, 606),
        RecognizedBox("Select", 624, 570, 690, 596),
        RecognizedBox("ROW", 635, 596, 685, 614),
        RecognizedBox("Wall Leveli5", 700, 465, 900, 495),
        RecognizedBox("UpgRade", 751, 570, 845, 596),
        RecognizedBox("MORe", 767, 596, 830, 614),
        RecognizedBox("QO0D 00o O9O00 000O", 900, 508, 1124, 532),
        RecognizedBox("UpgRade", 893, 586, 985, 612),
        RecognizedBox("UPgRade", 1029, 586, 1121, 612)
    )

    @Test
    fun aSplitUpgradeMoreLabelIsNotMistakenForThePurchaseButton() {
        // Both prices came back as one box, so neither button can be priced and none is offered.
        val buttons = WallPanel.purchaseButtons(splitLabelPanel, viewport, iconByColumn)
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun theTitleSurvivesLosingItsBracketsAndADigit() {
        // "Wall (Level 15)" came back as "Wall Leveli5".
        val selection = WallPanel.parseTitle(splitLabelPanel, viewport)
        assertEquals(15, selection?.level)
        assertEquals(1, selection?.count)
    }

    /**
     * The band from 23:02 on 17 September, with the elixir button read as "UP9Rade". Matching
     * the literal word "upgrade" found only the gold button, so the phase had one currency to
     * spend where the panel offered two.
     */
    private val digitSwappedPanel = listOf(
        RecognizedBox("INfo", 495, 570, 548, 598),
        RecognizedBox("Wall(Level 15)", 672, 465, 928, 495),
        RecognizedBox("Select", 623, 570, 689, 596),
        RecognizedBox("ROW", 636, 596, 686, 614),
        RecognizedBox("UpgRade", 751, 570, 845, 596),
        RecognizedBox("MORe", 769, 596, 832, 614),
        RecognizedBox("4000 0oo O", 893, 508, 1010, 532),
        RecognizedBox("UPgRade", 892, 586, 984, 612),
        RecognizedBox("4000 000", 1044, 508, 1148, 532),
        RecognizedBox("UP9Rade", 1035, 586, 1127, 612)
    )

    @Test
    fun anUpgradeButtonWhoseGCameBackAsANineIsStillAnUpgradeButton() {
        val buttons = WallPanel.purchaseButtons(digitSwappedPanel, viewport, iconByColumn)
        assertEquals(938, buttons["gold"]?.tap?.x)
        assertEquals(4_000_000L, buttons["gold"]?.price)
        assertEquals(1081, buttons["elixir"]?.tap?.x)
        assertEquals(4_000_000L, buttons["elixir"]?.price)
    }

    /**
     * At 23:05 on 17 September "Wall (Level 15)" came back as "Wall (Levelis)" and parsed as
     * level 1, which is eleven levels below where the check that stops the phase at its ceiling
     * would have caught it.
     */
    @Test
    fun aLevelWhoseFiveCameBackAsAnSIsStillFifteen() {
        val boxes = listOf(RecognizedBox("Wall (Levelis)", 702, 465, 928, 495))
        assertEquals(15, WallPanel.parseTitle(boxes, viewport)?.level)
    }

    @Test
    fun aBulkPanelHasNoUpgradeMoreToConfuseTheButtons() {
        val bulk = listOf(
            RecognizedBox("Wall (Level 15)x 1", 660, 465, 940, 495),
            RecognizedBox("Remove Wall", 470, 574, 566, 614),
            RecognizedBox("Add Wall", 620, 586, 696, 612),
            RecognizedBox("Add Wall", 762, 586, 838, 612),
            RecognizedBox("4 000 000", 898, 508, 980, 532),
            RecognizedBox("UpgRade", 900, 586, 982, 612),
            RecognizedBox("4 000 000", 1042, 508, 1124, 532),
            RecognizedBox("UpgRade", 1044, 586, 1126, 612)
        )
        assertNull(WallPanel.buttons(bulk, viewport)[WallPanel.WallButton.UPGRADE_MORE])
        val buttons = WallPanel.purchaseButtons(bulk, viewport, iconByColumn)
        assertEquals(941, buttons["gold"]?.tap?.x)
        assertEquals(1085, buttons["elixir"]?.tap?.x)
    }

    /**
     * The band from 23:18 on 17 September, where the reader merged the Select Row label and the
     * Upgrade of Upgrade More into one box, "Select UpgRadde@622". That left the two purchase
     * buttons as the only boxes with the word "upgrade" in them, the nearest one to the "MORe"
     * label was the gold button, and pressing it as though it were Upgrade More opened a
     * purchase dialog the phase was not expecting and then abandoned the panel.
     */
    private val mergedLabelPanel = listOf(
        RecognizedBox("INfo", 495, 570, 548, 598),
        RecognizedBox("Wail", 700, 465, 760, 495),
        RecognizedBox("(Level 1s)", 731, 465, 868, 495),
        RecognizedBox("Select UpgRadde", 622, 570, 845, 596),
        RecognizedBox("ROW", 635, 596, 685, 614),
        RecognizedBox("MORe", 769, 596, 832, 614),
        RecognizedBox("4000 00o O", 893, 508, 1010, 532),
        RecognizedBox("UpgRade", 892, 586, 984, 612),
        RecognizedBox("O0D O00", 1060, 508, 1148, 532),
        RecognizedBox("UPgRade", 1029, 586, 1121, 612)
    )

    @Test
    fun theGoldButtonIsNeverTakenForUpgradeMore() {
        val buttons = WallPanel.purchaseButtons(mergedLabelPanel, viewport, iconByColumn)
        assertEquals(938, buttons["gold"]?.tap?.x)
        // "O0D O00" is no price, so elixir is found but will not be pressed.
        assertEquals(1075, buttons["elixir"]?.tap?.x)
        assertNull(buttons["elixir"]?.price)
        assertTrue(buttons.values.none { it.tap.x in 770..830 })
    }

    /** Whichever box it is read from, Upgrade More is pressed on the Upgrade More button. */
    @Test
    fun upgradeMoreIsPressedOnItsOwnButton() {
        val split = WallPanel.buttons(splitLabelPanel, viewport)
        assertTrue(split[WallPanel.WallButton.UPGRADE_MORE]!!.center.x in 770..830)
        val digits = WallPanel.buttons(digitSwappedPanel, viewport)
        assertTrue(digits[WallPanel.WallButton.UPGRADE_MORE]!!.center.x in 770..830)
    }
}
