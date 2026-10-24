package local.clashfarmer.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMatcherTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun scaled(pixels: IntArray, gain: Double) = IntArray(pixels.size) {
        val p = pixels[it]
        rgb(((p shr 16 and 0xFF) * gain).toInt(), ((p shr 8 and 0xFF) * gain).toInt(), ((p and 0xFF) * gain).toInt())
    }

    /** A blue banner with a white name across its middle, like the settings identity row. */
    private val banner = IntArray(40) { if (it in 12..27) rgb(232, 232, 224) else rgb(14, 75, 148) }
    /** The same banner with the name drawn somewhere else. */
    private val otherBanner = IntArray(40) { if (it in 0..15) rgb(232, 232, 224) else rgb(14, 75, 148) }

    /** Clash 18.600.7 drew the whole settings panel at about 0.92 of its old brightness. */
    @Test fun aDimmerCopyOfTheTemplateStillMatches() {
        val difference = ImageMatcher.gainCorrectedDifference(scaled(banner, 0.92), banner)!!
        assertTrue("difference $difference", difference < 1.0)
    }

    @Test fun anotherImageStillDiffers() {
        val difference = ImageMatcher.gainCorrectedDifference(scaled(otherBanner, 0.92), banner)!!
        assertTrue("difference $difference", difference > 8.0)
    }

    @Test fun aBlackFrameIsNotAnyTemplate() {
        val difference = ImageMatcher.gainCorrectedDifference(IntArray(40) { rgb(0, 0, 0) }, banner)!!
        assertTrue("difference $difference", difference > 8.0)
    }

    @Test fun anIdenticalImageIsZero() {
        assertEquals(0.0, ImageMatcher.gainCorrectedDifference(banner, banner)!!, 1e-9)
    }

    @Test fun differentSizesDoNotCompare() {
        assertNull(ImageMatcher.gainCorrectedDifference(IntArray(39), banner))
        assertNull(ImageMatcher.gainCorrectedDifference(IntArray(0), IntArray(0)))
    }

    /** Colours sampled from Blood's discounted gold confirm button on 30 September. */
    @Test fun onlyTheYellowPriceIsInk() {
        for (cut in listOf(60, 80, 100)) {
            assertTrue(ImageMatcher.isYellowInk(224, 224, 0, cut))
            assertFalse(ImageMatcher.isYellowInk(192, 224, 128, cut))
            assertFalse(ImageMatcher.isYellowInk(160, 192, 64, cut))
            assertFalse(ImageMatcher.isYellowInk(255, 255, 255, cut))
            assertFalse(ImageMatcher.isYellowInk(0, 0, 0, cut))
            assertFalse(ImageMatcher.isYellowInk(224, 32, 32, cut))
        }
    }
}
