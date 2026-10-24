package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallPanelTest {

    private val viewport = Viewport(0, 0, 1600, 720)

    /** The single-wall panel from 162-wallpanel.png, read left to right. */
    private val singlePanel = listOf(
        RecognizedBox("Wall (Level 15)", 672, 465, 930, 495),
        RecognizedBox("INfo", 490, 578, 548, 606),
        RecognizedBox("Select ROW", 620, 570, 700, 614),
        RecognizedBox("UpgRade MORe", 752, 570, 850, 614),
        RecognizedBox("4 000 000", 898, 508, 980, 532),
        RecognizedBox("UpgRade", 900, 586, 982, 612),
        RecognizedBox("4 000 000", 1042, 508, 1124, 532),
        RecognizedBox("UpgRade", 1044, 586, 1126, 612)
    )

    /** The bulk panel from 163-upgrademore.png, after Upgrade More was pressed. */
    private val bulkPanel = listOf(
        RecognizedBox("Wall (Level 15)x 1", 660, 465, 940, 495),
        RecognizedBox("Remove Wall", 470, 574, 566, 614),
        RecognizedBox("Add Wall", 620, 586, 696, 612),
        RecognizedBox("Add Wall", 762, 586, 838, 612),
        RecognizedBox("4 000 000", 898, 508, 980, 532),
        RecognizedBox("UpgRade", 900, 586, 982, 612),
        RecognizedBox("4 000 000", 1042, 508, 1124, 532),
        RecognizedBox("UpgRade", 1044, 586, 1126, 612)
    )

    @Test
    fun readsTheLevelOffTheSingleWallPanel() {
        val selection = WallPanel.parseTitle(singlePanel, viewport)
        assertEquals(15, selection?.level)
        assertEquals(1, selection?.count)
    }

    @Test
    fun readsLevelAndCountOffTheBulkPanel() {
        // "Wall (Level 15)x 1" - no space before the x, which is how it came back.
        val selection = WallPanel.parseTitle(bulkPanel, viewport)
        assertEquals(15, selection?.level)
        assertEquals(1, selection?.count)
    }

    @Test
    fun readsACountAboveOne() {
        val boxes = listOf(RecognizedBox("Wall (Level 16) x 11", 660, 465, 940, 495))
        val selection = WallPanel.parseTitle(boxes, viewport)
        assertEquals(16, selection?.level)
        assertEquals(11, selection?.count)
    }

    @Test
    fun findsUpgradeMoreAndNeverConfusesItWithSelectRow() {
        val buttons = WallPanel.buttons(singlePanel, viewport)
        assertEquals("UpgRade MORe", buttons[WallPanel.WallButton.UPGRADE_MORE]?.text)
        assertEquals("Select ROW", buttons[WallPanel.WallButton.SELECT_ROW]?.text)
        val purchases = WallPanel.purchaseButtons(singlePanel, viewport) { if (it.left < 1000) "gold" else "elixir" }
        assertEquals(941, purchases["gold"]?.tap?.x)
        assertEquals(1085, purchases["elixir"]?.tap?.x)
    }

    /**
     * The band from 00:56 on 23 September, a Level 18 panel with the Wall Ring button on its
     * right. The gold label did not read, so taking Upgrade labels left to right made the elixir
     * button gold and the ring button elixir.
     */
    private val ringPanel = listOf(
        RecognizedBox("15", 291, 470, 320, 492),
        RecognizedBox("INfo", 422, 578, 475, 606),
        RecognizedBox("Select", 553, 570, 620, 596),
        RecognizedBox("ROW", 577, 596, 620, 614),
        RecognizedBox("UpgRade", 694, 570, 780, 596),
        RecognizedBox("Wall.(Level 18):", 703, 465, 925, 495),
        RecognizedBox("MORe UPgRade", 709, 596, 790, 614),
        RecognizedBox("8O00 000O", 962, 508, 1052, 532),
        RecognizedBox("UPgRade", 965, 586, 1060, 612),
        RecognizedBox("8", 1146, 505, 1165, 530),
        RecognizedBox("UpgRade", 1108, 586, 1205, 612)
    )

    @Test
    fun theWallRingButtonIsNeverAPurchaseButton() {
        // Only the elixir price read; its icon is pink.
        val purchases = WallPanel.purchaseButtons(ringPanel, viewport) { if (it.left in 950..1000) "elixir" else null }
        assertNull(purchases["gold"])
        assertEquals(1012, purchases["elixir"]?.tap?.x)
        assertEquals(8_000_000L, purchases["elixir"]?.price)
        assertTrue(purchases.values.none { it.tap.x >= 1100 })
    }

    @Test
    fun anUnrecognisedIconIsNoPurchaseButton() {
        assertTrue(WallPanel.purchaseButtons(ringPanel, viewport) { null }.isEmpty())
    }

    @Test
    fun aPanelWhoseTitleDidNotReadIsStillAPanel() {
        assertTrue(WallPanel.isPanel(ringPanel.filter { !it.text.contains("Level") }, viewport))
    }

    @Test
    fun panelPricesReadTheirOsAsZeros() {
        assertEquals(8_000_000L, WallPanel.panelPrice("8O00 000O"))
        assertEquals(8_000_000L, WallPanel.panelPrice("BO00 O00"))
        assertEquals(8_000_000L, WallPanel.panelPrice("8 O00 O00"))
        assertEquals(4_000_000L, WallPanel.panelPrice("4000 0oo O"))
        assertEquals(4_000_000L, WallPanel.panelPrice("4 000 000"))
        assertEquals(7_000_000L, WallPanel.panelPrice("7 000 000"))
    }

    @Test
    fun garbledPanelPricesAreNoPrice() {
        assertNull(WallPanel.panelPrice("O0D O00"))
        assertNull(WallPanel.panelPrice("QO0D 00o O9O00 000O"))
        assertNull(WallPanel.panelPrice("8 00"))
        assertNull(WallPanel.panelPrice(""))
    }

    @Test
    fun iconColoursNameTheirCurrency() {
        val gold = IntArray(200) { if (it < 60) 0xFFF0C828.toInt() else 0xFFFFFFFF.toInt() }
        val elixir = IntArray(200) { if (it < 60) 0xFFD040E0.toInt() else 0xFFFFFFFF.toInt() }
        val red = IntArray(200) { 0xFFE02020.toInt() }
        val blue = IntArray(200) { 0xFF2060E0.toInt() }
        assertEquals("gold", WallPanel.iconCurrency(gold))
        assertEquals("elixir", WallPanel.iconCurrency(elixir))
        assertNull(WallPanel.iconCurrency(red))
        assertNull(WallPanel.iconCurrency(blue))
    }

    @Test
    fun findsBothAddButtonsOnTheBulkPanel() {
        val buttons = WallPanel.buttons(bulkPanel, viewport)
        // +10 is the left Add Wall, +1 the right: the badge digits are drawn on the icon and
        // do not come back as text, so position is the only thing separating them.
        assertEquals(658, buttons[WallPanel.WallButton.ADD_TEN]?.center?.x)
        assertEquals(800, buttons[WallPanel.WallButton.ADD_ONE]?.center?.x)
        assertEquals(518, buttons[WallPanel.WallButton.REMOVE]?.center?.x)
    }

    @Test
    fun theSingleWallPanelHasNoAddButtons() {
        val buttons = WallPanel.buttons(singlePanel, viewport)
        assertNull(buttons[WallPanel.WallButton.ADD_TEN])
        assertNull(buttons[WallPanel.WallButton.ADD_ONE])
    }

    @Test
    fun theBandIgnoresEverythingOutsideThePanel() {
        val boxes = singlePanel + listOf(
            RecognizedBox("4 500 000", 1350, 28, 1480, 56),   // the gold bar, top right
            RecognizedBox("Attack!", 80, 665, 190, 700)        // the attack button, bottom left
        )
        val band = WallPanel.band(boxes, viewport)
        assertTrue(band.none { it.text == "4 500 000" && it.left == 1350 })
        assertTrue(band.none { it.text == "Attack!" })
        assertEquals(8, band.size)
    }

    @Test
    fun aTitleWhoseWallCameBackMisspeltIsStillATitle() {
        // The panel title is drawn in the outlined font, and the double l of "Wall" is the
        // first thing it loses. A Level 15 panel was open at 22:34 on 17 September and the
        // phase bought nothing because the word did not survive the read.
        val boxes = listOf(RecognizedBox("WaII (LeveI 15)", 672, 465, 930, 495))
        val selection = WallPanel.parseTitle(boxes, viewport)
        assertEquals(15, selection?.level)
        assertEquals(1, selection?.count)
    }

    @Test
    fun level18TitlesFromTheBloodWalkReadAs18() {
        // 22 September: these parsed as 8, 18 and 1.
        for (title in listOf("Wall (Leve18):", "wall (Leve 18):", "Wall (Level-18)", "Wall (Levelis)", "Wall Leveli5")) {
            val expected = if (title.contains("18")) 18 else 15
            val selection = WallPanel.parseTitle(listOf(RecognizedBox(title, 672, 465, 930, 495)), viewport)
            assertEquals(title, expected, selection?.level)
        }
    }

    @Test
    fun titlesLostOnTheFirstOfOctoberRead() {
        // Every one of these was in the band of an open panel on 1 October and parsed as nothing.
        val titles = mapOf(
            "Wáll (Level 18)" to 18, "Wall (Léevei 16)" to 16, "wall (Leyel:i8)" to 18,
            "Wair (Lever i8)" to 18, "Wair (Level T8)" to 18, "Swäil:(Level 18)" to 18,
            "Wail-tLevel 18)" to 18, "Wait(Level 18)" to 18, "wa(Level 18)" to 18
        )
        for ((title, expected) in titles) {
            val selection = WallPanel.parseTitle(listOf(RecognizedBox(title, 672, 465, 930, 495)), viewport)
            assertEquals(title, expected, selection?.level)
        }
    }

    @Test
    fun aTitleSplitAcrossTwoBoxesIsReadAsOne() {
        // 07:12 on 1 October: "Wall (Lever" at x 702 and "i8)" at x 859, on the same line.
        val boxes = listOf(
            RecognizedBox("i8)", 859, 466, 900, 494),
            RecognizedBox("Wall (Lever", 702, 465, 850, 495),
            RecognizedBox("8000 000", 829, 508, 910, 532)
        )
        assertEquals(18, WallPanel.parseTitle(boxes, viewport)?.level)
    }

    @Test
    fun aHeroTitleIsStillNoWall() {
        val boxes = listOf(RecognizedBox("Grand Warden (Level 18)", 672, 465, 930, 495))
        assertNull(WallPanel.parseTitle(boxes, viewport))
    }

    @Test
    fun aMisspeltBulkTitleStillCarriesItsCount() {
        val boxes = listOf(RecognizedBox("Wal (Level 15) x 11", 672, 465, 930, 495))
        val selection = WallPanel.parseTitle(boxes, viewport)
        assertEquals(15, selection?.level)
        assertEquals(11, selection?.count)
    }

    @Test
    fun aPanelWithNoTitleAtAllReadsAsNothing() {
        val selection = WallPanel.parseTitle(
            singlePanel.filter { !it.text.contains("Level") }, viewport
        )
        assertNull(selection)
    }

    @Test
    fun theBandTextNamesWhatItReadAndWhere() {
        val text = WallPanel.bandText(singlePanel, viewport)
        assertTrue(text.contains("Select ROW@620"))
    }

    /**
     * The maxed panel from 191-camera.png: a Level 17 wall, no Upgrade More, and both Upgrades
     * priced at seven million. The run read this one as HOME and walked past a wall it had
     * plainly selected, because HOME and WALL were told apart by whether the words "Select" and
     * "ROW" happened to land next to each other in the reader's output.
     */
    private val maxedPanel = listOf(
        RecognizedBox("Wall (Level 17)", 673, 465, 930, 495),
        RecognizedBox("INfo", 565, 578, 615, 606),
        RecognizedBox("Select", 695, 570, 760, 596),
        RecognizedBox("ROW", 710, 596, 755, 614),
        RecognizedBox("7 000 000", 818, 508, 900, 532),
        RecognizedBox("UpgRade", 828, 586, 915, 612),
        RecognizedBox("7 000 000", 960, 508, 1042, 532),
        RecognizedBox("Upgrade", 968, 586, 1058, 612)
    )

    @Test
    fun aMaxedPanelIsStillAPanel() {
        assertTrue(WallPanel.isPanel(maxedPanel, viewport))
        assertEquals(17, WallPanel.parseTitle(maxedPanel, viewport)?.level)
    }

    @Test
    fun everyPanelLayoutIsRecognisedAsOne() {
        assertTrue(WallPanel.isPanel(singlePanel, viewport))
        assertTrue(WallPanel.isPanel(bulkPanel, viewport))
    }

    /**
     * The village draws a wall's level beside it as the camera settles and puts dashed Research
     * and Upgrade hints over the laboratory, so "wall", "level" and "upgrade" are all on screen
     * with no panel open. All of it sits above the panel band.
     */
    @Test
    fun aVillageWithWallAndUpgradeLabelsOnItIsNotAPanel() {
        val village = listOf(
            RecognizedBox("Wall Level17", 520, 245, 570, 270),
            RecognizedBox("Upgrade", 1160, 335, 1220, 352),
            RecognizedBox("Research", 1030, 288, 1096, 305),
            RecognizedBox("Full!", 995, 445, 1030, 462)
        )
        assertTrue(!WallPanel.isPanel(village, viewport))
    }

    @Test
    fun theShortOfFundsDialogIsRecognised() {
        // As logged on 20 September, and a clean variant with an Okay button and a gem price.
        assertTrue(WallPanel.isInsufficientFunds(
            "YoU doNt HaVe eNOUH ELixiR! Use aMagictem to do tHat"
        ))
        assertTrue(WallPanel.isInsufficientFunds("You don't have enough Gold! Okay 1250"))
        assertTrue(WallPanel.isInsufficientFunds("Buy the missing resources"))
    }

    @Test
    fun anOrdinaryConfirmationIsNotShortOfFunds() {
        assertTrue(!WallPanel.isInsufficientFunds("Upgrade Wall to Level 18? 7200 7920 Okay 5 600 000"))
        assertTrue(!WallPanel.isInsufficientFunds(
            "Do you really want to upgrade the selected Walls for 4000000 Gold? Cancel Okay"
        ))
    }

    @Test
    fun aRedPriceIsUnaffordableAndAWhiteOneIsNot() {
        val white = 0xFFF0F0F0.toInt()
        val salmon = 0xFFE88C78.toInt()
        val coin = 0xFFF0C828.toInt()
        val drop = 0xFFD040E0.toInt()
        val beige = 0xFFE0E0C0.toInt()
        // Proportions from the 21 September panel: gold short, elixir payable.
        val redPrice = IntArray(2000) { when { it < 480 -> salmon; it < 650 -> white; it < 780 -> coin; else -> beige } }
        val whitePrice = IntArray(2000) { when { it < 560 -> white; it < 660 -> drop; else -> beige } }
        assertEquals(false, WallPanel.priceAffordable(redPrice))
        assertEquals(true, WallPanel.priceAffordable(whitePrice))
        assertNull(WallPanel.priceAffordable(IntArray(2000) { beige }))
    }

    @Test
    fun aDroppedLeadingOneIsRestoredOnlyWhenItCoversThePrice() {
        assertEquals(11_370_884L, WallPanel.restoredLeadingOne(1_370_884, 8_000_000))
        // Still short with the one put back: nothing to restore.
        assertNull(WallPanel.restoredLeadingOne(950_000, 8_000_000))
        // Already enough, or already eight digits.
        assertNull(WallPanel.restoredLeadingOne(9_000_000, 8_000_000))
        assertNull(WallPanel.restoredLeadingOne(14_791_164, 8_000_000))
    }

    @Test
    fun aLevel18WallIsLevel18AtEveryGoldPassDiscount() {
        // 10M full, 9M at 10% off (5 October), 8.5M at 15%, 8M at 20% (30 September).
        for (price in listOf(10_000_000L, 9_000_000L, 8_500_000L, 8_000_000L)) {
            assertEquals("$price", 18, WallPanel.wallLevel(18, price))
        }
        for (price in listOf(7_000_000L, 6_300_000L, 5_950_000L, 5_600_000L)) {
            assertEquals("$price", 17, WallPanel.wallLevel(17, price))
        }
        assertEquals(16, WallPanel.wallLevel(16, 5_000_000))
        assertEquals(15, WallPanel.wallLevel(15, 4_000_000))
    }

    @Test
    fun aPriceOutsideTheTitlesDiscountBandIsRefused() {
        assertNull("more than 20% off", WallPanel.wallLevel(18, 7_900_000))
        assertNull("above the full price", WallPanel.wallLevel(18, 10_500_000))
        assertNull("a level 17 price under a level 18 title", WallPanel.wallLevel(18, 5_600_000))
        assertNull(WallPanel.wallLevel(17, 8_000_000))
        // A misread price buys nothing.
        assertNull(WallPanel.wallLevel(18, 58_000_000))
    }

    @Test
    fun fourMillionIsSettledByTheTitleAndRefusedWithoutIt() {
        // 15 -> 16 at full price on Sweat, or 16 -> 17 at 20% off with the pass.
        assertEquals(15, WallPanel.wallLevel(15, 4_000_000))
        assertEquals(16, WallPanel.wallLevel(16, 4_000_000))
        assertNull(WallPanel.wallLevel(null, 4_000_000))
        assertNull(WallPanel.wallLevel(1, 4_000_000))
    }

    @Test
    fun aTitleThatLostADigitFallsBackToAnUnambiguousPrice() {
        // "Wall (Level 1)" for 17 on 23 September, "Leve18" read as 8 on 22 September.
        assertEquals(17, WallPanel.wallLevel(1, 5_600_000))
        assertEquals(18, WallPanel.wallLevel(8, 9_000_000))
        assertEquals(18, WallPanel.wallLevel(null, 8_500_000))
        assertNull(WallPanel.wallLevel(null, 7_500_000))
    }

    @Test
    fun aTitleWithNoKnownFullPriceBuysNothing() {
        // Nineteen is the wall cap: there is no level 20, so no price for a level 19 wall.
        assertNull(WallPanel.wallLevel(19, 9_000_000))
        assertNull(WallPanel.wallLevel(20, 9_500_000))
        assertNull(WallPanel.wallLevel(14, 4_000_000))
    }

    @Test
    fun aStruckOutFullPriceMustMatchTheLevelAndCoverThePrice() {
        assertEquals(18, WallPanel.wallLevel(18, 8_500_000, fullPrice = 10_000_000))
        assertEquals(18, WallPanel.wallLevel(null, 9_000_000, fullPrice = 10_000_000))
        assertEquals(16, WallPanel.wallLevel(null, 4_000_000, fullPrice = 5_000_000))
        assertNull("full price of another level", WallPanel.wallLevel(18, 8_000_000, fullPrice = 7_000_000))
        assertNull("more than 20% under the struck price", WallPanel.wallLevel(18, 7_000_000, fullPrice = 10_000_000))
        assertNull("not a full price at all", WallPanel.wallLevel(null, 8_000_000, fullPrice = 9_500_000))
    }

    @Test
    fun aStruckOutPriceIsTheOtherPriceOnTheConfirmRow() {
        val okay = RecognizedBox("Okay 9 000 000", 880, 420, 1100, 460)
        val struck = RecognizedBox("10 000 000", 1110, 420, 1230, 445)
        val hp = RecognizedBox("12 000 13 000", 900, 300, 1100, 330)
        assertEquals(10_000_000L, WallPanel.struckPrice(listOf(okay, struck, hp), okay, 9_000_000))
        assertNull(WallPanel.struckPrice(listOf(okay, hp), okay, 9_000_000))
        // A larger number the paid price is not a discount of is not a struck-out price.
        assertNull(WallPanel.struckPrice(listOf(okay, struck), okay, 5_000_000))
    }

    @Test
    fun aTotalIsOnlyACountWhenItIsWholeWalls() {
        assertEquals(3, WallPanel.countFor(24_000_000, 8_000_000))
        assertEquals(1, WallPanel.countFor(5_600_000, 5_600_000))
        assertNull(WallPanel.countFor(6_000_000, 8_000_000))
        assertNull(WallPanel.countFor(0, 8_000_000))
    }

    /** The bulk panel as it stood on 23 September: +10 left of +1, and Upgrade spelt "UPgRadde". */
    private val bulkPanel23 = listOf(
        RecognizedBox("Wall (Levei 1)x", 660, 465, 940, 495),
        RecognizedBox("ReMove Wall", 400, 574, 500, 614),
        RecognizedBox("Add Wall", 540, 586, 636, 612),
        RecognizedBox("Add Wall", 682, 586, 778, 612),
        RecognizedBox("16 000 000", 822, 508, 910, 532),
        RecognizedBox("UPgRadde", 822, 586, 918, 612),
        RecognizedBox("16 000 000", 964, 508, 1052, 532),
        RecognizedBox("UpgRade", 964, 586, 1060, 612),
        RecognizedBox("UpgRade", 1108, 586, 1204, 612)
    )

    @Test
    fun theAddWallButtonsAreFoundInOrderAndUpgradeIsNotOne() {
        val adds = WallPanel.addButtons(bulkPanel23, viewport)
        assertEquals(listOf(540, 682), adds.map { it.left })
    }

    @Test
    fun aBulkPanelWithAnUnreadableTitleIsStillAPanel() {
        assertTrue(WallPanel.isPanel(bulkPanel23, viewport))
    }

    /** Blood's bulk panel on 30 September: title unread, the red +10 unread, one Add Wall left. */
    @Test
    fun aBulkPanelWithOnlyOneAddWallReadIsStillAPanel() {
        val panel = listOf(
            RecognizedBox("Wall_(Levei 18)x 1", 640, 465, 960, 495),
            RecognizedBox("Remove Wall", 400, 586, 496, 612),
            RecognizedBox("Add Wall", 682, 586, 778, 612),
            RecognizedBox("8 000 000", 830, 508, 912, 532),
            RecognizedBox("UpgRade", 824, 586, 920, 612),
            RecognizedBox("8 000 000", 972, 508, 1054, 532),
            RecognizedBox("UpgRade", 966, 586, 1062, 612),
            RecognizedBox("UpgRade", 1108, 586, 1204, 612)
        )
        assertTrue(WallPanel.isPanel(panel.drop(1), viewport))
        assertTrue(WallPanel.isPanel(panel.drop(1).filterNot { it.text == "Add Wall" }, viewport))
    }

    /** 08:23 on 30 September: the elixir and ring labels read as one box. */
    @Test
    fun mergedUpgradeLabelsAreCroppedOneButtonEach() {
        val panel = listOf(
            RecognizedBox("Remove Wall", 400, 586, 496, 612),
            RecognizedBox("Add Wall", 682, 586, 778, 612),
            RecognizedBox("UpgRade", 824, 586, 920, 612),
            RecognizedBox("UPgRade UpgRade", 966, 586, 1204, 612)
        )
        val centres = WallPanel.priceRegions(panel, viewport).map { (it.left + it.right) / 2 }
        assertEquals(3, centres.size)
        assertTrue(centres.any { kotlin.math.abs(it - 1014) <= 30 })
    }

    @Test
    fun addOneIsAButtonsPitchLeftOfGold() {
        val gold = WallPanel.PurchaseButton("gold", Point(872, 599), 8_000_000L,
            RecognizedBox("8 000 000", 830, 508, 912, 532))
        assertEquals(Point(729, 599), WallPanel.addOneByPitch(mapOf("gold" to gold), viewport))
        assertNull(WallPanel.addOneByPitch(emptyMap(), viewport))
    }

    @Test
    fun upgradeMoreIsPressedOnItsWordNotOnTheGoldButtonItRanInto() {
        val alone = listOf(RecognizedBox("Upgrade More", 680, 586, 778, 612))
        assertEquals(Point(761, 599), WallPanel.upgradeMoreTap(alone, viewport))
        // "MORe UpgRade" spanning Upgrade More and the gold Upgrade: its middle is the gold button.
        val merged = listOf(RecognizedBox("MORe UpgRade", 700, 586, 940, 612))
        assertEquals(Point(740, 599), WallPanel.upgradeMoreTap(merged, viewport))
    }

    @Test
    fun aBulkTotalReadsAsAPrice() {
        assertEquals(16_000_000L, WallPanel.panelPrice("16 000 000"))
        assertEquals(16_000_000L, WallPanel.panelPrice("16 000 000 O"))
        assertEquals(16_000_000L, WallPanel.panelPrice("16000 000"))
        assertNull(WallPanel.panelPrice("160000 000"))
        assertEquals(8_000_000L, WallPanel.panelPrice("|8000 000C"))
    }

    /**
     * Blood's bulk panel at Level 18 on 15 October: the labels moved left to 753, 892 and 1033,
     * the third being the Wall Ring's, and both prices read as one box at 761.
     */
    private fun mergedPanel(prices: String, right: Int = 985) = listOf(
        RecognizedBox("Wall (Leveri8)x 1", 620, 465, 900, 495),
        RecognizedBox("Remove Wall", 330, 586, 426, 612),
        RecognizedBox("Add Wall", 612, 586, 708, 612),
        RecognizedBox(prices, 761, 508, right, 532),
        RecognizedBox("UpgRade", 753, 586, 849, 612),
        RecognizedBox("UpgRade", 892, 586, 988, 612),
        RecognizedBox("UpgRade", 1033, 586, 1129, 612)
    )

    /** The coin ends the gold price near 835 and the drop the elixir one near 975. */
    private val iconByPosition: (RecognizedBox) -> String? = { box ->
        val reach = box.left..box.right + 20
        when {
            835 in reach && 975 !in reach -> "gold"
            975 in reach && 835 !in reach -> "elixir"
            else -> null
        }
    }

    @Test
    fun twoPricesReadAsOneBoxAreSplitBetweenTheirLabels() {
        for (text in listOf("B000 00DC 8000 O00", "8000 000C 8000 000", "8 000 000 8 000 000")) {
            val purchases = WallPanel.purchaseButtons(mergedPanel(text), viewport, iconByPosition)
            assertEquals(text, Point(801, 599), purchases["gold"]?.tap)
            assertEquals(text, Point(940, 599), purchases["elixir"]?.tap)
            assertEquals(text, 8_000_000L, purchases["gold"]?.price)
            assertEquals(text, 8_000_000L, purchases["elixir"]?.price)
            assertTrue(text, purchases.values.none { it.tap.x >= 1033 })
        }
    }

    /** The ring's count on the price row has read as 8 and as B; B is dropped like an icon. */
    @Test
    fun aMergedBoxThatReachesTheRingIsNotSplit() {
        for (text in listOf("8000 000C 8000 000 8", "8000 000C 8000 000 B")) {
            val panel = mergedPanel(text, right = 1150)
            assertTrue(text, WallPanel.purchaseButtons(panel, viewport, iconByPosition).isEmpty())
        }
    }

    @Test
    fun aMergedBoxOverOneReadLabelIsNotSplit() {
        val panel = mergedPanel("8000 000C 8000 000").filterNot { it.left == 892 }
        assertTrue(WallPanel.purchaseButtons(panel, viewport, iconByPosition).isEmpty())
    }

    /**
     * The gold label merged with Add Wall's, as it has on Sweat and Blood, and both prices ran on
     * over the ring, whose count read as B. Only the elixir and ring labels are under the box, and
     * the ring is drawn in gold: a cut between them would make the ring a gold purchase button.
     */
    @Test
    fun aMergedBoxOverTheElixirAndRingLabelsIsNotSplit() {
        val panel = mergedPanel("8000 000C 8000 000 B", right = 1100)
            .filterNot { it.left == 612 || it.left == 753 } + RecognizedBox("Add Wall UpgRade", 622, 586, 849, 612)
        val ringInGold: (RecognizedBox) -> String? = { box ->
            if (1110 in box.left..box.right + 20) "gold" else iconByPosition(box)
        }
        assertTrue(WallPanel.purchaseButtons(panel, viewport, ringInGold).isEmpty())
    }

    /**
     * The elixir label unread and both prices run on to just past the ring's label: the labels
     * under the box are gold's and the ring's, and a cut between them leaves the elixir half
     * with no label near it, to be pressed beside the ring.
     */
    @Test
    fun aMergedBoxOverTheGoldAndRingLabelsIsNotSplit() {
        val panel = mergedPanel("B000 00DC 8000 000 B", right = 1088).filterNot { it.left == 892 }
        assertTrue(WallPanel.purchaseButtons(panel, viewport, iconByPosition).isEmpty())
    }

    @Test
    fun aMergedBoxThatIsNoPairOfPricesIsNotSplit() {
        assertTrue(WallPanel.purchaseButtons(mergedPanel("12 000 13 000"), viewport, iconByPosition).isEmpty())
    }

    @Test
    fun theTitleCountIsReadOnlyWhenTheTitleHasAnX() {
        assertEquals(1, WallPanel.titleCount(mergedPanel("8 000 000"), viewport))
        assertEquals(11, WallPanel.titleCount(listOf(RecognizedBox("Wall (Level 16) x 11", 660, 465, 940, 495)), viewport))
        assertNull(WallPanel.titleCount(singlePanel, viewport))
        assertNull(WallPanel.titleCount(singlePanel.filter { !it.text.contains("Level") }, viewport))
    }
}
