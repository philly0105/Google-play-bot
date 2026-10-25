package local.clashfarmer.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.BuilderCount
import local.clashfarmer.core.Builders
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.ocr.OcrBackends
import local.clashfarmer.desktop.ocr.OcrLine
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.ScreenReader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builders' counter read again from an upscaled crop on a PC village frame. The whole frame
 * reaches the recogniser shrunk, and the 28 px counter rarely reads. The corner-* fixtures are what
 * the sidecar's recogniser, run offline on saved captures, read from the x3 crop of the counters'
 * line; the fake backend here hands each fixture's lines back for the image of its size.
 */
class PcBuildersCornerTest {

    private val pc = Viewport(0, 0, 1740, 978)
    private val wide = Viewport(0, 0, 1860, 1162)
    private val resized = Viewport(0, 0, 1565, 978)
    private val phone = Viewport(0, 0, 1600, 720)

    /** The sizes of the images the recogniser was handed, in order. */
    private val calls: MutableList<Pair<Int, Int>> = java.util.Collections.synchronizedList(mutableListOf())

    private class Fixture(val width: Int, val height: Int, val region: Region?, val lines: List<OcrLine>)

    private fun fixture(name: String): Fixture {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText())
        val lines = json.getJSONArray("lines")
        val region = json.optJSONArray("region")?.let { Region(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
        return Fixture(json.getInt("width"), json.getInt("height"), region, (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            OcrLine(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        })
    }

    /** Installs a recogniser that reads [full] for the whole frame and [crop] for the corner's crop. */
    private fun install(full: Fixture, crop: Fixture?) = OcrBackends.install { width, height, _ ->
        calls += width to height
        when {
            width == full.width && height == full.height -> full.lines
            crop != null && width == crop.width && height == crop.height -> crop.lines
            else -> error("unexpected image $width x $height")
        }
    }

    @After
    fun uninstallOcr() = OcrBackends.install(null)

    private fun observe(full: Fixture): Observation = runBlocking {
        val blank = Bitmap.createBitmap(IntArray(full.width * full.height) { 0xFF404040.toInt() }, full.width, full.height, Bitmap.Config.ARGB_8888)
        ScreenReader().use { it.observe(blank) }
    }

    private fun Region.holds(box: RecognizedBox) = box.center.x in left..right && box.center.y in top..bottom

    @Test
    fun `the crop holds the counters at both sizes and the resized frame, and is none on the phone`() {
        assertEquals(Region(1557, 744, 1735, 828), Builders.pcCornerRegion(pc))
        assertEquals(Region(1643, 884, 1854, 984), Builders.pcCornerRegion(wide))
        assertTrue(Builders.pcCornerRegion(pc)!!.holds(RecognizedBox("0/6", 1576, 774, 1642, 804)))
        assertTrue(Builders.pcCornerRegion(wide)!!.holds(RecognizedBox("0/6", 1667, 920, 1746, 954)))
        assertTrue(Builders.pcCornerRegion(resized)!!.holds(RecognizedBox("0/6", 1400, 774, 1466, 804)))
        assertNull(Builders.pcCornerRegion(phone))
        // The fixtures were cut from the same regions.
        assertEquals(Builders.pcCornerRegion(pc), fixture("corner-1740-village").region)
        assertEquals(Builders.pcCornerRegion(wide), fixture("corner-1860-village").region)
    }

    @Test
    fun `a village frame that missed the counter reads it from the crop at 1740x978`() {
        val full = fixture("geo-home-3")
        val crop = fixture("corner-1740-village")
        install(full, crop)
        val obs = observe(full)
        assertEquals(ScreenState.HOME, obs.state)
        assertEquals(listOf(1740 to 978, crop.width to crop.height), calls)
        assertEquals(BuilderCount(0, 6), Builders.read(obs.boxes, pc))
        // The rest of the frame is left as it read, and so is its text.
        val outside = full.lines.map { RecognizedBox(it.text, it.left, it.top, it.right, it.bottom) }.filterNot { Builders.inCorner(it, pc) }
        assertTrue(obs.boxes.containsAll(outside))
        assertEquals(outside.size + 2, obs.boxes.size)
        assertFalse(obs.rawText.contains("0/6"))
    }

    @Test
    fun `a village frame that missed the counter reads it from the crop at 1860x1162`() {
        val full = fixture("live-1860-village")
        install(full, fixture("corner-1860-village"))
        val obs = observe(full)
        assertEquals(ScreenState.HOME, obs.state)
        assertEquals(2, calls.size)
        assertEquals(BuilderCount(0, 6), Builders.read(obs.boxes, wide))
    }

    @Test
    fun `a part of the counter read off the whole frame gives way to the crop's`() {
        val village = fixture("live-1860-village")
        val full = Fixture(village.width, village.height, null, village.lines + OcrLine("0/", 1662, 918, 1720, 961))
        install(full, fixture("corner-1860-dragged"))
        val obs = observe(full)
        assertEquals(BuilderCount(0, 6), Builders.read(obs.boxes, wide))
        assertTrue(obs.boxes.none { it.text == "0/" })
    }

    @Test
    fun `a free builder reads from the crop`() {
        install(fixture("geo-home-3"), fixture("corner-1740-one-free"))
        assertEquals(BuilderCount(1, 6), Builders.read(observe(fixture("geo-home-3")).boxes, pc))
    }

    @Test
    fun `a crop that misreads leaves the frame as it read`() {
        val full = fixture("geo-home-3")
        install(full, fixture("corner-1740-misread"))
        val obs = observe(full)
        assertEquals(2, calls.size)
        assertEquals(full.lines.map { it.text }, obs.boxes.map { it.text })
        assertNull(Builders.read(obs.boxes, pc))
    }

    @Test
    fun `a frame that read the counter, or shows no village, takes no second read`() {
        val home = fixture("geo-home")
        install(home, null)
        assertEquals(BuilderCount(0, 6), Builders.read(observe(home).boxes, pc))
        assertEquals(1, calls.size)

        calls.clear()
        val list = fixture("geo-list-top")
        install(list, null)
        assertEquals(ScreenState.UNKNOWN, observe(list).state)
        assertEquals(1, calls.size)
    }
}
