package local.clashfarmer.log

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId

/**
 * The last lines of today's and yesterday's JSONL logs, for the Activity screen (spec 2.5), and the
 * merge of those lines with the live buffer.
 *
 * Each file is read backwards from its end in fixed chunks, so a multi-MB log never sits in memory
 * whole: only the lines kept, one chunk, and the line being assembled. A last line without its
 * newline is a write in progress (or a torn one) and is dropped; a missing file reads as empty.
 */
object LogTail {
    const val MAX_LINES = 3000
    private const val CHUNK = 64 * 1024
    /** A line longer than this is not a log event; it is skipped rather than buffered. */
    private const val MAX_LINE_BYTES = 512 * 1024
    private const val DAY_MS = 86_400_000L
    private const val NEWLINE = '\n'.code.toByte()

    /**
     * Up to [maxLines] non-blank lines, oldest first: today's tail, topped up from older files when
     * today has fewer. Files are named by UTC day ([DailyLogFile]); enough of them are read to cover
     * yesterday and today in [zone], which west of UTC takes three files in the evening.
     */
    fun read(dir: File, nowMs: Long, maxLines: Int = MAX_LINES, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val names = DailyLogFile(dir)
        val from = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val newestFirst = ArrayList<List<String>>()
        var rest = maxLines
        var at = nowMs
        while (rest > 0) {
            val part = tail(names.fileFor(at), rest)
            newestFirst += part
            rest -= part.size
            val dayStart = at - Math.floorMod(at, DAY_MS)
            if (dayStart <= from) break
            at = dayStart - 1
        }
        return newestFirst.asReversed().flatten()
    }

    /** The last [maxLines] complete, non-blank lines of [file], oldest first. */
    fun tail(file: File, maxLines: Int): List<String> {
        if (maxLines <= 0 || !file.isFile) return emptyList()
        val newestFirst = ArrayList<String>()
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pos = raf.length()
                val buf = ByteArray(CHUNK)
                // Bytes of the line being assembled, in reverse order (newest byte first).
                val pending = ByteArrayOutputStream()
                var overlong = false
                // Bytes after the file's last newline are an unfinished write, never a line.
                var sawNewline = false

                fun emit() {
                    if (sawNewline && !overlong) {
                        val bytes = pending.toByteArray().apply { reverse() }
                        val line = String(bytes, Charsets.UTF_8).trim()
                        if (line.isNotEmpty()) newestFirst += line
                    }
                    pending.reset()
                    overlong = false
                }

                while (pos > 0 && newestFirst.size < maxLines) {
                    val n = minOf(CHUNK.toLong(), pos).toInt()
                    pos -= n
                    raf.seek(pos)
                    raf.readFully(buf, 0, n)
                    for (i in n - 1 downTo 0) {
                        val b = buf[i]
                        if (b == NEWLINE) {
                            emit()
                            sawNewline = true
                            if (newestFirst.size >= maxLines) break
                        } else if (!overlong) {
                            if (pending.size() >= MAX_LINE_BYTES) { overlong = true; pending.reset() } else pending.write(b.toInt())
                        }
                    }
                }
                // The file's first line has no newline before it.
                if (pos == 0L && newestFirst.size < maxLines) emit()
            }
        }
        return newestFirst.asReversed()
    }

    /**
     * [tail] (parsed file lines, oldest first) followed by the [live] buffer's events that are not
     * already in it. The two overlap, and counting an event twice would double a problem's count,
     * so events are matched by their parsed text. Malformed and blank live lines are skipped.
     */
    fun merge(tail: List<JSONObject>, live: List<String>): List<JSONObject> {
        val seen = tail.mapTo(HashSet()) { it.toString() }
        val fresh = live.mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) null else runCatching { JSONObject(t) }.getOrNull()
        }.filter { seen.add(it.toString()) }
        return if (fresh.isEmpty()) tail else tail + fresh
    }
}
