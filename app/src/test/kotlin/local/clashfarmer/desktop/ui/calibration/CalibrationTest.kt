package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.AttackPlanner
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    @Test
    fun `the 38 targets keep the phone's names and numbering`() {
        assertEquals(38, CALIBRATION_TARGETS.size)
        assertEquals(38, CALIBRATION_TARGETS.map { it.name }.toSet().size)
        CALIBRATION_TARGETS.forEachIndexed { i, target ->
            assertTrue(target.label, target.label.startsWith("${i + 1}. "))
        }
        assertEquals(COLLECTOR, CALIBRATION_TARGETS.last().name)
    }

    @Test
    fun `no description asks for a tap`() {
        val tap = Regex("\\btap", RegexOption.IGNORE_CASE)
        CALIBRATION_TARGETS.forEach { assertFalse(it.description, tap.containsMatchIn(it.description)) }
    }

    @Test
    fun `every target but the collector changes the profile`() {
        val profile = PhoneProfile.createDefault()
        CALIBRATION_TARGETS.filter { it.name != COLLECTOR }.forEach { target ->
            assertNotEquals(target.name, profile, applyTarget(profile, target.name, Point(700, 300)))
        }
    }

    @Test
    fun `the collector and unknown names leave the profile alone`() {
        val profile = PhoneProfile.createDefault()
        assertSame(profile, applyTarget(profile, COLLECTOR, Point(1, 2)))
        assertSame(profile, applyTarget(profile, "nothing", Point(1, 2)))
    }

    @Test
    fun `the viewport corners move one at a time and clear verification`() {
        val start = PhoneProfile.createDefault(1920, 1080).copy(isVerified = true)
        val tl = applyTarget(start, "vp_tl", Point(10, 20))
        assertEquals(Viewport(10, 20, 1920, 1080), tl.viewport)
        assertFalse(tl.isVerified)
        val br = applyTarget(tl, "vp_br", Point(1900, 1070))
        assertEquals(Viewport(10, 20, 1900, 1070), br.viewport)
    }

    @Test
    fun `a perimeter end alone fills the edges before it and keeps its other end`() {
        val end = Point(300, 400)
        val first = applyTarget(PhoneProfile.createDefault(), "perim_2_end", end)
        assertEquals(listOf(Point(0, 0) to Point(0, 0), end to end), first.perimeterSegments)
        val second = applyTarget(first, "perim_2_start", Point(100, 200))
        assertEquals(Point(100, 200) to end, second.perimeterSegments[1])
    }

    @Test
    fun `a wall button sets its cost box once and then moves alone`() {
        val first = applyTarget(PhoneProfile.createDefault(), "gold_cost_btn", Point(500, 300))
        val gold = first.wallConfig!!.currencies.getValue("gold")
        assertEquals(Region(450, 260, 550, 290), gold.costRoi)
        assertEquals(Point(500, 300), gold.buttonPoint)
        val moved = applyTarget(first, "gold_cost_btn", Point(520, 310)).wallConfig!!.currencies.getValue("gold")
        assertEquals(Region(450, 260, 550, 290), moved.costRoi)
        assertEquals(Point(520, 310), moved.buttonPoint)
    }

    @Test
    fun `the next price box ends at the click`() {
        val profile = applyTarget(PhoneProfile.createDefault(), "scout_fee", Point(1000, 200))
        assertEquals(Region(916, 185, 1004, 215), profile.scoutFeeRoi)
    }

    @Test
    fun `on a PC frame Apply draws the PC's boxes`() {
        val pc = PhoneProfile.createDefault(1740, 978)
        assertEquals(Region(75, 99, 243, 133), applyTarget(pc, "scout_gold", Point(80, 116)).scoutLootRois[Resource.GOLD])
        assertEquals(Region(814, 42, 928, 80), applyTarget(pc, "scout_countdown", Point(871, 60)).scoutCountdownRoi)
        assertEquals(Region(1580, 749, 1680, 783), applyTarget(pc, "scout_fee", Point(1675, 766)).scoutFeeRoi)
        assertEquals(Region(1493, 22, 1713, 62), applyTarget(pc, "home_gold", Point(1603, 42)).homeResourceRois[Resource.GOLD])
        assertEquals(Region(450, 260, 550, 290), applyTarget(pc, "gold_cost_btn", Point(500, 300)).wallConfig!!.currencies.getValue("gold").costRoi)
        assertEquals(Region(270, 846, 345, 962), applyTarget(pc, "valkyries", Point(307, 903)).troopRois["valkyries"])
        // A card in the corner stops at the frame's edges, as on the phone.
        assertEquals(Region(1693, 913, 1740, 978), applyTarget(pc, "jumps", Point(1730, 970)).troopRois["jumps"])
    }

    @Test
    fun `the PC's boxes scale with the frame's height`() {
        val tall = PhoneProfile.createDefault(1860, 1162)
        assertEquals(Region(94, 120, 294, 160), applyTarget(tall, "scout_gold", Point(100, 140)).scoutLootRois[Resource.GOLD])
        assertEquals(Region(932, 49, 1068, 94), applyTarget(tall, "scout_countdown", Point(1000, 70)).scoutCountdownRoi)
        assertEquals(Region(887, 180, 1006, 220), applyTarget(tall, "scout_fee", Point(1000, 200)).scoutFeeRoi)
        assertEquals(Region(1569, 26, 1831, 74), applyTarget(tall, "home_gold", Point(1700, 50)).homeResourceRois[Resource.GOLD])
        assertEquals(Region(441, 252, 559, 288), applyTarget(tall, "gold_cost_btn", Point(500, 300)).wallConfig!!.currencies.getValue("gold").costRoi)
        assertEquals(Region(321, 1005, 410, 1143), applyTarget(tall, "valkyries", Point(365, 1073)).troopRois["valkyries"])
        // 1565x978, the 16:10 frame sized down, is as high as 1740x978 and keeps its boxes.
        assertEquals(Region(1405, 749, 1505, 783), applyTarget(PhoneProfile.createDefault(1565, 978), "scout_fee", Point(1500, 766)).scoutFeeRoi)
    }

    @Test
    fun `on the PC's frames each box holds its own number and no other text`() {
        // Each click is where the target's description says: on a loot row's first digit, in the
        // middle of the seconds and of each balance, and just left of the coin under Next.
        val (scout, scoutLines) = frame("geo-scout")
        assertHolds(scoutLines, "1 193 372", applyTarget(scout, "scout_gold", Point(80, 116)).scoutLootRois[Resource.GOLD])
        assertHolds(scoutLines, "884 775", applyTarget(scout, "scout_elixir", Point(83, 158)).scoutLootRois[Resource.ELIXIR])
        assertHolds(scoutLines, "4 437", applyTarget(scout, "scout_dark", Point(83, 202)).scoutLootRois[Resource.DARK_ELIXIR])
        assertHolds(scoutLines, "265", applyTarget(scout, "scout_countdown", Point(871, 59)).scoutCountdownRoi)
        assertHolds(scoutLines, "1700", applyTarget(scout, "scout_fee", Point(1675, 766)).scoutFeeRoi)
        // The badge band AttackPlanner reads a card's count from hangs off the card area's top.
        val card = applyTarget(scout, "valkyries", Point(307, 903)).troopRois.getValue("valkyries")
        assertHolds(scoutLines, "x44", AttackPlanner.badgeBand(card))
        val (home, homeLines) = frame("geo-home")
        assertHolds(homeLines, "30 800 805", applyTarget(home, "home_gold", Point(1595, 42)).homeResourceRois[Resource.GOLD])
        assertHolds(homeLines, "9 693 255", applyTarget(home, "home_elixir", Point(1608, 98)).homeResourceRois[Resource.ELIXIR])
        assertHolds(homeLines, "167 280", applyTarget(home, "home_dark", Point(1618, 154)).homeResourceRois[Resource.DARK_ELIXIR])
        val (tall, tallLines) = frame("live-1860-home")
        assertHolds(tallLines, "30 799 105", applyTarget(tall, "home_gold", Point(1696, 50)).homeResourceRois[Resource.GOLD])
        assertHolds(tallLines, "9 693 255", applyTarget(tall, "home_elixir", Point(1705, 115)).homeResourceRois[Resource.ELIXIR])
        assertHolds(tallLines, "167 280", applyTarget(tall, "home_dark", Point(1719, 181)).homeResourceRois[Resource.DARK_ELIXIR])
    }

    /** A default profile at the size of the PC frame [name] in pc-frames, and its OCR lines. */
    private fun frame(name: String): Pair<PhoneProfile, List<Pair<String, Region>>> {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText())
        val lines = json.getJSONArray("lines")
        return PhoneProfile.createDefault(json.getInt("width"), json.getInt("height")) to (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            line.getString("text") to Region(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    /** That [box] holds the middle of the line reading [text] and of no other line. */
    private fun assertHolds(lines: List<Pair<String, Region>>, text: String, box: Region?) {
        assertNotNull(text, box)
        assertTrue(text, lines.any { it.first == text })
        for ((other, at) in lines) {
            val middle = Point((at.left + at.right) / 2, (at.top + at.bottom) / 2)
            assertEquals("$text's box $box against '$other'", other == text, box!!.contains(middle))
        }
    }

    @Test
    fun `a frame fits the view and centres in the spare height`() {
        val fit = FrameFit(960f, 700f, 1920, 1080)
        assertEquals(0.5f, fit.scale, 0f)
        assertEquals(0f, fit.left, 0f)
        assertEquals(80f, fit.top, 0f)
        assertEquals(Point(960, 540), fit.toImage(480f, 350f))
        assertEquals(480f, fit.x(960), 0f)
        assertEquals(350f, fit.y(540), 0f)
    }

    @Test
    fun `a click off the frame picks nothing and the far edges clamp`() {
        val fit = FrameFit(1200f, 540f, 1920, 1080)
        assertEquals(120f, fit.left, 0f)
        assertNull(fit.toImage(119f, 100f))
        assertNull(fit.toImage(500f, 541f))
        assertEquals(Point(0, 0), fit.toImage(120f, 0f))
        assertEquals(Point(1919, 1079), fit.toImage(1080f, 540f))
    }

    @Test
    fun `a 16 by 10 frame fits by its own shape`() {
        // Google Play Games came back at 1860x1162 after a relaunch; the fit must not assume 16:9.
        val tall = FrameFit(930f, 760f, 1860, 1162)
        assertEquals(0.5f, tall.scale, 0f)
        assertEquals(89.5f, tall.top, 0f)
        assertEquals(Point(930, 581), tall.toImage(465f, 380f))
        val wide = FrameFit(2000f, 581f, 1860, 1162)
        assertEquals(535f, wide.left, 0f)
        assertNull(wide.toImage(534f, 10f))
        assertEquals(Point(1859, 1161), wide.toImage(1465f, 581f))
    }
}
