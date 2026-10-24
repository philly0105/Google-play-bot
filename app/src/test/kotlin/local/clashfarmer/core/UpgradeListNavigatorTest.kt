package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListFixture
import local.clashfarmer.recognition.UpgradeListFixture.Header
import local.clashfarmer.recognition.UpgradeListFixture.Item
import local.clashfarmer.recognition.UpgradeListFixture.Line
import local.clashfarmer.recognition.UpgradeListFixture.gold
import local.clashfarmer.recognition.UpgradeListFixture.screen
import local.clashfarmer.recognition.builderCounterPoint
import local.clashfarmer.recognition.classifyScreen
import local.clashfarmer.recognition.upgradeListClosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The navigator against a phone that behaves as the 29 September captures show: the builder
 * counter opens the list and its red X closes it (since Clash 18.647), a swipe moves the content 370 px unless a test
 * gives the 18.647 panel's held 250, a tap on a Wall row's name selects it and leaves the list open over the panel. No profile is built anywhere in
 * this file: nothing the list needs is a calibrated wall point.
 */
class UpgradeListNavigatorTest {

    private val counter = builderCounterPoint(screen)
    private val close = upgradeListClosePoint(screen)

    /** A scripted phone. Time moves only when the navigator waits, captures or gestures. */
    private class Phone(
        val items: List<Item> = UpgradeListFixture.sweat(),
        var offset: Int = 0,
        var listOpen: Boolean = false,
        /** What the counter opens: the builder list, the laboratory's, or nothing. */
        val counterOpens: String = "builder",
        val listClosesOnRowTap: Boolean = false,
        val counterClosesList: Boolean = true,
        val panelShows: Boolean = true,
        /** The closed panel also draws its totals flush right where the list drew its prices. */
        val panelTotals: Boolean = false,
        /** Captures after the row tap on which the closing list is still drawn. */
        val closingFrames: Int = 0,
        val swipeScrolls: Boolean = true,
        val animate: Boolean = false,
        /**
         * Pixels the first frame after each swipe that ends at the bottom is caught off its rest
         * by, in turn: the list bouncing at its end. Later frames are at rest.
         */
        val bounces: List<Int> = emptyList(),
        /**
         * What the village shows with the list closed, beyond the bot's overlay: Sweat's builder
         * counter unless a test takes it away. A test may change it mid-run, as a pause could.
         */
        var village: List<RecognizedBox> = sweatCounter,
        /** How the village frame with the list closed classifies. */
        var villageState: ScreenState = ScreenState.HOME,
        /**
         * Pixels one swipe moves the content: 370 is a page of the 29 September popup these
         * fixtures draw; a held drag on the 18.647 panel moves its own 250.
         */
        val move: Int = 370
    ) : UpgradeListDevice {
        var time = 0L
        var captures = 0
        var labOpen = false
        var panelOpen = false
        val counterTaps = mutableListOf<Point>()
        val rowTaps = mutableListOf<Point>()
        val missedTaps = mutableListOf<Point>()
        val swipes = mutableListOf<String>()
        val holds = mutableListOf<Long>()
        var captureAtRowTap = -1
        var checkpointHook: () -> Boolean = { false }
        /** Pixels the list is caught off its rest by, for a frame taken mid-fling. */
        var jitter: (Int) -> Int = { 0 }
        /** Frames on which the Wall row's price reads as "5M". */
        var misread: (Int) -> Boolean = { false }

        private val counterPoint = builderCounterPoint(screen)
        private val closePoint = upgradeListClosePoint(screen)
        val maxOffset = maxOf(0, items.last().y + 30 - 555)
        private var bounced = 0
        private var bounce = 0

        override suspend fun capture(): UpgradeListFrame {
            time += 1_100
            captures++
            val index = captures
            if (labOpen) {
                return frameOf(emptyList(), 0, lab)
            }
            if (!listOpen) {
                if (captureAtRowTap >= 0 && captures - captureAtRowTap <= closingFrames) {
                    return frameOf(items, offset, UpgradeListFixture.overlay)
                }
                val extra = if (panelOpen && panelShows) {
                    UpgradeListFixture.overlay + RecognizedBox("Wall (Level 16)", 680, 464, 920, 492) +
                        if (panelTotals) totals else emptyList()
                } else UpgradeListFixture.overlay
                return frameOf(emptyList(), 0, extra + village, if (panelOpen) ScreenState.UNKNOWN else villageState)
            }
            var shown = items
            if (misread(index)) {
                shown = items.map { if (it is Line && it.name.startsWith("Wall x")) Line(it.name, it.y, "5M", it.currency, it.payable) else it }
            }
            if (animate) {
                shown = shown.map { if (it is Line && it.countdown != null) Line(it.name, it.y, countdown = "${index}h 1m") else it }
            }
            val ghost = if (animate) listOf(RecognizedBox("Results", 744, 200 + index * 7 % 300, 800, 220 + index * 7 % 300)) else emptyList()
            val caught = bounce
            bounce = 0
            return frameOf(shown, offset + jitter(index) + caught, UpgradeListFixture.overlay + ghost)
        }

        private fun frameOf(shown: List<Item>, at: Int, extra: List<RecognizedBox>, state: ScreenState = ScreenState.UNKNOWN) =
            UpgradeListFixture.Frame(shown, at, extra, state = state)

        /** The panel's own totals, flush right inside the popup's region like the list's prices. */
        private val totals = listOf(
            RecognizedBox("15 000 000", 866, 489, 970, 511),
            RecognizedBox("15 000 000", 866, 527, 970, 549)
        )

        private val lab = listOf(
            RecognizedBox("Barbarian 12", 515, 118, 640, 140),
            RecognizedBox("Archer 12", 515, 158, 620, 180),
            RecognizedBox("Giant 12", 515, 196, 610, 218),
            RecognizedBox("Wizard 11", 515, 234, 620, 256)
        ) + UpgradeListFixture.overlay

        override suspend fun tap(point: Point) {
            time += 100
            if (listOpen && kotlin.math.abs(point.x - closePoint.x) <= 3 && kotlin.math.abs(point.y - closePoint.y) <= 3) {
                counterTaps += point
                if (counterClosesList) listOpen = false
                return
            }
            if (kotlin.math.abs(point.x - counterPoint.x) <= 3 && kotlin.math.abs(point.y - counterPoint.y) <= 3) {
                counterTaps += point
                if (!listOpen) when (counterOpens) {
                    "builder" -> { listOpen = true; labOpen = false; panelOpen = false }
                    "lab" -> labOpen = true
                }
                return
            }
            if (!listOpen) {
                missedTaps += point
                return
            }
            val hit = items.filterIsInstance<Line>().firstOrNull { line ->
                val y = line.y - offset
                val left = if (line.icon) 699 else 662
                line.name.startsWith("Wall x") && point.y in (y - 11)..(y + 11) && point.x in left..(left + 9 * line.name.length)
            }
            if (hit == null) {
                missedTaps += point
                return
            }
            rowTaps += point
            captureAtRowTap = captures
            panelOpen = true
            if (listClosesOnRowTap) listOpen = false
        }

        override suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long) {
            time += durationMs + holdMs
            val down = from.y < to.y
            swipes += if (down) "down" else "up"
            holds += holdMs
            if (!listOpen || !swipeScrolls) return
            offset = (offset + if (down) -move else move).coerceIn(0, maxOffset)
            if (offset == maxOffset && bounces.isNotEmpty()) bounce = bounces[bounced++ % bounces.size]
        }

        override suspend fun wait(ms: Long) {
            time += ms
        }

        override fun now(): Long = time
        override suspend fun checkpoint(): Boolean = checkpointHook()
    }

    private fun run(
        phone: Phone,
        spendable: Map<String, Long> = mapOf("gold" to UpgradeListFixture.GOLD, "elixir" to UpgradeListFixture.ELIXIR),
        capped: Set<Long> = emptySet(),
        limits: UpgradeListLimits = UpgradeListLimits()
    ): UpgradeListResult = runBlocking { UpgradeListNavigator(phone, screen, limits).findAndSelectWall(spendable, capped) }

    /** Rows one after another from the top, 38 px apart. */
    private fun stack(vararg build: (Int) -> Item): List<Item> = build.mapIndexed { i, make -> make(123 + 38 * i) }

    private fun header(text: String) = { y: Int -> Header(text, y) as Item }
    private fun goldRow(name: String, price: Long) = { y: Int -> gold(name, y, price) as Item }
    /**
     * The game groups one building at one level into one row with a count, so no two rows share a
     * name and a price; the row comparison leaves the count out, as the phone misreads it.
     */
    private fun filler(index: Int) = { y: Int -> gold("Cannon x$index", y, 1_000_000L + index * 10_000L) as Item }

    private val wallX80 = WallCandidate("Wall x80", 80, 5_000_000, "gold")

    private companion object {
        /** Sweat's builder counter and Build as read on 14 October, 01-home. */
        val sweatCounter = listOf(
            RecognizedBox("1/6 1/2", 1411, 540, 1512, 563),
            RecognizedBox("Build", 1432, 674, 1492, 695)
        )

        /**
         * Blood's plain village as the inspect at 15:10:08 UTC on 14 October read it, box for box
         * (clashfarmer-20261014.jsonl line 3198): Battle and Build, and no builder counter, which
         * ML Kit missed on every plain read of Blood's village.
         */
        val bloodVillage = listOf(
            RecognizedBox("259", 69, 27, 113, 53),
            RecognizedBox("Collect", 159, 174, 201, 183),
            RecognizedBox("22h 55m", 87, 485, 163, 499),
            RecognizedBox("Blood", 196, 56, 252, 78),
            RecognizedBox("WAR O08", 181, 82, 253, 97),
            RecognizedBox("Battle", 98, 673, 180, 694),
            RecognizedBox("22M 13s", 273, 48, 308, 54),
            RecognizedBox("22M 14s", 273, 68, 307, 74),
            RecognizedBox("2d13H", 267, 272, 293, 284),
            RecognizedBox("Request", 978, 429, 1025, 440),
            RecognizedBox("Ly", 1342, 642, 1361, 690),
            RecognizedBox("18 497 234", 1367, 29, 1482, 49),
            RecognizedBox("22 263 489", 1363, 77, 1480, 95),
            RecognizedBox("199 198", 1405, 126, 1479, 144),
            RecognizedBox("1761", 1435, 179, 1481, 194),
            RecognizedBox("15M 85", 1415, 225, 1485, 246),
            RecognizedBox("FRee!", 1445, 378, 1509, 397),
            RecognizedBox("Build", 1432, 674, 1492, 696)
        )
    }

    @Test fun bloodsVillageWithNoCounterReadIsTheVillageAndItsCounterIsTapped() {
        // UO-21 review, 15 October: a counter read asked for on top of the village opened no list
        // on Blood, whose counter ML Kit missed on all 7 plain village reads.
        val state = classifyScreen(bloodVillage, screen)
        assertEquals(ScreenState.HOME, state)
        assertNull(Builders.read(bloodVillage, screen))
        val phone = Phone(village = bloodVillage, villageState = state)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(listOf(counter, close), phone.counterTaps)
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun opensTheListScrollsToTheWallTapsItAndClosesTheListOnItsPanel() {
        val phone = Phone()
        val result = run(phone)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), result)
        // Opened once by the builder counter, closed once by the list's X.
        assertEquals(listOf(counter, close), phone.counterTaps)
        assertEquals(listOf("up", "up"), phone.swipes)
        assertEquals(1, phone.rowTaps.size)
        assertTrue("the tap landed on the wall row's name", phone.missedTaps.isEmpty())
        assertTrue(phone.rowTaps.single().x in 662..740)
        assertTrue(!phone.listOpen)
    }

    @Test fun anAlreadyOpenListIsNotTappedOpenAgain() {
        val phone = Phone(listOpen = true)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        // The one counter tap is the close; a tap on an open list's counter would shut it.
        assertEquals(1, phone.counterTaps.size)
        assertTrue(!phone.listOpen)
    }

    @Test fun aListThatClosesItselfOnTheRowTapIsNotTappedShutIntoOpeningAgain() {
        val phone = Phone(listClosesOnRowTap = true)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(1, phone.counterTaps.size)
        assertTrue(!phone.listOpen)
    }

    @Test fun panelTotalsDoNotReopenClosedUpgradeList() {
        // The totals pass for list rows; only the missing header says the list is gone.
        val phone = Phone(listClosesOnRowTap = true, panelTotals = true)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(1, phone.counterTaps.size)
        assertTrue(!phone.listOpen)
    }

    @Test fun oneAnimationFrameDoesNotCauseCloseTap() {
        // The list still drawn on one frame while it slides away is not the list staying open.
        val phone = Phone(listClosesOnRowTap = true, closingFrames = 1)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(1, phone.counterTaps.size)
        assertTrue(!phone.listOpen)
    }

    @Test fun theLaboratorysListIsTheWrongListAndIsNeverScrolled() {
        val phone = Phone(counterOpens = "lab")
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.WRONG_LIST, result.reason)
        assertTrue(phone.swipes.isEmpty())
        assertTrue(phone.rowTaps.isEmpty())
    }

    @Test fun aListThatWillNotOpenFailsWithoutScrollingOrTappingRows() {
        val phone = Phone(counterOpens = "nothing")
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.LIST_NOT_OPEN, result.reason)
        assertTrue(phone.swipes.isEmpty())
        assertTrue(phone.rowTaps.isEmpty())
        assertEquals(1, phone.counterTaps.size)
    }

    @Test fun aListScrolledDownIsBroughtBackToItsTopBeforeScanning() {
        val phone = Phone(listOpen = true, offset = 740)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(listOf("down", "down", "up", "up"), phone.swipes)
    }

    @Test fun anUnaffordableWallIsSkippedToReachACheaperOneBelow() {
        val items = stack(
            header("Upgrades in progress:"), header("Other upgrades:"),
            goldRow("Wall x30", 8_000_000),
            *Array(12) { filler(it) },
            goldRow("Wall x50", 5_600_000),
            *Array(4) { filler(20 + it) }
        )
        val phone = Phone(items)
        val result = run(phone) as UpgradeListResult.Selected
        assertEquals(WallCandidate("Wall x50", 50, 5_600_000, "gold"), result.candidate)
        assertTrue("scrolled past the dearer wall", phone.swipes.isNotEmpty())
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun aPriceOnTheCappedListIsSkipped() {
        val items = stack(
            header("Upgrades in progress:"), header("Other upgrades:"),
            goldRow("Wall x80", 5_000_000), goldRow("Wall x20", 4_000_000), goldRow("Town Hall", 16_000_000)
        )
        val result = run(Phone(items), capped = setOf(5_000_000L)) as UpgradeListResult.Selected
        assertEquals(WallCandidate("Wall x20", 20, 4_000_000, "gold"), result.candidate)
    }

    @Test fun theFirstAffordableWallInScanOrderWinsWhateverItsPrice() {
        val items = stack(
            header("Upgrades in progress:"), header("Other upgrades:"),
            goldRow("Wall x20", 4_000_000), goldRow("Wall x80", 5_000_000)
        )
        val result = run(Phone(items)) as UpgradeListResult.Selected
        assertEquals("Wall x20", result.candidate.name)
    }

    @Test fun aRowWhoseColourSaysUnaffordableIsSkippedEvenIfTheBalanceLooksEnough() {
        val items = stack(
            header("Upgrades in progress:"), header("Other upgrades:"),
            { y -> Line("Wall x80", y, "5 000 000", "gold", payable = false) }
        )
        val result = run(Phone(items), spendable = mapOf("gold" to 50_000_000L))
        assertTrue(result is UpgradeListResult.Empty)
    }

    @Test fun displayedCurrencyDoesNotHideAnEligibleWall() {
        val items = stack(
            header("Upgrades in progress:"), header("Other upgrades:"),
            goldRow("Wall x80", 5_000_000)
        )
        assertTrue(run(Phone(items), spendable = mapOf("elixir" to 9_000_000L)) is UpgradeListResult.Selected)
        assertTrue(run(Phone(items), spendable = mapOf("gold" to 4_999_999L)) is UpgradeListResult.Empty)
        assertTrue(run(Phone(items), spendable = mapOf("gold" to 5_000_000L)) is UpgradeListResult.Selected)
    }

    @Test fun theBottomOfTheListWithNothingAffordableIsEmptyNotAFailure() {
        val phone = Phone()
        val result = run(phone, spendable = mapOf("gold" to 4_000_000L)) as UpgradeListResult.Empty
        assertEquals(1, result.wallRowsSeen)
        assertTrue(phone.rowTaps.isEmpty())
        // Three pages of movement, then two swipes that left the page as it was.
        assertEquals(listOf("up", "up", "up", "up", "up"), phone.swipes)
    }

    @Test fun aSwipeThatMovesNothingTwiceIsTheEndOfTheList() {
        val phone = Phone(swipeScrolls = false)
        val result = run(phone, spendable = mapOf("gold" to 1L))
        assertTrue(result is UpgradeListResult.Empty)
        assertEquals(2, phone.swipes.size)
    }

    @Test fun ticksAndVillageTextOnEveryFrameStillLetTheBottomShowAsTheBottom() {
        val phone = Phone(animate = true)
        assertTrue(run(phone, spendable = mapOf("gold" to 4_000_000L)) is UpgradeListResult.Empty)
        assertTrue(phone.swipes.size <= 6)
    }

    private fun longList(wallAt: Int, end: Int = 8_700): List<Item> {
        val rows = mutableListOf<Item>(Header("Upgrades in progress:", 123), Header("Other upgrades:", 161))
        var y = 199
        var n = 0
        while (y < end) {
            // A page of identical rows reads as unmoved after a scroll; real rows differ in price.
            rows += if (y == wallAt) gold("Wall x80", y, 5_000_000) else gold("Cannon x${n++ % 9 + 1}", y, 1_000_000 + n * 10_000L)
            y += 38
        }
        return rows
    }

    @Test fun aWallOnTheTwentiethPageIsFound() {
        // The swipe moves 370 px; 199 + 38 * 198 = 7723 first shows after the twentieth.
        val phone = Phone(longList(199 + 38 * 198))
        val result = run(phone, spendable = mapOf("gold" to 9_000_000L))
        assertTrue("was $result", result is UpgradeListResult.Selected)
        assertEquals(20, phone.swipes.size)
    }

    /**
     * Longer than Blood's list: about 60 cards 63 px apart in a 565 px panel leave 3 215 px to
     * scroll, and this one's last row is 3 208 px below where the popup shows it.
     */
    private val bloodLength = 3_750
    private val lastRowOfBloodLength = 199 + 38 * 93

    @Test fun everyListSwipeHoldsItsEndBeforeTheFingerLifts() {
        val phone = Phone(listOpen = true, offset = 740)
        assertTrue(run(phone) is UpgradeListResult.Selected)
        assertEquals(4, phone.holds.size)
        assertTrue("held ${phone.holds}", phone.holds.all { it in 250L..300L })
    }

    @Test fun aListAsLongAsBloodsIsReadToItsWallAtTheBottomAtAHeldSwipesMove() {
        val phone = Phone(longList(lastRowOfBloodLength, bloodLength), move = 250)
        val result = run(phone, spendable = mapOf("gold" to 9_000_000L))
        assertTrue("was $result", result is UpgradeListResult.Selected)
        assertEquals(13, phone.swipes.size)
    }

    @Test fun aListAsLongAsBloodsEndsEmptyWithinTheBudgetAtAHeldSwipesMove() {
        val phone = Phone(longList(lastRowOfBloodLength, bloodLength), move = 250)
        // Thirteen swipes to the bottom, two that move nothing: five to spare of twenty.
        assertEquals(UpgradeListResult.Empty(15, 1), run(phone, spendable = mapOf("gold" to 4_000_000L)))
    }

    @Test fun aWallOnlyTheTwentyFirstPageWouldShowIsOutOfBudget() {
        val phone = Phone(longList(199 + 38 * 208))
        val result = run(phone, spendable = mapOf("gold" to 9_000_000L)) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.BUDGET_EXCEEDED, result.reason)
        assertEquals(20, phone.swipes.size)
        assertTrue(phone.rowTaps.isEmpty())
    }

    @Test fun theTimeBudgetEndsAPhaseToo() {
        val phone = Phone()
        val result = run(phone, limits = UpgradeListLimits(maxScrolls = 20, maxMillis = 6_000)) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.BUDGET_EXCEEDED, result.reason)
        assertTrue(phone.rowTaps.isEmpty())
    }

    @Test fun aStopMidScrollEndsThePhaseWithNoFurtherGesture() {
        val phone = Phone()
        phone.checkpointHook = { if (phone.swipes.size >= 2) throw StopException("stopped") else false }
        try {
            run(phone)
            fail("a stop must propagate")
        } catch (e: StopException) {
            assertEquals("stopped", e.message)
        }
        assertEquals(2, phone.swipes.size)
        assertTrue(phone.rowTaps.isEmpty())
        assertEquals(1, phone.counterTaps.size)
    }

    @Test fun aPauseMakesTheFrameBeforeItNoEvidenceAndTheRowIsConfirmedAgain() {
        val plain = Phone().also { run(it) }
        val phone = Phone()
        var fired = false
        // The pause falls before the frame that would have been the first confirmation.
        phone.checkpointHook = {
            if (!fired && phone.captures == plain.captureAtRowTap - 1) { fired = true; true } else false
        }
        assertTrue(run(phone) is UpgradeListResult.Selected)
        assertTrue(fired)
        assertEquals(plain.captureAtRowTap + 1, phone.captureAtRowTap)
    }

    /** A pause that falls after the first frame, between it and the counter tap. */
    private fun pauseAfterFirstFrame(phone: Phone, during: () -> Unit = {}) {
        var fired = false
        phone.checkpointHook = {
            if (!fired && phone.captures == 1) { fired = true; during(); true } else false
        }
    }

    @Test fun aPauseBeforeTheCounterTapTapsNothingWhereTheVillageIsNotRead() {
        // The frame as it was before UO-21: no counter and nothing that reads as the village.
        val phone = Phone(village = emptyList(), villageState = ScreenState.UNKNOWN)
        pauseAfterFirstFrame(phone)
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.LIST_NOT_OPEN, result.reason)
        assertTrue(phone.counterTaps.isEmpty())
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun theCounterIsTappedOnlyOnAFrameThatReadsAsTheVillage() {
        // 15 October 05:31 UTC: a counter tap decided on the village landed on the gem store,
        // under its $0.99 card. The store reads UNKNOWN; the menus have their own buttons there.
        val store = listOf(RecognizedBox("Pile of Gems", 300, 420, 460, 450), RecognizedBox("$0.99", 1380, 540, 1450, 566))
        for ((state, village) in listOf(
            ScreenState.UNKNOWN to sweatCounter + store,
            ScreenState.MENU to sweatCounter,
            ScreenState.ARMY to sweatCounter,
            ScreenState.WALL to sweatCounter
        )) {
            val phone = Phone(village = village, villageState = state)
            val result = run(phone) as UpgradeListResult.Failed
            assertEquals("on $state", UpgradeListFailure.LIST_NOT_OPEN, result.reason)
            assertTrue("on $state", phone.counterTaps.isEmpty())
            assertTrue("on $state", phone.missedTaps.isEmpty())
            assertTrue("on $state", phone.swipes.isEmpty())
            // The first frame and two more.
            assertEquals("on $state", 3, phone.captures)
        }
        // The village is the evidence, with or without its counter read (UO-21 review, 15 October).
        for (village in listOf(sweatCounter, emptyList())) {
            val phone = Phone(village = village)
            assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
            assertEquals(listOf(counter, close), phone.counterTaps)
        }
    }

    @Test fun aScreenThatClearsToTheVillageWithinThreeFramesIsTapped() {
        val phone = Phone(villageState = ScreenState.MENU)
        phone.checkpointHook = { if (phone.captures == 2) phone.villageState = ScreenState.HOME; false }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(listOf(counter, close), phone.counterTaps)
    }

    @Test fun aFrameAPauseCrossedIsReadAgainAndTheStoreItLeftIsNotTapped() {
        // The village before the pause; the gem store, opened in it, after.
        val store = listOf(RecognizedBox("Pile of Gems", 300, 420, 460, 450), RecognizedBox("$0.99", 1380, 540, 1450, 566))
        val phone = Phone(village = emptyList())
        pauseAfterFirstFrame(phone) { phone.village = store; phone.villageState = ScreenState.UNKNOWN }
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.LIST_NOT_OPEN, result.reason)
        assertTrue(phone.counterTaps.isEmpty())
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun aVillageReadJustAfterAPauseIsReadOnceMoreBeforeTheCounterTap() {
        val phone = Phone(village = emptyList())
        var tappedAt = -1
        pauseAfterFirstFrame(phone)
        val pause = phone.checkpointHook
        phone.checkpointHook = { if (tappedAt < 0 && phone.counterTaps.isNotEmpty()) tappedAt = phone.captures; pause() }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        // The frame before the pause, the one taken just after it, and a clean one for the tap.
        assertEquals(3, tappedAt)
    }

    @Test fun aListOpenedDuringAPauseIsNotTappedThroughWithTheCounter() {
        val phone = Phone(village = sweatCounter)
        pauseAfterFirstFrame(phone) { phone.listOpen = true }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        // Only the list's X; the counter's spot is a card while the list is open.
        assertEquals(listOf(close), phone.counterTaps)
    }

    @Test fun theCounterReadAfterAPauseIsStillTapped() {
        val phone = Phone(village = sweatCounter)
        pauseAfterFirstFrame(phone)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertEquals(listOf(counter, close), phone.counterTaps)
    }

    /** A pause before the second swipe, after a frame taken since the first; [during] runs in it. */
    private fun pauseBeforeSecondSwipe(phone: Phone, during: () -> Unit = {}): () -> Boolean {
        var fired = false
        var capturesAtSwipe = -1
        phone.checkpointHook = {
            when {
                phone.swipes.size != 1 || fired -> false
                capturesAtSwipe < 0 -> { capturesAtSwipe = phone.captures; false }
                phone.captures > capturesAtSwipe -> { fired = true; during(); true }
                else -> false
            }
        }
        return { fired }
    }

    @Test fun aListClosedDuringAPauseIsNotSwiped() {
        val phone = Phone()
        val fired = pauseBeforeSecondSwipe(phone) { phone.listOpen = false }
        val result = run(phone) as UpgradeListResult.Failed
        assertTrue(fired())
        assertEquals(UpgradeListFailure.LIST_NOT_OPEN, result.reason)
        assertEquals(listOf("up"), phone.swipes)
        assertTrue(phone.rowTaps.isEmpty())
    }

    @Test fun aListStillOpenAfterAPauseIsSwipedAsBefore() {
        val phone = Phone()
        val fired = pauseBeforeSecondSwipe(phone)
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(fired())
        assertEquals(listOf("up", "up"), phone.swipes)
    }

    @Test fun aListThatClosedBeforeTheFirstScrollIsNotSwiped() {
        val phone = Phone()
        // Open on the frame after the counter tap, gone on the next.
        phone.checkpointHook = { if (phone.captures == 2 && phone.counterTaps.size == 1) phone.listOpen = false; false }
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.LIST_NOT_OPEN, result.reason)
        assertTrue(phone.swipes.isEmpty())
    }

    @Test fun aRowStillMovingIsNotTappedUntilTwoFramesAgreeAndItsCoordinatesAreTheLastFrames() {
        val plain = Phone().also { run(it) }
        val phone = Phone()
        // The first confirmation frame is caught mid-fling, 25 px off.
        phone.jitter = { if (it == plain.captureAtRowTap) 25 else 0 }
        assertTrue(run(phone) is UpgradeListResult.Selected)
        assertTrue("a stale coordinate would have missed", phone.missedTaps.isEmpty())
        assertEquals(plain.captureAtRowTap + 2, phone.captureAtRowTap)
    }

    /** Rows 38 px apart with a Wall at y 503, at the popup's bottom edge, on the top page. */
    private val wallAtTheBottomEdge = stack(
        header("Upgrades in progress:"), header("Other upgrades:"),
        *Array(8) { filler(it) },
        goldRow("Wall x80", 5_000_000),
        *Array(12) { filler(20 + it) }
    )

    @Test fun aPickGoneOffTheBottomEdgeIsLetGoOnceAndTakenAfterTheNextSwipe() {
        val plain = Phone(wallAtTheBottomEdge).also { run(it) }
        assertTrue(plain.swipes.isEmpty())
        val phone = Phone(wallAtTheBottomEdge)
        // The four frames after the pick catch the list 60 px lower, the Wall row below the popup
        // (Blood, 15 October: "Wall x7" picked at y 582, then four frames without it).
        val pickFrame = plain.captureAtRowTap - 1
        phone.jitter = { if (it in pickFrame + 1..pickFrame + 4) -60 else 0 }
        val result = run(phone) as UpgradeListResult.Selected
        assertEquals(WallCandidate("Wall x80", 80, 5_000_000, "gold"), result.candidate)
        assertEquals(listOf("up"), phone.swipes)
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun aPickNoTwoFramesAgreeOnIsLetGoOnceAndFailsTheSecondTime() {
        val plain = Phone(wallAtTheBottomEdge).also { run(it) }
        val phone = Phone(wallAtTheBottomEdge)
        val pickFrame = plain.captureAtRowTap - 1
        // From the pick on, every frame is 25 px off the one before it.
        phone.jitter = { if (it >= pickFrame && (it - pickFrame) % 2 == 1) -25 else 0 }
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.UNREADABLE, result.reason)
        assertTrue(result.detail, "hold still" in result.detail)
        assertTrue(phone.rowTaps.isEmpty())
        assertTrue(phone.missedTaps.isEmpty())
        // The pick, its four frames, the page read again where they left it, and four more.
        assertEquals(pickFrame + 8, phone.captures)
    }

    /** Rows 38 px apart with a Wall at y 351, mid-way down the top page and clear of both edges. */
    private val wallMidPage = stack(
        header("Upgrades in progress:"), header("Other upgrades:"),
        *Array(4) { filler(it) },
        goldRow("Wall x80", 5_000_000),
        *Array(6) { filler(20 + it) }
    )

    @Test fun aLostPickWhoseLastFrameTheNextAgreesWithIsTappedThereWithNoSwipe() {
        val plain = Phone(wallMidPage).also { run(it) }
        assertTrue(plain.swipes.isEmpty())
        val phone = Phone(wallMidPage)
        val pickFrame = plain.captureAtRowTap - 1
        // Two frames caught mid-fling either way, one back where the pick was read, and then the
        // list at rest 20 px on from it: no two of the four agree, and the last is where it stays.
        phone.jitter = { when (it) { pickFrame + 1 -> 15; pickFrame + 2 -> -15; else -> 0 } }
        var crept = false
        phone.checkpointHook = {
            if (!crept && phone.captures == pickFrame + 3) { crept = true; phone.offset += 20 }
            false
        }
        val result = run(phone) as UpgradeListResult.Selected
        assertEquals(wallX80, result.candidate)
        assertTrue(phone.swipes.isEmpty())
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.missedTaps.isEmpty())
        // The pick let go, the scan picks it again on the last of the four, and the next frame agrees.
        assertEquals(pickFrame + 5, phone.captureAtRowTap)
        val restY = UpgradeListFixture.lineY(wallMidPage, "Wall x80") - phone.offset
        assertTrue("tapped at ${phone.rowTaps.single()}, at rest $restY", kotlin.math.abs(phone.rowTaps.single().y - restY) <= 2)
    }

    @Test fun aPauseWaitedOutJustBeforeTheRowTapMakesTheNextFrameNoEvidence() {
        val plain = Phone(wallMidPage).also { run(it) }
        val phone = Phone(wallMidPage)
        var fired = false
        // The pause falls at the check just before the tap, after two frames agreed.
        phone.checkpointHook = {
            if (!fired && phone.captures == plain.captureAtRowTap) { fired = true; true } else false
        }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(fired)
        // The frame after it agrees with nothing from before it: two more frames, not one.
        assertEquals(plain.captureAtRowTap + 2, phone.captureAtRowTap)
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun aPauseJustBeforeTheTapOnALostPicksLastFrameGoesWithItIntoTheScan() {
        val plain = Phone(wallMidPage).also { run(it) }
        val phone = Phone(wallMidPage)
        val pickFrame = plain.captureAtRowTap - 1
        // Three frames that do not agree, a fourth that agrees with the third, and a pause at the
        // check before its tap: the pick is let go with the pause still to be counted.
        phone.jitter = { when (it) { pickFrame + 1 -> 15; pickFrame + 2 -> -15; else -> 0 } }
        var fired = false
        phone.checkpointHook = {
            if (!fired && phone.captures == pickFrame + 4) { fired = true; true } else false
        }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(fired)
        // The scan's first frame comes after the pause and agrees with nothing from before it.
        assertEquals(pickFrame + 6, phone.captureAtRowTap)
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.missedTaps.isEmpty())
        assertTrue(phone.swipes.isEmpty())
    }

    @Test fun aPausedFrameWithNoPickLeavesNothingBeforeThePauseToAgreeWith() {
        val plain = Phone(wallMidPage).also { run(it) }
        val phone = Phone(wallMidPage)
        // The list moves 20 px during a pause at the check before the tap. The frame after it
        // misreads the Wall's price, and the one after that is caught 20 px back, where the pick
        // was read before the pause.
        phone.misread = { it == plain.captureAtRowTap + 1 }
        phone.jitter = { if (it == plain.captureAtRowTap + 2) -20 else 0 }
        var fired = false
        phone.checkpointHook = {
            if (!fired && phone.captures == plain.captureAtRowTap) { fired = true; phone.offset += 20; true } else false
        }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(fired)
        assertTrue(phone.missedTaps.isEmpty())
        assertEquals(1, phone.rowTaps.size)
        val restY = UpgradeListFixture.lineY(wallMidPage, "Wall x80") - phone.offset
        assertTrue("tapped at ${phone.rowTaps.single()}, at rest $restY", kotlin.math.abs(phone.rowTaps.single().y - restY) <= 2)
    }

    @Test fun aPausedFrameWithNoPickOnALostPicksRetryLeavesNothingBeforeThePauseToAgreeWith() {
        val plain = Phone(wallMidPage).also { run(it) }
        val phone = Phone(wallMidPage)
        val pickFrame = plain.captureAtRowTap - 1
        // The pick is let go with a pause owed, as above, and the list moves 20 px during it; the
        // scan's first frame misreads the price and its second is caught where the list was.
        phone.misread = { it == pickFrame + 5 }
        phone.jitter = { when (it) { pickFrame + 1 -> 15; pickFrame + 2 -> -15; pickFrame + 6 -> -20; else -> 0 } }
        var fired = false
        phone.checkpointHook = {
            if (!fired && phone.captures == pickFrame + 4) { fired = true; phone.offset += 20; true } else false
        }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(fired)
        assertTrue(phone.missedTaps.isEmpty())
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.swipes.isEmpty())
        val restY = UpgradeListFixture.lineY(wallMidPage, "Wall x80") - phone.offset
        assertTrue("tapped at ${phone.rowTaps.single()}, at rest $restY", kotlin.math.abs(phone.rowTaps.single().y - restY) <= 2)
    }

    @Test fun anAmbiguousPriceIsRereadAndAGoodReadingIsActed() {
        val plain = Phone().also { run(it) }
        val phone = Phone()
        // The frame that first shows the wall reads "5M"; the reread is clean.
        phone.misread = { it == plain.captureAtRowTap - 1 }
        assertEquals(UpgradeListResult.Selected(wallX80, 16), run(phone))
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun aPriceThatNeverReadsCleanlyIsUnreadableNotGuessed() {
        val plain = Phone().also { run(it) }
        val phone = Phone()
        phone.misread = { it >= plain.captureAtRowTap - 1 }
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.UNREADABLE, result.reason)
        assertTrue(phone.rowTaps.isEmpty())
    }

    @Test fun aPanelThatNeverShowsItsTitleIsNotConfirmed() {
        val phone = Phone(panelShows = false)
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.PANEL_NOT_CONFIRMED, result.reason)
        assertEquals(1, phone.rowTaps.size)
    }

    @Test fun aListThatWillNotCloseIsTappedShutTwiceAtMost() {
        val phone = Phone(counterClosesList = false)
        val result = run(phone) as UpgradeListResult.Failed
        assertEquals(UpgradeListFailure.PANEL_NOT_CONFIRMED, result.reason)
        // One tap to open, two to close.
        assertEquals(3, phone.counterTaps.size)
    }

    // --- the bottom of a list that bounces (Sweat, 8 October) -----------------------------------

    /** The last page caught a row's height either way of its rest, alternately, as on the phone. */
    private val bouncing = listOf(-20, 20)

    @Test fun aMaxedListWithNoWallRowsEndsEmptyAtItsBouncingBottomInEitherCurrency() {
        // Frames 40 px apart never match line for line, so this ran the 20-swipe budget out before.
        val swipes = mutableListOf<List<String>>()
        for (spendable in listOf(mapOf("gold" to 20_000_000L), mapOf("elixir" to 20_000_000L))) {
            val phone = Phone(UpgradeListFixture.sweatMaxed(), bounces = bouncing)
            val result = run(phone, spendable = spendable)
            assertEquals("for $spendable", UpgradeListResult.Empty(6, 0), result)
            assertTrue(phone.rowTaps.isEmpty())
            swipes += phone.swipes
        }
        assertEquals("both orders read the list the same way", swipes[0], swipes[1])
    }

    @Test fun aMaxedListAtRestEndsEmptyOnTwoSwipesThatShowNothingNew() {
        val phone = Phone(UpgradeListFixture.sweatMaxed())
        assertEquals(UpgradeListResult.Empty(5, 0), run(phone, spendable = mapOf("gold" to 20_000_000L)))
    }

    @Test fun aSecondPhaseThatFindsTheListLeftAtItsBottomReturnsToTheTopAndEndsTheSame() {
        val fresh = Phone(UpgradeListFixture.sweatMaxed(), bounces = bouncing)
        run(fresh, spendable = mapOf("gold" to 20_000_000L))
        val left = Phone(UpgradeListFixture.sweatMaxed(), bounces = bouncing, listOpen = true)
        left.offset = left.maxOffset
        val result = run(left, spendable = mapOf("elixir" to 20_000_000L))
        assertEquals(UpgradeListResult.Empty(6, 0), result)
        assertEquals(listOf("down", "down", "down") + fresh.swipes, left.swipes)
    }

    @Test fun aWallOnTheBouncingLastPageIsStillFoundAndTapped() {
        val phone = Phone(UpgradeListFixture.sweatMaxed(wall = true), bounces = bouncing)
        val result = run(phone, spendable = mapOf("gold" to 20_000_000L)) as UpgradeListResult.Selected
        assertEquals(wallX80, result.candidate)
        assertEquals(1, phone.rowTaps.size)
        assertTrue(phone.missedTaps.isEmpty())
    }

    @Test fun theWallListStillEndsWhereItDidWithABounceAtItsBottom() {
        val phone = Phone(bounces = bouncing)
        val result = run(phone, spendable = mapOf("gold" to 4_000_000L)) as UpgradeListResult.Empty
        assertEquals(1, result.wallRowsSeen)
        assertTrue(phone.swipes.size < 20)
    }

    @Test fun emptyAndFailedAreDifferentResults() {
        assertTrue(UpgradeListResult.Empty(3, 1) != UpgradeListResult.Failed(UpgradeListFailure.UNREADABLE, ""))
    }
}
