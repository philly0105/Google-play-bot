package local.clashfarmer.desktop

import local.clashfarmer.core.RatePrompt
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.StarBonusNotice
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.classifyScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clash's "Star Bonus Received!" over Blood's village, left up when the third supervised attack of
 * 24 October returned home with the day's fifth star. live-1740-star-bonus.json is the sidecar's
 * OCR of audit-evidence/live/20261024-171712-look/frame.png, copied byte for byte.
 */
class PcStarBonusNoticeTest {

    private val pc = Viewport(0, 0, 1740, 978)

    private fun frame(name: String): List<RecognizedBox> {
        val json = JSONObject(
            PcStarBonusNoticeTest::class.java.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText()
        )
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    @Test
    fun `the notice reads as the village and Okay is its tap`() {
        val boxes = frame("live-1740-star-bonus")
        assertEquals(ScreenState.HOME, classifyScreen(boxes, pc))
        val okay = StarBonusNotice.okay(boxes)!!
        assertTrue("$okay", Region(820, 795, 936, 847).contains(okay))
        assertNull(RatePrompt.later(boxes))
    }

    @Test
    fun `no other PC frame is the notice`() {
        // The village, the quit prompt over it, the idle and rating dialogs, the attack menu, a
        // scout and a result.
        for (name in listOf("geo-home", "geo-after-deselect", "live-1740-idle", "live-1740-rate", "geo-menu",
                "geo-scout", "live-1740-result", "live-1860-home")) {
            assertNull(name, StarBonusNotice.okay(frame(name)))
        }
    }

    @Test
    fun `without Okay read below its words nothing is tapped`() {
        val boxes = frame("live-1740-star-bonus")
        assertNull(StarBonusNotice.okay(boxes.filter { it.text != "OkaY" }))
        assertNull(StarBonusNotice.okay(boxes.map { if (it.text == "OkaY") it.copy(top = 60, bottom = 80) else it }))
    }

    @Test
    fun `a notice that sells anything is not pressed`() {
        val boxes = frame("live-1740-star-bonus")
        assertNull(StarBonusNotice.okay(boxes + RecognizedBox("Finish now 25", 700, 700, 1000, 740)))
        assertNull(StarBonusNotice.okay(boxes + RecognizedBox("\$4.99", 700, 700, 800, 740)))
    }
}
