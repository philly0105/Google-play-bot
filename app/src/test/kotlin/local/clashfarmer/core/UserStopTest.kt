package local.clashfarmer.core

import local.clashfarmer.log.FailureSnapshot
import local.clashfarmer.service.RunNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStopTest {
    @Test fun everyUserStopSkipsTheFailureSnapshot() {
        assertEquals(5, UserStop.ALL.size)
        for (reason in UserStop.ALL) {
            assertTrue(reason, FailureSnapshot.isUserStop(reason))
            // A Stop the runner meets on its next capture comes back wrapped by the guard.
            assertTrue(reason, FailureSnapshot.isUserStop("Session is stopped: $reason"))
        }
    }

    @Test fun failuresAreNotUserStops() {
        listOf(
            "Screen transition timed out waiting for [HOME] (last seen: UNKNOWN) reading: ",
            "Run error: boom",
            "Session timeout elapsed (120m)",
            "Clash is not in foreground (detected: com.android.chrome)",
            "Active session interrupted: app switched to com.android.chrome",
            "Service destroyed"
        ).forEach { assertFalse(it, FailureSnapshot.isUserStop(it)) }
        assertFalse(FailureSnapshot.isUserStop(null))
    }

    @Test fun noUserStopIsRecoverable() {
        UserStop.ALL.forEach { assertFalse(it, RecoveryPolicy.isRecoverable(it)) }
    }

    @Test fun aStopButtonNoticeIsAnOrdinaryStop() {
        listOf(UserStop.FLOATING_PANEL, UserStop.DASHBOARD, UserStop.REMOTE, UserStop.NOTIFICATION).forEach {
            assertEquals(it, RunNotice.Kind.STOPPED, RunNotice.stopped(null, it).kind)
        }
    }
}
