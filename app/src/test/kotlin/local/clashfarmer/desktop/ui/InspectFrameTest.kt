package local.clashfarmer.desktop.ui

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.ocr.OcrBackends
import local.clashfarmer.desktop.ocr.OcrLine
import local.clashfarmer.recognition.PhoneProfile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Inspect on a 1860x1162 village with a 1740x978 calibration. The OCR hands back the lines of
 * `live-1860-village` that lie inside the image it is given, so a frame cut to its top-left
 * 1740x978 loses the Battle button; a balance mask with ink in it reads "29 303 105".
 */
class InspectFrameTest {

    private val calibrated = PhoneProfile.createDefault(1740, 978).copy(
        homeResourceRois = mapOf(Resource.GOLD to Region(1485, 22, 1705, 62)),
        isVerified = true
    )

    private val village: List<OcrLine> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/live-1860-village.json")!!.bufferedReader().readText())
        val lines = json.getJSONArray("lines")
        (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            OcrLine(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    /**
     * A dark 1860x1162 frame, white where the gold balance ends, x 1712 to 1780: inside the gold box
     * moved onto 16:10 and past the right edge of the box as saved.
     */
    private fun frame(): Bitmap {
        val pixels = IntArray(1860 * 1162) { 0xFF404040.toInt() }
        for (y in 30 until 70) for (x in 1712 until 1780) pixels[y * 1860 + x] = 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(pixels, 1860, 1162, Bitmap.Config.ARGB_8888)
    }

    @Before
    fun installOcr() = OcrBackends.install { width, height, bgra ->
        when {
            // The frame, or the part of it observe cut out.
            height >= 978 -> village.filter { it.right <= width && it.bottom <= height }
            (0 until width * height).any { bgra[it * 4].toInt() == 0 } -> listOf(OcrLine("29 303 105", 0, 0, width, height))
            else -> emptyList()
        }
    }

    @After
    fun uninstallOcr() = OcrBackends.install(null)

    @Test
    fun `a 16 by 10 village reads as the village, with the balances where Clash draws them`() = runBlocking {
        val (observation, balances) = inspectFrame(frame(), calibrated, 0L)
        assertEquals(ScreenState.HOME, observation.state)
        assertEquals(Viewport(0, 0, 1860, 1162), observation.viewport)
        assertEquals(listOf("gold=29303105"), balances)
    }
}
