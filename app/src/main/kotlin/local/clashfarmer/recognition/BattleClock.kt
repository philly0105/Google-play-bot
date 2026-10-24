package local.clashfarmer.recognition

import java.util.Locale

/**
 * Reads "Battle ends in: 2m 48s" from a full-screen reading.
 *
 * The recogniser rarely returns it clean. From the runs of 21 to 28 September: "2M 295" for 2m 29s,
 * "2M255", "ZM 315", "IM 595" for 1m 59s, "IM S25" for 1m 52s, and "S05" for 50s once under a
 * minute. As on the scout, the last character of the seconds is the "s" mark and is dropped; a
 * reading whose mark is not an s or a 5 is taken as unread rather than guessed at.
 */
object BattleClock {

    private const val BATTLE_SECONDS = 180

    /** How far past "battle ends" the clock is looked for; the next word on screen is further. */
    private const val TAIL = 18

    private val HEADER = Regex("battle\\s*ends")
    private val MINUTES = Regex("(?<![a-z0-9])([0-9izl])\\s*m\\s*([0-9so]{1,2}[s5])(?![a-z0-9])")
    private val SECONDS = Regex("(?<![a-z0-9])([0-9so]{1,2}[s5])(?![a-z0-9])")

    fun secondsLeft(rawText: String): Int? {
        val lower = rawText.lowercase(Locale.ROOT)
        val header = HEADER.find(lower) ?: return null
        val tail = lower.substring(header.range.last + 1).take(TAIL)
        val total = MINUTES.find(tail)?.let { m ->
            val minutes = digits(m.groupValues[1]).toIntOrNull() ?: return null
            val seconds = seconds(m.groupValues[2]) ?: return null
            minutes * 60 + seconds
        } ?: SECONDS.find(tail)?.let { s ->
            if (s.groupValues[1].none { it.isDigit() }) return null
            seconds(s.groupValues[1]) ?: return null
        } ?: return null
        return total.takeIf { it in 0..BATTLE_SECONDS }
    }

    private fun digits(text: String): String = text
        .replace('i', '1').replace('l', '1').replace('z', '2')
        .replace('s', '5').replace('o', '0')

    /** The seconds with their mark dropped, when under a minute. */
    private fun seconds(withMark: String): Int? =
        digits(withMark.dropLast(1)).toIntOrNull()?.takeIf { it in 0..59 }
}
