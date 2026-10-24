package local.clashfarmer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.UpgradeListResult.Selected
import local.clashfarmer.recognition.UpgradeListFixture
import local.clashfarmer.recognition.builderCounterPoint
import local.clashfarmer.recognition.upgradeListClosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wall phase against a scripted phone: one list view, one purchase, and the proof that the
 * purchase happened. Every refusal is checked for the two things that matter most on a purchase
 * path: that no confirmation was sent, and that the village is left at HOME.
 */
class WallPhaseTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "11111111-2222-3333-4444-555555555555"
    private val screen = UpgradeListFixture.screen

    private fun store() = WallPurchaseStore(folder.root)

    private fun order(
        balance: Long = 99_000_000L,
        mode: WallPhaseRequest = WallPhaseRequest.AUTOMATIC,
        reserve: Long = 0L,
        maxWalls: Int = 100,
        maxLevel: Int = 20,
        currency: Resource = Resource.GOLD,
        capped: Set<Long> = emptySet()
    ) = WallPhaseOrder(
        accountId = account, mode = mode, currency = currency, balance = balance, capacity = 100_000_000L,
        resumePercent = 51, reserve = reserve, maxWallsPerPhase = maxWalls, maxWallLevel = maxLevel,
        cappedPrices = capped
    )

    private fun selecting(phone: WallPhone, currency: String = "gold", listPrice: () -> Long = { phone.unit }):
        suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
        Selected(WallCandidate("Wall x80", 80, listPrice(), currency), phone.level)
    }

    private fun phase(phone: WallPhone, store: WallPurchaseStore = store(), select: (suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult)? = null) =
        WallPhase(phone, screen, store, phone.neutralPoint, select)

    private fun run(phone: WallPhone, order: WallPhaseOrder = order(), store: WallPurchaseStore = store(), select: (suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult)? = selecting(phone)) =
        runBlocking { phase(phone, store, select).run(order) }

    private fun bought(phone: WallPhone, count: Int, unit: Long, order: WallPhaseOrder = order(), afterBuy: String = "stays"): WallPhaseOutcome {
        phone.afterBuy = afterBuy
        return run(phone, order).also {
            assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, count, count * unit), it)
        }
    }

    private fun assertHome(phone: WallPhone) {
        assertEquals(WallPhone.Panel.NONE, phone.panel)
        assertFalse(phone.listOpen)
    }

    private fun assertRefused(outcome: WallPhaseOutcome, reason: WallPhaseFailure, phone: WallPhone, markerStays: Boolean = false) {
        assertTrue("expected Failed($reason), got $outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == reason)
        assertEquals("no confirmation may be sent", 0, phone.confirmTaps)
        assertEquals("marker", markerStays, store().isPending(account))
        assertHome(phone)
    }

    private fun assertStopsUnresolved(phone: WallPhone, order: WallPhaseOrder = order()): StopException {
        try {
            run(phone, order)
            fail("an unproven purchase must stop the run")
        } catch (e: StopException) {
            assertFalse("auto-restart must not resume: ${e.message}", RecoveryPolicy.isRecoverable(e.message))
            assertTrue("the marker must stay", store().isPending(account))
            return e
        }
        throw AssertionError()
    }

    private fun count(phone: WallPhone, x: Int, y: Int = 599) = phone.taps.count { it == "button $x,$y" }

    // --- what is bought ------------------------------------------------------------------------

    @Test
    fun aBulkPurchaseGrowsWithTenThenOnesAndIsProven() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 12, 4_000_000L)
        assertEquals(51_000_000L, phone.gold)
        assertEquals("+10 once", 1, count(phone, 658))
        assertEquals("+1 once (1 + 10 + 1 = 12)", 1, count(phone, 800))
        assertEquals(0, phone.selectRowPresses)
        assertEquals(1, phone.upgradePresses)
        assertEquals(1, phone.confirmTaps)
        assertFalse("the marker is cleared by proof", store().isPending(account))
        assertHome(phone)
    }

    /** Blood on 15 October: after Upgrade More the gold and elixir prices read as one box. */
    @Test
    fun aBulkPanelWhosePricesReadAsOneBoxStillGrowsAndBuys() {
        val phone = WallPhone(gold = 99_000_000L, mergedPrices = true)
        bought(phone, 12, 4_000_000L)
        assertEquals(51_000_000L, phone.gold)
        assertEquals(1, phone.upgradePresses)
        assertEquals("Upgrade was pressed on the gold label", 1, count(phone, 941))
    }

    @Test
    fun aTitleCountThatNeverAgreesWithThePriceIsNoCount() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.bulkTitle = { "Wall (Level ${phone.level})x ${it + 1}" }
        assertRefused(run(phone), WallPhaseFailure.SELECTION_REFUSED, phone)
        assertEquals("nothing was added on a count that did not agree", 0, count(phone, 658) + count(phone, 800))
    }

    @Test
    fun aTitleCountThatDisagreesForTwoFramesIsReadAgain() {
        val agreeing = WallPhone(gold = 99_000_000L)
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 12, 48_000_000L), run(agreeing, store = WallPurchaseStore(folder.newFolder())))
        var frames = 0
        val phone = WallPhone(gold = 99_000_000L)
        phone.bulkTitle = { n -> "Wall (Level ${phone.level})x ${if (frames++ < 2) 9 else n}" }
        bought(phone, 12, 4_000_000L)
        assertEquals("one more look for each frame that disagreed", agreeing.captures + 2, phone.captures)
    }

    @Test
    fun aBulkTitleWithoutItsCountLeavesThePriceToCount() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.bulkTitle = { "Wall (Level ${phone.level})" }
        bought(phone, 12, 4_000_000L)
    }

    @Test
    fun aManualPhaseSpendsDownToTheReserveIgnoringTheResumeLevel() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 9, 4_000_000L, order(mode = WallPhaseRequest.MANUAL, reserve = 60_000_000L))
        assertEquals(63_000_000L, phone.gold)
    }

    @Test
    fun theWallsPerPhaseLimitCapsTheSelection() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 3, 4_000_000L, order(maxWalls = 3))
        assertEquals(0, count(phone, 658))
        assertEquals(2, count(phone, 800))
    }

    @Test
    fun aSingleWallSkipsUpgradeMoreAndUsesTheSingleDialog() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 1, 4_000_000L, order(maxWalls = 1))
        assertEquals(WallPhone.Panel.NONE, phone.panel)
        assertEquals("no bulk panel was opened", 0, phone.taps.count { it == "button 800,605" })
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aTenThatDoesNothingFallsBackToOnes() {
        val phone = WallPhone(gold = 99_000_000L, addTenWorks = false)
        bought(phone, 12, 4_000_000L)
        assertEquals("the dead +10 is tried once, then never again", 1, count(phone, 658))
        assertEquals(11, count(phone, 800))
    }

    @Test
    fun growthThatStopsShortBuysWhatTheSelectionReached() {
        val phone = WallPhone(gold = 99_000_000L, growthLimit = 7)
        bought(phone, 7, 4_000_000L)
        assertEquals(71_000_000L, phone.gold)
    }

    @Test
    fun growthWithoutTheTenButtonThatStopsShortBuysWhatItReached() {
        val phone = WallPhone(gold = 99_000_000L, addTenWorks = false, growthLimit = 3)
        bought(phone, 3, 4_000_000L)
        assertEquals(87_000_000L, phone.gold)
    }

    @Test
    fun aPanelAtTheNextLevelIsProofDespiteStrayFundsWords() {
        // 5 October, Sweat: a real 15M purchase raised the walls to level 17 and the run still
        // stopped unresolved. Words such as "gem" anywhere on screen must not veto the title.
        val phone = WallPhone(gold = 99_000_000L)
        phone.beforeConfirmation = { phone.extraText = "magic gem" }
        bought(phone, 12, 4_000_000L)
        assertFalse("the marker is cleared by proof", store().isPending(account))
    }

    @Test
    fun fundsWordsOnAHomeScreenStillWithholdProof() {
        // With no title to contradict them, the words are the only sign of a gem dialog the
        // reader half-missed, so home stays strict and the run stops for a person to look.
        val phone = WallPhone(gold = 99_000_000L, afterBuy = "closes")
        phone.beforeConfirmation = { phone.extraText = "magic gem" }
        assertStopsUnresolved(phone)
    }

    @Test
    fun aTotalDrawnRedIsTrimmedBeforeAnythingIsBought() {
        val phone = WallPhone(gold = 99_000_000L, payableLimit = 40_000_000L)
        bought(phone, 10, 4_000_000L)
        assertEquals("two Remove Wall presses", 2, count(phone, 518, 594))
        assertEquals(59_000_000L, phone.gold)
    }

    @Test
    fun selectRowIsNeverPressed() {
        val phone = WallPhone(gold = 99_000_000L, addTenWorks = false, growthLimit = 5)
        bought(phone, 5, 4_000_000L)
        assertEquals(0, phone.selectRowPresses)
    }

    // --- what is refused -----------------------------------------------------------------------

    @Test
    fun aWallAtTheLevelCapIsNotBoughtAndItsListPriceIsReported() {
        val phone = WallPhone(gold = 99_000_000L, level = 17)
        val outcome = run(phone, order(maxLevel = 17))
        assertEquals(WallPhaseOutcome.Capped(5_600_000L), outcome)
        assertEquals(0, phone.upgradePresses)
        assertEquals(0, phone.confirmTaps)
        assertHome(phone)
    }

    @Test
    fun aPanelPriceThatIsNotTheListPriceIsRefused() {
        val phone = WallPhone(gold = 99_000_000L)
        val outcome = run(phone, select = selecting(phone, listPrice = { 5_000_000L }))
        assertRefused(outcome, WallPhaseFailure.PRICE_CHANGED, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aPriceNotInTheLevelTableIsRefusedNotGuessed() {
        val phone = WallPhone(gold = 99_000_000L, level = 20)
        assertRefused(run(phone, order(maxLevel = 25)), WallPhaseFailure.PRICE_UNKNOWN, phone)
        assertEquals(0, phone.upgradePresses)
    }

    /** 5 October: 10M walls at 10% off were taken for level 19 and refused under a cap of 19. */
    @Test
    fun aDiscountedLevel18WallIsBoughtToLevel19UnderACapOf19() {
        for (price in listOf(9_000_000L, 8_500_000L)) {
            val phone = WallPhone(gold = 99_000_000L, level = 18, unitOverride = price)
            bought(phone, 1, price, order(maxWalls = 1, maxLevel = 19))
            assertEquals("the dialog's Level 19 agreed with the title's 18", 1, phone.confirmTaps)
            assertEquals(19, phone.level)
        }
    }

    @Test
    fun aPriceOutsideTheTitlesDiscountBandIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, level = 18, unitOverride = 7_500_000L)
        assertRefused(run(phone, order(maxLevel = 19)), WallPhaseFailure.PRICE_UNKNOWN, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aStruckOutFullPriceThatMatchesTheLevelIsBought() {
        val phone = WallPhone(gold = 99_000_000L, level = 18, unitOverride = 9_000_000L, dialogFullPrice = 10_000_000L)
        bought(phone, 1, 9_000_000L, order(maxWalls = 1, maxLevel = 19))
    }

    @Test
    fun aStruckOutFullPriceOfAnotherLevelIsCancelled() {
        val phone = WallPhone(gold = 99_000_000L, level = 17, unitOverride = 5_600_000L, dialogFullPrice = 7_000_000L)
        bought(phone, 1, 5_600_000L, order(maxWalls = 1, maxLevel = 19))
        val wrong = WallPhone(gold = 99_000_000L, level = 18, unitOverride = 8_000_000L, dialogFullPrice = 9_000_000L)
        assertRefused(run(wrong, order(maxWalls = 1, maxLevel = 19)), WallPhaseFailure.CONFIRMATION_REFUSED, wrong)
        assertEquals(99_000_000L, wrong.gold)
    }

    @Test
    fun aPanelThatIsNotAWallIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, titleOverride = "Cannon (Level 3)")
        assertRefused(run(phone), WallPhaseFailure.PANEL_UNREADABLE, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aSelectionOfSeveralWallsIsRefusedAtTheStart() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.panel = WallPhone.Panel.BULK
        phone.count = 3
        assertRefused(run(phone), WallPhaseFailure.MULTIPLE_SELECTION, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aCandidateDisplayedInTheOtherCurrencyUsesTheVerifiedGoldButton() {
        val phone = WallPhone(gold = 99_000_000L)
        assertTrue(run(phone, select = selecting(phone, currency = "elixir")) is WallPhaseOutcome.Bought)
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aPanelWithNoButtonForTheCurrencyIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, goldButton = false)
        assertRefused(run(phone), WallPhaseFailure.WRONG_CURRENCY, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aPriceDrawnRedIsNotPressed() {
        val phone = WallPhone(gold = 99_000_000L, payableLimit = 3_000_000L)
        assertRefused(run(phone), WallPhaseFailure.NOT_PAYABLE, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aBalanceTheBarDoesNotAgreeWithIsRefused() {
        var readings = 0
        val phone = WallPhone(gold = 99_000_000L, barGold = { if (++readings % 2 == 0) 50_000_000L else 51_000_000L })
        assertRefused(run(phone), WallPhaseFailure.BALANCE_UNREAD, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aBalanceTheBarCannotReadIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, barGold = { null })
        assertRefused(run(phone), WallPhaseFailure.BALANCE_UNREAD, phone)
    }

    @Test
    fun aReserveThatLeavesNoWallIsARefusalNotAPurchase() {
        val phone = WallPhone(gold = 10_000_000L)
        val outcome = run(phone, order(balance = 10_000_000L, reserve = 8_000_000L))
        assertRefused(outcome, WallPhaseFailure.NOTHING_PLANNED, phone)
        assertEquals(0, phone.upgradePresses)
    }

    @Test
    fun aBalanceAtTheReserveOpensNothingAndIsEmpty() {
        val phone = WallPhone(gold = 10_000_000L, start = "home")
        val outcome = run(phone, order(balance = 10_000_000L, reserve = 10_000_000L))
        assertEquals(WallPhaseOutcome.Empty, outcome)
        assertEquals(0, phone.gestures)
    }

    @Test
    fun theInsufficientFundsDialogIsCancelledNotAccepted() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "insufficient")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
        assertEquals(99_000_000L, phone.gold)
        assertTrue("dismissed through Cancel", phone.taps.any { it.startsWith("button 660,440") })
    }

    @Test
    fun aGemPurchaseIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "gems")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
        assertEquals(99_000_000L, phone.gold)
    }

    @Test
    fun aMoneyPurchaseIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "money")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
        assertEquals(99_000_000L, phone.gold)
    }

    /**
     * UO-21: a priced offer is the store's, and only ever left with Back. On 15 October at 05:31
     * UTC an Attack tap landed on the gem store under its $0.99 card; neither button of an offer
     * is pressed to close it.
     */
    @Test
    fun aMoneyDialogIsLeftWithBackAndNeitherOfItsButtons() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "money")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
        assertEquals(listOf("gem_shop"), phone.backWheres)
        assertTrue("Okay not pressed: ${phone.taps}", phone.taps.none { it.endsWith(" 940,440") })
        assertTrue("Cancel not pressed: ${phone.taps}", phone.taps.none { it.endsWith(" 660,440") })
        assertEquals(99_000_000L, phone.gold)
    }

    /**
     * UO-21: a confirmation refused or held before it went out bought nothing, so the marker goes
     * and the phase ends as paused, not as an unproven purchase that stops the run.
     */
    @Test
    fun aConfirmationNotSentClearsTheMarkerAndEndsThePhasePaused() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationNotSent = true
        assertRefused(run(phone), WallPhaseFailure.PAUSED, phone)
        assertEquals(1, phone.upgradePresses)
        assertEquals(99_000_000L, phone.gold)
    }

    /**
     * UO-21 review, 15 October: a confirmation held with no pause, as by a look that found the
     * dialog's state flip from HOME to UNKNOWN, is read again and sent, not taken for a pause.
     */
    @Test
    fun aConfirmationHeldWithNoPauseIsReadAgainAndSent() {
        val plain = WallPhone(gold = 99_000_000L)
        val bought = run(plain, store = WallPurchaseStore(folder.newFolder()))
        assertTrue("$bought", bought is WallPhaseOutcome.Bought)
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationsHeldUnpaused = 2
        assertEquals(bought, run(phone))
        assertEquals(2, phone.taps.count { it.startsWith("held") })
        assertEquals(1, phone.confirmTaps)
        assertEquals(1, phone.upgradePresses)
        assertEquals(plain.gold, phone.gold)
        assertFalse(store().isPending(account))
    }

    @Test
    fun aConfirmationHeldWithNoPauseOnEveryReadIsRefusedNotPaused() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationsHeldUnpaused = 3
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
        assertEquals(3, phone.taps.count { it.startsWith("held") })
        assertEquals(1, phone.upgradePresses)
        assertEquals(99_000_000L, phone.gold)
    }

    /** UO-21: a press held by its look is not sent, and the panel is read again before the next. */
    @Test
    fun aHeldPressIsNotSentAndThePanelIsReadAgain() {
        val plain = WallPhone(gold = 99_000_000L)
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 12, 48_000_000L), run(plain, store = WallPurchaseStore(folder.newFolder())))
        val phone = WallPhone(gold = 99_000_000L)
        phone.holdTaps = 1
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 12, 48_000_000L), run(phone, store = WallPurchaseStore(folder.newFolder())))
        val held = phone.taps.single { it.startsWith("held") }
        val after = phone.taps[phone.taps.indexOf(held) + 1]
        assertEquals("the same press, sent once the panel read the same", held.substringAfter(' '), after.substringAfter(' '))
        assertEquals("the held press was not dispatched", plain.gestures, phone.gestures)
        assertTrue("read again after the hold", phone.captures > plain.captures)
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aDarkElixirPurchaseIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "dark")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
    }

    /** 30 September: the same single-wall dialog priced at 02:36 and read unpriced at 02:46. */
    @Test
    fun aDialogFrameWithNoPriceIsReadAgain() {
        val phone = WallPhone(gold = 99_000_000L, unpricedDialogFrames = 1)
        bought(phone, 1, 4_000_000L, order(maxWalls = 1))
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aDialogThatNeverShowsAPriceIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, unpricedDialogFrames = 99)
        assertRefused(run(phone, order(maxWalls = 1)), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
    }

    @Test
    fun aDialogPriceThatIsNotThePanelTotalIsRefused() {
        val phone = WallPhone(gold = 99_000_000L, dialogKind = "wrongprice")
        assertRefused(run(phone), WallPhaseFailure.CONFIRMATION_REFUSED, phone)
    }

    // --- proof of purchase ---------------------------------------------------------------------

    /** 04:34 on 30 September: a wall raised to 19 left a panel with nothing to buy on screen. */
    @Test
    fun aWallRaisedToTheCapIsProvenByItsTitle() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 1, 4_000_000L, order(maxWalls = 1), afterBuy = "maxed")
        assertFalse("the marker is cleared by proof", store().isPending(account))
    }

    /** Blood at 16:18 UTC on 14 October: the capped selection read as HOME and was left up. */
    @Test
    fun aCappedWallLeftSelectedIsClearedBeforeHomeCounts() {
        val phone = WallPhone(gold = 99_000_000L, maxedReadsHome = true)
        bought(phone, 1, 4_000_000L, order(maxWalls = 1), afterBuy = "maxed")
        assertHome(phone)
        assertEquals("the selection is cleared on empty grass", "tap 280,330", phone.taps.last())
    }

    /** Blood at 07:06 UTC on 14 October: the capped title read "Walf (Level 19)", Select Row read. */
    @Test
    fun aCappedWallWithAMisreadTitleIsClearedByItsSelectRow() {
        val phone = WallPhone(gold = 99_000_000L, maxedReadsHome = true, maxedTitle = "Walf (Level 16)")
        bought(phone, 1, 4_000_000L, order(maxWalls = 1), afterBuy = "maxed")
        assertHome(phone)
        assertEquals("the selection is cleared on empty grass", "tap 280,330", phone.taps.last())
    }

    @Test
    fun aPanelThatClosesAfterTheSpendIsProvenByTheBalanceAlone() {
        val phone = WallPhone(gold = 99_000_000L)
        bought(phone, 12, 4_000_000L, afterBuy = "closes")
        assertFalse(store().isPending(account))
    }

    @Test
    fun aPanelThatStaysOnTheOldLevelIsNotProof() {
        val phone = WallPhone(gold = 99_000_000L, afterBuy = "unchanged")
        assertStopsUnresolved(phone)
        assertEquals("the money did leave, and that is why the marker matters", 51_000_000L, phone.gold)
    }

    @Test
    fun aBalanceThatDidNotFallByTheTotalIsNotProof() {
        val phone = WallPhone(gold = 99_000_000L, settleOverride = 60_000_000L)
        assertStopsUnresolved(phone)
    }

    @Test
    fun aConfirmationThatDidNothingIsNotProof() {
        val phone = WallPhone(gold = 99_000_000L, okayWorks = false)
        assertStopsUnresolved(phone)
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aTimeoutAfterTheConfirmationStopsWithAReasonAutoRestartWillNotResume() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationFailure = StopException("Tap at Point(x=990, y=440) did not complete within 5000ms")
        val stop = assertStopsUnresolved(phone)
        assertFalse("the cause text must not be in the reason", stop.message!!.contains("did not complete within"))
        assertNotNull(stop.cause)
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aScreenshotFailureAfterTheConfirmationDoesNotMakeTheStopRecoverable() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationFailure = StopException("Screenshot capture failed")
        assertStopsUnresolved(phone)
    }

    @Test
    fun cancellationAfterTheConfirmationLeavesTheMarkerAndIsRethrown() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.confirmationFailure = CancellationException("run cancelled")
        try {
            run(phone)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(store().isPending(account))
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun theMarkerIsOnDiskBeforeTheConfirmationIsSent() {
        val phone = WallPhone(gold = 99_000_000L)
        val seen = mutableListOf<PendingWallPurchase?>()
        phone.beforeConfirmation = { seen += store().pending(account) }
        run(phone)
        val marker = seen.single()!!
        assertEquals(PendingWallPurchase(account, Resource.GOLD, 12, 48_000_000L, 51_000_000L, 99_000_000L, marker.atMs), marker)
    }

    @Test
    fun aRecreatedProcessSpendsNothingWhileTheMarkerStands() {
        val first = WallPhone(gold = 99_000_000L)
        first.confirmationFailure = CancellationException("process died")
        try { run(first) } catch (_: CancellationException) {}

        val second = WallPhone(gold = 99_000_000L, start = "home")
        val outcome = run(second, store = store())
        assertRefused(outcome, WallPhaseFailure.PENDING_PURCHASE, second, markerStays = true)
        assertEquals("no gesture at all", 0, second.gestures)
        assertTrue("the marker was not cleared", store().isPending(account))
    }

    @Test
    fun onlyAnExplicitAcknowledgeClearsItAndItRecordsNoSuccess() {
        val store = store()
        store.markPending(PendingWallPurchase(account, Resource.GOLD, 12, 48_000_000L, 51_000_000L, 99_000_000L, 5L))
        val blocked = WallPhone(gold = 99_000_000L, start = "home")
        assertRefused(run(blocked, store = store), WallPhaseFailure.PENDING_PURCHASE, blocked, markerStays = true)

        assertTrue(store.acknowledge(account))
        val phone = WallPhone(gold = 99_000_000L)
        val outcome = run(phone, store = store)
        assertEquals("only this phase's own walls are counted", WallPhaseOutcome.Bought(Resource.GOLD, 12, 48_000_000L), outcome)
    }

    @Test
    fun aMarkerThatCannotBeWrittenSendsNothing() {
        java.io.File(folder.root, "accounts").writeText("in the way")
        val phone = WallPhone(gold = 99_000_000L)
        val outcome = run(phone)
        assertTrue(outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.MARKER_WRITE_FAILED)
        assertEquals(0, phone.confirmTaps)
        assertHome(phone)
    }

    // --- pause ---------------------------------------------------------------------------------

    @Test
    fun aPauseWhileTheDialogIsUpMakesTheDialogBeReadAgainBeforeAnythingIsSent() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.pauseAtDialogCheckpoint = 2
        bought(phone, 12, 4_000_000L)
        assertEquals(1, phone.confirmTaps)
    }

    @Test
    fun aRunThatStaysPausedNeverConfirms() {
        val phone = WallPhone(gold = 99_000_000L)
        phone.pauseFirst = 6
        val outcome = run(phone)
        assertRefused(outcome, WallPhaseFailure.PAUSED, phone)
    }

    // --- getting home --------------------------------------------------------------------------

    @Test
    fun aVillageThatCannotBeConfirmedStopsTheRunWithAReasonAutoRestartWillNotResume() {
        val phone = WallPhone(gold = 10_000_000L, start = "home", stuck = true)
        try {
            run(phone, order(balance = 10_000_000L, reserve = 10_000_000L))
            fail("an unconfirmed village must stop the run")
        } catch (e: StopException) {
            assertFalse(RecoveryPolicy.isRecoverable(e.message))
        }
    }

    @Test
    fun theVillageIsConfirmedTwiceBeforeTheOutcomeIsReturned() {
        val phone = WallPhone(gold = 10_000_000L, start = "home")
        run(phone, order(balance = 10_000_000L, reserve = 10_000_000L))
        assertTrue("two clear frames", phone.captures >= 2)
    }

    // --- with the real navigator ---------------------------------------------------------------

    private val noWalls = listOf(
        UpgradeListFixture.Header("Upgrades in progress:", 123),
        UpgradeListFixture.Line("Available!", 161, icon = true),
        UpgradeListFixture.Header("Other upgrades:", 199),
        UpgradeListFixture.gold("Bomb x8", 237, 4_000_000)
    )

    @Test
    fun aListWithNoWallRowSaysSoClosesTheListAndReturnsHome() {
        val phone = WallPhone(gold = 99_000_000L, start = "home", items = noWalls)
        val outcome = run(phone, select = null)
        assertEquals(WallPhaseOutcome.NoWalls, outcome)
        assertHome(phone)
        assertEquals(0, phone.confirmTaps)
    }

    @Test
    fun aWallRowNothingCanPayForIsEmptyNotNoWalls() {
        val phone = WallPhone(gold = 99_000_000L, start = "home")
        assertEquals(WallPhaseOutcome.Empty, run(phone, select = { _, _ -> UpgradeListResult.Empty(3, 1) }))
        assertEquals(WallPhaseOutcome.NoWalls, run(phone, select = { _, _ -> UpgradeListResult.Empty(3, 0) }))
        assertEquals(0, phone.confirmTaps)
    }

    @Test
    fun oneScanWithNoWallRowIsCheckedByASecondBeforeWallsEnd() {
        // A Wall name garbled past reading makes one scan see none; the second reads it.
        val phone = WallPhone(gold = 99_000_000L)
        var scans = 0
        val outcome = run(phone, select = { spendable, capped ->
            if (++scans == 1) UpgradeListResult.Empty(2, 0) else selecting(phone)(spendable, capped)
        })
        assertEquals(2, scans)
        assertTrue("$outcome", outcome is WallPhaseOutcome.Bought)

        val other = WallPhone(gold = 99_000_000L, start = "home")
        var otherScans = 0
        val seen = run(other, select = { _, _ ->
            if (++otherScans == 1) UpgradeListResult.Empty(2, 0) else UpgradeListResult.Empty(2, 1)
        })
        assertEquals(WallPhaseOutcome.Empty, seen)
        assertEquals(2, otherScans)

        // A rescan that could not finish keeps the first scan's verdict, not a Failed that re-arms walls.
        var failedScans = 0
        val kept = run(WallPhone(gold = 99_000_000L, start = "home"), select = { _, _ ->
            if (++failedScans == 1) UpgradeListResult.Empty(2, 0)
            else UpgradeListResult.Failed(UpgradeListFailure.BUDGET_EXCEEDED, "20 swipes")
        })
        assertEquals(WallPhaseOutcome.NoWalls, kept)
        assertEquals(2, failedScans)

        // A scan that saw a Wall row is not repeated.
        var once = 0
        run(WallPhone(gold = 99_000_000L, start = "home"), select = { _, _ -> once++; UpgradeListResult.Empty(2, 1) })
        assertEquals(1, once)
    }

    @Test
    fun sweatsMaxedWallsEndAfterTwoScansWithTheCounterAndTheXAlone() {
        val phone = WallPhone(gold = 99_000_000L, start = "home", items = noWalls)
        assertEquals(WallPhaseOutcome.NoWalls, run(phone, select = null))
        // The second scan reads the list the first left open: no second counter tap, no other press.
        val counter = builderCounterPoint(screen)
        val close = upgradeListClosePoint(screen)
        assertEquals(listOf("tap ${counter.x},${counter.y}", "tap ${close.x},${close.y}"), phone.taps)
        assertHome(phone)
    }

    @Test
    fun aListCaughtOnOneFrameAsItClosesIsNotPressedWithItsX() {
        // With the list gone, the X's spot is the village's gold bar.
        val phone = WallPhone(gold = 99_000_000L, start = "home")
        val outcome = run(phone, select = { _, _ ->
            phone.listOpen = true
            phone.listClosesAfter = 1
            UpgradeListResult.Empty(3, 1)
        })
        assertEquals(WallPhaseOutcome.Empty, outcome)
        assertTrue("${phone.taps}", phone.taps.isEmpty())
        assertHome(phone)
    }

    @Test
    fun aListThatWillNotOpenFailsAndTheRunCarriesOn() {
        val phone = WallPhone(gold = 99_000_000L, start = "home", counterOpens = false)
        val outcome = run(phone, select = null)
        assertTrue(outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.LIST_FAILED)
        assertHome(phone)
    }

    @Test
    fun aWholePhaseThroughTheRealNavigator() {
        val phone = WallPhone(gold = 99_000_000L, start = "home", level = 16)
        val outcome = run(phone, select = null)
        assertEquals(WallPhaseOutcome.Bought(Resource.GOLD, 10, 50_000_000L), outcome)
        assertEquals(49_000_000L, phone.gold)
        assertEquals(1, phone.confirmTaps)
        assertHome(phone)
        assertNull(store().pending(account))
    }

    // --- builders -----------------------------------------------------------------------------

    @Test
    fun noFreeBuilderSkipsThePhaseBeforeAnythingIsPlanned() {
        val phone = WallPhone(gold = 29_000_000L, level = 18, start = "home", builders = "0/6")
        val outcome = run(phone, order(balance = 29_000_000L), select = { _, _ ->
            fail("no list may be opened with no builder free"); UpgradeListResult.Empty(0, 0)
        })
        assertEquals(WallPhaseOutcome.NoBuilder, outcome)
        assertEquals(0, phone.confirmTaps)
        assertEquals(0, phone.upgradePresses)
        assertEquals("nothing is pressed at all", 0, phone.gestures)
        assertFalse("no pending marker", store().isPending(account))
        assertHome(phone)
    }

    @Test
    fun aFreeBuilderOrAnUnreadCounterOnTheVillageOpensTheListAndBuys() {
        // The village is the evidence for the counter tap, not the counter's text: ML Kit missed
        // Blood's "1/6 0/1" on all 7 plain village reads from 16:17 UTC on 14 October to 03:28 on
        // 15 October, and each of them opened the list (UO-21 review, 15 October).
        for (counter in listOf("1/6", null)) {
            val phone = WallPhone(gold = 99_000_000L, start = "home", level = 16, builders = counter)
            assertEquals("counter $counter", WallPhaseOutcome.Bought(Resource.GOLD, 10, 50_000_000L), run(phone, select = null))
            assertNull(store().pending(account))
        }
    }

    @Test
    fun aZeroCounterThatTheNextFrameContradictsRunsThePhase() {
        // One frame's "0/6" against a second count with a builder free: a misread "6/6" must not
        // cost the account its walls.
        for (second in listOf("1/6")) {
            var firstFrame: Long? = null
            val phone = WallPhone(gold = 99_000_000L, start = "home", level = 16)
            phone.buildersAt = { t -> if (firstFrame == null) firstFrame = t; if (t == firstFrame) "0/6" else second }
            val outcome = run(phone, select = null)
            assertEquals("then $second", WallPhaseOutcome.Bought(Resource.GOLD, 10, 50_000_000L), outcome)
            assertNull(store().pending(account))
        }
    }

    @Test
    fun aZeroCounterWhoseSecondFrameIsZeroOfAnotherTotalSkipsThePhase() {
        // A-W5-V: 0/6 then 0/5 disagree on the total, but neither shows a builder free.
        var firstFrame: Long? = null
        val phone = WallPhone(gold = 99_000_000L, start = "home", level = 16)
        phone.buildersAt = { t -> if (firstFrame == null) firstFrame = t; if (t == firstFrame) "0/6" else "0/5" }
        val outcome = run(phone, select = { _, _ ->
            fail("no list may be opened on two zeros"); UpgradeListResult.Empty(0, 0)
        })
        assertEquals(WallPhaseOutcome.NoBuilder, outcome)
        assertEquals(0, phone.confirmTaps)
        assertEquals(0, phone.gestures)
        assertHome(phone)
    }

    @Test
    fun aZeroCounterWhoseSecondFrameDoesNotReadSkipsThePhase() {
        // Blood, 13:06:59 UTC on 8 October: 0/6, then a frame that read nothing, and the phase ran
        // into the busy dialog. The zero stands.
        var firstFrame: Long? = null
        val phone = WallPhone(gold = 99_000_000L, start = "home", level = 16)
        phone.buildersAt = { t -> if (firstFrame == null) firstFrame = t; if (t == firstFrame) "0/6" else null }
        val outcome = run(phone, select = { _, _ ->
            fail("no list may be opened on an unconfirmed zero"); UpgradeListResult.Empty(0, 0)
        })
        assertEquals(WallPhaseOutcome.NoBuilder, outcome)
        assertEquals(0, phone.confirmTaps)
        assertEquals(0, phone.gestures)
        assertHome(phone)
    }

    @Test
    fun theBusyDialogAfterConfirmIsClosedByItsXOnceAndEndsAsBuildersBusy() {
        // Blood, 09:33 UTC on 8 October: three level 18 walls at 8M, confirmed, then the dialog.
        val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true)
        val outcome = run(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
        assertEquals(WallPhaseOutcome.BuildersBusy, outcome)
        assertEquals(1, phone.confirmTaps)
        assertEquals("the gem button is never pressed", 0, phone.gemTaps)
        assertEquals(1, phone.busyCloses)
        assertEquals("the X is sent once, never re-sent", listOf("once 1061,216"), phone.taps.filter { it.startsWith("once") })
        assertEquals(0, phone.backs)
        assertFalse(phone.busyShowing)
        assertEquals(29_000_000L, phone.gold)
        assertFalse("the unchanged balance proves nothing was bought", store().isPending(account))
        assertHome(phone)
    }

    @Test
    fun theBusyDialogWithNoXReadIsClosedByBackNotACornerTap() {
        val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true, busyCloseRead = false)
        val outcome = run(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
        assertEquals(WallPhaseOutcome.BuildersBusy, outcome)
        assertEquals(0, phone.gemTaps)
        assertEquals(1, phone.busyCloses)
        assertEquals(1, phone.backs)
        assertEquals(0, phone.backsOnVillage)
        assertTrue("nothing pressed at the corner", phone.taps.none { it.endsWith("1060,215") })
        assertEquals(1, phone.confirmTaps)
        assertFalse(store().isPending(account))
    }

    @Test
    fun aBusyDialogGoneOnTheFreshFrameIsNotPressed() {
        // D3 F3: the dialog was seen on a frame caught as it faded. Nothing may be sent at it.
        for (xRead in listOf(true, false)) {
            val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true, busyCloseRead = xRead, busyFrames = 1)
            val outcome = run(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
            assertEquals("x read $xRead", WallPhaseOutcome.BuildersBusy, outcome)
            assertEquals(0, phone.busyTapsAfterFade)
            assertEquals(0, phone.backs)
            assertEquals(0, phone.busyCloses)
            assertTrue(phone.taps.none { it.startsWith("once") })
            assertEquals(0, phone.gemTaps)
            assertFalse(store().isPending(account))
            assertHome(phone)
        }
    }

    /**
     * UO-21: the X held or refused before it went out is not an unproven purchase. The unchanged
     * balance still proves nothing was bought, and the next look closes the dialog.
     */
    @Test
    fun aBusyDialogXHeldOrRefusedIsClosedOnTheNextLookNotAStop() {
        for (refused in listOf(false, true)) {
            val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true)
            phone.holdOnceTaps = 1
            phone.refuseOnce = refused
            val outcome = run(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
            assertEquals("refused $refused", WallPhaseOutcome.BuildersBusy, outcome)
            assertEquals(listOf("held 1061,216", "once 1061,216"),
                phone.taps.filter { it.startsWith("held") || it.startsWith("once") })
            assertEquals(1, phone.busyCloses)
            assertEquals(0, phone.gemTaps)
            assertEquals(1, phone.confirmTaps)
            assertFalse(store().isPending(account))
            assertHome(phone)
        }
    }

    @Test
    fun aBalanceDropBehindTheBusyDialogStaysUnproven() {
        val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true)
        phone.settleOverride = 21_000_000L
        val e = assertStopsUnresolved(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
        assertTrue(e.message.orEmpty().contains("acknowledge"))
        assertEquals(0, phone.gemTaps)
    }

    @Test
    fun anUnreadBalanceBehindTheBusyDialogStaysUnproven() {
        val phone = WallPhone(gold = 29_000_000L, level = 18, buildersBusy = true)
        phone.settleOverride = -1L
        assertStopsUnresolved(phone, order(balance = 29_000_000L, mode = WallPhaseRequest.MANUAL, maxWalls = 3))
        assertEquals(0, phone.gemTaps)
    }

    // --- the quit prompt -----------------------------------------------------------------------

    /**
     * Back on the village at 14:05 UTC on 8 October opened "Confirm Exit / Do you want to quit the
     * game?". The frame read as HOME with 21 boxes; Cancel and Okay came back as separate boxes.
     * [buttons] are the two button boxes in the order they are listed.
     */
    private fun quitFrame(buttons: List<local.clashfarmer.recognition.RecognizedBox>) = listOf(
        box("259", 70, 15, 125, 42), box("29", 70, 48, 110, 72), box("!", 30, 560, 45, 590),
        box("Blood", 20, 95, 110, 120), box("Attack!", 60, 650, 210, 705), box("Collect", 230, 660, 330, 690),
        box("23d 17h", 1300, 120, 1400, 145), box("DOOst", 1300, 150, 1400, 175), box("0/6", 795, 26, 845, 56),
        box("4H 43M", 1420, 120, 1520, 145), box("CONFiRM Bxit", 690, 205, 910, 245),
        box("Do you want to quit the game?", 590, 300, 1010, 335)
    ) + buttons.take(1) + listOf(
        box("Full!", 1380, 20, 1440, 40), box("Full!", 1380, 70, 1440, 90), box("Full!", 1380, 120, 1440, 140)
    ) + buttons.drop(1) + listOf(
        box("29 000 000", 1250, 20, 1370, 45), box("29 000 000", 1250, 70, 1370, 95), box("472585", 1250, 120, 1370, 145),
        box("1983", 1460, 180, 1530, 205), box("SHOP", 1480, 640, 1560, 680)
    )

    private fun box(text: String, l: Int, t: Int, r: Int, b: Int) = local.clashfarmer.recognition.RecognizedBox(text, l, t, r, b)

    private val quitCancelBox = box("CaNcel", 605, 445, 715, 480)
    private val quitOkayBox = box("Okay", 895, 445, 985, 480)

    private fun tapPoints(phone: WallPhone) = phone.taps.filter { it.contains(' ') }.map {
        val (x, y) = it.substringAfter(' ').split(',').map(String::toInt)
        Point(x, y)
    }

    private fun within(p: Point, b: local.clashfarmer.recognition.RecognizedBox) = p.x in b.left..b.right && p.y in b.top..b.bottom

    private fun assertQuitPromptCancelled(buttons: List<local.clashfarmer.recognition.RecognizedBox>) {
        val phone = WallPhone(gold = 10_000_000L, start = "home", quitPrompt = quitFrame(buttons))
        // The prompt's 0/6 reads as no builder free, so the phase is skipped and only the walk home runs.
        val outcome = run(phone, order(balance = 10_000_000L, reserve = 10_000_000L))
        assertEquals(WallPhaseOutcome.NoBuilder, outcome)
        val points = tapPoints(phone)
        assertTrue("a tap inside Cancel's box: ${phone.taps}", points.any { within(it, quitCancelBox) })
        assertTrue("nothing inside Okay's box: ${phone.taps}", points.none { within(it, quitOkayBox) })
        assertEquals(1, phone.quitCancels)
        assertEquals(0, phone.quitOkays)
        assertEquals(0, phone.backs)
        assertNull("the prompt is closed", phone.quitPrompt)
        assertHome(phone)
    }

    @Test
    fun theQuitPromptIsCancelledNotAccepted() {
        assertQuitPromptCancelled(listOf(quitCancelBox, quitOkayBox))
    }

    @Test
    fun theQuitPromptIsCancelledWhenOkayIsListedFirst() {
        assertQuitPromptCancelled(listOf(quitOkayBox, quitCancelBox))
    }

    @Test
    fun aQuitPromptWithCancelAndOkayReadAsOneBox() {
        // OCR may join the two buttons into one line. findCancelButton takes that box, and its
        // centre (800,462) falls in the gap between the drawn buttons: neither is pressed, the
        // prompt stays up, and the walk home times out. Recorded as found, not as wanted.
        val merged = box("CaNcel Okay", 605, 445, 995, 480)
        val phone = WallPhone(gold = 10_000_000L, start = "home", quitPrompt = quitFrame(listOf(merged)))
        try {
            run(phone, order(balance = 10_000_000L, reserve = 10_000_000L))
            fail("the prompt never closes, so home is never confirmed")
        } catch (e: StopException) {
            assertTrue(e.message.orEmpty().startsWith("The village could not be confirmed after the wall phase"))
        }
        val points = tapPoints(phone)
        assertTrue(points.isNotEmpty())
        assertTrue("every press is the merged box's centre: ${points.toSet()}", points.all { it == merged.center })
        assertEquals(0, phone.quitOkays)
        assertEquals(0, phone.quitCancels)
        assertNotNull(phone.quitPrompt)
    }

    // --- what else can be over the village ----------------------------------------------------

    /** A list that fails after a [state] screen came over the village, so only the walk home is left. */
    private fun overAfterList(phone: WallPhone, state: ScreenState, vararg boxes: local.clashfarmer.recognition.RecognizedBox):
        suspend (Map<String, Long>, Set<Long>) -> UpgradeListResult = { _, _ ->
        phone.over = state to boxes.toList()
        UpgradeListResult.Failed(UpgradeListFailure.UNREADABLE, "a screen came over the village")
    }

    private fun assertListFailed(outcome: WallPhaseOutcome) =
        assertTrue("$outcome", outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.LIST_FAILED)

    @Test
    fun theAttackMenuAndTheArmyScreenAreClosedWithBackNotOpenGround() {
        for ((state, boxes) in listOf(
            ScreenState.MENU to arrayOf(box("Find a Match", 200, 500, 420, 540), box("Single Player", 200, 300, 420, 340)),
            ScreenState.ARMY to arrayOf(box("Army", 700, 40, 900, 80), box("Train Troops", 300, 600, 500, 640))
        )) {
            val phone = WallPhone(gold = 99_000_000L, start = "home")
            assertListFailed(run(phone, select = overAfterList(phone, state, *boxes)))
            assertEquals("on $state", listOf("wall_home"), phone.backWheres)
            assertEquals("on $state: ${phone.taps}", 0, phone.tapsOnOver)
            assertTrue("on $state: ${phone.taps}", phone.taps.none { it.startsWith("tap") || it.startsWith("button") })
            assertNull(phone.over)
        }
    }

    @Test
    fun aBattleOrItsResultGetsNothingAndTheVillageIsNotConfirmed() {
        for ((state, boxes) in listOf(
            ScreenState.BATTLE to arrayOf(box("End Battle", 40, 560, 200, 600)),
            ScreenState.RESULT to arrayOf(box("Return Home", 700, 600, 900, 640))
        )) {
            val phone = WallPhone(gold = 99_000_000L, start = "home")
            try {
                run(phone, select = overAfterList(phone, state, *boxes))
                fail("on $state the village is never confirmed")
            } catch (e: StopException) {
                assertTrue(e.message.orEmpty().startsWith("The village could not be confirmed after the wall phase"))
            }
            assertEquals("on $state: ${phone.taps}", 0, phone.gestures)
            assertEquals(0, phone.backs)
            assertNotNull(phone.over)
        }
    }

    @Test
    fun theGemStoreIsLeftWithBackBeforeAnythingElse() {
        val phone = WallPhone(gold = 99_000_000L, start = "home")
        // The store reads as no state of its own: its packs and prices are what give it away.
        val outcome = run(phone, select = overAfterList(phone, ScreenState.UNKNOWN,
            box("Pile of Gems", 300, 420, 460, 450), box("$4.99", 330, 560, 430, 590), box("Okay", 880, 420, 1000, 460)))
        assertListFailed(outcome)
        assertEquals(listOf("gem_shop"), phone.backWheres)
        assertEquals(0, phone.tapsOnOver)
        assertNull(phone.over)
    }

    @Test
    fun aWallSelectionOverTheVillageIsStillClearedOnOpenGround() {
        val phone = WallPhone(gold = 99_000_000L, start = "home")
        assertListFailed(run(phone, select = overAfterList(phone, ScreenState.WALL)))
        assertEquals("tap 280,330", phone.taps.single())
        assertEquals(0, phone.backs)
        assertNull(phone.over)
    }

    // --- the order -----------------------------------------------------------------------------

    @Test
    fun anOrderForACurrencyWallsCannotSpendIsInvalid() {
        val phone = WallPhone(start = "home")
        val outcome = run(phone, order(currency = Resource.DARK_ELIXIR))
        assertTrue(outcome is WallPhaseOutcome.Failed && outcome.reason == WallPhaseFailure.INVALID_ORDER)
        assertEquals(0, phone.gestures)
    }

    /** 18.647, Sweat with gold for one wall: no Upgrade More, so that one wall is bought alone. */
    @Test fun aPanelWithoutUpgradeMoreBuysOneWall() {
        val phone = WallPhone(gold = 99_000_000L, level = 17, upgradeMore = false)
        bought(phone, 1, 5_600_000L)
        assertEquals(1, phone.confirmTaps)
    }

    /** RT-7: the one-wall path is for one wall only; a title counting two is never bought. */
    @Test fun aPanelWithoutUpgradeMoreThatCountsTwoWallsBuysNothing() {
        val atStart = WallPhone(gold = 99_000_000L, level = 17, upgradeMore = false, titleOverride = "Wall (Level 17)x 2")
        assertRefused(run(atStart), WallPhaseFailure.MULTIPLE_SELECTION, atStart)
        assertEquals(0, atStart.upgradePresses)

        // The title turns to two after the balance read, so it is the growth step that meets it.
        val later = WallPhone(gold = 99_000_000L, level = 17, upgradeMore = false)
        later.barGold = { later.titleOverride = "Wall (Level 17)x 2"; 99_000_000L }
        assertRefused(run(later), WallPhaseFailure.SELECTION_REFUSED, later)
        assertEquals(0, later.upgradePresses)
    }
}
