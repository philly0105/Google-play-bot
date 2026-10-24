package local.clashfarmer.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Readings copied from the battle frames logged from 21 to 28 September. */
class BattleClockTest {

    private fun left(text: String) = BattleClock.secondsLeft(text)

    @Test fun minutesAndSecondsWithTheMarkReadAsAFive() {
        assertEquals(149, left("Battle ends in: 2M 295 Pick a RewaRd! YEETeR XO XO"))
        assertEquals(145, left("Battle ends. in: 2M255 Pick a Reward! 18 Yeti UNder"))
        assertEquals(128, left("Battle ends' in: 2M 85 Pick a Reward! 18 Yeti UNder"))
        assertEquals(155, left("Battle ends ins 2M 355 Pick a Reward! sONT:XPlad 1A"))
    }

    @Test fun lettersTheRecogniserPutsForDigits() {
        assertEquals(151, left("Battle ends in: ZM 315 Pick a Reward! GiaNt Giant 5"))
        assertEquals(119, left("Battle ends in: IM 595 Pick a Reward! 18 Yeti UNder"))
        assertEquals(112, left("Battle ends in: IM S25 Pick a Reward! X 14 K.A.N.E"))
    }

    @Test fun strayCharactersBeforeTheClock() {
        assertEquals(162, left("Battle endsin: t8 2M 425 Pick a Reward! GiaNt GiaNt"))
        assertEquals(164, left("Battle ends in? 50 2M 445 Pick a Reward! Select a d"))
    }

    @Test fun underAMinute() {
        assertEquals(50, left("Battle ends in: S05 XO DLIMPOLA"))
        assertEquals(8, left("Battle ends in: 85 Pick a Reward!"))
    }

    @Test fun noClockReadIsNull() {
        assertNull(left("Battle ends in: Pick a Reward! logd. EMPİRE 14 Gia"))
        assertNull(left("Battle ends in: XS X 10 069 82"))
        assertNull(left("ENd Battle XO 100 100 75 50"))
        // The mark lost: 2m 29 could be 2m 2Xs, so it is not guessed at.
        assertNull(left("Battle ends in: 2M 29 Pick"))
    }
}
