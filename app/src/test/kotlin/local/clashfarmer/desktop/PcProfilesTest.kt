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

/**
 * A 1740x978 calibration moved onto the game areas Google Play Games has given: 1860x1162 after a
 * relaunch, 1565x978 when 1740x978 was asked of that, and the 16:9 sizes 1920x1080 and 1280x720.
 *
 * [calibrated] is the owner's working calibration, its positions only. The 16:10 checks read the
 * OCR of `live-1860-village`, copied from the sidecar's read of
 * audit-evidence/live/20261024-034034-dragmeasure/4b-right150-back.png.
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
        assertEquals(Point(1643, 989), moved.stateAnchors["army"])
        assertEquals(Point(261, 1073), moved.troopAnchors["valkyries"])
        assertEquals(1074, moved.troopAnchors.getValue("jumps").x)
        assertEquals(Point(176, 447), moved.heroDrop)
        assertEquals(Region(88, 118, 288, 158), moved.scoutLootRois[Resource.GOLD])
        assertEquals(Region(1668, 890, 1786, 930), moved.scoutFeeRoi)
        assertEquals(Region(863, 52, 999, 97), moved.scoutCountdownRoi)
        assertEquals(SurfaceSize(1860, 1162), SurfaceSize(moved.resolutionWidth, moved.resolutionHeight))
        assertEquals(Viewport(0, 0, 1860, 1162), moved.viewport)
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
    fun movingAnXKeepsItsEdge() {
        assertEquals(114, PcGeometry.moveX(96, 1740, 1860, 1162 / 978.0, Anchor.LEFT))
        assertEquals(1708, PcGeometry.moveX(1612, 1740, 1860, 1162 / 978.0, Anchor.RIGHT))
        assertEquals(930, PcGeometry.moveX(870, 1740, 1860, 1162 / 978.0, Anchor.CENTRE))
        assertEquals(1052, PcGeometry.moveY(885, 1162 / 978.0))
    }
}
