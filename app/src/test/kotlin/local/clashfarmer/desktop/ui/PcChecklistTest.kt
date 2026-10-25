package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.PcInfo
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcChecklistTest {

    private fun window(width: Int = 1600, height: Int = 900, iconic: Boolean = false) =
        ClashWindow(1, 2, "Clash of Clans", iconic, width, height, foreground = false, monitorDpi = 96)

    @Test
    fun `before the first lookup the line is not read and not counted`() {
        val line = PcChecklist.gameWindow(WindowStatus())
        assertEquals("game_window", line.key)
        assertEquals("Game window", line.title)
        assertEquals(Tone.NEUTRAL, line.tone)
        assertEquals("Not read", line.chip)
        assertFalse(line.counts)
        assertNull(line.fix)
    }

    @Test
    fun `a failed lookup is red with its reason and no fix`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, error = "Can't start the sidecar"))
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Needs you", line.chip)
        assertEquals("Could not look for the window: Can't start the sidecar", line.detail)
        assertTrue(line.counts)
        assertNull(line.fix)
    }

    @Test
    fun `no window asks for Clash and offers to open it`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true))
        assertEquals(Tone.WARN, line.tone)
        assertEquals("Not open", line.chip)
        assertTrue(line.detail.startsWith("Open Clash of Clans in Google Play Games."))
        assertEquals(CheckLine.FixKind.ACCESSIBILITY, line.fix?.kind)
        assertEquals("Fix: Open Clash of Clans", SetupChecklist.fixLabel(line))
    }

    @Test
    fun `a window closed by hand is red, with no fix that would start Clash again`() {
        val status = WindowStatus(checked = true, window = window().copy(visible = false), surface = SurfaceSize(1600, 900))
        val line = PcChecklist.gameWindow(status)
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Closed", line.chip)
        assertTrue(line.detail.endsWith("Restart the PC, then open Clash of Clans."))
        assertTrue(line.counts)
        assertNull(line.fix)
    }

    @Test
    fun `a minimized window is fine and gives its last size`() {
        val status = WindowStatus(checked = true, window = window(0, 0, iconic = true), surface = SurfaceSize(1600, 900))
        val line = PcChecklist.gameWindow(status)
        assertEquals(Tone.OK, line.tone)
        assertEquals("Minimized", line.chip)
        assertEquals("Minimized. A run restores it without taking focus. The game area is 1600x900.", line.detail)
        assertNull(line.fix)
    }

    @Test
    fun `a minimized window never seen open has no size`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, window = window(0, 0, iconic = true)))
        assertEquals("Minimized. A run restores it without taking focus.", line.detail)
    }

    @Test
    fun `a found window gives its game area`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, window = window(), surface = SurfaceSize(1600, 900)))
        assertEquals(Tone.OK, line.tone)
        assertEquals("Open", line.chip)
        assertEquals("Found. The game area is 1600x900.", line.detail)
        assertTrue(line.counts)
    }

    @Test
    fun `a 16 by 10 game area is given at its live size`() {
        // After a Google Play Games relaunch the surface came back at 1860x1162, not 16:9.
        val status = WindowStatus(checked = true, window = window(1860, 1162), surface = SurfaceSize(1860, 1162))
        val line = PcChecklist.gameWindow(status)
        assertEquals(Tone.OK, line.tone)
        assertEquals("Found. The game area is 1860x1162.", line.detail)
    }

    @Test
    fun `the tray icon line is green when supported and amber without a fix when not`() {
        val on = PcChecklist.trayIcon(true)
        assertEquals("tray_icon", on.key)
        assertEquals(Tone.OK, on.tone)
        assertEquals("On", on.chip)
        val off = PcChecklist.trayIcon(false)
        assertEquals(Tone.WARN, off.tone)
        assertEquals("Off", off.chip)
        assertTrue(off.counts)
        assertNull(off.fix)
    }

    @Test
    fun `the PC lines count toward ready, the tab dot and the primary fix`() {
        val lines = listOf(
            PcChecklist.gameWindow(WindowStatus(checked = true)),
            PcChecklist.trayIcon(true),
            SetupChecklist.battery(null),
            SetupChecklist.remote(serviceOn = false, address = null)
        )
        assertEquals(1 to 2, SetupChecklist.ready(lines))
        assertEquals(TabBadge(0, Tone.WARN), SetupChecklist.badge(lines))
        assertEquals("game_window", SetupChecklist.primaryFix(lines)?.key)
    }

    @Test
    fun `without Google Play Games the line is red, with no fix that could not open anything`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, pc = PcInfo(22631, gpgInstalled = false, clashInstalled = false)))
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Not installed", line.chip)
        assertTrue(line.detail.startsWith("Google Play Games is not installed."))
        assertNull(line.fix)
    }

    @Test
    fun `without Clash the line is amber and still offers to open it`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, pc = PcInfo(22631, gpgInstalled = true, clashInstalled = false)))
        assertEquals(Tone.WARN, line.tone)
        assertEquals("Not installed", line.chip)
        assertTrue(line.detail.startsWith("Clash of Clans is not installed."))
        assertEquals(CheckLine.FixKind.ACCESSIBILITY, line.fix?.kind)
    }

    @Test
    fun `a failed start says so until the window appears`() {
        val failed = WindowStatus(checked = true, launchError = "The system cannot find the file specified")
        val line = PcChecklist.gameWindow(failed)
        assertEquals(Tone.WARN, line.tone)
        assertEquals("Did not open", line.chip)
        assertTrue(line.detail.startsWith("Clash of Clans did not open."))
        assertFalse(line.detail.contains("system cannot find"))
        assertEquals(CheckLine.FixKind.ACCESSIBILITY, line.fix?.kind)
        val found = failed.copy(window = window(), surface = SurfaceSize(1600, 900))
        assertEquals(Tone.OK, PcChecklist.gameWindow(found).tone)
    }

    @Test
    fun `everything installed and no window is the plain open line`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, pc = PcInfo(22631, gpgInstalled = true, clashInstalled = true)))
        assertEquals("Not open", line.chip)
        // A shortcut scan that could not tell says nothing about Clash.
        val unknown = PcChecklist.gameWindow(WindowStatus(checked = true, pc = PcInfo(22631, gpgInstalled = true, clashInstalled = null)))
        assertEquals("Not open", unknown.chip)
    }

    @Test
    fun `a window opened again in the running game is red, with the restart advice and no fix`() {
        val status = WindowStatus(checked = true, window = window(), surface = SurfaceSize(1600, 900), reopenedInRunningVm = true)
        val line = PcChecklist.gameWindow(status)
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Reopened", line.chip)
        assertTrue(line.detail.endsWith("Restart the PC, then open Clash of Clans."))
        assertTrue(line.counts)
        assertNull(line.fix)
    }

    @Test
    fun `a full-screen window is amber and says how to leave full screen`() {
        val status = WindowStatus(checked = true, window = window(1920, 1080).copy(fullscreen = true), surface = SurfaceSize(1920, 1080))
        val line = PcChecklist.gameWindow(status)
        assertEquals(Tone.WARN, line.tone)
        assertEquals("Full screen", line.chip)
        assertEquals("Clash of Clans is full screen. Turn full screen off in Google Play Games so the bot can size the window.", line.detail)
        assertNull(line.fix)
    }

    @Test
    fun `Windows 10 gets an amber line outside the count, Windows 11 none`() {
        val ten = PcChecklist.windowsVersion(PcInfo(19045, gpgInstalled = true, clashInstalled = true))!!
        assertEquals("windows", ten.key)
        assertEquals(Tone.WARN, ten.tone)
        assertEquals("Windows 10", ten.chip)
        assertTrue(ten.detail.contains("yellow frame"))
        assertFalse(ten.counts)
        assertNull(ten.fix)
        assertNull(PcChecklist.windowsVersion(PcInfo(22000, gpgInstalled = true, clashInstalled = true)))
        assertNull(PcChecklist.windowsVersion(PcInfo(26300, gpgInstalled = true, clashInstalled = true)))
        assertNull(PcChecklist.windowsVersion(null))
    }

    @Test
    fun `a found window and a tray leave nothing to fix`() {
        val lines = listOf(
            PcChecklist.gameWindow(WindowStatus(checked = true, window = window(), surface = SurfaceSize(1600, 900))),
            PcChecklist.trayIcon(true)
        )
        assertEquals(2 to 2, SetupChecklist.ready(lines))
        assertNull(SetupChecklist.badge(lines))
        assertNull(SetupChecklist.primaryFix(lines))
    }
}
