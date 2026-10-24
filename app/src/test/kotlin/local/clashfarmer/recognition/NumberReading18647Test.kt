package local.clashfarmer.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What ML Kit returned for the numbers of Clash 18.647, read on the phone from frames of 14
 * October (r4-probe-results-1.json). Village readings are in the order the balance masks run:
 * cuts 215, 230 and 240, then white 150, 180 and 210, each at gaps 2 and 3. Loot readings are in
 * the scout cuts' order, 215, 200, 230 and 185.
 */
class NumberReading18647Test {

    /** The balance reader's path from text to a settled number, after the masks. */
    private fun balanceDigits(raws: List<String>) = raws
        .map { ScreenReader.normalizeAmountText(ScreenReader.leadingOne(it)).replace(Regex("[^0-9]"), "") }
        .filter { it.isNotEmpty() }

    private fun balance(vararg raws: String): Long? {
        val digits = balanceDigits(raws.toList())
        assertFalse("asked for another frame: $digits", ScreenReader.needsAnotherFrame(digits))
        return ScreenReader.agreedBalance(digits)
    }

    /** The same path with the readings that lack the number's shape left out, as the reader does. */
    private fun shapedBalance(vararg raws: String): Long? = balance(*shaped(raws))

    private fun shaped(raws: Array<out String>) =
        raws.filter { ScreenReader.numberShaped(ScreenReader.normalizeAmountText(ScreenReader.leadingOne(it))) }.toTypedArray()

    /**
     * readBalanceWithAgreement on a frame that does not change: a second frame, the same readings
     * again, when the voting readings or all of them show a collapsed pair of ones.
     */
    private fun pooledBalance(vararg raws: String): Long? {
        val every = balanceDigits(raws.toList())
        val voting = balanceDigits(shaped(raws).toList())
        val second = ScreenReader.needsAnotherFrame(voting) || ScreenReader.needsAnotherFrame(every)
        return ScreenReader.agreedBalance(if (second) voting + voting else voting)
    }

    @Test
    fun bloodsVillageReadsEveryDigit() {
        // The old cuts settled this gold on 517 215 at 08:43:08; two ones touch in the middle.
        assertEquals(5_172_115L, balance(
            "S 172 1S", "S 172 1S", "5 172 1IS", "S 172 115", "S 172 115", "5 172 I15", "5 172 I1S", "S 172 US", "5 172 IUS"))
        assertEquals(5_678_403L, balance(
            "5 678 403", "S678 403", "S 678 403", "S 678 403", "5 678 403", "5 678 403", "5678 403", "5678 403", "5678 403"))
        assertEquals(180_940L, balance(
            "180 94O", "180 940", "180 940", "180 940", "180 940", "180 940", "180 940", "180 940", "180 940"))
    }

    @Test
    fun sweatsEightDigitBalancesKeepEveryDigit() {
        assertEquals(12_474_588L, balance(
            "12474 588 (", "12 474 588", "12 474 588", "12474 S88", "12474 588", "12 474 588", "12 474 588", "12 474 588", "12 474 588"))
        assertEquals(13_144_603L, balance(
            "13 144 603", "13 144 603", "", "13 144 603", "13 144 603", "13 144 603", "13 144 603", "13 144 603", "13 144 603"))
        assertEquals(298_542L, balance(
            "298 542", "25 862", "298 542", "298 S42", "298 542", "298 542", "298 542", "298 542", "298 542"))
    }

    @Test
    fun aLeadingPairOfOnesIsNotLost() {
        // Sweat's frame of round 2: 11 746 821 elixir, whose two leading ones the old cuts read
        // as one three times out of eight.
        assertEquals(11_746_821L, balance(
            "| 746 821", "1 746 821", "I1 746 821", "11 746 821", "11 746 821", "11 746 821", "I1 746 821", "1| 746 821", "1| 74 821"))
        assertEquals(10_849_025L, balance(
            "I0 849 o25", "IO 349 025", "I0 849 025", "10 849 025", "I0 849 025", "I0 84S 025", "10 849 025", "I0 849 025", "I0 849 025"))
        assertEquals(279_179L, balance(
            "279 179", "279 179", "279 179", "279 19", "279 179", "279 179", "279 179", "279 179", "279 179"))
    }

    @Test
    fun anOlderVillageStillReads() {
        assertEquals(7_158_904L, balance(
            "7 158 9p4", "7 158 s04", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904"))
        assertEquals(7_008_100L, balance(
            "7 008 100", "O01 8O0", "7 008 100", "7 008 i00", "7 O08 İ00", "7 008 I00", "7 003 100", "7 O08 100", "7008 100"))
        assertEquals(249_035L, balance(
            "249 D35", "249 035", "249 035", "249 035", "249 035", "249 035", "249 035", "249 035", "249 035"))
    }

    @Test
    fun aLeadingTIsAOne() {
        assertEquals("1588 988", ScreenReader.leadingOne("T588 988"))
        assertEquals("1B0 940", ScreenReader.leadingOne("TB0 940"))
        assertEquals("1S88 988", ScreenReader.leadingOne(" TS88 988"))
        // Only the first character; a T anywhere else is still the 7 normalisation makes it.
        assertEquals("1T 816", ScreenReader.leadingOne("1T 816"))
        assertEquals("7 008 TOo", ScreenReader.leadingOne("7 008 TOo"))
        assertEquals("7008700", ScreenReader.normalizeAmountText("7 008 TOo").replace(" ", ""))
    }

    @Test
    fun sweatsScoutRowsReadAtTheirNewPlace() {
        assertEquals(627_363L, LootPanelReader.lootAmount(listOf("627 363", "627 363", "627 363", "527 363")))
        assertEquals(563_870L, LootPanelReader.lootAmount(listOf("S63 870", "S63 870", "S63 870", "S63 80")))
        assertEquals(8_498L, LootPanelReader.lootAmount(listOf("8 498", "8 498", "8 498", "8498")))
        assertEquals(141_495L, LootPanelReader.lootAmount(listOf("14| 495", "141 49S", ":141 495", "141495")))
        assertEquals(118_375L, LootPanelReader.lootAmount(listOf("W8 375*", "I18 37S", "118 37S", "I18 375 :")))
        assertEquals(2_288L, LootPanelReader.lootAmount(listOf("2 288", "2 288", "2 288", "2 288")))
        assertEquals(48_260L, LootPanelReader.lootAmount(listOf("48 26O", "48 26O", "- 48 260", "48 26O")))
        assertEquals(46_175L, LootPanelReader.lootAmount(listOf("46 175", "46 175", "46 175", "46 175")))
        assertEquals(3_001L, LootPanelReader.lootAmount(listOf("3 001", "3 001", "3 001", "3001")))
    }

    @Test
    fun theBattlesLootLeftReadsWithALeadingOne() {
        assertEquals(1_596_657L, LootPanelReader.lootAmount(listOf("1\$96 657", "1\$96 657", ":1596 657", "1596 657")))
        // Read as 7 588 988 while every leading T was a 7.
        assertEquals(1_588_988L, LootPanelReader.lootAmount(listOf("1 \$88 988", "T588 988", "886 885 I", "TS88 988")))
        assertEquals(19_586L, LootPanelReader.lootAmount(listOf("19 S86", "19 586", "19 586", "19 586")))
    }

    @Test
    fun theOldRowsClipTheDigitsTop() {
        // Sweat's calibrated rows start on the digits' top line since 18.647. 141 495 gold came
        // back upside down from three cuts, which agreed on 5 611 until a group of two stopped
        // counting as a number; the battle's 1 596 657 never read the same way twice.
        assertNull(LootPanelReader.lootAmount(listOf("S6t ltl:", "S6t ltbl", "S6t lbl-")))
        assertNull(LootPanelReader.lootAmount(listOf("596 551", "59 96S", "I596 557", "1595 557")))
    }

    @Test
    fun spacedColumnsPutBlankColumnsInFrontOfEachGlyph() {
        val ink = booleanArrayOf(false, true, true, false, false, true, false)
        assertArrayEquals(
            intArrayOf(-1, -1, 0, -1, -1, 1, 2, 3, 4, -1, -1, 5, 6, -1, -1),
            ImageMatcher.spacedColumns(ink, gap = 2)
        )
        // A glyph that starts the strip already has the side margin.
        assertArrayEquals(
            intArrayOf(-1, -1, 0, 1, -1, -1, -1, 2, -1, -1),
            ImageMatcher.spacedColumns(booleanArrayOf(true, false, true), gap = 3)
        )
        assertArrayEquals(intArrayOf(-1, -1, -1, -1), ImageMatcher.spacedColumns(BooleanArray(0), gap = 2))
    }

    /**
     * Blood's village of 15 October after three walls at 8 000 000 elixir, read by the probe on
     * the phone (audit-evidence/uo20/probe-results.json). Two masks split one zero of the elixir
     * into "O00D", their eight digits outvoted six masks on length, and a real purchase stopped a
     * two-hour run as unproven.
     */
    @Test
    fun aZeroSplitInTwoDoesNotOutvoteTheBalance() {
        val elixir = arrayOf(
            "S O00D O00", "000 000 S", "S 000 000", "S000 0o0", "S 000 O00", "5 000 000", "5000 000", "S O00 O00", "5 O00D O00")
        assertEquals(50_000_000L, balance(*elixir))
        assertEquals(5_000_000L, shapedBalance(*elixir))
        // The same frame's gold and dark elixir read right either way.
        val gold = arrayOf(
            "30 D0O O00 (", "30 000 D00", "30 D0O O00", "30 O00 000", "30 O0O O0O", "30 O00 000", "30 00 O00", "30 D0O O00D", "30 00O O0D")
        assertEquals(30_000_000L, balance(*gold))
        assertEquals(30_000_000L, shapedBalance(*gold))
        val darkElixir = arrayOf(
            "470 000", "470 000", "470 000", "470 000", "470 000", "470 000", "470 000", "470 OD0", "470 000")
        assertEquals(470_000L, balance(*darkElixir))
        assertEquals(470_000L, shapedBalance(*darkElixir))
    }

    @Test
    fun aReadingHasTheNumbersShapeWhenEveryLaterGroupIsThreesOrSix() {
        assertTrue(ScreenReader.numberShaped("5 000 000"))
        // The first group can be any length, and a lost space leaves a group of six.
        assertTrue(ScreenReader.numberShaped("5000 000"))
        assertTrue(ScreenReader.numberShaped("12 474588"))
        assertTrue(ScreenReader.numberShaped("4 000000:"))
        // No space, or a stray mark, is no evidence against it.
        assertTrue(ScreenReader.numberShaped("5000000"))
        assertTrue(ScreenReader.numberShaped("0"))
        assertTrue(ScreenReader.numberShaped("- 48 260"))
        // One zero split in two, two zeros merged into one, the five read last.
        assertFalse(ScreenReader.numberShaped("5 0000 000"))
        assertFalse(ScreenReader.numberShaped("30 00 000"))
        assertFalse(ScreenReader.numberShaped("000 000 5"))
    }

    /**
     * The village bars above in which the shape rule leaves a reading out still settle on the
     * same number. Every reading it leaves out was wrong: a one lost ("S 172 1S", "1| 74 821",
     * "279 19"), a letter it did not repair ("S 172 US", "7 158 9p4", "7 O08 İ00").
     */
    @Test
    fun theShapeRuleOnlyDropsWrongReadingsOfTheEarlierVillages() {
        assertEquals(5_172_115L, shapedBalance(
            "S 172 1S", "S 172 1S", "5 172 1IS", "S 172 115", "S 172 115", "5 172 I15", "5 172 I1S", "S 172 US", "5 172 IUS"))
        assertEquals(11_746_821L, shapedBalance(
            "| 746 821", "1 746 821", "I1 746 821", "11 746 821", "11 746 821", "11 746 821", "I1 746 821", "1| 746 821", "1| 74 821"))
        assertEquals(279_179L, shapedBalance(
            "279 179", "279 179", "279 179", "279 19", "279 179", "279 179", "279 179", "279 179", "279 179"))
        assertEquals(7_158_904L, shapedBalance(
            "7 158 9p4", "7 158 s04", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904", "7 158 904"))
        assertEquals(7_008_100L, shapedBalance(
            "7 008 100", "O01 8O0", "7 008 100", "7 008 i00", "7 O08 İ00", "7 008 I00", "7 003 100", "7 O08 100", "7008 100"))
    }

    /**
     * Nine of the probe's cuts of Blood's 5 172 115 gold on 14 October, older masks among them.
     * Six glue the pair of ones in its last group ("S 172 1S"), which leaves a group of two, so
     * they do not vote; the one cut that read it whole has no partner. Those six still show the
     * whole reading is a pair put back, so a second frame is read and settles it.
     */
    @Test
    fun onesGluedInsideALaterGroupStillAskForASecondFrame() {
        val gold = arrayOf(
            "S 172 1S", "S 172 1IS (", "S 172 1S", "S 172 S", "S 172 1S", "5 172 IT", "S 172 IS", "5 172 IS", "S 172 15")
        assertNull(shapedBalance(*gold))
        assertEquals(5_172_115L, pooledBalance(*gold))
    }

    /**
     * Made up from the two misreads together: 11 000 000 with its leading ones glued by most cuts
     * and one zero split by two. The split pair wins the old vote on length, so all the readings
     * together show no collapsed pair; only the voting readings do.
     */
    @Test
    fun aSplitZeroDoesNotHideACollapsedPairOfOnes() {
        val elixir = arrayOf(
            "1 000 000", "1 000 000", "1 000 000", "1 000 000", "1 000 000", "11 000 000", "1 0000 000", "1 O00D 000", "1 000 000")
        assertEquals(10_000_000L, balance(*elixir))
        assertEquals(11_000_000L, pooledBalance(*elixir))
    }

    /**
     * The recorded price and post-purchase balance of AmountTextTest (17 September) keep every
     * vote they had: the only reading the rule leaves out, "D00 0000", is a lost group the zero
     * rule already threw away.
     */
    @Test
    fun theShapeRuleCostsTheRecordedPricesNoVote() {
        fun digits(raws: List<String>) = raws.map { ScreenReader.normalizeAmountText(it) }
            .filter { ScreenReader.numberShaped(it) }
            .map { it.replace(Regex("[^0-9]"), "") }
            .filter { it.isNotEmpty() && it.toLong() != 0L }
        val price = digits(listOf("A0DD 0DD", "D00 0000", "40DD0DD", "A0DD00D", "4 000000:", "D00 000"))
        assertEquals(List(4) { "4000000" }, price)
        assertEquals(4_000_000L, ScreenReader.agreedAmount(price))
        val elixir = digits(listOf(
            "1 034 762", "F034762", "44 034 762", "1034 762", "1 034 762", "1034 762", "P034762", "1 034 762"))
        assertEquals(8, elixir.size)
        assertEquals(1_034_762L, ScreenReader.agreedAmount(elixir))
    }
}
