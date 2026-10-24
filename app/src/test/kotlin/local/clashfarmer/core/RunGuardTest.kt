package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunGuardTest {

    private val validViewport = Viewport(0, 0, 1600, 720)
    private val clashPackage = "com.supercell.clashofclans"

    @Test
    fun testValidRunCanAct() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isTokenValid(token))
        assertFalse(guard.isStopped())

        // Should not throw
        guard.checkCanAct(
            token = token,
            foregroundPackage = clashPackage,
            isUnlocked = true,
            currentViewport = validViewport
        )
    }

    @Test
    fun testStoppedSessionRejectsInput() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.stop("User initiated stop")

        assertTrue(guard.isStopped())
        assertEquals("User initiated stop", guard.stopReason)
        assertFalse(guard.isTokenValid(token))

        var caught = false
        try {
            guard.checkCanAct(
                token = token,
                foregroundPackage = clashPackage,
                isUnlocked = true,
                currentViewport = validViewport
            )
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when session is stopped", caught)
    }

    @Test
    fun testStopIsIdempotent() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.stop("First stop")
        assertTrue(guard.isStopped())
        assertEquals("First stop", guard.stopReason)

        guard.stop("Second stop")
        assertTrue(guard.isStopped())
        assertEquals("First stop", guard.stopReason)
    }

    @Test
    fun testNonClashForegroundPackageRejectsInput() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)

        val nonClashPackages = listOf(
            "com.android.settings",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            null,
            ""
        )

        for (pkg in nonClashPackages) {
            var caught = false
            try {
                guard.checkCanAct(
                    token = token,
                    foregroundPackage = pkg,
                    isUnlocked = true,
                    currentViewport = validViewport
                )
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for foreground package: $pkg", caught)
        }
    }

    @Test
    fun testLockedDeviceRejectsInput() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)

        var caught = false
        try {
            guard.checkCanAct(
                token = token,
                foregroundPackage = clashPackage,
                isUnlocked = false,
                currentViewport = validViewport
            )
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when device is locked", caught)
    }

    @Test
    fun testViewportMismatchRejectsInput() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)

        val badViewports = listOf(
            Viewport(0, 0, 1920, 1080),
            Viewport(0, 0, 720, 1600), // Portrait
            Viewport(50, 0, 1600, 720), // Inset shift
            null
        )

        for (vp in badViewports) {
            var caught = false
            try {
                guard.checkCanAct(
                    token = token,
                    foregroundPackage = clashPackage,
                    isUnlocked = true,
                    currentViewport = vp
                )
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for mismatched viewport: $vp", caught)
        }
    }

    @Test
    fun testSessionTimeoutRejectsInput() {
        var simulatedClock = 1_000_000L
        val guard = RunGuard(clock = { simulatedClock })
        val token = guard.startRun(timeoutMs = 5000, expectedViewport = validViewport)

        // Advance time past deadline
        simulatedClock += 6000L

        var caught = false
        try {
            guard.checkCanAct(
                token = token,
                foregroundPackage = clashPackage,
                isUnlocked = true,
                currentViewport = validViewport
            )
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when session deadline has elapsed", caught)
    }

    @Test
    fun testPositiveTimeLimitsValidated() {
        val guard = RunGuard()
        for (badTimeout in listOf(0L, -1L, -5000L)) {
            var caught = false
            try {
                guard.startRun(timeoutMs = badTimeout, expectedViewport = validViewport)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for non-positive timeout: $badTimeout", caught)
        }
    }

    @Test
    fun testRepeatedStartRejected() {
        val guard = RunGuard()
        val token1 = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isTokenValid(token1))

        var caught = false
        try {
            guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        } catch (e: IllegalStateException) {
            caught = true
        }
        assertTrue("Expected IllegalStateException when calling startRun while already running", caught)
    }

    @Test
    fun testPointBoundsExclusiveOfRightAndBottom() {
        val guard = RunGuard(displayWidth = 1600, displayHeight = 720)
        // Valid point inside [0, 1600) x [0, 720)
        guard.validatePoint(Point(0, 0))
        guard.validatePoint(Point(1599, 719))

        // Out of bounds (exclusive of right/bottom)
        val outOfBounds = listOf(
            Point(1600, 300),
            Point(500, 720),
            Point(-1, 300),
            Point(500, -1),
            Point(1601, 721)
        )

        for (pt in outOfBounds) {
            var caught = false
            try {
                guard.validatePoint(pt)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for out-of-bounds point: $pt", caught)
        }
    }

    @Test
    fun testStaleCallbacksNeverResumeInput() {
        val guard = RunGuard()
        val runToken = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)

        val action1Token = guard.nextActionToken(runToken)
        assertTrue(guard.validateCallback(runToken, action1Token))

        guard.stop("Interrupted")

        assertFalse(guard.validateCallback(runToken, action1Token))

        val run2Token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.validateCallback(runToken, action1Token))
        assertFalse(guard.validateCallback(run2Token, action1Token))

        val action2Token = guard.nextActionToken(run2Token)
        assertTrue(guard.validateCallback(run2Token, action2Token))
    }

    @Test
    fun testPauseHoldsUntilResume() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())

        guard.pause()
        assertTrue(guard.isPaused())
        assertFalse("Pausing is not stopping", guard.isStopped())

        guard.resume()
        assertFalse(guard.isPaused())
    }

    @Test
    fun testPausedTimeDoesNotCountAgainstTheDeadline() {
        var simulatedClock = 1_000_000L
        val guard = RunGuard(clock = { simulatedClock })
        val token = guard.startRun(timeoutMs = 5000, expectedViewport = validViewport)

        simulatedClock += 1000L
        guard.pause()
        simulatedClock += 600_000L // ten minutes with a finger on the button
        guard.resume()
        simulatedClock += 1000L

        // Two seconds of the five have actually been spent, so the run may still act.
        guard.checkCanAct(
            token = token,
            foregroundPackage = clashPackage,
            isUnlocked = true,
            currentViewport = validViewport
        )
    }

    @Test
    fun testResumeWithoutPauseLeavesTheDeadlineAlone() {
        var simulatedClock = 1_000_000L
        val guard = RunGuard(clock = { simulatedClock })
        val token = guard.startRun(timeoutMs = 5000, expectedViewport = validViewport)

        simulatedClock += 1000L
        guard.resume()
        simulatedClock += 4500L

        var caught = false
        try {
            guard.checkCanAct(
                token = token,
                foregroundPackage = clashPackage,
                isUnlocked = true,
                currentViewport = validViewport
            )
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("A resume with nothing to resume must not buy the run more time", caught)
    }

    @Test
    fun testStoppedRunCannotBePaused() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.stop("Finished")

        guard.pause()
        assertFalse("A stopped run has nothing to pause", guard.isPaused())
    }

    @Test
    fun testStoppingClearsAPause() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Stopped while paused")

        assertFalse(guard.isPaused())
    }

    @Test
    fun testANewRunStartsUnpaused() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Stopped while paused")

        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())
    }

    @Test
    fun thePauseCounterMovesOnlyOnAPauseThatTakesEffect() {
        val guard = RunGuard()
        guard.pause()
        assertEquals("Nothing is running", 0L, guard.pauses)
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        assertEquals(1L, guard.pauses)
        guard.pause()
        guard.pause(byPerson = false)
        assertEquals("Already paused", 1L, guard.pauses)
        guard.resume()
        guard.resume()
        assertEquals("A resume is not a pause", 1L, guard.pauses)
        guard.pause(byPerson = false)
        assertEquals("The shade's hold counts too", 2L, guard.pauses)
    }

    @Test
    fun aPersonsPauseIsCarriedThroughAnInternalStopAndUsedUpByTheNextRun() {
        var simulatedClock = 1_000_000L
        val guard = RunGuard(clock = { simulatedClock })
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Account identity verified", carryPause = true)
        assertTrue(guard.isStopped())
        assertTrue("A carried pause reads as paused, so the overlay offers Resume", guard.isPaused())

        simulatedClock += 5_000L
        val before = guard.pauses
        val token = guard.startRun(timeoutMs = 5_000, expectedViewport = validViewport)
        assertTrue("The next run begins paused", guard.isPaused())
        assertEquals("Its pause is counted, so a gesture stamped before it is held", before + 1, guard.pauses)

        // Paused from the start, so ten minutes before Resume cost the new run nothing.
        simulatedClock += 600_000L
        guard.resume()
        simulatedClock += 1_000L
        guard.checkCanAct(token, clashPackage, isUnlocked = true, currentViewport = validViewport)

        guard.stop("Recovered to the village", carryPause = true)
        assertFalse("The carry was used up", guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())
    }

    @Test
    fun theShadesHoldIsNotCarried() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause(byPerson = false)
        guard.stop("Recovering: Screen transition timed out", carryPause = true)
        assertFalse(guard.isPaused())
        guard.pause(byPerson = false)
        assertFalse("The shade between runs holds nothing", guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())
    }

    @Test
    fun aPausePressedDuringTheShadesHoldIsThePersonsAndIsCarried() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause(byPerson = false)
        guard.pause()
        guard.stop("Switch only completed", carryPause = true)
        assertTrue(guard.isPaused())
    }

    @Test
    fun aCarrySurvivesASecondInternalStop() {
        // The app-switch recovery stops the guard, and the recovery's own stop follows it with
        // the flag already cleared.
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Active session interrupted: app switched to com.android.chrome", carryPause = true)
        guard.stop("Recovering: Active session interrupted: app switched to com.android.chrome", carryPause = true)
        assertTrue(guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isPaused())
    }

    @Test
    fun aPauseBetweenRunsIsCarriedAndAResumeBetweenRunsDropsIt() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.stop("Account identity verified", carryPause = true)
        assertFalse(guard.isPaused())

        guard.pause()
        assertTrue(guard.isPaused())
        guard.resume()
        assertFalse(guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())

        guard.stop("Recovering: Screen transition timed out", carryPause = true)
        guard.pause()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isPaused())
    }

    @Test
    fun thePersonsStopDropsTheCarry() {
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Account identity verified", carryPause = true)
        guard.stop("Stopped from the overlay")
        assertFalse(guard.isPaused())
        guard.pause()
        assertFalse("Nothing is between runs any more", guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())
    }

    @Test
    fun aPauseAtAnAccountsFinishHoldsTheNextAccountsSwitchAndTheJobsEndDropsIt() {
        // A combined run (decision 8). Account 1's finish stops with the carry, as LocalRunner's
        // normal finishes now do, and account 2's switch run is the next start.
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.stop("Session limits reached successfully", carryPause = true)
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue("Paused during the last read: account 2's switch starts paused", guard.isPaused())
        guard.resume()

        guard.stop("Time limit reached", carryPause = true)
        guard.pause()
        assertTrue("Paused after the finish: kept for what comes next", guard.isPaused())
        // The job is over: FarmerService stops the stopped guard again without the carry.
        guard.stop("Session finished")
        assertEquals("The finish keeps its reason", "Time limit reached", guard.stopReason)
        assertFalse(guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse("The next job starts as any other", guard.isPaused())
    }

    @Test
    fun switchOnlyAndTheCameraCaptureEndPlainSoAnIdlePauseHoldsNoNextStart() {
        // Both ended between runs before the UO-21 review (15 October), so a Pause pressed while
        // nothing ran was carried into the next Start, an unattended plan's included.
        val guard = RunGuard()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        // The switch's own check stops with the carry; the job's end follows without it.
        guard.stop("Account identity verified", carryPause = true)
        guard.stop("Switch only completed")
        assertFalse("A pause standing at the end is dropped", guard.isPaused())
        guard.pause()
        assertFalse("A Pause after the end holds nothing", guard.isPaused())
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse("The next Start begins unpaused", guard.isPaused())

        guard.stop("Camera reference captured")
        guard.pause()
        guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertFalse(guard.isPaused())
    }

    @Test
    fun checkCanActStillIgnoresAPause() {
        val guard = RunGuard()
        val token = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.pause()
        guard.checkCanAct(token, clashPackage, isUnlocked = true, currentViewport = validViewport)

        guard.stop("Account identity verified", carryPause = true)
        val carried = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isPaused())
        guard.checkCanAct(carried, clashPackage, isUnlocked = true, currentViewport = validViewport)
    }

    @Test
    fun testLateCancelledCallbackOwnershipDoesNotStopNewRun() {
        val guard = RunGuard()
        val run1 = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        guard.stop("Run 1 ended", expectedRunToken = run1)

        val run2 = guard.startRun(timeoutMs = 60_000, expectedViewport = validViewport)
        assertTrue(guard.isTokenValid(run2))

        // Late cancelled callback from run1 tries to stop
        guard.stop("Late cancelled gesture from run 1", expectedRunToken = run1)

        // Run 2 must still be active and NOT stopped!
        assertTrue(guard.isTokenValid(run2))
        assertFalse(guard.isStopped())
    }
}
