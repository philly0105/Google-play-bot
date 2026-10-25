package local.clashfarmer.desktop.ui

import local.clashfarmer.desktop.ui.screens.FindingFix
import local.clashfarmer.desktop.ui.screens.clearable
import local.clashfarmer.desktop.ui.screens.findingFix
import local.clashfarmer.insight.Problem
import local.clashfarmer.insight.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Activity's Clear resolved hides, and where a calibration finding's button goes. */
class ActivityLinksTest {

    private fun problem(key: String, action: Problem.Action, dismissible: Boolean = true) =
        Problem(key, Tone.WARN, "title", "detail", timeMs = null, accountId = "main", action = action, dismissible = dismissible)

    @Test
    fun `Clear resolved leaves findings that still stop runs and markers only a check clears`() {
        val remote = problem("remote:2026-10-25", Problem.Action.DISMISS)
        val calibration = problem("health:main:not_calibrated", Problem.Action.OPEN_SETUP)
        val marker = problem("marker:main", Problem.Action.CHECK_VILLAGE, dismissible = false)
        assertEquals(listOf(remote), clearable(listOf(remote, calibration, marker)))
        assertEquals(emptyList<Problem>(), clearable(listOf(calibration, marker)))
    }

    @Test
    fun `a finding's button goes where it is fixed`() {
        assertEquals(FindingFix.CALIBRATE, findingFix(problem("health:main:not_calibrated", Problem.Action.OPEN_SETUP)))
        assertEquals(FindingFix.CALIBRATE, findingFix(problem("health:main:walls_incomplete", Problem.Action.OPEN_SETUP)))
        assertEquals(FindingFix.CALIBRATE, findingFix(problem("health:main:copied_anchors:ab12", Problem.Action.OPEN_SETUP)))
        assertEquals(FindingFix.SUPERVISED_ATTACK, findingFix(problem("health:main:not_verified", Problem.Action.OPEN_SETUP)))
        assertEquals(FindingFix.SETUP, findingFix(problem("health:main:wall_setup", Problem.Action.OPEN_SETUP)))
        assertEquals(FindingFix.SETUP, findingFix(problem("health:main:goal_unreachable", Problem.Action.OPEN_SETUP)))
        assertNull(findingFix(problem("remote:2026-10-25", Problem.Action.DISMISS)))
    }
}
