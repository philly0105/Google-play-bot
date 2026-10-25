package local.clashfarmer.desktop

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.BattleResultReader
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result screen of Blood's second supervised attack of 24 October, at 1740x978: two stars,
 * 70%, and 1 346 371 gold, 474 889 elixir and 4 314 dark elixir. live-1740-result.json is the
 * sidecar's OCR of audit-evidence/live/20261024-165200-result/1792886045068.png, and the .rgb
 * files are raw RGB crops of that frame: the rows 270x220 from (660, 400), the stars 320x110
 * from (715, 160).
 */
class PcBattleResultTest {

    private val pc = Viewport(0, 0, 1740, 978)

    private val boxes: List<RecognizedBox> = run {
        val json = JSONObject(
            PcBattleResultTest::class.java.getResourceAsStream("/pc-frames/live-1740-result.json")!!.bufferedReader().readText()
        )
        val lines = json.getJSONArray("lines")
        (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

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
    fun `two earned stars are counted`() {
        val centres = BattleResultReader.starCentres(boxes, pc)!!
        assertEquals(listOf(Point(742, 237), Point(871, 190), Point(1007, 217)), centres)
        for (dx in listOf(-8, 0, 8)) for (dy in listOf(-8, 0, 8)) {
            val moved = centres.map { Point(it.x + dx, it.y + dy) }
            assertEquals("$dx,$dy", 2, BattleResultReader.starCount(moved, stars::pixel))
        }
        assertTrue(BattleResultReader.starsAgreeWithDamage(2, 70))
    }
}
