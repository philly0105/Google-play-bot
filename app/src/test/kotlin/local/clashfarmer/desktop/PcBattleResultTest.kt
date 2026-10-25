package local.clashfarmer.desktop

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.BattleResultReader
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result screen of Blood's second supervised attack of 24 October, at 1740x978: two stars,
 * 70%, and 1 346 371 gold, 474 889 elixir and 4 314 dark elixir. live-1740-result.json is the
 * sidecar's OCR of audit-evidence/live/20261024-165200-result/1792886045068.png, and the .rgb
 * files are raw RGB crops of that frame: the rows 270x220 from (660, 400), the stars 320x110
 * from (715, 160).
 *
 * live-1860-result.json is a result at 1860x1162 from 25 October, two stars, 72%, and 577 557
 * gold, 232 449 elixir and 2 039 dark elixir: the OCR of
 * audit-evidence/live/20261025-053311-result1610/1792931695135.png with the defender's name and
 * clan replaced. result-1860-stars.rgb is that frame's stars, 400x140 from (740, 180).
 *
 * result-1740-damage.rgb and result-1860-damage.rgb are the damage digits of those two frames,
 * 180x90 from (780, 120) and 180x110 from (840, 140).
 *
 * result-1860-rows.rgb is the rows of a result at 1860x1162 from 25 October, 320x230 from
 * (680, 495) of audit-evidence/live/20261025-065005-results1610/1792936962785.png: 1 212 550 gold,
 * 1 247 184 elixir and 12 497 dark elixir, with "You got:" read at (875, 462, 989, 502). Every
 * cut of four frames read the elixir as 247 184.
 */
class PcBattleResultTest {

    private val pc = Viewport(0, 0, 1740, 978)
    private val wide = Viewport(0, 0, 1860, 1162)

    private fun frame(name: String): List<RecognizedBox> {
        val json = JSONObject(
            PcBattleResultTest::class.java.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText()
        )
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    private val boxes = frame("live-1740-result")
    private val wideBoxes = frame("live-1860-result")

    private class Crop(name: String, val left: Int, val top: Int, val width: Int, val height: Int) {
        private val rgb = PcBattleResultTest::class.java.getResourceAsStream("/pc-frames/$name.rgb")!!
            .use { it.readBytes() }
        fun pixel(x: Int, y: Int): Int? {
            val cx = x - left
            val cy = y - top
            if (cx !in 0 until width || cy !in 0 until height) return null
            val i = (cy * width + cx) * 3
            return (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        }
    }

    private val rows = Crop("result-1740-rows", 660, 400, 270, 220)
    private val stars = Crop("result-1740-stars", 715, 160, 320, 110)
    private val wideStars = Crop("result-1860-stars", 740, 180, 400, 140)
    private val damage = Crop("result-1740-damage", 780, 120, 180, 90)
    private val wideDamage = Crop("result-1860-damage", 840, 140, 180, 110)
    private val wideRows = Crop("result-1860-rows", 680, 495, 320, 230)
    private val loot = listOf(Resource.GOLD to 1_346_371L, Resource.ELIXIR to 474_889L, Resource.DARK_ELIXIR to 4_314L)

    @Test
    fun `the rows hang under You got at the PC's lengths`() {
        val regions = BattleResultReader.lootRegions(boxes, pc)!!
        assertEquals(Region(670, 427, 916, 473), regions[Resource.GOLD])
        assertEquals(Region(670, 492, 916, 538), regions[Resource.ELIXIR])
        assertEquals(Region(670, 556, 916, 602), regions[Resource.DARK_ELIXIR])
    }

    @Test
    fun `every row's ink is as many glyphs as it has digits`() {
        val youGot = boxes.single { it.text == "You got:" }
        // The anchor as read, and moved 8 px each way.
        for (dy in -8..8) {
            val moved = boxes - youGot + youGot.copy(top = youGot.top + dy, bottom = youGot.bottom + dy)
            val regions = BattleResultReader.lootRegions(moved, pc)!!
            for ((resource, value) in loot) {
                val glyphs = BattleResultReader.glyphCount(regions.getValue(resource), pc, rows::pixel)
                assertEquals("$resource $dy", value.toString().length, glyphs)
                assertTrue("$resource $dy", BattleResultReader.inkAgrees(value, glyphs))
            }
        }
    }

    @Test
    fun `a leading 1 the reader dropped is put back when the ink shows it alone`() {
        val youGot = RecognizedBox("You got:", 875, 462, 989, 502)
        for (dy in -8..8) {
            val regions = BattleResultReader.lootRegions(listOf(youGot.copy(top = youGot.top + dy, bottom = youGot.bottom + dy)), wide)!!
            val gold = regions.getValue(Resource.GOLD)
            val elixir = regions.getValue(Resource.ELIXIR)
            val dark = regions.getValue(Resource.DARK_ELIXIR)
            assertFalse("$dy", BattleResultReader.inkAgrees(247_184, BattleResultReader.glyphCount(elixir, wide, wideRows::pixel)))
            assertEquals("$dy", 1_247_184L, BattleResultReader.withLeadingOne(247_184, elixir, wide, wideRows::pixel))
            assertEquals("$dy", 1_212_550L, BattleResultReader.withLeadingOne(212_550, gold, wide, wideRows::pixel))
            // Not a read that kept the 1 and lost a later digit, nor one whose 1s fall on wide glyphs.
            assertNull("$dy", BattleResultReader.withLeadingOne(124_718, elixir, wide, wideRows::pixel))
            assertNull("$dy", BattleResultReader.withLeadingOne(212_550, elixir, wide, wideRows::pixel))
            // Nor a 1 inside its group: 12 497 read short is left short.
            assertNull("$dy", BattleResultReader.withLeadingOne(2_497, dark, wide, wideRows::pixel))
            assertNull("$dy", BattleResultReader.withLeadingOne(1_247_184, elixir, wide, wideRows::pixel))
        }
        // At 1740x978 the 1 of 1 346 371 comes back too; 4 314 has no 1 to put back.
        val regions = BattleResultReader.lootRegions(boxes, pc)!!
        assertEquals(1_346_371L, BattleResultReader.withLeadingOne(346_371, regions.getValue(Resource.GOLD), pc, rows::pixel))
        assertNull(BattleResultReader.withLeadingOne(314, regions.getValue(Resource.DARK_ELIXIR), pc, rows::pixel))
        assertNull(BattleResultReader.withLeadingOne(74_889, regions.getValue(Resource.ELIXIR), pc, rows::pixel))
    }

    @Test
    fun `two earned stars are counted`() {
        val centres = BattleResultReader.starCentres(boxes, pc)!!
        assertEquals(listOf(Point(742, 237), Point(871, 190), Point(1007, 217)), centres)
        for (dx in listOf(-8, 0, 8)) for (dy in listOf(-8, 0, 8)) {
            val moved = centres.map { Point(it.x + dx, it.y + dy) }
            assertEquals("$dx,$dy", 2, BattleResultReader.starCount(moved, stars::pixel))
        }
        assertTrue(BattleResultReader.starsAgreeWithDamage(2, 70))
    }

    @Test
    fun `the damage region scales with the height`() {
        assertEquals(Region(810, 140, 932, 180), BattleResultReader.damageRegion(boxes, pc))
        assertEquals(Region(857, 167, 1001, 215), BattleResultReader.damageRegion(wideBoxes, wide))
    }

    @Test
    fun `the damage region holds all of the digits' ink`() {
        // The phone's lengths cut off the digits' lower part, which turned 59% into "c9%" live at
        // 1860x1162. The PC's box keeps every green pixel of 70% and 72% inside it with the label
        // read up to 4 px higher or lower.
        val ink = BattleResultReader.DAMAGE_INKS.last()
        for ((viewport, frame, crop) in listOf(Triple(pc, boxes, damage), Triple(wide, wideBoxes, wideDamage))) {
            val label = frame.single { it.text.startsWith("Total damage") }
            val pixels = (crop.left until crop.left + crop.width).flatMap { x ->
                (crop.top until crop.top + crop.height).map { y -> Point(x, y) }
            }.filter { crop.pixel(it.x, it.y)?.let(ink::matches) == true }
            assertTrue(pixels.size > 500)
            for (dy in -4..4) {
                val moved = frame - label + label.copy(top = label.top + dy, bottom = label.bottom + dy)
                val region = BattleResultReader.damageRegion(moved, viewport)!!
                val outside = pixels.filterNot { it.x in region.left until region.right && it.y in region.top until region.bottom }
                assertTrue("$viewport $dy: ${outside.size} outside $region", outside.isEmpty())
            }
        }
    }

    @Test
    fun `at 1860x1162 the rows and stars hold what they read`() {
        val regions = BattleResultReader.lootRegions(wideBoxes, wide)!!
        for ((resource, text) in listOf(Resource.GOLD to "577 557", Resource.ELIXIR to "232 449", Resource.DARK_ELIXIR to "2 039")) {
            val digits = wideBoxes.single { it.text == text }
            val region = regions.getValue(resource)
            assertTrue("$resource $region", region.left <= digits.left && region.right >= digits.right && region.contains(digits.center))
        }
        val centres = BattleResultReader.starCentres(wideBoxes, wide)!!
        for (dx in listOf(-8, 0, 8)) for (dy in listOf(-8, 0, 8)) {
            val moved = centres.map { Point(it.x + dx, it.y + dy) }
            assertEquals("$dx,$dy", 2, BattleResultReader.starCount(moved, wideStars::pixel))
        }
    }
}
