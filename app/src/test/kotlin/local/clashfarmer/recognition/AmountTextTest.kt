package local.clashfarmer.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings in here are what ML Kit actually returned for the gold cost on a live
 * "Wall (Level 15)" panel, logged by `amount_unreadable` on 17 September 2026. The wall cost
 * 4 000 000 and the phase priced it at 400, then at 0.
 */
class AmountTextTest {

    private fun digitsOf(raw: String) =
        ScreenReader.normalizeAmountText(raw).replace(Regex("[^0-9]"), "")

    @Test
    fun clashDigitsThatComeBackAsLettersAreRecovered() {
        // Zero reads as D, four reads as A.
        assertEquals("4000000", digitsOf("A0DD 0DD"))
        assertEquals("4000000", digitsOf("40DD0DD"))
        assertEquals("4000000", digitsOf("A0DD00D"))
        assertEquals("4000000", digitsOf("4 000000:"))
    }

    @Test
    fun aLostDigitGroupStillReadsShort() {
        // These are misreads and must stay short so the length guard rejects them; the point
        // is that normalisation does not invent the missing group.
        assertEquals("0000000", digitsOf("D00 0000"))
        assertEquals("000000", digitsOf("D00 000"))
    }

    @Test
    fun theAgreementRuleSettlesOnTheRealAmount() {
        // The full sequence for the wall cost, in the order the thresholds produced it.
        val raws = listOf("A0DD 0DD", "D00 0000", "40DD0DD", "A0DD00D", "4 000000:", "D00 000")
        val digits = raws.map { digitsOf(it) }.filter { it.isNotEmpty() && it.toLong() != 0L }
        assertEquals(4_000_000L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun aSingleLongSmearDoesNotOutrankSixAgreeingReads() {
        // The elixir bar after a purchase: one soft cut glued a scrap of the icon onto the front
        // and produced "44 034 762", which used to veto every correct reading behind it.
        val raws = listOf(
            "1 034 762", "F034762", "44 034 762", "1034 762",
            "1 034 762", "1034 762", "P034762", "1 034 762"
        )
        val digits = raws.map { digitsOf(it) }.filter { it.isNotEmpty() && it.toLong() != 0L }
        assertEquals(1_034_762L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun aDroppedDigitGroupStillLosesToTheFullReading() {
        // The original failure: a harsh cut lost a group, and "616661" agreed with itself.
        val digits = listOf("616661", "6116661", "616661", "6116661")
        assertEquals(6_116_661L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun aLoneCollapsedDoubleAsksForAnotherFrame() {
        // The gold bar at 13:05 on 17 September: the screen said 11 831 452 and all but one cut
        // glued the two leading ones into a single glyph. One reading is not enough to add a
        // digit, so the frame is ambiguous and another frame has to settle it.
        val digits = listOf("1831452", "1831452", "11831452", "1831452")
        assertEquals(1_831_452L, ScreenReader.agreedAmount(digits))
        assertTrue(ScreenReader.needsAnotherFrame(digits))
    }

    @Test
    fun aSecondFrameSettlesTheCollapsedDouble() {
        val first = listOf("1831452", "1831452", "11831452", "1831452")
        val second = listOf("1831452", "11831452", "1831452")
        assertEquals(11_831_452L, ScreenReader.agreedAmount(first + second))
    }

    @Test
    fun aClearReadingNeedsNoSecondFrame() {
        assertFalse(ScreenReader.needsAnotherFrame(listOf("1034762", "1034762", "44034762")))
        assertFalse(ScreenReader.needsAnotherFrame(listOf("4013597", "4013597", "40135597")))
        assertFalse(ScreenReader.needsAnotherFrame(listOf("1034", "44034762")))
    }

    @Test
    fun aBalanceCanBeARealZero() {
        // Sweat's elixir sat at 0 on 23 September. A price never reads zero; a balance can.
        assertEquals(0L, ScreenReader.agreedBalance(listOf("0", "0", "0")))
        assertNull(ScreenReader.agreedAmount(listOf("0", "0", "0")))
        assertEquals(1_034_762L, ScreenReader.agreedBalance(listOf("1034762", "1034762", "44034762")))
        assertNull(ScreenReader.agreedBalance(listOf("1034762", "1034")))
    }

    @Test
    fun aDoubledGlyphDoesNotInflateABalanceTenfold() {
        // The elixir bar at 20:26 on 17 September held 16 605 162. One cut split a 5 into two,
        // and because deleting one of that pair gives the consensus back exactly, the
        // doubled-glyph rule took the longer reading as the truth and reported 166 055 162.
        // No storage holds nine digits, so that reading is not a candidate at all.
        val digits = listOf("16605162", "16605162", "166055162", "16605162")
        assertEquals(16_605_162L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun aSmearIsNotACollapsedDouble() {
        // The same shape of evidence - one long reading against several short ones - but the
        // extra character is not a twin of its neighbour and no single deletion recovers the
        // consensus, so the consensus stands.
        val digits = listOf("1034762", "1034762", "44034762", "1034762")
        assertEquals(1_034_762L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun anIconDigitOnTheEndIsNotPutBack() {
        // A trailing scrap of the coin reads as one more zero. It is a deletion away from the
        // consensus, but the character beside it is a 2, not another zero, so it is not the
        // doubled-glyph case and must not inflate the balance tenfold.
        val digits = listOf("1034762", "1034762", "10347620", "1034762")
        assertEquals(1_034_762L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun roundBalanceTrailingIconZeroIsNotAppended() {
        // The icon beside a round balance reads as a zero next to the last zero, which is a
        // doubled glyph by shape and a tenfold balance by value.
        val digits = listOf("4000000", "4000000", "40000000", "4000000", "4000000")
        assertEquals(4_000_000L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun singleDoubledDigitDoesNotOverruleConsensus() {
        // 22 September: 4 013 597 settled as 40 135 597 and 5 821 143 as 55 821 143.
        assertEquals(
            4_013_597L,
            ScreenReader.agreedAmount(listOf("4013597", "4013597", "40135597", "4013597"))
        )
        assertEquals(
            5_821_143L,
            ScreenReader.agreedAmount(listOf("5821143", "55821143", "5821143"))
        )
    }

    @Test
    fun twoDisagreeingLongReadsDoNotHideTheAgreementBelowThem() {
        // The gold bar at 13:40 on 17 September. Two nine-digit smears disagreed with each other
        // and used to end the search there, discarding the eight-digit reading three cuts had
        // agreed on, and the session stopped mid wall phase.
        val digits = listOf(
            "157608651", "45608651", "19", "915608651",
            "15608651", "15608651", "5608651", "15608651"
        )
        assertEquals(15_608_651L, ScreenReader.agreedAmount(digits))
    }

    @Test
    fun theCoinRimAfterTheLastGroupIsDropped() {
        // Blood's gold bar on 30 September (8 540 626) and 28 September (16 670 857).
        assertEquals("8540626", digitsOf("8 540 6261"))
        assertEquals("8540626", digitsOf("8 540 626 1"))
        assertEquals("16670857", digitsOf("16 670 8571"))
        // A real last group of three keeps its ones.
        assertEquals("12345671", digitsOf("12 345 671"))
        assertEquals("1101", digitsOf("1 101"))
        assertEquals("8540611", digitsOf("8 540 611"))
        // Without grouping there is no telling a rim from a digit.
        assertEquals("85406261", digitsOf("85406261"))
        // Only a 1 is the rim; any other extra digit is left for the vote to reject.
        assertEquals("85406267", digitsOf("8 540 6267"))
    }

    @Test
    fun nothingIsReturnedWhenNoTwoReadingsAgree() {
        assertNull(ScreenReader.agreedAmount(listOf("1034762", "1034", "44034762")))
    }
}
