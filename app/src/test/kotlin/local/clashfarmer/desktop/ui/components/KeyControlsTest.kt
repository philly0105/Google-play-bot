package local.clashfarmer.desktop.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The keys a stepper and a segmented track answer. */
class KeyControlsTest {

    @Test
    fun `Up and Down step a stepper, other keys do not`() {
        assertEquals(1, stepKey(Key.DirectionUp))
        assertEquals(-1, stepKey(Key.DirectionDown))
        assertEquals(0, stepKey(Key.DirectionLeft))
        assertEquals(0, stepKey(Key.Enter))
    }

    @Test
    fun `Left and Right move to the nearest enabled option and stop at the ends`() {
        val all = { _: Int -> true }
        assertEquals(1, nextOption(0, 3, 1, all))
        assertEquals(1, nextOption(2, 3, -1, all))
        assertNull(nextOption(2, 3, 1, all))
        assertNull(nextOption(0, 3, -1, all))
        // A disabled option is skipped, and one with nothing enabled past it goes nowhere.
        assertEquals(2, nextOption(0, 3, 1) { it != 1 })
        assertNull(nextOption(0, 2, 1) { it != 1 })
    }
}
