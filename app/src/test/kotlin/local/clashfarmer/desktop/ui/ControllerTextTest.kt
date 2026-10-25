package local.clashfarmer.desktop.ui

import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.Observation
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        assertEquals("the village", readableState("HOME"))
        assertEquals("a base to attack (scout screen)", readableState("SCOUT"))
        assertEquals("a screen the bot doesn't know", readableState("UNKNOWN"))
        // Every state the reader can give has words of its own, not its lowercased name.
        ScreenState.entries.forEach { assertNotEquals(it.name, it.name.lowercase(), readableState(it.name)) }
        assertEquals("village home", readableState("VILLAGE_HOME"))
    }

    @Test
    fun `the inspect report leads with words and keeps the raw reading under Details`() {
        val observation = Observation(ScreenState.SCOUT, emptyList(), "Next 1234", Viewport(0, 0, 1740, 978))
        val report = inspectReport(observation, "inspect-20261025-120000.png").lines()
        assertEquals("The game shows a base to attack (scout screen).", report[0])
        assertEquals("Saved picture: inspect-20261025-120000.png", report[1])
        val details = report.indexOf("Details")
        assertTrue(details > 1)
        assertEquals("State: SCOUT", report[details + 1])
        assertEquals("Game area: [0, 0, 1740, 978]", report[details + 2])
        assertEquals("Next 1234", report.last())
        val long = inspectReport(observation.copy(rawText = "x".repeat(500)), "a.png").lines().last()
        assertEquals("x".repeat(400) + "...", long)
    }
}
