package local.clashfarmer.insight

import local.clashfarmer.core.Resource
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.insight.LogFixtures.event
import local.clashfarmer.log.RunLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TimelineTest {
    private val utc = ZoneOffset.UTC

    private fun started(time: String, account: String, id: String, session: String, attacks: Int = 40, walls: Boolean = true) =
        event(time, "session_started", "accountId" to id, "accountName" to account, "supervised" to false, "walls" to false,
            "wallsEnabled" to walls, "maxAttacks" to attacks, "maxMinutes" to 120, "sessionId" to session)

    private fun attack(time: String, n: Int, limit: Int = 40) = event(time, "attack_completed", "completed" to n, "limit" to limit)
    private fun result(time: String, earned: String) = event(time, "battle_result", "earned" to earned, "stars" to 2, "damagePercent" to 71)

    @Test
    fun realRunIsOneGroupWithItsSwitchAndItsNotification() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.SMOKE_RUN))
        assertEquals(1, groups.size)
        val run = groups.single()
        assertEquals(RunGroup.Kind.RUN, run.kind)
        assertEquals("Sweat · Farm 40 + walls · 19:58-20:00 · Storage full", run.header(utc))
        assertEquals("1 of 40 attacks · +560k gold · +657k elixir", run.summary())
        assertEquals(Tone.WARN, run.tone)
        assertEquals(listOf("0c9e9bf1-ef3b-496a-af95-e9821475f22a"), run.sessionIds)
        val texts = run.lines.map { it.text }
        // Newest first; the switch logged before session_started and the notification after it are in.
        assertEquals("Notification sent: Sweat: Run finished", texts.first())
        assertTrue(texts.contains("Switched to Sweat"))
        assertTrue(texts.contains("Attack 1 done: +560k gold, +657k elixir, +5.5k dark elixir"))
        assertEquals("Run started: 40 attacks, walls on", texts[texts.size - 2])
        assertEquals("Switched to Sweat", texts.last())
        assertFalse(texts.any { it.contains('_') })
        assertEquals(mapOf("0c9e9bf1-ef3b-496a-af95-e9821475f22a" to "Full"), Timeline.endings(groups))
    }

    @Test
    fun splitsOnSessionStarted() {
        val events = listOf(
            started("2026-10-08T06:51:00Z", "Sweat", SWEAT, "s1"),
            result("2026-10-08T06:55:00Z", "gold=412000,elixir=398000"),
            attack("2026-10-08T06:55:05Z", 1),
            event("2026-10-08T07:00:00Z", "run_stopped", "reason" to "Stopped from floating panel"),
            started("2026-10-08T07:10:00Z", "Blood", BLOOD, "s2", attacks = 1, walls = false),
            attack("2026-10-08T07:14:00Z", 1, 1),
            event("2026-10-08T07:14:10Z", "session_finished", "completed" to 1, "reason" to "quota_reached")
        )
        val groups = Timeline.group(events)
        assertEquals(2, groups.size)
        assertEquals("Blood · Farm 1 · 07:10-07:14 · Complete", groups[0].header(utc))
        assertEquals("1 of 1 attacks · loot not read", groups[0].summary())
        assertEquals("Sweat · Farm 40 + walls · 06:51-07:00 · Stopped", groups[1].header(utc))
        assertEquals(Tone.NEUTRAL, groups[1].tone)
        assertEquals("1 of 40 attacks · +412k gold · +398k elixir", groups[1].summary())
        assertEquals(mapOf("s1" to "Stopped", "s2" to "Done"), Timeline.endings(groups))
    }

    @Test
    fun splitsOnAccountHandoff() {
        val events = listOf(
            event("2026-10-08T06:00:00Z", "combined_run_started", "firstAccount" to "Sweat", "firstQuota" to 2,
                "secondAccount" to "Blood", "secondQuota" to 2, "globalDeadlineMinutes" to 120),
            started("2026-10-08T06:01:00Z", "Sweat", SWEAT, "a1", attacks = 2),
            attack("2026-10-08T06:05:00Z", 1, 2),
            attack("2026-10-08T06:09:00Z", 2, 2),
            event("2026-10-08T06:09:10Z", "session_finished", "accountName" to "Sweat", "completed" to 2, "reason" to "quota_reached"),
            event("2026-10-08T06:09:11Z", "account_handoff", "from" to "Sweat", "to" to "Blood", "completed" to 2, "reason" to "quota_reached"),
            event("2026-10-08T06:10:00Z", "account_switch_verified", "accountName" to "Blood", "village" to "Blood"),
            started("2026-10-08T06:10:30Z", "Blood", BLOOD, "b1", attacks = 2),
            attack("2026-10-08T06:14:00Z", 1, 2),
            event("2026-10-08T06:20:00Z", "session_finished", "accountName" to "Blood", "completed" to 1, "reason" to "time_limit"),
            event("2026-10-08T06:20:01Z", "combined_run_completed", "firstCompleted" to 2, "firstReason" to "quota_reached",
                "secondCompleted" to 1, "secondReason" to "time_limit")
        )
        val groups = Timeline.group(events)
        assertEquals(2, groups.size)
        assertEquals("Blood · Both accounts (2 of 2) · 06:10-06:20 · Time limit", groups[0].header(utc))
        assertEquals("Sweat · Both accounts (1 of 2) · 06:00-06:09 · Complete", groups[1].header(utc))
        assertEquals(listOf("b1"), groups[0].sessionIds)
        assertEquals(listOf("a1"), groups[1].sessionIds)
        assertTrue(groups[0].lines.any { it.text == "Switched to Blood" })
        assertTrue(groups[0].lines.first().text.startsWith("Both accounts run finished"))
    }

    @Test
    fun anAutomaticRestartStaysInTheSameRun() {
        val events = listOf(
            started("2026-10-08T05:40:00Z", "Blood", BLOOD, "r1"),
            JSONObject(LogFixtures.REAL.getValue("run_auto_restart")),
            JSONObject(LogFixtures.REAL.getValue("run_recovered")),
            started("2026-10-08T05:48:00Z", "Blood", BLOOD, "r2"),
            event("2026-10-08T06:00:00Z", "session_finished", "completed" to 5, "reason" to "time_limit")
        )
        val groups = Timeline.group(events)
        assertEquals(1, groups.size)
        assertEquals(listOf("r1", "r2"), groups.single().sessionIds)
        assertEquals("Blood · Farm 40 + walls · 05:40-06:00 · Time limit", groups.single().header(utc))
    }

    /** The 2-hour Blood run of 14 October: Activity showed a Stopped card and a "Farm 88" card. */
    @Test
    fun aRunThatWaitedOutATakeoverIsOneCard() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.TAKEOVER_RUN))
        assertEquals(1, groups.size)
        val run = groups.single()
        assertEquals("Blood · 2 h + walls · 08:26-10:29 · Time limit", run.header(ZoneOffset.ofHours(-7)))
        assertEquals("25 attacks · +5.2M gold · +5.6M elixir · 6 walls bought", run.summary())
        assertEquals(Tone.OK, run.tone)
        val sessions = listOf("1c720eea-bd11-4268-9167-d9ca27e2cdeb", "626404c2-3db9-4eff-8ac1-487d97a3d3ac")
        assertEquals(sessions, run.sessionIds)
        assertEquals(sessions.associateWith { "Time" }, Timeline.endings(groups))
    }

    @Test
    fun anAutomaticRestartCountsTheAttacksOfBothSessions() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.AUTO_RESTART_RUN))
        assertEquals(2, groups.size)
        val restarted = groups[1]
        assertEquals("Blood · Farm 40 + walls · 05:38-05:49 · Stopped", restarted.header(utc))
        assertEquals("5 of 40 attacks · +2.1M gold · +3.0M elixir", restarted.summary())
        assertEquals(listOf("0afb6f25-626a-4cc6-85a9-dd94b9fb3f4d", "5425559b-14d5-418f-bae4-318106d1d2aa"), restarted.sessionIds)
        // The run started after the stop is its own card.
        assertEquals("Blood · Farm 40 · 05:51-05:52 · Stopped", groups[0].header(utc))
        assertEquals("0 of 40 attacks", groups[0].summary())
    }

    @Test
    fun aRestartThatWasStoppedDoesNotTakeInTheNextRun() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.RESTART_STOPPED))
        assertEquals(2, groups.size)
        assertEquals("Sweat · Farm 3 + walls · 06:16-06:19 · Stopped", groups[1].header(utc))
        assertEquals("0 of 3 attacks", groups[1].summary())
        assertEquals("Sweat · Farm 3 + walls · 06:27-06:34 · Complete", groups[0].header(utc))
        assertEquals("3 of 3 attacks · +2.4M gold · +1.7M elixir", groups[0].summary())
        assertEquals(listOf("c1aabb33-f337-4adc-b55d-8b9c639fda75"), groups[0].sessionIds)
    }

    /** 5 October: the process died in a 2-hour run's restart, and the run of 40 started 8 hours later is its own card. */
    @Test
    fun aRunWhoseProcessDiedInARestartIsNotJoinedByTheNextRun() {
        val dead = listOf("9f377567-71a7-4ebe-bf8f-4834f0d4ce25")
        val next = listOf("e77dbae9-8ad2-4f1e-ba1b-3bfe2856f37e", "368ba131-fa0c-4cab-9c01-e1da62ca599d")
        val groups = Timeline.group(Timeline.parse(LogFixtures.PROCESS_DIED_RUN))
        assertEquals(2, groups.size)
        // A run its process took with it stays open until the next run starts, as before.
        assertEquals("Sweat · 2 h + walls · 09:34-19:31 · Stopped", groups[1].header(utc))
        assertEquals(56, groups[1].completed)
        assertEquals(22, groups[1].wallsBought)
        assertEquals(dead, groups[1].sessionIds)
        assertEquals(Tone.WARN, groups[1].tone)
        // The new run's own restart before its first attack still keeps it on one card.
        assertEquals("Sweat · Farm 40 + walls · 19:34-20:32 · Stopped", groups[0].header(utc))
        assertEquals(25, groups[0].completed)
        assertEquals(40, groups[0].limit)
        assertEquals(2, groups[0].wallsBought)
        assertEquals(next, groups[0].sessionIds)
        // Stats groups the run lines alone, and they split the same way.
        val runLines = LogFixtures.PROCESS_DIED_RUN.filter { RunLog.isRunLine(it) }
        assertEquals(listOf(next, dead), Timeline.group(Timeline.parse(runLines)).map { it.sessionIds })
        assertEquals((dead + next).associateWith { "Stopped" }, RunLog.endings(LogFixtures.PROCESS_DIED_RUN))
        // Had the new run's first try been restarted before its session started, it would still be its own.
        val restartedFirst = LogFixtures.PROCESS_DIED_RUN.filterNot { it.contains(next[0]) }
        assertEquals(listOf(next.drop(1), dead), Timeline.group(Timeline.parse(restartedFirst)).map { it.sessionIds })
    }

    @Test
    fun eventsOutsideARunFormAnIdleStretch() {
        val events = listOf(
            event("2026-10-08T05:00:00Z", "service_connected"),
            event("2026-10-08T05:00:01Z", "main_activity_opened"),
            JSONObject(LogFixtures.REAL.getValue("inspect_completed")),
            event("2026-10-08T05:33:00Z", "profile_saved", "accountId" to SWEAT),
            event("2026-10-08T05:34:00Z", "action_step", "state" to "HOME")
        )
        val groups = Timeline.group(events)
        assertEquals(1, groups.size)
        val idle = groups.single()
        assertEquals(RunGroup.Kind.IDLE, idle.kind)
        assertEquals("Idle · 3 events (service, inspect, calibration saved)", idle.header(utc))
        assertNull(idle.summary())
    }

    @Test
    fun anOpenRunIsInProgressAndRepeatsAreDroppedOnce() {
        val start = started("2026-10-08T06:51:00Z", "Sweat", SWEAT, "o1").toString()
        val groups = Timeline.group(Timeline.parse(listOf(start, start, "not json", "")))
        assertEquals(1, groups.size)
        assertEquals("Sweat · Farm 40 + walls · 06:51-now · In progress", groups.single().header(utc))
        assertEquals(1, groups.single().lines.size)
        assertTrue(Timeline.endings(groups).isEmpty())
    }

    @Test
    fun aFailureSnapshotDoesNotCloseOrReopenARun() {
        val events = listOf(
            started("2026-10-08T06:51:00Z", "Sweat", SWEAT, "f1"),
            event("2026-10-08T07:00:00Z", "run_stopped", "reason" to "Service destroyed"),
            LogFixtures.shaped("failure_snapshot").put("time", "2026-10-08T07:00:01Z")
        )
        val groups = Timeline.group(events)
        assertEquals(1, groups.size)
        assertEquals(Tone.BAD, groups.single().tone)
        assertEquals("Saved the screen and log for the stop", groups.single().lines.first().text)
        assertEquals("Stopped: Clash Farmer closed", groups.single().lines[1].text)
    }

    @Test
    fun aWallsOnlyRunOnMaxedWallsReadsAsWallsOnly() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.WALLS_ONLY_MAXED))
        val run = groups.single()
        // Its session_started says supervised as well as walls; it is not a test attack.
        assertEquals("Sweat · Walls only · 07:55-07:56 · Complete", run.header(utc))
        assertNull("no attack count for a walls-only run", run.summary())
        assertEquals(Tone.OK, run.tone)
        assertEquals(listOf("Notification sent: Sweat: Run finished", "Walls-only run finished: 0 purchases",
            "No walls left to upgrade in the builder's list", "Walls-only run started, up to 30 min", "Switched to Sweat"),
            run.lines.map { it.text })
        assertEquals(mapOf("409a5a7a-2ef5-41a3-9199-49e188affc6d" to "Done"), Timeline.endings(groups))
        // As logged now, the finish says why nothing was bought.
        val now = LogFixtures.WALLS_ONLY_MAXED.map { if (it.contains("wall_session_finished")) LogFixtures.WALLS_ONLY_MAXED_FINISH else it }
        assertEquals("Walls-only run finished: 0 purchases; every wall is maxed",
            Timeline.group(Timeline.parse(now)).single().lines[1].text)
    }

    @Test
    fun aStorageStopWithEveryWallMaxedSaysSo() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.FULL_WALLS_MAXED))
        val run = groups.single()
        assertEquals("Sweat · Farm 2 + walls · 08:43-08:46 · Storage full", run.header(utc))
        assertEquals("1 of 2 attacks · +413k gold · +302k elixir", run.summary())
        assertEquals(Tone.WARN, run.tone)
        val texts = run.lines.map { it.text }
        assertEquals("Finished: storage full after 1 attack; every wall is maxed", texts[1])
        assertTrue(texts.contains("No walls left to upgrade in the builder's list"))
        assertEquals(mapOf("371c3e07-d681-41af-9b88-baa93b3b492f" to "Full"), Timeline.endings(groups))
    }

    @Test
    fun aCombinedRunsSecondPartThatFillsStorageOnMaxedWallsSaysSo() {
        val groups = Timeline.group(Timeline.parse(LogFixtures.FULL_WALLS_MAXED_COMBINED))
        assertEquals(2, groups.size)
        val sweat = groups[0]
        assertEquals("Sweat · Both accounts (2 of 2) · 08:43-08:46 · Storage full", sweat.header(utc))
        assertTrue(sweat.lines.any { it.text == "Finished: storage full after 1 attack; every wall is maxed" })
        assertEquals(mapOf("371c3e07-d681-41af-9b88-baa93b3b492f" to "Full"), Timeline.endings(groups))
    }

    @Test
    fun jsonHasHeaderSummaryAndLines() {
        val json = Timeline.toJson(Timeline.group(Timeline.parse(LogFixtures.SMOKE_RUN)), utc)
        val run = json.getJSONObject(0)
        assertEquals("run", run.getString("kind"))
        assertEquals("Storage full", run.getString("ending"))
        assertTrue(run.getJSONArray("lines").length() > 3)
        assertEquals(560437L + 0, Timeline.group(Timeline.parse(LogFixtures.SMOKE_RUN)).single().loot[Resource.GOLD])
    }
}
