package local.clashfarmer.desktop.ui.calibration

import androidx.compose.ui.input.key.Key
import local.clashfarmer.core.AttackPlanner
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.StopException
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
    fun `the 38 targets keep the phone's names, grouped by game screen`() {
        assertEquals(38, CALIBRATION_TARGETS.size)
        assertEquals(38, CALIBRATION_TARGETS.map { it.name }.toSet().size)
        assertEquals(COLLECTOR, CALIBRATION_TARGETS.last().name)
        assertEquals(listOf("vp_tl", "vp_br"), CALIBRATION_TARGETS.take(2).map { it.name })
        // Each screen's targets sit together, so the list walks one screen at a time.
        val groups = CALIBRATION_TARGETS.map { it.group }
        val runs = listOf(groups.first()) + groups.zipWithNext().filter { (a, b) -> a != b }.map { it.second }
        assertEquals(groups.distinct(), runs)
        assertEquals(9, groups.distinct().size)
        // No number in front any more; the list's headers and checks do that job.
        CALIBRATION_TARGETS.forEach { assertFalse(it.label, it.label.first().isDigit()) }
    }

    @Test
    fun `labels and descriptions are plain, with no tap and no developer words`() {
        val banned = Regex("\\btap|\\bROI\\b|\\banchor|viewport|\\bphone\\b", RegexOption.IGNORE_CASE)
        CALIBRATION_TARGETS.forEach {
            assertFalse(it.description, banned.containsMatchIn(it.description))
            assertFalse(it.label, banned.containsMatchIn(it.label))
            // Sentence case: only the first word, and game names such as Attack!, start with a capital.
            assertTrue(it.label, it.label.first().isUpperCase())
        }
    }

    @Test
    fun `the army targets say what the attack does with them`() {
        fun label(name: String) = CALIBRATION_TARGETS.first { it.name == name }.label
        assertEquals("Main troop card (e.g. Valkyries)", label("valkyries"))
        assertEquals("Spell cast inside (e.g. Rage)", label("rages"))
        assertTrue(label("jumps").contains("optional"))
        listOf("king", "queen", "warden", "champion").forEach {
            assertTrue(label(it), label(it).endsWith("(skip if you don't have it)"))
        }
        assertTrue(CALIBRATION_TARGETS.first { it.name == "rages" }.description.contains("five times"))
    }

    @Test
    fun `the targets marked needed are exactly what an attack checks for`() {
        val required = CALIBRATION_TARGETS.filter { it.required }
        assertEquals(
            setOf("attack", "find_match", "return_home", "valkyries", "rages") +
                (1..4).flatMap { listOf("perim_${it}_start", "perim_${it}_end") },
            required.map { it.name }.toSet()
        )
        // All of them set on a PC profile passes the attack check; any one missing fails it.
        var full = PhoneProfile.createDefault(1740, 978)
        required.forEachIndexed { i, target -> full = applyTarget(full, target.name, Point(200 + i * 40, 150 + i * 30)) }
        full.validateForAttack()
        assertTrue(required.all { isSet(full, it.name) })
        listOf("attack", "valkyries").forEach { name ->
            var partial = PhoneProfile.createDefault(1740, 978)
            required.filter { it.name != name }.forEachIndexed { i, target ->
                partial = applyTarget(partial, target.name, Point(200 + i * 40, 150 + i * 30))
            }
            assertFalse(name, isSet(partial, name))
            assertTrue(name, runCatching { partial.validateForAttack() }.isFailure)
        }
    }

    @Test
    fun `a target counts as set once it has a value`() {
        val empty = PhoneProfile.createDefault(1740, 978)
        assertFalse(isSet(empty, "attack"))
        assertFalse(isSet(empty, "valkyries"))
        assertFalse(isSet(empty, "scout_gold"))
        assertFalse(isSet(empty, COLLECTOR))
        assertTrue(isSet(empty, "vp_tl"))
        assertEquals(0, COUNTED_TARGETS.count { isSet(empty, it.name) })

        val attack = applyTarget(empty, "attack", Point(90, 900))
        assertTrue(isSet(attack, "attack"))
        assertEquals(Point(90, 900), savedPoint(attack, "attack"))

        val gold = applyTarget(empty, "scout_gold", Point(80, 116))
        assertTrue(isSet(gold, "scout_gold"))
        assertNull(savedPoint(gold, "scout_gold"))
        assertEquals(Region(75, 99, 243, 133), savedBox(gold, "scout_gold"))

        val card = applyTarget(empty, "valkyries", Point(307, 903))
        assertEquals(Point(307, 903), savedPoint(card, "valkyries"))
        assertEquals(Region(270, 846, 345, 962), savedBox(card, "valkyries"))

        val button = applyTarget(empty, "gold_cost_btn", Point(500, 300))
        assertEquals(Point(500, 300), savedPoint(button, "gold_cost_btn"))
        assertFalse(isSet(button, "elixir_cost_btn"))
    }

    @Test
    fun `an edge's end counts only once the edge has two different ends`() {
        val one = applyTarget(PhoneProfile.createDefault(1740, 978), "perim_2_end", Point(300, 400))
        // Apply filled edge 1 with zeros and edge 2's start with the same point.
        assertFalse(isSet(one, "perim_1_start"))
        assertFalse(isSet(one, "perim_2_end"))
        assertFalse(isSet(one, "perim_2_start"))
        val both = applyTarget(one, "perim_2_start", Point(100, 200))
        assertTrue(isSet(both, "perim_2_start"))
        assertTrue(isSet(both, "perim_2_end"))
        assertEquals(Point(100, 200), savedPoint(both, "perim_2_start"))
        assertEquals(Point(300, 400), savedPoint(both, "perim_2_end"))
    }

    @Test
    fun `on a PC profile the game area's corners are set by capture, not picked`() {
        val pc = PhoneProfile.createDefault(1740, 978)
        assertFalse(targetEnabled(pc, "vp_tl"))
        assertFalse(targetEnabled(pc, "vp_br"))
        assertTrue(targetEnabled(pc, "attack"))
        val phone = PhoneProfile.createDefault()
        assertTrue(targetEnabled(phone, "vp_tl"))
        assertEquals(36, COUNTED_TARGETS.size)
        assertTrue(COUNTED_TARGETS.none { it.name in VIEWPORT_TARGETS })
    }

    @Test
    fun `the list puts one header over each game screen`() {
        val rows = targetRows()
        val headers = rows.filterIsInstance<TargetRow.Header>()
        assertEquals(CALIBRATION_TARGETS.map { it.group }.distinct(), headers.map { it.group })
        assertEquals(CALIBRATION_TARGETS.indices.toList(), rows.filterIsInstance<TargetRow.Item>().map { it.index })
        assertTrue(rows.first() is TargetRow.Header)
    }

    @Test
    fun `the arrows move the point 1 pixel, 10 with Shift, and Enter applies`() {
        fun nudge(key: Key, shift: Boolean) = (calibrationKey(key, shift) as CalibrationKey.Nudge).let { it.dx to it.dy }
        assertEquals(-1 to 0, nudge(Key.DirectionLeft, false))
        assertEquals(10 to 0, nudge(Key.DirectionRight, true))
        assertEquals(0 to -10, nudge(Key.DirectionUp, true))
        assertEquals(0 to 1, nudge(Key.DirectionDown, false))
        assertSame(CalibrationKey.Apply, calibrationKey(Key.Enter, false))
        assertSame(CalibrationKey.Apply, calibrationKey(Key.NumPadEnter, false))
        assertSame(CalibrationKey.Back, calibrationKey(Key.Escape, false))
        assertNull(calibrationKey(Key.A, false))
        assertNull(calibrationKey(Key.Tab, false))
    }

    @Test
    fun `a failed save says what to do in plain words`() {
        val outside = saveFailure(StopException("State anchor 'attack' at Point(x=2000, y=10) is outside viewport Viewport(0, 0, 1740, 978)"))
        assertEquals("Could not save: a point is outside the game area. Capture the game, check the points, then save again.", outside)
        assertEquals(outside, saveFailure(StopException("Viewport Viewport(0, 0, 9, 9) out of bounds for resolution 1x1")))
        assertEquals("Could not save: Blood is no longer on this PC.", saveFailure(StopException("Blood is no longer on this PC")))
        assertEquals("Could not save the calibration. Check that the disk has space, then try again.",
            saveFailure(java.io.IOException("Access is denied")))
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
