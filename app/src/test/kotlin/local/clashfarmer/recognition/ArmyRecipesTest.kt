package local.clashfarmer.recognition

import local.clashfarmer.core.Action
import local.clashfarmer.core.Point
import local.clashfarmer.core.RecoveryPolicy
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Session
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The army screen's tabs and Blood's Saved Recipes list, from the boxes OCR read on the phone at
 * 1600x720. The list reads "Imported Army 1", "Imported Army 2", "Army 3", "Army 5" and
 * "Imported Army 6" top to bottom. Each frame is kept exactly as read.
 */
class ArmyRecipesTest {

    private val screen = Viewport(0, 0, 1600, 720)

    /** "text@left,top,right,bottom | ..." as an inspect line logs it. */
    private fun frame(ocr: String): List<RecognizedBox> = ocr.split(" | ").map { part ->
        val at = part.lastIndexOf('@')
        val (l, t, r, b) = part.substring(at + 1).split(',').map { it.toInt() }
        RecognizedBox(part.substring(0, at), l, t, r, b)
    }

    /** The list at its top: audit-evidence/recipe/inspect-10-top.jsonl, 15 October 00:57 UTC. */
    private val top = frame(
        "X1@221,169,233,178 | IMPORted ARMY 1@252,124,428,145 | Activate@223,211,276,222 | 12@293,465,307,476 | " +
            "MY ARMY@356,55,460,72 | IMPORted ARMY 2@252,375,431,393 | ARMY 3@251,623,322,639 | X11-@293,662,325,678 | " +
            "X6@369,167,388,178 | 12@449,468,458,474 | 12@598,464,609,476 | X1@671,168,687,178 | 13@675,468,684,476 | " +
            "Saved Recipes@726,57,874,74 | x2@821,414,840,427 | Cookbook@1120,57,1221,71 | " +
            "340/352n/n 3/3@926,127,1175,148 | 60/61@1227,134,1269,145 | 352/352n 3/3 A61/61@928,374,1269,397 | " +
            "X3@1123,416,1142,427 | 352/352nn 3/3 60/61@925,623,1269,643 | Use@1334,171,1373,189 | " +
            "Edit@1331,230,1375,249 | SHaRe@1319,287,1384,306 | Use@1334,419,1373,437 | Edit@1331,478,1375,497 | " +
            "SHaRe@1321,536,1385,555 | Use@1334,667,1372,685 | X@1403,45,1425,68 | 23@1446,76,1490,100 | " +
            "45@1446,125,1481,146 | 178@1452,177,1485,197 | 4M@1453,229,1481,244 | FRee!@1452,378,1509,396 | " +
            "071@1474,543,1513,563 | bild@1451,674,1492,696"
    )

    /** One swipe down the list: inspect-12-scrolled.jsonl, 00:58 UTC. Army 3's title is above the list's top. */
    private val scrolled = frame(
        "X11@293,131,313,141 | X4@216,625,240,638 | ARMY 5@251,338,324,353 | 6@300,430,306,438 | " +
            "MY ARMY@356,56,459,72 | X1@372,132,384,141 | OIMPORted ARMY6@175,585,440,604 | 4@376,678,382,686 | " +
            "X1: X1-@447,130,543,143 | X2@453,628,465,639 | X3@527,626,538,640 | Saved Recipes@726,57,874,74 | " +
            "Cookbook@1120,57,1221,71 | o/352@918,588,970,606 | X2@1105,216,1120,226 | " +
            "4d352/352 /o/3 o/61@887,335,1269,357 | nn 3/3 A 60/61@1054,584,1273,607 | Use@1334,133,1373,151 | " +
            "Edit@1331,192,1375,211 | SHaRe@1320,250,1385,268 | Use@1334,381,1373,399 | Edit@1331,440,1375,459 | " +
            "SHaRe@1320,496,1385,517 | Use@1334,630,1373,648 | X@1403,45,1425,68 | 23@1451,76,1488,98 | " +
            "45@1451,128,1481,144 | B78@1452,176,1483,196 | 4M@1452,230,1481,244 | FRee!@1452,378,1509,396 | " +
            "071@1474,543,1513,563 | uild@1450,673,1492,696"
    )

    /** The My Army tab on Clash 18.647: clash-18647/05-army-ocr.ocr.txt, 14 October 05:22 UTC. */
    private val myArmy = frame(
        "239@68,27,114,51 | Ended@100,486,148,499 | Ba1@100,675,152,696 | 83@179,194,196,205 | 4/4@201,149,238,164 | " +
            "66@309,194,329,207 | MY ARMY@356,55,459,72 | 61@449,194,461,204 | 39@579,194,596,205 | " +
            "Saved Recipes@726,57,874,74 | 320/320@742,153,820,172 | X5@724,380,747,398 | PAO/50@726,525,802,552 | " +
            "P0/3 0/2@854,526,1012,550 | Tap to edit@897,611,978,626 | Boost@1060,117,1116,131 | " +
            "ARMY@1063,136,1115,149 | Cookbook@1120,57,1221,71 | Boost@1243,117,1300,131 | HeROes@1238,135,1307,149 | " +
            "o/3 !)@1224,335,1281,355 | Tap to edit@1250,416,1360,432 | AMak!@1268,632,1381,662 | X@1403,45,1425,68 | " +
            "O4@1452,29,1483,49 | 35@1451,128,1481,144 | 911@1454,179,1481,194 | BM@1451,229,1481,244 | " +
            "FRee!@1452,378,1509,396 | 1/2@1476,541,1512,563 | Uild@1449,673,1492,697"
    )

    private fun box(boxes: List<RecognizedBox>, text: String, top: Int) = boxes.single { it.text == text && it.top == top }

    @Test fun eachTabIsToldByWhatItShows() {
        assertEquals(ArmyTab.SAVED_RECIPES, ArmyRecipes.tab(top, screen))
        assertEquals(ArmyTab.SAVED_RECIPES, ArmyRecipes.tab(scrolled, screen))
        assertEquals(ArmyTab.MY_ARMY, ArmyRecipes.tab(myArmy, screen))
    }

    @Test fun neitherOrBothIsUnknown() {
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(emptyList(), screen))
        // The tab labels alone are on both tabs.
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(myArmy.filter { "MY ARMY" in it.text || "Saved" in it.text }, screen))
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(top + myArmy.filter { it.text == "Boost" }, screen))
        // One list button is not a list.
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(top.filter { it.text == "Use" }.take(1), screen))
    }

    @Test fun aStrayEditSplitFromTapToEditStillReadsAsMyArmy() {
        val split = myArmy + RecognizedBox("edit", 1320, 416, 1360, 432)
        assertEquals(ArmyTab.MY_ARMY, ArmyRecipes.tab(split, screen))
    }

    @Test fun aBoostButtonsOtherWordStillReadsAsMyArmy() {
        // Not as read: the My Army frame without its "Boost" and "Tap to edit" boxes, as a running
        // boost's timer might leave it. The buttons' "ARMY" and "HeROes" still tell the tab.
        val boosted = myArmy.filter { it.text != "Boost" && it.text != "Tap to edit" }
        assertEquals(ArmyTab.MY_ARMY, ArmyRecipes.tab(boosted, screen))
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(boosted.filter { it.text != "ARMY" && it.text != "HeROes" }, screen))
        // The tab's own "MY ARMY" is not a boost button's word.
        assertEquals(ArmyTab.SAVED_RECIPES, ArmyRecipes.tab(top, screen))
    }

    @Test fun theStopWhenMyArmyCannotBeToldIsRecoverable() {
        assertTrue(RecoveryPolicy.isRecoverable(ArmyRecipes.TAB_UNKNOWN))
    }

    @Test fun theArmyScreenIsNeverHandedBackToTheRunLoopOnceAttackIsDue() {
        // Why a look after a pause decides there and then: CHECK_ARMY moves the session on to the
        // scout, and the army screen read again would end the run with no restart.
        val session = Session(1)
        session.accept(ScreenState.HOME)
        session.accept(ScreenState.MENU)
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        // A search already begun goes on to these, which the session takes.
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.BATTLE))
        val stop = assertThrows(StopException::class.java) { session.accept(ScreenState.ARMY) }
        assertFalse(RecoveryPolicy.isRecoverable(stop.message))
    }

    @Test fun attackIsSentAfterAPauseOnlyWhileMyArmyShows() {
        val tab = ArmyRecipes.tab(myArmy, screen)
        assertEquals(AttackAfterPause.SEND, ArmyRecipes.attackAfterPause(ScreenState.ARMY, tab, retried = false))
        assertEquals(AttackAfterPause.SEND, ArmyRecipes.attackAfterPause(ScreenState.ARMY, tab, retried = true))
    }

    @Test fun anotherTabAfterAPauseIsReadiedOnceAndThenStops() {
        for (tab in listOf(ArmyRecipes.tab(top, screen), ArmyRecipes.tab(scrolled, screen), ArmyTab.UNKNOWN)) {
            assertEquals("$tab", AttackAfterPause.READY_AGAIN, ArmyRecipes.attackAfterPause(ScreenState.ARMY, tab, retried = false))
            assertEquals("$tab", AttackAfterPause.STOP, ArmyRecipes.attackAfterPause(ScreenState.ARMY, tab, retried = true))
        }
    }

    @Test fun aSearchAlreadyBegunIsNotPressedAgain() {
        for (state in listOf(ScreenState.SEARCHING, ScreenState.SCOUT, ScreenState.BATTLE)) {
            for (tab in ArmyTab.values()) for (retried in listOf(false, true)) {
                assertEquals("$state", AttackAfterPause.PASSED, ArmyRecipes.attackAfterPause(state, tab, retried))
            }
        }
    }

    @Test fun anyOtherScreenAfterAPauseStopsAndRecovers() {
        val others = ScreenState.values().toSet() -
            setOf(ScreenState.ARMY, ScreenState.SEARCHING, ScreenState.SCOUT, ScreenState.BATTLE)
        assertEquals(setOf(ScreenState.HOME, ScreenState.MENU, ScreenState.REWARD, ScreenState.RESULT,
            ScreenState.WALL, ScreenState.UNKNOWN), others)
        for (state in others) for (tab in ArmyTab.values()) for (retried in listOf(false, true)) {
            assertEquals("$state", AttackAfterPause.STOP, ArmyRecipes.attackAfterPause(state, tab, retried))
        }
        // The runner stops with this, which Back and a new runner pick up from.
        assertTrue(RecoveryPolicy.isRecoverable(ArmyRecipes.TAB_UNKNOWN))
    }

    @Test fun aClippedSavedRecipesLabelStillNamesTheTab() {
        // Not as read: the label clipped as the 22 September text read it, "aved Recipes".
        val clipped = myArmy.map { if (it.text == "Saved Recipes") RecognizedBox("aved Recipes", 738, 57, 874, 74) else it }
        assertEquals(Point(806, 65), ArmyRecipes.savedRecipesLabel(clipped, screen))
        // Cookbook beside it is not the tab.
        assertNull(ArmyRecipes.savedRecipesLabel(clipped.filter { it.text != "aved Recipes" }, screen))
    }

    @Test fun theTabLabelsAreTheirOcrBoxes() {
        assertEquals(Point(800, 65), ArmyRecipes.savedRecipesLabel(top, screen))
        assertEquals(Point(800, 65), ArmyRecipes.savedRecipesLabel(myArmy, screen))
        assertEquals(Point(408, 63), ArmyRecipes.myArmyLabel(top, screen))
        assertEquals(Point(407, 64), ArmyRecipes.myArmyLabel(scrolled, screen))
        // The Boost Army button's "ARMY" is not the tab.
        assertNull(ArmyRecipes.myArmyLabel(myArmy.filter { it.text != "MY ARMY" }, screen))
        assertNull(ArmyRecipes.savedRecipesLabel(myArmy.filter { it.text != "Saved Recipes" }, screen))
    }

    @Test fun eachCardInViewIsFoundWithTheUseBelowItsTitle() {
        assertEquals(RecipeCard(box(top, "IMPORted ARMY 1", 124), box(top, "Use", 171)),
            ArmyRecipes.find(top, screen, "Imported Army 1"))
        assertEquals(RecipeCard(box(top, "IMPORted ARMY 2", 375), box(top, "Use", 419)),
            ArmyRecipes.find(top, screen, "Imported Army 2"))
        assertEquals(RecipeCard(box(top, "ARMY 3", 623), box(top, "Use", 667)),
            ArmyRecipes.find(top, screen, "Army 3"))
        assertEquals(RecipeCard(box(scrolled, "ARMY 5", 338), box(scrolled, "Use", 381)),
            ArmyRecipes.find(scrolled, screen, "Army 5"))
    }

    @Test fun theDragHandleMergedIntoATitleIsForgiven() {
        assertEquals(RecipeCard(box(scrolled, "OIMPORted ARMY6", 585), box(scrolled, "Use", 630)),
            ArmyRecipes.find(scrolled, screen, "Imported Army 6"))
        // ...but not as a way into another title.
        assertNull(ArmyRecipes.find(scrolled, screen, "Army 6"))
    }

    @Test fun aCardOutOfViewIsNotFound() {
        assertNull(ArmyRecipes.find(top, screen, "Army 5"))
        assertNull(ArmyRecipes.find(top, screen, "Imported Army 6"))
        // Army 3's Use is at the top of the scrolled list, but its title is not.
        assertNull(ArmyRecipes.find(scrolled, screen, "Army 3"))
        assertNull(ArmyRecipes.find(myArmy, screen, "Army 5"))
    }

    @Test fun anImportedArmyIsNotTheArmyWithItsNumber() {
        assertNull(ArmyRecipes.find(top, screen, "Army 1"))
        assertNull(ArmyRecipes.find(top, screen, "Army 2"))
        assertNull(ArmyRecipes.find(scrolled, screen, "Imported Army 5"))
    }

    @Test fun aUseWhereBoostHeroesIsOnTheOtherTabIsNotTaken() {
        // Not as read: the scrolled frame with Army 3's title moved down into view above its Use@1334,133.
        val raised = scrolled + RecognizedBox("ARMY 3", 251, 96, 322, 112)
        assertNull(ArmyRecipes.find(raised, screen, "Army 3"))
    }

    @Test fun theUseIsStillWhereItWasOnlyForTheSameCardAndPlace() {
        val use = box(scrolled, "Use", 381).center
        // Read again after a pause, give or take OCR's few pixels.
        assertTrue(ArmyRecipes.useStillAt(ArmyRecipes.find(scrolled, screen, "Army 5"), use, screen))
        val nudged = scrolled.map { if (it.text == "Use" && it.top == 381) RecognizedBox("Use", 1336, 384, 1375, 402) else it }
        assertTrue(ArmyRecipes.useStillAt(ArmyRecipes.find(nudged, screen, "Army 5"), use, screen))
        // A list that slid on, or a card gone, is not.
        val slid = scrolled.map { RecognizedBox(it.text, it.left, it.top - 10, it.right, it.bottom - 10) }
        assertFalse(ArmyRecipes.useStillAt(ArmyRecipes.find(slid, screen, "Army 5"), use, screen))
        assertFalse(ArmyRecipes.useStillAt(ArmyRecipes.find(top, screen, "Army 5"), use, screen))
        assertFalse(ArmyRecipes.useStillAt(null, use, screen))
    }

    /** Not as read: the scrolled frame [dy] px lower, as a list still coasting up would show it. */
    private fun coasting(dy: Int) = scrolled.map { RecognizedBox(it.text, it.left, it.top + dy, it.right, it.bottom + dy) }

    private fun useCheck(boxes: List<RecognizedBox>, last: List<RecognizedBox>, looks: Int): UseCheck {
        val use = ArmyRecipes.find(last, screen, "Army 5")!!.use.center
        return ArmyRecipes.useCheck(ArmyRecipes.tab(boxes, screen), ArmyRecipes.find(boxes, screen, "Army 5"), use, looks, screen)
    }

    @Test fun aUseReadInOnePlaceTwiceIsTapped() {
        val nudged = scrolled.map { if (it.text == "Use" && it.top == 381) RecognizedBox("Use", 1336, 384, 1375, 402) else it }
        assertEquals(UseCheck.TAP, useCheck(scrolled, scrolled, looks = 2))
        assertEquals(UseCheck.TAP, useCheck(nudged, scrolled, looks = 2))
    }

    @Test fun aListStillCoastingIsReadAgainUntilTwoReadsAgree() {
        assertEquals(UseCheck.LOOK_AGAIN, useCheck(coasting(10), coasting(20), looks = 2))
        // The third look is measured from the second read, not the first.
        assertEquals(UseCheck.TAP, useCheck(coasting(10), coasting(10), looks = 3))
        assertEquals(UseCheck.MOVED, useCheck(scrolled, coasting(10), looks = 3))
    }

    @Test fun aCardNotReadIsLookedForAgainWhileLooksAreLeft() {
        val unread = scrolled.filter { it.text != "ARMY 5" }
        assertEquals(ArmyTab.SAVED_RECIPES, ArmyRecipes.tab(unread, screen))
        assertEquals(UseCheck.LOOK_AGAIN, useCheck(unread, scrolled, looks = 2))
        assertEquals(UseCheck.MOVED, useCheck(unread, scrolled, looks = 3))
    }

    @Test fun anotherTabIsTheListLostWhateverTheFrameReads() {
        assertEquals(UseCheck.LOST_LIST, useCheck(myArmy, scrolled, looks = 2))
        // Both tabs read at once cannot be told, even with the card where it was.
        val both = scrolled + myArmy.filter { it.text == "ARMY" || it.text == "HeROes" }
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(both, screen))
        assertEquals(UseCheck.LOST_LIST, useCheck(both, scrolled, looks = 2))
    }

    @Test fun aLookAfterAPauseBeforeUseHasNoneToSpare() {
        assertEquals(UseCheck.TAP, useCheck(scrolled, scrolled, ArmyRecipes.USE_LOOKS))
        assertEquals(UseCheck.MOVED, useCheck(coasting(10), scrolled, ArmyRecipes.USE_LOOKS))
        assertEquals(UseCheck.MOVED, useCheck(top, scrolled, ArmyRecipes.USE_LOOKS))
    }

    @Test fun titlesIgnoreCaseAndSpaces() {
        assertTrue(ArmyRecipes.titleMatches("ARMY 5", "Army 5", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("ARMY5", "army 5", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("IMPORted ARMY 2", "Imported Army 2", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("VALKS", "Valks", strayAllowed = false))
        assertFalse(ArmyRecipes.titleMatches("ARMY 5", "Army 6", strayAllowed = false))
        assertFalse(ArmyRecipes.titleMatches("ARMV 5", "Army 5", strayAllowed = false))
    }

    @Test fun theNumberForgivesItsLookAlikes() {
        assertTrue(ArmyRecipes.titleMatches("ARMY S", "Army 5", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("ARMY O", "Army 0", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("ARMY IO", "Army 10", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("ARMY l", "Army 1", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("ARMY |", "Army 1", strayAllowed = false))
        // Only in the number: the S in a word stays an S.
        assertFalse(ArmyRecipes.titleMatches("5PAM", "Spam", strayAllowed = false))
    }

    @Test fun anotherNumberOrALongerTitleIsNotTheOne() {
        assertFalse(ArmyRecipes.titleMatches("ARMY 15", "Army 5", strayAllowed = false))
        assertFalse(ArmyRecipes.titleMatches("ARMY 15", "Army 5", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("ARMY 51", "Army 5", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("IMPORted ARMY 5", "Army 5", strayAllowed = false))
        assertFalse(ArmyRecipes.titleMatches("IMPORted ARMY 5", "Army 5", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("ARMY 5", "Imported Army 5", strayAllowed = true))
    }

    @Test fun oneLeadingCharacterGoesOnlyLeftOfTheTitleColumn() {
        assertTrue(ArmyRecipes.titleMatches("OIMPORted ARMY6", "Imported Army 6", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("OIMPORted ARMY6", "Imported Army 6", strayAllowed = false))
        assertTrue(ArmyRecipes.titleMatches("OARMY 5", "Army 5", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("OOARMY 5", "Army 5", strayAllowed = true))
    }

    @Test fun aBlankTitleMatchesNothing() {
        assertFalse(ArmyRecipes.titleMatches("ARMY 5", "", strayAllowed = true))
        assertFalse(ArmyRecipes.titleMatches("-", " - ", strayAllowed = true))
        assertNull(ArmyRecipes.find(top, screen, " "))
    }

    @Test fun theTitleColumnShowsWhetherASwipeMovedTheList() {
        assertEquals(ArmyRecipes.titles(top, screen), ArmyRecipes.titles(top.reversed(), screen))
        assertTrue(ArmyRecipes.titles(top, screen) != ArmyRecipes.titles(scrolled, screen))
        assertEquals(listOf("importedarmy1", "x1", "activate", "importedarmy2", "12", "army3", "x11"),
            ArmyRecipes.titles(top, screen))
    }

    @Test fun theLoadToastIsRead() {
        assertTrue(ArmyRecipes.loadToast("Recipe Army 5 set as active army!"))
        assertTrue(ArmyRecipes.loadToast("Recipe ARMY 5 set as\nactive ARMY!"))
        assertFalse(ArmyRecipes.loadToast(top.joinToString("\n") { it.text }))
        assertFalse(ArmyRecipes.loadToast(myArmy.joinToString("\n") { it.text }))
    }

    @Test fun eachLookSaysWhereTheTitleAndUseWere() {
        assertEquals("title@345 use@1353,390", ArmyRecipes.describe(scrolled, screen, "Army 5"))
        assertEquals("no title", ArmyRecipes.describe(top, screen, "Army 5"))
        assertEquals("no title", ArmyRecipes.describe(myArmy, screen, "Army 5"))
        val noUse = scrolled.filterNot { it.text == "Use" && it.top == 381 }
        assertEquals("title@345 no use", ArmyRecipes.describe(noUse, screen, "Army 5"))
        val raised = scrolled + RecognizedBox("ARMY 3", 251, 96, 322, 112)
        assertEquals("title@104 use@1353,142 too high", ArmyRecipes.describe(raised, screen, "Army 3"))
    }

    @Test fun theListIsHeldStillBeforeItIsReadAndTheFindIsTriedTwice() {
        assertTrue(ArmyRecipes.HOLD_MS in 250L..300L)
        assertEquals(2, ArmyRecipes.FIND_TRIES)
    }

    @Test fun aMissBeforeUseIsTriedOnceMoreAndAUseAPauseCutShortNever() {
        fun run(vararg misses: String?): Pair<String?, List<String>> {
            val calls = mutableListOf<String>()
            val end = ArmyRecipes.findTries(again = { calls += "again" }) { tries ->
                calls += "find $tries"
                misses[tries - 1]
            }
            return end to calls
        }
        assertEquals(null to listOf("find 1"), run(null))
        // No Use went out: the same label tap, swipes and reads again.
        for (miss in listOf("no_list", "not_found", "lost_list", "moved")) {
            assertEquals(null to listOf("find 1", "again", "find 2"), run(miss, null))
            assertEquals(miss to listOf("find 1", "again", "find 2"), run(miss, miss))
        }
        // Use may have gone out on the first try: a second find would press it again.
        assertEquals(ArmyRecipes.USE_INTERRUPTED to listOf("find 1"), run(ArmyRecipes.USE_INTERRUPTED, null))
        assertEquals(ArmyRecipes.USE_INTERRUPTED to listOf("find 1", "again", "find 2"), run("moved", ArmyRecipes.USE_INTERRUPTED))
    }

    private val afterPauseChecks = UseCheck.values().filter { it != UseCheck.TAP }
    private val afterPauseTexts =
        listOf(top.joinToString("\n") { it.text }, myArmy.joinToString("\n") { it.text }, "Recipe Army 5 set as active army!")

    /** How many finds [findTries] runs when the first ends in [miss] and a second would press Use. */
    private fun findsAfter(miss: String?): Int {
        var finds = 0
        ArmyRecipes.findTries(again = {}) { finds++; if (finds == 1) miss else null }
        return finds
    }

    @Test fun aUseAPauseCutShortEndsTheLoadWhateverTheLookShows() {
        val (list, onMyArmy, toast) = afterPauseTexts
        for (check in afterPauseChecks) {
            // The toast or My Army: the tap went through, and the load is confirmed as after any Use.
            assertNull(ArmyRecipes.afterInterruptedUse(true, check, ArmyTab.MY_ARMY, onMyArmy))
            assertNull(ArmyRecipes.afterInterruptedUse(true, check, ArmyTab.UNKNOWN, toast))
            // The list with the card moved, another screen, or a tab that cannot be told.
            for (tab in listOf(ArmyTab.SAVED_RECIPES, ArmyTab.UNKNOWN)) {
                assertEquals(ArmyRecipes.USE_INTERRUPTED, ArmyRecipes.afterInterruptedUse(true, check, tab, list))
            }
        }
        // Whatever the looks show, a tap that went out is never followed by another find.
        for (check in afterPauseChecks) for (tab in ArmyTab.values()) for (text in afterPauseTexts) {
            assertEquals("$check $tab $text", 1, findsAfter(ArmyRecipes.afterInterruptedUse(true, check, tab, text)))
        }
    }

    @Test fun aPauseBeforeTheUseTapWentOutIsAMissTriedOnceMore() {
        val (list, onMyArmy, _) = afterPauseTexts
        // My Army after a pause that came before the tap is no load: nothing was pressed.
        assertEquals("lost_list", ArmyRecipes.afterInterruptedUse(false, UseCheck.LOST_LIST, ArmyTab.MY_ARMY, onMyArmy))
        assertEquals("moved", ArmyRecipes.afterInterruptedUse(false, UseCheck.MOVED, ArmyTab.SAVED_RECIPES, list))
        for (check in afterPauseChecks) for (tab in ArmyTab.values()) for (text in afterPauseTexts) {
            val miss = ArmyRecipes.afterInterruptedUse(false, check, tab, text)
            assertEquals("$check $tab", ArmyRecipes.useMiss(check), miss)
            assertEquals("$check $tab", 2, findsAfter(miss))
        }
    }

    @Test fun theListSwipeScalesWithTheViewport() {
        assertEquals(Point(1080, 600) to Point(1080, 300), ArmyRecipes.listSwipe(screen))
        assertEquals(Point(1720, 950) to Point(1720, 500), ArmyRecipes.listSwipe(Viewport(100, 50, 2500, 1130)))
    }

    @Test fun onlyTheMyArmyLabelIsUnderTheBotsPanel() {
        val swipe = ArmyRecipes.listSwipe(screen)
        val clear = listOfNotNull(ArmyRecipes.savedRecipesLabel(top, screen), swipe.first, swipe.second) +
            (top + scrolled).filter { it.text == "Use" }.map { it.center }
        val myArmyLabel = ArmyRecipes.myArmyLabel(top, screen)!!
        val busy = listOf(LiveStatus.State.RUNNING, LiveStatus.State.PAUSED, LiveStatus.State.HELD)
        for (density in listOf(1.75f, 2.0f)) for (state in busy) for (collapsed in listOf(false, true)) {
            val touchable = OverlayGeometry.windows(state, collapsed, density).filter { it.touchable }
            for (p in clear) for (dx in -3..3) for (dy in -3..3) for (w in touchable) {
                assertFalse("$p in $w at $density, collapsed $collapsed", w.contains(p.x + dx, p.y + dy))
            }
            // The open card's header, with Pause and Stop, covers it: the runner hides the panel to tap it.
            if (!collapsed) assertTrue(touchable.any { it.contains(myArmyLabel.x, myArmyLabel.y) })
        }
    }
}
