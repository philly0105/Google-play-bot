package local.clashfarmer.recognition

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Viewport
import java.util.Locale

/** What one result frame said: the "You got" rows that read, the total damage and the stars. */
data class BattleReading(val earned: Map<Resource, Long>, val damagePercent: Int?, val stars: Int? = null)

/** A pixel is ink when its green is above [minGreen] and ahead of red and blue by the margins. */
data class GreenInk(val minGreen: Int, val overRed: Int, val overBlue: Int) {
    fun matches(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return g > minGreen && g - r > overRed && g - b > overBlue
    }
}

/**
 * Reads the result screen: the loot the battle took, under "You got", its total damage and the
 * stars above it.
 *
 * Calibration cannot reach this screen without attacking, so the regions hang from the screen's
 * own words, found where the result draws them; with a word missing its values stay unknown. The
 * league bonus beside the panel and any reward taken during the battle are not part of these rows.
 *
 * The rows count up from zero as the screen opens, and two cuts of one frame agree on a number
 * still climbing, so a value only settles when two frames in a row agree on it ([settled]).
 */
object BattleResultReader {

    /**
     * The rows' heights, from "You got:", were measured at 1600x720 on the result frames of 21 and 22
     * September. Their sides are measured from the middle of the screen, because the phone's box for
     * "You got:" moves (its centre from 780 to 802 on 15 October) while the panel does not: on the
     * ten result frames of that morning every row's digits end at x 822 to 824, against icons from
     * 839, and "1 650 606" starts at 681. Rows hung from the box lost that leading 1 or a last digit.
     * The left side leaves 28 px for an eighth digit. Left of the panel, to x 652, a building's
     * health bar can show through the shade; the shade keeps it under luminance 180, so it is not ink.
     */
    private const val ROW_LEFT = -147
    private const val ROW_RIGHT = 34
    private val ROW_OFFSETS = listOf(Resource.GOLD to 18, Resource.ELIXIR to 65, Resource.DARK_ELIXIR to 113)
    private const val ROW_HEIGHT = 34

    /** "You got:" sits at about (803, 297); "Total damage:" at (802, 90). */
    private val YOU_GOT_BAND = listOf(0.35, 0.3, 0.65, 0.55)
    private val DAMAGE_BAND = listOf(0.35, 0.0, 0.65, 0.25)
    private val YOU_GOT = Regex("yougot")
    private val TOTAL_DAMAGE = Regex("to[a-z]{1,4}da[a-z]*")

    fun lootRegions(boxes: List<RecognizedBox>, viewport: Viewport): Map<Resource, Region>? {
        val anchor = find(boxes, viewport, YOU_GOT, YOU_GOT_BAND) ?: return null
        val middle = viewport.left + viewport.width / 2
        return ROW_OFFSETS.associate { (resource, dy) ->
            resource to Region(middle + ROW_LEFT, anchor.y + dy, middle + ROW_RIGHT, anchor.y + dy + ROW_HEIGHT)
        }
    }

    /**
     * The rows' digits are white, luminance above 215 with their channels within 70, inside a dark
     * outline; the dimmed village behind them stays under 160. A column of a row is ink with two
     * such pixels, so the one-pixel lines between the rows never count.
     */
    private const val FILL_LUMINANCE = 215
    private const val FILL_SPREAD = 70
    private const val INK_PER_COLUMN = 2

    /** Digits and their groups sit at most 13 px apart on the frames of 15 October. */
    private const val GLYPH_GAP = 16
    private const val EDGE = 2

    /**
     * No digit is wider than 19 px, but two can touch: the "74" of 610 474 on the victory frame is
     * one run 32 px wide. A run wider than this is counted as two digits. Two touching digits with
     * a 1 among them can be narrower and still count as one. Over the 22 result frames of 15
     * October no single digit is wider than 19 px at any anchor from 8 px above to 8 px below, so
     * 19 is the lowest cut-off with no miss. 20 leaves a pixel to spare, and a 1 (7 to 8 px)
     * touching a digit 14 px or wider now counts as two.
     */
    private const val WIDE_GLYPH = 20

    /**
     * How many digits the "You got" row in [roi] holds, counted from its ink rather than read, or
     * null when it cannot be told: no ink, or ink at either side of the row, which may run past it.
     * The glyphs are the runs of inked columns chained leftwards from the icon. On the 66 rows of 15
     * October and the 27 of eight other result frames this gives each row's digit count with the
     * anchor anywhere from 8 px above to 8 px below where it sits. [pixel] gives the ARGB colour at
     * a point, or null off the frame.
     */
    fun glyphCount(roi: Region, pixel: (Int, Int) -> Int?): Int? {
        val inked = (roi.left until roi.right).filter { x ->
            (roi.top until roi.bottom).count { y -> pixel(x, y)?.let(::digitFill) == true } >= INK_PER_COLUMN
        }
        if (inked.isEmpty()) return null
        val glyphs = mutableListOf<IntRange>()
        for (x in inked.asReversed()) {
            val last = glyphs.lastOrNull()
            when {
                last == null -> glyphs += x..x
                last.first - x == 1 -> glyphs[glyphs.lastIndex] = x..last.last
                last.first - x - 1 > GLYPH_GAP -> break
                else -> glyphs += x..x
            }
        }
        val clear = glyphs.last().first - roi.left > EDGE && roi.right - 1 - glyphs.first().last > EDGE
        return (glyphs.size + glyphs.count { it.last - it.first + 1 > WIDE_GLYPH }).takeIf { clear }
    }

    /**
     * Whether a row's read [value] has as many digits as its ink has glyphs. The phone read
     * "1 650 606" as 650 606 on every cut at 03:08 on 15 October; the ink of that row is seven
     * glyphs, so the short read is dropped rather than recorded.
     */
    fun inkAgrees(value: Long, glyphs: Int?): Boolean = glyphs == value.toString().length

    private fun digitFill(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 > FILL_LUMINANCE && maxOf(r, g, b) - minOf(r, g, b) < FILL_SPREAD
    }

    fun damageRegion(boxes: List<RecognizedBox>, viewport: Viewport): Region? {
        val anchor = find(boxes, viewport, TOTAL_DAMAGE, DAMAGE_BAND) ?: return null
        return Region(anchor.x - 45, anchor.y + 13, anchor.x + 45, anchor.y + 43)
    }

    /**
     * The damage digits are green, about (136, 200, 89), with a dark outline. Behind them is a
     * silver star when the middle star was earned and an olive silhouette over the base when it
     * was not, so on 8 October the luminance cuts either merged the digits into the white star or
     * dropped them, and 22 of 22 battles lost their damage. White and silver have red, green and
     * blue within 50 of each other and the silhouette is low and yellowish, so a green lead
     * separates the digits from both. Both masks keep exactly the digits on the seven result frames
     * of 21 September to 7 October; the softer one takes in the anti-aliased edge of each glyph.
     */
    val DAMAGE_INKS = listOf(GreenInk(150, 50, 60), GreenInk(130, 35, 45))

    /**
     * Where the three stars are sampled, from left to right. Each point sits on the body of its
     * star and off the digits, the label and the ribbon. The left one is low and to the right
     * because this app's Stop button covers the top of that star in the frames of 21 to 24
     * September; the middle one sits under the digits.
     */
    private val STAR_OFFSETS = listOf(Point(-95, 85), Point(0, 50), Point(100, 70))
    private const val STAR_RADIUS = 12

    /**
     * Earned stars are silver-white: on the seven result frames, with the anchor moved up to 6 px
     * each way, the sampled discs averaged luminance 167 to 225 at saturation 0.21 or less.
     * Unearned stars are a dark olive silhouette at 52 to 109. The gold ribbon is bright too but
     * saturated near 0.8, so saturation keeps it out.
     */
    private const val EARNED_LUMINANCE = 140.0
    private const val EARNED_SATURATION = 0.35

    fun starCentres(boxes: List<RecognizedBox>, viewport: Viewport): List<Point>? {
        val anchor = find(boxes, viewport, TOTAL_DAMAGE, DAMAGE_BAND) ?: return null
        return STAR_OFFSETS.map { Point(anchor.x + it.x, anchor.y + it.y) }
    }

    /**
     * How many of the stars at [centres] were earned, or null when it cannot be told. [pixel] gives
     * the ARGB colour at a point, or null off the frame. Stars are earned from the left, so a lit
     * star after an unlit one is a misreading - another window over the screen, say - not a count.
     */
    fun starCount(centres: List<Point>, pixel: (Int, Int) -> Int?): Int? {
        val earned = centres.map { centre -> starEarned(centre, pixel) ?: return null }
        val count = earned.count { it }
        return count.takeIf { earned.take(count).all { it } }
    }

    private fun starEarned(centre: Point, pixel: (Int, Int) -> Int?): Boolean? {
        var luminance = 0.0
        var saturation = 0.0
        var n = 0
        for (dy in -STAR_RADIUS..STAR_RADIUS) for (dx in -STAR_RADIUS..STAR_RADIUS) {
            if (dx * dx + dy * dy > STAR_RADIUS * STAR_RADIUS) continue
            val p = pixel(centre.x + dx, centre.y + dy) ?: continue
            // The anchor moves a few pixels between readings, which can bring the digits' lower
            // edge into the middle disc.
            if (DAMAGE_INKS.first().matches(p)) continue
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            luminance += (r * 299 + g * 587 + b * 114) / 1000.0
            saturation += if (max == 0) 0.0 else (max - minOf(r, g, b)).toDouble() / max
            n++
        }
        if (n == 0) return null
        return luminance / n >= EARNED_LUMINANCE && saturation / n < EARNED_SATURATION
    }

    /**
     * Whether a star count can go with a damage. One star is 50% or the town hall, two is both,
     * and three is 100%, so 100% is always three stars and 50% is always at least one.
     */
    fun starsAgreeWithDamage(stars: Int, damagePercent: Int): Boolean = when {
        damagePercent == 100 -> stars == 3
        stars == 3 -> false
        stars == 2 -> damagePercent >= 50
        stars == 1 -> true
        else -> damagePercent < 50
    }

    /**
     * The total damage, when two cuts read the same whole percent, sign and all. The digits get
     * the amount rows' letter repairs: 59% came back as "S9%" on 24 September.
     */
    fun damagePercent(rawReadings: List<String>): Int? {
        val values = rawReadings.mapNotNull { raw ->
            val text = ScreenReader.normalizeAmountText(raw).replace(Regex("\\s"), "")
            Regex("^(\\d{1,3})%$").find(text)?.groupValues?.get(1)?.toInt()
        }.filter { it in 0..100 }
        return values.groupingBy { it }.eachCount().filterValues { it >= 2 }.maxByOrNull { it.value }?.key
    }

    /**
     * Each value its last two readings agree on; anything still moving, or read once, is unknown.
     * A frame that did not read a value is skipped for it rather than unsettling it: on 24 September
     * one frame between two exact reads lost the "You got" anchor. The rows only count up, so two
     * equal readings mean the count has stopped.
     */
    fun settled(frames: List<BattleReading>): BattleReading {
        fun <T> agreed(values: List<T?>): T? = values.filterNotNull().takeLast(2)
            .takeIf { it.size == 2 && it[0] == it[1] }?.last()
        return BattleReading(
            earned = Resource.entries.mapNotNull { r -> agreed(frames.map { it.earned[r] })?.let { r to it } }.toMap(),
            damagePercent = agreed(frames.map { it.damagePercent }),
            stars = agreed(frames.map { it.stars })
        )
    }

    /**
     * The box whose letters, run together, match [word] inside [band]. On 7 October the phone read
     * "You got:" as "Yougot:" on 8 of 46 result frames, and "Total damage:" as "Total danMage",
     * "Tocal daage:", "Toal danmage:" and two dozen other spellings on 55 of 58, so the words are
     * matched without their spaces and the damage word by its "to...da..." shape alone. The band
     * keeps a defender's name at the top of the screen from passing as either.
     */
    private fun find(boxes: List<RecognizedBox>, viewport: Viewport, word: Regex, band: List<Double>) =
        boxes.firstOrNull { box ->
            word.containsMatchIn(box.text.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")) &&
                inBand(box.center.x, box.center.y, viewport, band)
        }?.center

    private fun inBand(x: Int, y: Int, viewport: Viewport, band: List<Double>): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        val fx = (x - viewport.left).toDouble() / viewport.width
        val fy = (y - viewport.top).toDouble() / viewport.height
        return fx in band[0]..band[2] && fy in band[1]..band[3]
    }
}
