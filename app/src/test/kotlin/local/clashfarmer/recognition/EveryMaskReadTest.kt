package local.clashfarmer.recognition

import org.junit.Assert.*
import org.junit.Test

/** The cuts are what balance_implausible logged for Blood on 15 October. */
class EveryMaskReadTest {
    private val masks = listOf("215", "230", "240", "w150g2", "w150g3", "w180g2", "w180g3", "w210g2", "w210g3")

    private fun cuts(text: String, odd: Map<String, String> = emptyMap()): List<String> =
        masks.map { "$it:${odd[it] ?: text}" }

    @Test fun nineMasksReadingTheBalanceAgree() {
        // 04:12 elixir, on all three frames.
        assertTrue(ScreenReader.everyMaskRead(cuts("387 427"), 387_427L))
        assertFalse(ScreenReader.everyMaskRead(cuts("387 427"), 387_428L))
    }

    @Test fun oneMaskMayReadNothingOrLackTheNumbersShape() {
        // 05:01 elixir, first frame: the 240 cut read "908 9L8 E".
        assertTrue(ScreenReader.everyMaskRead(cuts("3 876 306", mapOf("240" to "908 9L8 E")), 3_876_306L))
        assertTrue(ScreenReader.everyMaskRead(cuts("262 165").drop(1), 262_165L))
    }

    @Test fun twoMasksThatAbstainAreTooMany() {
        // 01:39 elixir: two white masks split a glyph, "6 5655 088", and the other seven read
        // 6 565 088. The old vote took 65 655 088 from it.
        val raw = cuts("6 565 088", mapOf("w180g3" to "6 5655 088", "w210g3" to "6 5655 088"))
        assertFalse(ScreenReader.everyMaskRead(raw, 6_565_088L))
        assertFalse(ScreenReader.everyMaskRead(raw, 65_655_088L))
        assertFalse(ScreenReader.everyMaskRead(cuts("262 165").drop(2), 262_165L))
    }

    @Test fun oneMaskReadingOtherDigitsWithTheShapeDisagrees() {
        assertFalse(ScreenReader.everyMaskRead(cuts("2 403 171", mapOf("w150g2" to "24 031 171")), 2_403_171L))
        assertFalse(ScreenReader.everyMaskRead(emptyList(), 0L))
    }
}
