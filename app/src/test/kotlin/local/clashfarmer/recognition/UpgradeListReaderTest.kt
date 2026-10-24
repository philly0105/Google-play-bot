package local.clashfarmer.recognition

import local.clashfarmer.recognition.UpgradeListFixture.Frame
import local.clashfarmer.recognition.UpgradeListFixture.Header
import local.clashfarmer.recognition.UpgradeListFixture.Line
import local.clashfarmer.recognition.UpgradeListFixture.gold
import local.clashfarmer.recognition.UpgradeListFixture.screen
import local.clashfarmer.recognition.UpgradeListReader.Amount
import local.clashfarmer.recognition.UpgradeListReader.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames are the 29 September captures of Sweat's builder list; see [UpgradeListFixture]. */
class UpgradeListReaderTest {

    private val sweat = UpgradeListFixture.sweat()

    private fun read(items: List<UpgradeListFixture.Item>, offset: Int = 0, extra: List<RecognizedBox> = UpgradeListFixture.overlay): UpgradeListReader.Reading {
        val frame = Frame(items, offset, extra)
        return UpgradeListReader.read(frame.observation.boxes, screen, frame::pixels)
    }

    /** Sweat's list scrolled so the wall row sits where 08-b-scroll3 shows it, at y 343. */
    private val wallPage get() = UpgradeListFixture.lineY(sweat, "Wall x80") - 343

    private fun wallRows(reading: UpgradeListReader.Reading) = reading.rows.filter { it.kind != Kind.OTHER }

    @Test fun aWallRowReadsWithItsCountPriceAndCurrency() {
        val wall = wallRows(read(sweat, wallPage)).single()
        assertEquals(Kind.WALL, wall.kind)
        assertEquals(80, wall.count)
        assertEquals(Amount.Exact(5_000_000), wall.amount)
        assertEquals("gold", wall.currency)
        assertEquals(true, wall.payable)
        assertTrue(wall.readable)
        // Tapped on its name, as the evidence tapped (720, 180), never on the price at the right.
        assertTrue(wall.tap.x in 662..740)
        assertEquals(343, wall.tap.y)
    }

    @Test fun theCountIsPartOfTheNameAndNeverThePrice() {
        val wall = wallRows(read(sweat, wallPage)).single()
        assertEquals("Wall x80", wall.name)
        assertFalse(wall.amount == Amount.Exact(80))
    }

    @Test fun anUnaffordablePriceIsReadAsRed() {
        val items = listOf(Header("Other upgrades:", 123), gold("Wall x30", 200, 8_000_000))
        val wall = wallRows(read(items)).single()
        assertEquals(Amount.Exact(8_000_000), wall.amount)
        assertEquals(false, wall.payable)
    }

    @Test fun nothingButAWallRowIsAWallRow() {
        val page = read(sweat, wallPage)
        assertTrue(page.rows.size >= 10)
        // Buildings, traps, equipment and heroes on this page and the ones above it.
        for (offset in listOf(0, 370, 740, wallPage, 1110)) {
            for (row in read(sweat, offset).rows.filter { it.name != "Wall x80" }) {
                assertEquals("${row.name} at offset $offset", Kind.OTHER, row.kind)
            }
        }
    }

    @Test fun wallBreakerAndItsKinNeverMatch() {
        val items = listOf(
            Header("Other upgrades:", 123),
            gold("Wall Breaker", 160, 200_000),
            gold("Wall Breaker x2", 200, 300_000),
            gold("Wallpaper", 240, 1_000_000),
            gold("Wall Wizard x1", 280, 1_000_000)
        )
        assertEquals(emptyList<UpgradeListReader.Row>(), wallRows(read(items)))
    }

    @Test fun severalWallRowsAtDifferentLevelsAndPricesAllRead() {
        val items = listOf(
            Header("Other upgrades:", 123),
            gold("Wall x12", 170, 5_600_000),
            gold("Wall x30", 210, 8_000_000),
            UpgradeListFixture.elixir("Wall x80", 250, 4_000_000)
        )
        val walls = wallRows(read(items))
        assertEquals(listOf(12, 30, 80), walls.map { it.count })
        assertEquals(listOf(5_600_000L, 8_000_000L, 4_000_000L), walls.map { (it.amount as Amount.Exact).value })
        assertEquals(listOf("gold", "gold", "elixir"), walls.map { it.currency })
        assertEquals(listOf(true, false, true), walls.map { it.payable })
    }

    @Test fun aNameAndPriceRunOnAsOneBoxAreSplit() {
        val items = listOf(Header("Other upgrades:", 123), Line("Wall x80", 200, "5 000 000", "gold", merged = true))
        val wall = wallRows(read(items)).single()
        assertEquals("Wall x80", wall.name)
        assertEquals(80, wall.count)
        assertEquals(Amount.Exact(5_000_000), wall.amount)
        assertEquals("gold", wall.currency)
    }

    @Test fun theCountAndTheWordCanComeBackAsSeparateBoxes() {
        val boxes = listOf(
            RecognizedBox("Upgrades in progress:", 662, 113, 850, 133),
            RecognizedBox("Wall", 662, 332, 700, 354),
            RecognizedBox("x80", 706, 332, 740, 354),
            RecognizedBox("5 000 000", 866, 332, 970, 354)
        )
        val wall = UpgradeListReader.read(boxes, screen) { IntArray(it.width * it.height) }.rows.first { it.kind != Kind.OTHER }
        assertEquals(80, wall.count)
        assertEquals(Amount.Exact(5_000_000), wall.amount)
    }

    @Test fun aLettersInPlaceOfDigitsStillReadsTheCount() {
        val items = listOf(Header("Other upgrades:", 123), gold("Wall x8O", 200, 5_000_000))
        assertEquals(80, wallRows(read(items)).single().count)
    }

    @Test fun aBareWallWithNoCountIsUnreadNotGuessed() {
        val items = listOf(Header("Other upgrades:", 123), gold("Wall", 200, 5_000_000))
        val wall = wallRows(read(items)).single()
        assertEquals(Kind.AMBIGUOUS_WALL, wall.kind)
        assertFalse(wall.readable)
    }

    @Test fun duplicateBoxesFromTheReaderAreOneRow() {
        val frame = Frame(listOf(Header("Other upgrades:", 123), gold("Wall x80", 200, 5_000_000)), 0)
        val boxes = frame.observation.boxes.flatMap { listOf(it, it.copy(top = it.top + 2, bottom = it.bottom + 2)) }
        val reading = UpgradeListReader.read(boxes, screen, frame::pixels)
        assertEquals(1, wallRows(reading).size)
        assertEquals(Amount.Exact(5_000_000), wallRows(reading).single().amount)
    }

    @Test fun aRowCutByThePopupEdgeIsClippedNotRead() {
        val items = listOf(Header("Upgrades in progress:", 123), gold("Wall x80", 549, 5_000_000))
        val wall = wallRows(read(items)).single()
        assertTrue(wall.clipped)
        assertEquals(Amount.Absent, wall.amount)
    }

    @Test fun anAbbreviatedAmountIsAmbiguousNotAGuess() {
        for (text in listOf("5M", "5.0M", "5 000 K", "5 00")) {
            val items = listOf(Header("Other upgrades:", 123), Line("Wall x80", 200, text, "gold"))
            val wall = wallRows(read(items)).single()
            assertTrue("$text should be ambiguous, was ${wall.amount}", wall.amount is Amount.Ambiguous)
            assertFalse(wall.readable)
        }
    }

    @Test fun anIconThatIsNeitherGoldNorElixirIsNoCurrency() {
        for (currency in listOf("dark", null)) {
            val items = listOf(Header("Other upgrades:", 123), Line("Wall x80", 200, "5 000 000", currency))
            val wall = wallRows(read(items)).single()
            assertEquals(null, wall.currency)
            assertFalse(wall.readable)
        }
    }

    @Test fun aLoneLetterBesideTheDigitsIsTheCoin() {
        assertEquals(Amount.Exact(5_000_000), UpgradeListReader.parseAmount("5 000 000 O"))
        assertEquals(Amount.Exact(5_000_000), UpgradeListReader.parseAmount("O 5 000 000"))
        assertEquals(Amount.Exact(5_000_000), UpgradeListReader.parseAmount("5000 000"))
    }

    @Test fun theBotsOwnOverlayIsNotTheList() {
        val noise = listOf(
            RecognizedBox("Wall x80 5 000 000", 20, 30, 300, 60),
            RecognizedBox("Upgrades in progress:", 30, 70, 250, 95)
        )
        val reading = read(emptyList(), extra = UpgradeListFixture.overlay + noise)
        assertFalse(reading.hasListEvidence)
        assertFalse(reading.atTop)
        assertTrue(reading.rows.isEmpty())
    }

    @Test fun aHeaderOrTwoPricedRowsShowTheListAndNothingElseDoes() {
        assertTrue(read(sweat, 0).hasListEvidence)
        assertTrue(read(sweat, 0).atTop)
        val mid = read(sweat, wallPage)
        assertTrue("priced rows alone are evidence", mid.hasListEvidence)
        assertFalse(mid.atTop)
        assertFalse(read(emptyList()).hasListEvidence)
    }

    @Test fun theLaboratoryListIsTheWrongListNotNoList() {
        // 02-list-open: the goblin counter's popup, x 502-808, rows left of where the builder's start.
        val lab = listOf(
            RecognizedBox("Barbarian 12", 515, 118, 640, 140),
            RecognizedBox("Archer 12", 515, 158, 620, 180),
            RecognizedBox("Giant 12", 515, 196, 610, 218),
            RecognizedBox("Wizard 11", 515, 234, 620, 256),
            RecognizedBox("Furnace 260 000", 560, 272, 790, 294)
        )
        val reading = UpgradeListReader.read(lab, screen) { IntArray(it.width * it.height) }
        assertFalse(reading.hasListEvidence)
        assertTrue(reading.laboratoryShowing)
        assertFalse(read(emptyList()).laboratoryShowing)
    }

    @Test fun timersTickingAndVillageTextDoNotMakeANewPage() {
        val a = read(sweat, 0)
        val ticked = sweat.map {
            if (it is Line && it.countdown != null) Line(it.name, it.y, countdown = "0h 0${it.countdown.length}m") else it
        }
        val village = listOf(RecognizedBox("Results", 744, 209, 800, 229), RecognizedBox("Boost", 340, 510, 390, 530))
        val b = read(ticked, 0, UpgradeListFixture.overlay + village)
        assertTrue(UpgradeListReader.samePage(a, b))
    }

    @Test fun aScrollIsANewPageAndAWallCountAloneIsNot() {
        val a = read(sweat, 0)
        assertFalse(UpgradeListReader.samePage(a, read(sweat, 370)))
        assertFalse("a shift of one row moves every line", UpgradeListReader.samePage(a, read(sweat, 38)))
        val fewer = sweat.map { if (it is Line && it.name == "Air Bomb x7") gold("Air Bomb x6", it.y, 5_000_000) else it }
        val page = wallPage - 200
        assertTrue(UpgradeListReader.samePage(read(sweat, page), read(fewer, page)))
    }

    @Test fun sameRowNeedsTheSameNamePriceCurrencyAndPlace() {
        val row = wallRows(read(sweat, wallPage)).single()
        assertTrue(UpgradeListReader.sameRow(row, wallRows(read(sweat, wallPage + 4)).single()))
        assertFalse(UpgradeListReader.sameRow(row, wallRows(read(sweat, wallPage + 30)).single()))
        val cheaper = sweat.map { if (it is Line && it.name == "Wall x80") gold("Wall x80", it.y, 4_000_000) else it }
        assertFalse(UpgradeListReader.sameRow(row, wallRows(read(cheaper, wallPage)).single()))
        assertNotNull(row.tap)
    }

    @Test fun villageTextTrailingAWallNameDoesNotHideIt() {
        // 07 shows "Results" from the village ghosting through the popup at x 715-775, right
        // where a short name ends.
        val boxes = listOf(
            RecognizedBox("Other upgrades:", 662, 113, 800, 133),
            RecognizedBox("Wall x80", 662, 332, 734, 354),
            RecognizedBox("Results", 744, 334, 800, 350),
            RecognizedBox("5 000 000", 866, 332, 970, 354)
        )
        val wall = UpgradeListReader.read(boxes, screen) { IntArray(it.width * it.height) }.rows.first { it.kind != Kind.OTHER }
        assertEquals("Wall x80", wall.name)
        assertEquals(Amount.Exact(5_000_000), wall.amount)
    }

    @Test fun wallBreakerReadAsTwoBoxesIsStillNotAWall() {
        val boxes = listOf(
            RecognizedBox("Other upgrades:", 662, 113, 800, 133),
            RecognizedBox("Wall", 662, 332, 700, 354),
            RecognizedBox("Breaker", 706, 332, 780, 354),
            RecognizedBox("200 000", 866, 332, 970, 354)
        )
        val reading = UpgradeListReader.read(boxes, screen) { IntArray(it.width * it.height) }
        assertEquals(emptyList<UpgradeListReader.Row>(), wallRows(reading))
    }

    // --- whether a scroll showed anything new, by row titles (Sweat, 8 October) ------------------

    /** Rows as [UpgradeListReader.titles] keys them, transcribed from sweat-builder-list-2..4.png. */
    private fun keys(vararg rows: Pair<String, String>) = rows.map { (name, price) -> UpgradeListReader.title("$name $price") }

    private val page2 = keys(
        "Barbarian King" to "195 000", "Minion Prince" to "180 000", "Hero Hunter" to "60 000",
        "Cake-A-Pult" to "35 000", "Hot Candle" to "30 000", "Spell Factory" to "24 000 000",
        "Dark Spell Factory" to "18 000 000", "Dark Elixir Drill x3" to "12 000 000",
        "Dark Elixir Storage" to "10 000 000", "Gold Mine x7" to "8 000 000",
        "Cake-A-Pult" to "4 000 000", "Hot Candle" to "3 500 000"
    )
    private val page3 = keys(
        "Hero Hunter" to "3 000 000", "Builder's Hut x5" to "8 000 000", "Air Defense x3" to "9 000 000",
        "Bomb Tower x2" to "8 500 000", "Hidden Tesla x5" to "8 200 000", "Wizard Tower x4" to "8 000 000",
        "Mortar x4" to "7 000 000", "Town Hall" to "16 000 000", "Seeking Air Mine x8" to "6 500 000",
        "Giant Bomb x7" to "5 500 000", "Air Bomb x7" to "5 000 000"
    )
    private val page4 = keys(
        "Wizard Tower x4" to "8 000 000", "Mortar x4" to "7 000 000", "Town Hall" to "16 000 000",
        "Seeking Air Mine x8" to "6 500 000", "Giant Bomb x7" to "5 500 000", "Air Bomb x7" to "5 000 000",
        "Bomb x7" to "4 000 000", "Spring Trap x9" to "1 000 000", "Hot Candle" to "4 000 000",
        "Hero Hunter" to "3 500 000", "Cake-A-Pult" to "3 000 000"
    )

    private fun stopped(before: List<UpgradeListReader.Title>, after: List<UpgradeListReader.Title>) =
        UpgradeListReader.stopped(UpgradeListReader.advance(before, after), before, after)

    @Test fun aFullScrollPastRepeatedNamesShowsNewRows() {
        // Hero Hunter, Cake-A-Pult and Hot Candle each come back on the next page at another price.
        val a = UpgradeListReader.advance(page2, page3)
        assertTrue("revealed ${a.revealed}", a.revealed > 0)
        assertFalse(stopped(page2, page3))
    }

    @Test fun theShortLastScrollShowsTheRowsItUncovered() {
        // Pages 3 and 4 overlap by six rows; the last swipe moved the list partway.
        val a = UpgradeListReader.advance(page3, page4)
        assertEquals(UpgradeListReader.Advance(6, 5), a)
        assertFalse(stopped(page3, page4))
    }

    @Test fun theLastPageAgainHasNotMoved() {
        assertEquals(UpgradeListReader.Advance(11, 0), UpgradeListReader.advance(page4, page4))
        assertTrue(stopped(page4, page4))
    }

    @Test fun theLastPageBouncedOneRowEitherWayHasNotMoved() {
        // Up a row: a row above shows at the top and the last one is cut off by the edge.
        val down = keys("Hidden Tesla x5" to "8 200 000") + page4.dropLast(1)
        assertTrue(stopped(page4, down))
        // Down a row: the top row is cut off and nothing follows the last one.
        assertTrue(stopped(page4, page4.drop(1)))
        assertTrue(stopped(page4.drop(1), page4))
        // The other way round the cut-off row is new; the navigator keeps the furthest page as
        // its reference, so the bounce after that compares against the full one and stops.
        assertFalse(stopped(down, page4))
    }

    @Test fun aMisreadPriceOrAVillageWordAmongTheRowsDoesNotMakeTheBottomMove() {
        val noisy = page4.toMutableList()
        noisy[3] = UpgradeListReader.title("Seeking Air Mine x86500 ")
        noisy.add(6, UpgradeListReader.title("Request 6d3"))
        noisy[noisy.lastIndex] = UpgradeListReader.title("Cake-A-Puit 3 000 000")
        assertTrue(stopped(page4, noisy))
        assertTrue(stopped(noisy, page4))
    }

    @Test fun pagesWithTooFewTitlesInCommonSayNothing() {
        val a = UpgradeListReader.advance(page4, page4.take(2))
        assertFalse(stopped(page4, page4.take(2)))
        assertEquals(2, a.common)
        assertEquals(UpgradeListReader.Advance(0, 3), UpgradeListReader.advance(emptyList(), page4.take(3)))
    }

    @Test fun aRowReadAsOneMergedBoxKeysAsItsSplitReadAndStillStops() {
        val maxed = UpgradeListFixture.sweatMaxed()
        val bottom = maxed.last().y + 30 - 555
        val split = UpgradeListReader.titles(read(maxed, bottom))
        // Town Hall has no x count, so the reader keeps "Town Hall 16 000 000" whole as its name.
        val merged = maxed.map { if (it is Line && it.name == "Town Hall") Line(it.name, it.y, it.price, it.currency, merged = true) else it }
        val reading = read(merged, bottom)
        assertTrue(reading.rows.any { it.name == "Town Hall 16 000 000" && it.priceText == null })
        val joined = UpgradeListReader.titles(reading)
        assertEquals(split, joined)
        assertTrue(stopped(split, joined))
    }

    @Test fun aTimesSignKeysAsTheLetterX() {
        val items = listOf(Header("Other upgrades:", 123), gold("Giant Bomb ×7", 200, 5_500_000), gold("Giant Bomb x7", 238, 5_500_000))
        val keys = UpgradeListReader.titles(read(items))
        assertEquals(keys[1], keys[2])
    }

    @Test fun aShortMoveWithOnlyItsTopTwoRowsReadIsNotTheEnd() {
        // Mid-list, three rows down, and only two rows read on the new frame: too little in common.
        val before = page3
        val after = page3.drop(3).take(2)
        assertEquals(2, UpgradeListReader.advance(before, after).common)
        assertFalse(stopped(before, after))
    }

    @Test fun aShortMoveWithOnlyItsTopFiveRowsReadIsTakenForTheEnd() {
        // The limit: five rows read past a three-row move all lie inside the earlier page, so
        // nothing reads as new. Two such frames in a row end the list early (the cost is Empty).
        val before = page3
        val after = page3.drop(3).take(5)
        assertEquals(UpgradeListReader.Advance(5, -3), UpgradeListReader.advance(before, after))
        assertTrue(stopped(before, after))
    }

    @Test fun wallRowsWhosePriceDidNotReadDoNotPinAShortMoveInPlace() {
        // Wall rows share a stem and differ only by price. With the last one's price unread on
        // the earlier page it would match any new Wall row, so revealed is measured from the last
        // pair with both prices read.
        val walls = keys(
            "Wall x40" to "3 000 000", "Wall x30" to "3 500 000", "Wall x20" to "4 000 000"
        ) + UpgradeListReader.title("Wall x80")
        val before = page3.take(7) + walls
        val after = before.drop(2).dropLast(1) + keys(
            "Wall x80" to "5 000 000", "Wall x60" to "5 500 000", "Wall x10" to "6 000 000"
        )
        assertEquals(11, before.size)
        assertEquals(11, after.size)
        assertEquals(2, UpgradeListReader.advance(before, after).revealed)
        assertFalse(stopped(before, after))
    }

    @Test fun titlesReadOffAFrameSkipCutOffRowsAndKeepPrices() {
        val maxed = UpgradeListFixture.sweatMaxed()
        val bottom = maxed.last().y + 30 - 555
        val atRest = UpgradeListReader.titles(read(maxed, bottom))
        assertEquals(page4, atRest.takeLast(page4.size))
        // Caught 20 px short of its rest the last row is cut off by the popup's edge and left out.
        val short = UpgradeListReader.titles(read(maxed, bottom - 20))
        assertEquals(page4.dropLast(1), short.takeLast(page4.size - 1))
        assertTrue(stopped(atRest, short))
        // Line for line the two frames do not match, which is why the list never ended before.
        assertFalse(UpgradeListReader.samePage(read(maxed, bottom + 20), read(maxed, bottom - 20)))
    }

    @Test fun aRowReadThroughTheVillageKeysAsTheRowItIs() {
        // Read off the phone at 13:04-13:06 UTC: village words, zeros as O, o and D, a coin as O.
        val title = UpgradeListReader::title
        assertEquals(UpgradeListReader.Title("townhall", 16_000_000), title("Town Hall Request O16 000 000."))
        assertEquals(UpgradeListReader.Title("giantbomb", 5_500_000), title("Giant Bomb yR O0B x7 WAR 5 500 o00"))
        assertEquals(UpgradeListReader.Title("bormb", 4_000_000), title("Bormb xT O4 000 O00 Sd 15H"))
        assertEquals(UpgradeListReader.Title("bomb", 4_000_000), title("Bornb x 04 000 000"))
        assertEquals(UpgradeListReader.Title("airbomb", 5_000_000), title("Air Bomb x7 83 5 000 000"))
        assertEquals(UpgradeListReader.Title("herohunter", 3_500_000), title("9Hero Hunter 3 500 000u"))
        assertEquals(UpgradeListReader.Title("springtrap", 1_000_000), title("Spring Trap x9 Oi000 000"))
        assertEquals(UpgradeListReader.Title("grandwarden", 16_000_000), title("Grand Warden 16 00O D00"))
        assertEquals(UpgradeListReader.Title("darkspell", 18_000_000), title("Dark Spell Factory 18 000 000"))
        // A price run on into the count is no price; the name still reads.
        assertEquals(UpgradeListReader.Title("seekingair", null), title("Seeking Air Mine x86500 "))
        assertTrue(UpgradeListReader.sameTitle(title("Bormb xT 4000 000"), title("Bomb x7 4 000 000")))
        assertFalse(UpgradeListReader.sameTitle(title("Bomb x7 4 000 000"), title("Air Bomb x7 4 000 000")))
        assertFalse(UpgradeListReader.sameTitle(title("Hero Hunter 60 000"), title("Hero Hunter 3 000 000")))
    }

    /** The wall_list_page titles of the 13:04-13:06 run: each phase's scan pages in order. */
    private fun devicePhases(): List<List<List<UpgradeListReader.Title>>> {
        val text = UpgradeListReaderTest::class.java.getResourceAsStream("/upgrade-list/sweat-2026-10-08.txt")!!
            .bufferedReader().readText()
        val phases = mutableListOf<MutableList<List<UpgradeListReader.Title>>>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (phase, scroll, titles) = line.split("\t")
            if (phase != "scan") continue
            if (scroll == "0") phases += mutableListOf<List<UpgradeListReader.Title>>()
            phases.last() += titles.split(" / ").map(UpgradeListReader::title)
                .filter(UpgradeListReader::isRow)
        }
        return phases
    }

    /** The scan's rule: two pages in a row with nothing past the furthest page so far end it. */
    private fun replay(pages: List<List<UpgradeListReader.Title>>): Pair<Int?, List<Pair<UpgradeListReader.Advance, Int>>> {
        var reference = pages.first()
        var unchanged = 0
        val seen = mutableListOf<Pair<UpgradeListReader.Advance, Int>>()
        for (k in 1 until pages.size) {
            val a = UpgradeListReader.advance(reference, pages[k])
            unchanged = if (UpgradeListReader.stopped(a, reference, pages[k])) unchanged + 1 else 0
            seen += a to unchanged
            if (unchanged >= 2) return k to seen
            if (unchanged == 0) reference = pages[k]
        }
        return null to seen
    }

    @Test fun theDeviceRunOfEighthOctoberEndsBothPhasesAtTheirFirstBottomPages() {
        val phases = devicePhases()
        assertEquals(listOf(7, 21), phases.map { it.size })
        for ((n, pages) in phases.withIndex()) {
            val (end, seen) = replay(pages)
            assertEquals("phase $n: $seen", 5, end)
            // Scans 1 to 3 are real moves of 8 to 11 rows and never read as stopped.
            for (k in 0..2) {
                assertEquals("phase $n scan ${k + 1}: ${seen[k]}", 0, seen[k].second)
                assertTrue("phase $n scan ${k + 1}: ${seen[k]}", seen[k].first.revealed >= 8)
            }
        }
    }

    @Test fun everyPairOfTheDevicesBottomPagesReadsAsStoppedAndNoRealMoveDoes() {
        val phases = devicePhases()
        // Scan 3 onwards sat at the bottom in both phases: 4 pages, then 18.
        val bottom = phases.flatMap { it.drop(3) }
        for (a in bottom) for (b in bottom) {
            if (a === b) continue
            val advance = UpgradeListReader.advance(a, b)
            assertTrue("$advance", UpgradeListReader.stopped(advance, a, b))
            assertTrue("$advance", advance.common >= 9)
        }
        for (pages in phases) for (k in 0..2) for (later in k + 1..k + 3) {
            val advance = UpgradeListReader.advance(pages[k], pages[later])
            assertFalse("scan $k to $later: $advance", UpgradeListReader.stopped(advance, pages[k], pages[later]))
        }
    }
}
