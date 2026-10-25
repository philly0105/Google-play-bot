package local.clashfarmer.desktop

import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.UpgradeListReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder panel's cards on the PC captures (audit-evidence/spike/geo-list-*), where the
 * recogniser mangles the timers on rows in progress and on the cards' name lines.
 */
class PcUpgradeListTest {

    private val pc = Viewport(0, 0, 1740, 978)
    private val wide = Viewport(0, 0, 1860, 1162)
    private val phone = Viewport(0, 0, 1600, 720)

    private val white: (Region) -> IntArray = { r -> IntArray(maxOf(1, r.width * r.height)) { -1 } }

    private fun frame(name: String): List<RecognizedBox> {
        val json = JSONObject(javaClass.getResourceAsStream("/pc-frames/$name.json")!!.bufferedReader().readText())
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val box = line.getJSONArray("box")
            RecognizedBox(line.getString("text"), box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3))
        }
    }

    private fun rows(name: String) = UpgradeListReader.read(frame(name), pc, white).rows

    /** Every timer as the captures read it. */
    private val timers = listOf(
        "Id B8H �", "Id 17H ", "Sd 17H", "8H", "8H ", "10 9H", "4H �", "4H ", "Gd 9H", "Ed 9H",
        "4d0", "4d 0", "40M 0s ", "7H 30M", "11d 4H", "15H 42M", "1d 14H", "19H 10M"
    )

    /** Every price as the captures read it. */
    private val prices = listOf(
        "160 000", "3 600 000", "19 200 000", "32 000", "28 000", "24 000", "336 000", "320 000", "176 000",
        "60 000", "200 000", "4 800 000", "2 800 000"
    )

    @Test
    fun `a mangled timer is a timer on the PC and a price never is`() {
        for (timer in timers) {
            assertTrue(timer, UpgradeListReader.isTimer(timer, pc))
            assertTrue(timer, UpgradeListReader.isTimer(timer, wide))
        }
        for (price in prices) {
            assertFalse(price, UpgradeListReader.isTimer(price, pc))
        }
    }

    @Test
    fun `the phone keeps its own timers`() {
        assertFalse(UpgradeListReader.isTimer("Gd 9H", phone))
        assertFalse(UpgradeListReader.isTimer("4d0", phone))
        // A lone capital unit is an abbreviated price on the phone.
        assertFalse(UpgradeListReader.isTimer("5M", phone))
        assertTrue(UpgradeListReader.isTimer("5h 51m", phone))
    }

    @Test
    fun `a card with a mangled timer keeps the price on its second line`() {
        val scroll = rows("geo-list-scroll1")
        for ((card, price) in listOf(
            "Royal CHampION" to "336 000", "Barbarian King" to "320 000", "ARcher Queen" to "320 000",
            "Monolith" to "176 000", "ReveNge ToweR" to "368 000"
        )) {
            assertEquals(card, price, scroll.single { it.name == card }.priceText)
        }
        assertEquals("60 000", rows("geo-list-end").single { it.name == "Air Bomb" }.priceText)
        assertEquals("19 200 000", rows("geo-list-top").single { it.name.startsWith("BuildeR's Hut") }.priceText)
    }

    @Test
    fun `a card's name reads line by line and a bare number under it is left out`() {
        val end = rows("geo-list-end")
        assertEquals("200 000", end.single { it.name == "ElixiR CollectoR x 7" }.priceText)
        assertEquals("2 800 000", end.single { it.name == "CRafting StatioN Hero Hunter Poison EFFect" }.priceText)
        assertTrue(end.none { it.name.startsWith("983") })
        assertEquals("32 000", rows("geo-list-scroll1").single { it.name == "CRafting StatioN Hero Hunter Hitpoints" }.priceText)
        assertEquals("32 000", rows("geo-list-top").single { it.name == "CRafting StatioN Hero Hunter Hitpoints" }.priceText)
        // The tap stays on the name's own line.
        val collector = end.single { it.name == "ElixiR CollectoR x 7" }
        assertEquals(717, collector.top)
        assertEquals(753, collector.bottom)
    }

    @Test
    fun `no row takes a timer for its price`() {
        for (name in listOf("geo-list-top", "geo-list-scroll1", "geo-list-end")) {
            for (row in rows(name)) {
                val price = row.priceText ?: continue
                assertFalse("$name ${row.name} $price", UpgradeListReader.isTimer(price, pc))
            }
        }
        // The rows in progress at the top of the list have no price.
        assertTrue(rows("geo-list-top").filter { it.name == "ElixiR Storage" }.all { it.priceText == null })
    }
}
