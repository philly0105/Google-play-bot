package local.clashfarmer.recognition

import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values are the scout panel as it stood on 24 September: 695 227 gold, 953 189 elixir and
 * 9 371 dark elixir with 29s left, and a Next press priced at 1500.
 */
class LootPanelReaderTest {

    @Test fun aLootRowSettlesWhenTwoCutsAgree() {
        assertEquals(695_227L, LootPanelReader.lootAmount(listOf("695 227", "695227", "695 22")))
        assertEquals(9_371L, LootPanelReader.lootAmount(listOf("9 37I", "9 371")))
    }

    /**
     * Cuts of Sweat's loot rows from 1 to 8 October that did not settle because a one came back
     * as a bare bar. Each value is checked against what the battle then earned.
     */
    @Test fun aOneReadAsABarIsStillAOne() {
        // 8 Oct 03:19:06, dark elixir; the battle earned 11 657.
        assertEquals(11_657L, LootPanelReader.lootAmount(listOf("W6S7", "1| 657", "11 657", "IT 657")))
        // 8 Oct 03:01:08, gold; the battle earned 391 035.
        assertEquals(391_035L, LootPanelReader.lootAmount(listOf("SE0 I6E", "391 03S", "39] 03S", "39T 035-")))
        // 5 Oct, dark elixir; the battles earned 9 009 and 3 537, more than 1 180 or 1 850.
        assertEquals(11_180L, LootPanelReader.lootAmount(listOf("I1 180", "1| 180", "IT 180")))
        assertEquals(11_850L, LootPanelReader.lootAmount(listOf("1| 850", "I1 850")))
        // 6 Oct 03:00:42, gold; the battle earned 1 281 680.
        assertEquals(1_611_580L, LootPanelReader.lootAmount(listOf("1611 580", "I61| S80", "I611 \$80.")))
        // 6 Oct 03:09:06, gold left in battle; 889 475 five seconds later.
        assertEquals(971_831L, LootPanelReader.lootAmount(listOf(".\n97] 83I", "971 83I")))
    }

    /**
     * 8 Oct 03:19:54, gold left in battle. Two adjacent ones glue into one glyph at most cuts, so
     * "|] 972" and "|I 972" are "11 972" while a third cut shows a third one. Agreeing on the
     * shorter reading would be wrong by a factor of ten, so it stays unread.
     */
    @Test fun anAgreementAnotherCutShowsWithMoreOnesIsUnread() {
        assertNull(LootPanelReader.lootAmount(listOf("|] 972", "|I 972", "111 972")))
        assertNull(LootPanelReader.lootAmount(listOf("|] 972", "I 972", "I11 972")))
        assertNull(LootPanelReader.lootAmount(listOf("1 657", "1 657", "11 657")))
        assertNull(LootPanelReader.lootAmount(listOf("I| 816", "1T 816", "816")))
    }

    /**
     * 14 Oct 11:03:58, Sweat's scout gold. Two cuts read 628 916; a third split a one's flag into
     * a second glyph, "91I6". A group of four digits is no number, so it does not unread the row.
     */
    @Test fun aGroupOfFourDoesNotUnreadAnAgreedRow() {
        assertEquals(628_916L, LootPanelReader.lootAmount(listOf("628 96", "628 916", "628 91I6", "628 9I6")))
        // A well-formed reading with the extra one still does.
        assertNull(LootPanelReader.lootAmount(listOf("628 916", "628 9I6", "1628 916")))
    }

    /** 14 Oct 11:44:27, Sweat's scout gold; the five came back as "$" and as "S". */
    @Test fun aFiveReadAsADollarIsAFive() {
        assertEquals(506_933L, LootPanelReader.lootAmount(listOf("\$06 933", "S06 S33", "EEG 905:", "S06 933")))
    }

    /**
     * 8 Oct 10:31:03, Sweat's scout gold. Two cuts split a one's flag into "65S3"; read as
     * numbers they agree on 5 536 553 and outvote 553 653 by length.
     */
    @Test fun aCutThatIsNoNumberDoesNotVote() {
        assertEquals(553_653L, LootPanelReader.lootAmount(listOf("S\$3 65S3", "\$53 653", "S53 65S3", "553 6\$3")))
    }

    @Test fun aNumberThatLostItsLeadingGroupDoesNotVote() {
        // 3 Oct 08:18:17, Sweat's scout: "O14 652" is 1 014 652 without its one.
        assertEquals(1_014_652L, LootPanelReader.lootAmount(listOf("1014 6\$2", "O14 652", "1 014 652", "IO14 S2")))
        // 14 Oct 11:43:47: Sweat earned 1 089 398 gold (the bars rose by that plus the 270 000
        // bonus, less a 1 500 search) and it was recorded as 89 398. A read row's text is not
        // logged; this is what two cuts that lost the one would have returned.
        assertNull(LootPanelReader.lootAmount(listOf("089 398", "089 398", "1 089 398")))
        assertEquals(1_089_398L, LootPanelReader.lootAmount(listOf("089 398", "1 089 398", "I 089 398")))
    }

    /** 5 Oct 11:24:26: a gold-row cut read nothing but "|". That is no reading, not a 1. */
    @Test fun aCutOfOnlyABarIsNoReading() {
        assertNull(LootPanelReader.lootAmount(listOf("|", "|", "391 035", "39I 0S5")))
        assertNull(LootPanelReader.lootAmount(listOf("]", "[", "|")))
    }

    @Test fun aLootRowNoCutAgreesOnIsUnknownNotZero() {
        assertNull(LootPanelReader.lootAmount(listOf("695 227", "695 221")))
        assertNull(LootPanelReader.lootAmount(emptyList()))
        assertNull(LootPanelReader.lootAmount(listOf("", "...")))
    }

    @Test fun anEmptyStorageReadsAsARealZero() {
        assertEquals(0L, LootPanelReader.lootAmount(listOf("0", "0")))
        // A lost group is not zero.
        assertNull(LootPanelReader.lootAmount(listOf("000", "000")))
    }

    /**
     * 14 Oct 08:46-08:48, Sweat's gold left in battle (r4-log-run3.jsonl:1155-1202). 257 137 was
     * left, 781 535 scouted less 524 398 earned, yet the reader of that day took 667, 5 265 and
     * 2 560 on the way. Its 13 s cuts were logged, and its first two are what it took for 5 265.
     */
    @Test fun theRoundFourBattleCutsThatReadWrongStayUnread() {
        assertNull(LootPanelReader.lootAmount(listOf("S2E 65t", "S2E 6St", "S2E 6Sti")))
        assertNull(LootPanelReader.lootAmount(listOf("S2E 65t", "S2E 6St")))
        // The same row's clean cuts, :1184 and :1186.
        assertEquals(257_137L, LootPanelReader.lootAmount(listOf("257 137\n1", "257 137")))
        // Rows of a later run that reader would have taken for 1 601 and 8 051 (:2395, :2479).
        assertNull(LootPanelReader.lootAmount(listOf("10 391", "I6E O1", "I6E OI")))
        assertNull(LootPanelReader.lootAmount(listOf("80 5EI:", "35 087", "L80 SEI", "135 087")))
    }

    /**
     * A letter left over after the repairs stood for a digit. Two such cuts agree on a number
     * short of that digit, so neither votes.
     */
    @Test fun aCutThatStillHoldsALetterDoesNotVote() {
        // 14 Oct, Sweat's scout elixir, 118 375; two of eighteen probe cuts of r4-08-sweat-scout-b.png.
        assertNull(LootPanelReader.lootAmount(listOf("W8 375", "W8 375*")))
        assertEquals(118_375L, LootPanelReader.lootAmount(listOf("W8 375", "W8 375*", "118 375", "I18 37S")))
        // 14 Oct, Sweat's scout gold at the old place, 627 363; three of eighteen probe cuts.
        assertNull(LootPanelReader.lootAmount(listOf("b27 363", "b27 363", "b27 363")))
        // A word is no number, though its i reads as a one.
        assertNull(LootPanelReader.lootAmount(listOf("Maintenance", "Maintenance")))
    }

    @Test fun theCountdownsSecondsMarkIsReadAsItsLastCharacter() {
        assertEquals(29, LootPanelReader.countdownSeconds(listOf("29s", "29 s")))
        assertEquals(20, LootPanelReader.countdownSeconds(listOf("20s", "2Os")))
        // What the phone's recogniser returned for the 24 September frames: mostly a 5 for the s.
        assertEquals(29, LootPanelReader.countdownSeconds(listOf("295 ", "29s ", "295 ", "295 ")))
        assertEquals(20, LootPanelReader.countdownSeconds(listOf("205 ", "20s ", "205 ", "-205 ")))
        // A dropped s makes 25s read as 2 - too few seconds, never too many.
        assertEquals(2, LootPanelReader.countdownSeconds(listOf("25", "25")))
        assertNull(LootPanelReader.countdownSeconds(listOf("s", "5")))
    }

    @Test fun aCountdownTheScoutCannotShowIsUnknown() {
        assertNull(LootPanelReader.countdownSeconds(listOf("45s", "45s")))
        assertNull(LootPanelReader.countdownSeconds(listOf("29s")))
        assertNull(LootPanelReader.countdownSeconds(listOf("29s", "28s")))
    }

    @Test fun theNextFeeIsTheAgreedPositiveNumber() {
        assertEquals(1_500L, LootPanelReader.nextFee(listOf("1500", "15DD", "I500")))
        assertNull(LootPanelReader.nextFee(listOf("0", "0")))
        assertNull(LootPanelReader.nextFee(listOf("1500", "15000")))
        assertNull(LootPanelReader.nextFee(listOf("1500000", "1500000")))
    }

    @Test fun aNewBaseIsOneWhoseReadLootDiffers() {
        val first = mapOf(Resource.GOLD to 695_227L, Resource.ELIXIR to 953_189L, Resource.DARK_ELIXIR to 9_371L)
        val second = mapOf(Resource.GOLD to 560_271L, Resource.ELIXIR to 463_200L, Resource.DARK_ELIXIR to 5_355L)
        assertTrue(LootPanelReader.differentBase(first, second))
        assertFalse(LootPanelReader.differentBase(first, first))
        // Nothing read on either side is no evidence of a new base.
        assertFalse(LootPanelReader.differentBase(first, emptyMap()))
        assertFalse(LootPanelReader.differentBase(emptyMap(), emptyMap()))
        // One row that did not read this time does not hide a change in another.
        assertTrue(LootPanelReader.differentBase(first, mapOf(Resource.GOLD to 560_271L)))
    }
}
