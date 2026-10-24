package local.clashfarmer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLatchTest {

    @Test
    fun aMatchedSpendKeepsWallsOn() {
        assertTrue(WallRunner.spendVerified(expected = 18_818_587, settled = 18_818_587))
    }

    @Test
    fun aMismatchedSpendTurnsWallsOff() {
        // 17 September, 20:26: the dialog was read against a balance ten times the real one.
        // The purchase was correct and the session died anyway.
        assertFalse(WallRunner.spendVerified(expected = 151_055_162, settled = 1_605_162))
    }

    @Test
    fun anUnreadableSettleIsNotAVerifiedSpend() {
        // settleResourceBalance returns -1 when the final read will not parse.
        assertFalse(WallRunner.spendVerified(expected = 4_500_000, settled = -1))
    }
}
