package local.clashfarmer.core

/**
 * The "Attack available in: 1m 15s" line the attack menu shows after the game reloads, for
 * example when another device connects. Find a Match does nothing until it clears, so tapping
 * it timed out, auto-restarted and recovered in a loop until the restarts ran out (Blood,
 * 2 October 16:26-16:28).
 */
object MultiplayerCooldown {
    /** How long a cooldown is waited out before the run gives up on it. */
    const val MAX_WAIT_MS = 300_000L

    /** Between readings of the menu while it waits. */
    const val POLL_MS = 3_000L

    // Read as "Attack available in", "Atack available in", "avaiiable in". The star bonus line
    // says "Next in", which this does not match.
    private val LINE = Regex("""ava[il1|]{1,3}ab[il1|]e\s*[il1|]n""", RegexOption.IGNORE_CASE)

    fun showing(text: String): Boolean = LINE.containsMatchIn(text)
}
