package local.clashfarmer.log

import local.clashfarmer.insight.Timeline
import java.io.File

/**
 * How each session in the daily JSONL logs ended ("Done", "Time", "Stopped"), for the Stats tab and
 * the remote page's /api/stats. Both use [shared], so they give the same word for the same session.
 *
 * Every retained daily file is read, since a session in the 30-day window may have ended weeks ago,
 * but only the lines that open, restart, hand off or close a run are kept ([Timeline.RUN_EVENTS],
 * about 1% of a log). A file whose length has not changed is not read again, so after the first
 * call only today's growing file is, and nothing is regrouped while no file changed. [LogTail]
 * stays the reader for the Activity timeline and problems: its two-day tail is too short for this.
 */
class RunLog(private val dir: File) {
    /** File name to the length it was read at and its run lines. */
    private val files = HashMap<String, Pair<Long, List<String>>>()
    /** The file names and lengths [last] was built from; null when a file would not read. */
    private var builtFrom: List<Pair<String, Long>>? = null

    /** What the last [endings] call found, empty before the first. Reads no file, so any thread may ask. */
    @Volatile
    var last: Map<String, String> = emptyMap()
        private set

    /** Session id to its end word for every closed run the logs hold. Reads files: call it off the main thread. */
    @Synchronized
    fun endings(): Map<String, String> {
        val found = dir.listFiles { f -> NAME.matches(f.name) }.orEmpty().sortedBy { it.name }
        val lengths = found.map { it.name to it.length() }
        if (lengths == builtFrom) return last
        files.keys.retainAll(lengths.map { it.first }.toSet())
        var complete = true
        val lines = found.zip(lengths).flatMap { (file, entry) ->
            val length = entry.second
            files[file.name]?.takeIf { it.first == length }?.second
                // A file that will not read (pruned since the listing) costs its own words only, and is tried again next call.
                ?: runCatching { runLines(file) }.getOrNull()?.also { files[file.name] = length to it }
                ?: emptyList<String>().also { complete = false }
        }
        return endings(lines).also {
            last = it
            builtFrom = if (complete) lengths else null
        }
    }

    companion object {
        private val NAME = Regex("""clashfarmer-\d{8}\.jsonl""")
        private const val EVENT_KEY = "\"event\":\""
        private var shared: RunLog? = null

        /** The process's one reader for [dir], so Stats and the remote page share its cache. */
        @Synchronized
        fun shared(dir: File): RunLog = shared?.takeIf { it.dir == dir } ?: RunLog(dir).also { shared = it }

        /** Whether a raw JSONL log line is one of [Timeline.RUN_EVENTS], found without parsing it. */
        fun isRunLine(line: String): Boolean {
            val at = line.indexOf(EVENT_KEY)
            if (at < 0) return false
            val start = at + EVENT_KEY.length
            val end = line.indexOf('"', start)
            return end > start && line.substring(start, end) in Timeline.RUN_EVENTS
        }

        /** Session id to end word from raw log lines, oldest first; lines that are not run lines are skipped. */
        fun endings(lines: List<String>): Map<String, String> =
            Timeline.endings(Timeline.group(Timeline.parse(lines.filter { isRunLine(it) })))

        private fun runLines(file: File): List<String> = file.useLines { lines -> lines.filter { isRunLine(it) }.toList() }
    }
}
