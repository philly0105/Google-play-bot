package local.clashfarmer.core

import local.clashfarmer.core.AppSwitchLatch.Action
import local.clashfarmer.core.AppSwitchLatch.Gate
import local.clashfarmer.core.AppSwitchLatch.HoldTimer.Tick
import local.clashfarmer.log.FailureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSwitchLatchTest {
    private val shade = AppSwitchLatch.SYSTEM_UI_PACKAGE
    private val clash = RunGuard.CLASH_PACKAGE
    private val own = "local.clashfarmer"
    private val store = "com.android.vending"
    private val launcher = "com.sec.android.app.launcher"

    private fun decide(
        active: String?,
        unlocked: Boolean = true,
        running: Boolean = true,
        paused: Boolean = false,
        held: Boolean = false,
        restartable: Boolean = false,
        waitingForVillage: Boolean = false
    ) = AppSwitchLatch.decide(active, own, unlocked, running, paused, held, restartable, waitingForVillage)

    private fun gate(
        active: String?,
        unlocked: Boolean = true,
        running: Boolean = true,
        paused: Boolean = false,
        held: Boolean = false,
        restartable: Boolean = false,
        waitingForVillage: Boolean = false
    ) = AppSwitchLatch.gate(active, own, unlocked, running, paused, held, restartable, waitingForVillage)

    @Test fun theShadeOverClashOnAnUnlockedPhoneHoldsTheRun() {
        assertEquals(Action.HOLD, decide(shade))
        assertEquals(Action.HOLD, decide(shade, restartable = true))
    }

    @Test fun theShadeOverAPausedRunLeavesThePauseAlone() {
        // Already held, or paused by a person before the shade came down.
        assertEquals(Action.IGNORE, decide(shade, paused = true, held = true))
        assertEquals(Action.IGNORE, decide(shade, paused = true))
    }

    @Test fun theLockScreenStillEndsTheRun() {
        assertEquals(Action.STOP, decide(shade, unlocked = false))
        assertEquals(Action.STOP, decide(shade, unlocked = false, restartable = true))
        assertEquals(Action.STOP, decide(shade, unlocked = false, paused = true, held = true))
    }

    @Test fun anotherAppStopsOrRecoversAsBefore() {
        assertEquals(Action.STOP, decide(store))
        assertEquals(Action.RECOVER, decide(store, restartable = true))
        assertEquals(Action.STOP, decide(store, restartable = true, unlocked = false))
        // A pause does not let the run sit under another app.
        assertEquals(Action.STOP, decide(store, paused = true))
        assertEquals(Action.RECOVER, decide(store, restartable = true, paused = true, held = true))
    }

    @Test fun clashBackAfterTheShadesHoldReleasesIt() {
        assertEquals(Action.RELEASE, decide(clash, paused = true, held = true))
    }

    @Test fun clashBackAfterAPersonPausedDuringTheHoldStaysPaused() {
        // Pause pressed on the overlay, in the shade or in the app makes the pause theirs.
        assertEquals(Action.IGNORE, decide(clash, paused = true, held = false))
    }

    @Test fun aHoldTheGuardAlreadyDroppedReleasesNothing() {
        assertEquals(Action.IGNORE, decide(clash, paused = false, held = true))
        assertEquals(Action.IGNORE, decide(clash))
    }

    @Test fun nothingHappensWithoutARun() {
        for (active in listOf(shade, clash, store, own, null)) {
            assertEquals(Action.IGNORE, decide(active, running = false))
            assertEquals(Action.IGNORE, decide(active, running = false, unlocked = false))
        }
    }

    @Test fun theHelperOrAnUnknownWindowEndsNothing() {
        assertEquals(Action.IGNORE, decide(own))
        assertEquals(Action.IGNORE, decide(null))
        assertEquals(Action.IGNORE, decide(own, paused = true, held = true))
    }

    @Test fun theShadeDuringTheVillageWaitEndsNothing() {
        // HOME is pressed on purpose there, so the launcher ends nothing, and the takeover notice
        // invites a pull of the shade to read it, so that ends nothing either.
        assertEquals(Action.IGNORE, decide(launcher, waitingForVillage = true))
        assertEquals(Action.IGNORE, decide(launcher, waitingForVillage = true, restartable = true))
        assertEquals(Action.IGNORE, decide(shade, waitingForVillage = true))
        // Neither held nor reopened: the wait is not a run to pause or recover.
        assertEquals(Action.IGNORE, decide(shade, waitingForVillage = true, restartable = true))
        assertEquals(Action.IGNORE, decide(shade, waitingForVillage = true, paused = true))
        // The lock screen still ends the wait.
        assertEquals(Action.STOP, decide(launcher, waitingForVillage = true, unlocked = false))
        assertEquals(Action.STOP, decide(shade, waitingForVillage = true, unlocked = false))
        assertEquals(Action.STOP, decide(shade, waitingForVillage = true, unlocked = false, restartable = true))
    }

    @Test fun onlyTheUnlockedShadeCounts() {
        assertTrue(AppSwitchLatch.shadeOver(shade, unlocked = true))
        assertFalse(AppSwitchLatch.shadeOver(shade, unlocked = false))
        assertFalse(AppSwitchLatch.shadeOver(clash, unlocked = true))
        assertFalse(AppSwitchLatch.shadeOver(null, unlocked = true))
    }

    @Test fun aCaptureOrGestureUnderTheShadeHoldsTheRunRatherThanStoppingIt() {
        // The shade is down before its window change arrives; the guard's check would stop the run.
        assertEquals(Gate.HOLD, gate(shade))
        assertEquals(Gate.HOLD, gate(shade, restartable = true))
    }

    @Test fun aCaptureOrGestureWaitsOutAHoldAlreadyInPlace() {
        assertEquals(Gate.WAIT, gate(shade, paused = true, held = true))
        assertEquals(Gate.WAIT, gate(shade, paused = true, held = true, restartable = true))
        // The read found the helper or nothing while the hold stands.
        assertEquals(Gate.WAIT, gate(own, paused = true, held = true))
        assertEquals(Gate.WAIT, gate(null, paused = true, held = true))
        // Clash back: the wait lets go of the hold, and the gate is asked again.
        assertEquals(Gate.WAIT, gate(clash, paused = true, held = true))
    }

    @Test fun aHoldOnALockedPhoneIsLeftToTheCheck() {
        // The read found the helper or nothing, but the phone is locked: the guard ends the run
        // rather than the gate waiting it out.
        for (active in listOf(own, null)) {
            assertEquals(Gate.CHECK, gate(active, unlocked = false, paused = true, held = true))
            assertEquals(Gate.CHECK, gate(active, unlocked = false, paused = true, held = true, restartable = true))
            assertEquals(Gate.CHECK, gate(active, unlocked = false, paused = true))
        }
    }

    @Test fun aPersonsPauseUnderTheShadeWaitsRatherThanStopping() {
        assertEquals(Gate.WAIT, gate(shade, paused = true))
        assertEquals(Gate.WAIT, gate(shade, paused = true, restartable = true))
    }

    @Test fun aCaptureOrGestureOnTheLockScreenStillStopsTheRun() {
        assertEquals(Gate.CHECK, gate(shade, unlocked = false))
        assertEquals(Gate.CHECK, gate(shade, unlocked = false, restartable = true))
        assertEquals(Gate.CHECK, gate(shade, unlocked = false, paused = true, held = true))
        assertEquals(Gate.CHECK, gate(shade, unlocked = false, paused = true))
        // CHECK hands it to the guard, which ends the run.
        val viewport = Viewport(0, 0, 1600, 720)
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = viewport)
        assertThrows(StopException::class.java) {
            guard.checkCanAct(token, shade, isUnlocked = false, currentViewport = viewport)
        }
        assertTrue(guard.isStopped())
    }

    @Test fun aCaptureOrGestureUnderAnotherAppIsUnchanged() {
        for (restartable in listOf(false, true)) {
            assertEquals(Gate.CHECK, gate(store, restartable = restartable))
            assertEquals(Gate.CHECK, gate(store, restartable = restartable, paused = true))
            assertEquals(Gate.CHECK, gate(store, restartable = restartable, paused = true, held = true))
            assertEquals(Gate.CHECK, gate(launcher, restartable = restartable))
        }
    }

    @Test fun clashInFrontWaitsWhilePaused() {
        // Unpaused, Clash in front goes straight to the check.
        assertEquals(Gate.CHECK, gate(clash))
        assertEquals(Gate.CHECK, gate(clash, restartable = true))
        // A person's Pause holds every gesture where it stands, not at the runner's next pause point.
        assertEquals(Gate.WAIT, gate(clash, paused = true))
        assertEquals(Gate.WAIT, gate(clash, paused = true, restartable = true))
        assertEquals(Gate.WAIT, gate(own, paused = true))
        assertEquals(Gate.WAIT, gate(null, paused = true))
        // A locked phone is the check's to stop.
        assertEquals(Gate.CHECK, gate(clash, paused = true, unlocked = false))
    }

    @Test fun theVillageWaitAndAnEndedRunAreLeftToTheCheck() {
        assertEquals(Gate.CHECK, gate(shade, waitingForVillage = true))
        assertEquals(Gate.CHECK, gate(shade, waitingForVillage = true, paused = true))
        assertEquals(Gate.CHECK, gate(launcher, waitingForVillage = true))
        for (active in listOf(shade, clash, store, own, null)) {
            assertEquals(Gate.CHECK, gate(active, running = false))
            // A pause carried between runs reads as paused while the guard is stopped.
            assertEquals(Gate.CHECK, gate(active, running = false, paused = true))
        }
    }

    @Test fun theHoldCapIsTenMinutes() {
        assertEquals(600_000L, AppSwitchLatch.HOLD_CAP_MS)
        assertEquals(30_000L, AppSwitchLatch.STILL_HELD_AFTER_MS)
        assertEquals("The notification shade stayed open for 10 minutes", AppSwitchLatch.HOLD_TIMEOUT_REASON)
    }

    @Test fun aHoldIsReportedOnceAt30SecondsAndEndsOnceAtTheCap() {
        val start = 5_000L
        val timer = AppSwitchLatch.HoldTimer(start)
        assertEquals(Tick.WAIT, timer.next(start + 1_000, held = true))
        assertEquals(Tick.WAIT, timer.next(start + AppSwitchLatch.STILL_HELD_AFTER_MS - 1, held = true))
        assertEquals(Tick.STILL_HELD, timer.next(start + AppSwitchLatch.STILL_HELD_AFTER_MS, held = true))
        assertEquals(Tick.WAIT, timer.next(start + AppSwitchLatch.STILL_HELD_AFTER_MS + 1_000, held = true))
        assertEquals(Tick.WAIT, timer.next(start + AppSwitchLatch.HOLD_CAP_MS - 1, held = true))
        assertEquals(Tick.TIMEOUT, timer.next(start + AppSwitchLatch.HOLD_CAP_MS, held = true))
        // Fires once: the watch is over after the stop.
        assertEquals(Tick.OVER, timer.next(start + AppSwitchLatch.HOLD_CAP_MS + 1_000, held = true))
        assertEquals(Tick.OVER, timer.next(start + 2 * AppSwitchLatch.HOLD_CAP_MS, held = true))
    }

    @Test fun aPauseTakenDuringTheHoldIsNotCapped() {
        val timer = AppSwitchLatch.HoldTimer(0L)
        assertEquals(Tick.WAIT, timer.next(10_000L, held = true))
        // Pause pressed: the pause is the person's, so the hold reads as over.
        assertEquals(Tick.OVER, timer.next(11_000L, held = false))
        // A later read that looks held again is a new hold with its own timer, not this one.
        assertEquals(Tick.OVER, timer.next(AppSwitchLatch.HOLD_CAP_MS, held = true))
        assertEquals(Tick.OVER, timer.next(AppSwitchLatch.HOLD_CAP_MS * 3, held = false))
    }

    @Test fun aReleasedHoldNeverTimesOut() {
        val timer = AppSwitchLatch.HoldTimer(0L)
        assertEquals(Tick.OVER, timer.next(AppSwitchLatch.HOLD_CAP_MS + 1, held = false))
    }

    @Test fun theCapsStopIsQuietAndNotRestarted() {
        val wrapped = "Session is stopped: ${AppSwitchLatch.HOLD_TIMEOUT_REASON}"
        assertTrue(AppSwitchLatch.isHoldTimeout(AppSwitchLatch.HOLD_TIMEOUT_REASON))
        assertTrue(AppSwitchLatch.isHoldTimeout(wrapped))
        assertFalse(AppSwitchLatch.isHoldTimeout("Clash is not in foreground (detected: $shade)"))
        assertFalse(AppSwitchLatch.isHoldTimeout(null))
        // Not a person's stop, which has its own handling, and not one the restart loop retries.
        assertFalse(FailureSnapshot.isUserStop(AppSwitchLatch.HOLD_TIMEOUT_REASON))
        assertFalse(RecoveryPolicy.isRecoverable(AppSwitchLatch.HOLD_TIMEOUT_REASON))
        assertFalse(RecoveryPolicy.isRecoverable(wrapped))
    }
}
