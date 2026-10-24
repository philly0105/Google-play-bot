package local.clashfarmer.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Waits for one callback from a framework call that can silently never answer - the
 * AccessibilityService screenshot callback is the caller this exists for - under a
 * [timeoutMs] deadline.
 *
 * A missed deadline is a [StopException], the same stop a gesture that never completes
 * raises: a bare CancellationException would pass for a pause through every handler above.
 * A value delivered after the deadline, or after the run was stopped, goes to [disposeLate]
 * and is never resumed with - the frame's buffer still has to be released, and nothing may
 * act on a capture the run already gave up on. That includes a value that resumed the
 * waiter but lost the race to a deadline or stop before the waiter ran again.
 */
suspend fun <T : Any> awaitCallbackWithin(
    timeoutMs: Long,
    what: String,
    disposeLate: (T) -> Unit,
    start: (deliver: (T) -> Unit, fail: (Throwable) -> Unit) -> Unit
): T = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<T> { cont ->
        start(
            { if (cont.isActive) cont.resume(it) { _, late, _ -> disposeLate(late) } else disposeLate(it) },
            { if (cont.isActive) cont.resumeWithException(it) }
        )
    }
} ?: throw StopException("$what did not complete within ${timeoutMs}ms")
