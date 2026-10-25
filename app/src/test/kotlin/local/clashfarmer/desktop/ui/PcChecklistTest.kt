package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.desktop.RemotePage
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.PcInfo
import local.clashfarmer.desktop.sidecar.Hello
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
    fun `a failed lookup is red with what to do, no internal error and no fix`() {
        val line = PcChecklist.gameWindow(WindowStatus(checked = true, error = "Can't start the sidecar"))
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Needs you", line.chip)
        assertTrue(line.detail.startsWith("Could not look for the Clash window. Restart Clash Farmer"))
        assertFalse(line.detail.contains("sidecar"))
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
    fun `a run sizes an area of the calibration's shape and leaves another shape alone`() {
        // FarmerService.fitSurface resizes only at the calibration's shape.
        assertEquals(SurfaceSize(1860, 1162), PcChecklist.runSize(SurfaceSize(1860, 1162), null, calibrated))
        assertEquals(SurfaceSize(1860, 1162), PcChecklist.runSize(SurfaceSize(1860, 1162), SurfaceSize(1600, 900), calibrated))
        assertEquals(SurfaceSize(1740, 978), PcChecklist.runSize(SurfaceSize(1740, 978), null, calibrated))
        assertEquals(SurfaceSize(1740, 978), PcChecklist.runSize(SurfaceSize(1280, 720), null, calibrated))
        assertEquals(SurfaceSize(1600, 899), PcChecklist.runSize(SurfaceSize(1740, 978), SurfaceSize(1600, 900), calibrated))
    }

    @Test
    fun `a small area of another shape is red, as the run refuses it`() {
        // A 16:10 laptop: a run works at the area's own 1120x700, which is under 720 rows.
        val line = compared(1120, 700)
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Can't run", line.chip)
        assertTrue(line.detail, line.detail.contains("1120x700, too small for the bot to read"))
    }

    @Test
    fun `an unverified calibration on a smaller screen is told to calibrate again`() {
        val line = compared(1600, 899, room = SurfaceSize(1600, 900), profile = calibrated.copy(isVerified = false))
        assertEquals(Tone.WARN, line.tone)
        assertEquals("New size", line.chip)
        assertTrue(line.detail, line.detail.endsWith("this screen holds 1600x899. Calibrate again on this game area."))
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
            PcChecklist.power(null, null),
            PcChecklist.remote(serviceOn = false, status = null)
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
        // Setup's button under the account's lines undoes a wrong read.
        assertTrue(line.detail.contains(PcAccount.READ_AGAIN))
        assertNull(line.fix)
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

    @Test
    fun `the text reader is not counted until the sidecar answers or while it loads`() {
        val unread = PcChecklist.textReader(null)
        assertEquals("text_reader", unread.key)
        assertEquals("Text reader", unread.title)
        assertEquals(Tone.NEUTRAL, unread.tone)
        assertFalse(unread.counts)
        val loading = PcChecklist.textReader(Hello("1", 7, ocrReady = false))
        assertEquals(Tone.NEUTRAL, loading.tone)
        assertEquals("Loading", loading.chip)
        assertFalse(loading.counts)
    }

    @Test
    fun `a text reader that failed to load is red with its reason and what to do`() {
        val line = PcChecklist.textReader(Hello("1", 7, ocrReady = false, ocrError = "Unable to load DLL 'onnxruntime'."))
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Failed", line.chip)
        assertTrue(line.counts)
        assertTrue(line.detail.startsWith("Could not start: Unable to load DLL 'onnxruntime'. Runs can't read the game."))
        assertTrue(line.detail.contains("Visual C++ Redistributable (x64)"))
        assertEquals(TabBadge(0, Tone.WARN), SetupChecklist.badge(listOf(line)))
    }

    @Test
    fun `a loaded text reader is green`() {
        val line = PcChecklist.textReader(Hello("1", 7, ocrReady = true))
        assertEquals(Tone.OK, line.tone)
        assertEquals("Ready", line.chip)
        assertTrue(line.counts)
    }

    @Test
    fun `the remote page line says why it is off and is never counted`() {
        val stopped = PcChecklist.remote(serviceOn = false, status = RemotePage.Status("100.101.102.103", true))
        assertEquals("remote", stopped.key)
        assertEquals("Remote page", stopped.title)
        assertEquals(Tone.IDLE, stopped.tone)
        val noTailscale = PcChecklist.remote(serviceOn = true, status = RemotePage.Status(null, false))
        assertEquals(Tone.IDLE, noTailscale.tone)
        assertEquals("Optional", noTailscale.chip)
        assertTrue(noTailscale.detail.startsWith("Not needed to farm."))
        assertNull(noTailscale.fix)
        val portTaken = PcChecklist.remote(serviceOn = true,
            status = RemotePage.Status("100.101.102.103", false, RemotePage.Failure.PORT_IN_USE))
        assertEquals(Tone.WARN, portTaken.tone)
        assertTrue(portTaken.detail.startsWith("Port 8765 is in use"))
        val notBound = PcChecklist.remote(serviceOn = true,
            status = RemotePage.Status("100.101.102.103", false, RemotePage.Failure.OTHER))
        assertEquals(Tone.WARN, notBound.tone)
        assertTrue(notBound.detail.startsWith("Tailscale is connected, but the page could not start"))
        assertFalse(notBound.detail.contains("in use"))
        val on = PcChecklist.remote(serviceOn = true, status = RemotePage.Status("100.101.102.103", true))
        assertEquals(Tone.OK, on.tone)
        assertEquals("On", on.chip)
        assertTrue(on.detail.startsWith("100.101.102.103:8765 on your tailnet."))
        assertTrue(on.detail.contains("like a password"))
        assertTrue(listOf(stopped, noTailscale, portTaken, notBound, on).none { it.counts })
        assertNull(SetupChecklist.badge(listOf(stopped, noTailscale, on)))
    }

    @Test
    fun `a PC with no battery reads plugged in, green and not counted`() {
        val line = PcChecklist.power(DeviceHealth(levelPercent = null, charging = true, tempC = null), hasBattery = false)
        assertEquals("battery", line.key)
        assertEquals("Power", line.title)
        assertEquals(Tone.OK, line.tone)
        assertEquals("No battery", line.chip)
        assertTrue(line.detail.startsWith("Plugged in. This PC has no battery"))
        assertFalse(line.detail.contains("charging"))
        assertFalse(line.counts)
        // The flag decides even before the first battery reading.
        assertEquals(line, PcChecklist.power(null, hasBattery = false))
    }

    @Test
    fun `a laptop's power line reads its level and plug, and counts as the phone's did`() {
        val plugged = PcChecklist.power(DeviceHealth(80, charging = true, tempC = null), hasBattery = true)
        assertEquals("80%, plugged in.", plugged.detail)
        assertEquals(Tone.OK, plugged.tone)
        assertTrue(plugged.counts)
        val onBattery = PcChecklist.power(DeviceHealth(60, charging = false, tempC = null), hasBattery = null)
        assertEquals("60%, on battery. Runs end at home at 15%.", onBattery.detail)
        val low = PcChecklist.power(DeviceHealth(10, charging = false, tempC = null), hasBattery = true)
        assertEquals(Tone.BAD, low.tone)
        assertEquals("10%, on battery. Plug the laptop in; runs stop until then.", low.detail)
        assertTrue(low.counts)
        val unread = PcChecklist.power(null, hasBattery = true)
        assertEquals(Tone.NEUTRAL, unread.tone)
        assertFalse(unread.counts)
        assertTrue(listOf(plugged, onBattery, low, unread).none { "heat" in it.title.lowercase() || "charging" in it.detail })
    }
}
