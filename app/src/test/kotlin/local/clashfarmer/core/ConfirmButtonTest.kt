package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmButtonTest {

    /** The lines Clash's "Upgrade Walls" dialog actually put on screen, left to right. */
    private val dialogBoxes = listOf(
        RecognizedBox("Upgrade Walls", 570, 178, 1030, 220),
        RecognizedBox("Do you really want to upgrade the selected", 555, 298, 1050, 326),
        RecognizedBox("Walls For 4000000 Gold?", 660, 336, 945, 364),
        RecognizedBox("Cancel", 610, 444, 710, 478),
        RecognizedBox("Okay", 900, 444, 975, 478)
    )

    @Test
    fun picksOkayAndNotCancel() {
        val box = WallPanel.findConfirmButton(dialogBoxes)
        assertEquals("Okay", box?.text)
        assertEquals(Point(937, 461), box?.center)
    }

    @Test
    fun ignoresTheSentenceThatNamesThePrice() {
        // The previous version searched for "confirm", found nothing, and fell back to whichever
        // box held the price - the sentence in the middle of the dialog, nowhere near a button.
        assertNull(WallPanel.findConfirmButton(dialogBoxes.filter { it.text != "Okay" }))
    }

    /**
     * The other dialog. A single wall gets this one, and it never spells its currency out: the
     * cost is on the accept button with the coin drawn beside it as an icon. Read left to right,
     * top to bottom, as the recogniser returned it at 13:05 on 17 September.
     */
    private val singleWallBoxes = listOf(
        RecognizedBox("UpgRade Wall to Level 16?", 560, 150, 1040, 192),
        RecognizedBox("Hitpoints:", 600, 240, 700, 268),
        RecognizedBox("10 O00", 720, 240, 800, 268),
        RecognizedBox("o1 000", 830, 240, 910, 268),
        RecognizedBox("Wita tHe Gold Pass vOU COuld save up to:", 560, 330, 960, 358),
        RecognizedBox("800 000o", 970, 330, 1060, 358),
        RecognizedBox("CONfiRM", 640, 452, 760, 486),
        RecognizedBox("4 000 O00", 790, 452, 900, 486),
        RecognizedBox("Upgrade time NoNe", 600, 530, 820, 558)
    )

    @Test
    fun readsThePriceOffTheAcceptButtonWhenNoCurrencyIsNamed() {
        val confirm = WallPanel.findConfirmButton(singleWallBoxes)
        assertEquals("CONfiRM", confirm?.text)
        assertEquals(4_000_000L, WallPanel.priceOnButton(singleWallBoxes, confirm))
    }

    @Test
    fun theGoldPassPitchIsNotThePrice() {
        // "With the Gold Pass you could save up to: 800 000" is the only other large number on
        // the dialog, and it is the one that would have been read as the cost of the wall. It
        // sits a row above the button.
        val confirm = WallPanel.findConfirmButton(singleWallBoxes)
        assertNotEquals(800_000L, WallPanel.priceOnButton(singleWallBoxes, confirm))
    }

    @Test
    fun theHitpointsAreNotThePriceEither() {
        val boxes = singleWallBoxes.filter { it.text != "4 000 O00" }
        val confirm = WallPanel.findConfirmButton(boxes)
        // Nothing shares the button's row once the price is gone, so there is no price to read
        // and the caller stops rather than pressing an unknown amount.
        assertNull(WallPanel.priceOnButton(boxes, confirm))
    }

    @Test
    fun readsTheLevelTheDialogIsBuying() {
        assertEquals(18, WallPanel.targetLevel("UpgRade Wall to Level 18? Hitpoints: 12 000 13 000"))
        assertEquals(16, WallPanel.targetLevel("UpgRade Wall to Level 16?"))
        assertNull(WallPanel.targetLevel("Do you really want to upgrade the selected Walls?"))
    }

    @Test
    fun takesTheHighestLevelOnScreen() {
        // The village behind the dialog carries a "Wall Level 16" tag of its own, and the note
        // inside it mentions a Town Hall level. Declining too often costs a wall; pressing on a
        // wall past the cap spends dark elixir, so the highest number wins.
        val text = "Wall Level 16 UpgRade Wall to Level 18? You need to upgrade your Town Hall to level 17!"
        assertEquals(18, WallPanel.targetLevel(text))
    }

    @Test
    fun spotsTheReasonAPurchaseCannotGoThrough() {
        assertTrue(WallPanel.isBlocked("Note! You need to upgrade your Town Hall to level 17!"))
        assertFalse(WallPanel.isBlocked("Walls are great For keeping your village safe"))
    }

    @Test
    fun darkElixirIsNotElixir() {
        assertTrue(WallPanel.namesCurrency("Walls For 5000000 Elixir?", "elixir"))
        assertFalse(WallPanel.namesCurrency("Walls For 7000000 Dark Elixir?", "elixir"))
        assertTrue(WallPanel.namesCurrency("Walls For 4000000 Gold?", "gold"))
        assertFalse(WallPanel.namesCurrency("Walls For 4000000 Gold?", "elixir"))
    }

    @Test
    fun readsAZeroAsTheLeadingLetter() {
        assertEquals("0kay", WallPanel.findConfirmButton(listOf(RecognizedBox("0kay", 900, 444, 975, 478)))?.text)
    }

    @Test
    fun aDoubledLetterStillNamesTheAcceptButton() {
        // The button that stopped the 21:10 wall phase on 17 September, with the price beside it
        // exactly as the reader returned both: a doubled M, a 5 read as S and zeros as O.
        val boxes = listOf(
            RecognizedBox("UpgRade Wall to Level 17?", 560, 180, 1040, 220),
            RecognizedBox("CONfiRMM", 740, 590, 860, 625),
            RecognizedBox("S O00 000", 880, 592, 1010, 622)
        )
        val confirm = WallPanel.findConfirmButton(boxes)
        assertEquals("CONfiRMM", confirm?.text)
        assertEquals(5_000_000L, WallPanel.priceOnButton(boxes, confirm))
    }

    @Test
    fun aDoubledLetterDoesNotTurnCancelIntoAnAcceptButton() {
        val boxes = listOf(RecognizedBox("CaNCell", 610, 444, 710, 478))
        assertNull(WallPanel.findConfirmButton(boxes))
        assertEquals("CaNCell", WallPanel.findCancelButton(boxes)?.text)
    }

    @Test
    fun aDottedCapitalIStillNamesTheAcceptButton() {
        // The button that stopped every gold purchase of the 05:02 wall phase on 19 September.
        // The reader spelled the I of CONFIRM as U+0130, which lowercases to an i with a
        // combining dot; the dot is not a letter, so the label split into "confi" and "rm".
        val boxes = listOf(
            RecognizedBox("UpgRade Wall to Level 18?", 560, 180, 1040, 220),
            RecognizedBox("CONFİRM", 740, 590, 860, 625),
            RecognizedBox("S600 O00", 880, 592, 1010, 622)
        )
        val confirm = WallPanel.findConfirmButton(boxes)
        assertEquals("CONFİRM", confirm?.text)
        assertEquals(5_600_000L, WallPanel.priceOnButton(boxes, confirm))
    }
}
