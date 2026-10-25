package local.clashfarmer.desktop

import local.clashfarmer.core.UserStop
import local.clashfarmer.service.FarmerOverlay
import local.clashfarmer.service.LiveStatus
import local.clashfarmer.service.RunNotice
import local.clashfarmer.service.RunNotifier
import local.clashfarmer.service.RunTray
import local.clashfarmer.service.plainStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

/** The notifier and the panel as the tray and the panel window read them. */
class RunTrayTest {
    private val busy = LiveStatus(LiveStatus.State.RUNNING, "1/40", "Blood · 1 of 40", "Attacking", 0.025f, false, "2%  0:42")

    @After
    fun reset() {
        RunTray.supported = false
        RunTray.live.value = null
        while (RunTray.notices.tryReceive().isSuccess) Unit
    }

    @Test
    fun `a result notice goes to the tray and takes the ongoing status down`() {
        RunTray.supported = true
        val notifier = RunNotifier()
        notifier.postLive(busy)
        assertEquals(busy, RunTray.live.value)
        val notice = RunNotice.stopped("Blood", UserStop.DASHBOARD)
        notifier.post(notice)
        assertNull(RunTray.live.value)
        assertEquals(notice, RunTray.notices.tryReceive().getOrNull())
        // A run still cancelling does not bring the ongoing status back over its result.
        notifier.postLive(busy)
        assertNull(RunTray.live.value)
        assertFalse(notifier.withdraw(notice))
    }

    @Test
    fun `a stop notice shows the plain line under the running account`() {
        val panel = RunNotifier.shown(RunNotice.stopped(null, UserStop.FLOATING_PANEL), "Blood")
        assertEquals(RunNotice(RunNotice.Kind.STOPPED, "Blood: Run stopped", "You pressed Stop on the status panel."), panel)
        val crash = RunNotifier.shown(RunNotice.stopped(null, "Run error: java.lang.IllegalStateException: boom"), null)
        assertEquals(RunNotice.Kind.NEEDS_YOU, crash.kind)
        assertEquals("Run needs you", crash.title)
        assertEquals("Something went wrong; the Technical log on the Activity tab has the details.", crash.text)
        assertFalse(crash.text.contains("Exception"))
    }

    @Test
    fun `notices already written for people show as they are`() {
        val notices = listOf(
            RunNotice.finished("Blood", 3, 0, 0, 0),
            RunNotice.deviceGuard("Blood", "Battery low: 14% and not charging (limit 15%)"),
            RunNotice.takeoverWait("Blood", 10),
            RunNotice(RunNotice.Kind.NEEDS_YOU, "Scheduled start did not run", "Farm at 07:00: Clash is busy")
        )
        for (notice in notices) assertEquals(notice, RunNotifier.shown(notice, "Blood"))
    }

    @Test
    fun `a scheduled start that did not run says why in PC words`() {
        fun scheduled(text: String) = RunNotice(RunNotice.Kind.NEEDS_YOU, "Scheduled start did not run", text)
        assertEquals(scheduled("Farm at 07:00: Clash Farmer was closed before the start time; set the start again"),
            RunNotifier.shown(scheduled("Farm at 07:00: The helper stopped before the start time"), "Blood"))
        assertEquals(scheduled("Blood at 23:30: Clash Farmer was closed before the start time; set the start again"),
            RunNotifier.shown(scheduled("Blood at 23:30: The helper service shut down"), null))
        assertEquals(scheduled("Night: 2 h at 01:05: The PC was asleep at the start time; set the start again"),
            RunNotifier.shown(scheduled("Night: 2 h at 01:05: The phone was asleep at the start time"), null))
        // Run's own words pass through.
        val refused = scheduled("Blood at 07:00: Blood calibration is incomplete; finish it in Setup")
        assertEquals(refused, RunNotifier.shown(refused, null))
    }

    @Test
    fun `the tray gets the plain stop line`() {
        RunTray.supported = true
        RunNotifier().post(RunNotice.stopped(null, UserStop.NOTIFICATION))
        assertEquals("You chose Stop from the tray icon.", RunTray.notices.tryReceive().getOrNull()?.text)
    }

    @Test
    fun `without a notification area no notice waits for the tray`() {
        RunNotifier().post(RunNotice.stopped(null, UserStop.DASHBOARD))
        assertTrue(RunTray.notices.tryReceive().isFailure)
    }

    @Test
    fun `the ongoing status follows a busy run and clears when it ends`() {
        val notifier = RunNotifier()
        notifier.postLive(busy)
        val paused = busy.copy(state = LiveStatus.State.PAUSED)
        notifier.postLive(paused)
        assertEquals(paused, RunTray.live.value)
        notifier.postLive(LiveStatus.IDLE)
        assertNull(RunTray.live.value)
    }

    @Test
    fun `a status text takes the phase's place, and an idle panel never opens to the card`() {
        val overlay = FarmerOverlay(onStopRequested = {}, onPauseToggled = {})
        overlay.updateStatus("Switching to Blood...")
        SwingUtilities.invokeAndWait {}
        assertEquals("Switching to Blood...", overlay.panel.value.phase)
        SwingUtilities.invokeAndWait { overlay.setCollapsed(false) }
        assertFalse(overlay.panel.value.collapsed)
        assertFalse(overlay.panel.value.expanded)
        overlay.dismiss()
        assertFalse(overlay.panel.value.visible)
    }

    @Test
    fun `the panel shows the runner's phone phrasing in plain words`() {
        assertEquals("Stopped: you pressed Stop on the status panel", plainStatus("Stopped: ${UserStop.FLOATING_PANEL}"))
        assertEquals("Stopped: something went wrong; the Technical log on the Activity tab has the details",
            plainStatus("Stopped: Run error: java.lang.NullPointerException"))
        assertEquals("Session complete: 1 attack", plainStatus("Session Complete: 1 attacks"))
        assertEquals("Session complete: 40 attacks", plainStatus("Session Complete: 40 attacks"))
        assertEquals("Waiting for the Clash window...", plainStatus("Waiting for Clash foreground..."))
        assertEquals("Remote start: 2 h farming for Blood...", plainStatus("Remote start (farm2h) for Blood..."))
        assertEquals("Switching to Blood...", plainStatus("Switching to Blood..."))
        val overlay = FarmerOverlay(onStopRequested = {}, onPauseToggled = {})
        overlay.updateStatus("Session Complete: 3 attacks")
        SwingUtilities.invokeAndWait {}
        assertEquals("Session complete: 3 attacks", overlay.panel.value.phase)
        overlay.dismiss()
    }

    @Test
    fun `the panel's Stop stops as the phone's floating panel did`() {
        var reason: String? = null
        val overlay = FarmerOverlay(onStopRequested = { reason = it }, onPauseToggled = {})
        overlay.pressStop()
        assertEquals(UserStop.FLOATING_PANEL, reason)
        overlay.dismiss()
    }
}
