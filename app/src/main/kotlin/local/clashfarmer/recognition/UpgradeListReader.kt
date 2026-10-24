package local.clashfarmer.recognition

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.PcGeometry.Anchor
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.WallPanel
import java.text.Normalizer
import java.util.Locale

/**
 * The builder upgrade list read off one frame: its rows, whether it is showing at all, and what
 * a scroll would have to change.
 *
 * Nothing here taps or decides. A row is a name at the left and, for the rows this cares about,
 * a price at the right with its currency's icon just before the digits. Only Wall rows are looked
 * at closely: their price, currency icon and colour are sampled from pixels, and everything else
 * is a name and a position. Geometry is the phone's own (ScreenTargets.kt), measured on
 * 29 September; audit-evidence/2026-09-29-upgrade-list-discovery.md.
 */
object UpgradeListReader {

    /** Boxes whose centres are this close vertically are one row; rows are about 38 px apart. */
    private const val ROW_TOLERANCE = 14

    /** Name text ends before this fraction of the popup; a price starts after it. */
    private const val NAME_SPLIT = 0.5

    /** A gap wider than this between two words is not one name; the village shows through the popup. */
    private const val NAME_GAP = 25

    /** A box this close to the popup's edge is cut off by it. */
    private const val CLIP_MARGIN = 3

    /** A price digit or space is about this wide, which places the price inside a merged box. */
    private const val PRICE_CHAR_WIDTH = 12

    /** The currency icon sits this far either side of the price's left edge. */
    private const val ICON_REACH = 30

    /** Lab rows found left of the builder popup before a missing list reads as the wrong one. */
    private const val LAB_ROWS = 4

    /** Rows with a price flush right before a frame with no header still counts as the list. */
    private const val PRICED_ROWS = 2

    /** A price this close to the popup's right edge is right-aligned like the list's own. */
    private const val PRICE_EDGE = 45

    /** How much of the page must sit where it did for two frames to be the same page. */
    private const val SAME_PAGE = 0.7
    private const val SAME_PAGE_Y = 12
    private const val SAME_ROW_Y = 6

    /** Titles two pages must share in order before one can read as the other not having moved. */
    private const val MIN_COMMON_TITLES = 3

    /**
     * Since Clash 18.647 a row is a card: the name, then its price about 22 px lower and flush
     * right. A line this close under a row that has no price yet is that row's second line.
     */
    private const val CARD_LINE_GAP = 32

    /**
     * A card's icon fills about this much of the 18.647 panel's left edge and carries the
     * building's level as a badge ("19", "6"). A box that ends inside it is that badge, never a
     * name: read as the card's leftmost word it took the name, and a Wall row would be lost.
     */
    private const val ICON_COLUMN = 50

    /**
     * The PC's [ICON_COLUMN], in pixels at 1740x978, scaled with the frame's height ([PcGeometry]).
     * Its badges read wider and end later: "se" and "20" end at x 1336 and 1344, up to 72 px into
     * the panel, and with 50 they were glued onto "Giant Bomb x 8" and "Hero Hunter Poison Effect"
     * (audit-evidence/spike/geo-list-end.json). Card names start at x 1346 and end far past the
     * cut. Rows in progress have no icon and start at x 1283, so a name there shorter than about
     * 65 px, such as Bomb, is cut too; a row in progress is never a Wall, since walls upgrade at once.
     */
    private const val PC_ICON_COLUMN = 80

    /**
     * The PC panel's currency icons, an x at 1740x978 kept from the right edge: one column from
     * here to the frame's edge, whatever the boxes say. Prices end by x 1700 and the coin's yellow
     * fills x 1708 to 1724; the reader returns the coin alone as a "0" from x 1699 or 1702, or runs
     * a price on over it to x 1734, and either way the icon region after the price missed it
     * (audit-evidence/spike/geo-list-end.json and .png). The elixir drop sits in the same column.
     */
    private const val PC_COIN_COLUMN = 1696

    /** A card's price line ends about 25 px below its name's (Blood's Wall row, 14 October). */
    private const val CARD_PRICE_DROP = 30

    sealed interface Amount {
        data class Exact(val value: Long) : Amount
        /** Digits were there but do not make a price: abbreviated, glued, or partly read. */
        data class Ambiguous(val text: String) : Amount
        /** No price was read on the row. */
        data object Absent : Amount
    }

    enum class Kind { WALL, AMBIGUOUS_WALL, OTHER }

    data class Row(
        val name: String,
        /** Where to tap to select the row, on the frame it was read from. */
        val tap: Point,
        val top: Int,
        val bottom: Int,
        val kind: Kind,
        /** The number after the x in "Wall x80"; only for a Wall row. */
        val count: Int? = null,
        val amount: Amount = Amount.Absent,
        /** "gold" or "elixir" from the icon; null when neither clearly is. */
        val currency: String? = null,
        /** True for a price drawn white, false for red, null when the pixels do not say. */
        val payable: Boolean? = null,
        val clipped: Boolean = false,
        /** The price as read on any row, for telling pages apart; null when the row has none. */
        val priceText: String? = null
    ) {
        val centerY: Int get() = (top + bottom) / 2

        /** A Wall row whose count, price and currency all read; anything less earns a reread. */
        val readable: Boolean
            get() = kind == Kind.WALL && count != null && amount is Amount.Exact && currency != null
    }

    /** One line of text inside the popup and where it sat, minus timers, for comparing pages. */
    data class PageItem(val text: String, val y: Int)

    data class Reading(
        val rows: List<Row>,
        val hasListEvidence: Boolean,
        /** A section header is showing, which only the list draws; priced lines alone could be a wall panel's totals. */
        val headerShowing: Boolean,
        /** "Upgrades in progress:" is showing, which is where the list starts. */
        val atTop: Boolean,
        /** The laboratory's list is showing instead: its rows sit left of the builder popup. */
        val laboratoryShowing: Boolean,
        val items: List<PageItem>,
        /**
         * The 18.647 panel's "Buildings & Heroes" title is showing, so the list is open whatever
         * its rows say: a page of it can have no header in view (Blood's Wall row, 14 October).
         */
        val builderPanel: Boolean = false
    )

    fun read(boxes: List<RecognizedBox>, viewport: Viewport, pixels: (Region) -> IntArray): Reading {
        val own = boxes.filterNot { isOwnOverlay(it, viewport) }
        val panel = builderPanelShowing(own, viewport)
        val popup = upgradePopupRegion(viewport, own)
        val inside = dedupe(own.filter { popup.contains(it.center) })

        val letters = inside.map { compact(it.text) }
        // The 18.647 panel has no "Upgrades in progress" header: its first rows are the timers'
        // green bars, and only rows in progress sit above "Suggested upgrades".
        val atTop = letters.any { it.contains("upgrade") && it.contains("progress") } ||
            (panel && letters.any { it.contains("suggested") && it.contains("upgrade") })
        val headers = atTop || letters.any {
            it.contains("upgrade") && (it.contains("suggested") || it.contains("other"))
        }
        val rows = rowsOf(inside, popup, viewport, pixels, panel)
        val pricedFlush = inside.count { box ->
            box.right >= popup.right - PRICE_EDGE && !isCountdown(box.text) && hasDigit(box.text)
        }
        val evidence = headers || panel || pricedFlush >= PRICED_ROWS

        val lab = laboratoryColumnRegion(viewport)
        val labRows = own.count { lab.contains(it.center) }

        return Reading(
            rows = rows,
            hasListEvidence = evidence,
            headerShowing = headers,
            atTop = atTop,
            laboratoryShowing = !evidence && labRows >= LAB_ROWS,
            items = inside.filterNot { isCountdown(it.text) }
                .map { PageItem(pageText(it.text), it.center.y) }
                .sortedBy { it.y },
            builderPanel = panel
        )
    }

    /**
     * Whether two readings are the same page of the list: each line where it was, give or take
     * a few pixels. The village underneath animates and timers tick, so only this popup's text
     * counts and timers are left out; a wall count changing on its own is not movement either.
     */
    fun samePage(a: Reading, b: Reading): Boolean = pageMatch(a, b) >= SAME_PAGE

    /**
     * The share of items found again at about the same height. OCR noise changes a few lines from
     * frame to frame (the clipped edge rows most), so text is compared as letters and digits only;
     * a real scroll moves every row far more than [SAME_PAGE_Y], so the loose match cannot pass one.
     */
    fun pageMatch(a: Reading, b: Reading): Double {
        val larger = maxOf(a.items.size, b.items.size)
        if (larger == 0) return 1.0
        val free = b.items.toMutableList()
        var matched = 0
        for (item in a.items) {
            val key = alnum(item.text)
            val hit = free.indexOfFirst { alnum(it.text) == key && kotlin.math.abs(it.y - item.y) <= SAME_PAGE_Y }
            if (hit >= 0) {
                free.removeAt(hit)
                matched++
            }
        }
        return matched.toDouble() / larger
    }

    private fun alnum(text: String): String = text.filter { it.isLetterOrDigit() }

    /**
     * A row as a page comparison sees it: the first two words of its name as letters, and its
     * price. The village shows through the popup and every frame reads it differently (Sweat,
     * 8 October): "Town Hall Request", "Giant Bomb yR O0B x7 WAR", "Bormb" for Bomb, zeros read
     * as O, o or D, the coin read as a leading O. So the name keeps only words that carry
     * letters and are not an x count, "rn" reads as m, and the price is the longest run of
     * digit groups once lookalike letters count as digits. A row with no price has [price] null.
     */
    data class Title(val stem: String, val price: Long?)

    /** [text] is a row's name, then a space and its price as read when it has one. */
    fun title(text: String): Title {
        val digits = String(CharArray(text.length) { DIGIT_LIKE[text[it]] ?: text[it] })
        var best: String? = null
        var start = text.length
        for (match in PRICE.findAll(digits)) {
            val value = match.value.filter(Char::isDigit).trimStart('0')
            if (value.isNotEmpty() && (best == null || value.length >= best.length)) {
                best = value
                start = match.range.first
            }
        }
        val words = text.substring(0, start).split(WORD_GAP)
            .filter { word -> word.any(Char::isLetter) && !COUNT_WORD.matches(word) }
        val stem = words.take(2).joinToString("").lowercase(Locale.ROOT).filter { it in 'a'..'z' }.replace("rn", "m")
        return Title(stem, best?.toLongOrNull())
    }

    /**
     * The page's rows top to bottom as [Title]s, minus rows the popup's edge cuts off and stray
     * village digits ("61", "G1") that have neither a name nor a price.
     */
    fun titles(reading: Reading): List<Title> = reading.rows.filterNot { it.clipped }
        .map { title(it.name + (it.priceText?.let { price -> " $price" } ?: "")) }
        .filter(::isRow)

    /** Whether a title is a list row and not stray village text: a name of three letters, or a price. */
    fun isRow(title: Title): Boolean = title.stem.length >= 3 || title.price != null

    /**
     * Whether two titles are one row read twice: prices agree where both read, and names are
     * within an edit or two of each other ("bormb" and "bomb"). Names repeat down the list, so
     * the price is what tells Sweat's three Cake-A-Pults apart.
     */
    fun sameTitle(a: Title, b: Title): Boolean {
        if (a.price != null && b.price != null && a.price != b.price) return false
        val shorter = minOf(a.stem.length, b.stem.length)
        val allowed = when {
            shorter >= 6 -> 2
            shorter >= 3 -> 1
            else -> 0
        }
        return editDistance(a.stem, b.stem) <= allowed
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * How a later page sits against an earlier one: [common] titles line up in order between them,
     * and [revealed] is how many more rows the later page shows past the last of those than the
     * earlier page did. A scroll down shows about nine new rows; a list that has stopped, or
     * bounced back at its end, shows none.
     */
    data class Advance(val common: Int, val revealed: Int)

    /**
     * Lines up two pages' [titles] as a longest common subsequence under [sameTitle], so a misread
     * or a row gained or lost at either edge does not break the comparison, and measures from its
     * last pair.
     */
    fun advance(before: List<Title>, after: List<Title>): Advance {
        val n = before.size
        val m = after.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            lcs[i][j] = if (sameTitle(before[i - 1], after[j - 1])) lcs[i - 1][j - 1] + 1 else maxOf(lcs[i - 1][j], lcs[i][j - 1])
        }
        var i = n
        var j = m
        // A price that did not read matches any price, so such a pair counts as common but is never
        // what revealed is measured from: two Wall rows differ only by price (audit A-W4-R2).
        while (i > 0 && j > 0 && !(sameTitle(before[i - 1], after[j - 1]) && lcs[i][j] == lcs[i - 1][j - 1] + 1 &&
                before[i - 1].price != null && after[j - 1].price != null)) {
            // On a tie drop the later page's row: that reads as more revealed, never as a stop.
            if (lcs[i][j - 1] >= lcs[i - 1][j]) j-- else i--
        }
        if (i == 0 || j == 0) return Advance(0, m)
        return Advance(lcs[n][m], (m - j) - (n - i))
    }

    /**
     * Whether [after] shows nothing below [before]: enough titles line up (three, and half the
     * shorter page) and none follow the last of them that [before] did not have. Row heights play
     * no part, so a page caught mid-bounce a row off its rest still reads as the same end.
     */
    fun stopped(a: Advance, before: List<Title>, after: List<Title>): Boolean =
        a.common >= maxOf(MIN_COMMON_TITLES, (minOf(before.size, after.size) + 1) / 2) && a.revealed <= 0

    /** Whether [row] on one reading is the same row as [other] on another. */
    fun sameRow(row: Row, other: Row): Boolean =
        row.kind == other.kind && row.count == other.count && row.amount == other.amount &&
            row.currency == other.currency && kotlin.math.abs(row.centerY - other.centerY) <= SAME_ROW_Y

    /** OCR sometimes returns one line twice, a few pixels apart; keep the first. */
    private fun dedupe(boxes: List<RecognizedBox>): List<RecognizedBox> {
        val kept = mutableListOf<RecognizedBox>()
        for (box in boxes.sortedWith(compareBy({ it.top }, { it.left }))) {
            val twin = kept.any {
                it.text == box.text && kotlin.math.abs(it.left - box.left) <= 4 && kotlin.math.abs(it.top - box.top) <= 4
            }
            if (!twin) kept += box
        }
        return kept
    }

    private fun rowsOf(
        all: List<RecognizedBox>, popup: Region, viewport: Viewport, pixels: (Region) -> IntArray, panel: Boolean
    ): List<Row> {
        val iconColumn = PcGeometry.pick(viewport, ICON_COLUMN, PcGeometry.length(viewport, PC_ICON_COLUMN))
        val boxes = if (panel) all.filter { it.right >= popup.left + iconColumn } else all
        val split = popup.left + (popup.width * NAME_SPLIT).toInt()
        val clusters = mutableListOf<MutableList<RecognizedBox>>()
        for (box in boxes.sortedBy { it.center.y }) {
            val open = clusters.lastOrNull()
            if (open != null && box.center.y - open.map { it.center.y }.average() <= ROW_TOLERANCE) open += box
            else clusters += mutableListOf(box)
        }
        if (panel) {
            // A card's second line (a subtitle, its price, or both) joins the name above it.
            val cards = mutableListOf<MutableList<RecognizedBox>>()
            for (line in clusters) {
                val card = cards.lastOrNull()
                val priced = card?.any { it.left >= split && !isCountdown(it.text) && hasDigit(it.text) } == true
                // A section header is its own line, never a card's.
                val header = line.any { compact(it.text).contains("upgrade") }
                if (card != null && !priced && !header &&
                    line.map { it.center.y }.average() - card.minOf { it.center.y } <= CARD_LINE_GAP
                ) card += line else cards += line
            }
            return cards.mapNotNull { cluster -> rowOf(cluster, popup, split, viewport, pixels, panel) }
        }
        return clusters.mapNotNull { cluster -> rowOf(cluster, popup, split, viewport, pixels, panel) }
    }

    private fun rowOf(
        cluster: List<RecognizedBox>, popup: Region, split: Int, viewport: Viewport, pixels: (Region) -> IntArray,
        panel: Boolean = false
    ): Row? {
        val left = cluster.filter { it.left < split }.sortedBy { it.left }
        val first = left.firstOrNull() ?: return null
        val nameBoxes = mutableListOf(first)
        for (box in left.drop(1)) {
            if (box.left - nameBoxes.last().right <= NAME_GAP) nameBoxes += box
        }
        var name = nameBoxes.joinToString(" ") { it.text.trim() }
        var nameRight = nameBoxes.last().right
        var priceText: String? = null
        var priceLeft = 0
        var priceRight = 0
        var priceTop = 0
        var priceBottom = 0

        // On the PC a box that starts in the coin column is the coin, never digits of the price.
        val coin = PcGeometry.pick(viewport, Int.MAX_VALUE, PcGeometry.x(viewport, PC_COIN_COLUMN, Anchor.RIGHT))
        val prices = cluster.filter { it.left >= split && it.left < coin && !isCountdown(it.text) && hasDigit(it.text) }.sortedBy { it.left }
        if (prices.isNotEmpty()) {
            priceText = prices.joinToString(" ") { it.text.trim() }
            priceLeft = prices.first().left
            priceRight = prices.last().right
            priceTop = prices.minOf { it.top }
            priceBottom = prices.maxOf { it.bottom }
        } else {
            // The reader sometimes runs a name and its price on as one box.
            val merged = MERGED.matchEntire(name)
            val tail = nameBoxes.last()
            if (merged != null && tail.right >= popup.right - PRICE_EDGE) {
                val perChar = (tail.right - tail.left).toDouble() / tail.text.trim().length.coerceAtLeast(1)
                name = merged.groupValues[1]
                priceText = merged.groupValues[2]
                nameRight = (tail.left + perChar * name.length).toInt()
                priceLeft = (tail.right - PRICE_CHAR_WIDTH * priceText.length).coerceAtLeast(tail.left)
                priceRight = tail.right
                priceTop = tail.top
                priceBottom = tail.bottom
            }
        }

        // Village text shows through the popup and can trail a name; a counted Wall in front of it stands.
        var wall = wallKind(name)
        if (wall?.kind != Kind.WALL) {
            for (n in nameBoxes.size - 1 downTo 1) {
                val prefix = nameBoxes.take(n).joinToString(" ") { it.text.trim() }
                val hit = wallKind(prefix)
                if (hit?.kind == Kind.WALL) {
                    wall = hit
                    name = prefix
                    nameRight = nameBoxes[n - 1].right
                    break
                }
            }
        }

        val top = nameBoxes.minOf { it.top }
        val bottom = nameBoxes.maxOf { it.bottom }
        val tap = Point((first.left + nameRight) / 2, (top + bottom) / 2)
        val clipped = top < popup.top + CLIP_MARGIN || bottom > popup.bottom - CLIP_MARGIN
        val plain = Row(name, tap, top, bottom, Kind.OTHER, clipped = clipped, priceText = priceText)

        // A Wall card in the panel's last line of view can have its name above the tabs and its
        // price under them (Blood, three scrolls down: r3-03). It is cut off like any other, and
        // the next scroll shows it whole, where a reread at the same place never would.
        val priceCut = panel && bottom + CARD_PRICE_DROP > popup.bottom &&
            (priceText == null || priceBottom > popup.bottom - CLIP_MARGIN)

        return when (val kind = wall) {
            null -> plain
            else -> {
                if (clipped || priceCut) return plain.copy(kind = kind.kind, count = kind.count, clipped = true)
                val amount = priceText?.let(::parseAmount) ?: Amount.Absent
                val currency: String?
                val payable: Boolean?
                if (priceText == null) {
                    currency = null
                    payable = null
                } else {
                    // The 18.647 panel draws the coin after the digits, on the price's own line.
                    val edge = if (panel && PcGeometry.isPc(viewport)) Region(coin, priceTop, viewport.right, priceBottom)
                    else if (panel) Region(
                        priceRight, priceTop, (priceRight + ICON_REACH).coerceAtMost(viewport.right), priceBottom
                    ) else Region(
                        (priceLeft - ICON_REACH).coerceAtLeast(viewport.left), top,
                        (priceLeft + ICON_REACH).coerceAtMost(viewport.right), bottom
                    )
                    val digits = if (panel) Region(priceLeft, priceTop, priceRight.coerceAtLeast(priceLeft + 1), priceBottom)
                        else Region(priceLeft, top, priceRight.coerceAtLeast(priceLeft + 1), bottom)
                    currency = WallPanel.iconCurrency(pixels(edge))
                    payable = WallPanel.priceAffordable(pixels(digits))
                }
                plain.copy(kind = kind.kind, count = kind.count, amount = amount, currency = currency, payable = payable)
            }
        }
    }

    private data class WallName(val kind: Kind, val count: Int?)

    /**
     * Only a name that is the word Wall and then an x count is a wall: "Wall Breaker" is a
     * laboratory troop and never matches. The count is part of the name and never the price.
     * The word is allowed the lookalikes this font trades its l for, as WallPanel does for the
     * title. A bare "Wall" is a wall whose count did not read, which earns a reread.
     *
     * So does one short word starting with w and an x count ("Wait x 23", "Wa x 23"): no other
     * card is named like that, and read as some other card it would let a scan conclude the
     * list has no Wall row at all, which ends the wall phase for both currencies.
     */
    private fun wallKind(name: String): WallName? {
        // "Wáll x 23": marks off first, as WallPanel does for the title.
        val text = Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
        val counted = WALL_ROW.matchEntire(text)
        if (counted != null) {
            val count = ScreenReader.normalizeAmountText(counted.groupValues[1]).toIntOrNull()
            return if (count != null && count > 0) WallName(Kind.WALL, count) else WallName(Kind.AMBIGUOUS_WALL, null)
        }
        return if (WALL_WORD.matches(text) || WALL_LIKE.matches(text)) WallName(Kind.AMBIGUOUS_WALL, null) else null
    }

    /** A price as the list draws it: full digits with spaces between the groups, never K or M. */
    fun parseAmount(text: String): Amount {
        if (ABBREVIATED.containsMatchIn(text)) return Amount.Ambiguous(text)
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // The coin can come back as a lone letter on either side of the digits.
        if (tokens.size >= 2 && tokens.first().length == 1 && tokens.first()[0].isLetter()) tokens.removeAt(0)
        if (tokens.size >= 2 && tokens.last().length == 1 && tokens.last()[0].isLetter()) tokens.removeAt(tokens.lastIndex)
        val joined = tokens.joinToString(" ")
        val value = WallPanel.strictPrice(joined) ?: WallPanel.panelPrice(joined)
        return if (value != null) Amount.Exact(value) else Amount.Ambiguous(text)
    }

    /**
     * "5h 51m", "1d 11h", "23h 46m": the timers on rows in progress. 18.647 draws "11H 15M" and
     * "2d 2H"; a capital unit counts only as the first of two, since a lone "5M" is a price.
     */
    fun isCountdown(text: String): Boolean = COUNTDOWN.containsMatchIn(text)

    private fun hasDigit(text: String): Boolean = text.any { it.isDigit() }

    private fun compact(text: String): String = text.lowercase(Locale.ROOT).filter { it in 'a'..'z' }

    /** Text as a page comparison sees it: lower case, single spaces, no trailing x count. */
    private fun pageText(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("\\s*[x×]\\s*\\d{1,3}\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val WALL_ROW = Regex("wa[l1i|!]{1,2}\\s*[x×]\\s*([0-9oOilsS]{1,3})")
    private val WALL_WORD = Regex("wa[l1i|!]{1,2}")
    private val WALL_LIKE = Regex("w\\S{0,4}\\s*[x×]\\s*[0-9oOilsS]{1,3}")
    private val MARKS = Regex("\\p{Mn}+")
    private val MERGED = Regex("(.+?\\s[x×]\\s*[0-9oOilsS]{1,3})\\s+(\\S.*)")
    /** Letters the list's font is read as in place of a digit; "o" and "D" for 0 most. */
    private val DIGIT_LIKE = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'S' to '5', 's' to '5', 'Z' to '7',
        'G' to '6', 'i' to '1', 'I' to '1', 'l' to '1'
    )
    /** A price at the start of a word: digit groups ("8 000 000", "8000 000"), or four digits run on. */
    private val PRICE = Regex("(?<!\\S)(?:0?\\d{1,4}(?: \\d{3})+|\\d{4,})")
    private val COUNT_WORD = Regex("[xX×]\\S{0,2}")
    private val WORD_GAP = Regex("\\s+")
    private val ABBREVIATED = Regex("\\d\\s*[kKmM]\\b")
    private val COUNTDOWN = Regex("\\b\\d{1,2}\\s*[dhm]\\b|\\b\\d{1,2}\\s*[DHdh]\\s+\\d{1,2}\\s*[HMhm]\\b")
}
