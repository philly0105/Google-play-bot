package local.clashfarmer.recognition

import local.clashfarmer.core.Region

/**
 * Whether a hero has left the tray, read off its card.
 *
 * A landed hero's card rises out of the tray and carries the hero's health bar across its top: on
 * Sweat's frames of 14 October (audit-evidence/accept/80-sweat-hero-*.png) the bar sits 17 to 28 px
 * above the card's calibrated top, bright green from (54,193,1) to (179,255,21), across the whole
 * card width, and stays green after the ability greys the card art. A hero still in the tray has no
 * bar. The level badge stays on the card either way, so it proves nothing.
 *
 * Lawn and trees sit right above the tray, so green alone is not a bar. The grass on those frames
 * is about (140,183,62), but its brightest tufts reach a green of 217 (accept/56-scout.png) or run
 * 68 ahead of red (73-battle-run2h.png), and a few lone pixels pass every limit below. Only the
 * limits together keep it out: a pixel as bright and pure as the bar, in a row it covers for most
 * of the card's width, three rows running. No lawn row on the saved frames covered a tenth.
 */
object HeroCards {

    /** How far above the card's calibrated top the bar is looked for, and the gap left above it. */
    private const val SEARCH_ABOVE = 40
    private const val SEARCH_GAP = 6
    private const val MIN_COVER = 0.6
    private const val MIN_BAR_ROWS = 3

    private const val MIN_GREEN = 180
    private const val MAX_BLUE = 40
    private const val GREEN_OVER_RED = 60

    /** The longest run of rows the bar covers, and the best cover of any one row (0 to 1). */
    data class Bar(val rows: Int, val cover: Double) {
        val landed: Boolean get() = rows >= MIN_BAR_ROWS
    }

    fun bar(card: Region, pixel: (Int, Int) -> Int?): Bar {
        val width = card.width
        if (width <= 0) return Bar(0, 0.0)
        var best = 0.0
        var run = 0
        var longest = 0
        for (y in card.top - SEARCH_ABOVE until card.top - SEARCH_GAP) {
            val lit = (card.left until card.right).count { x -> pixel(x, y)?.let(::isBar) == true }
            val cover = lit.toDouble() / width
            best = maxOf(best, cover)
            run = if (cover >= MIN_COVER) run + 1 else 0
            longest = maxOf(longest, run)
        }
        return Bar(longest, best)
    }

    private fun isBar(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return g >= MIN_GREEN && b <= MAX_BLUE && g - r >= GREEN_OVER_RED
    }
}
