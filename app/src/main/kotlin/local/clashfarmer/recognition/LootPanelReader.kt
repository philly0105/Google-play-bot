package local.clashfarmer.recognition

import local.clashfarmer.core.Resource
import java.util.Locale

/**
 * Reads the scout screen's Available Loot panel, its countdown and the price of Next, from the
 * text several binarisation cuts of each calibrated row returned. Only these rows are read: a
 * number elsewhere on the scout - a storage bar, a base name - is never taken for loot.
 *
 * This is the loot a base offers, not what an attack earned; the result screen's earned loot is
 * a different reading and must stay one.
 */
object LootPanelReader {

    /** The scout counts down from thirty seconds. */
    private const val SCOUT_SECONDS = 30

    /** Next costs a few thousand gold at most; anything longer is the coin read as a digit. */
    private const val MAX_FEE_DIGITS = 5

    private fun digitsOf(raw: String): String =
        ScreenReader.normalizeAmountText(raw).replace(Regex("[^0-9]"), "")

    /**
     * A one in this font is a bare upright, and on these rows the recogniser returns it as "|",
     * "]" or "[" about as often as "1": 11 657 dark elixir came back as "1| 657" on 8 October.
     * The amount repairs strip those marks, which dropped the one and left no two cuts agreeing.
     */
    private val BAR_ONE = Regex("[|\\[\\]]")

    /**
     * This font's five comes back as "$" as well as "S": Sweat's 506 933 scout gold read
     * "$06 933" and "S06 933" on 14 October, and the two never agreed.
     */
    private const val DOLLAR_FIVE = '$'

    /** One loot row, when two cuts agree. Unread is null, never zero; an empty storage is "0". */
    fun lootAmount(rawReadings: List<String>): Long? {
        // A cut of nothing but a bar is no reading: one read just "|" on 5 October.
        val digits = rawReadings.filter { r -> r.any { it.isLetterOrDigit() } }
            .map { ScreenReader.normalizeAmountText(ScreenReader.leadingOne(it).replace(BAR_ONE, "1").replace(DOLLAR_FIVE, '5')) }
            .filter(::drawable)
            .map { it.replace(Regex("[^0-9]"), "") }
        val agreed = ScreenReader.agreedBalance(digits) ?: return null
        // Adjacent ones glue into one glyph at most cuts, so two cuts can agree on a number one
        // short of a one that a third cut still shows. Such a row is left unread.
        return agreed.takeUnless { digits.any { onesAdded(it, agreed.toString()) } }
    }

    /**
     * Whether a cut's text is a number Clash could draw: every group after the first has three
     * digits, and it starts with a zero only if it is zero. A cut that gained or lost a glyph is
     * neither, and two such cuts can agree with each other:
     * - The 18.647 one's flag can come apart into a second glyph. Sweat's 628 916 gold read
     *   "628 91I6" from one cut of four on 14 October, and 553 653 read "S53 65S3" from two cuts
     *   of four on 8 October, which by length would outvote the true value.
     * - A lone leading one can vanish, taking its group with it: 1 014 652 read "O14 652" on 3
     *   October, and Sweat's 1 089 398 gold earned on 14 October was recorded as 89 398. A lone
     *   surviving "000" group is a lost number, not an empty storage.
     * - A letter the repairs could not turn into a digit stood for one, and dropping it loses that
     *   digit: Sweat's 118 375 scout elixir read "W8 375", "W8 375*" and "W8 37S" from three of
     *   eighteen cuts on 14 October, which agree on 8 375. No such cut in the logs or that day's probe carried a
     *   right value that a clean cut did not.
     */
    private fun drawable(text: String): Boolean {
        if (text.any(Char::isLetter)) return false
        val groups = text.split(Regex("\\s+")).map { it.replace(Regex("[^0-9]"), "") }.filter { it.isNotEmpty() }
        val digits = groups.joinToString("")
        return digits.isNotEmpty() && (digits.length == 1 || digits[0] != '0') && groups.drop(1).all { it.length == 3 }
    }

    /** Whether [longer] is [base] with one or more ones put in and nothing else changed. */
    private fun onesAdded(longer: String, base: String): Boolean {
        if (longer.length <= base.length) return false
        var i = 0
        for (c in longer) {
            if (i < base.length && c == base[i]) i++
            else if (c != '1') return false
        }
        return i == base.length
    }

    /**
     * Seconds left on the scout, when two cuts agree.
     *
     * The game always draws the "s", and the recogniser mostly returns it as a 5: "29s" came back
     * as "295" from nine cuts in twelve on 24 September. So the last character is taken for the
     * mark and dropped, whatever it reads as. If the mark itself was lost, a digit goes instead
     * and the countdown reads short - which only ever deploys sooner, never presses Next late.
     */
    fun countdownSeconds(rawReadings: List<String>): Int? {
        val seconds = rawReadings.mapNotNull { raw ->
            val text = raw.lowercase(Locale.ROOT).replace('o', '0').replace(Regex("[^0-9s]"), "")
            Regex("^(\\d{1,2})[s5]$").find(text)?.groupValues?.get(1)?.toInt()
        }.filter { it in 0..SCOUT_SECONDS }
        return agreed(seconds)
    }

    /** What pressing Next costs, when two cuts agree on the same positive number. */
    fun nextFee(rawReadings: List<String>): Long? {
        val fees = rawReadings.map(::digitsOf)
            .filter { it.length in 1..MAX_FEE_DIGITS }
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
        return agreed(fees)
    }

    /**
     * Whether two scout readings show different bases: some row read on both sides and differs.
     * Rows unread on either side prove nothing either way.
     */
    fun differentBase(before: Map<Resource, Long>, after: Map<Resource, Long>): Boolean =
        before.keys.intersect(after.keys).any { before[it] != after[it] }

    private fun <T> agreed(values: List<T>): T? =
        values.groupingBy { it }.eachCount().filterValues { it >= 2 }.maxByOrNull { it.value }?.key
}
