package local.clashfarmer.ui.components

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small display formatters the shell and the wave-1 screens share. */
object UiText {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneId.systemDefault())
    private val stamp = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())

    fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    /** "6:51 AM". */
    fun clock(ms: Long): String = clock.format(Instant.ofEpochMilli(ms))

    /** "Oct 8, 6:51:46 AM", or "Time unavailable". */
    fun stamp(ms: Long?): String = ms?.let { stamp.format(Instant.ofEpochMilli(it)) } ?: "Time unavailable"

    /** "12 s ago", "4 min ago", then the clock time. */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> clock(ms)
        }
    }

    /** "04:12" under an hour, "1:04:12" over. */
    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
