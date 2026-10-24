package local.clashfarmer.log

import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Names the JSONL file for an event's UTC date, so a service that stays up across midnight starts
 * a new file instead of growing one file over several days. The day's bounds are cached, so a write
 * on the same day costs two long comparisons. Not thread safe; AppLog calls it under its own lock.
 */
class DailyLogFile(private val dir: File) {
    private var dayStart = Long.MAX_VALUE
    private var dayEnd = Long.MIN_VALUE
    private var file: File? = null

    fun fileFor(millis: Long): File {
        val cached = file
        if (cached != null && millis >= dayStart && millis < dayEnd) return cached
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        dayStart = calendar.timeInMillis
        val name = String.format(
            Locale.US, "clashfarmer-%04d%02d%02d.jsonl",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
        )
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        dayEnd = calendar.timeInMillis
        return File(dir, name).also { file = it }
    }
}
