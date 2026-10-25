package local.clashfarmer.desktop

import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A 1740x978 calibration moved onto the game areas Google Play Games has given: 1860x1162 after a
 * relaunch, 1565x978 when 1740x978 was asked of that, and the 16:9 sizes 1920x1080 and 1280x720.
 *
 * [calibrated] is the owner's working calibration, its positions only. The 16:10 checks read the
 * OCR of `live-1860-village`, copied from the sidecar's read of
 * audit-evidence/live/20261024-034034-dragmeasure/4b-right150-back.png, and of `live-1860-army`,
 * the army screen in audit-evidence/live/20261025-033812-menus/2-army.png without the HUD's top rows.
 */
class PcProfilesTest {

    private val calibrated = PhoneProfile(
        resolutionWidth = 1740,
        resolutionHeight = 978,
        viewport = Viewport(0, 0, 1740, 978),
        stateAnchors = mapOf(
            "find_match" to Point(220, 690),
            "army" to Point(1470, 832),
            "next" to Point(1612, 745),
            "end_battle" to Point(105, 768),
            "attack" to Point(96, 885),
            "return_home" to Point(870, 869)
        ),
        troopAnchors = mapOf(
            "valkyries" to Point(307, 903), "king" to Point(429, 903), "queen" to Point(539, 903),
            "warden" to Point(649, 903), "champion" to Point(759, 903), "rages" to Point(881, 903),
            "jumps" to Point(991, 903)
        ),
        troopRois = mapOf(
            "valkyries" to Region(270, 846, 345, 962), "king" to Region(392, 846, 467, 962),
            "queen" to Region(502, 846, 577, 962), "warden" to Region(612, 846, 687, 962),
            "champion" to Region(722, 846, 797, 962), "rages" to Region(844, 846, 919, 962),
            "jumps" to Region(954, 846, 1029, 962)
        ),
        perimeterSegments = listOf(
            Pair(Point(307, 322), Point(179, 418)),
            Pair(Point(179, 466), Point(307, 562)),
            Pair(Point(1445, 310), Point(1573, 406)),
            Pair(Point(1573, 454), Point(1445, 550))
        ),
        heroDrop = Point(235, 376),
        homeResourceRois = mapOf(
            Resource.GOLD to Region(1485, 22, 1705, 62),
            Resource.ELIXIR to Region(1498, 78, 1718, 118),
            Resource.DARK_ELIXIR to Region(1508, 134, 1728, 174)
        ),
        scoutLootRois = mapOf(
            Resource.GOLD to Region(74, 99, 242, 133),
            Resource.ELIXIR to Region(74, 141, 242, 175),
            Resource.DARK_ELIXIR to Region(74, 185, 242, 219)
        ),
        scoutCountdownRoi = Region(814, 44, 928, 82),
        scoutFeeRoi = Region(1578, 749, 1678, 783),
        isVerified = true
    )

    private val wide = SurfaceSize(1860, 1162)
    private val resized = SurfaceSize(1565, 978)

    private fun frame(name: String): List<RecognizedBox> {
        val json = JSONObject(
            PcProfilesTest::class.java.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText()
        )
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    /** Whether [roi] holds the full width of [box] and its middle row, which is what OCR needs. */
    private fun reads(roi: Region, box: RecognizedBox): Boolean =
        roi.left <= box.left && roi.right >= box.right && (box.top + box.bottom) / 2 in roi.top until roi.bottom

    @Test
    fun theCalibratedSizeIsTheCalibrationItself() {
        assertSame(calibrated, PcProfiles.derive(calibrated, SurfaceSize(1740, 978), emptySet()))
        val unverified = calibrated.copy(isVerified = false)
        assertSame(unverified, PcProfiles.derive(unverified, SurfaceSize(1740, 978), emptySet()))
    }

    @Test
    fun at16by10BattleIsTappedOnItsButton() {
        // The Battle button on 4b-right150-back.png, measured by eye from the image: the icon and
        // its word run x 25-200, y 968-1142. OCR reads the word at [50,1084,179,1139].
        val button = Region(25, 968, 200, 1142)
        val word = frame("live-1860-village").single { it.text.trim().equals("Battle", ignoreCase = true) }
        assertTrue(button.contains(word.left, word.top))
        val attack = PcProfiles.derive(calibrated, wide, emptySet()).stateAnchors.getValue("attack")
        assertEquals(Point(114, 1052), attack)
        assertTrue("$attack is off the button", button.contains(attack))
        assertFalse("the raw point should miss", button.contains(calibrated.stateAnchors.getValue("attack")))
    }

    @Test
    fun at16by10TheStorageBoxesHoldTheBalances() {
        val boxes = frame("live-1860-village")
        val gold = boxes.single { it.text == "29 303 105" }
        val elixir = boxes.single { it.text == "8 797" }
        val elixirTail = boxes.single { it.text == "599" }
        val dark = boxes.single { it.text == "158 081" }
        val home = PcProfiles.derive(calibrated, wide, emptySet()).homeResourceRois
        assertEquals(Region(1557, 26, 1818, 74), home[Resource.GOLD])
        assertTrue(reads(home.getValue(Resource.GOLD), gold))
        assertTrue(reads(home.getValue(Resource.ELIXIR), elixir))
        assertTrue(reads(home.getValue(Resource.ELIXIR), elixirTail))
        assertTrue(reads(home.getValue(Resource.DARK_ELIXIR), dark))
        // The calibration as saved cuts every balance short.
        assertFalse(reads(calibrated.homeResourceRois.getValue(Resource.GOLD), gold))
        assertFalse(reads(calibrated.homeResourceRois.getValue(Resource.DARK_ELIXIR), dark))
    }

    @Test
    fun at16by10EachFieldKeepsItsEdge() {
        val moved = PcProfiles.derive(calibrated, wide, emptySet())
        assertEquals(Point(125, 912), moved.stateAnchors["end_battle"])
        assertEquals(Point(261, 820), moved.stateAnchors["find_match"])
        assertEquals(Point(1708, 885), moved.stateAnchors["next"])
        assertEquals(Point(930, 1032), moved.stateAnchors["return_home"])
        assertEquals(Point(1571, 948), moved.stateAnchors["army"])
        assertEquals(Point(261, 1073), moved.troopAnchors["valkyries"])
        assertEquals(1074, moved.troopAnchors.getValue("jumps").x)
        assertEquals(Region(88, 118, 288, 158), moved.scoutLootRois[Resource.GOLD])
        assertEquals(Region(1668, 890, 1786, 930), moved.scoutFeeRoi)
        assertEquals(Region(863, 52, 999, 97), moved.scoutCountdownRoi)
        assertEquals(SurfaceSize(1860, 1162), SurfaceSize(moved.resolutionWidth, moved.resolutionHeight))
        assertEquals(Viewport(0, 0, 1860, 1162), moved.viewport)
    }

    @Test
    fun at16by10TheDropPointsAreFittedToTheMap() {
        // Clash fits the zoomed-out battle map inside a 16:10 game area, as it does the army
        // screen. On a live 1860x1162 battle (audit-evidence/live/20261025-055900-battle1610) these
        // segments lie on the grass edge at the left and right corners; scaled by the height they
        // fell on the beach and in the forest, and every Valkyrie drop was refused.
        val moved = PcProfiles.derive(calibrated, wide, emptySet())
        assertEquals(
            listOf(
                Pair(Point(328, 402), Point(191, 505)),
                Pair(Point(191, 556), Point(328, 659)),
                Pair(Point(1545, 390), Point(1681, 492)),
                Pair(Point(1681, 544), Point(1545, 646))
            ),
            moved.perimeterSegments
        )
        assertEquals(Point(251, 460), moved.heroDrop)
        val withAnchor = calibrated.copy(heroDrop = null, stateAnchors = calibrated.stateAnchors + ("hero_drop" to Point(235, 376)))
        assertEquals(Point(251, 460), PcProfiles.derive(withAnchor, wide, emptySet()).stateAnchors["hero_drop"])
        assertEquals(Point(211, 387), PcProfiles.derive(calibrated, resized, emptySet()).heroDrop)
    }

    @Test
    fun movedPointsAgreeWithTheHudRule() {
        // From 1740x978, moving a point is PcGeometry's own placement of a PC value.
        for (size in listOf(wide, resized, SurfaceSize(1920, 1080), SurfaceSize(1280, 720))) {
            val viewport = Viewport(0, 0, size.width, size.height)
            val moved = PcProfiles.derive(calibrated, size, emptySet())
            val next = calibrated.stateAnchors.getValue("next")
            val attack = calibrated.stateAnchors.getValue("attack")
            assertEquals(PcGeometry.point(viewport, next.x, next.y, Anchor.RIGHT), moved.stateAnchors["next"])
            assertEquals(PcGeometry.point(viewport, attack.x, attack.y, Anchor.LEFT), moved.stateAnchors["attack"])
            val ret = calibrated.stateAnchors.getValue("return_home")
            val placed = PcGeometry.point(viewport, ret.x, ret.y, Anchor.CENTRE)
            val got = moved.stateAnchors.getValue("return_home")
            assertTrue("$size: $got against $placed", abs(got.x - placed.x) <= 1 && got.y == placed.y)
        }
    }

    @Test
    fun at1565by978TheLeftCornerStaysAndTheRightFollowsTheEdge() {
        val moved = PcProfiles.derive(calibrated, resized, emptySet())
        assertEquals(Point(96, 885), moved.stateAnchors["attack"])
        assertEquals(Point(1437, 745), moved.stateAnchors["next"])
        assertEquals(Region(1310, 22, 1530, 62), moved.homeResourceRois[Resource.GOLD])
        assertEquals(Point(783, 869), moved.stateAnchors["return_home"])
    }

    @Test
    fun atAnother16by9SizeEverythingScalesAlike() {
        val size = SurfaceSize(1920, 1080)
        val moved = PcProfiles.derive(calibrated, size, emptySet())
        val sx = 1920.0 / 1740
        val sy = 1080.0 / 978
        fun near(from: Point, to: Point) =
            assertTrue("$from -> $to", abs(from.x * sx - to.x) <= 2 && abs(from.y * sy - to.y) <= 2)
        calibrated.stateAnchors.forEach { (name, p) -> near(p, moved.stateAnchors.getValue(name)) }
        calibrated.troopAnchors.forEach { (name, p) -> near(p, moved.troopAnchors.getValue(name)) }
        calibrated.perimeterSegments.zip(moved.perimeterSegments).forEach { (a, b) ->
            near(a.first, b.first)
            near(a.second, b.second)
        }
    }

    @Test
    fun aRoundTripComesBackWithinAPixel() {
        val back = PcProfiles.derive(PcProfiles.derive(calibrated, wide, emptySet()), SurfaceSize(1740, 978), emptySet())
        fun near(a: Point, b: Point) = assertTrue("$a -> $b", abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1)
        calibrated.stateAnchors.forEach { (name, p) -> near(p, back.stateAnchors.getValue(name)) }
        calibrated.troopAnchors.forEach { (name, p) -> near(p, back.troopAnchors.getValue(name)) }
        near(calibrated.heroDrop!!, back.heroDrop!!)
        calibrated.perimeterSegments.zip(back.perimeterSegments).forEach { (a, b) ->
            near(a.first, b.first)
            near(a.second, b.second)
        }
    }

    @Test
    fun aMovedCalibrationIsValidAtEverySizeSeen() {
        for (size in listOf(wide, resized, SurfaceSize(1280, 720), SurfaceSize(1920, 1080))) {
            PcProfiles.derive(calibrated, size, emptySet()).validateForAttack()
        }
    }

    @Test
    fun anotherShapeIsUnverifiedUntilAnAttackWorksThere() {
        assertFalse(PcProfiles.derive(calibrated, wide, emptySet()).isVerified)
        assertFalse(PcProfiles.derive(calibrated, wide, setOf(1.78)).isVerified)
        assertTrue(PcProfiles.derive(calibrated, wide, setOf(1.6)).isVerified)
        // 1565x978 is 1.6002: the same shape.
        assertTrue(PcProfiles.derive(calibrated, resized, setOf(1.6)).isVerified)
        // The same shape at another size needs nothing more.
        assertTrue(PcProfiles.derive(calibrated, SurfaceSize(1920, 1080), emptySet()).isVerified)
        // A calibration not yet verified where it was taken is not verified anywhere.
        val unverified = calibrated.copy(isVerified = false)
        assertFalse(PcProfiles.derive(unverified, wide, setOf(1.6)).isVerified)
        assertFalse(PcProfiles.derive(unverified, SurfaceSize(1920, 1080), setOf(1.78)).isVerified)
    }

    @Test
    fun collectorsAreDroppedWithTheSize() {
        val withTargets = calibrated.copy(collectionTargets = listOf(CollectionTarget("mine", Point(800, 500))))
        assertTrue(PcProfiles.derive(withTargets, wide, emptySet()).collectionTargets.isEmpty())
        assertEquals(1, PcProfiles.derive(withTargets, SurfaceSize(1740, 978), emptySet()).collectionTargets.size)
    }

    @Test
    fun aShapeOrSizeTheBotCannotReadIsRefusedInPlainWords() {
        try {
            PcProfiles.derive(calibrated, SurfaceSize(2200, 978), emptySet())
            fail("a 2.25:1 area should be refused")
        } catch (e: StopException) {
            assertTrue(e.message!!, e.message!!.contains("2200x978") && e.message!!.contains("16:9 or 16:10"))
        }
        try {
            PcProfiles.derive(calibrated, SurfaceSize(1120, 700), emptySet())
            fail("an area under 720 rows should be refused")
        } catch (e: StopException) {
            assertTrue(e.message!!, e.message!!.contains("too small"))
        }
        try {
            PcProfiles.derive(PhoneProfile.createDefault(1600, 720), wide, emptySet())
            fail("a calibration of a shape that is not the PC's should be refused")
        } catch (e: StopException) {
            assertTrue(e.message!!, e.message!!.contains("Calibrate"))
        }
        assertEquals(null, PcProfiles.surfaceProblem(SurfaceSize(1280, 720)))
    }

    @Test
    fun shapesAreNamedTheWayTheUserKnowsThem() {
        assertEquals("16:9", PcProfiles.shape(1740, 978))
        assertEquals("16:10", PcProfiles.shape(1860, 1162))
        assertEquals("16:10", PcProfiles.shape(1565, 978))
        assertEquals("1.33:1", PcProfiles.shape(1024, 768))
    }

    @Test
    fun verifiedShapesAreKeptBesideTheProfileAndCleared() {
        val dir = Files.createTempDirectory("surfaces").toFile()
        try {
            val profileFile = File(dir, "profile.json")
            assertTrue(SurfaceVerification.aspects(profileFile).isEmpty())
            SurfaceVerification.record(profileFile, 1860, 1162)
            assertEquals(setOf(1.6), SurfaceVerification.aspects(profileFile))
            SurfaceVerification.record(profileFile, 1565, 978)
            assertEquals(setOf(1.6), SurfaceVerification.aspects(profileFile))
            SurfaceVerification.record(profileFile, 1600, 1200)
            assertEquals(setOf(1.33, 1.6), SurfaceVerification.aspects(profileFile))
            assertFalse(profileFile.exists())
            SurfaceVerification.clear(profileFile)
            assertTrue(SurfaceVerification.aspects(profileFile).isEmpty())
            SurfaceVerification.file(profileFile).writeText("not json")
            assertTrue(SurfaceVerification.aspects(profileFile).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSizePastTheMonitorShrinksAtItsAspect() {
        val want = SurfaceSize(1740, 978)
        assertSame(want, PcProfiles.fitInside(want, null))
        assertSame(want, PcProfiles.fitInside(want, SurfaceSize(1740, 978)))
        assertEquals(SurfaceSize(1600, 899), PcProfiles.fitInside(want, SurfaceSize(1600, 1000)))
        assertEquals(SurfaceSize(1531, 861), PcProfiles.fitInside(want, SurfaceSize(1900, 861)))
        // A 1366x768 laptop's work area under its taskbar, with GPG's chrome taken off, is under 720 rows.
        val laptop = PcProfiles.fitInside(want, SurfaceSize(1350, 700))
        assertEquals(SurfaceSize(1245, 700), laptop)
        assertTrue(PcProfiles.surfaceProblem(laptop)!!.contains("too small"))
    }

    @Test
    fun gameAreaStopsAreSaidInWords() {
        val guard = "Session is stopped: Viewport mismatch: expected Viewport(left=0, top=0, right=1740, bottom=978), " +
            "got Viewport(left=0, top=0, right=1860, bottom=1162)"
        assertEquals(
            "Clash's game area changed from 1740x978 to 1860x1162 during the run. " +
                "Leave the Clash window's size alone while the bot runs, then start again.",
            PcProfiles.plainStopReason(guard)
        )
        assertTrue(PcProfiles.plainStopReason("Viewport mismatch: expected Viewport(left=0, top=0, right=1740, bottom=978), got null")
            .startsWith("Clash's game area went missing"))
        val runner = "The screen is Viewport(left=0, top=0, right=1565, bottom=978) but this profile was calibrated for " +
            "Viewport(left=0, top=0, right=1740, bottom=978). Put Clash in the orientation you calibrated, or calibrate again."
        val plain = PcProfiles.plainStopReason(runner)
        assertTrue(plain, plain.startsWith("Clash's game area is 1565x978, not the 1740x978 the run expected."))
        assertFalse(plain.contains("Viewport") || plain.contains("orientation"))
        assertEquals("Stopped by user", PcProfiles.plainStopReason("Stopped by user"))
    }

    @Test
    fun aRunSizesOnlyAnAreaOfTheCalibrationsShape() {
        val size = SurfaceSize(1740, 978)
        assertEquals(size, PcProfiles.runSize(SurfaceSize(1280, 720), null, size))
        assertEquals(SurfaceSize(1600, 899), PcProfiles.runSize(SurfaceSize(1280, 720), SurfaceSize(1600, 900), size))
        assertEquals(wide, PcProfiles.runSize(wide, SurfaceSize(1600, 900), size))
    }

    @Test
    fun anUnverifiedCalibrationMovedElsewhereIsToldToCalibrateAgain() {
        val unverified = calibrated.copy(isVerified = false)
        fun refusal(saved: PhoneProfile, run: SurfaceSize?, runVerified: Boolean, attacks: Boolean = true, allowed: Boolean) =
            PcProfiles.startRefusal("Main", saved, run, runVerified, attacks, allowed)

        // At its own size, or before the area is known, the supervised attack earns it, as before.
        assertEquals(null, refusal(unverified, SurfaceSize(1740, 978), runVerified = false, allowed = true))
        assertEquals(null, refusal(unverified, null, runVerified = false, allowed = true))
        val farm = refusal(unverified, SurfaceSize(1740, 978), runVerified = false, allowed = false)!!
        assertTrue(farm, farm.startsWith("Main has not earned verification on this PC. Run \"Supervised attack\" once"))

        // At 16:10 no attack can earn it, so even the supervised one is refused, with advice that works.
        for (allowed in listOf(true, false)) {
            val moved = refusal(unverified, wide, runVerified = false, allowed = allowed)!!
            assertTrue(moved, moved.contains("calibrated at 1740x978, not on this 1860x1162 game area"))
            assertTrue(moved, moved.contains("Open Calibrate") && moved.contains("restart the PC and open Clash of Clans on a 16:9 screen"))
        }
        // A smaller screen of the same shape: restarting would not help.
        val smaller = refusal(unverified, SurfaceSize(1600, 899), runVerified = false, allowed = true)!!
        assertFalse(smaller, smaller.contains("restart"))
        // A wall-only run needs no verification anywhere.
        assertEquals(null, refusal(unverified, wide, runVerified = false, attacks = false, allowed = true))

        // A verified calibration at a new shape: the supervised attack earns that shape.
        assertEquals(null, refusal(calibrated, wide, runVerified = false, allowed = true))
        val newShape = refusal(calibrated, wide, runVerified = false, allowed = false)!!
        assertTrue(newShape, newShape.contains("has not had a supervised attack at 16:10 yet. Run \"Supervised attack\" once"))
        assertEquals(null, refusal(calibrated, wide, runVerified = true, allowed = false))
    }

    /**
     * Synthetic: a 1740x978 capture's box scaled about the frame's centre onto [size], by the
     * height as the HUD scales, or by the width, fitting the screen to the narrower frame
     * (PcGeometryTest's centred).
     */
    private fun centred(box: Region, size: SurfaceSize, byHeight: Boolean): Region {
        val scale = if (byHeight) size.height / 978.0 else size.width / 1740.0
        fun x(px: Int) = (size.width / 2.0 + (px - 870) * scale).roundToInt()
        fun y(py: Int) = (size.height / 2.0 + (py - 489) * scale).roundToInt()
        return Region(x(box.left), y(box.top), x(box.right), y(box.bottom))
    }

    /** A 1740x978 box on a screen Clash fits inside [size]: scaled alike both ways, by the smaller scale. */
    private fun fitted(box: Region, size: SurfaceSize): Region =
        centred(box, size, byHeight = size.height / 978.0 <= size.width / 1740.0)

    /** A 1740x978 box placed on [size] by [PcGeometry], keeping its distance from [anchor]'s edge. */
    private fun placed(box: RecognizedBox, size: SurfaceSize, anchor: Anchor): RecognizedBox {
        val viewport = Viewport(0, 0, size.width, size.height)
        return RecognizedBox(
            box.text, PcGeometry.x(viewport, box.left, anchor), PcGeometry.y(viewport, box.top),
            PcGeometry.x(viewport, box.right, anchor), PcGeometry.y(viewport, box.bottom)
        )
    }

    /** Whether [p] is more than [margin] px from [box]. */
    private fun clear(p: Point, box: Region, margin: Int): Boolean =
        p.x < box.left - margin || p.x > box.right + margin || p.y < box.top - margin || p.y > box.bottom + margin

    /** The share of [box]'s width inside [roi], or 0 when its middle row is outside. */
    private fun cover(roi: Region, box: RecognizedBox): Double {
        if ((box.top + box.bottom) / 2 !in roi.top until roi.bottom) return 0.0
        val overlap = minOf(roi.right, box.right) - maxOf(roi.left, box.left)
        return overlap.coerceAtLeast(0).toDouble() / (box.right - box.left)
    }

    @Test
    fun theMovedArmyTapStaysOnAttackAndAwayFromTheGemButton() {
        // Hand-made: the OCR has no words for the buttons under the army, so their green faces were
        // measured on audit-evidence/spike/geo-army.png at 1740x978 and on the 16:10 capture behind
        // live-1860-army. The left one finishes training for gems; the right one asks the clan for
        // troops. The army is tapped without reading its label.
        val attack = Region(1322, 805, 1617, 866)
        val gems = Region(1322, 728, 1460, 790)
        val clan = Region(1478, 726, 1618, 790)
        val word = frame("geo-army").single { it.text == "Atfack!" }
        assertTrue(attack.contains((word.left + word.right) / 2, (word.top + word.bottom) / 2))
        val army = calibrated.stateAnchors.getValue("army")
        assertTrue(attack.contains(army))

        // At 1860x1162 Clash fits the army screen inside the game area: it grows by the width's
        // 1.069, not the height's 1.188, about the centre, so each face is within 8 px of fitted().
        val wideAttack = Region(1419, 924, 1731, 983)
        val wideGems = Region(1419, 841, 1561, 899)
        val wideClan = Region(1588, 841, 1731, 899)
        val wideWord = frame("live-1860-army").single { it.text == "Attack!" }
        assertTrue(wideAttack.contains((wideWord.left + wideWord.right) / 2, (wideWord.top + wideWord.bottom) / 2))
        for ((at1740, at1860) in listOf(attack to wideAttack, gems to wideGems, clan to wideClan)) {
            val placed = fitted(at1740, wide)
            assertTrue(
                "$at1740 fits to $placed, measured $at1860",
                abs(placed.left - at1860.left) <= 8 && abs(placed.top - at1860.top) <= 8 &&
                    abs(placed.right - at1860.right) <= 8 && abs(placed.bottom - at1860.bottom) <= 8
            )
        }
        val tap = PcProfiles.derive(calibrated, wide, emptySet()).stateAnchors.getValue("army")
        assertTrue("$tap is off Attack!", wideAttack.contains(tap))
        assertTrue("$tap is near the gem button", clear(tap, wideGems, 20))
        assertTrue("$tap is near the clan button", clear(tap, wideClan, 20))
        // The height's rule, which the rest of the 16:10 screens follow, misses the button's bottom.
        val byHeight = PcGeometry.point(Viewport(0, 0, wide.width, wide.height), army.x, army.y, Anchor.CENTRE)
        assertFalse("$byHeight should miss", wideAttack.contains(byHeight))

        for (size in listOf(wide, resized, SurfaceSize(1920, 1080), SurfaceSize(1280, 720))) {
            val moved = PcProfiles.derive(calibrated, size, emptySet()).stateAnchors.getValue("army")
            assertTrue("$size: $moved is off Attack!", fitted(attack, size).contains(moved))
            assertTrue("$size: $moved is near the gem button", clear(moved, fitted(gems, size), 8))
            assertTrue("$size: $moved is near the clan button", clear(moved, fitted(clan, size), 8))
        }
    }

    @Test
    fun a16by10CalibrationPutsTheArmyTapBackOn16by9() {
        // A friend who calibrates at 1860x1162 and later gets 1740x978: the army screen shrinks by
        // the width's 0.935, and the tap lands where the 16:9 calibration has it.
        val at1610 = PcProfiles.derive(calibrated, wide, emptySet()).copy(isVerified = true)
        assertEquals(Point(1470, 832), PcProfiles.derive(at1610, SurfaceSize(1740, 978), emptySet()).stateAnchors["army"])
        assertEquals(Point(1622, 919), PcProfiles.derive(at1610, SurfaceSize(1920, 1080), emptySet()).stateAnchors["army"])
    }

    @Test
    fun movedScoutBoxesHoldWhatTheyReadAsWellAsAt1740() {
        // geo-scout's OCR, placed on each size by the anchor each part of the scout screen keeps:
        // the loot from the left, the fee beside Next from the right, the countdown and the
        // tray's cards about the centre. At 1740x978 the calibration cuts a few of them short.
        val scout = frame("geo-scout")
        val loot = mapOf(
            Resource.GOLD to scout.single { it.text == "1 193 372" },
            Resource.ELIXIR to scout.single { it.text == "884 775" },
            Resource.DARK_ELIXIR to scout.single { it.text == "4 437" }
        )
        val fee = scout.single { it.text == "1700" }
        val countdown = scout.single { it.text == "265" }
        // The count on the first card, the Valkyries.
        val count = scout.single { it.text == "x44" }

        for (size in listOf(wide, resized, SurfaceSize(1920, 1080))) {
            val moved = PcProfiles.derive(calibrated, size, emptySet())
            fun holds(what: String, before: Region, after: Region, box: RecognizedBox, anchor: Anchor) {
                val was = cover(before, box)
                val now = cover(after, placed(box, size, anchor))
                assertTrue("$size $what: ${"%.2f".format(now)} of the text, was ${"%.2f".format(was)}", was >= 0.85 && now >= was - 0.03)
            }
            loot.forEach { (resource, box) ->
                holds("$resource loot", calibrated.scoutLootRois.getValue(resource), moved.scoutLootRois.getValue(resource), box, Anchor.LEFT)
            }
            holds("fee", calibrated.scoutFeeRoi!!, moved.scoutFeeRoi!!, fee, Anchor.RIGHT)
            holds("countdown", calibrated.scoutCountdownRoi!!, moved.scoutCountdownRoi!!, countdown, Anchor.CENTRE)

            val card = centred(Region(count.left, count.top, count.right, count.bottom), size, byHeight = true)
            val middle = Point((card.left + card.right) / 2, (card.top + card.bottom) / 2)
            assertTrue("$size: the count is off its card", moved.troopRois.getValue("valkyries").contains(middle))
            assertFalse("$size: the count is on the next card", moved.troopRois.getValue("king").contains(middle))
        }
    }

    @Test
    fun movingAnXKeepsItsEdge() {
        assertEquals(114, PcGeometry.moveX(96, 1740, 1860, 1162 / 978.0, Anchor.LEFT))
        assertEquals(1708, PcGeometry.moveX(1612, 1740, 1860, 1162 / 978.0, Anchor.RIGHT))
        assertEquals(930, PcGeometry.moveX(870, 1740, 1860, 1162 / 978.0, Anchor.CENTRE))
        assertEquals(1052, PcGeometry.moveY(885, 1162 / 978.0))
    }
}
