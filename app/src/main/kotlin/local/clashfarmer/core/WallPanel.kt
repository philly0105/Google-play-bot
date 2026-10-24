package local.clashfarmer.core

import local.clashfarmer.recognition.RecognizedBox
import local.clashfarmer.recognition.ScreenReader
import java.text.Normalizer
import java.util.Locale

/**
 * Everything the wall panel says, read off recognised text alone.
 *
 * This holds no coordinates of its own. The panel's buttons moved between two layouts and the
 * old code carried a calibrated point for each, which is how a press meant for Upgrade landed
 * between two buttons and a 4 000 000 wall came back priced at 5 000 000. Buttons are found by
 * their labels and prices by being the only all-digit text on the panel, so a layout change
 * moves nothing that has to be recalibrated.
 */
object WallPanel {

    fun strictPrice(text: String): Long? {
        val normalized = ScreenReader.normalizeAmountText(text).trim()
        if (!normalized.matches(Regex("[0-9]{1,8}|[0-9]{1,3}(?:[ ,][0-9]{3}){1,2}"))) return null
        return normalized.replace(Regex("[ ,]"), "").toLongOrNull()?.takeIf { it > 0 }
    }

    /** A tiny panel price may leave one glyph fragment on its right edge after binarization. */
    fun smallPriceCandidate(text: String): Long? {
        val compact = ScreenReader.normalizeAmountText(text).replace(Regex("[ ,]"), "").trim()
        if (!compact.matches(Regex("[1-9][0-9]{5,7}[A-Za-z]?"))) return null
        return compact.takeWhile(Char::isDigit).toLongOrNull()?.takeIf { it > 0 }
    }

    fun namedPrice(text: String, currency: String): Long? {
        if (currency !in setOf("gold", "elixir") || !namesCurrency(text, currency)) return null
        val matches = Regex("(?i)\\bfor\\s+([0-9][0-9 ,]*)\\s+" + currency + "\\b")
            .findAll(text).mapNotNull { strictPrice(it.groupValues[1]) }.toList()
        return matches.singleOrNull()
    }

    /** What the panel title says: the level of the selection, and how many walls are in it. */
    data class WallSelection(val level: Int, val count: Int)

    enum class WallButton {
        INFO, SELECT_ROW, UPGRADE_MORE, ADD_TEN, ADD_ONE, REMOVE
    }

    /** A button that spends [currency]: where to press it, and the price on it if that read. */
    data class PurchaseButton(val currency: String, val tap: Point, val price: Long?, val priceBox: RecognizedBox)

    /** A panel price: digits and the spaces between their groups, nothing else. */
    private val PRICE_TEXT = Regex("[0-9][0-9 ]{2,}")

    private val CONFIRM_LABELS = setOf("ok", "okay", "confirm", "yes")
    private val REJECT_LABELS = setOf("cancel", "no", "back")

    /**
     * The strip of screen the panel occupies, as a fraction of the viewport's height.
     *
     * The title sits at 0.66 and the buttons at 0.78 on this phone. Everything above is the
     * village and the resource bars, whose numbers would otherwise be mistaken for prices.
     */
    private const val BAND_TOP = 0.62
    private const val BAND_BOTTOM = 0.88

    fun band(boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> {
        val top = viewport.top + (viewport.height * BAND_TOP).toInt()
        val bottom = viewport.top + (viewport.height * BAND_BOTTOM).toInt()
        return boxes.filter { it.top >= top && it.bottom <= bottom }
    }

    /**
     * The level and count from "Wall (Level 15)" or "Wall (Level 15)x 1".
     *
     * The x and the count are drawn tight against the bracket and come back with the space
     * anywhere or nowhere, so the count is taken as whatever digits follow the bracket. A title
     * with no count is one wall.
     *
     * The word "Wall" is not required. It is drawn in the stylised outlined font Clash uses for
     * panel titles and comes back as "WaII" or "Wal" often enough to lose a panel that was
     * plainly on screen - at 22:34 on 17 September a Level 15 panel was open, readable in the
     * screenshot, and the phase bought nothing because the title did not contain the four
     * letters it was looking for. Nothing else in the band carries the word "level" followed by
     * a number, so that pair alone identifies the title - with the l of "level" allowed to
     * come back as an i or a 1, which in this font it does.
     */
    fun parseTitle(boxes: List<RecognizedBox>, viewport: Viewport): WallSelection? = readTitle(boxes, viewport)?.first

    /**
     * The count after the title's x, or null when the title did not read or has no x. A title
     * without one is a single wall to [parseTitle], but it says nothing about a bulk selection.
     */
    fun titleCount(boxes: List<RecognizedBox>, viewport: Viewport): Int? =
        readTitle(boxes, viewport)?.takeIf { it.second }?.first?.count

    /** The selection the title names, and whether it carried an x and a count. */
    private fun readTitle(boxes: List<RecognizedBox>, viewport: Viewport): Pair<WallSelection, Boolean>? {
        val inBand = band(boxes, viewport)
        for (raw in inBand.map { it.text } + lines(inBand)) {
            // "Wáll (Level 18)" and "Swäil:(Level 18)" on 1 October: marks first, then letters.
            val text = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(MARKS, "")
            val match = LEVEL_TEXT.find(text) ?: continue
            if (!WALL_WORD.containsMatchIn(text.substring(0, match.range.first))) continue
            // "Levelí18" loses its accent to "leveli18", whose i is the separator, not a 1.
            val digits = match.groupValues[1]
            val level = listOf(digits, digits.drop(1).takeIf { digits.first().isLetter() })
                .firstNotNullOfOrNull { it?.let(::asNumber)?.takeIf { n -> n in 1..PLAUSIBLE_LEVEL } }
                ?: continue
            val suffix = text.substring(match.range.last + 1)
            val count = if ('x' in suffix) {
                COUNT_TEXT.find(suffix)?.let { asNumber(it.groupValues[1]) }
                    ?.takeIf { it in 1..PLAUSIBLE_COUNT } ?: continue
            } else 1
            return WallSelection(level, count) to ('x' in suffix)
        }
        return null
    }

    private val MARKS = Regex("\\p{Mn}+")

    /**
     * The band's boxes that share a line, joined left to right. At 07:12 on 1 October Blood's
     * title came back as "Wall (Lever" and "i8)" in two boxes, and neither read on its own.
     */
    private fun lines(boxes: List<RecognizedBox>): List<String> {
        val lines = mutableListOf<MutableList<RecognizedBox>>()
        for (box in boxes.sortedBy { it.top + it.bottom }) {
            val centre = (box.top + box.bottom) / 2
            val line = lines.lastOrNull()?.takeIf { line -> line.any { centre in it.top..it.bottom } }
            if (line != null) line.add(box) else lines.add(mutableListOf(box))
        }
        return lines.filter { it.size > 1 }
            .map { line -> line.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    /**
     * The word "level" and the number after it, both allowed to be mangled.
     *
     * Every l of the title can come back as an i or a 1, and a digit of the number can come
     * back as the letter it resembles: at 22:39 on 17 September "Wall (Level 15)" read as
     * "Wall Leveli5", and at 23:05 the same title read as "Wall (Levelis)". So the word is
     * matched letter by letter with those treated as one, the number is read the same way, and
     * a reading that is not a level a wall can be is passed over rather than believed.
     *
     * The last l is taken possessively and never as a 1. On 22 September a Level 18 title read
     * as "Wall (Leve18):", the optional l swallowed the 1 and the level came out as 8; and
     * "Wall (Level-18)" failed on the hyphen, backtracked, and read the l itself as level 1.
     */
    // On 1 October the same Level 18 title also read as "Leyel:i8", "Lever i8" and "Level T8":
    // the v can come back as a y, the last l as an r, and a leading 1 as a t.
    private val LEVEL_TEXT = Regex("[a-z1][e3][vy][e3][lir]?+[\\s:(\\-.]*(t?[\\dlios]+)")

    /**
     * The word before the level: "wa" and up to four of the letters this font makes of "ll".
     *
     * On 30 September Blood's Level 18 title read as "Walis(Level 18)", "WaiistLevel 18)",
     * "Waiitkevel i8)", "Wair (Level 18)y" and "Wal (Eevel 18)", every one was lost, and two
     * phases gave up on a panel in plain sight. So the l of "level" may be any letter and the
     * word before it may run into it, but the word must end there, so "Grand Warden" is no wall.
     */
    // "walk(Level 18)", "waik(Level i8)" and "AWaistLevel i8)" were lost at 06:59 on 30 September:
    // the font's l also reads as k, and a stray letter can land in front of the W.
    // "Wail-tLevel 18)" and "Wall (Léevei 16)" on 1 October left one stray letter between the
    // word and the level, so one is allowed there.
    private val WALL_WORD = Regex("\\b[a-z]?wa[lirstck1]{0,4}[^a-z0-9]*[a-z]?$")
    private val COUNT_TEXT = Regex("\\)?\\s*x\\s*([\\dlios]+)")

    /** No wall reaches level 25, and no selection runs to 200 walls. */
    private const val PLAUSIBLE_LEVEL = 25
    private const val PLAUSIBLE_COUNT = 200

    private fun asNumber(text: String): Int? = text
        .map {
            when (it) {
                'l', 'i', 't' -> '1'
                's' -> '5'
                'o' -> '0'
                else -> it
            }
        }
        .joinToString("")
        .toIntOrNull()

    /**
     * Whether a wall panel is on screen: a title naming a level, and something to buy with.
     *
     * This is asked here rather than of the screen classifier, which tells HOME from WALL by
     * looking for the words "wall", "level", "upgrade" and "select row" anywhere in the reader's
     * output. "Select Row" is drawn on two lines and comes back as two boxes, so whether the
     * phrase exists at all depends on those two boxes landing next to each other in the
     * concatenation - and at 23:13 on 17 September they did not, a Level 17 panel in plain sight
     * read as HOME, and the phase reported that it had not selected a wall it had selected. The
     * band settles it instead: the village draws wall levels and dashed Upgrade hints over
     * itself, but never inside the strip a panel occupies.
     */
    fun isPanel(boxes: List<RecognizedBox>, viewport: Viewport): Boolean {
        val band = band(boxes, viewport)
        val more = band.firstOrNull { labelText(it.text).contains("more") }
        val upgrades = upgradeLabels(band, more)
        // A title and one Upgrade is a panel; so is Upgrade More beside two more Upgrades with the
        // title unread, which is how a panel was left standing on 23 September.
        // The bulk panel Upgrade More opens has Add Wall where Upgrade More was. Its +10 is drawn
        // red and often does not read, so one Add Wall or Remove Wall is enough.
        val bulk = addButtons(boxes, viewport).isNotEmpty() || band.any { labelText(it.text).contains("remove") }
        return (parseTitle(boxes, viewport) != null && upgrades.isNotEmpty()) ||
            ((more != null || bulk) && upgrades.size >= 2)
    }

    /**
     * Where to press Upgrade More: on the word "More" itself.
     *
     * The reader sometimes runs "MORe" on into the gold button's label as one box, "MORe UpgRade",
     * and the middle of that box is the gold button: at 03:11 on 23 September the press meant for
     * Upgrade More opened a gold purchase dialog. So the press goes where the word sits within
     * the box, taking the characters as evenly wide.
     */
    fun upgradeMoreTap(boxes: List<RecognizedBox>, viewport: Viewport): Point? {
        val box = band(boxes, viewport).firstOrNull { labelText(it.text).contains("more") } ?: return null
        val text = labelText(box.text)
        val start = text.indexOf("more")
        val x = box.left + (box.right - box.left) * (start + 2) / text.length
        return Point(x, box.center.y)
    }

    /**
     * The two Add Wall buttons of the bulk panel, left to right: +10, then +1.
     *
     * The +10 and +1 are drawn on the icons, so the labels are identical and only their order
     * tells them apart. "Add" has to start a word: the reader spells Upgrade "UPgRadde", which
     * has "add" in the middle of it.
     */
    fun addButtons(boxes: List<RecognizedBox>, viewport: Viewport): List<RecognizedBox> = band(boxes, viewport)
        .filter { Regex("(^|[^a-z])add").containsMatchIn(labelText(it.text)) }
        .sortedBy { it.left }

    /**
     * Where Add Wall +1 is when its label did not read: one button's pitch left of the gold
     * purchase button. On 30 September the bulk panel stood Remove 447, +10 589, +1 729,
     * gold 872 and elixir 1014 across a 1600-wide screen.
     */
    fun addOneByPitch(buttons: Map<String, PurchaseButton>, viewport: Viewport): Point? {
        val gold = buttons["gold"]?.tap ?: return null
        return Point(gold.x - PcGeometry.pick(viewport, viewport.width * BUTTON_PITCH / 1600, PcGeometry.length(viewport, PC_BUTTON_PITCH)), gold.y)
    }

    private const val BUTTON_PITCH = 143

    /**
     * The PC draws a selected building's buttons at the phone's own pixel size at 1740x978: under
     * the Town Hall the labels centre at x 592, 732 and 872, and 1152 four buttons along
     * (audit-evidence/spike/geo-selected.json). Like the rest of the HUD they scale with the frame's
     * height ([PcGeometry]); that, and the bulk panel, are not captured on the PC yet.
     */
    private const val PC_BUTTON_PITCH = 140

    /**
     * The level a wall stands at, or null when the title and the price do not agree on one.
     *
     * The title says the level and the price can only confirm it. A Gold Pass takes 10% off on
     * the first day of a season and up to 20% by the end, so one level-18 wall costs 10 000 000,
     * 9 000 000, 8 500 000 or 8 000 000 depending on the day: Blood paid 8M on 30 September and
     * was asked 9M after the reset on 5 October. A table of paid prices took the 9M walls for
     * level 19 and never knew 8.5M at all. So a price is accepted for a level when it lies within
     * 0-20% under that level's full price, and a price outside the band cancels the purchase.
     *
     * [fullPrice] is a struck-out full price, when one was read: it must be the full price of the
     * level, and the paid price must lie within its band.
     *
     * The title is set in Clash's outlined font and can lose a digit: on 23 September "Wall
     * (Level 17)" came back as "Wall (Level 1)", and on 22 September a Level 18 title read as 8.
     * A single-digit or missing title therefore falls back to the one level whose band holds the
     * price, and a price two bands share (4 000 000 is 15 at full price and 16 at 20% off) buys
     * nothing without a title to settle it. Any other title without a full price here, such as
     * 19, the wall cap, buys nothing.
     */
    fun wallLevel(titleLevel: Int?, unitPrice: Long, fullPrice: Long? = null): Int? {
        if (fullPrice != null && !inDiscountBand(unitPrice, fullPrice)) return null
        val levels = FULL_PRICE_BY_LEVEL
            .filterValues { full -> inDiscountBand(unitPrice, full) && (fullPrice == null || full == fullPrice) }
            .keys
        return when {
            titleLevel != null && titleLevel in FULL_PRICE_BY_LEVEL -> titleLevel.takeIf { it in levels }
            titleLevel == null || titleLevel < 10 -> levels.singleOrNull()
            else -> null
        }
    }

    /** Whether [paid] is [full] with no more than the Gold Pass's 20% taken off. */
    private fun inDiscountBand(paid: Long, full: Long): Boolean =
        paid in 1..full && paid * 100 >= full * (100 - MAX_DISCOUNT_PERCENT)

    private const val MAX_DISCOUNT_PERCENT = 20

    /**
     * What raising one wall from each level costs with no discount. 4M and 5M were paid at full
     * price on Sweat; 7M and 10M are Blood's 5.6M and 8M at 20% off, and 10M was drawn struck
     * out over the 8M on 30 September. Nothing below 15 is listed, so nothing below 15 is bought.
     */
    private val FULL_PRICE_BY_LEVEL = mapOf(
        15 to 4_000_000L,
        16 to 5_000_000L,
        17 to 7_000_000L,
        18 to 10_000_000L
    )

    /**
     * The struck-out full price beside a discounted confirmation's price, or null when none is
     * on the row. It is the other price on the accept button's row that [paid] is 0-20% under;
     * a larger number that [paid] is not a discount of is something else and is not taken.
     */
    fun struckPrice(boxes: List<RecognizedBox>, confirmBox: RecognizedBox?, paid: Long): Long? {
        if (confirmBox == null) return null
        return boxes
            .filter { kotlin.math.abs(it.center.y - confirmBox.center.y) <= 60 }
            .mapNotNull { embeddedPrice(it.text) }
            .filter { it != paid && inDiscountBand(paid, it) }
            .distinct()
            .singleOrNull()
    }

    /** How many walls a panel total pays for at [unitPrice] each, or null if it is not a whole number. */
    fun countFor(total: Long, unitPrice: Long): Int? {
        if (unitPrice <= 0 || total <= 0 || total % unitPrice != 0L) return null
        return (total / unitPrice).toInt()
    }

    /** Every box in the panel band with its left edge, for the log to say what it read. */
    fun bandText(boxes: List<RecognizedBox>, viewport: Viewport): String =
        band(boxes, viewport).joinToString(" / ") { "${it.text}@${it.left}" }.take(400)

    /**
     * Where each button is, by the words on it.
     *
     * The two Add Wall buttons carry identical text (+10 and +1 are drawn on the
     * icon, not written), so left and right separate them.
     */
    fun buttons(boxes: List<RecognizedBox>, viewport: Viewport): Map<WallButton, RecognizedBox> {
        val band = band(boxes, viewport)
        fun withWord(word: String): List<RecognizedBox> = band
            .filter { labelText(it.text).contains(word) }
            .sortedBy { it.left }

        val found = mutableMapOf<WallButton, RecognizedBox>()
        withWord("info").firstOrNull()?.let { found[WallButton.INFO] = it }
        withWord("select").firstOrNull()?.let { found[WallButton.SELECT_ROW] = it }
        withWord("remove").firstOrNull()?.let { found[WallButton.REMOVE] = it }

        // "Upgrade More" is pressed on whichever box carries the word "More", because that
        // word is drawn on the button itself and no other button has it. Pairing it with the
        // nearest box saying "Upgrade" was tried and is not safe: at 23:18 on 17 September the
        // reader merged Select Row and the Upgrade of Upgrade More into one box, the nearest
        // remaining Upgrade was the gold purchase button, and the press meant to grow the
        // selection opened a purchase dialog instead. The purchase buttons are found by their
        // prices instead: see [purchaseButtons].
        val more = band.firstOrNull { labelText(it.text).contains("more") }
        more?.let { found[WallButton.UPGRADE_MORE] = it }

        val adds = withWord("add")
        if (adds.size >= 2) {
            found[WallButton.ADD_TEN] = adds[0]
            found[WallButton.ADD_ONE] = adds[1]
        }

        return found
    }

    /** Two labels closer than this across the panel are two lines of one button. */
    private const val SAME_BUTTON_X = 60

    /** The Upgrade labels outside Upgrade More's column: the purchase buttons and the ring button. */
    private fun upgradeLabels(band: List<RecognizedBox>, more: RecognizedBox?): List<RecognizedBox> = band
        .filter { labelText(it.text).contains("upgrade") && !labelText(it.text).contains("more") }
        .flatMap(::splitLabels)
        .filter { box -> more == null || kotlin.math.abs(box.center.x - more.center.x) > SAME_BUTTON_X }

    /**
     * One box per Upgrade word. At 08:23 on 30 September the elixir and ring labels came back as
     * one box, "UPgRade UpgRade", whose middle is the gap between the buttons: the elixir price
     * was cropped around that, read as "29", and a phase with three walls selected was refused.
     */
    private fun splitLabels(box: RecognizedBox): List<RecognizedBox> {
        val n = Regex("upgrade").findAll(labelText(box.text)).count()
        if (n < 2) return listOf(box)
        val width = (box.right - box.left) / n
        return (0 until n).map { i ->
            box.copy(text = "Upgrade", left = box.left + i * width, right = box.left + (i + 1) * width)
        }
    }

    /**
     * Enlarge each small price separately so OCR cannot merge neighbouring currencies.
     *
     * Each region is centred on the word Upgrade within its label box, taking the characters as
     * evenly wide, as [upgradeMoreTap] does. The reader runs Add Wall on into the gold label as
     * one box, "Add Wall Upgrade", and a region around the middle of that box cut the gold price
     * to "S000 0" on every reread (Sweat, 5 October).
     *
     * The PC's buttons keep the phone's pixel size ([PC_BUTTON_PITCH]), and its reader puts a
     * label's top about 76 px below its button's top where the phone's put it 81-85 below. Its
     * region keeps the phone's place on the button, from 10 px above its top to 50 px into it, so
     * the panel's title stays out of it as on the phone. All three are pixels at 1740x978, scaled
     * with the height. An estimate: no purchase button has been read on the PC yet.
     */
    fun priceRegions(boxes: List<RecognizedBox>, viewport: Viewport): List<Region> {
        val strip = band(boxes, viewport)
        val more = strip.firstOrNull { labelText(it.text).contains("more") }
        return upgradeLabels(strip, more)
            .filter { it.top >= viewport.top + viewport.height * 76 / 100 }
            .map { label ->
            val halfWidth = PcGeometry.pick(viewport, viewport.width * 75 / 1600, PcGeometry.length(viewport, 75))
            val text = labelText(label.text)
            val x = label.left + (label.right - label.left) * (2 * text.indexOf("upgrade") + 7) / (2 * text.length)
            Region(
                (x - halfWidth).coerceAtLeast(viewport.left),
                (label.top - PcGeometry.pick(viewport, viewport.height * 95 / 720, PcGeometry.length(viewport, 86))).coerceAtLeast(viewport.top),
                (x + halfWidth).coerceAtMost(viewport.right),
                (label.top - PcGeometry.pick(viewport, viewport.height * 35 / 720, PcGeometry.length(viewport, 26))).coerceAtMost(viewport.bottom)
            )
        }.distinct()
    }

    /**
     * A frame's first read with the [priceRegions] reads put in: a first-read box whose centre is
     * in a region gives way to those reads when its centre is in one whose read found a price, or
     * it reaches more than [REGION_SLACK] px into one. A read that found no price adds nothing and
     * takes nothing away, so a frame on which none did keeps its first read, and a box of both
     * prices there is still cut by [splitPrices]. An 8-digit gold total with its coin ends 1-2 px
     * past the elixir region's left edge, 936 against 934 at rest and 867 against 865 after
     * Upgrade More, and was dropped for that alone when its own region read no price (UO-18 round 2).
     */
    fun withPriceReads(boxes: List<RecognizedBox>, reads: List<Pair<Region, List<RecognizedBox>>>): List<RecognizedBox> {
        val priced = reads.filter { (_, read) -> read.any { panelPrice(it.text) != null } }
        return boxes.filterNot { box ->
            reads.any { (region, _) -> region.contains(box.center) } &&
                priced.any { (region, _) ->
                    region.contains(box.center) || minOf(box.right, region.right) - maxOf(box.left, region.left) > REGION_SLACK
                }
        } + priced.flatMap { (_, read) -> read }
    }

    /** How far a first-read price may reach into a neighbour's priced region and still be its own. */
    private const val REGION_SLACK = 6

    /** A price box is one price and its icon; wider than this it has swallowed its neighbour. */
    private const val MAX_PRICE_WIDTH = 150

    /** How far below its price a purchase button's label sits, for when the label did not read. */
    private const val PRICE_TO_LABEL_Y = 78

    /**
     * The gold and elixir purchase buttons, each found by the price printed on it.
     *
     * Taking the Upgrade labels left to right was wrong twice over on 23 September. The gold
     * label did not read, so the elixir button became "gold" and the Wall Ring button beside it
     * became "elixir"; and the ring button, which spends a magic item, has an Upgrade label just
     * like the other two. What only a purchase button has is a price with the currency's icon at
     * its end, so the buttons are found from those: [iconCurrency] names the icon at a price box's
     * right edge, and the button is the Upgrade label under it. A currency whose price box is not
     * found, or is found twice, has no button, and nothing is pressed for it. A box the reader made
     * of both prices is cut in two first: see [splitPrices].
     */
    fun purchaseButtons(
        boxes: List<RecognizedBox>,
        viewport: Viewport,
        iconCurrency: (RecognizedBox) -> String?
    ): Map<String, PurchaseButton> {
        val band = band(boxes, viewport)
        val more = band.firstOrNull { labelText(it.text).contains("more") }
        val labels = upgradeLabels(band, more)
        val found = band
            .flatMap { box -> if (box in labels || box === more) listOf(box) else splitPrices(box, labels) }
            .filter { box -> box !in labels && box !== more && box.right - box.left <= MAX_PRICE_WIDTH }
            // "|8000 000C" at 06:59 on 30 September: the elixir icon read as C on the last group.
            // panelPrice already drops an icon glyph there, so a box it prices is a price box.
            .filter { box -> panelPrice(box.text) != null ||
                ScreenReader.normalizeAmountText(box.text).let { it.matches(Regex("[0-9 ]+")) && it.count(Char::isDigit) >= 3 } }
            .filter { box -> labels.none {
                kotlin.math.abs(it.center.x - box.center.x) <= SAME_BUTTON_X && it.center.y <= box.center.y
            } }
            .filter { box -> more == null || kotlin.math.abs(box.center.x - more.center.x) > SAME_BUTTON_X }
            .mapNotNull { box ->
                val currency = iconCurrency(box)?.takeIf { it == "gold" || it == "elixir" }
                    ?: return@mapNotNull null
                val label = labels
                    .filter { kotlin.math.abs(it.center.x - box.center.x) <= SAME_BUTTON_X }
                    .minByOrNull { kotlin.math.abs(it.center.x - box.center.x) }
                val tap = label?.center ?: Point(box.center.x, box.center.y + PRICE_TO_LABEL_Y)
                PurchaseButton(currency, tap, panelPrice(box.text), box)
            }
        return found.groupBy { it.currency }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
    }

    /**
     * A box holding two purchase prices, cut in two between the Upgrade labels under it.
     *
     * After Upgrade More on 15 October Blood's panel moved left, to labels at 753, 892 and 1033,
     * and the reader returned the gold and elixir prices as one box, "B000 00DC 8000 O00" at 761.
     * It was wider than [MAX_PRICE_WIDTH], so neither price was a button and the count went
     * unread on all eight rereads. The box is cut only when its words part into two prices in
     * exactly one place and exactly two label centres lie under it. The cut is halfway between
     * those centres, so each half has its own icon to sample and its own label to press, and a
     * box that reaches the ring's label as well is left whole.
     *
     * Each half must also be no wider than one price and sit over its own label. Two labels under
     * a box are not always the two prices' labels. When the gold label merges with Add Wall's, as
     * it has on Sweat and Blood, a box of both prices that runs on over the ring has only the
     * elixir and ring labels under it, and a cut between those puts the elixir price over the
     * ring's button. When the elixir label is unread the labels are gold's and the ring's, and the
     * elixir half has no label near it, so it would be pressed beside the ring by its price alone.
     */
    private fun splitPrices(box: RecognizedBox, labels: List<RecognizedBox>): List<RecognizedBox> {
        if (box.right - box.left <= MAX_PRICE_WIDTH) return listOf(box)
        val words = box.text.trim().split(Regex("\\s+"))
        val cuts = (1 until words.size).filter { i ->
            panelPrice(words.take(i).joinToString(" ")) != null && panelPrice(words.drop(i).joinToString(" ")) != null
        }
        val under = labels.map { it.center.x }.filter { it in box.left..box.right }.sorted()
        if (cuts.size != 1 || under.size != 2) return listOf(box)
        val x = (under[0] + under[1]) / 2
        val halves = listOf(
            box.copy(text = words.take(cuts.single()).joinToString(" "), right = x),
            box.copy(text = words.drop(cuts.single()).joinToString(" "), left = x)
        )
        val fits = halves.zip(under).all { (half, label) ->
            half.right - half.left <= MAX_PRICE_WIDTH && kotlin.math.abs(half.center.x - label) <= SAME_BUTTON_X
        }
        return if (fits) halves else listOf(box)
    }

    /**
     * The price printed on a purchase button, or null.
     *
     * The type is small and the reader loses the spaces between digit groups and reads both the
     * zeros and the currency icon as the letter O: "8O00 000O" is 8 000 000 with the icon on the
     * end, and "4000 0oo O" is 4 000 000 with the icon on its own. Groups after the first are
     * three digits, so a fourth character on the last one, or a lone glyph after it, is the icon.
     * The first group may be four digits when the space after the millions went missing.
     */
    fun panelPrice(text: String): Long? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size >= 2 && tokens.last().length == 1 && !tokens.last()[0].isDigit()) {
            tokens.removeAt(tokens.lastIndex)
        }
        if (tokens.size >= 2 && tokens.last().length == 4) tokens[tokens.lastIndex] = tokens.last().dropLast(1)
        val groups = tokens.map { ScreenReader.normalizeAmountText(it) }
        if (groups.isEmpty() || groups.any { g -> g.isEmpty() || !g.all(Char::isDigit) }) return null
        // Two walls at 8 000 000 read as "16000 000" on 30 September: with tens of millions the
        // lost space leaves five digits in front.
        if (groups.size > 1 && groups.first().length > 5) return null
        if (groups.drop(1).any { it.length != 3 }) return null
        val digits = groups.joinToString("")
        if (digits.length !in 6..8 || digits.startsWith("0")) return null
        return digits.toLongOrNull()
    }

    /**
     * Which currency's icon is in these pixels, or null when neither clearly is.
     *
     * The gold coin is yellow and the elixir drop pink; the digits beside them are white, or red
     * when the balance is short, and pass neither test. Measured on 23 September's panel: 129
     * gold pixels and no elixir ones at the gold price's end, 98 and none at elixir's.
     */
    fun iconCurrency(argb: IntArray): String? {
        var gold = 0
        var elixir = 0
        for (c in argb) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 200 && g > 160 && b < 90) gold++
            if (r > 170 && b > 150 && g < 110) elixir++
        }
        return when {
            gold >= ICON_MIN_PIXELS && gold >= 3 * elixir -> "gold"
            elixir >= ICON_MIN_PIXELS && elixir >= 3 * gold -> "elixir"
            else -> null
        }
    }

    private const val ICON_MIN_PIXELS = 20

    /**
     * Whether Clash drew this price as payable: false for red digits, true for white, null when
     * the pixels do not say clearly.
     *
     * The game colours a price it knows the balance cannot cover. Measured on 21 and 23 September:
     * about 470 to 500 salmon-red pixels on an unaffordable price and none on an affordable one.
     * The gold coin fails the red test on its blue channel and the elixir drop on its green.
     */
    fun priceAffordable(argb: IntArray): Boolean? {
        var red = 0
        var white = 0
        for (c in argb) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 200 && g in 91..174 && b in 81..174 && r - g > 60) red++
            if (minOf(r, g, b) > 225) white++
        }
        return when {
            red >= PRICE_COLOUR_PIXELS && red > white -> false
            red < PRICE_COLOUR_PIXELS / 5 && white >= PRICE_COLOUR_PIXELS -> true
            else -> null
        }
    }

    private const val PRICE_COLOUR_PIXELS = 100

    /**
     * [read] with the leading one the resource bar's reader drops put back, when that and only
     * that makes it cover [price]; otherwise null.
     *
     * Two ones side by side in this font are two thin strokes a pixel apart and read as one, and
     * a lone leading one is thinner than anything around it: 11 370 884 came back as 1 370 884
     * on 23 September. Only called when the game has drawn the price as payable.
     */
    fun restoredLeadingOne(read: Long, price: Long): Long? {
        val digits = read.toString()
        if (digits.length >= 8) return null
        val restored = ("1" + digits).toLong()
        return restored.takeIf { it >= price && read < price }
    }

    /**
     * A button label with the digits this font trades letters for folded back.
     *
     * The panel labels are drawn small and outlined, and the reader swaps a digit for the letter
     * it looks like: at 23:02 on 17 September the elixir button came back as "UP9Rade", the word
     * "upgrade" was not in it, and the only purchase button the panel offered was gold. Nothing
     * matched against this has a digit in it, so folding cannot turn one label into another.
     */
    private fun labelText(text: String): String = text.lowercase(Locale.ROOT)
        .replace('9', 'g')
        .replace('0', 'o')
        .replace('1', 'l')
        .replace('5', 's')

    /**
     * How many walls to try adding next: ten, one, or none.
     *
     * There is no budget arithmetic here because there is no price to do it with. The panel
     * prices are 13px type with a currency icon butted against them and at 22:39 on
     * 17 September both came back as one box reading "QO0D 00o O9O00 000O". The game does the
     * budgeting itself: an Add the balance cannot cover is drawn in red and pressing it does
     * nothing, so the count on the title is the only thing worth believing, and the caller
     * stops when it stops rising.
     */
    fun addStep(count: Int, maxWalls: Int): Int = when {
        count + 10 <= maxWalls -> 10
        count + 1 <= maxWalls -> 1
        else -> 0
    }

    /**
     * The currencies to try, deepest pile first.
     *
     * Walls cost the same in gold or elixir, so the only question is which pile is deeper. What
     * a wall actually costs is settled by the confirmation dialog, which prints its price across
     * half the screen and has never been misread.
     */
    fun currencyOrder(gold: Long, elixir: Long): List<String> =
        if (gold >= elixir) listOf("gold", "elixir") else listOf("elixir", "gold")

    /**
     * The words on a button, each with any run of one letter collapsed to a single letter.
     *
     * The reader doubles a letter often enough to matter: at 21:10 on 17 September the accept
     * button came back as "CONfiRMM", no button was found because "confirmm" is not "confirm",
     * the five million gold price sitting on that same button was never looked at, and a wall
     * phase with 22 million gold in the bank bought nothing and ended the session. None of the
     * six labels this is matched against has a doubled letter of its own, so collapsing runs
     * cannot turn one of them into another.
     *
     * The reader also spells the dotted capital I some fonts use as U+0130, whose lowercase is an
     * i with a combining dot over it rather than a plain i. At 05:02 on 19 September every gold
     * dialog came back reading "CONFİRM": the combining mark is not a letter, so the split broke
     * "confirm" into "confi" and "rm", no button was found, the 5 600 000 price printed on that
     * same button was never read, and a phase with 16 million gold in the bank spent none of it
     * while the elixir dialogs beside it bought walls. Marks are stripped before the split so a
     * dot over a letter cannot break a word in two.
     */
    private fun buttonWords(text: String): List<String> = text.lowercase(Locale.ROOT)
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .replace(Regex("\\p{Mn}+"), "")
        .replace('0', 'o')
        .split(Regex("[^a-z]+"))
        .filter { it.isNotEmpty() }
        .map { word ->
            val collapsed = StringBuilder()
            for (c in word) {
                if (collapsed.isEmpty() || collapsed.last() != c) collapsed.append(c)
            }
            collapsed.toString()
        }

    /**
     * Clash labels the accept button on its purchase dialog "Okay", not "Confirm", and sets
     * Cancel right beside it. Both are just words on the screen to the recogniser, so the
     * accept button is matched a whole word at a time and Cancel is ruled out by name rather
     * than by which side of the dialog it sits on. A zero stands in for the leading letter
     * often enough in this font to be worth folding back.
     */
    fun findConfirmButton(boxes: List<RecognizedBox>): RecognizedBox? = boxes.firstOrNull { box ->
        val words = buttonWords(box.text)
        words.none { it in REJECT_LABELS } && words.any { it in CONFIRM_LABELS }
    }

    /** The way out of a dialog this run has decided not to accept. */
    fun findCancelButton(boxes: List<RecognizedBox>): RecognizedBox? = boxes.firstOrNull { box ->
        buttonWords(box.text).any { it in REJECT_LABELS }
    }

    /**
     * Whether this line prices the wall in the currency asked about.
     *
     * "Dark Elixir" contains "elixir", and the bulk dialog prices a wall by naming its currency
     * in words, so a line reading "for 7000000 Dark Elixir?" would have been read as an elixir
     * price. Nothing on this account can reach that - dark elixir storage caps at 400 000 and
     * the wall that wants it costs seven million - but the phase should not depend on the
     * storage cap to keep it out of a currency it does not budget.
     */
    fun namesCurrency(text: String, currency: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (!lower.contains(currency)) return false
        return !lower.contains("dark $currency") && !lower.contains("dark$currency")
    }

    /** The level named in "Upgrade Wall to Level 18?", the highest if several are on screen. */
    fun targetLevel(text: String): Int? = Regex("level\\s*(\\d+)")
        .findAll(text.lowercase(Locale.ROOT))
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .maxOrNull()

    /**
     * Whether this is the dialog Clash opens instead of a confirmation when the balance is short.
     *
     * Its main button buys the missing amount with gems or a magic item. Twenty of them came up
     * between 19 and 21 September, read as "YoU doNt HaVe eNOUH ... aMagictem to do tHat", and
     * none contained the word "gem", so only the lack of a readable price kept the phase from
     * pressing it. The fragments below survive the recogniser's usual damage to each word.
     */
    fun isInsufficientFunds(text: String): Boolean {
        val letters = text.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        return listOf("enou", "donthave", "magic", "gem", "missing", "notenough")
            .any { letters.contains(it) }
    }

    /**
     * The confirmation without its Gold Pass advert, so the premium check can read the rest.
     *
     * On 5 October the wall dialog on Sweat carried "Wita tHe Gold Pass vOU COUld save up to:",
     * and the word "pass" alone made Policy refuse a plain elixir upgrade. Only that sentence is
     * cut; a gem price or "finish now" anywhere else in the dialog still refuses.
     */
    fun withoutGoldPassNote(text: String): String =
        GOLD_PASS_NOTE.replace(text, " ")

    private val GOLD_PASS_NOTE =
        Regex("""gold\s*pass.{0,40}?sav[ea]\s*up\s*to""", RegexOption.IGNORE_CASE)

    /** Clash puts the reason a purchase cannot go through in a red panel above the button. */
    fun isBlocked(text: String): Boolean =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), "").contains("upgrade your town hall")

    /**
     * The price on a single-wall dialog, which never spells its currency out.
     *
     * The bulk dialog writes "for 4000000 Gold" and can be read by the currency's name. The
     * single-wall dialog writes "Upgrade Wall to Level 16?" and then puts the cost on the accept
     * button itself with the coin drawn as an icon, so there is no word to match and the only
     * other numbers on it are the hitpoints either side of an arrow and a Gold Pass discount
     * pitch. The cost is the number sharing a row with the button, which those others do not.
     */
    fun priceOnButton(boxes: List<RecognizedBox>, confirmBox: RecognizedBox?): Long? {
        if (confirmBox == null) return null
        val row = confirmBox.center.y
        return boxes
            .filter { kotlin.math.abs(it.center.y - row) <= 60 }
            .sortedBy { kotlin.math.abs(it.center.x - confirmBox.center.x) }
            .firstNotNullOfOrNull { embeddedPrice(it.text) }
    }

    /**
     * The price out of a button's text, whether or not the label came with it.
     *
     * The cost sits beside the word CONFIRM, and how ML Kit splits that row is a coin toss: the
     * wall phase of 19 September read the price as its own box three times and as part of
     * "CONFiRM S600 O00" five times, and the five were refused because the box was not a price
     * from end to end. Both spellings carry the same number, so the digits are taken out of
     * whatever the row says rather than insisted upon as the whole of it.
     *
     * A price is six to eight digits: wall levels cost millions, while the hitpoints and the
     * Gold Pass pitch elsewhere on the dialog are thousands, and the row filter keeps those off
     * this text anyway. Spacing between the digit groups is whatever survived binarisation.
     */
    fun embeddedPrice(text: String): Long? {
        val normalized = ScreenReader.normalizeAmountText(text)
        return PRICE_TEXT.findAll(normalized)
            .map { it.value.replace(" ", "") }
            .filter { it.length in 6..8 }
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0 }
            .toList()
            .singleOrNull()
    }
}
