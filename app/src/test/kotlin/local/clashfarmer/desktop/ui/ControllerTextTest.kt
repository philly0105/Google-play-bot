package local.clashfarmer.desktop.ui

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the controller's alerts say when an action fails, and the inspect report's state word. */
class ControllerTextTest {

    @Test
    fun `a failed action's alert is titled for what was asked`() {
        val failed = IllegalStateException("Blood needs a supervised attack first")
        assertEquals("Could not start the run", actionFailure("plan:{}", failed).first)
        assertEquals("Could not start the run", actionFailure("supervised", failed).first)
        assertEquals("Could not start the run", actionFailure("walls", failed).first)
        assertEquals("Could not clear the marker", actionFailure("acknowledge_wall:blood", failed).first)
        assertEquals("Could not choose that account", actionFailure("select:blood", failed).first)
        assertEquals("Could not choose that account", actionFailure("switch_only:blood", failed).first)
        assertEquals("Something went wrong", actionFailure("inspect", failed).first)
    }

    @Test
    fun `a worded failure shows as a sentence, and a fault sends the user to the log`() {
        assertEquals("Blood needs a supervised attack first.",
            actionFailure("plan:{}", IllegalStateException("Blood needs a supervised attack first")).second)
        assertEquals(ACCOUNT_GONE, actionFailure("acknowledge_wall:x", IllegalArgumentException(ACCOUNT_GONE)).second)
        assertEquals(SOMETHING_WENT_WRONG, actionFailure("plan:{", JSONException("Unterminated object at character 2")).second)
        assertEquals(SOMETHING_WENT_WRONG, actionFailure("plan:{}", NullPointerException()).second)
        assertEquals(SOMETHING_WENT_WRONG, actionFailure("plan:{}", IllegalStateException(" ")).second)
    }

    @Test
    fun `the inspect report names the screen in words`() {
        assertEquals("village home", readableState("VILLAGE_HOME"))
        assertEquals("unknown", readableState("UNKNOWN"))
    }
}
