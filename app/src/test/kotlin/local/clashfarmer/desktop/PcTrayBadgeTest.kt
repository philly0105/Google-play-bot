package local.clashfarmer.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.Region
import local.clashfarmer.desktop.ocr.OcrBackends
import local.clashfarmer.desktop.ocr.OcrLine
import local.clashfarmer.recognition.ScreenReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The tray's "xNN" badges on the PC, whose white Google Play Games draws at 220 to 229 (Blood's
 * 1740x978 scout of 24 October). The OCR here reads "x44" off any mask with ink in it, so each
 * reading is a cut that kept the badge.
 */
class PcTrayBadgeTest {

    private val band = Region(270, 597, 353, 622)

    /** A frame of card art at luminance 110 with a badge of [white] in [band]. */
    private fun frame(width: Int, height: Int, white: Int): Bitmap {
        val pixels = IntArray(width * height) { 0xFF6E6E6E.toInt() }
        for (y in band.top + 5 until band.bottom - 5) for (x in band.left + 10 until band.right - 10) {
            pixels[y * width + x] = (0xFF shl 24) or (white shl 16) or (white shl 8) or white
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    @Before
    fun installOcr() = OcrBackends.install { width, height, bgra ->
        val ink = (0 until width * height).any { bgra[it * 4].toInt() == 0 }
        if (ink) listOf(OcrLine("x44", 0, 0, width, height)) else emptyList()
    }

    @After
    fun uninstallOcr() = OcrBackends.install(null)

    @Test
    fun `a PC badge's white reads on every PC cut and on no phone cut`() = runBlocking {
        ScreenReader().use { reader ->
            assertEquals(List(4) { "x44" }, reader.readTrayBadge(frame(1740, 978, white = 224), band))
            assertEquals(emptyList<String>(), reader.readTrayBadge(frame(1600, 720, white = 224), band))
        }
    }

    @Test
    fun `the phone's badge white still reads on its own cuts`() = runBlocking {
        ScreenReader().use { reader ->
            assertEquals(List(4) { "x44" }, reader.readTrayBadge(frame(1600, 720, white = 255), band))
        }
    }
}
