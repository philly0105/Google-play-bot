package local.clashfarmer.core

import local.clashfarmer.core.PopupGuard.Verdict
import local.clashfarmer.core.ReturnHome.Frame
import local.clashfarmer.core.ReturnHome.Step
import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReturnHomeTest {

    private fun start(ownBattle: Boolean = false) = ReturnHome.forStart(startedAt = 0L, ownBattle = ownBattle)
    private fun recovery() = ReturnHome.forRecovery(startedAt = 0L, reason = "Screen transition timed out")
    private fun frame(state: ScreenState, popup: Verdict = Verdict.None, reload: Boolean = false, settings: Boolean = false) =
        Frame(state, popup, reloadDialog = reload, settingsCanHandle = settings, text = "read")

    @Test fun theStartIsDoneAtHome() {
        assertEquals(Step.Done, start().next(frame(ScreenState.HOME), 0))
    }

    @Test fun theStartLeavesWhatSettingsAlreadyHandlesToSettings() {
        // Welcome Back, the Layout Editor and an open Settings panel all read as unknown.
        assertEquals(Step.Done, start().next(frame(ScreenState.UNKNOWN, settings = true), 0))
    }

    @Test fun theStartPressesReturnHomeOnAResult() {
        assertEquals(Step.ReturnHome, start().next(frame(ScreenState.RESULT), 0))
    }

    @Test fun theStartBacksOutOfMenusTheArmyAndPanels() {
        for (state in listOf(ScreenState.MENU, ScreenState.ARMY, ScreenState.WALL)) {
            assertEquals(state.name, Step.Back, start().next(frame(state), 0))
        }
    }

    @Test fun theStartNeverBacksAtAnUnreadScreen() {
        val walk = start()
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), 0))
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), 60_000))
    }

    @Test fun theStartClosesAKnownPopupByTheGuardsTapOnly() {
        val tap = Point(1180, 90)
        assertEquals(
            Step.ClosePopup("event_intro", tap),
            start().next(frame(ScreenState.UNKNOWN, Verdict.Dismiss("event_intro", tap)), 0)
        )
    }

    @Test fun theStartStopsTappingAPopupThatWillNotClose() {
        val walk = start()
        val popup = frame(ScreenState.UNKNOWN, Verdict.Dismiss("reward", Point(800, 600)))
        repeat(ReturnHome.MAX_POPUP_TAPS) { assertTrue(walk.next(popup, 0) is Step.ClosePopup) }
        assertEquals(Step.Wait, walk.next(popup, 0))
    }

    @Test fun theStartWaitsOnAPopupWithNothingSafeToTap() {
        assertEquals(Step.Wait, start().next(frame(ScreenState.UNKNOWN, Verdict.Wait("samsung_update_banner")), 0))
    }

    @Test fun bothWalksBackOutOfAPricedOfferAtOnce() {
        // A priced offer was waited on at the start until 15 October; Back is now its only answer.
        for (walk in listOf(start(), recovery())) {
            assertEquals(Step.Back, walk.next(frame(ScreenState.UNKNOWN, Verdict.RefusedPremium("reward")), 0))
        }
    }

    @Test fun bothWalksBackOutOfTheStoreAtOnceAndAtMostThreeTimes() {
        // The 15 October recovery spent 25 s of waits on the store before its Back.
        val shop = frame(ScreenState.UNKNOWN, Verdict.Shop)
        for (walk in listOf(start(), recovery())) {
            repeat(ReturnHome.MAX_SHOP_BACKS) { assertEquals(Step.Back, walk.next(shop, it * 2_000L)) }
            // Past the cap the store is any unread screen: the start waits, recovery waits 20 s.
            assertEquals(Step.Wait, walk.next(shop, 6_000))
        }
    }

    @Test fun theStoreGetsBackEvenPastTheBound() {
        val walk = start()
        assertEquals(Step.Back, walk.next(frame(ScreenState.UNKNOWN, Verdict.Shop), ReturnHome.START_BOUND_MS))
        val step = walk.next(frame(ScreenState.UNKNOWN, Verdict.Shop), ReturnHome.START_BOUND_MS + 1_000)
        assertEquals(Step.Back, step)
        val stop = walk.next(frame(ScreenState.UNKNOWN, Verdict.Wait("event_intro")), ReturnHome.START_BOUND_MS + 2_000)
        assertTrue(stop is Step.Stop)
    }

    @Test fun aPlainUnreadScreenStillWaitsTwentySecondsInRecovery() {
        val walk = recovery()
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), 0))
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS - 1))
        assertEquals(Step.Back, walk.next(frame(ScreenState.UNKNOWN), RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS))
    }

    @Test fun theGuardIsOnlyAskedAboutUnreadScreens() {
        // A popup verdict on a classified screen is ignored: the army screen still gets Back.
        assertEquals(Step.Back, start().next(frame(ScreenState.ARMY, Verdict.Maintenance), 0))
    }

    @Test fun aBattleTheBotDidNotStartRefusesTheStartOnTheSecondReading() {
        val walk = start()
        assertEquals(Step.Wait, walk.next(frame(ScreenState.BATTLE), 0))
        assertEquals(Step.Stop(ReturnHome.BATTLE_REFUSED), walk.next(frame(ScreenState.BATTLE), 1_000))
    }

    @Test fun aScoutTheBotDidNotStartRefusesTheStartToo() {
        val walk = start()
        walk.next(frame(ScreenState.SCOUT), 0)
        assertEquals(Step.Stop(ReturnHome.BATTLE_REFUSED), walk.next(frame(ScreenState.SCOUT), 1_000))
    }

    @Test fun oneStrayBattleFrameDoesNotRefuse() {
        val walk = start()
        walk.next(frame(ScreenState.BATTLE), 0)
        assertEquals(Step.ReturnHome, walk.next(frame(ScreenState.RESULT), 1_000))
        assertEquals(Step.Wait, walk.next(frame(ScreenState.BATTLE), 2_000))
    }

    @Test fun aRestartWaitsOutItsOwnBattle() {
        val walk = start(ownBattle = true)
        repeat(5) { assertEquals(Step.Wait, walk.next(frame(ScreenState.BATTLE), it * 1_000L)) }
    }

    @Test fun matchmakingIsWaitedOn() {
        assertEquals(Step.Wait, start().next(frame(ScreenState.SEARCHING), 0))
    }

    @Test fun theStartStopsWhenTheBoundRunsOut() {
        val walk = start()
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), ReturnHome.START_BOUND_MS - 1))
        val step = walk.next(frame(ScreenState.UNKNOWN), ReturnHome.START_BOUND_MS)
        assertTrue(step is Step.Stop)
        val reason = (step as Step.Stop).reason
        assertTrue(reason, reason.startsWith("Could not reach the village home screen before the account check within 90s"))
        assertTrue(reason, reason.contains("state: UNKNOWN"))
    }

    @Test fun theTimeoutNamesThePopupThatHeldTheStart() {
        val walk = start()
        walk.next(frame(ScreenState.UNKNOWN, Verdict.Wait("event_intro")), 0)
        val step = walk.next(frame(ScreenState.UNKNOWN, Verdict.Wait("event_intro")), ReturnHome.START_BOUND_MS)
        assertTrue((step as Step.Stop).reason.contains("popup: event_intro"))
    }

    @Test fun homeAtTheLastMomentStillCounts() {
        assertEquals(Step.Done, start().next(frame(ScreenState.HOME), ReturnHome.START_BOUND_MS + 5_000))
    }

    @Test fun theStartTimeoutIsRecoverableButTheRefusalsAreNot() {
        val walk = start()
        val timeout = (walk.next(frame(ScreenState.ARMY), ReturnHome.START_BOUND_MS) as Step.Stop).reason
        assertTrue(RecoveryPolicy.isRecoverable(timeout))
        assertTrue(RecoveryPolicy.isRecoverable(ReturnHome.MAINTENANCE_AT_START))
        assertFalse(RecoveryPolicy.isRecoverable(ReturnHome.BATTLE_REFUSED))
    }

    @Test fun maintenanceAtTheStartStopsWithoutATap() {
        assertEquals(
            Step.Stop(ReturnHome.MAINTENANCE_AT_START),
            start().next(frame(ScreenState.UNKNOWN, Verdict.Maintenance), 0)
        )
    }

    @Test fun aForcedUpdateStopsBothWalks() {
        val update = frame(ScreenState.UNKNOWN, Verdict.UpdateRequired)
        assertEquals(Step.Stop(PopupGuard.UPDATE_REQUIRED), start().next(update, 0))
        assertEquals(Step.Stop(PopupGuard.UPDATE_REQUIRED), recovery().next(update, 0))
    }

    @Test fun aServerDialogAtTheStartEndsTheAttemptForAReload() {
        assertEquals(Step.Stop(PopupGuard.SERVER_DIALOG), start().next(frame(ScreenState.UNKNOWN, reload = true), 0))
        assertEquals(
            Step.Stop(PopupGuard.SERVER_DIALOG),
            start().next(frame(ScreenState.UNKNOWN, Verdict.ServerDialog("idle")), 0)
        )
    }

    // Recovery keeps RecoveryPolicy.stepFor's table and adds the guard's verdicts.

    @Test fun recoveryWaitsOutMaintenanceInsteadOfPressingBack() {
        val walk = recovery()
        val maintenance = frame(ScreenState.UNKNOWN, Verdict.Maintenance)
        assertEquals(Step.WaitOutMaintenance, walk.next(maintenance, 0))
        assertEquals(Step.WaitOutMaintenance, walk.next(maintenance, 30_000))
    }

    @Test fun recoveryClosesAKnownPopup() {
        val tap = Point(1180, 90)
        assertEquals(
            Step.ClosePopup("privacy_notice", tap),
            recovery().next(frame(ScreenState.UNKNOWN, Verdict.Dismiss("privacy_notice", tap)), 0)
        )
    }

    @Test fun recoveryStillBacksAtAnUnreadScreenAfterTwentySeconds() {
        val walk = recovery()
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), 0))
        assertEquals(Step.Back, walk.next(frame(ScreenState.UNKNOWN), RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS))
        // Back starts the unread clock again.
        assertEquals(Step.Wait, walk.next(frame(ScreenState.UNKNOWN), RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS + 1_000))
    }

    @Test fun recoveryLeavesAReloadToTheStart() {
        assertEquals(Step.LeaveToStart, recovery().next(frame(ScreenState.UNKNOWN, reload = true), 0))
        assertEquals(Step.LeaveToStart, recovery().next(frame(ScreenState.UNKNOWN, Verdict.ServerDialog("idle")), 0))
    }

    @Test fun recoveryWaitsOutABattle() {
        val walk = recovery()
        repeat(3) { assertEquals(Step.Wait, walk.next(frame(ScreenState.BATTLE), it * 1_000L)) }
    }

    @Test fun recoveryRunsOutWithItsOwnReason() {
        val walk = recovery()
        assertEquals(
            Step.Stop("Could not get back to the village after: Screen transition timed out"),
            walk.next(frame(ScreenState.MENU), RecoveryPolicy.RECOVERY_TIMEOUT_MS)
        )
    }

    @Test fun maintenanceTimeIsNotChargedToTheRecoveryBound() {
        val walk = recovery()
        walk.next(frame(ScreenState.UNKNOWN, Verdict.Maintenance), 0)
        walk.restartClock(30 * 60_000L)
        assertEquals(Step.Back, walk.next(frame(ScreenState.MENU), 30 * 60_000L + 1_000))
    }

    // The event intro of 8 October, as PopupGuard.decide reads it (see PopupGuardTest).

    private fun box(text: String, x: Int, y: Int) =
        local.clashfarmer.recognition.RecognizedBox(text, x - 60, y - 15, x + 60, y + 15)

    private val sweatHud = listOf(
        box("239", 90, 18), box("Sweat", 180, 18), box("Booso", 300, 20),
        box("0/1", 660, 40), box("O/6", 818, 40), box("NONe", 987, 40),
        box("Request", 797, 220), box("WAR", 790, 283), box("Upglrade", 1185, 300),
        box("18 775 984", 1405, 40), box("25 598 784", 1408, 108),
        box("184 853", 1428, 173), box("2 018", 1441, 238),
        box("SHOP", 1463, 679), box("9", 1514, 582)
    )
    private val bubbleFrame = Frame(
        ScreenState.UNKNOWN,
        PopupGuard.decide(
            sweatHud + listOf(
                box("Y-ouCH! THat daRN PoRtal", 538, 450), box("PendaNt Has MasHed up", 538, 469),
                box("OUR WORIds.", 538, 488)
            ),
            Viewport(0, 0, 1600, 720)
        )
    )

    @Test fun theStartTapsThroughTheEventIntroThenGoesOnToTheAccountCheck() {
        // The frame that stopped Sweat's run at 08:05:04 UTC: bubble, then the village.
        val walk = start()
        assertEquals(
            Step.AdvanceDialogue("Y-ouCH! THat daRN PoRtal PendaNt Has MasHed up OUR WORIds.", Point(538, 469)),
            walk.next(bubbleFrame, 0)
        )
        assertEquals(Step.Done, walk.next(frame(ScreenState.HOME), 3_000))
        assertEquals(1, walk.clearedAfter)
    }

    @Test fun theDialogueBudgetIsTwelveBubblesThenTheStartWaits() {
        val walk = start()
        repeat(PopupGuard.MAX_DIALOGUE_ADVANCES) { assertTrue(walk.next(bubbleFrame, 0) is Step.AdvanceDialogue) }
        assertEquals(Step.Wait, walk.next(bubbleFrame, 0))
    }

    @Test fun aKnownScreenNeverRestoresTheDialogueBudget() {
        // A dialogue match that flickers with a classified frame must not earn a fresh 12 each time.
        val walk = recovery()
        repeat(PopupGuard.MAX_DIALOGUE_ADVANCES - 1) { assertTrue(walk.next(bubbleFrame, 0) is Step.AdvanceDialogue) }
        walk.next(frame(ScreenState.MENU), 0)
        assertEquals(PopupGuard.MAX_DIALOGUE_ADVANCES - 1, walk.clearedAfter)
        assertTrue(walk.next(bubbleFrame, 1_000) is Step.AdvanceDialogue)
        assertEquals(0, walk.clearedAfter)
        walk.next(frame(ScreenState.MENU), 2_000)
        assertEquals(1, walk.clearedAfter)
        // The walk's twelve are spent: the bubble is now an unread screen like any other.
        assertEquals(Step.Wait, walk.next(bubbleFrame, 3_000))
    }

    // The 212-gem "All builders are busy" dialog, audit-evidence/overnight/W1D-after-unverified.png.

    private val builderGemBoxes = listOf(
        box("All builders are busy!", 797, 220),
        box("Complete the previous building and free up a", 797, 321), box("builder?", 797, 348),
        box("212", 775, 452), box("Attack!", 140, 680), box("Shop", 1463, 679)
    )

    private fun tapOf(step: Step): Point? = when (step) {
        is Step.ClosePopup -> step.tap
        is Step.AdvanceDialogue -> step.tap
        else -> null
    }

    @Test fun theBuilderGemDialogIsNeverTappedOnItsGemButton() {
        val verdict = PopupGuard.decide(builderGemBoxes, Viewport(0, 0, 1600, 720))
        for (state in listOf(ScreenState.HOME, ScreenState.UNKNOWN)) {
            val gemDialog = Frame(state, if (state == ScreenState.UNKNOWN) verdict else Verdict.None, text = "212")
            for (walk in listOf(start(), recovery())) {
                var now = 0L
                while (now <= RecoveryPolicy.RECOVERY_TIMEOUT_MS) {
                    val step = walk.next(gemDialog, now)
                    val tap = tapOf(step)
                    assertFalse(
                        "$state $step",
                        tap != null && tap.x in 678..918 && tap.y in 400..510
                    )
                    if (step == Step.Done || step is Step.Stop) break
                    now += 1_000
                }
            }
        }
    }

    // The attack menu read as UNKNOWN, as recovery finds it after "Screen transition timed out
    // waiting for [MENU]". Same logged OCR boxes as PopupGuardTest (599a8ce).

    private val cooldownMenu = listOf(
        box("Multiplayer Battle", 267, 460), box("Attack available in:", 268, 514), box("195", 268, 550),
        box("Star Bonus", 203, 675), box("Single PlayeR", 480, 56), box("Next in: 1oh 53m", 398, 675),
        box("Rank", 500, 198), box("2", 500, 224), box("18719", 620, 150),
        box("Ranked Battle", 618, 461), box("PRactice", 737, 56), box("Attacks CoMpleted!", 619, 512),
        box("12/12", 620, 555), box("Time Left:", 556, 596), box("4d 19h", 724, 596),
        box("SH 14M", 985, 40), box("940 219", 1428, 40), box("218 346", 1433, 108),
        box("470 0O0", 1426, 173), box("967", 1455, 238), box("SHOP", 1468, 679)
    )

    private val joinTournamentMenu = listOf(
        box("Battle", 267, 460), box("FiNd a MatcH", 268, 514), box("1500", 250, 550),
        box("Star Bonus", 203, 675), box("Next in: 14m 27s", 395, 675), box("Ractice", 737, 56),
        box("RaNked Battle", 618, 461), box("Join ToURNaMeNt", 619, 531), box("Signup ends in:", 575, 597),
        box("23h 47m", 712, 597), box("SN 36M", 985, 40), box("Request", 960, 253), box("WAR DOB", 960, 313),
        box("FulL", 960, 645), box("90", 1213, 381), box("Upgrade", 1133, 540),
        box("1136 946", 1420, 40), box("400 000", 1425, 173), box("2 159", 1448, 238), box("SHOP", 1468, 679)
    )

    private fun assertRecoveryBacksOutOfTheMenu(boxes: List<local.clashfarmer.recognition.RecognizedBox>) {
        val menu = Frame(ScreenState.UNKNOWN, PopupGuard.decide(boxes, Viewport(0, 0, 1600, 720)))
        val walk = recovery()
        var now = 0L
        while (now < RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS) {
            assertEquals("at ${now}ms", Step.Wait, walk.next(menu, now))
            now += 2_000
        }
        assertEquals(Step.Back, walk.next(menu, RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS))
        // Nor is the card tapped on any later frame.
        now = RecoveryPolicy.UNKNOWN_BEFORE_BACK_MS
        while (now < RecoveryPolicy.RECOVERY_TIMEOUT_MS) {
            now += 2_000
            val step = walk.next(menu, now)
            assertTrue("$step at ${now}ms", step == Step.Wait || step == Step.Back || step is Step.Stop)
        }
    }

    @Test fun recoveryBacksOutOfTheCooldownMenuWithoutTappingTheRankedCard() {
        assertRecoveryBacksOutOfTheMenu(cooldownMenu)
    }

    @Test fun recoveryBacksOutOfTheJoinTournamentMenuWithoutTappingIt() {
        assertRecoveryBacksOutOfTheMenu(joinTournamentMenu)
    }

    @Test fun backIsOnlySentWhileTheRunMayAct() {
        assertTrue(ReturnHome.mayPressBack(tokenValid = true, paused = false, clashForeground = true, unlocked = true))
        assertFalse(ReturnHome.mayPressBack(tokenValid = false, paused = false, clashForeground = true, unlocked = true))
        assertFalse(ReturnHome.mayPressBack(tokenValid = true, paused = true, clashForeground = true, unlocked = true))
        assertFalse(ReturnHome.mayPressBack(tokenValid = true, paused = false, clashForeground = false, unlocked = true))
        assertFalse(ReturnHome.mayPressBack(tokenValid = true, paused = false, clashForeground = true, unlocked = false))
    }

    @Test fun recoveryTapsThroughTheEventIntroToo() {
        assertTrue(recovery().next(bubbleFrame, 0) is Step.AdvanceDialogue)
    }

    @Test fun stepNamesMatchTheRecoveryLog() {
        assertEquals("DONE", Step.Done.name)
        assertEquals("RETURN_HOME", Step.ReturnHome.name)
        assertEquals("BACK", Step.Back.name)
        assertEquals("WAIT", Step.Wait.name)
        assertEquals("LEAVE_TO_START", Step.LeaveToStart.name)
    }

    // --- Clash's quit prompt (UO-21 review, 15 October) ----------------------------------------

    private val quitCancel = RecognizedBox("CaNcel", 605, 445, 715, 480)
    private val quitOkay = RecognizedBox("Okay", 895, 445, 985, 480)

    /** Where the prompt's Okay is pressed, the surrender confirmation's point; it quits Clash. */
    private val okayPoint = Point(938, 462)

    /**
     * The frame Back on the village opened at 14:05 UTC on 8 October, "Confirm Exit / Do you want
     * to quit the game?", with the boxes WallPhaseTest and PopupGuardTest hold for it. It read as
     * HOME. [buttons] are the button boxes in the order the reader listed them.
     */
    private fun quitBoxes(buttons: List<RecognizedBox> = listOf(quitCancel, quitOkay)) = listOf(
        RecognizedBox("259", 70, 15, 125, 42), RecognizedBox("29", 70, 48, 110, 72),
        RecognizedBox("!", 30, 560, 45, 590), RecognizedBox("Blood", 20, 95, 110, 120),
        RecognizedBox("Attack!", 60, 650, 210, 705), RecognizedBox("Collect", 230, 660, 330, 690),
        RecognizedBox("23d 17h", 1300, 120, 1400, 145), RecognizedBox("DOOst", 1300, 150, 1400, 175),
        RecognizedBox("0/6", 795, 26, 845, 56), RecognizedBox("4H 43M", 1420, 120, 1520, 145),
        RecognizedBox("CONFiRM Bxit", 690, 205, 910, 245),
        RecognizedBox("Do you want to quit the game?", 590, 300, 1010, 335)
    ) + buttons.take(1) + listOf(
        RecognizedBox("Full!", 1380, 20, 1440, 40), RecognizedBox("Full!", 1380, 70, 1440, 90),
        RecognizedBox("Full!", 1380, 120, 1440, 140)
    ) + buttons.drop(1) + listOf(
        RecognizedBox("29 000 000", 1250, 20, 1370, 45), RecognizedBox("29 000 000", 1250, 70, 1370, 95),
        RecognizedBox("472585", 1250, 120, 1370, 145), RecognizedBox("1983", 1460, 180, 1530, 205),
        RecognizedBox("SHOP", 1480, 640, 1560, 680)
    )

    private fun quitFrame(
        state: ScreenState = ScreenState.HOME,
        buttons: List<RecognizedBox> = listOf(quitCancel, quitOkay),
        settings: Boolean = false
    ): Frame {
        val boxes = quitBoxes(buttons)
        return Frame(state, settingsCanHandle = settings, text = boxes.joinToString(" ") { it.text }, boxes = boxes)
    }

    private fun RecognizedBox.holds(p: Point) = p.x in left..right && p.y in top..bottom

    private fun assertOnCancelNeverOkay(tap: Point?) {
        assertNotNull(tap)
        assertTrue("$tap is on Cancel", quitCancel.holds(tap!!))
        assertFalse("$tap is on Okay", quitOkay.holds(tap))
        assertNotEquals(okayPoint, tap)
    }

    private fun assertCancels(step: Step) {
        assertTrue("$step", step is Step.CancelQuitPrompt)
        assertOnCancelNeverOkay((step as Step.CancelQuitPrompt).tap)
    }

    private val bothOrders = listOf(listOf(quitCancel, quitOkay), listOf(quitOkay, quitCancel))

    @Test fun theStartCancelsTheQuitPromptRatherThanTakeItForTheVillage() {
        for (buttons in bothOrders) assertCancels(start().next(quitFrame(buttons = buttons), 0))
        // Read as unknown with the village's words, which the start leaves to openSettings.
        assertCancels(start().next(quitFrame(ScreenState.UNKNOWN, settings = true), 0))
    }

    @Test fun recoveryCancelsTheQuitPromptToo() {
        for (buttons in bothOrders) assertCancels(recovery().next(quitFrame(buttons = buttons), 0))
        assertCancels(recovery().next(quitFrame(ScreenState.UNKNOWN), 0))
    }

    @Test fun theVillageAfterTheCancelIsTheVillage() {
        val walk = start()
        assertCancels(walk.next(quitFrame(), 0))
        assertEquals(Step.Done, walk.next(frame(ScreenState.HOME), 2_000))
    }

    @Test fun aQuitPromptWithItsCancelUnreadIsWaitedOnWithinTheBound() {
        val noCancel = quitFrame(buttons = listOf(quitOkay))
        for (walk in listOf(start(), recovery())) assertEquals(Step.CancelQuitPrompt(null), walk.next(noCancel, 0))
        assertTrue(start().next(noCancel, ReturnHome.START_BOUND_MS) is Step.Stop)
        assertTrue(recovery().next(noCancel, RecoveryPolicy.RECOVERY_TIMEOUT_MS) is Step.Stop)
    }

    @Test fun aQuitPromptThatStaysIsCancelledThreeTimesThenWaitedOutAsARecoverableStop() {
        val walk = start()
        repeat(ReturnHome.MAX_POPUP_TAPS) { assertCancels(walk.next(quitFrame(), 0)) }
        assertEquals(Step.Wait, walk.next(quitFrame(), 0))
        val reason = (walk.next(quitFrame(), ReturnHome.START_BOUND_MS) as Step.Stop).reason
        assertTrue(reason, reason.contains("popup: ${ReturnHome.QUIT_PROMPT}"))
        assertTrue(RecoveryPolicy.isRecoverable(reason))
    }

    /** openSettings and the farm loop's read before Attack ask [ReturnHome.quitPrompt] themselves. */
    @Test fun theQuitPromptsCancelIsItsOwnBoxAndEveryTapOnItStaysThere() {
        for (buttons in bothOrders) {
            val boxes = quitBoxes(buttons)
            val cancel = ReturnHome.quitPrompt(boxes.joinToString(" ") { it.text }, boxes)?.cancel
            assertOnCancelNeverOkay(cancel)
            // tapButton moves a press up to 3 px each way.
            repeat(200) { assertOnCancelNeverOkay(buttonTapPoint(cancel!!, 1600, 720, Random(it))) }
        }
        val okayOnly = quitBoxes(listOf(quitOkay))
        assertEquals(ReturnHome.QuitPrompt(null), ReturnHome.quitPrompt(okayOnly.joinToString(" ") { it.text }, okayOnly))
        val village = quitBoxes().filterNot { it.text.startsWith("CONF") || it.text.contains("quit") }
        assertNull(ReturnHome.quitPrompt(village.joinToString(" ") { it.text }, village))
    }
}
