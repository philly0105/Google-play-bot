package local.clashfarmer.desktop

import local.clashfarmer.core.RatePrompt
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.classifyScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Play's "Rate Clash of Clans" over Blood's village, which came up as the supervised attack
 * of 24 October returned home. live-1740-rate.json is the sidecar's OCR of
 * audit-evidence/live/20261024-163618-look/frame.png, copied byte for byte.
 */
class PcRatePromptTest {

    private val pc = Viewport(0, 0, 1740, 978)

    private fun frame(name: String): List<RecognizedBox> {
        val json = JSONObject(
            PcRatePromptTest::class.java.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText()
        )
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    @Test
    fun `the dialog reads as the village and LATER is its tap`() {
        val boxes = frame("live-1740-rate")
        assertEquals(ScreenState.HOME, classifyScreen(boxes, pc))
        val later = RatePrompt.later(boxes)!!
        assertTrue("$later", Region(519, 531, 579, 558).contains(later))
    }

    @Test
    fun `no other PC frame is the dialog`() {
        // The village, the quit prompt over it, the idle dialog, the attack menu and a scout.
        for (name in listOf("geo-home", "geo-after-deselect", "live-1740-idle", "geo-menu", "geo-scout", "live-1860-home")) {
            assertNull(name, RatePrompt.later(frame(name)))
        }
    }

    @Test
    fun `without LATER read below its words nothing is tapped`() {
        val boxes = frame("live-1740-rate")
        assertNull(RatePrompt.later(boxes.filter { it.text != "LATER" }))
        assertNull(RatePrompt.later(boxes.map { if (it.text == "LATER") it.copy(top = 400, bottom = 420) else it }))
    }
}
