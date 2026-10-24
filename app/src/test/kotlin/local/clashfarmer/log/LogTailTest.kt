package local.clashfarmer.log

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

class LogTailTest {
    @get:Rule val temp = TemporaryFolder()

    // 2026-10-08T12:00:00Z; yesterday's file is clashfarmer-20261007.jsonl.
    private val now = 1_791_417_600_000L + 12 * 3_600_000L

    private fun line(i: Int) = """{"time":"t$i","event":"e$i"}"""
    private fun dir() = temp.root
    private fun today() = File(dir(), "clashfarmer-20261008.jsonl")
    private fun yesterday() = File(dir(), "clashfarmer-20261007.jsonl")

    @Test fun aTornLastLineIsDropped() {
        today().writeText(line(1) + "\n" + line(2) + "\n" + """{"time":"t3","ev""")
        assertEquals(listOf(line(1), line(2)), LogTail.read(dir(), now))
    }

    @Test fun aFileWithOnlyATornLineReadsEmpty() {
        today().writeText("""{"time":"t1","ev""")
        assertEquals(emptyList<String>(), LogTail.read(dir(), now))
    }

    @Test fun aMissingYesterdayFileReadsTodayAlone() {
        today().writeText(line(1) + "\n" + line(2) + "\n")
        assertEquals(listOf(line(1), line(2)), LogTail.read(dir(), now))
    }

    @Test fun bothFilesMissingReadEmpty() {
        assertEquals(emptyList<String>(), LogTail.read(dir(), now))
    }

    @Test fun yesterdayComesFirstAndTopsUpToday() {
        yesterday().writeText((1..3).joinToString("") { line(it) + "\n" })
        today().writeText((4..5).joinToString("") { line(it) + "\n" })
        assertEquals((1..5).map { line(it) }, LogTail.read(dir(), now))
        assertEquals((3..5).map { line(it) }, LogTail.read(dir(), now, maxLines = 3))
    }

    @Test fun theCapKeepsTheNewest3000Lines() {
        yesterday().writeText((1..500).joinToString("") { line(it) + "\n" })
        // Long enough to span many 64 KB chunks.
        today().bufferedWriter().use { w -> (1001..5000).forEach { w.write(line(it)); w.write("\n") } }
        val got = LogTail.read(dir(), now)
        assertEquals(LogTail.MAX_LINES, got.size)
        assertEquals(line(2001), got.first())
        assertEquals(line(5000), got.last())
        assertEquals((2001..5000).map { line(it) }, got)
    }

    @Test fun theCapSpillsIntoYesterdayInOrder() {
        yesterday().writeText((1..2000).joinToString("") { line(it) + "\n" })
        today().writeText((3001..4000).joinToString("") { line(it) + "\n" })
        val got = LogTail.read(dir(), now)
        assertEquals(3000, got.size)
        assertEquals((1..2000).map { line(it) } + (3001..4000).map { line(it) }, got)
    }

    @Test fun westOfUtcTheEveningReadsLocalYesterdayFromAThirdFile() {
        // 2026-10-09T01:00:00Z is 21:00 on Oct 8 in New York, whose Oct 7 began at 04:00 UTC on Oct 7.
        val evening = now + 13 * 3_600_000L
        File(dir(), "clashfarmer-20261006.jsonl").writeText(line(0) + "\n")
        yesterday().writeText(line(1) + "\n")
        today().writeText(line(2) + "\n")
        File(dir(), "clashfarmer-20261009.jsonl").writeText(line(3) + "\n")
        val newYork = ZoneId.of("America/New_York")
        assertEquals((1..3).map { line(it) }, LogTail.read(dir(), evening, zone = newYork))
        assertEquals((2..3).map { line(it) }, LogTail.read(dir(), evening, maxLines = 2, zone = newYork))
        // In UTC the same moment is 01:00 on Oct 9, so yesterday is the Oct 8 file.
        assertEquals((2..3).map { line(it) }, LogTail.read(dir(), evening, zone = ZoneOffset.UTC))
    }

    @Test fun blankLinesAndCarriageReturnsAreSkipped() {
        today().writeText(line(1) + "\r\n\n\n" + line(2) + "\n")
        assertEquals(listOf(line(1), line(2)), LogTail.read(dir(), now))
    }

    @Test fun mergeAddsOnlyLiveEventsTheTailLacks() {
        val tail = listOf(JSONObject(line(1)), JSONObject(line(2)))
        // The live buffer repeats the tail's newest lines, plus one torn and one blank line.
        val got = LogTail.merge(tail, listOf(line(2), line(3), "", """{"time":"t4","ev""", line(3), line(4)))
        assertEquals(listOf("e1", "e2", "e3", "e4"), got.map { it.getString("event") })
        assertTrue(LogTail.merge(tail, listOf(line(1), line(2))) === tail)
    }

    @Test fun multiByteTextAcrossAChunkBoundarySurvives() {
        // The trailing x puts each 64 KB chunk boundary inside a two-byte character.
        val wide = """{"time":"t","event":"e","note":"${"é·".repeat(40_000)}x"}"""
        today().writeText(line(1) + "\n" + wide + "\n" + line(2) + "\n")
        val bytes = today().readBytes()
        assertEquals(0x80, bytes[bytes.size - 64 * 1024].toInt() and 0xC0)
        val got = LogTail.read(dir(), now)
        assertEquals(listOf(line(1), wide, line(2)), got)
        assertTrue(got[1].endsWith("\"}"))
    }
}
