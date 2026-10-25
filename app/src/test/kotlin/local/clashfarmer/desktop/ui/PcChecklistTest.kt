package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
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
    fun `an account not read yet is amber, says what reads it, and offers no fix`() {
        val fresh = AccountRecord(PcAccount.ID, PcAccount.FRESH_VILLAGE_NAME, PcAccount.FRESH_SUPERCELL_NAME, PcAccount.FRESH_PLAYER_TAG)
        val unread = PcChecklist.account(fresh, enrolled = false)
        assertEquals("account_identity", unread.key)
        assertEquals(Tone.WARN, unread.tone)
        assertEquals("Not read", unread.chip)
        assertTrue(unread.detail.contains("first supervised attack"))
        assertFalse(unread.detail.contains(PcAccount.FRESH_PLAYER_TAG))
        assertTrue(unread.counts)
        assertNull(unread.fix)
        val unknown = PcChecklist.account(fresh, enrolled = null)
        assertEquals(Tone.NEUTRAL, unknown.tone)
        assertFalse(unknown.counts)
    }

    @Test
    fun `a read account is green and named by its village`() {
        val line = PcChecklist.account(AccountRecord(PcAccount.ID, "Farmland", "Someone", "#TEST2468"), enrolled = true)
        assertEquals(Tone.OK, line.tone)
        assertEquals("Read", line.chip)
        assertTrue(line.detail.contains("Farmland"))
        assertFalse(line.detail.contains("#TEST2468"))
        assertEquals(1 to 1, SetupChecklist.ready(listOf(line)))
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
