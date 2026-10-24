package local.clashfarmer.insight

import local.clashfarmer.core.Resource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a line or a chip should read: green OK, amber attention, red needs you, grey idle or unknown. */
enum class Tone { OK, WARN, BAD, IDLE, NEUTRAL }

/**
 * Every number and time the screens print, in one place so the app and the remote page agree.
 * Anything not read is [UNKNOWN], never zero. English digits and month names whatever the locale.
 */
object Format {
    const val UNKNOWN = "-"

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_HHMM = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** 950, 2.1k, 412k, 5.1M, 29.0M, 1.42B. */
    fun amount(value: Long?): String {
        if (value == null) return UNKNOWN
        val sign = if (value < 0) "-" else ""
        val v = Math.abs(value)
        val body = when {
            v < 1_000 -> v.toString()
            v < 9_950 -> String.format(Locale.US, "%.1fk", v / 1_000.0)
            v < 999_500 -> "${(v + 500) / 1_000}k"
            v < 999_950_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
            else -> String.format(Locale.US, "%.2fB", v / 1_000_000_000.0)
        }
        return sign + body
    }

    /** +412k for a gain; a loss keeps its minus sign. */
    fun signed(value: Long?): String = when {
        value == null -> UNKNOWN
        value >= 0 -> "+" + amount(value)
        else -> amount(value)
    }

    /** 17.8M/h, or [UNKNOWN] when there is nothing to divide. */
    fun rate(perHour: Long?): String = if (perHour == null) UNKNOWN else amount(perHour) + "/h"

    /** 29.0M / 29.0M; a missing side is [UNKNOWN]. */
    fun ofCapacity(value: Long?, capacity: Long?): String = "${amount(value)} / ${amount(capacity)}"

    /** Whole percent of [whole], clamped to 0..100; null when either side is unknown or [whole] is not positive. */
    fun percentOf(part: Long?, whole: Long?): Int? {
        if (part == null || whole == null || whole <= 0) return null
        return (part * 100 / whole).coerceIn(0, 100).toInt()
    }

    fun resourceName(resource: Resource): String = when (resource) {
        Resource.GOLD -> "Gold"
        Resource.ELIXIR -> "Elixir"
        Resource.DARK_ELIXIR -> "Dark elixir"
    }

    /** "1 battle", "12 battles". */
    fun count(n: Int, noun: String, plural: String = noun + "s"): String = "$n ${if (n == 1) noun else plural}"

    /** "12 of 40 attacks"; "12 attacks" when there is no limit. */
    fun attacks(completed: Int, limit: Int?): String =
        if (limit == null || limit <= 0) count(completed, "attack") else "$completed of $limit attacks"

    /** 1:12:05 under a run, or 12:05 below an hour. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** 1h04 for a session's length; 42 min below an hour. */
    fun span(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) String.format(Locale.US, "%dh%02d", m / 60, m % 60) else "$m min"
    }

    /** Farming hours: 0.5 h, 9.5 h, 31 h. */
    fun hours(ms: Long): String {
        val h = ms.coerceAtLeast(0) / 3_600_000.0
        return if (h < 10) String.format(Locale.US, "%.1f h", h) else "${Math.round(h)} h"
    }

    /** A limit in minutes: 45 min, 2 h, 1 h 30 min. */
    fun minutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    fun time(ms: Long?, zone: ZoneId): String = ms?.let { HHMM.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: UNKNOWN

    /** Oct 8 06:51. */
    fun dayTime(ms: Long?, zone: ZoneId): String = ms?.let { DAY_HHMM.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: UNKNOWN

    fun day(ms: Long?, zone: ZoneId): String = ms?.let { DAY.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: UNKNOWN

    /** 06:51-07:40; an open end reads "06:51-now". */
    fun timeRange(startMs: Long?, endMs: Long?, zone: ZoneId): String =
        "${time(startMs, zone)}-${if (endMs == null) "now" else time(endMs, zone)}"

    /** 2 s ago, 6 min ago, 2 h ago, 3 d ago. A future time reads "just now". */
    fun ago(ms: Long?, nowMs: Long): String {
        if (ms == null) return UNKNOWN
        val s = (nowMs - ms) / 1000
        return when {
            s < 1 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** Parses a log line's ISO time; null when absent or malformed. */
    fun parseTime(iso: String?): Long? =
        if (iso.isNullOrBlank()) null else runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /**
     * Parses the log's loot text, "gold=681488,elixir=372373,dark_elixir=9060". An empty text is an
     * empty map: nothing was read. Unknown names and bad numbers are skipped.
     */
    fun parseLoot(text: String?): Map<Resource, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<Resource, Long>()
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val resource = Resource.fromKey(part.substring(0, eq).trim()) ?: continue
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            out[resource] = value
        }
        return out
    }
}
