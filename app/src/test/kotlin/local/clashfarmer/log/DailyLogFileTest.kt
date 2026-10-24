package local.clashfarmer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class DailyLogFileTest {
    private val dir = File("logs")

    // 2026-10-07T23:59:59.999Z and 2026-10-08T00:00:00.000Z
    private val beforeMidnight = 1_791_417_599_999L
    private val atMidnight = 1_791_417_600_000L

    @Test fun eventsOnTheSameUtcDayShareOneFile() {
        val daily = DailyLogFile(dir)
        val first = daily.fileFor(atMidnight)
        val later = daily.fileFor(atMidnight + 23 * 3_600_000L)
        assertEquals("clashfarmer-20261008.jsonl", first.name)
        assertSame(first, later)
    }

    @Test fun crossingUtcMidnightPicksTheNextDaysFile() {
        val daily = DailyLogFile(dir)
        assertEquals("clashfarmer-20261007.jsonl", daily.fileFor(beforeMidnight).name)
        assertEquals("clashfarmer-20261008.jsonl", daily.fileFor(atMidnight).name)
        assertEquals(dir, daily.fileFor(atMidnight).parentFile)
    }

    @Test fun aClockThatStepsBackwardReturnsToTheEarlierDay() {
        val daily = DailyLogFile(dir)
        daily.fileFor(atMidnight)
        assertEquals("clashfarmer-20261007.jsonl", daily.fileFor(beforeMidnight).name)
    }
}
