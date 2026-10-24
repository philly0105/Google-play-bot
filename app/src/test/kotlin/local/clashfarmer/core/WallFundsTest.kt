package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import local.clashfarmer.recognition.UpgradeListFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the wall phase does when the money is short. A balance that cannot pay is never a reason
 * to press anything: it is an empty phase, a refusal, or a smaller purchase, and the confirmation
 * is not sent in any of them.
 */
class WallFundsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "11111111-2222-3333-4444-555555555555"

    private fun order(balance: Long, reserve: Long, mode: WallPhaseRequest = WallPhaseRequest.MANUAL) = WallPhaseOrder(
        accountId = account, mode = mode, currency = Resource.GOLD, balance = balance, capacity = 100_000_000L,
        resumePercent = 51, reserve = reserve, maxWallsPerPhase = 100, maxWallLevel = 20
    )

    private fun run(phone: WallPhone, order: WallPhaseOrder) = runBlocking {
        WallPhase(phone, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint) { _, _ ->
            UpgradeListResult.Selected(WallCandidate("Wall x80", 80, phone.unit, "gold"), phone.level)
        }.run(order)
    }

    @Test fun aBalanceAtTheReserveHasNothingToSpendAndTouchesNothing() {
        val phone = WallPhone(gold = 10_000_000L, start = "home")
        assertEquals(WallPhaseOutcome.Empty, run(phone, order(balance = 10_000_000L, reserve = 10_000_000L)))
        assertEquals(0, phone.confirmTaps)
        assertEquals(0, phone.upgradePresses)
    }

    @Test fun aSpendThatWouldBreachTheReserveBuysOnlyWhatLeavesItIntact() {
        // 10M gold, 6M reserve, 4M walls: one wall, never two.
        val phone = WallPhone(gold = 10_000_000L)
        val outcome = run(phone, order(balance = 10_000_000L, reserve = 6_000_000L))
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 1, 4_000_000L), outcome)
        assertTrue(phone.gold >= 6_000_000L)
    }

    @Test fun aPanelThatDrawsTheTotalRedIsNotBought() {
        val phone = WallPhone(gold = 99_000_000L, payableLimit = 3_000_000L)
        val outcome = run(phone, order(balance = 99_000_000L, reserve = 0L))
        assertTrue("got $outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.NOT_PAYABLE)
        assertEquals(0, phone.confirmTaps)
    }

    @Test fun gemsAndDarkElixirAreNotWallCurrencies() {
        val phone = WallPhone(gold = 99_000_000L, start = "home")
        val outcome = run(phone, order(balance = 1L, reserve = 0L).copy(currency = Resource.DARK_ELIXIR))
        assertTrue("got $outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.INVALID_ORDER)
        assertEquals(0, phone.gestures)
    }
}
