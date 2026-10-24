package local.clashfarmer.log

import local.clashfarmer.core.UserStop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What a failure snapshot is called and when one is worth writing. The frame is one the run
 * already captured; nothing here takes a screenshot.
 */
object FailureSnapshot {
    const val PREFIX = "failure-"

    /** A frame older than this was taken before whatever went wrong. */
    const val MAX_FRAME_AGE_MS = 120_000L

    /** Stops a person asked for: the overlay, the dashboard, the remote page, the screen going off. */
    fun isUserStop(reason: String?): Boolean = UserStop.matches(reason)

    fun fileName(event: String, nowMs: Long): String {
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
        return "$PREFIX${event.replace(Regex("[^A-Za-z0-9_]"), "_")}-$time.png"
    }

    /**
     * One file per frame: a timeout's frame is not written again by the restart or stop after it.
     * A frame captured before [runStartedAtMs] belongs to the previous run, possibly the other
     * account, and is never this run's snapshot.
     */
    fun shouldSave(frameAtMs: Long?, nowMs: Long, lastSavedFrameAtMs: Long?, runStartedAtMs: Long? = null): Boolean =
        frameAtMs != null && frameAtMs != lastSavedFrameAtMs && nowMs - frameAtMs <= MAX_FRAME_AGE_MS &&
            (runStartedAtMs == null || frameAtMs >= runStartedAtMs)
}
