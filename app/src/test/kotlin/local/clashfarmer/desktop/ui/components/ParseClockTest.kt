package local.clashfarmer.desktop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseClockTest {

    @Test
    fun `the usual ways of typing a time all read`() {
        assertEquals(8 to 30, parseClock("8:30"))
        assertEquals(8 to 30, parseClock("08:30"))
        assertEquals(8 to 30, parseClock("0830"))
        assertEquals(8 to 30, parseClock("8.30"))
        assertEquals(8 to 30, parseClock(" 830 "))
        assertEquals(0 to 0, parseClock("00:00"))
        assertEquals(23 to 59, parseClock("23:59"))
    }

    @Test
    fun `a time past the 24-hour clock is refused`() {
        assertNull(parseClock("24:00"))
        assertNull(parseClock("12:60"))
        assertNull(parseClock("99:99"))
    }

    @Test
    fun `anything that is not a time is refused`() {
        assertNull(parseClock(""))
        assertNull(parseClock("8"))
        assertNull(parseClock("8:3"))
        assertNull(parseClock("8:30 pm"))
        assertNull(parseClock("ab:cd"))
        assertNull(parseClock("8-30"))
    }
}
