package local.clashfarmer.desktop

import local.clashfarmer.core.Point
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.classifyScreen
import local.clashfarmer.recognition.returnHomeTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * A defence replay's end screen at 1860x1162 (audit-evidence/live/20261024-033349-look, the
 * attacker's name replaced). Its Return Home sits at the bottom left, not where a battle's result
 * screen draws it.
 */
class PcReplayTest {

    private val wide = Viewport(0, 0, 1860, 1162)
    private val phone = Viewport(0, 0, 1600, 720)

    /** The calibrated Return Home of a 1600x720 profile moved onto 1860x1162: bare village here. */
    private val calibrated = Point(930, 1008)

    private fun replay(): List<RecognizedBox> {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/live-1860-replay.json")!!.bufferedReader().readText())
        assertEquals(1860, json.getInt("width"))
        assertEquals(1162, json.getInt("height"))
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    private fun isButton(box: RecognizedBox) = box.text == "RetuRN" || box.text == "HoMe"

    @Test
    fun `a replay's end screen goes home from its own corner`() {
        val boxes = replay()
        assertEquals(ScreenState.RESULT, classifyScreen(boxes, wide))
        for (anchor in listOf(calibrated, null)) {
            val tap = returnHomeTarget(boxes, wide, anchor)!!
            assertTrue("$tap", tap.x in 40..190 && tap.y in 1053..1131)
        }
    }

    @Test
    fun `half of the corner's words is not the replay's button`() {
        val boxes = replay()
        // Only Return read: the calibrated point, as before.
        assertEquals(calibrated, returnHomeTarget(boxes.filterNot { it.text == "HoMe" }, wide, calibrated))
        assertNull(returnHomeTarget(boxes.filterNot { it.text == "HoMe" }, wide, null))
    }

    @Test
    fun `a name that says Return is still no button`() {
        val named = replay().filterNot(::isButton).map { if (it.text == "Player") it.copy(text = "RETURN HOME") else it }
        assertNull(returnHomeTarget(named, wide, null))
    }

    @Test
    fun `matchmaking's Return Home in the same corner is left alone`() {
        val search = replay().filter(::isButton) +
            RecognizedBox("Searching for opponents", 700, 560, 1160, 600)
        assertNull(returnHomeTarget(search, wide, calibrated))
    }

    @Test
    fun `the phone keeps its calibrated point`() {
        val boxes = replay().map {
            fun x(v: Int) = (v * 1600.0 / 1860).roundToInt()
            fun y(v: Int) = (v * 720.0 / 1162).roundToInt()
            RecognizedBox(it.text, x(it.left), y(it.top), x(it.right), y(it.bottom))
        }
        assertEquals(Point(800, 624), returnHomeTarget(boxes, phone, Point(800, 624)))
    }
}
