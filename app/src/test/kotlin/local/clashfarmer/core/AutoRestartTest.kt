package local.clashfarmer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoRestartTest {

    @Test fun takeoversWaitRepeatedlyWithoutSpendingTheRestartBudget() = runTest {
        val quotas = mutableListOf<Int>()
        val retries = mutableListOf<Int>()
        val outcome = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                quotas += remaining
                if (quotas.size <= 4) throw StopException(Policy.VILLAGE_TAKEN)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { if (quotas.size == 1) 3 else 0 },
            recover = { _, retry, _ -> retries += retry; kotlinx.coroutines.delay(600_000) })
        assertEquals(listOf(10, 7, 7, 7, 7), quotas)
        assertEquals(listOf(0, 0, 0, 0), retries)
        assertEquals(2_400_000L, testScheduler.currentTime)
        assertEquals(10, outcome.completedAttacks)
    }

    @Test fun aRestartThatEndsOnTheTimeLimitKeepsItsReasonAndEveryAttack() = runTest {
        var attempts = 0
        val outcome = farmWithRestarts(20,
            attempt = { remaining ->
                attempts++
                if (attempts == 1) throw StopException("Screen transition timed out")
                assertEquals(16, remaining)
                RunOutcome(5, CompletionReason.TIME_LIMIT)
            },
            completedIn = { 4 },
            recover = { _, _, _ -> })
        assertEquals(RunOutcome(9, CompletionReason.TIME_LIMIT), outcome)
    }

    @Test fun aRestartKeepsTheFinishDetail() = runTest {
        var attempts = 0
        val outcome = farmWithRestarts(20,
            attempt = { _ ->
                attempts++
                if (attempts == 1) throw StopException("Screen transition timed out")
                RunOutcome(2, CompletionReason.STORAGE_FULL, "Storage full; walls failed: wall list could not be read")
            },
            completedIn = { 3 },
            recover = { _, _, _ -> })
        assertEquals(RunOutcome(5, CompletionReason.STORAGE_FULL,
            "Storage full; walls failed: wall list could not be read"), outcome)
    }

    @Test fun aRestartThatEndsOnTheDeviceGuardKeepsItsDetail() = runTest {
        var attempts = 0
        val outcome = farmWithRestarts(20,
            attempt = {
                attempts++
                if (attempts == 1) throw StopException("Screen transition timed out")
                RunOutcome(2, CompletionReason.DEVICE_GUARD, "Phone too hot: 45.5 C (limit 45.0 C)")
            },
            completedIn = { 4 },
            recover = { _, _, _ -> })
        assertEquals(RunOutcome(6, CompletionReason.DEVICE_GUARD, "Phone too hot: 45.5 C (limit 45.0 C)"), outcome)
    }

    @Test fun theHardDeadlineSitsFifteenMinutesPastTheLimit() {
        val limitAt = 1_000_000L
        // Fresh start, 20 minutes to go: 20 + 15.
        assertEquals(35 * 60_000L, Policy.hardDeadlineMs(limitAt, limitAt - 20 * 60_000L))
        // A restart five minutes past the limit gets what is left of the grace, not a fresh one.
        assertEquals(10 * 60_000L, Policy.hardDeadlineMs(limitAt, limitAt + 5 * 60_000L))
        // Past the grace the guard still needs a positive timeout; it expires at once.
        assertEquals(1L, Policy.hardDeadlineMs(limitAt, limitAt + 20 * 60_000L))
        assertEquals(15 * 60_000L, Policy.TIME_LIMIT_GRACE_MS)
    }

    @Test fun takeoverDuringRecoveryDoesNotCountCompletedAttacksTwice() = runTest {
        var attempts = 0
        val reasons = mutableListOf<String>()
        val outcome = farmWithRestarts(10,
            attempt = { remaining ->
                attempts++
                if (attempts == 1) throw StopException("Screen transition timed out")
                assertEquals(7, remaining)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            }, completedIn = { 3 },
            recover = { reason, _, remaining ->
                reasons += reason
                assertEquals(7, remaining)
                if (reasons.size < 3) throw StopException(Policy.VILLAGE_TAKEN)
            })
        assertEquals(listOf("Screen transition timed out", Policy.VILLAGE_TAKEN, Policy.VILLAGE_TAKEN), reasons)
        assertEquals(10, outcome.completedAttacks)
    }

    @Test fun deadlineCancelsTakeoverWaitBeforeAnotherAttempt() = runTest {
        var attempts = 0
        val failure = runCatching {
            kotlinx.coroutines.withTimeout(30_000) {
                farmWithRestarts(10,
                    attempt = { attempts++; throw StopException(Policy.VILLAGE_TAKEN) },
                    completedIn = { 0 },
                    recover = { _, _, _ -> kotlinx.coroutines.delay(600_000) })
            }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(1, attempts)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test fun onlyTheGameMisbehavingIsRecoverable() {
        // Reasons as the runner and switcher word them.
        assertTrue(RecoveryPolicy.isRecoverable("Screen transition timed out waiting for [HOME] (last seen: UNKNOWN) reading: x"))
        assertTrue(RecoveryPolicy.isRecoverable("The screen stayed unrecognized for 60s (last text: )"))
        assertTrue(RecoveryPolicy.isRecoverable("Matchmaking was still searching for an opponent after 180s"))
        assertTrue(RecoveryPolicy.isRecoverable("Screenshot capture failed: Internal error (1)"))
        assertTrue(RecoveryPolicy.isRecoverable("Tap at Point(x=1, y=2) did not complete within 5000ms"))
        assertTrue(RecoveryPolicy.isRecoverable(
            "Cannot verify account identity because Settings did not open over the village home screen (state: UNKNOWN, read: )"))
        // Another app in front: the way back reopens Clash.
        assertTrue(RecoveryPolicy.isRecoverable("Clash is not in foreground (detected: com.sec.android.app.launcher)"))
        assertTrue(RecoveryPolicy.isRecoverable(
            "Session is stopped: Active session interrupted: app switched to com.sec.android.app.launcher"))
        // A tap held after a pause or on a stale frame, with no read loop around it to read again.
        assertTrue(RecoveryPolicy.isRecoverable(TapAfterHold.HELD))
        assertTrue(RecoveryPolicy.isRecoverable(BattleLeft(ScreenState.HOME).message))

        assertFalse(RecoveryPolicy.isRecoverable(null))
        assertFalse(RecoveryPolicy.isRecoverable("Stopped from the overlay"))
        assertFalse(RecoveryPolicy.isRecoverable(Policy.VILLAGE_TAKEN))
        assertFalse(RecoveryPolicy.isRecoverable("Session deadline elapsed"))
        assertFalse(RecoveryPolicy.isRecoverable("Screen turned off (latched stop)"))
        assertFalse(RecoveryPolicy.isRecoverable("Account switch verification failed: wrong tag"))
        assertFalse(RecoveryPolicy.isRecoverable("Tap at Point(x=1, y=2) was cancelled by a touch on the screen"))
    }

    @Test fun eachScreenHasItsWayBack() {
        assertEquals(RecoveryPolicy.Step.DONE, RecoveryPolicy.stepFor(ScreenState.HOME, 0, false))
        assertEquals(RecoveryPolicy.Step.RETURN_HOME, RecoveryPolicy.stepFor(ScreenState.RESULT, 0, false))
        assertEquals(RecoveryPolicy.Step.WAIT, RecoveryPolicy.stepFor(ScreenState.BATTLE, 0, false))
        assertEquals(RecoveryPolicy.Step.WAIT, RecoveryPolicy.stepFor(ScreenState.UNKNOWN, 5_000, false))
        assertEquals(RecoveryPolicy.Step.BACK, RecoveryPolicy.stepFor(ScreenState.UNKNOWN, 25_000, false))
        assertEquals(RecoveryPolicy.Step.BACK, RecoveryPolicy.stepFor(ScreenState.MENU, 0, false))
        assertEquals(RecoveryPolicy.Step.LEAVE_TO_START, RecoveryPolicy.stepFor(ScreenState.UNKNOWN, 0, true))
    }

    @Test fun aRestartCarriesOnWithTheAttacksStillOwed() = runTest {
        val asked = mutableListOf<Int>()
        val recovered = mutableListOf<String>()
        val outcome = farmWithRestarts(
            quota = 10,
            attempt = { remaining ->
                asked += remaining
                if (asked.size == 1) throw StopException("Screen transition timed out waiting for [HOME]")
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 4 },
            recover = { reason, _, _ -> recovered += reason }
        )
        assertEquals(listOf(10, 6), asked)
        assertEquals(1, recovered.size)
        assertEquals(RunOutcome(10, CompletionReason.QUOTA_REACHED), outcome)
    }

    @Test fun anUnrecoverableStopIsThrownAtOnce() = runTest {
        var attempts = 0
        val thrown = runCatching {
            farmWithRestarts(5, attempt = { attempts++; throw StopException("Session deadline elapsed") },
                completedIn = { 0 }, recover = { _, _, _ -> })
        }.exceptionOrNull()
        assertEquals("Session deadline elapsed", thrown?.message)
        assertEquals(1, attempts)
    }

    @Test fun restartsRunOut() = runTest {
        var attempts = 0
        val thrown = runCatching {
            farmWithRestarts(5, maxRestarts = 2,
                attempt = { attempts++; throw StopException("The screen stayed unrecognized for 60s") },
                completedIn = { 0 }, recover = { _, _, _ -> })
        }.exceptionOrNull()
        assertTrue(thrown is StopException)
        assertEquals(3, attempts)
    }

    @Test fun aFailureAfterTheLastAttackIsAFinish() = runTest {
        var recovers = 0
        val outcome = farmWithRestarts(3,
            attempt = { throw StopException("Screen transition timed out waiting for [HOME]") },
            completedIn = { 3 }, recover = { _, _, _ -> recovers++ })
        assertEquals(RunOutcome(3, CompletionReason.QUOTA_REACHED), outcome)
        assertEquals(0, recovers)
    }

    @Test fun aServerDialogReloadsWithoutSpendingARestart() = runTest {
        val reasons = mutableListOf<String>()
        val restarts = mutableListOf<Int>()
        var attempts = 0
        val outcome = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts == 1) throw StopException(PopupGuard.SERVER_DIALOG)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 2 },
            recover = { reason, restart, _ -> reasons += reason; restarts += restart },
            now = { testScheduler.currentTime })
        assertEquals(listOf(PopupGuard.SERVER_DIALOG), reasons)
        assertEquals(listOf(0), restarts)
        // The first one is reloaded at once.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(10, outcome.completedAttacks)
    }

    @Test fun aServerDialogThatKeepsComingBackBacksOffThenStops() = runTest {
        var attempts = 0
        val thrown = runCatching {
            farmWithRestarts(10, maxRestarts = 3,
                attempt = { attempts++; throw StopException(PopupGuard.SERVER_DIALOG) },
                completedIn = { 0 },
                recover = { _, _, _ -> },
                now = { testScheduler.currentTime })
        }.exceptionOrNull()
        assertEquals(RecoveryPolicy.repeatedServerDisconnects(3, 1), thrown?.message)
        assertEquals(3, attempts)
        // Reloaded at once, then after 60 s; the third stops without a reload.
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test fun aDisconnectOncePerBattleStopsOnTheThirdWithinHalfAnHour() = runTest {
        // Each battle runs 200 s and each reload takes 200 s, so every drop comes long after
        // the last recovery finished. The window counts sightings, not reload completions.
        var attempts = 0
        val sightings = mutableListOf<Long>()
        val thrown = runCatching {
            farmWithRestarts(10, maxRestarts = 0,
                attempt = {
                    attempts++
                    kotlinx.coroutines.delay(200_000)
                    sightings += testScheduler.currentTime
                    throw StopException(PopupGuard.SERVER_DIALOG)
                },
                completedIn = { 0 },
                recover = { _, _, _ -> kotlinx.coroutines.delay(200_000) },
                now = { testScheduler.currentTime })
        }.exceptionOrNull()
        assertEquals(3, attempts)
        // Sightings at 200 s, 600 s and 1060 s (after a 60 s backoff): 3 in 15 min.
        assertEquals(listOf(200_000L, 600_000L, 1_060_000L), sightings)
        assertEquals("Repeated server disconnects: 3 in 15 min", thrown?.message)
        assertFalse(RecoveryPolicy.isRecoverable(thrown?.message))
    }

    @Test fun twoDisconnectsSpreadOverAnHourDoNotStop() = runTest {
        var attempts = 0
        val outcome = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts <= 2) {
                    kotlinx.coroutines.delay(1_800_000)
                    throw StopException(PopupGuard.SERVER_DIALOG)
                }
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, _, _ -> },
            now = { testScheduler.currentTime })
        assertEquals(3, attempts)
        assertEquals(10, outcome.completedAttacks)
        // The second came 30 min after the first, outside its window: reloaded at once.
        assertEquals(3_600_000L, testScheduler.currentTime)
    }

    @Test fun theWindowSlidesSoOldDisconnectsStopCounting() = runTest {
        // Drops every 20 min: each window of 30 min holds two at most.
        var attempts = 0
        val outcome = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts <= 5) {
                    kotlinx.coroutines.delay(1_200_000)
                    throw StopException(PopupGuard.SERVER_DIALOG)
                }
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, _, _ -> },
            now = { testScheduler.currentTime })
        assertEquals(6, attempts)
        assertEquals(10, outcome.completedAttacks)
    }

    @Test fun aSharedCountCarriesServerDialogsAcrossTheAccountHandoff() = runTest {
        val sightings = ArrayDeque<Long>()
        var attempts = 0
        val first = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts <= 2) throw StopException(PopupGuard.SERVER_DIALOG)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, _, _ -> },
            now = { testScheduler.currentTime },
            serverSightings = sightings)
        assertEquals(10, first.completedAttacks)
        // The second account's first dialog is the run's third inside the window.
        var secondAttempts = 0
        val thrown = runCatching {
            farmWithRestarts(10, maxRestarts = 0,
                attempt = { secondAttempts++; throw StopException(PopupGuard.SERVER_DIALOG) },
                completedIn = { 0 },
                recover = { _, _, _ -> },
                now = { testScheduler.currentTime },
                serverSightings = sightings)
        }.exceptionOrNull()
        assertEquals(RecoveryPolicy.repeatedServerDisconnects(3, 1), thrown?.message)
        assertEquals(1, secondAttempts)
    }

    @Test fun aSharedCountDropsDialogsOlderThanTheWindow() = runTest {
        val sightings = ArrayDeque<Long>()
        var attempts = 0
        farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts <= 2) throw StopException(PopupGuard.SERVER_DIALOG)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, _, _ -> },
            now = { testScheduler.currentTime },
            serverSightings = sightings)
        kotlinx.coroutines.delay(RecoveryPolicy.SERVER_WINDOW_MS)
        var secondAttempts = 0
        val second = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                secondAttempts++
                if (secondAttempts == 1) throw StopException(PopupGuard.SERVER_DIALOG)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, _, _ -> },
            now = { testScheduler.currentTime },
            serverSightings = sightings)
        assertEquals(10, second.completedAttacks)
        assertEquals(listOf(testScheduler.currentTime), sightings.toList())
    }

    @Test fun withoutASharedCountEachCallStartsFresh() = runTest {
        repeat(2) {
            var attempts = 0
            val outcome = farmWithRestarts(10, maxRestarts = 0,
                attempt = { remaining ->
                    attempts++
                    if (attempts <= 2) throw StopException(PopupGuard.SERVER_DIALOG)
                    RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
                },
                completedIn = { 0 },
                recover = { _, _, _ -> },
                now = { testScheduler.currentTime })
            assertEquals(10, outcome.completedAttacks)
        }
    }

    @Test fun theStopMessagesSayWhatHappened() {
        assertEquals("Repeated server disconnects: 3 in 12 min", RecoveryPolicy.repeatedServerDisconnects(3, 12))
        // The dialog's own reason no longer promises a reload it may not get.
        assertFalse(PopupGuard.SERVER_DIALOG.lowercase().contains("reloading"))
    }

    @Test fun theEndOfAMaintenanceBreakRestartsWithoutSpendingTheBudget() = runTest {
        var attempts = 0
        val restarts = mutableListOf<Int>()
        val outcome = farmWithRestarts(10, maxRestarts = 0,
            attempt = { remaining ->
                attempts++
                if (attempts == 1) throw StopException(PopupGuard.MAINTENANCE_OVER)
                RunOutcome(remaining, CompletionReason.QUOTA_REACHED)
            },
            completedIn = { 0 },
            recover = { _, restart, _ -> restarts += restart })
        assertEquals(listOf(0), restarts)
        assertEquals(10, outcome.completedAttacks)
    }

    @Test fun maintenanceRestartsAreCapped() = runTest {
        var attempts = 0
        val thrown = runCatching {
            farmWithRestarts(10, maxRestarts = 0,
                attempt = { attempts++; throw StopException(PopupGuard.MAINTENANCE_OVER) },
                completedIn = { 0 }, recover = { _, _, _ -> })
        }.exceptionOrNull()
        assertEquals(PopupGuard.MAINTENANCE_OVER, thrown?.message)
        assertEquals(RecoveryPolicy.MAX_RESTARTS + 1, attempts)
    }

    @Test fun dialogStopsAreClassifiedForRecovery() {
        assertTrue(RecoveryPolicy.isRecoverable(PopupGuard.SERVER_DIALOG))
        assertTrue(RecoveryPolicy.isRecoverable(PopupGuard.MAINTENANCE_OVER))
        assertFalse(RecoveryPolicy.isRecoverable(PopupGuard.UPDATE_REQUIRED + " (read: Update available)"))
        assertFalse(RecoveryPolicy.isRecoverable(PopupGuard.maintenanceTimedOut(60)))
    }
}
