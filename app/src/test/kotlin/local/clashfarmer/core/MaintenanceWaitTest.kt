package local.clashfarmer.core

import local.clashfarmer.core.MaintenanceWait.Decision
import local.clashfarmer.core.MaintenanceWait.Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceWaitTest {

    @Test fun maintenanceStillUpIsPolledEveryMinute() {
        val wait = MaintenanceWait(startedAt = 0)
        assertEquals(Decision.Wait(PopupGuard.MAINTENANCE_POLL_MS), wait.next(Read.MAINTENANCE, 60_000))
    }

    @Test fun oneFrameWithoutMaintenanceThenMaintenanceAgainKeepsWaiting() {
        val wait = MaintenanceWait(startedAt = 0)
        // OCR drops a letter ("maintenanee") on one poll; the next one reads the dialog again.
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.RECOGNISED, 60_000))
        assertEquals(Decision.Wait(PopupGuard.MAINTENANCE_POLL_MS), wait.next(Read.MAINTENANCE, 65_000))
        // The streak starts over: one more clean read is not enough on its own.
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.RECOGNISED, 125_000))
    }

    @Test fun twoReadsWithoutMaintenanceFiveSecondsApartEndTheWait() {
        val wait = MaintenanceWait(startedAt = 0)
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.RECOGNISED, 60_000))
        assertEquals(Decision.Over, wait.next(Read.RECOGNISED, 65_000))
    }

    @Test fun twoReadsCloserThanFiveSecondsDoNotEndIt() {
        val wait = MaintenanceWait(startedAt = 0)
        wait.next(Read.RECOGNISED, 60_000)
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.RECOGNISED, 63_000))
        assertEquals(Decision.Over, wait.next(Read.RECOGNISED, 65_000))
    }

    @Test fun loadingCloudsAfterTheBreakAreWaitedOnUntilARecognisedScreen() {
        val wait = MaintenanceWait(startedAt = 0)
        // Splash and loading clouds read as nothing: still waiting, no input, not over.
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.UNRECOGNISED, 60_000))
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.UNRECOGNISED, 65_000))
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.UNRECOGNISED, 70_000))
        assertEquals(Decision.Over, wait.next(Read.RECOGNISED, 75_000))
    }

    @Test fun maintenanceBackDuringTheLoadingStartsOver() {
        val wait = MaintenanceWait(startedAt = 0)
        wait.next(Read.UNRECOGNISED, 60_000)
        wait.next(Read.UNRECOGNISED, 65_000)
        assertEquals(Decision.Wait(PopupGuard.MAINTENANCE_POLL_MS), wait.next(Read.MAINTENANCE, 70_000))
        assertEquals(Decision.Wait(MaintenanceWait.CONFIRM_GAP_MS), wait.next(Read.RECOGNISED, 130_000))
        assertEquals(Decision.Over, wait.next(Read.RECOGNISED, 135_000))
    }

    @Test fun anyScreenSomethingActsOnIsRecognised() {
        fun read(
            verdict: PopupGuard.Verdict,
            text: String = "",
            state: ScreenState = ScreenState.UNKNOWN,
            reloadDialog: Boolean = false
        ) = MaintenanceWait.classify(state, verdict, text, reloadDialog)
        assertEquals(Read.MAINTENANCE, read(PopupGuard.Verdict.Maintenance))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.None, state = ScreenState.HOME))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.None, state = ScreenState.MENU))
        // After a break: a forced update, an event intro, a league or reward popup, the bubbles.
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.UpdateRequired))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.Dismiss("event_intro", Point(1200, 150))))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.Dismiss("league_promotion", Point(800, 600))))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.AdvanceDialogue("Y-ouCH! THat daRN PoRtal", Point(540, 470))))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.RefusedPremium("reward")))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.ServerDialog("connection_lost")))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.None, "Welcome back Chief Okay"))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.None, "Connection lost another device Reload"))
        assertEquals(Read.RECOGNISED, read(PopupGuard.Verdict.None, reloadDialog = true))
        // Splash, clouds and popups nothing may tap are still loading.
        assertEquals(Read.UNRECOGNISED, read(PopupGuard.Verdict.None))
        assertEquals(Read.UNRECOGNISED, read(PopupGuard.Verdict.None, "SUPERCELL"))
        assertEquals(Read.UNRECOGNISED, read(PopupGuard.Verdict.Wait("reward")))
        assertEquals(Read.UNRECOGNISED, read(PopupGuard.Verdict.Wait("samsung_update_banner")))
    }

    @Test fun theWholeWaitIsBounded() {
        val wait = MaintenanceWait(startedAt = 1_000)
        assertFalse(wait.timedOut(PopupGuard.MAINTENANCE_MAX_MS))
        assertTrue(wait.timedOut(1_000 + PopupGuard.MAINTENANCE_MAX_MS))
        // Loading that never settles still ends at the same bound.
        wait.next(Read.UNRECOGNISED, 60_000)
        assertTrue(wait.timedOut(1_000 + PopupGuard.MAINTENANCE_MAX_MS))
    }
}
