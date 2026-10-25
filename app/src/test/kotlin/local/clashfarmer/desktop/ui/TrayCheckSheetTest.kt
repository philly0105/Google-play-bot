package local.clashfarmer.desktop.ui

import local.clashfarmer.core.Region
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayCheckSheetTest {

    private val calibrated = PhoneProfile.createDefault(1740, 978).copy(
        troopRois = mapOf("slot_1" to Region(300, 860, 400, 970)),
        isVerified = true
    )

    @Test
    fun `the tray is checked on the calibration itself at its own size`() {
        val (here, problem) = TrayCheckSheet.profileAt(calibrated, 1740, 978)
        assertSame(calibrated, here)
        assertNull(problem)
    }

    @Test
    fun `on a 16 by 10 game area the card areas move as a run moves them`() {
        val (here, problem) = TrayCheckSheet.profileAt(calibrated, 1860, 1162)
        assertNull(problem)
        assertEquals(PcProfiles.derive(calibrated, SurfaceSize(1860, 1162), emptySet()), here)
        assertNotEquals(calibrated.troopRois.getValue("slot_1"), here.troopRois.getValue("slot_1"))
        assertEquals(1162, here.viewport.bottom)
    }

    @Test
    fun `a game area too small to read is the problem shown`() {
        val (here, problem) = TrayCheckSheet.profileAt(calibrated, 1245, 700)
        assertSame(calibrated, here)
        assertTrue(problem!!, problem.contains("too small"))
    }
}
