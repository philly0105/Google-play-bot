package local.clashfarmer.accounts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.RunOutcome
import local.clashfarmer.core.StopException
import org.junit.Assert.*
import org.junit.Test

class AccountOrchestrationTest {
    private val sweat = AccountRecord(AccountStore.SWEAT_ID, "Sweat", "Philly0105", "#8YQCYG2RU")
    private val blood = AccountRecord(AccountStore.BLOOD_ID, "Blood", "VastMaNiSh", "#Y2G29YJQ8")

    /** Plays the service: [first] is what Sweat's session does; Blood always fills its quota. */
    private class Driver(private val first: (quota: Int) -> RunOutcome) : AccountRunnerDriver {
        val calls = mutableListOf<String>()
        override suspend fun farmAccount(account: AccountRecord, quota: Int, maxMinutes: Int): RunOutcome {
            calls += "farm:${account.villageName}:$quota"
            return if (account.villageName == "Sweat") first(quota)
            else RunOutcome(quota, CompletionReason.QUOTA_REACHED)
        }
    }

    private val starts = mutableListOf<String>()
    private fun orchestrator(driver: Driver) = AccountOrchestrator(driver) { completedBefore, account ->
        starts += "${account.villageName}@$completedBefore"
    }

    @Test fun runsEachQuotaOnceInOrder() = runBlocking {
        val driver = Driver { RunOutcome(it, CompletionReason.QUOTA_REACHED) }
        val (a, b) = orchestrator(driver).runCombined(sweat, blood, 15, 9)
        assertEquals(RunOutcome(15, CompletionReason.QUOTA_REACHED), a)
        assertEquals(RunOutcome(9, CompletionReason.QUOTA_REACHED), b)
        assertEquals(listOf("farm:Sweat:15", "farm:Blood:9"), driver.calls)
        assertEquals(listOf("Sweat@0", "Blood@15"), starts)
    }

    @Test fun aGoalReachedBeforeAnyAttackStillAdvances() = runBlocking {
        val driver = Driver { RunOutcome(0, CompletionReason.GOAL_REACHED) }
        val (a, _) = orchestrator(driver).runCombined(sweat, blood, 15, 9)
        assertEquals(RunOutcome(0, CompletionReason.GOAL_REACHED), a)
        assertEquals(listOf("farm:Sweat:15", "farm:Blood:9"), driver.calls)
        // Combined progress counts what was actually done, not the first quota.
        assertEquals(listOf("Sweat@0", "Blood@0"), starts)
    }

    @Test fun aGoalPartWayCountsItsRealAttacks() = runBlocking {
        val driver = Driver { RunOutcome(6, CompletionReason.GOAL_REACHED) }
        orchestrator(driver).runCombined(sweat, blood, 15, 9)
        assertEquals(listOf("Sweat@0", "Blood@6"), starts)
    }

    @Test fun aFullStorageHandsOverToTheSecondAccount() = runBlocking {
        val handoffs = mutableListOf<String>()
        val driver = Driver { RunOutcome(4, CompletionReason.STORAGE_FULL) }
        val orchestrator = AccountOrchestrator(driver,
            onAccountStart = { completedBefore, account -> starts += "${account.villageName}@$completedBefore" },
            onHandoff = { from, to, outcome -> handoffs += "${from.villageName}->${to.villageName}:${outcome.reason.key}" })
        val (a, b) = orchestrator.runCombined(sweat, blood, 15, 9)
        assertEquals(RunOutcome(4, CompletionReason.STORAGE_FULL), a)
        assertEquals("storage_full", a.reason.key)
        assertEquals(RunOutcome(9, CompletionReason.QUOTA_REACHED), b)
        assertEquals(listOf("farm:Sweat:15", "farm:Blood:9"), driver.calls)
        assertEquals(listOf("Sweat@0", "Blood@4"), starts)
        assertEquals(listOf("Sweat->Blood:storage_full"), handoffs)
    }

    @Test fun aTimeLimitHandsOverToTheSecondAccount() = runBlocking {
        val handoffs = mutableListOf<String>()
        val driver = Driver { RunOutcome(5, CompletionReason.TIME_LIMIT) }
        val orchestrator = AccountOrchestrator(driver, clock = { 0L },
            onAccountStart = { completedBefore, account -> starts += "${account.villageName}@$completedBefore" },
            onHandoff = { from, to, outcome -> handoffs += "${from.villageName}->${to.villageName}:${outcome.reason.key}" })
        val (a, b) = orchestrator.runCombined(sweat, blood, 15, 9)
        assertEquals(RunOutcome(5, CompletionReason.TIME_LIMIT), a)
        assertEquals(RunOutcome(9, CompletionReason.QUOTA_REACHED), b)
        assertEquals(listOf("farm:Sweat:15", "farm:Blood:9"), driver.calls)
        assertEquals(listOf("Sweat@0", "Blood@5"), starts)
        assertEquals(listOf("Sweat->Blood:time_limit"), handoffs)
    }

    @Test fun eachAccountGetsItsOwnCapWithinTheGlobalDeadline() = runBlocking {
        var now = 0L
        val minutes = mutableListOf<String>()
        val driver = object : AccountRunnerDriver {
            override suspend fun farmAccount(account: AccountRecord, quota: Int, maxMinutes: Int): RunOutcome {
                minutes += "${account.villageName}:$maxMinutes"
                // Sweat runs out its 20 minutes and a little over, finishing the battle under way.
                if (account.villageName == "Sweat") { now += 23 * 60_000L; return RunOutcome(4, CompletionReason.TIME_LIMIT) }
                return RunOutcome(quota, CompletionReason.QUOTA_REACHED)
            }
        }
        val capped = sweat.copy(settings = sweat.settings.copy(maxMinutes = 20))
        AccountOrchestrator(driver, clock = { now }).runCombined(capped, blood, 15, 9, globalDeadlineMinutes = 60)
        assertEquals(listOf("Sweat:20", "Blood:37"), minutes)
    }

    @Test fun noTimeLeftForTheSecondAccountEndsItOnTheTimeLimit() = runBlocking {
        var now = 0L
        val driver = Driver { now += 61 * 60_000L; RunOutcome(7, CompletionReason.TIME_LIMIT) }
        val (a, b) = AccountOrchestrator(driver, clock = { now }).runCombined(sweat, blood, 15, 9, globalDeadlineMinutes = 60)
        assertEquals(RunOutcome(7, CompletionReason.TIME_LIMIT), a)
        assertEquals(RunOutcome(0, CompletionReason.TIME_LIMIT), b)
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }

    @Test fun underFiveMinutesLeftIsNotWorthASwitch() = runBlocking {
        var now = 0L
        val driver = Driver { now += 56 * 60_000L; RunOutcome(7, CompletionReason.TIME_LIMIT) }
        val (_, b) = AccountOrchestrator(driver, clock = { now }).runCombined(sweat, blood, 15, 9, globalDeadlineMinutes = 60)
        assertEquals(RunOutcome(0, CompletionReason.TIME_LIMIT), b)
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }

    @Test fun fiveMinutesLeftStillStartsTheSecondAccount() = runBlocking {
        var now = 0L
        val driver = Driver { now += 55 * 60_000L; RunOutcome(7, CompletionReason.TIME_LIMIT) }
        AccountOrchestrator(driver, clock = { now }).runCombined(sweat, blood, 15, 9, globalDeadlineMinutes = 60)
        assertEquals(listOf("farm:Sweat:15", "farm:Blood:9"), driver.calls)
    }

    @Test fun aDeviceGuardFinishNeverStartsTheSecondAccount() = runBlocking {
        val handoffs = mutableListOf<String>()
        // The global deadline is also spent, so the guard must be the reason reported, not the time limit.
        var now = 0L
        val low = "Battery low: 14% and not charging (limit 15%)"
        val driver = Driver { now += 61 * 60_000L; RunOutcome(3, CompletionReason.DEVICE_GUARD, low) }
        val orchestrator = AccountOrchestrator(driver, clock = { now },
            onAccountStart = { completedBefore, account -> starts += "${account.villageName}@$completedBefore" },
            onHandoff = { from, to, _ -> handoffs += "${from.villageName}->${to.villageName}" })
        val (a, b) = orchestrator.runCombined(sweat, blood, 15, 9, globalDeadlineMinutes = 60)
        assertEquals(RunOutcome(3, CompletionReason.DEVICE_GUARD, low), a)
        // The second account was skipped for the same reason, and says so.
        assertEquals(RunOutcome(0, CompletionReason.DEVICE_GUARD, low), b)
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
        assertEquals(listOf("Sweat@0"), starts)
        assertTrue(handoffs.isEmpty())
    }

    @Test fun storageFullIsAKnownCompletionReason() {
        assertEquals(CompletionReason.STORAGE_FULL, CompletionReason.fromKey("storage_full"))
    }

    @Test fun anIdentityFailureNeverSwitchesSecondAccount() = runBlocking {
        val driver = Driver { throw StopException("Account switch verification failed: mismatch") }
        try { orchestrator(driver).runCombined(sweat, blood, 15, 9); fail("Expected StopException") }
        catch (_: StopException) {}
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }

    @Test fun anOperatorStopNeverSwitchesSecondAccount() = runBlocking {
        val driver = Driver { throw StopException("Stopped from the panel") }
        try { orchestrator(driver).runCombined(sweat, blood, 15, 9); fail("Expected StopException") }
        catch (_: StopException) {}
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }

    @Test fun cancellationNeverSwitchesSecondAccount() = runBlocking {
        val driver = Driver { throw CancellationException("stop") }
        try { orchestrator(driver).runCombined(sweat, blood, 15, 9); fail("Expected cancellation") }
        catch (_: CancellationException) {}
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }

    @Test fun anOutcomeBeyondItsQuotaIsRefused() = runBlocking {
        val driver = Driver { RunOutcome(it + 1, CompletionReason.QUOTA_REACHED) }
        try { orchestrator(driver).runCombined(sweat, blood, 15, 9); fail("Expected StopException") }
        catch (_: StopException) {}
        assertEquals(listOf("farm:Sweat:15"), driver.calls)
    }
}
