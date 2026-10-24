package local.clashfarmer.core

import local.clashfarmer.insight.LogFixtures
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SessionSummaryTest {
    @Test fun takeoverWaitShowsRecoveryWithoutEndingOrLosingProgress() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        summary.accept(event("attack_completed", fields = arrayOf("completed" to 4)))
        summary.accept(event("village_retry_wait"))
        assertEquals("Village opened elsewhere; waiting 10 minutes", summary.snapshot.phase)
        assertEquals(4, summary.snapshot.completed)
        assertNull(summary.snapshot.endedAt)
        summary.accept(event("village_retry_reopening"))
        assertEquals("Reopening village", summary.snapshot.phase)
        summary.accept(event("village_retry_verified"))
        assertEquals("Village verified; resuming", summary.snapshot.phase)
        summary.accept(event("run_stopped", fields = arrayOf("reason" to "Stopped from floating panel")))
        summary.accept(event("village_retry_wait"))
        assertEquals("Stopped", summary.snapshot.phase)
    }

    private fun event(name: String, time: String = "2026-09-17T12:00:00.000Z", vararg fields: Pair<String, Any>) =
        JSONObject().put("event", name).put("time", time).apply { fields.forEach { put(it.first, it.second) } }

    @Test fun newSessionClearsPreviousSpendingAndProgress() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        summary.accept(event("attack_completed", fields = arrayOf("completed" to 4)))
        summary.accept(event("wall_upgraded", fields = arrayOf("currency" to "gold", "walls" to 2, "cost" to 1000000L)))
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 15, "attacksPerWallPhase" to 16)))
        assertEquals(0, summary.snapshot.completed)
        assertEquals(0L, summary.snapshot.goldSpent)
        assertEquals(0, summary.snapshot.wallsUpgraded)
        assertNull(summary.snapshot.attacksUntilWalls)
    }

    private fun replay(lines: List<String>, summary: SessionSummary = SessionSummary()) =
        summary.apply { lines.forEach { accept(JSONObject(it)) } }

    private fun at(time: String) = Instant.parse(time).toEpochMilli()

    /** The 2-hour Blood run of 14 October: the takeover's retry started a session told the 88 attacks owed. */
    @Test fun aRunThatWaitedOutATakeoverKeepsItsStartLimitAndTotals() {
        val lines = LogFixtures.TAKEOVER_RUN
        val resumed = lines.indexOfFirst { it.contains("\"maxAttacks\":88") }
        val summary = replay(lines.take(resumed + 1))
        with(summary.snapshot) {
            assertEquals(at("2026-10-14T15:26:21.444Z"), startedAt)
            assertNull(endedAt)
            assertEquals("Starting session", phase)
            assertEquals(12, completed)
            assertEquals(100, limit)
            assertEquals(3, wallsUpgraded)
            assertEquals(24_000_000L, elixirSpent)
        }
        replay(lines.drop(resumed + 1), summary)
        with(summary.snapshot) {
            assertEquals(at("2026-10-14T15:26:21.444Z"), startedAt)
            assertEquals(at("2026-10-14T17:29:15.601Z"), endedAt)
            assertEquals(25, completed)
            assertEquals(100, limit)
            assertEquals(120, maxMinutes)
            assertTrue(wallsEnabled)
            assertEquals(90, triggerPercent)
            assertEquals(0, resumePercent)
            assertEquals(6, wallsUpgraded)
            assertEquals(24_000_000L, goldSpent)
            assertEquals(24_000_000L, elixirSpent)
            assertEquals("time_limit", completionReason)
            assertEquals("Time limit", phase)
            assertEquals(at("2026-10-14T16:32:28.869Z"), sessionStartedAt)
            assertEquals(12, completedBefore)
        }
    }

    @Test fun anAutomaticRestartCountsOnAndAPlainStartAfterTheStopResets() {
        val lines = LogFixtures.AUTO_RESTART_RUN
        val stopped = lines.indexOfFirst { it.contains("\"run_stopped\"") }
        val summary = replay(lines.take(stopped + 1))
        with(summary.snapshot) {
            assertEquals(at("2026-10-08T05:38:11.359Z"), startedAt)
            assertEquals(5, completed)
            assertEquals(40, limit)
            assertEquals("Stopped", phase)
        }
        replay(lines.drop(stopped + 1).take(1), summary)
        with(summary.snapshot) {
            assertEquals(at("2026-10-08T05:51:25.973Z"), startedAt)
            assertNull(endedAt)
            assertEquals(0, completed)
            assertEquals(40, limit)
            assertFalse(wallsEnabled)
            assertEquals(75, resumePercent)
        }
    }

    @Test fun aRestartThatWasStoppedDoesNotCarryIntoTheNextRun() {
        val lines = LogFixtures.RESTART_STOPPED
        val next = lines.indexOfLast { it.contains("\"session_started\"") }
        val summary = replay(lines.take(next))
        assertEquals("Stopped", summary.snapshot.phase)
        replay(lines.drop(next), summary)
        with(summary.snapshot) {
            assertEquals(at("2026-10-14T06:27:37.235Z"), startedAt)
            assertEquals(3, completed)
            assertEquals(3, limit)
            assertEquals("quota_reached", completionReason)
        }
    }

    /** 5 October: the process died in a 2-hour run's restart; the run of 40 started 8 hours later is its own. */
    @Test fun aRunWhoseProcessDiedInARestartDoesNotTakeInTheNextRun() {
        val lines = LogFixtures.PROCESS_DIED_RUN
        val next = lines.indexOfFirst { it.contains("\"maxAttacks\":40") }
        val summary = replay(lines.take(next + 1))
        with(summary.snapshot) {
            assertEquals(at("2026-10-05T19:34:32.645Z"), startedAt)
            assertEquals(0, completed)
            assertEquals(40, limit)
            assertEquals(0, wallsUpgraded)
            assertEquals(0L, goldSpent)
            assertEquals(0L, elixirSpent)
        }
        // The new run's own restart before its first attack still continues it.
        replay(lines.drop(next + 1), summary)
        with(summary.snapshot) {
            assertEquals(at("2026-10-05T19:34:32.645Z"), startedAt)
            assertEquals(at("2026-10-05T20:32:51.194Z"), endedAt)
            assertEquals(25, completed)
            assertEquals(40, limit)
            assertEquals(2, wallsUpgraded)
            assertEquals(0L, goldSpent)
            assertEquals(10_000_000L, elixirSpent)
            assertEquals(at("2026-10-05T19:35:42.033Z"), sessionStartedAt)
            assertEquals(0, completedBefore)
        }
    }

    /** The same log, had the new run's first try been restarted before its session started, as a switch can be. */
    @Test fun aRestartBeforeTheNextRunsFirstSessionDoesNotJoinItToTheDeadRun() {
        val lines = LogFixtures.PROCESS_DIED_RUN.filterNot { it.contains("e77dbae9-8ad2-4f1e-ba1b-3bfe2856f37e") }
        with(replay(lines).snapshot) {
            assertEquals(at("2026-10-05T19:35:42.033Z"), startedAt)
            assertEquals(25, completed)
            assertEquals(40, limit)
            assertEquals(2, wallsUpgraded)
            assertEquals(0L, goldSpent)
            assertEquals(10_000_000L, elixirSpent)
        }
    }

    @Test fun theNextAccountOfACombinedRunStartsAfresh() {
        // The second account's switch may be restarted, or wait out a takeover, before its session starts.
        for (restart in listOf(
            listOf("run_auto_restart", "run_recovered"),
            listOf("village_retry_wait", "village_retry_reopening", "village_retry_verified")
        )) {
            val summary = SessionSummary()
            summary.accept(event("session_started", "2026-09-17T12:00:00.000Z", "maxAttacks" to 3))
            summary.accept(event("attack_completed", "2026-09-17T12:05:00.000Z", "completed" to 3))
            summary.accept(event("wall_upgraded", "2026-09-17T12:06:00.000Z", "currency" to "gold", "walls" to 2, "cost" to 8_000_000L))
            summary.accept(event("session_finished", "2026-09-17T12:07:00.000Z", "completed" to 3, "reason" to "quota_reached"))
            summary.accept(event("account_handoff", "2026-09-17T12:07:01.000Z", "from" to "Sweat", "to" to "Blood"))
            restart.forEach { summary.accept(event(it, "2026-09-17T12:08:00.000Z")) }
            summary.accept(event("session_started", "2026-09-17T12:12:00.000Z", "maxAttacks" to 3, "maxMinutes" to 108))
            with(summary.snapshot) {
                assertEquals(restart[0], at("2026-09-17T12:12:00.000Z"), startedAt)
                assertNull(restart[0], endedAt)
                assertEquals(restart[0], 0, completed)
                assertEquals(restart[0], 3, limit)
                assertEquals(restart[0], 108, maxMinutes)
                assertEquals(restart[0], 0, wallsUpgraded)
                assertEquals(restart[0], 0L, goldSpent)
            }
        }
    }

    @Test fun theNewestSessionsAccountIsTheRuns() {
        val summary = SessionSummary()
        assertNull(summary.snapshot.accountId)
        summary.accept(event("session_started", "2026-09-17T12:00:00.000Z", "maxAttacks" to 3, "accountId" to "sweat-id", "accountName" to "Sweat"))
        summary.accept(event("session_finished", "2026-09-17T12:07:00.000Z", "completed" to 3, "reason" to "quota_reached"))
        // A combined run's handoff: until Blood's session starts, the run is still Sweat's.
        summary.accept(event("account_handoff", "2026-09-17T12:07:01.000Z", "accountId" to "sweat-id", "from" to "Sweat", "to" to "Blood"))
        assertEquals("sweat-id", summary.snapshot.accountId)
        summary.accept(event("session_started", "2026-09-17T12:12:00.000Z", "maxAttacks" to 3, "accountId" to "blood-id", "accountName" to "Blood"))
        assertEquals("blood-id", summary.snapshot.accountId)
        assertEquals("Blood", summary.snapshot.accountName)
        // Lines of another account after the run do not move it.
        summary.accept(event("session_finished", "2026-09-17T12:20:00.000Z", "completed" to 3, "accountId" to "blood-id"))
        summary.accept(event("camera_reference_retired", "2026-09-17T12:25:00.000Z", "accountId" to "sweat-id"))
        assertEquals("blood-id", summary.snapshot.accountId)
        // A session from a log written before sessions named their account names none.
        summary.accept(event("session_started", "2026-09-17T13:00:00.000Z", "maxAttacks" to 3))
        assertNull(summary.snapshot.accountId)
        assertNull(summary.snapshot.accountName)
    }

    @Test fun aRestartThatContinuesTheRunKeepsItsAccount() {
        val lines = LogFixtures.TAKEOVER_RUN
        val resumed = lines.indexOfFirst { it.contains("\"maxAttacks\":88") }
        val summary = replay(lines.take(resumed))
        assertEquals(LogFixtures.BLOOD, summary.snapshot.accountId)
        // The continuing session_started is the same account's; were it to name another, the run's is kept.
        summary.accept(JSONObject(lines[resumed]).put("accountId", "other-id").put("accountName", "Other"))
        with(summary.snapshot) {
            assertEquals(at("2026-10-14T15:26:21.444Z"), startedAt)
            assertEquals(at("2026-10-14T16:32:28.869Z"), sessionStartedAt)
            assertEquals(LogFixtures.BLOOD, accountId)
            assertEquals("Blood", accountName)
        }
        // A service restart in between starts the next session afresh, on its own account.
        summary.accept(event("run_auto_restart", "2026-10-14T16:40:00.000Z"))
        summary.accept(event("service_connected", "2026-10-14T16:41:00.000Z"))
        summary.accept(event("session_started", "2026-10-14T16:42:00.000Z", "maxAttacks" to 5, "accountId" to "other-id", "accountName" to "Other"))
        assertEquals("other-id", summary.snapshot.accountId)
    }

    @Test fun aGoalFinishIsToldApartFromAQuotaFinish() {
        val goal = SessionSummary()
        goal.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        goal.accept(event("session_finished", fields = arrayOf("completed" to 0, "reason" to "goal_reached")))
        assertEquals("Goal reached", goal.snapshot.phase)
        assertEquals("goal_reached", goal.snapshot.completionReason)
        assertEquals(0, goal.snapshot.completed)

        val quota = SessionSummary()
        quota.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        quota.accept(event("session_finished", fields = arrayOf("completed" to 20, "reason" to "quota_reached")))
        assertEquals("Complete", quota.snapshot.phase)
        assertEquals("quota_reached", quota.snapshot.completionReason)

        val full = SessionSummary()
        full.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        full.accept(event("session_finished", fields = arrayOf("completed" to 7, "reason" to "storage_full")))
        assertEquals("Storage full", full.snapshot.phase)
        assertEquals("storage_full", full.snapshot.completionReason)
        assertEquals(7, full.snapshot.completed)

        val guarded = SessionSummary()
        guarded.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        guarded.accept(event("session_finished", fields = arrayOf("completed" to 2, "reason" to "device_guard")))
        assertEquals("Battery or heat guard", guarded.snapshot.phase)
        assertEquals("device_guard", guarded.snapshot.completionReason)
    }

    @Test fun aStorageFullFinishSaysWhyWallsDidNotSpend() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        summary.accept(event("session_finished", fields = arrayOf("completed" to 7, "reason" to "storage_full",
            "detail" to "Storage full; walls failed: wall list could not be read")))
        assertEquals("Storage full; walls failed: wall list could not be read", summary.snapshot.phase)
        assertEquals("storage_full", summary.snapshot.completionReason)
    }

    @Test fun aSecondRunStoppedKeepsTheFirstReason() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        summary.accept(event("run_stopped", fields = arrayOf("reason" to "Stopped from floating panel")))
        summary.accept(event("run_stopped",
            fields = arrayOf("reason" to "Pinch at Point(x=800, y=360) was cancelled by a touch on the screen")))
        assertEquals("Stopped from floating panel", summary.snapshot.stopReason)
    }

    @Test fun aTimeLimitFinishReadsAsTimeLimitNotStopped() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20, "maxMinutes" to 20)))
        summary.accept(event("attack_completed", fields = arrayOf("completed" to 6)))
        summary.accept(event("session_finished", fields = arrayOf("completed" to 6, "reason" to "time_limit")))
        assertEquals("Time limit", summary.snapshot.phase)
        assertEquals("time_limit", summary.snapshot.completionReason)
        assertNull(summary.snapshot.stopReason)
        assertNotNull(summary.snapshot.endedAt)
        assertEquals(6, summary.snapshot.completed)
        assertEquals(CompletionReason.TIME_LIMIT, CompletionReason.fromKey("time_limit"))
    }

    @Test fun aStopDuringTheHandoffReadsAsStoppedNotAsTheFirstAccountsFinish() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20)))
        summary.accept(event("session_finished", fields = arrayOf("completed" to 6, "reason" to "time_limit")))
        summary.accept(event("account_handoff", fields = arrayOf("from" to "Sweat", "to" to "Blood", "reason" to "time_limit")))
        assertEquals("Switching account", summary.snapshot.phase)
        assertNull(summary.snapshot.endedAt)
        // The second account's switch fails before its own session starts.
        summary.accept(event("run_stopped", fields = arrayOf("reason" to "Device is locked")))
        assertEquals("Stopped", summary.snapshot.phase)
        assertNull(summary.snapshot.completionReason)
        assertEquals("Device is locked", summary.snapshot.stopReason)
    }

    @Test fun unverifiedSpendNeverCountsAsSuccess() {
        val summary = SessionSummary()
        summary.accept(event("session_started"))
        summary.accept(event("wall_spend_unverified", fields = arrayOf("currency" to "gold", "cost" to 8000000L)))
        assertEquals(0L, summary.snapshot.goldSpent)
        assertEquals(0, summary.snapshot.wallsUpgraded)
        assertTrue(summary.snapshot.wallsDisabled)
    }

    @Test fun interruptedConfirmationIsRecordedAsUnknownInsteadOfZeroSpend() {
        val summary = SessionSummary()
        summary.accept(event("session_started"))
        summary.accept(event("run_stopped", fields = arrayOf("reason" to "Stopped from floating panel")))
        summary.accept(event(
            "wall_purchase_outcome_unknown",
            fields = arrayOf("currency" to "elixir", "cost" to 4_000_000L, "walls" to 1)
        ))
        assertTrue(summary.snapshot.purchaseOutcomeUnknown)
        assertEquals("elixir", summary.snapshot.unknownPurchaseCurrency)
        assertEquals(4_000_000L, summary.snapshot.unknownPurchaseCost)
        assertEquals(1, summary.snapshot.unknownPurchaseWalls)
        assertEquals(0L, summary.snapshot.elixirSpent)
    }

    /** A cancelled confirmation logged its price as "total" until 8 October; those logs are on the phone. */
    @Test fun anUnknownPurchaseLoggedWithTotalShowsItsCost() {
        val summary = SessionSummary()
        summary.accept(event("session_started"))
        summary.accept(event(
            "wall_purchase_outcome_unknown",
            fields = arrayOf("stage" to "dispatch", "currency" to "gold", "total" to 9_000_000L, "walls" to 1)
        ))
        assertTrue(summary.snapshot.purchaseOutcomeUnknown)
        assertEquals(9_000_000L, summary.snapshot.unknownPurchaseCost)
    }

    @Test fun elapsedTimeFreezesAtFinishAndDoesNotResumeForServiceEvents() {
        val summary = SessionSummary()
        summary.accept(event("session_started"))
        summary.accept(event("session_finished", "2026-09-17T12:02:00.000Z", "completed" to 1))
        summary.accept(event("service_connected", "2026-09-17T13:00:00.000Z"))
        assertEquals(120000L, summary.snapshot.elapsedMs(Long.MAX_VALUE))
        assertEquals("Complete", summary.snapshot.phase)
    }

    @Test fun verifiedUpgradeIsCountedOnceDespitePhaseSummaryEvents() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("walls" to true)))
        summary.accept(event("wall_upgraded", fields = arrayOf("currency" to "elixir", "walls" to 3, "cost" to 6000000L)))
        summary.accept(event("walls_finished", fields = arrayOf("upgrades" to 3)))
        summary.accept(event("wall_session_finished", fields = arrayOf("upgrades" to 3)))
        assertEquals(3, summary.snapshot.wallsUpgraded)
        assertEquals(6000000L, summary.snapshot.elixirSpent)
        assertNull(summary.snapshot.attacksUntilWalls)
    }

    @Test fun noSessionMeansNoFabricatedElapsedTimeOrCadence() {
        val snapshot = SessionSummary().snapshot
        assertNull(snapshot.startedAt)
        assertEquals(0L, snapshot.elapsedMs(100000L))
        assertNull(snapshot.attacksUntilWalls)
    }

    @Test fun openingHelperDoesNotExtendAnInterruptedHistoricalRun() {
        val summary = SessionSummary()
        summary.accept(event("session_started"))
        summary.accept(event("attack_completed", "2026-09-17T12:02:00.000Z", "completed" to 1))
        val lastBotTime = summary.snapshot.lastEventAt
        summary.accept(event("main_activity_opened", "2026-09-17T13:00:00.000Z"))
        assertEquals(lastBotTime, summary.snapshot.lastEventAt)
    }

    @Test fun wallCountdownDoesNotPromiseAPhaseBeyondTheAttackLimit() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 20, "attacksPerWallPhase" to 7)))
        summary.accept(event("attack_completed", fields = arrayOf("completed" to 19)))
        assertNull(summary.snapshot.attacksUntilWalls)
    }

    @Test fun wallsOnlySessionReportsItsActualWork() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("walls" to true)))
        assertEquals("Upgrading walls", summary.snapshot.phase)
    }

    @Test fun explicitWallsEnabledInSessionStarted() {
        val summary = SessionSummary()
        summary.accept(event("session_started", fields = arrayOf("maxAttacks" to 15, "wallsEnabled" to true, "triggerPercent" to 85, "resumePercent" to 70)))
        assertTrue(summary.snapshot.wallsEnabled)
        assertEquals(85, summary.snapshot.triggerPercent)
        assertEquals(70, summary.snapshot.resumePercent)
    }
}
