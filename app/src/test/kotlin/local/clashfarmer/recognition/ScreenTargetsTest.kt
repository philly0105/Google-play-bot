package local.clashfarmer.recognition

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry is the phone's own, 1600x720 landscape, measured on 23 September: the result screen's
 * Return Home at (801, 606), matchmaking's at (135, 640) with "Searching for opponents..." across
 * the middle, the village's Attack! at (134, 680). See audit-evidence/2026-09-23-expansion-progress.md.
 */
class ScreenTargetsTest {

    private val screen = Viewport(0, 0, 1600, 720)
    private val calibrated = Point(800, 624)

    private fun box(text: String, cx: Int, cy: Int, halfWidth: Int = 60, halfHeight: Int = 18) =
        RecognizedBox(text, cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)

    private val resultAnchors = listOf(
        box("Total damage:", 800, 90),
        box("VictoRY", 800, 210),
        box("You got:", 800, 297),
        box("Troops expended:", 800, 472)
    )

    @Test fun returnHomeIgnoresDefenderName() {
        val boxes = listOf(
            RecognizedBox("RETURN OF ELDER", 100, 25, 310, 75),
            RecognizedBox("Return", 740, 570, 860, 610)
        )
        assertEquals(Point(800, 590), returnHomeTarget(boxes, Viewport(0, 0, 1600, 720), null))
    }

    @Test fun aNameBesideTheBonusPanelIsNotTheButton() {
        // 19:36 on 23 September: "RETURN OF ELDER" sat on the base behind the result panel.
        val boxes = resultAnchors + listOf(
            box("RETURN", 1003, 300, 25, 8),
            box("ELDE", 1008, 311, 20, 8),
            box("RetuRN", 802, 606, 42, 10),
            box("HOMe", 801, 625, 30, 10)
        )
        // Without a calibration the line that says Return is the one tapped.
        assertEquals(Point(802, 606), returnHomeTarget(boxes, screen, null))
    }

    @Test fun theWrappedButtonChoosesTheLineNearestTheCalibration() {
        val boxes = resultAnchors + listOf(box("RetuRN", 802, 606), box("HOMe", 801, 625))
        assertEquals(Point(801, 625), returnHomeTarget(boxes, screen, calibrated))
    }

    @Test fun matchmakingsReturnHomeIsNeverTheResultButton() {
        val boxes = listOf(box("SeaRcHiNg foR oppoNeNts...", 800, 395, 225), box("RetuRN", 135, 657), box("HOMe", 135, 680))
        assertNull(returnHomeTarget(boxes, screen, calibrated))
    }

    @Test fun calibrationIsOnlyTrustedOnAPositivelyIdentifiedResult() {
        // The button's words did not come back, but the screen is plainly a result screen.
        assertEquals(calibrated, returnHomeTarget(resultAnchors, screen, calibrated))
        // Nothing says result: no tap anywhere.
        assertNull(returnHomeTarget(listOf(box("Attack!", 134, 680)), screen, calibrated))
        // A calibration outside the button's band is not a valid anchor.
        assertNull(returnHomeTarget(resultAnchors, screen, Point(207, 50)))
    }

    @Test fun twoButtonsFarApartAreAmbiguous() {
        val boxes = resultAnchors + listOf(box("Return", 600, 606), box("Return", 1000, 606))
        assertNull(returnHomeTarget(boxes, screen, null))
    }

    @Test fun victoryIsResultEvenWhenANameSpellsOpp() {
        // 19:00 on 23 September a 62% victory was read as matchmaking. Something on the screen
        // supplied "opp"; a defender's name is the only text a result screen does not fix.
        val boxes = resultAnchors + listOf(
            box("OPPortunist", 1000, 300),
            box("RetuRN", 802, 606),
            box("HOMe", 801, 625)
        )
        assertEquals(ScreenState.RESULT, classifyScreen(boxes, screen))
    }

    @Test fun realMatchmakingIsSearching() {
        val boxes = listOf(box("SeaRcHiNg foR oppoNeNts...", 800, 395, 225), box("RetuRN", 135, 657), box("HOMe", 135, 680))
        assertEquals(ScreenState.SEARCHING, classifyScreen(boxes, screen))
    }

    @Test fun matchmakingWithItsTitleMangledIsStillSearching() {
        // Logged three times as a stray RESULT frame: the title lost its "opp" to "DPPD".
        val boxes = listOf(box("toR DPPDNeRiSoe", 800, 395, 150), box("RetuRN", 135, 657), box("HOMe", 135, 680))
        assertEquals(ScreenState.SEARCHING, classifyScreen(boxes, screen))
    }

    @Test fun aLaboratoryLabelIsNotMatchmaking() {
        // The Sweat village at 04:43 UTC on 29 September, after the lab ran out of research:
        // two "Research" labels across the middle spelt matchmaking's "sear", the village read
        // as a search for three minutes, and the run stopped on the search timeout.
        val boxes = listOf(box("Sweat", 150, 20), box("Research", 1045, 295), box("Upgrade", 1130, 330),
            box("Research", 1130, 368), box("Attack!", 134, 680), box("SHOP", 1468, 680))
        assertEquals(ScreenState.HOME, classifyScreen(boxes, screen))
    }

    @Test fun unableToAttackIsNotAResult() {
        // "Unable to attack village! Try again later." with the second sentence mangled, over
        // matchmaking's Return Home. The run used to call it a result screen and stop.
        val boxes = listOf(box("UNable tD ariadt Vilage!", 800, 360, 200), box("Tav agatu lateR", 800, 400),
            box("RetuRN", 135, 657), box("HOMe", 135, 680))
        assertEquals(ScreenState.UNKNOWN, classifyScreen(boxes, screen))
    }

    @Test fun aLoadingTipAboutAttackingIsNotTheVillage() {
        val boxes = listOf(box("Tip: attack when the shield is down", 800, 650, 300), box("CLASH of CLANS", 800, 200))
        assertEquals(ScreenState.UNKNOWN, classifyScreen(boxes, screen))
    }

    @Test fun theVillageIsHomeByItsAttackButton() {
        val boxes = listOf(box("Blood", 150, 20), box("Attack!", 134, 680), box("SHOP", 1468, 680))
        assertEquals(ScreenState.HOME, classifyScreen(boxes, screen))
    }

    @Test fun attackAndShopElsewhereAreNotTheVillage() {
        // "Attacks Completed!" on the menu, and a shop label, but no Attack! button in its corner.
        val boxes = listOf(box("26 Attacks CoMpleted!", 700, 600), box("SHOP", 1468, 680))
        assertEquals(ScreenState.UNKNOWN, classifyScreen(boxes, screen))
    }

    @Test fun aBlockingDialogOverTheVillageIsNotHome() {
        val boxes = listOf(box("Attack!", 134, 680), box("SHOP", 1468, 680),
            box("Another device is connecting to this village", 800, 300, 300), box("Reload", 800, 450))
        assertEquals(ScreenState.UNKNOWN, classifyScreen(boxes, screen))
    }

    @Test fun theOtherScreensStillClassifyWithBoxes() {
        val cases = listOf(
            listOf(box("Find a Match", 268, 515), box("Attack!", 134, 680)) to ScreenState.MENU,
            listOf(box("My Army", 400, 64), box("Saved Recipes", 800, 64), box("Attack!", 1320, 647)) to ScreenState.ARMY,
            listOf(box("Battle starts in:", 800, 23), box("Next", 1465, 494), box("End Battle", 143, 537)) to ScreenState.SCOUT,
            listOf(box("Battle ends in: 2m", 800, 23), box("Overall Damage", 1450, 470)) to ScreenState.BATTLE,
            listOf(box("Wall (Level 16)", 800, 478), box("Upgrade", 940, 596), box("Select Row", 660, 596),
                box("Attack!", 134, 680), box("SHOP", 1468, 680)) to ScreenState.WALL,
            listOf(box("Pick a Reward!", 800, 300), box("Gold", 700, 400)) to ScreenState.REWARD
        )
        for ((boxes, expected) in cases) {
            assertEquals(boxes.joinToString { it.text }, expected, classifyScreen(boxes, screen))
        }
    }

    @Test fun positionsScaleWithTheViewport() {
        // The same result screen through a viewport offset and half the size.
        val half = Viewport(100, 50, 900, 410)
        val boxes = listOf(
            RecognizedBox("Total damage:", 480, 85, 520, 95),
            RecognizedBox("RetuRN", 480, 348, 520, 356)
        )
        assertEquals(Point(500, 352), returnHomeTarget(boxes, half, null))
    }

    // The scout's Next at (1440, 495), priced just below it; End Battle at (143, 537).
    private val scoutNext = Point(1440, 495)

    @Test fun nextIsTappedOnlyWhereItsWordWasRead() {
        val boxes = listOf(box("Next", 1440, 490, 40, 14), box("1500", 1458, 532, 32, 14))
        assertEquals(scoutNext, nextButtonTarget(boxes, screen, scoutNext))
        assertEquals(Point(1440, 490), nextButtonTarget(boxes, screen, null))
    }

    @Test fun aCalibratedNextWithoutItsWordIsNotEnoughToSpendGold() {
        assertNull(nextButtonTarget(listOf(box("1500", 1458, 532, 32, 14)), screen, scoutNext))
    }

    @Test fun aBaseNamedNextIsNotTheButton() {
        assertNull(nextButtonTarget(listOf(box("NEXT", 800, 60)), screen, scoutNext))
    }

    @Test fun aCalibrationFarFromTheReadButtonIsDistrusted() {
        assertNull(nextButtonTarget(listOf(box("Next", 1440, 490, 40, 14)), screen, Point(1300, 600)))
    }

    // 06:14 on 25 September: the iPhone opened Sweat mid-battle; audit-evidence/2026-09-25-takeover-now.png.
    private val takeover = listOf(
        box("Battle ends in:", 800, 23), box("End Battle", 148, 536),
        box("Connection lost", 580, 499, 120), box("Another device is connecting to this Village.", 743, 545, 280),
        box("Reload", 790, 648, 56, 20)
    )

    @Test fun reloadIsTheTakeoverDialogsButton() {
        assertEquals(Point(790, 648), reloadTarget(takeover, screen))
    }

    @Test fun theIdleDialogsReloadIsTappedToo() {
        // Nineteen minutes idle on 25 September; audit-evidence/2026-09-25-idle-19min.png.
        val idle = listOf(box("Anyone there?", 570, 499, 110),
            box("You have been disconnected due to inactivity.", 753, 545, 290), box("Reload game", 790, 648, 110, 20))
        assertEquals(Point(790, 648), reloadTarget(idle, screen))
    }

    @Test fun theLostConnectionDialogsTryAgainIsTappedToo() {
        // 12:44 local on 25 September, over matchmaking; audit-evidence/2026-09-25-reload-miss.png.
        val lost = listOf(box("Unable to attack Village! Try again later.", 800, 393, 270),
            box("Connection lost", 578, 486, 115), box("You have lost connection with the server. Check", 768, 531, 305),
            box("your internet connection and try again.", 709, 573, 245), box("Try again", 790, 650, 75, 20),
            box("Return Home", 139, 668))
        assertEquals(Point(790, 650), reloadTarget(lost, screen))
    }

    @Test fun onlyTheWordReloadIsTheButton() {
        assertNull(reloadTarget(listOf(box("Attack!", 134, 680), box("Reloading troops", 790, 648)), screen))
    }

    @Test fun reloadOutsideTheDialogsButtonIsNotTapped() {
        val moved = takeover.dropLast(1) + box("Reload", 790, 300)
        assertNull(reloadTarget(moved, screen))
    }

    @Test fun surrenderIsTheWordInTheBottomLeftButton() {
        val battle = listOf(box("Battle ends in: 2M 295", 800, 40), box("SURReNdeR", 140, 537))
        assertEquals(Point(140, 537), surrenderTarget(battle, screen, null))
        assertEquals(Point(140, 537), surrenderTarget(listOf(box("ENd Battle", 140, 537)), screen, null))
    }

    @Test fun surrenderElsewhereOnScreenIsNotTheButton() {
        // A player or clan name can say anything; only the button's corner counts.
        assertNull(surrenderTarget(listOf(box("Surrender", 800, 300)), screen, null))
    }

    @Test fun aCalibratedEndBattleIsUsedOnlyInTheButtonsCorner() {
        assertEquals(Point(130, 540), surrenderTarget(emptyList(), screen, Point(130, 540)))
        assertNull(surrenderTarget(emptyList(), screen, Point(800, 540)))
    }

    @Test fun okayIsTheWholeWordInTheDialog() {
        val dialog = listOf(box("Are you sure you want to surrender?", 800, 330), box("Cancel", 640, 520), box("Okay", 960, 520))
        assertEquals(Point(960, 520), confirmSurrenderTarget(dialog, screen))
        assertNull(confirmSurrenderTarget(listOf(box("Okay", 960, 60)), screen))
        assertNull(confirmSurrenderTarget(listOf(box("Okay then", 960, 520)), screen))
    }

    @Test fun missingButtonDoesNotConsumeDispatchBudget() {
        // Two frames fire the trigger without the button's word on them; a later one has it.
        val tracker = SurrenderTracker(maxTries = 2, maxMisses = 3)
        val battle = listOf(box("Battle ends in: 2M 295", 800, 40))
        assertNull(tracker.locate(battle, screen, null))
        assertNull(tracker.locate(battle, screen, null))
        assertFalse(tracker.exhausted)
        assertEquals(Point(140, 537), tracker.locate(battle + box("SURReNdeR", 140, 537), screen, null))
        assertEquals(1, tracker.tries)
        assertEquals(2, tracker.misses)
    }

    @Test fun twoSurrenderDispatchesExhaustTheBudget() {
        val tracker = SurrenderTracker(maxTries = 2, maxMisses = 3)
        val battle = listOf(box("Battle ends in: 2M 295", 800, 40), box("SURReNdeR", 140, 537))
        assertEquals(Point(140, 537), tracker.locate(battle, screen, null))
        assertEquals(Point(140, 537), tracker.locate(battle, screen, null))
        assertTrue(tracker.exhausted)
        // No third dispatch: the battle is left to run out on its own.
        assertNull(tracker.locate(battle, screen, null))
    }

    @Test fun surrenderMissesAreBoundedWithoutATap() {
        val tracker = SurrenderTracker(maxTries = 2, maxMisses = 3)
        val battle = listOf(box("Battle ends in: 2M 295", 800, 40))
        repeat(3) { assertNull(tracker.locate(battle, screen, null)) }
        assertTrue(tracker.exhausted)
        // The word reading back in changes nothing once the misses are spent: no tap.
        assertNull(tracker.locate(battle + box("SURReNdeR", 140, 537), screen, null))
        assertEquals(0, tracker.tries)
    }

    @Test fun upgradeListGeometryIsThePhonesOwnAndNeedsNoWallCalibration() {
        assertEquals(Point(1430, 553), builderCounterPoint(screen))
        assertEquals(Point(1520, 28), upgradeListClosePoint(screen))
        assertEquals(Region(649, 100, 985, 555), upgradePopupRegion(screen))
        assertEquals(Point(1330, 500) to Point(1330, 250), upgradeListSwipe(screen, towardsBottom = true))
        assertEquals(Point(1330, 250) to Point(1330, 500), upgradeListSwipe(screen, towardsBottom = false))
        assertEquals(true, isOwnOverlay(box("Pause", 425, 65, 35, 15), screen))
        assertEquals(false, isOwnOverlay(box("Wall x80", 720, 300), screen))
        // Sits left of the popup, clear of the builder counter's own column.
        assertEquals(649 - 8, laboratoryColumnRegion(screen).right)
    }

    @Test fun upgradeListGeometryScalesWithTheViewport() {
        val half = Viewport(0, 0, 800, 360)
        assertEquals(Point(715, 277), builderCounterPoint(half))
        assertEquals(Region(325, 50, 493, 278), upgradePopupRegion(half))
    }
}
