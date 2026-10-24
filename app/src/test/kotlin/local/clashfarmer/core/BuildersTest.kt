package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder counter and the builders-busy dialog, laid out from Blood's frames of 8 October
 * (audit-evidence/overnight/W1D-after-unverified.png): "0/1" at x 640-680, the builders' "0/6"
 * at x 795-845, both at y 40; the dialog's red X at (1060, 215) and its 212-gem button at
 * x 678-918, y 400-510.
 */
class BuildersTest {

    private val screen = UpgradeListFixture.screen

    private fun box(text: String, left: Int, top: Int, right: Int, bottom: Int) = RecognizedBox(text, left, top, right, bottom)

    private val raidCounter = box("0/1", 638, 26, 684, 56)
    private fun builders(text: String) = box(text, 795, 26, 845, 56)

    @Test fun theCounterParses() {
        assertEquals(BuilderCount(0, 6), Builders.parseCount("0/6"))
        assertEquals(BuilderCount(3, 6), Builders.parseCount("3/6"))
        assertEquals(BuilderCount(0, 6), Builders.parseCount("O/6"))
        assertEquals(BuilderCount(0, 6), Builders.parseCount("o / 6"))
        assertEquals(BuilderCount(5, 6), Builders.parseCount(" 5/6 "))
    }

    @Test fun anythingElseDoesNotParse() {
        for (text in listOf("", "6", "0/", "/6", "7/6", "10/40", "0/0", "29 000 000", "31M 50s", "a/6", "0/6/6")) {
            assertNull("'$text'", Builders.parseCount(text))
        }
    }

    @Test fun theBuildersAreReadBesideTheFaceAndNotTheRaidCounter() {
        val boxes = UpgradeListFixture.overlay + raidCounter + builders("0/6") + box("29 000 000", 1330, 28, 1470, 56)
        assertEquals(BuilderCount(0, 6), Builders.read(boxes, screen))
    }

    @Test fun theRaidCounterAloneIsUnreadable() {
        assertNull(Builders.read(listOf(raidCounter), screen))
        // The bot's own overlay counts attacks as N/M too.
        assertNull(Builders.read(UpgradeListFixture.overlay + box("1/6", 150, 70, 200, 92), screen))
    }

    @Test fun aCounterLowOnTheScreenIsNotTheTopBar() {
        assertNull(Builders.read(listOf(box("0/6", 795, 300, 845, 330)), screen))
    }

    @Test fun twoDisagreeingReadsAreUnreadable() {
        assertNull(Builders.read(listOf(builders("0/6"), box("2/6", 800, 30, 850, 58)), screen))
        assertEquals(BuilderCount(2, 6), Builders.read(listOf(builders("2/6"), box("2/6", 800, 30, 850, 58)), screen))
    }

    // --- one OCR line holding both top-bar counters ------------------------------------------
    // Blood read null at 11:05:48 UTC on 8 October with 0/6 on screen, and its busy-dialog frame
    // held "O/| 0/6" in sequence, so one line holding both counters is inferred; Sweat showed 1/6
    // (audit-evidence/overnight/soak3-end-home.png): the other builder's "0/1" near x 660 and the
    // builders' counter at x 790-840, y 40.

    @Test fun theBuildersAreReadOutOfALineJoinedWithTheOtherCounter() {
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/1 O/6", 638, 24, 845, 58)), screen))
        assertEquals(BuilderCount(1, 6), Builders.read(listOf(box("0/1 1/6", 638, 24, 845, 58)), screen))
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("o/1 o / 6", 638, 24, 845, 58)), screen))
        // A no-break space between the counters.
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/1 O/6", 638, 24, 845, 58)), screen))
        // Running on to the shield's timer, and from the far edge of the other counter's icon.
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/1 O/6 7H 42M", 600, 24, 1022, 58)), screen))
        assertEquals(BuilderCount(0, 6), Builders.read(UpgradeListFixture.overlay + box("O/| 0/6", 600, 24, 845, 58), screen))
    }

    @Test fun aLoneCounterBesideTheFaceReads() {
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(builders("o/6")), screen))
        assertEquals(BuilderCount(1, 6), Builders.read(listOf(builders("1/6")), screen))
        assertEquals(BuilderCount(0, 2), Builders.read(listOf(builders("0/2")), screen))
        assertEquals(BuilderCount(2, 7), Builders.read(listOf(builders("2/7")), screen))
    }

    @Test fun theOtherCounterIsNeverTheBuilders() {
        assertNull(Builders.read(listOf(box("O/1", 795, 26, 845, 56)), screen))
        assertNull(Builders.read(listOf(box("O/1 +", 638, 24, 1060, 58)), screen))
        assertNull(Builders.read(listOf(box("0/1 7H 41M", 638, 24, 1022, 58)), screen))
        assertNull(Builders.read(listOf(box("O/1", 638, 26, 684, 56)), screen))
    }

    @Test fun anImplausibleTotalIsNotTheBuilders() {
        assertNull(Builders.read(listOf(builders("0/8")), screen))
        assertNull(Builders.read(listOf(builders("0/9")), screen))
        assertNull(Builders.read(listOf(box("O/1 O/9", 638, 24, 845, 58)), screen))
    }

    @Test fun anAmbiguousLineIsUnreadable() {
        // Two plausible builder counts that differ.
        assertNull(Builders.read(listOf(box("0/6 1/6", 638, 24, 845, 58)), screen))
        assertNull(Builders.read(listOf(box("O/1 O/6", 638, 24, 845, 58), builders("1/6")), screen))
        // Glued counters: which glyphs are the builders' is a guess.
        assertNull(Builders.read(listOf(box("0/10/6", 638, 24, 845, 58)), screen))
        assertNull(Builders.read(listOf(box("0/67H", 638, 24, 845, 58)), screen))
        // A line that does not reach the face, and one low on the screen.
        assertNull(Builders.read(listOf(box("O/6", 600, 24, 720, 58)), screen))
        assertNull(Builders.read(listOf(box("O/1 O/6", 638, 300, 845, 330)), screen))
    }

    @Test fun aStrayLetterGluedBeforeTheCounterDoesNotHideIt() {
        // Blood, 13:19:46 UTC on 8 October: the top bar read "a0/6@743-840" with 0/6 on screen.
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("a0/6", 743, 24, 840, 58)), screen))
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/1 a0/6", 638, 24, 845, 58)), screen))
        assertEquals(BuilderCount(1, 6), Builders.read(listOf(box("e1/6", 743, 24, 840, 58)), screen))
        // The other counter's "O/|" is not a digit, so the builders' "0/6" glued after it reads.
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/|0/6", 638, 24, 845, 58)), screen))
    }

    @Test fun aDigitOrSlashGluedBeforeTheCounterHidesIt() {
        // "10/6" is not a 0/6 with a stray 1, and "/0/6" is the tail of something longer.
        assertNull(Builders.read(listOf(box("10/6", 743, 24, 840, 58)), screen))
        assertNull(Builders.read(listOf(box("/0/6", 743, 24, 840, 58)), screen))
        assertNull(Builders.read(listOf(box("O/1 10/6", 638, 24, 845, 58)), screen))
        assertNull(Builders.read(listOf(box("O/1 /0/6", 638, 24, 845, 58)), screen))
    }

    @Test fun aViewportOffsetIsSubtracted() {
        val shifted = Viewport(100, 50, 1700, 770)
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(box("O/1 O/6", 738, 74, 945, 108)), shifted))
        assertNull(Builders.read(listOf(box("O/1 O/6", 638, 74, 820, 108)), shifted))
    }

    @Test fun theTopBarIsLoggedWithItsSpan() {
        val boxes = UpgradeListFixture.overlay + box("O/1 O/6", 638, 24, 845, 58) + box("Attack!", 60, 650, 210, 705)
        assertEquals("Switching to Clash a...@110-345 | 10/40 attacks - 0 walls@110-310 | Pause@390-460 | O/1 O/6@638-845",
            Builders.topBarText(boxes, screen))
    }

    // --- the busy dialog -------------------------------------------------------------------

    private val busyBoxes = listOf(
        box("All builders are", 640, 190, 960, 222),
        box("busy!", 745, 225, 850, 258),
        box("Complete the previous building and free up a", 540, 308, 1055, 335),
        box("builder?", 750, 337, 845, 362),
        box("212", 740, 430, 812, 475)
    )
    private val closeX = box("X", 1040, 195, 1082, 238)

    @Test fun theBusyDialogIsRecognised() {
        assertTrue(Builders.isBusyDialog(busyBoxes + closeX, screen))
        assertTrue(Builders.isBusyDialog(busyBoxes, screen))
        // Only the body read, or only the title.
        assertTrue(Builders.isBusyDialog(busyBoxes.drop(2), screen))
        assertTrue(Builders.isBusyDialog(busyBoxes.take(2), screen))
        // "builder" and the gem price, with the rest misread.
        assertTrue(Builders.isBusyDialog(listOf(box("Comp1ete tHe prev building and fre", 540, 308, 1055, 335),
            box("builder?", 750, 337, 845, 362), box("212", 740, 430, 812, 475)), screen))
    }

    @Test fun aVillageIsNotTheBusyDialog() {
        val home = UpgradeListFixture.overlay + raidCounter + builders("0/6") + box("Attack!", 60, 650, 210, 705)
        assertFalse(Builders.isBusyDialog(home, screen))
        // "builder" alone, with no gem price on a button, is not enough.
        assertFalse(Builders.isBusyDialog(listOf(box("Builder's Hut", 700, 300, 900, 330)), screen))
    }

    // --- dialogs that are not the busy dialog (copied from ConfirmButtonTest) ---------------

    private val multiWallConfirm = listOf(
        box("Upgrade Walls", 570, 178, 1030, 220),
        box("Do you really want to upgrade the selected", 555, 298, 1050, 326),
        box("Walls For 4000000 Gold?", 660, 336, 945, 364),
        box("Cancel", 610, 444, 710, 478),
        box("Okay", 900, 444, 975, 478)
    )
    private val goldPassSingleWall = listOf(
        box("UpgRade Wall to Level 16?", 560, 150, 1040, 192),
        box("Hitpoints:", 600, 240, 700, 268),
        box("10 O00", 720, 240, 800, 268),
        box("o1 000", 830, 240, 910, 268),
        box("Wita tHe Gold Pass vOU COuld save up to:", 560, 330, 960, 358),
        box("800 000o", 970, 330, 1060, 358),
        box("CONfiRM", 640, 452, 760, 486),
        box("4 000 O00", 790, 452, 900, 486),
        box("Upgrade time NoNe", 600, 530, 820, 558)
    )

    @Test fun theMultiWallConfirmIsNotTheBusyDialog() {
        assertFalse(Builders.isBusyDialog(multiWallConfirm, screen))
        assertFalse(Builders.isBusyDialog(UpgradeListFixture.overlay + raidCounter + builders("0/6") + multiWallConfirm, screen))
    }

    @Test fun theGoldPassSingleWallConfirmIsNotTheBusyDialog() {
        assertFalse(Builders.isBusyDialog(goldPassSingleWall, screen))
        // The price split into digit groups by OCR, with no "builder" on the dialog.
        val split = goldPassSingleWall.filterNot { it.text == "4 000 O00" } +
            listOf(box("4", 790, 452, 805, 486), box("000", 812, 452, 850, 486), box("000", 858, 452, 900, 486))
        assertFalse(Builders.isBusyDialog(split, screen))
    }

    @Test fun theBuilderListIsNotTheBusyDialog() {
        val list = UpgradeListFixture.sweat()
        for (offset in 0..1400 step 50) {
            val boxes = UpgradeListFixture.boxes(list, offset)
            assertFalse("offset $offset", Builders.isBusyDialog(boxes, screen))
            // Every price split into its digit groups, "8 000 000" as "8", "000", "000".
            val split = boxes.flatMap { b ->
                if (b.left != 866 || !b.text.any(Char::isDigit)) listOf(b)
                else b.text.split(" ").mapIndexed { i, part -> box(part, 866 + i * 36, b.top, 896 + i * 36, b.bottom) }
            }
            assertFalse("split at offset $offset", Builders.isBusyDialog(split, screen))
        }
    }

    @Test fun theCloseTapIsTheRedXAndNeverTheGemButton() {
        val tap = Builders.closeTap(busyBoxes + closeX, screen)
        assertEquals(closeX.center, tap)
        assertFalse("$tap is on the gem button", tap!!.x in 660..940 && tap.y in 390..520)
    }

    @Test fun anUnreadXGivesNoTapAtAll() {
        // D3 F3: no fixed corner to tap; the caller presses Back instead.
        assertNull(Builders.closeTap(busyBoxes, screen))
        // A stray "x" read on the gem button must never be pressed.
        assertNull(Builders.closeTap(busyBoxes + box("x", 850, 440, 870, 470), screen))
    }
}
