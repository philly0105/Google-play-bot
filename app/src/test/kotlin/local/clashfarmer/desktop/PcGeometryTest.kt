package local.clashfarmer.desktop

import local.clashfarmer.core.BuilderCount
import local.clashfarmer.core.Builders
import local.clashfarmer.core.CollectionPolicy
import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.Region
import local.clashfarmer.core.ReturnHome
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.WallPanel
import local.clashfarmer.core.upgradeListCardShowing
import local.clashfarmer.recognition.ArmyRecipes
import local.clashfarmer.recognition.ArmyTab
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListReader
import local.clashfarmer.recognition.UpgradeListReader.Amount
import local.clashfarmer.recognition.UpgradeListReader.Kind
import local.clashfarmer.recognition.builderCounterPoint
import local.clashfarmer.recognition.builderPanelShowing
import local.clashfarmer.recognition.classifyScreen
import local.clashfarmer.recognition.nextButtonTarget
import local.clashfarmer.recognition.reloadTarget
import local.clashfarmer.recognition.surrenderTarget
import local.clashfarmer.recognition.upgradeListClosePoint
import local.clashfarmer.recognition.upgradeListSwipe
import local.clashfarmer.recognition.upgradePopupRegion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The phone's screen readers and tap points on the PC's frames. Google Play Games draws Clash at
 * the aspect of the monitor it launched on: 1740x978 (16:9), and after a relaunch 1860x1162, which
 * narrowing the window made 1565x978 (16:10).
 *
 * Each JSON in resources/pc-frames is the sidecar's OCR of one capture, copied byte for byte: the
 * geo-* frames from audit-evidence/spike, the live-* frames from audit-evidence/live
 * (docs/PC-GEOMETRY.md names the capture behind each). Boxes and frames made by hand, and frames
 * moved by a model where no capture exists, say so where they are built.
 */
class PcGeometryTest {

    private val pc = Viewport(0, 0, 1740, 978)
    private val wide = Viewport(0, 0, 1860, 1162)
    private val resized = Viewport(0, 0, 1565, 978)
    private val phone = Viewport(0, 0, 1600, 720)

    private val lists = listOf("geo-list-top", "geo-list-scroll1", "geo-list-end")

    /** Each 1740x978 capture and the screen it shows. */
    private val screens = linkedMapOf(
        "geo-home" to ScreenState.HOME,
        "geo-home-2" to ScreenState.HOME,
        "geo-home-3" to ScreenState.HOME,
        "geo-home-4" to ScreenState.HOME,
        "geo-home-5" to ScreenState.HOME,
        "geo-endbattle" to ScreenState.HOME,
        "geo-selected" to ScreenState.HOME,
        // Clash's quit prompt over the village, Battle read "Baftle".
        "geo-after-deselect" to ScreenState.HOME,
        "geo-menu" to ScreenState.MENU,
        "geo-after-esc" to ScreenState.MENU,
        "geo-army" to ScreenState.ARMY,
        "geo-myarmy" to ScreenState.ARMY,
        "geo-recipes" to ScreenState.ARMY,
        "geo-scout" to ScreenState.SCOUT,
        // The clouds parting on a scout: only the base's name and loot read through them.
        "geo-search" to ScreenState.UNKNOWN,
        // The builder list over the village.
        "geo-list-top" to ScreenState.UNKNOWN,
        "geo-list-scroll1" to ScreenState.UNKNOWN,
        "geo-list-end" to ScreenState.UNKNOWN
    )

    private val captures = screens.keys.toList()

    private val villages1860 = listOf("live-1860-home", "live-1860-village", "live-1860-counter")

    /** A capture's OCR lines as boxes; its frame must be [viewport]'s size. */
    private fun frame(name: String, viewport: Viewport = pc): List<RecognizedBox> {
        val json = JSONObject(
            PcGeometryTest::class.java.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText()
        )
        assertEquals("$name width", viewport.width, json.getInt("width"))
        assertEquals("$name height", viewport.height, json.getInt("height"))
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    private fun text(boxes: List<RecognizedBox>) = boxes.joinToString(" ") { it.text }

    /** The one box that reads [text], case and surrounding spaces aside. */
    private fun named(boxes: List<RecognizedBox>, text: String) =
        boxes.single { it.text.trim().equals(text, ignoreCase = true) }

    private fun assertInside(what: String, box: RecognizedBox, point: Point?) {
        assertNotNull(what, point)
        assertTrue("$what: $point is outside ${box.region}", box.region.contains(point!!))
    }

    private val gold = 0xFFFFD21C.toInt()
    private val whitePixel = 0xFFFFFFFF.toInt()

    /** Pixels for frames whose colours the test does not look at. */
    private val white: (Region) -> IntArray = { r -> IntArray(maxOf(1, r.width * r.height)) { whitePixel } }

    /** Pixels as the list draws a price: white digits, and the gold coin in the columns [coin]. */
    private fun coinPixels(coin: IntRange): (Region) -> IntArray = { r ->
        val width = maxOf(1, r.width)
        IntArray(maxOf(1, r.width * r.height)) { i -> if ((r.left + i % width) in coin) gold else whitePixel }
    }

    /** The coin's columns after a price on geo-list-end.png, where OCR read it as "0". */
    private val coin1740 = 1708..1724

    /**
     * Hand-made: geo-list-end with its first two cards renamed Wall, and their timers dropped, since
     * a Wall upgrades at once. Each keeps its name, its price and the coin after the digits where
     * the capture has them. No Wall card has been captured on the PC.
     */
    private fun wallFrame(): List<RecognizedBox> = frame("geo-list-end")
        .filterNot { it.text == "1d 14H" && it.top == 90 || it.text == "2d 0" }
        .map {
            when {
                it.text == "Spring Trap x 9" -> it.copy(text = "Wall x 9")
                it.text.startsWith("Giant Bomb") -> it.copy(text = "Wall x 80")
                else -> it
            }
        }

    /**
     * Synthetic: a 1740x978 capture's boxes moved onto [viewport] as the model moves the HUD and the
     * builder panel, each x kept from the right edge and every length scaled by the height.
     */
    private fun keptRight(boxes: List<RecognizedBox>, viewport: Viewport) = boxes.map {
        RecognizedBox(
            it.text, PcGeometry.x(viewport, it.left, Anchor.RIGHT), PcGeometry.y(viewport, it.top),
            PcGeometry.x(viewport, it.right, Anchor.RIGHT), PcGeometry.y(viewport, it.bottom)
        )
    }

    /**
     * Synthetic: a 1740x978 capture's boxes scaled about the frame's centre onto [viewport], by the
     * height as the HUD scales, or by the width, fitting the screen to the narrower frame.
     */
    private fun centred(boxes: List<RecognizedBox>, viewport: Viewport, byHeight: Boolean): List<RecognizedBox> {
        val scale = if (byHeight) viewport.height / 978.0 else viewport.width / 1740.0
        fun x(px: Int) = viewport.left + (viewport.width / 2.0 + (px - 870) * scale).roundToInt()
        fun y(py: Int) = viewport.top + (viewport.height / 2.0 + (py - 489) * scale).roundToInt()
        return boxes.map { RecognizedBox(it.text, x(it.left), y(it.top), x(it.right), y(it.bottom)) }
    }

    /**
     * Hand-made: the village's Battle, Build and builder counter on the 1565x978 frame, which has no
     * OCR. Found by matching geo-home's crops of each on
     * audit-evidence/live/20261024-025818-resize/1740x978-got-1565x978.png (normalised correlation
     * 0.89 to 0.98).
     */
    private val resizedHud = listOf(
        RecognizedBox("Battle", 43, 919, 149, 959),
        RecognizedBox("Build", 1427, 919, 1513, 959),
        RecognizedBox("0/6", 1400, 774, 1466, 804)
    )

    @Test fun landscapeFramesFrom13To2AreThePcs() {
        for (v in listOf(
            pc, wide, resized, Viewport(0, 0, 1920, 1080), Viewport(0, 0, 1280, 720), Viewport(0, 0, 1024, 768),
            Viewport(0, 0, 1300, 1000), Viewport(0, 0, 1999, 1000)
        )) assertTrue("$v", PcGeometry.isPc(v))
        // The phone tests' frames, 2.15:1 or wider or portrait; 2:1; narrower than 1.3:1; no frame.
        for (v in listOf(
            phone, Viewport(50, 50, 1550, 670), Viewport(50, 0, 1600, 720), Viewport(100, 50, 900, 410),
            Viewport(100, 50, 2500, 1130), Viewport(100, 50, 1700, 770), Viewport(100, 0, 1800, 720),
            Viewport(0, 0, 800, 360), Viewport(0, 0, 720, 1600), Viewport(0, 0, 2000, 1000),
            Viewport(0, 0, 1299, 1000), Viewport(0, 0, 0, 0)
        )) assertFalse("$v", PcGeometry.isPc(v))
        assertEquals("pc", PcGeometry.pick(pc, "phone", "pc"))
        assertEquals("phone", PcGeometry.pick(phone, "phone", "pc"))
    }

    @Test fun aPcValueKeepsItsDistanceFromItsEdgeScaledByTheHeight() {
        // At 1740x978 every value is itself, wherever the frame sits.
        assertEquals(Point(1609, 789), PcGeometry.point(pc, 1609, 789, Anchor.RIGHT))
        assertEquals(Point(97, 939), PcGeometry.point(pc, 97, 939, Anchor.LEFT))
        assertEquals(Point(1202, 773), PcGeometry.point(pc, 1202, 773, Anchor.CENTRE))
        assertEquals(Point(1709, 839), PcGeometry.point(Viewport(100, 50, 1840, 1028), 1609, 789, Anchor.RIGHT))
        // 1860x1162 is 1.188 times the height.
        assertEquals(Point(115, 1116), PcGeometry.point(wide, 97, 939, Anchor.LEFT))
        assertEquals(Point(1704, 937), PcGeometry.point(wide, 1609, 789, Anchor.RIGHT))
        assertEquals(Point(1324, 918), PcGeometry.point(wide, 1202, 773, Anchor.CENTRE))
        assertEquals(166, PcGeometry.length(wide, 140))
        assertEquals(Region(1304, 75, 1860, 1037), PcGeometry.region(wide, Region(1272, 63, 1740, 873), Anchor.RIGHT))
        // 1565x978 is the same height, 175 px narrower.
        assertEquals(Point(97, 939), PcGeometry.point(resized, 97, 939, Anchor.LEFT))
        assertEquals(Point(1434, 789), PcGeometry.point(resized, 1609, 789, Anchor.RIGHT))
        assertEquals(Point(1114, 773), PcGeometry.point(resized, 1202, 773, Anchor.CENTRE))
        assertEquals(140, PcGeometry.length(resized, 140))
        assertEquals(1632.0 / 1740, PcGeometry.xFraction(pc, 1632, Anchor.RIGHT), 1e-9)
    }

    @Test fun everyCaptureIsTheScreenItShows() {
        for ((name, state) in screens) assertEquals(name, state, classifyScreen(frame(name), pc))
    }

    @Test fun onlyTheQuitPromptIsTheQuitPrompt() {
        // "CONfiRM Exi+" over "Do you want Go quit Che game?"; the answer is Cancel, never Okay.
        val prompt = frame("geo-after-deselect")
        assertEquals(ReturnHome.QuitPrompt(Point(710, 604)), ReturnHome.quitPrompt(text(prompt), prompt))
        for (name in captures - "geo-after-deselect") assertFalse(name, Policy.showsQuitPrompt(text(frame(name))))
    }

    /**
     * The builders' "0/6" and the goblin's "0/1" side by side above Build. Both are drawn on every
     * village capture, but OCR returned the "0/6" on geo-home only and the "0/1" on none; nothing
     * else on any capture reads as the count.
     */
    @Test fun theBuildersCounterReadsAboveBuild() {
        for (name in captures) {
            assertEquals(name, if (name == "geo-home") BuilderCount(0, 6) else null, Builders.read(frame(name), pc))
        }
        // Hand-made: the goblin's "0/1" where its glyphs are on geo-home.png, x 1658-1701, alone,
        // beside the "0/6", and run on from it as one line, the way ML Kit read Sweat's "1/6 1/2".
        val goblin = RecognizedBox("0/1", 1651, 774, 1709, 804)
        assertTrue(Builders.inCorner(goblin, pc))
        assertNull(Builders.read(listOf(goblin), pc))
        assertEquals(BuilderCount(0, 6), Builders.read(frame("geo-home") + goblin, pc))
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(RecognizedBox("0/6 0/1", 1576, 774, 1709, 804)), pc))
    }

    @Test fun theCounterAndTheListsCloseLandOnTheirBoxes() {
        assertEquals(Point(1609, 789), builderCounterPoint(pc))
        assertInside("counter", named(frame("geo-home"), "0/6"), builderCounterPoint(pc))
        assertEquals(Point(1711, 28), upgradeListClosePoint(pc))
        // geo-list-end read no X.
        for (name in listOf("geo-list-top", "geo-list-scroll1")) {
            assertInside(name, named(frame(name), "X"), upgradeListClosePoint(pc))
        }
        // The phone's points are its own.
        assertEquals(Point(1430, 553), builderCounterPoint(phone))
        assertEquals(Point(1520, 28), upgradeListClosePoint(phone))
    }

    @Test fun theBuilderPanelShowsOnTheListFramesOnly() {
        for (name in captures) {
            val boxes = frame(name)
            assertEquals(name, name in lists, builderPanelShowing(boxes, pc))
            assertEquals(name, name in lists, upgradeListCardShowing(boxes, pc))
        }
        val panel = Region(1272, 63, 1740, 873)
        for (name in lists) assertEquals(name, panel, upgradePopupRegion(pc, frame(name)))
        val (low, high) = upgradeListSwipe(pc, towardsBottom = true)
        assertTrue("$low", panel.contains(low))
        assertTrue("$high", panel.contains(high))
    }

    @Test fun theListFramesReadAsTheList() {
        // builderPanel, hasListEvidence, headerShowing, atTop.
        val flags = mapOf(
            "geo-list-top" to listOf(true, true, true, true),
            "geo-list-scroll1" to listOf(true, true, true, false),
            "geo-list-end" to listOf(true, true, false, false),
            "geo-home" to listOf(false, false, false, false)
        )
        for ((name, want) in flags) {
            val reading = UpgradeListReader.read(frame(name), pc, white)
            assertEquals(name, want, listOf(reading.builderPanel, reading.hasListEvidence, reading.headerShowing, reading.atTop))
            assertFalse(name, reading.laboratoryShowing)
            if (name in lists) {
                // No Wall card is on these pages. The icons' level badges, "se" and "20", start no name.
                assertTrue(name, reading.rows.all { it.kind == Kind.OTHER })
                assertTrue(name, reading.rows.none { it.name.startsWith("se ") || it.name.startsWith("20 ") })
            }
        }
        // The coin after a price, read "0", is no digit of it.
        val end = UpgradeListReader.read(frame("geo-list-end"), pc, white).rows
        val giantBomb = end.single { it.name.startsWith("Giant Bomb") }
        assertEquals("4 400 000", giantBomb.priceText)
        assertEquals(Amount.Exact(4_400_000), UpgradeListReader.parseAmount(giantBomb.priceText!!))
        assertEquals("4 000 000", end.single { it.name == "AiR Bomb x 7" }.priceText)
    }

    @Test fun aWallRowReadsItsCountPriceAndCoin() {
        val walls = UpgradeListReader.read(wallFrame(), pc, coinPixels(coin1740)).rows.filter { it.kind == Kind.WALL }
        assertEquals(listOf("Wall x 9", "Wall x 80"), walls.map { it.name })
        assertEquals(listOf(9, 80), walls.map { it.count })
        assertEquals(listOf<Amount>(Amount.Exact(4_800_000), Amount.Exact(4_400_000)), walls.map { it.amount })
        assertEquals(listOf(Point(1440, 108), Point(1438, 184)), walls.map { it.tap })
        assertEquals(listOf("gold", "gold"), walls.map { it.currency })
        assertEquals(listOf(true, true), walls.map { it.payable })
        assertTrue(walls.all { it.readable && !it.clipped })
    }

    @Test fun theArmyScreenReadsItsTabsLabelsAndCards() {
        assertEquals(ArmyTab.MY_ARMY, ArmyRecipes.tab(frame("geo-army"), pc))
        assertEquals(ArmyTab.MY_ARMY, ArmyRecipes.tab(frame("geo-myarmy"), pc))
        assertEquals(ArmyTab.SAVED_RECIPES, ArmyRecipes.tab(frame("geo-recipes"), pc))
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(frame("geo-menu"), pc))
        assertEquals(ArmyTab.UNKNOWN, ArmyRecipes.tab(frame("geo-home"), pc))

        // My Army's label, then Saved Recipes'.
        val labels = mapOf(
            "geo-army" to (Point(403, 140) to Point(869, 142)),
            "geo-myarmy" to (Point(404, 140) to Point(869, 140)),
            "geo-recipes" to (Point(403, 140) to Point(869, 141))
        )
        for ((name, want) in labels) {
            val boxes = frame(name)
            assertEquals(name, want, ArmyRecipes.myArmyLabel(boxes, pc) to ArmyRecipes.savedRecipesLabel(boxes, pc))
        }

        // Each card's title, then its Use.
        val recipes = frame("geo-recipes")
        val cards = mapOf(
            "Imported Army 1" to (Point(322, 223) to Point(1524, 277)),
            "Imported Army 2" to (Point(325, 518) to Point(1524, 570)),
            "Army 3" to (Point(262, 810) to Point(1524, 864))
        )
        for ((wanted, at) in cards) {
            val card = ArmyRecipes.find(recipes, pc, wanted)
            assertNotNull(wanted, card)
            assertEquals(wanted, at, card!!.title.center to card.use.center)
        }
        assertNull(ArmyRecipes.find(recipes, pc, "Imported Army 3"))
        assertNull(ArmyRecipes.find(recipes, pc, "Army 1"))

        assertEquals(Point(1202, 773) to Point(1202, 418), ArmyRecipes.listSwipe(pc))
        assertEquals(Point(1324, 918) to Point(1324, 497), ArmyRecipes.listSwipe(wide))
    }

    /**
     * Android's idle dialog over the village, "Anyone there?" and RELOAD GAME, at both aspects. It
     * is a server dialog and not the village. RELOAD GAME sits at y 0.56-0.57, above the phone
     * band's 0.78, and the PC's band finds it.
     */
    @Test fun theIdleDialogIsAServerDialogWithReloadInItsBand() {
        for ((name, viewport) in listOf("live-1740-idle" to pc, "live-1860-idle" to wide)) {
            val boxes = frame(name, viewport)
            assertEquals(name, PopupGuard.Verdict.ServerDialog("idle"), PopupGuard.inspect(boxes, viewport, text(boxes)))
            assertEquals(name, ScreenState.UNKNOWN, classifyScreen(boxes, viewport))
            assertInside(name, named(boxes, "RELOAD GAME"), reloadTarget(boxes, viewport))
        }
        assertEquals(Point(578, 545), reloadTarget(frame("live-1740-idle"), pc))
        assertEquals(Point(651, 660), reloadTarget(frame("live-1860-idle", wide), wide))
    }

    @Test fun noOtherFrameIsAPopup() {
        for (name in captures) {
            val boxes = frame(name)
            assertEquals(name, PopupGuard.Verdict.None, PopupGuard.inspect(boxes, pc, text(boxes)))
        }
        for (name in villages1860) {
            val boxes = frame(name, wide)
            assertEquals(name, PopupGuard.Verdict.None, PopupGuard.inspect(boxes, wide, text(boxes)))
        }
    }

    @Test fun theScoutsNextAndEndBattleAreTheirWords() {
        val scout = frame("geo-scout")
        assertEquals(Point(1641, 723), nextButtonTarget(scout, pc, null))
        assertEquals(Point(105, 767), surrenderTarget(scout, pc, null))
    }

    /**
     * A selected building's buttons stand 140 px apart at 1740x978 (geo-selected: GuaRdians, then
     * IteMs), so Add Wall +1 is taken that far left of the gold button, scaled by the height.
     */
    @Test fun theSelectionButtonsPitchScalesWithTheHeight() {
        val selected = frame("geo-selected")
        val guardians = named(selected, "GuaRdians").center
        val items = named(selected, "IteMs").center
        assertEquals(140, items.x - guardians.x)
        fun goldAt(at: Point) = mapOf(
            "gold" to WallPanel.PurchaseButton("gold", at, null, RecognizedBox("7000000", at.x - 50, at.y - 60, at.x + 50, at.y - 30))
        )
        assertEquals(Point(guardians.x, items.y), WallPanel.addOneByPitch(goldAt(items), pc))
        assertEquals(Point(834, 800), WallPanel.addOneByPitch(goldAt(Point(1000, 800)), wide))
        assertEquals(Point(860, 800), WallPanel.addOneByPitch(goldAt(Point(1000, 800)), resized))
        // The phone's pitch is its own: 143 of 1600.
        assertEquals(Point(857, 500), WallPanel.addOneByPitch(goldAt(Point(1000, 500)), phone))
    }

    @Test fun theHudKeepsToItsEdgesAt16by10() {
        val home = frame("geo-home")
        // Each word's centre on the 1740x978 village, moved by its edge's rule, lands on the same
        // word on the 1860x1162 village.
        val village = frame("live-1860-village", wide)
        for ((word, read, anchor) in listOf(
            Triple("Battle", "Battle", Anchor.LEFT),
            Triple("Blood", "Blood", Anchor.LEFT),
            Triple("Build", "Build", Anchor.RIGHT),
            Triple("SHOP", "SHOp", Anchor.RIGHT)
        )) {
            val at = named(home, word).center
            assertInside("$word at 1860x1162", named(village, read), PcGeometry.point(wide, at.x, at.y, anchor))
        }
        // And on the frame narrowed to 1565x978.
        for ((word, anchor) in listOf("Battle" to Anchor.LEFT, "Build" to Anchor.RIGHT, "0/6" to Anchor.RIGHT)) {
            val at = named(home, word).center
            assertInside("$word at 1565x978", named(resizedHud, word), PcGeometry.point(resized, at.x, at.y, anchor))
        }
    }

    /**
     * The event bar along the bottom left ends at x 636, y 884-960, on geo-home.png and on the
     * 1565x978 frame alike, with the "<" that folds it at about x 590-615 (measured on the images;
     * OCR reads none of it). A collection tap is never sent onto it, and the ground past it is still
     * the village's. At 1860x1162 the bar is where the left edge's rule puts it.
     */
    @Test fun aCollectionTapKeepsOffTheEventBar() {
        for (viewport in listOf(pc, resized, wide)) {
            val onBar = PcGeometry.point(viewport, 630, 920, Anchor.LEFT)
            val pastIt = PcGeometry.point(viewport, 700, 920, Anchor.LEFT)
            assertFalse("$viewport $onBar", CollectionPolicy.inVillage(onBar, viewport))
            assertTrue("$viewport $pastIt", CollectionPolicy.inVillage(pastIt, viewport))
        }
    }

    @Test fun theVillageAt16by10IsHomeAndItsCounterReads() {
        for (name in villages1860) {
            val boxes = frame(name, wide)
            assertEquals(name, ScreenState.HOME, classifyScreen(boxes, wide))
            assertFalse(name, builderPanelShowing(boxes, wide))
            assertFalse(name, upgradeListCardShowing(boxes, wide))
        }
        val counter = frame("live-1860-counter", wide)
        assertEquals(BuilderCount(0, 6), Builders.read(counter, wide))
        assertInside("counter at 1860x1162", named(counter, "0/6"), builderCounterPoint(wide))
        // Hand-made: the goblin's "0/1" where its glyphs are, x 1760-1811 of
        // audit-evidence/live/20261024-030026-restart/after-launch-45s.png, and the two as one line.
        val goblin = RecognizedBox("0/1", 1753, 919, 1818, 955)
        assertTrue(Builders.inCorner(goblin, wide))
        assertNull(Builders.read(listOf(goblin), wide))
        assertEquals(BuilderCount(0, 6), Builders.read(counter + goblin, wide))
        assertEquals(BuilderCount(0, 6), Builders.read(listOf(RecognizedBox("0/6 0/1", 1663, 919, 1818, 955)), wide))

        assertEquals(ScreenState.HOME, classifyScreen(resizedHud, resized))
        assertEquals(BuilderCount(0, 6), Builders.read(resizedHud, resized))
        assertInside("counter at 1565x978", named(resizedHud, "0/6"), builderCounterPoint(resized))
    }

    /**
     * Synthetic: the list frames moved onto both 16:10 frames as the model moves the panel still
     * read as the list, every row named and priced as at 1740x978, with the close X and the swipe
     * on the panel and the hand-made Wall cards whole. No list has been captured at 16:10.
     */
    @Test fun theListMovedByTheModelStillReadsAtBothSizes() {
        for ((viewport, panel) in listOf(wide to Region(1304, 75, 1860, 1037), resized to Region(1097, 63, 1565, 873))) {
            for (name in lists) {
                val boxes = keptRight(frame(name), viewport)
                assertTrue("$name $viewport", builderPanelShowing(boxes, viewport))
                assertTrue("$name $viewport", upgradeListCardShowing(boxes, viewport))
                assertEquals("$name $viewport", panel, upgradePopupRegion(viewport, boxes))
                assertTrue("$name $viewport", UpgradeListReader.read(boxes, viewport, white).builderPanel)
                // A card's second line, 22-29 px under its name at 1740x978, stays on its card.
                assertEquals(
                    "$name $viewport",
                    UpgradeListReader.read(frame(name), pc, white).rows.map { it.name to it.priceText },
                    UpgradeListReader.read(boxes, viewport, white).rows.map { it.name to it.priceText }
                )
            }
            for (name in listOf("geo-list-top", "geo-list-scroll1")) {
                assertInside("$name $viewport", named(keptRight(frame(name), viewport), "X"), upgradeListClosePoint(viewport))
            }
            val (low, high) = upgradeListSwipe(viewport, towardsBottom = true)
            assertTrue("$viewport $low", panel.contains(low))
            assertTrue("$viewport $high", panel.contains(high))

            val coin = PcGeometry.x(viewport, coin1740.first, Anchor.RIGHT)..PcGeometry.x(viewport, coin1740.last, Anchor.RIGHT)
            val walls = UpgradeListReader.read(keptRight(wallFrame(), viewport), viewport, coinPixels(coin)).rows
                .filter { it.kind == Kind.WALL }
            assertEquals("$viewport", listOf(9, 80), walls.map { it.count })
            assertEquals("$viewport", listOf<Amount>(Amount.Exact(4_800_000), Amount.Exact(4_400_000)), walls.map { it.amount })
            assertEquals("$viewport", listOf("gold", "gold"), walls.map { it.currency })
            assertEquals("$viewport", listOf(true, true), walls.map { it.payable })
            assertTrue("$viewport", walls.all { it.readable && !it.clipped })
        }
    }

    /**
     * Synthetic: the army screen scaled about the centre onto both 16:10 frames, by the height or by
     * the width, still reads its tabs, labels and cards. No army screen has been captured at 16:10,
     * so either scale may be the one Clash uses.
     */
    @Test fun theArmyScreenReadsUnderEitherScaleAt16by10() {
        for (viewport in listOf(wide, resized)) for (byHeight in listOf(true, false)) {
            val at = "$viewport by the ${if (byHeight) "height" else "width"}"
            for ((name, tab) in listOf(
                "geo-army" to ArmyTab.MY_ARMY, "geo-myarmy" to ArmyTab.MY_ARMY, "geo-recipes" to ArmyTab.SAVED_RECIPES
            )) {
                val boxes = centred(frame(name), viewport, byHeight)
                assertEquals("$name $at", tab, ArmyRecipes.tab(boxes, viewport))
                assertNotNull("$name $at", ArmyRecipes.myArmyLabel(boxes, viewport))
                assertNotNull("$name $at", ArmyRecipes.savedRecipesLabel(boxes, viewport))
            }
            val recipes = centred(frame("geo-recipes"), viewport, byHeight)
            for (wanted in listOf("Imported Army 1", "Imported Army 2", "Army 3")) {
                assertNotNull("$wanted $at", ArmyRecipes.find(recipes, viewport, wanted))
            }
            assertNull(at, ArmyRecipes.find(recipes, viewport, "Imported Army 3"))
            assertNull(at, ArmyRecipes.find(recipes, viewport, "Army 1"))
        }
    }
}
