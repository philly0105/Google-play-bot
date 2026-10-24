package local.clashfarmer.recognition

import local.clashfarmer.core.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroCardsTest {

    /** The hero cards' calibrated areas on Sweat and Blood, 1600x720. */
    private val cards = mapOf(
        "king" to Region(376, 600, 443, 702),
        "queen" to Region(473, 600, 540, 702),
        "warden" to Region(570, 600, 637, 702),
        "champion" to Region(667, 600, 733, 702)
    )

    /**
     * Crops of real frames at 1600x720, 374x46 from (368, 554): the strip above the four hero
     * cards, as raw RGB bytes. The landed ones are Sweat's attack of 14 October right after the
     * heroes dropped (a, the champion not yet up), after the abilities (b) and 4 s later (c),
     * audit-evidence/accept/80-sweat-hero-*.png. The others have lawn, trees and the map above the
     * cards and no hero out: Blood in battle with its wrong tray (accept/73-battle-run2h.png,
     * 63-battle-card.png), Blood's scout (accept/56-scout.png) and Sweat's (clash-18647/08-scout.png).
     */
    private class Frame(name: String) {
        private val rgb = HeroCardsTest::class.java.getResourceAsStream("/hero-cards/$name.rgb")!!
            .use { it.readBytes() }
        fun pixel(x: Int, y: Int): Int? {
            val cx = x - 368
            val cy = y - 554
            if (cx !in 0 until 374 || cy !in 0 until 46) return null
            val i = (cy * 374 + cx) * 3
            return (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        }
    }

    private val landed = mapOf(
        "sweat-landed-a" to setOf("king", "queen", "warden"),
        "sweat-landed-b" to cards.keys,
        "sweat-landed-c" to cards.keys,
        "blood-battle-73" to emptySet(),
        "blood-battle-63" to emptySet(),
        "blood-scout-56" to emptySet(),
        "sweat-scout-08" to emptySet()
    )

    @Test fun aLandedHeroShowsItsBarAndATrayCardDoesNot() {
        for ((name, expected) in landed) {
            val frame = Frame(name)
            for ((hero, card) in cards) {
                val bar = HeroCards.bar(card, frame::pixel)
                assertEquals("$name $hero $bar", hero in expected, bar.landed)
            }
        }
    }

    /** The bar is the full card width; the grass and trees above a tray card never come close. */
    @Test fun theBarIsEvenAcrossTheCardAndTheLawnIsNot() {
        for ((name, expected) in landed) {
            val frame = Frame(name)
            for ((hero, card) in cards) {
                val bar = HeroCards.bar(card, frame::pixel)
                if (hero in expected) assertTrue("$name $hero $bar", bar.cover >= 0.95 && bar.rows >= 6)
                else assertTrue("$name $hero $bar", bar.cover < 0.1)
            }
        }
    }

    /** Calibration puts the card area a few pixels either way from one profile to the next. */
    @Test fun aCardAreaOffByAFewPixelsReadsTheSame() {
        for ((name, expected) in landed) {
            val frame = Frame(name)
            for ((hero, card) in cards) for (dx in listOf(-4, 4)) for (dy in listOf(-4, 4)) {
                val moved = Region(card.left + dx, card.top + dy, card.right + dx, card.bottom + dy)
                assertEquals("$name $hero $dx,$dy", hero in expected, HeroCards.bar(moved, frame::pixel).landed)
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun onlyAPureBrightGreenIsBar() {
        val card = cards.getValue("king")
        fun band(colour: Int, rows: IntRange): (Int, Int) -> Int? = { _, y -> if (y in rows) colour else rgb(90, 70, 50) }
        // The bar's darkest and brightest pixels on frame 80-a.
        assertTrue(HeroCards.bar(card, band(rgb(54, 193, 1), 576..583)).landed)
        assertTrue(HeroCards.bar(card, band(rgb(179, 255, 21), 576..583)).landed)
        // The lawn above the tray on frame 73.
        assertFalse(HeroCards.bar(card, band(rgb(140, 183, 62), 560..593)).landed)
        // Bright but bluish, or green but dull.
        assertFalse(HeroCards.bar(card, band(rgb(120, 230, 90), 560..593)).landed)
        assertFalse(HeroCards.bar(card, band(rgb(60, 160, 10), 560..593)).landed)
        // Two rows of bar are a line, not a bar.
        assertFalse(HeroCards.bar(card, band(rgb(80, 214, 5), 576..577)).landed)
        // Green on the card itself is not above it.
        assertFalse(HeroCards.bar(card, band(rgb(80, 214, 5), 600..640)).landed)
    }

    @Test fun aBarAcrossPartOfTheCardIsNotEnough() {
        val card = cards.getValue("king")
        val half: (Int, Int) -> Int? = { x, y -> if (y in 576..583 && x < card.left + card.width / 2) rgb(80, 214, 5) else rgb(90, 70, 50) }
        assertFalse(HeroCards.bar(card, half).landed)
    }

    @Test fun offTheFrameNothingHasLanded() {
        assertFalse(HeroCards.bar(cards.getValue("king")) { _, _ -> null }.landed)
        assertEquals(HeroCards.Bar(0, 0.0), HeroCards.bar(Region(400, 600, 400, 702)) { _, _ -> rgb(80, 214, 5) })
    }
}
