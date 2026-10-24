package local.clashfarmer.core

import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.recognition.RecognizedBox
import java.util.Locale
import kotlin.math.roundToInt

/** The village's builder counter: [free] of [total] builders are idle. */
data class BuilderCount(val free: Int, val total: Int)

/**
 * The builder counter in the village's top bar, and the dialog Clash opens when an upgrade is
 * asked for with no builder free.
 *
 * Measured on Blood at 1600x720, 09:33 UTC on 8 October (audit-evidence/overnight/
 * W1D-after-unverified.png): the counter "0/6" sits beside the builder's face at x 795-845, y 40;
 * the "0/1" at x 640-680 is another counter and is not the builders'. The dialog is titled "All
 * builders are busy!", asks "Complete the previous building and free up a builder?", and offers a
 * green 212-gem button at x 678-918, y 400-510, with its red X at (1060, 215).
 */
object Builders {

    /** Where the builder counter sits, as fractions of the viewport: x 728-896, y 0-94. */
    private const val COUNTER_LEFT = 728.0 / 1600
    private const val COUNTER_RIGHT = 896.0 / 1600
    private const val COUNTER_BOTTOM = 94.0 / 720

    /** Since Clash 18.647: the counters' line above Build, x 1395-1530, y 525-578. */
    private const val CORNER_LEFT = 1395.0 / 1600
    private const val CORNER_RIGHT = 1530.0 / 1600
    private const val CORNER_TOP = 525.0 / 720
    private const val CORNER_BOTTOM = 578.0 / 720

    /** The builders' counter starts left of this; the goblin's starts near x 1475. */
    private const val CORNER_BUILDERS_LEFT = 1450.0 / 1600

    /**
     * The same line and split on the PC, in pixels of 1740x978 kept from the right edge
     * ([PcGeometry]): the builders' "0/6" at x 1576-1642, y 774-804, the goblin's "0/1" beside it
     * near x 1680 (audit-evidence/spike/geo-home.json). At 16:10 the "0/6" reads at x 1667-1746,
     * y 920-954 of 1860x1162 (audit-evidence/live/20261024-034034-dragmeasure/4a-right150.json),
     * and sits at x 1400-1466, y 774-804 of the same frame resized to 1565x978.
     */
    private val PC_CORNER = Region(1569, 756, 1723, 816)
    private const val PC_CORNER_BUILDERS_LEFT = 1632

    /** One glyph free, a slash, one digit total. The top bar's "O" for a zero is taken as one. */
    private val COUNT = Regex("^([0-9OoDQ])\\s*/\\s*([0-9])$")

    /**
     * A counter inside a longer line: no digit or slash glued before it, nothing glued after it. A
     * stray letter before it is OCR noise from the icon beside it: Blood's top bar read "a0/6" at
     * 13:19:46 UTC on 8 October.
     */
    private val TOKEN = Regex("(?<![0-9/])[0-9OoDQ]\\s*/\\s*[0-9](?!\\S)")

    /** The builders' total: a village starts with two, and has at most seven. */
    private val BUILDER_TOTALS = 2..7

    /** The busy dialog's words with everything but letters removed, so OCR spacing does not matter. */
    private val BUSY_WORDS = listOf("buildersarebusy", "allbuilders", "freeupabuilder", "completetheprevious")

    /** Where the dialog's title, body and gem button sit: the middle of the screen. */
    private val DIALOG = Region(400, 150, 1200, 560)

    /** The gem button's row, where a digits-only box is its price. */
    private val GEM_BUTTON = Region(660, 390, 940, 520)

    /** The red X's corner, right of the title, well clear of the gem button. */
    private val CLOSE_CORNER = Region(980, 160, 1150, 270)

    private val CLOSE_GLYPHS = setOf("x", "×")

    fun parseCount(text: String): BuilderCount? {
        val m = COUNT.find(text.trim()) ?: return null
        val free = m.groupValues[1].let { if (it[0].isDigit()) it.toInt() else 0 }
        val total = m.groupValues[2].toInt()
        if (total < 1 || free > total) return null
        return BuilderCount(free, total)
    }

    /**
     * The builder counter off a village frame, or null when it did not read or read two ways.
     *
     * ML Kit returns whole lines and, it is inferred, joins the top bar's counters: on Blood on
     * 8 October the read was null at 11:05:48 UTC with 0/6 on screen though a lone "O/6" box
     * already read, and the busy dialog's frame at 11:06:34 held "O/| 0/6" in sequence, the other
     * builder's "0/1" beside the builders' "0/6", zeros read as the letter O. So every N/M token in a top-bar box that reaches over the
     * builder's face is read, an O taken as a zero, and only a total of two to seven is the
     * builders'. Two such tokens that differ are a guess, and read null.
     */
    fun read(boxes: List<RecognizedBox>, viewport: Viewport): BuilderCount? {
        readCorner(boxes, viewport)?.let { return it }
        val reads = topBar(boxes, viewport).filter { box ->
            val left = (box.left - viewport.left).toDouble() / viewport.width
            val right = (box.right - viewport.left).toDouble() / viewport.width
            left <= COUNTER_RIGHT && right >= COUNTER_LEFT
        }.flatMap { box ->
            // A no-break space between the counters is a space too.
            val text = box.text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
            TOKEN.findAll(text).mapNotNull { parseCount(it.value) }.toList()
        }.filter { it.total in BUILDER_TOTALS }.distinct()
        return reads.singleOrNull()
    }

    /**
     * Since Clash 18.647 (14 October) the counters sit above Build, read as one line "1/6 1/2" at
     * x 1411-1512, y 540-563 on Sweat: the builders' first, the goblin's "1/2" after it. The
     * goblin's total is two, inside the builders' range, so position decides: the first token of
     * the leftmost box that starts where the builders' counter does. audit-evidence/clash-18647/01-home.
     * A slash before that token is the builders' with its free count lost: Sweat's read "/6 1/2"
     * at 07:55:39 UTC on 14 October, and the goblin's "1/2" is not the builders'.
     */
    private fun readCorner(boxes: List<RecognizedBox>, viewport: Viewport): BuilderCount? {
        if (viewport.width <= 0 || viewport.height <= 0) return null
        val first = corner(boxes, viewport)
            .filter { (it.left - viewport.left).toDouble() / viewport.width <= PcGeometry.pick(viewport, CORNER_BUILDERS_LEFT, PcGeometry.xFraction(viewport, PC_CORNER_BUILDERS_LEFT, Anchor.RIGHT)) }
            .minByOrNull { it.left } ?: return null
        val text = first.text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
        val token = TOKEN.find(text) ?: return null
        if ('/' in text.substring(0, token.range.first)) return null
        return parseCount(token.value)?.takeIf { it.total in BUILDER_TOTALS }
    }

    private fun corner(boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> =
        boxes.filter { inCorner(it, viewport) }

    /** Whether [box] is centred on the 18.647 counters' line above Build. */
    fun inCorner(box: RecognizedBox, viewport: Viewport): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        if (PcGeometry.isPc(viewport)) return PcGeometry.region(viewport, PC_CORNER, Anchor.RIGHT).holds(box.center)
        val x = (box.center.x - viewport.left).toDouble() / viewport.width
        val y = (box.center.y - viewport.top).toDouble() / viewport.height
        return x in CORNER_LEFT..CORNER_RIGHT && y in CORNER_TOP..CORNER_BOTTOM
    }

    /** The top bar's and the corner's boxes as text and x span, "O/1 O/6@638-845", for the log. */
    fun topBarText(boxes: List<RecognizedBox>, viewport: Viewport): String =
        (topBar(boxes, viewport) + corner(boxes, viewport)).joinToString(" | ") { "${it.text}@${it.left}-${it.right}" }

    private fun topBar(boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> {
        if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
        return boxes.filter { box ->
            val y = (box.center.y - viewport.top).toDouble() / viewport.height
            y in 0.0..COUNTER_BOTTOM
        }
    }

    /**
     * Whether "All builders are busy!" is up: its title or body read in the middle of the screen,
     * or the word "builder" there with a bare number on the gem button. That number must be the
     * dialog's only bare number and the builder no "Builder's Hut": the upgrade list's prices, split
     * by OCR into "8", "000", "000", put several bare numbers on the same rows.
     */
    fun isBusyDialog(boxes: List<RecognizedBox>, viewport: Viewport): Boolean {
        val inDialog = boxes.filter { scaled(DIALOG, viewport).holds(it.center) }
        val letters = inDialog.joinToString("") { it.text }.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        if (BUSY_WORDS.any { letters.contains(it) }) return true
        val bareNumbers = inDialog.filter { it.text.trim().matches(Regex("[0-9]{1,4}")) }
        val gemPrice = bareNumbers.singleOrNull()?.let { scaled(GEM_BUTTON, viewport).holds(it.center) } == true
        return gemPrice && letters.contains("builder") && !letters.contains("buildershut")
    }

    /**
     * The busy dialog's red X, the box read in its corner, or null when it did not read. Never the
     * gem button. There is no fixed fallback: a frame caught while the dialog fades has no X, and
     * a tap at the corner then lands on the village and opens whatever building is there.
     */
    fun closeTap(boxes: List<RecognizedBox>, viewport: Viewport): Point? {
        val corner = scaled(CLOSE_CORNER, viewport)
        return boxes.firstOrNull {
            it.text.trim().lowercase(Locale.ROOT) in CLOSE_GLYPHS && corner.holds(it.center)
        }?.center
    }

    private fun Region.holds(p: Point) = p.x in left..right && p.y in top..bottom

    private fun scaled(p: Point, v: Viewport) = Point(
        v.left + (p.x * v.width / 1600.0).roundToInt(),
        v.top + (p.y * v.height / 720.0).roundToInt()
    )

    private fun scaled(r: Region, v: Viewport): Region {
        val a = scaled(Point(r.left, r.top), v)
        val b = scaled(Point(r.right, r.bottom), v)
        return Region(a.x, a.y, b.x, b.y)
    }
}
