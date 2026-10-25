package local.clashfarmer.recognition

import java.util.Locale

/**
 * Reads "Overall Damage 52%" from a full-screen battle reading.
 *
 * From the runs of 19 to 29 September the words come back as "OveRall Damage", "DaMage",
 * "Damagge" or "Damage.", and the digits as "S2%" for 52%, "SO%" for 50%, "G0%" for 60% and "iOO%"
 * for 100%; a five also comes back as "$" ("$3%" for 53%, from 22 September on). The sign is
 * sometimes read as a stray digit. Two digits and then "9%" is the one such shape the logs settle:
 * on 14 October "S29%" stood on fourteen frames between reads of 52 in a battle that ended at 52%,
 * and none of the 36 frames of this shape logged unread on 8 and 14 October disagrees with the
 * reads around it. So that 9 is dropped.
 * Any other stray digit ("532%", "9896"), and any number without its sign ("S26", "486"), is
 * taken as unread rather than guessed at.
 *
 * "Overall" itself came back as "OveRalI", "OveRal", "Overal", "OveRallI", "OveRaIl", "OveRalW"
 * and a dozen more on 273 of 1358 battle frames from 18 to 24 September, which lost each of them
 * its damage. So the word is matched by its "ove" start and length, and the number after it is
 * held to the same rules as before.
 *
 * Since Clash 18.647 a hero card's level badge can stand between the word and the value
 * ("OveRall Damage 100 34% 100 78" for 34%, on 504 of the 607 frames the 2-hour run of
 * 14 October logged unread), so one standalone token of two or three plain digits there, the
 * length of a hero level, is skipped. A single digit may be the front of a split value ("5 2%"
 * for 52%) and a token with letters may be the value itself, so neither is skipped.
 *
 * The PC's recogniser puts the battle button's label, "SURReNdeR" before the first star and "End
 * Battle" after it, between the word and the value ("OveRall Damage End Battle 68%" on all nine
 * damage readings of Blood's supervised attack of 24 October, none of them read), so that label is
 * skipped too: a word of seven to ten letters starting with s, or one starting with e and then one
 * starting with b.
 *
 * The stars beside the value come back as a lone "e", "d" or "*" on many PC frames, before the
 * label or after it ("Overall Damage d End Battle * 87%", "SURReNdeR e 44%"). On Blood's unattended
 * run of 25 October that left 18 of 336 samples unread, twelve of them in a row in one battle, and
 * the stall watcher took a battle still climbing from 79% to 93% for a stall. So up to two such
 * one-character tokens are skipped on each side of the label. A token that could be a digit (0-9,
 * the lookalikes read as digits above, b for 8) or the sign itself is never skipped, so a stray
 * can neither supply the value nor hide part of it.
 */
object BattleDamage {

    /** One stray one-character token that is no digit, lookalike or sign: up to two of them. */
    private const val STRAY = "(?:\\s+[^\\s0-9soilzgb\$%](?=\\s)){0,2}"

    private val OVERALL =
        Regex("(?<![a-z])ove[a-z]{2,5}[,.]?\\s*d[a-z]{0,7}?\\.?$STRAY(?:\\s+(?:s[a-z]{6,9}|e[a-z]{1,3}\\s*b[a-z]{4,6}))?$STRAY(?:\\s+[0-9]{2,3}(?=\\s))?\\s*([0-9soilzg\$]{1,3})%")

    /**
     * Whether [rawText] is the result screen sweeping in over the battle: its "Total damage: 0%"
     * counts up from zero, so it is no battle damage and no reader miss either (one frame each on
     * 25 October, "Total damage: 0% 174 833 VictORY End Battle").
     */
    fun resultWipe(rawText: String): Boolean = rawText.lowercase(Locale.ROOT).contains("total damage")

    fun overallPercent(rawText: String): Int? {
        val match = OVERALL.find(rawText.lowercase(Locale.ROOT)) ?: return null
        val digits = match.groupValues[1]
            .replace('s', '5').replace('o', '0').replace('i', '1').replace('l', '1')
            .replace('z', '2').replace('g', '6').replace('$', '5')
        val value = when {
            digits.length < 3 || digits == "100" -> digits
            digits[2] == '9' && digits[0] != '0' -> digits.take(2)
            else -> return null
        }
        return value.toIntOrNull()?.takeIf { it in 0..100 }
    }
}
