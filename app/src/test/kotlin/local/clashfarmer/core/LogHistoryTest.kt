package local.clashfarmer.core

import local.clashfarmer.log.replayRecentHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun lastSessionSurvivesNewDayWithOnlyHelperEvents() {
        val old = folder.newFile("clashfarmer-20260917.jsonl").apply { writeText("""
            {"event":"session_started","time":"2026-09-17T12:00:00.000Z","maxAttacks":15}
            {"event":"session_finished","time":"2026-09-17T12:02:00.000Z","completed":15}
        """.trimIndent()) }
        val today = folder.newFile("clashfarmer-20260918.jsonl").apply { writeText("""
            {"event":"main_activity_opened","time":"2026-09-18T12:00:00.000Z"}
            malformed line
        """.trimIndent()) }
        val summary = SessionSummary()
        replayRecentHistory(listOf(today, old), summary::accept)
        assertEquals(15, summary.snapshot.completed)
        assertEquals("Complete", summary.snapshot.phase)
    }

    @Test fun newestSessionDoesNotRequireReplayingOldRuns() {
        val old = folder.newFile("clashfarmer-20260917.jsonl").apply { writeText("""{"event":"old_event"}""") }
        val today = folder.newFile("clashfarmer-20260918.jsonl").apply { writeText("""{"event":"session_started"}""") }
        val events = mutableListOf<String>()
        replayRecentHistory(listOf(old, today)) { events.add(it.optString("event")) }
        assertEquals(listOf("session_started"), events)
    }
}
