package local.clashfarmer.log

import local.clashfarmer.insight.LogFixtures
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.insight.LogFixtures.event
import local.clashfarmer.insight.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunLogTest {
    @get:Rule val temp = TemporaryFolder()

    private fun started(time: String, account: String, id: String, session: String) =
        event(time, "session_started", "accountId" to id, "accountName" to account, "maxAttacks" to 40, "sessionId" to session).toString()

    private fun finished(time: String, reason: String) = event(time, "session_finished", "completed" to 3, "reason" to reason).toString()
    private fun attack(time: String, n: Int) = event(time, "attack_completed", "completed" to n, "limit" to 40).toString()

    private val s12 = listOf(
        started("2026-10-05T11:59:00Z", "Sweat", SWEAT, "s12"),
        attack("2026-10-05T12:20:00Z", 1),
        finished("2026-10-05T13:01:00Z", "time_limit")
    )

    /** Every case the timeline tests group, as raw log lines, plus chatter and a failure snapshot. */
    private val cases = listOf(
        LogFixtures.SMOKE_RUN,
        s12,
        listOf(
            event("2026-10-08T06:00:00Z", "combined_run_started", "firstAccount" to "Sweat", "firstQuota" to 2,
                "secondAccount" to "Blood", "secondQuota" to 2, "globalDeadlineMinutes" to 120).toString(),
            started("2026-10-08T06:01:00Z", "Sweat", SWEAT, "a1"),
            attack("2026-10-08T06:09:00Z", 2),
            finished("2026-10-08T06:09:10Z", "quota_reached"),
            event("2026-10-08T06:09:11Z", "account_handoff", "from" to "Sweat", "to" to "Blood", "completed" to 2, "reason" to "quota_reached").toString(),
            LogFixtures.REAL.getValue("account_switch_verified"),
            started("2026-10-08T06:10:30Z", "Blood", BLOOD, "b1"),
            finished("2026-10-08T06:20:00Z", "time_limit"),
            event("2026-10-08T06:20:01Z", "combined_run_completed", "firstCompleted" to 2, "firstReason" to "quota_reached",
                "secondCompleted" to 1, "secondReason" to "time_limit").toString()
        ),
        listOf(
            started("2026-10-08T05:40:00Z", "Blood", BLOOD, "r1"),
            LogFixtures.REAL.getValue("run_auto_restart"),
            LogFixtures.REAL.getValue("run_recovered"),
            started("2026-10-08T05:48:00Z", "Blood", BLOOD, "r2"),
            finished("2026-10-08T06:00:00Z", "time_limit")
        ),
        listOf(
            started("2026-10-08T06:51:00Z", "Sweat", SWEAT, "f1"),
            // Written with "event":"run_stopped", so it passes the raw filter; parsing names it a snapshot.
            LogFixtures.SHAPED.getValue("failure_snapshot"),
            LogFixtures.REAL.getValue("run_paused"),
            event("2026-10-08T07:10:00Z", "run_stopped", "reason" to "Stopped from floating panel").toString(),
            "not json",
            """{"time":"2026-10-08T07:11:00Z","event":"session_fin"""
        ),
        listOf(started("2026-10-08T07:20:00Z", "Sweat", SWEAT, "o1"), attack("2026-10-08T07:24:00Z", 1))
    )

    @Test
    fun runLinesGiveTheEndingsTheWholeLogGives() {
        // All cases in one log too: their Oct 8 clock times interleave, and the grouping still agrees.
        for (lines in cases + listOf(cases.flatten())) {
            assertEquals(Timeline.endings(Timeline.group(Timeline.parse(lines))), RunLog.endings(lines))
        }
        // Not equal by both being empty: each case has its words, and the open run has none.
        fun words(case: Int, vararg ids: String) = RunLog.endings(cases[case]).let { found -> ids.map { found[it] } }
        assertEquals(listOf("Full"), words(0, "0c9e9bf1-ef3b-496a-af95-e9821475f22a"))
        assertEquals(listOf("Time"), words(1, "s12"))
        assertEquals(listOf("Time"), words(2, "b1"))
        assertEquals(listOf("Time", "Time"), words(3, "r1", "r2"))
        assertEquals(listOf("Stopped"), words(4, "f1"))
        assertTrue(RunLog.endings(cases[5]).isEmpty())
    }

    @Test
    fun aRunLineIsFoundByItsEventNameAlone() {
        assertTrue(RunLog.isRunLine(s12[0]))
        assertFalse(RunLog.isRunLine(s12[1]))
        assertFalse(RunLog.isRunLine("not json"))
        assertFalse(RunLog.isRunLine("""{"time":"t","event":"session_fin"""))
        assertFalse(RunLog.isRunLine("""{"time":"t","prevent":"session_started"}"""))
        for (name in Timeline.RUN_EVENTS) assertTrue(name, RunLog.isRunLine(event("t", name).toString()))
    }

    private fun file(day: String) = File(temp.root, "clashfarmer-$day.jsonl")

    @Test
    fun anUnchangedFileIsServedFromItsCache() {
        file("20261005").writeText(s12.joinToString("\n", postfix = "\n"))
        val log = RunLog(temp.root)
        assertEquals(mapOf("s12" to "Time"), log.endings())
        // Same length, different session: a cached file is not read again.
        file("20261005").writeText(s12.joinToString("\n", postfix = "\n").replace("s12", "s99"))
        assertEquals(mapOf("s12" to "Time"), log.endings())
        assertEquals(mapOf("s12" to "Time"), log.last)
    }

    @Test
    fun aGrownFileIsReadAgain() {
        file("20261008").writeText(started("2026-10-08T06:51:00Z", "Sweat", SWEAT, "s15") + "\n")
        val log = RunLog(temp.root)
        assertEquals(emptyMap<String, String>(), log.endings())
        file("20261008").appendText(event("2026-10-08T06:58:00Z", "run_stopped", "reason" to "Stop pressed in the app").toString() + "\n")
        assertEquals(mapOf("s15" to "Stopped"), log.endings())
    }

    @Test
    fun aDeletedFileIsDroppedAndOtherNamesAreIgnored() {
        file("20261005").writeText(s12.joinToString("\n", postfix = "\n"))
        File(temp.root, "clashfarmer-20261006.jsonl.bak").writeText(cases[3].joinToString("\n"))
        File(temp.root, "notes.jsonl").writeText(cases[3].joinToString("\n"))
        val log = RunLog(temp.root)
        assertEquals(mapOf("s12" to "Time"), log.endings())
        assertTrue(file("20261005").delete())
        assertEquals(emptyMap<String, String>(), log.endings())
    }

    @Test
    fun filesAreReadOldestFirstByName() {
        // A run that crosses UTC midnight: started in one file, finished in the next.
        file("20261009").writeText(finished("2026-10-09T00:20:00Z", "quota_reached") + "\n")
        file("20261008").writeText(started("2026-10-08T23:50:00Z", "Blood", BLOOD, "m1") + "\n")
        assertEquals(mapOf("m1" to "Done"), RunLog(temp.root).endings())
    }

    @Test
    fun oneReaderPerDirectory() {
        val a = RunLog.shared(temp.root)
        assertSame(a, RunLog.shared(File(temp.root.path)))
        val other = temp.newFolder("other")
        val b = RunLog.shared(other)
        assertTrue(a !== b)
        assertSame(b, RunLog.shared(other))
    }
}
