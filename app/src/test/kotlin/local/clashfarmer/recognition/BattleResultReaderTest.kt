package local.clashfarmer.recognition

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry and values are the result screens of 21 and 22 September at 1600x720. */
class BattleResultReaderTest {

    private val viewport = Viewport(0, 0, 1600, 720)
    private fun box(text: String, l: Int, t: Int, r: Int, b: Int) = RecognizedBox(text, l, t, r, b)

    private val youGot = box("You got:", 773, 290, 833, 305)
    private val totalDamage = box("Total damage:", 755, 83, 850, 98)

    @Test fun theLootRowsHangUnderYouGot() {
        val rows = BattleResultReader.lootRegions(listOf(youGot), viewport)!!
        assertEquals(Region(653, 315, 834, 349), rows[Resource.GOLD])
        assertEquals(Region(653, 362, 834, 396), rows[Resource.ELIXIR])
        assertEquals(Region(653, 410, 834, 444), rows[Resource.DARK_ELIXIR])
    }

    /**
     * On 15 October the phone's box for "You got:" sat 22 px further left on some frames, and rows
     * hung from it cut the last digit; on the others they cut the leading 1 of "1 650 606". The
     * sides now stay where the digits are, right of the panel's edge (x 652) and left of the icons
     * (from 839), and only the height follows the words.
     */
    @Test fun theRowsKeepTheirSidesWhereverThePhonePutsYouGot() {
        for (box in listOf(box("You got:", 751, 290, 809, 305), box("You got:", 773, 292, 833, 307))) {
            val rows = BattleResultReader.lootRegions(listOf(box), viewport)!!
            for (row in rows.values) {
                assertEquals(653, row.left)
                assertEquals(834, row.right)
            }
            assertEquals(box.center.y + 18, rows[Resource.GOLD]!!.top)
        }
        // On a wider screen the rows stay on the middle of it.
        val wide = Viewport(100, 0, 1800, 720)
        val rows = BattleResultReader.lootRegions(listOf(box("You got:", 923, 290, 983, 305)), wide)!!
        assertEquals(Region(803, 315, 984, 349), rows[Resource.GOLD])
    }

    @Test fun theDamageHangsUnderTotalDamage() {
        assertEquals(Region(757, 103, 847, 133), BattleResultReader.damageRegion(listOf(totalDamage), viewport))
    }

    /** Spellings from the result frames of 7 and 8 October. */
    @Test fun theWordsAreMatchedWithoutTheirSpaces() {
        val rows = BattleResultReader.lootRegions(listOf(box("Yougot:", 773, 290, 833, 305)), viewport)!!
        assertEquals(Region(653, 315, 834, 349), rows[Resource.GOLD])
        for (text in listOf("Total danMage", "Tocal daage:", "Toal danmage:", "Total dauaget", "Tolcal damago:")) {
            assertEquals(text, Region(757, 103, 847, 133),
                BattleResultReader.damageRegion(listOf(box(text, 755, 83, 850, 98)), viewport))
        }
        assertNull(BattleResultReader.damageRegion(listOf(box("Troops expended:", 755, 83, 850, 98)), viewport))
    }

    @Test fun withoutItsWordsNothingIsRead() {
        assertNull(BattleResultReader.lootRegions(listOf(totalDamage), viewport))
        assertNull(BattleResultReader.damageRegion(listOf(youGot), viewport))
        // A defender named "You got" at the top of the screen is not the loot panel.
        assertNull(BattleResultReader.lootRegions(listOf(box("You got", 20, 20, 90, 35)), viewport))
    }

    @Test fun damageIsAPercentTwoCutsAgreeOn() {
        assertEquals(62, BattleResultReader.damagePercent(listOf("62%", "62 %", "62%")))
        assertEquals(100, BattleResultReader.damagePercent(listOf("100%", "100%")))
        assertEquals(0, BattleResultReader.damagePercent(listOf("0%", "0%")))
        assertNull(BattleResultReader.damagePercent(listOf("62%")))
        assertNull(BattleResultReader.damagePercent(listOf("62%", "63%")))
        assertNull(BattleResultReader.damagePercent(listOf("101%", "101%")))
        // 06:49 on 24 September: the phone read 59% as "S9%" twice, "59%" once and "%6S" once.
        assertEquals(59, BattleResultReader.damagePercent(listOf("S9%", "S9%", "59%", "%6S")))
        // Without its percent sign a number is not a damage reading.
        assertNull(BattleResultReader.damagePercent(listOf("62", "62")))
    }

    @Test fun aValueStillCountingUpIsNotSettled() {
        val counting = listOf(
            BattleReading(mapOf(Resource.GOLD to 120_000L), 40),
            BattleReading(mapOf(Resource.GOLD to 310_000L), 62),
            BattleReading(mapOf(Resource.GOLD to 398_073L, Resource.ELIXIR to 610_474L), 62)
        )
        assertEquals(BattleReading(emptyMap(), 62), BattleResultReader.settled(counting))
        assertEquals(
            BattleReading(mapOf(Resource.GOLD to 398_073L, Resource.ELIXIR to 610_474L), 62),
            BattleResultReader.settled(counting + counting.last())
        )
    }

    /** The four frames of 24 September 10:42: the third lost the "You got" anchor. */
    @Test fun aFrameThatMissedTheRowsDoesNotUnsettleTheOthers() {
        val loot = mapOf(Resource.GOLD to 448_577L, Resource.ELIXIR to 382_611L, Resource.DARK_ELIXIR to 5_119L)
        val frames = listOf(
            BattleReading(mapOf(Resource.GOLD to 44_857L, Resource.ELIXIR to 3_826L, Resource.DARK_ELIXIR to 511L), null),
            BattleReading(loot, null),
            BattleReading(emptyMap(), null),
            BattleReading(loot, null)
        )
        assertEquals(BattleReading(loot, null), BattleResultReader.settled(frames))
    }

    @Test fun twoReadingsApartThatDifferStayUnknown() {
        val frames = listOf(
            BattleReading(mapOf(Resource.GOLD to 300_000L), 50),
            BattleReading(emptyMap(), null),
            BattleReading(mapOf(Resource.GOLD to 448_577L), 62)
        )
        assertEquals(BattleReading(emptyMap(), null), BattleResultReader.settled(frames))
    }

    @Test fun oneFrameSettlesNothing() {
        assertEquals(BattleReading(emptyMap(), null),
            BattleResultReader.settled(listOf(BattleReading(mapOf(Resource.GOLD to 1L), 62))))
    }

    /**
     * Crops of real result frames at 1600x720, 250x120 from (680, 80), as raw RGB bytes, so the
     * "Total damage" anchor sits where the phone's reading puts it, (802, 90). They come from
     * audit-evidence/2026-10-07-pass-supervised-sweat-fixed-cap1.png, 2026-09-24-t5-04-result.png,
     * 2026-09-24-t6-b-result.png and run-evidence/result-screen.png. The last has this app's
     * dimmed Stop button over the top of the left star.
     */
    private class Frame(name: String, val left: Int = 680, val top: Int = 80, val width: Int = 250, val height: Int = 120) {
        private val rgb = BattleResultReaderTest::class.java.getResourceAsStream("/result-frames/$name.rgb")!!
            .use { it.readBytes() }
        fun pixel(x: Int, y: Int): Int? {
            val cx = x - left
            val cy = y - top
            if (cx !in 0 until width || cy !in 0 until height) return null
            val i = (cy * width + cx) * 3
            return (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        }
        fun region(r: Region): List<Triple<Int, Int, Int>> =
            (r.top until r.bottom).flatMap { y -> (r.left until r.right).map { x -> Triple(x, y, pixel(x, y)!!) } }
    }

    private val frames = mapOf(
        "two-stars-99" to 2, "one-star-79" to 1, "three-stars-100" to 3, "one-star-84-overlay" to 1
    )
    private val damageRoi = Region(757, 103, 847, 133)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Colours sampled from the frames: digit fill, white earned star, its shaded edge, olive silhouette. */
    @Test fun onlyTheGreenDigitsAreInk() {
        for (ink in BattleResultReader.DAMAGE_INKS) {
            assertTrue(ink.matches(rgb(136, 200, 89)))
            assertFalse(ink.matches(rgb(224, 221, 238)))
            assertFalse(ink.matches(rgb(184, 194, 216)))
            assertFalse(ink.matches(rgb(127, 118, 70)))
            assertFalse(ink.matches(rgb(255, 255, 255)))
            assertFalse(ink.matches(rgb(0, 0, 0)))
        }
    }

    /**
     * Every luminance cut lost 99% on the white star of 8 October. The green mask keeps the digits,
     * and only the digits, on a white star, a dark one and both 100% frames.
     */
    @Test fun theGreenMaskFindsTheDigitsOnAnyStar() {
        for (name in frames.keys) {
            val frame = Frame(name)
            for (ink in BattleResultReader.DAMAGE_INKS) {
                val lit = frame.region(damageRoi).filter { ink.matches(it.third) }
                assertTrue("$name $ink ${lit.size}", lit.size in 400..1000)
                assertTrue("$name $ink", lit.all { it.first in 768..836 && it.second in 106..127 })
            }
        }
    }

    @Test fun theStarsHangFromTotalDamage() {
        assertEquals(listOf(Point(707, 175), Point(802, 140), Point(902, 160)),
            BattleResultReader.starCentres(listOf(totalDamage), viewport))
        assertNull(BattleResultReader.starCentres(listOf(youGot), viewport))
    }

    @Test fun earnedStarsAreCountedByColour() {
        for ((name, stars) in frames) {
            val frame = Frame(name)
            val centres = BattleResultReader.starCentres(listOf(totalDamage), viewport)!!
            assertEquals(name, stars, BattleResultReader.starCount(centres, frame::pixel))
            // The phone's reading of the anchor moves by a few pixels from frame to frame.
            for (dx in listOf(-6, 6)) for (dy in listOf(-6, 6)) {
                val moved = centres.map { Point(it.x + dx, it.y + dy) }
                assertEquals("$name $dx,$dy", stars, BattleResultReader.starCount(moved, frame::pixel))
            }
        }
    }

    /** Stars are earned left to right, so a lit star after an unlit one is not a reading. */
    @Test fun starsOutOfOrderAreNotACount() {
        val centres = BattleResultReader.starCentres(listOf(totalDamage), viewport)!!
        val white = rgb(224, 221, 238)
        val olive = rgb(127, 118, 70)
        fun only(vararg lit: Int): (Int, Int) -> Int? = { x, _ ->
            if (lit.any { kotlin.math.abs(x - centres[it].x) <= 20 }) white else olive
        }
        assertEquals(0, BattleResultReader.starCount(centres, only()))
        assertEquals(3, BattleResultReader.starCount(centres, only(0, 1, 2)))
        assertNull(BattleResultReader.starCount(centres, only(1)))
        assertNull(BattleResultReader.starCount(centres, only(0, 2)))
        // The gold ribbon is bright but saturated: not a silver star.
        assertEquals(0, BattleResultReader.starCount(centres) { _, _ -> rgb(240, 190, 40) })
        // Off the frame there is nothing to measure.
        assertNull(BattleResultReader.starCount(centres) { _, _ -> null })
    }

    @Test fun starsSettleLikeTheOtherValues() {
        val frames = listOf(
            BattleReading(emptyMap(), 99, stars = 1),
            BattleReading(emptyMap(), 99, stars = 2),
            BattleReading(emptyMap(), 99, stars = 2)
        )
        assertEquals(2, BattleResultReader.settled(frames).stars)
        assertNull(BattleResultReader.settled(frames.take(2)).stars)
    }

    /** One star is 50% or the town hall, two is both, three is 100%. */
    @Test fun starsCrossCheckTheDamage() {
        assertTrue(BattleResultReader.starsAgreeWithDamage(0, 49))
        assertFalse(BattleResultReader.starsAgreeWithDamage(0, 50))
        assertTrue(BattleResultReader.starsAgreeWithDamage(1, 12))
        assertTrue(BattleResultReader.starsAgreeWithDamage(1, 79))
        assertFalse(BattleResultReader.starsAgreeWithDamage(1, 100))
        assertFalse(BattleResultReader.starsAgreeWithDamage(2, 49))
        assertTrue(BattleResultReader.starsAgreeWithDamage(2, 99))
        assertFalse(BattleResultReader.starsAgreeWithDamage(2, 100))
        assertTrue(BattleResultReader.starsAgreeWithDamage(3, 100))
        assertFalse(BattleResultReader.starsAgreeWithDamage(3, 99))
    }

    /**
     * The "You got" rows of the ten result frames of 15 October 02:57 to 03:27, from
     * audit-evidence/f11/result-HHMMSS.png, cropped 190x150 from (650, 305) as raw RGB bytes. Their
     * "You got:" sits at x 774 to 831, y 293 to 304 on every frame. 03:08 read gold 1 650 606 as
     * 650 606 and 03:02 lost elixir 1 279 470 to "279 470". "victory" is the same crop of
     * androidTest's screen-RESULT-victory-read-as-search.png, whose "74" touch.
     */
    private val lootFrames = mapOf(
        "025714" to listOf(142_196L, 237_762L, 8_888L),
        "030254" to listOf(377_269L, 1_279_470L, 3_254L),
        "030412" to listOf(418_841L, 459_232L, 4_867L),
        "030810" to listOf(1_650_606L, 1_554_580L, 12_350L),
        "030958" to listOf(924_778L, 984_503L, 6_440L),
        "031919" to listOf(1_036_479L, 744_879L, 3_618L),
        "032107" to listOf(166_732L, 221_300L, 3_825L),
        "032324" to listOf(493_459L, 487_254L, 2_173L),
        "032556" to listOf(493_943L, 385_636L, 3_257L),
        "032750" to listOf(448_022L, 888_459L, 4_761L),
        "victory" to listOf(398_073L, 610_474L, 1_369L)
    )
    private fun lootFrame(name: String) = Frame("loot-$name", left = 650, top = 305, width = 190, height = 150)
    private val rowOrder = listOf(Resource.GOLD, Resource.ELIXIR, Resource.DARK_ELIXIR)

    @Test fun everyRowsInkIsAsManyGlyphsAsItHasDigits() {
        for ((name, loot) in lootFrames) {
            val frame = lootFrame(name)
            // The phone's box moves; the rows' height follows it.
            for (dy in -8..8) {
                val rows = BattleResultReader.lootRegions(listOf(box("You got:", 774, 293 + dy, 831, 304 + dy)), viewport)!!
                for ((resource, value) in rowOrder.zip(loot)) {
                    val glyphs = BattleResultReader.glyphCount(rows.getValue(resource), frame::pixel)
                    assertEquals("$name $resource $dy", value.toString().length, glyphs)
                    assertTrue("$name $resource $dy", BattleResultReader.inkAgrees(value, glyphs))
                }
            }
        }
    }

    @Test fun aReadThatLostADigitDoesNotAgreeWithTheInk() {
        val gold = BattleResultReader.lootRegions(listOf(youGot), viewport)!!.getValue(Resource.GOLD)
        val glyphs = BattleResultReader.glyphCount(gold, lootFrame("030810")::pixel)
        assertEquals(7, glyphs)
        assertFalse(BattleResultReader.inkAgrees(650_606L, glyphs))
        assertTrue(BattleResultReader.inkAgrees(1_650_606L, glyphs))
        // A read with a digit too many fails the same way, and nothing agrees with unknown ink.
        assertFalse(BattleResultReader.inkAgrees(16_506_060L, glyphs))
        assertFalse(BattleResultReader.inkAgrees(1_650_606L, null))
        assertTrue(BattleResultReader.inkAgrees(0L, 1))
    }

    /** Two digits whose ink touches are one run of columns, wider than any digit, and count as two. */
    @Test fun twoDigitsThatTouchAreTwoGlyphs() {
        val elixir = BattleResultReader.lootRegions(listOf(youGot), viewport)!!.getValue(Resource.ELIXIR)
        assertEquals(6, BattleResultReader.glyphCount(elixir, lootFrame("victory")::pixel))
        val white = rgb(240, 240, 240)
        // One 19 px digit is one glyph; 21 px of ink with no gap, a 1 touching a 14 px digit, is two.
        for ((width, glyphs) in listOf(19 to 1, 20 to 1, 21 to 2, 23 to 2)) {
            val count = BattleResultReader.glyphCount(Region(653, 340, 834, 374)) { x, y ->
                if (y in 345..365 && x in 790 until 790 + width) white else rgb(60, 60, 60)
            }
            assertEquals("$width px", glyphs, count)
        }
    }

    /** A row whose side runs through a digit cannot say how many it holds. */
    @Test fun inkAtTheSideOfARowIsNotCounted() {
        assertNull(BattleResultReader.glyphCount(Region(684, 316, 834, 350), lootFrame("030810")::pixel))
        // The row logged at 03:02 as "279 470": its side ran through the leading 1.
        assertNull(BattleResultReader.glyphCount(Region(692, 363, 833, 397), lootFrame("030254")::pixel))
        // A side through the last digit.
        assertNull(BattleResultReader.glyphCount(Region(653, 316, 815, 350), lootFrame("030810")::pixel))
        // No white digits at all, or no frame.
        assertNull(BattleResultReader.glyphCount(Region(653, 316, 834, 350)) { _, _ -> rgb(90, 110, 60) })
        assertNull(BattleResultReader.glyphCount(Region(653, 316, 834, 350)) { _, _ -> null })
    }

    /** The lines between the rows are white but one pixel high: they do not make a glyph. */
    @Test fun theLinesBetweenTheRowsAreNotInk() {
        val white = rgb(240, 240, 240)
        val glyphs = BattleResultReader.glyphCount(Region(653, 340, 834, 374)) { x, y ->
            if (y == 355 || x in 760..775) white else rgb(60, 60, 60)
        }
        assertEquals(1, glyphs)
    }
}
