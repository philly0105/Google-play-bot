package local.clashfarmer.desktop

import local.clashfarmer.accounts.PcAccount
import local.clashfarmer.desktop.platform.SidecarGameSurface
import local.clashfarmer.insight.EventText
import local.clashfarmer.service.FarmerService
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PC's own stop and refusal reasons read as plain sentences, with the PC's capitals kept. */
class PcStopLineTest {

    @Test
    fun `the PC's stop reasons read plainly`() {
        assertEquals(
            "the PC was locked or the screen turned off, so the game stopped updating",
            EventText.stopLine("Session is stopped: ${SidecarGameSurface.FRAMES_STOPPED}")
        )
        assertEquals(
            "the Clash window was opened again while the game kept running, so restart the PC",
            EventText.stopLine(FarmerService.CLASH_REOPENED)
        )
        assertEquals("the PC was asleep at the start time", EventText.stopLine(FarmerService.PC_ASLEEP))
        assertEquals(
            "the PC was locked or its screen was off at the start time",
            EventText.stopLine(FarmerService.PC_LOCKED_AT_START)
        )
    }

    @Test
    fun `a first read that can't find the village keeps its advice, Clash's capitals included`() {
        assertEquals(
            "could not find the village in Clash. The bot reads Clash's English words, so if Clash is in another " +
                "language, set it to English (Settings > Language). Otherwise close any open window in Clash. Then " +
                "run one supervised attack again",
            EventText.stopLine(PcAccount.VILLAGE_NOT_FOUND)
        )
    }
}
