package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.TabBadge
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private val calibrated = PhoneProfile.createDefault(1740, 978).copy(isVerified = true)

    private fun open(width: Int, height: Int, room: SurfaceSize? = null) =
        WindowStatus(checked = true, window = window(width, height).copy(room = room), surface = SurfaceSize(width, height))

    private fun compared(width: Int, height: Int, room: SurfaceSize? = null, profile: PhoneProfile = calibrated,
                         aspects: Set<Double> = emptySet()) =
        PcChecklist.gameWindow(open(width, height, room), PcChecklist.Calibration("Main", profile, aspects))

    @Test
    fun `a game area at the calibrated size says so`() {
        val line = compared(1740, 978)
        assertEquals(Tone.OK, line.tone)
        assertEquals("Open", line.chip)
        assertEquals("Found. The game area is 1740x978, the size Main was calibrated at.", line.detail)
    }

    @Test
    fun `a game area of the calibrated shape is sized to the calibration`() {
        val line = compared(1920, 1080)
        assertEquals(Tone.OK, line.tone)
        assertEquals("Found. The game area is 1920x1080. Main was calibrated at 1740x978, and a run sizes the window to it.",
            line.detail)
    }

    @Test
    fun `a screen too small for the calibration gives the size a run works at`() {
        val line = compared(1600, 899, room = SurfaceSize(1600, 900))
        assertEquals(Tone.OK, line.tone)
        assertTrue(line.detail, line.detail.endsWith("this screen holds 1600x899, and a run works at that."))
    }

    @Test
    fun `a new shape is amber until a supervised attack works there`() {
        val line = compared(1860, 1162)
        assertEquals(Tone.WARN, line.tone)
        assertEquals("New shape", line.chip)
        assertTrue(line.counts)
        assertEquals("Found. The game area is 1860x1162 (16:10), but Main was calibrated at 16:9. A run moves the " +
            "calibration onto it; run one supervised attack, then the bot can farm on its own.", line.detail)
        val verified = compared(1860, 1162, aspects = setOf(1.6))
        assertEquals(Tone.OK, verified.tone)
        assertTrue(verified.detail, verified.detail.endsWith("a supervised attack has worked at 16:10 too."))
    }

    @Test
    fun `an unverified calibration at a new shape is told to calibrate again`() {
        val line = compared(1860, 1162, profile = calibrated.copy(isVerified = false), aspects = setOf(1.6))
        assertEquals(Tone.WARN, line.tone)
        assertTrue(line.detail, line.detail.contains("has not been verified. Calibrate again on this game area"))
    }

    @Test
    fun `a game area a run can't use is red`() {
        val small = compared(1600, 900, room = SurfaceSize(1245, 700))
        assertEquals(Tone.BAD, small.tone)
        assertEquals("Can't run", small.chip)
        assertTrue(small.detail, small.detail.contains("too small for the bot to read"))
        val square = compared(1000, 1000)
        assertEquals(Tone.BAD, square.tone)
        assertTrue(square.detail, square.detail.contains("a shape the bot can't work with"))
    }

    @Test
    fun `a run keeps the game area's shape inside the calibration's size`() {
        assertEquals(SurfaceSize(1565, 978), PcChecklist.runSize(SurfaceSize(1860, 1162), null, calibrated))
        assertEquals(SurfaceSize(1740, 978), PcChecklist.runSize(SurfaceSize(1740, 978), null, calibrated))
        assertEquals(SurfaceSize(1600, 899), PcChecklist.runSize(SurfaceSize(1740, 978), SurfaceSize(1600, 900), calibrated))
    }

    @Test
    fun `verification is counted as a run counts it on the game area shown`() {
        assertSame(calibrated, PcChecklist.verifiedHere(calibrated, open(1740, 978), emptySet()))
        assertFalse(PcChecklist.verifiedHere(calibrated, open(1860, 1162), emptySet()).isVerified)
        assertTrue(PcChecklist.verifiedHere(calibrated, open(1860, 1162), setOf(1.6)).isVerified)
        assertSame(calibrated, PcChecklist.verifiedHere(calibrated, WindowStatus(checked = true), emptySet()))
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
