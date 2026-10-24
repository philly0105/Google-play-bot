package local.clashfarmer.core

import kotlinx.coroutines.delay

/** A gesture the system cancelled because a real finger touched the screen while it ran. */
class GestureCancelledException(message: String) : RuntimeException(message)

/**
 * Sends a gesture and, when a press of Pause is what cancelled it, sends it again after Resume.
 *
 * Any real touch cancels an injected gesture, and the overlay's Pause button is a real touch. The
 * finger lands before the button registers the press, so the cancel arrives first and the pause
 * only [graceMs] later. A cancel that no pause follows is someone handling the phone, and still
 * stops the run. [isPaused] may hold the run first: the finger pulling the notification shade
 * down cancels a gesture too, and over immersive Clash the shade opens only on a second swipe,
 * 0.4 to 2.6 s after the cancel. So the first look is at [graceMs] and there are up to
 * [LOOKS] of them, about 3.2 s in all, before the cancel counts as someone handling the phone.
 *
 * [afterResume] runs after Resume and before the resend, for a gesture whose point may no longer
 * be on the screen it was read from; it throws to send nothing more. A gesture with no look is not
 * resent: after Resume it throws [TapAfterHold.HELD], and the caller's read loop decides afresh.
 */
suspend fun sendResendingAfterPause(
    what: String,
    isPaused: suspend () -> Boolean,
    awaitResume: suspend () -> Unit,
    isStopped: () -> Boolean,
    stop: (String) -> Unit,
    log: (String) -> Unit = {},
    graceMs: Long = 800L,
    maxResends: Int = 3,
    afterResume: (suspend () -> Unit)? = null,
    send: suspend () -> Unit
) {
    var resends = 0
    while (true) {
        try {
            send()
            return
        } catch (e: GestureCancelledException) {
            var paused = false
            for (look in 1..LOOKS) {
                delay(graceMs)
                paused = isPaused()
                if (paused) break
            }
            if (!paused || resends >= maxResends) {
                val reason = "$what was cancelled by a touch on the screen"
                stop(reason)
                throw StopException(reason)
            }
            resends++
            log("gesture_resent_after_pause")
            awaitResume()
            if (isStopped()) throw StopException("Run stopped while paused")
            // The screen may have changed under the pause, so nothing goes back out unchecked.
            if (afterResume == null) {
                log("gesture_held")
                throw StopException(TapAfterHold.HELD)
            }
            afterResume()
        }
    }
}

/** How many times [sendResendingAfterPause] looks for a pause after a cancel, [graceMs] apart. */
private const val LOOKS = 4
