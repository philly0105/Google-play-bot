package local.clashfarmer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import local.clashfarmer.recognition.UpgradeListFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which failures end the wall phase only and which end the run. Most are wall trouble: a tap
 * that timed out or a panel that would not read costs the walls and nothing else. Cancellation
 * and a stopped run always propagate, and so does a purchase whose outcome is unknown.
 */
class WallFailureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "11111111-2222-3333-4444-555555555555"
    private val order = WallPhaseOrder(
        accountId = account, mode = WallPhaseRequest.AUTOMATIC, currency = Resource.GOLD, balance = 99_000_000L,
        capacity = 100_000_000L, resumePercent = 51, reserve = 0L, maxWallsPerPhase = 100, maxWallLevel = 20
    )

    private fun phase(phone: WallPhone, select: suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult) =
        WallPhase(phone, UpgradeListFixture.screen, WallPurchaseStore(folder.root), phone.neutralPoint, select)

    private fun selected(phone: WallPhone): suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult =
        { _, _ -> UpgradeListResult.Selected(WallCandidate("Wall x80", 80, phone.unit, "gold"), phone.level) }

    @Test fun aGestureTimeoutEndsTheWallPhaseOnly() {
        val phone = WallPhone(gold = 99_000_000L)
        val outcome = runBlocking {
            phase(phone) { _, _ -> throw StopException("Tap at (1, 2) did not complete within 5000ms") }.run(order)
        }
        assertTrue("got $outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.ERROR)
    }

    @Test fun anUnreadablePanelEndsTheWallPhaseOnly() {
        val phone = WallPhone(gold = 99_000_000L)
        val outcome = runBlocking { phase(phone) { _, _ -> throw IllegalStateException("recogniser failed") }.run(order) }
        assertTrue("got $outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.ERROR)
        assertEquals(0, phone.confirmTaps)
    }

    @Test fun cancellationEndsTheRun() {
        val phone = WallPhone(gold = 99_000_000L)
        try {
            runBlocking { phase(phone) { _, _ -> throw CancellationException("Stop") }.run(order) }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test fun aPurchaseOfUnknownOutcomeEndsTheRunAndCannotBeAutoRestarted() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationFailure = StopException("Tap at (1, 2) did not complete within 5000ms")
        try {
            runBlocking { phase(phone, selected(phone)).run(order) }
            fail("an unproven purchase must stop the run")
        } catch (e: StopException) {
            assertFalse(RecoveryPolicy.isRecoverable(e.message))
            assertTrue(WallPurchaseStore(folder.root).isPending(account))
        }
    }
}
