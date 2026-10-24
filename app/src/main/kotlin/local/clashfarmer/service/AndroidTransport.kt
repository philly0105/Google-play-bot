package local.clashfarmer.service

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import local.clashfarmer.core.GestureCancelledException
import local.clashfarmer.core.Point
import local.clashfarmer.core.RunGuard
import local.clashfarmer.core.StopException
import local.clashfarmer.core.TapAfterHold
import local.clashfarmer.core.awaitCallbackWithin
import local.clashfarmer.core.sendResendingAfterPause
import local.clashfarmer.desktop.platform.GameSurface
import local.clashfarmer.desktop.platform.GestureCallback
import local.clashfarmer.desktop.platform.GestureStroke
import local.clashfarmer.desktop.platform.ScreenshotCallback
import local.clashfarmer.log.AppLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CapturedImage(
    val bitmap: Bitmap,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Desktop port: every guard is the phone's, unchanged, including the 1.1 s floor between
 * screenshots that Android 11 needed. Only the screenshot, the gesture dispatch and Back go to
 * [surface] where the phone called its AccessibilityService, with the same semantics.
 */
class AndroidTransport(
    private val service: FarmerService,
    private val runGuard: RunGuard,
    private val surface: GameSurface
) {

    private companion object {
        /**
         * Android 11 rejects a screenshot request made less than a second after the previous
         * one, and it measures from request to request, not from the last result.
         */
        const val MIN_CAPTURE_INTERVAL_MS = 1100L
        const val CAPTURE_INTERVAL_RETRIES = 3

        /**
         * The screenshot callback is not guaranteed to arrive at all; without a bound here a
         * capture waits on it until the session deadline, hours later.
         */
        const val SCREENSHOT_CALLBACK_TIMEOUT_MS = 5000L
    }

    /**
     * Thrown for the framework failures that pass on their own, which are worth waiting out
     * rather than stopping a run for.
     *
     * The rate limit is one. The other is the internal error the framework returns while the
     * display is rotating: starting the wall phase from the helper turns the screen from the
     * helper's portrait to Clash's landscape, and the first capture after that lands mid
     * rotation often enough to matter - twice in five minutes at 22:54 and 22:58 on
     * 17 September, each one ending the run before it read a single frame.
     */
    private class RetryableCaptureException(message: String) : RuntimeException(message)

    private val captureMutex = Mutex()
    private var lastCaptureRequestMs: Long = 0L

    /**
     * [RunGuard.pauses] as it stood just before the last screenshot. A gesture built on that
     * frame is held when the counter has moved since, even if the pause and its Resume both fell
     * inside the reading and the gate had nothing left to wait for.
     */
    @Volatile private var pauseStamp: Long = 0L

    /** When the last screenshot was taken, on the frame's own clock ([CapturedImage.timestampMs]). */
    @Volatile var lastCaptureAt: Long = 0L
        private set

    suspend fun capture(currentRunToken: Long? = null): CapturedImage {
        if (currentRunToken != null) {
            // The shade can be down before its window change arrives; the check would end the run.
            service.awaitClearToAct("capture")
            runGuard.checkCanAct(
                token = currentRunToken,
                foregroundPackage = service.getActiveForegroundPackage(),
                isUnlocked = service.isDeviceUnlocked(),
                currentViewport = service.currentDisplayViewport()
            )
        }

        return captureMutex.withLock {
            var lastError: RetryableCaptureException? = null
            for (attempt in 0 until CAPTURE_INTERVAL_RETRIES) {
                val wait = MIN_CAPTURE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCaptureRequestMs)
                if (wait > 0) {
                    delay(wait)
                }
                lastCaptureRequestMs = SystemClock.uptimeMillis()
                try {
                    val stamp = runGuard.pauses
                    val captured = service.overlay?.hideForCapture {
                        performScreenshotCapture()
                    } ?: performScreenshotCapture()
                    pauseStamp = stamp
                    lastCaptureAt = captured.timestampMs
                    // Callers recycle the bitmap right after this returns, so the copy is made here.
                    runCatching { service.latestFrame.offer(captured.bitmap, captured.timestampMs) }
                    return@withLock captured
                } catch (e: RetryableCaptureException) {
                    lastError = e
                }
            }
            throw StopException(
                lastError?.message
                    ?: "Screenshot capture failed $CAPTURE_INTERVAL_RETRIES times in a row"
            )
        }
    }

    private suspend fun performScreenshotCapture(): CapturedImage = awaitCallbackWithin(
        timeoutMs = SCREENSHOT_CALLBACK_TIMEOUT_MS,
        what = "Screenshot capture",
        disposeLate = { it.bitmap.recycle() }
    ) { deliver, fail ->
        val acquisitionTime = System.currentTimeMillis()

        surface.takeScreenshot(
            object : ScreenshotCallback {
                override fun onSuccess(bitmap: Bitmap) {
                    deliver(CapturedImage(bitmap, acquisitionTime))
                }

                override fun onFailure(message: String, retryable: Boolean) {
                    val msg = "Screenshot capture failed: $message"
                    System.err.println("AndroidTransport: $msg")
                    fail(if (retryable) RetryableCaptureException(msg) else StopException(msg))
                }
            }
        )
    }

    /**
     * Runs one gesture with a deadline, and turns a missed deadline into a StopException.
     *
     * `withTimeout` throws TimeoutCancellationException, which is a CancellationException: the
     * wall phase's StopException handler let it straight through, and the service reported a
     * five-second tap as "Session timeout elapsed". A gesture that never completes is a stop
     * like any other failed gesture, and it has to look like one to every handler above it.
     */
    private suspend fun withinGestureTimeout(timeoutMs: Long, what: String, block: suspend () -> Unit) {
        withTimeoutOrNull(timeoutMs) { block() }
            ?: throw StopException("$what did not complete within ${timeoutMs}ms")
    }

    /**
     * [afterPause] is a look taken after the run was held or paused just before this tap, or
     * when [decidedOn] is older than [TapAfterHold.MAX_DECISION_AGE_MS], and before it is sent;
     * it throws to send nothing. The tap's point was read before the pause. A tap with no look is
     * held instead, with [TapAfterHold.HELD], and its caller reads the screen again.
     * [decidedOn] is the [CapturedImage.timestampMs] of the frame the tap was read from.
     * [onSend] runs just before each dispatch, so a caller can tell a pause that came before the
     * tap went out from one that cut it short.
     */
    suspend fun tap(
        point: Point,
        runToken: Long,
        afterPause: (suspend () -> Unit)? = null,
        onSend: (() -> Unit)? = null,
        decidedOn: Long? = null
    ) {
        // A resend after a look stands on that look's frame, not on the one the tap was read from.
        var basis = decidedOn
        val looked: (suspend () -> Unit)? = if (afterPause == null) null else suspend {
            afterPause()
            if (basis != null) basis = lastCaptureAt
        }
        resendingAfterPause("Tap at $point", point, runToken, looked) {
            tapOnce(point, runToken, decidedOn = basis, afterPause = afterPause, onSend = onSend)
        }
    }

    suspend fun tapButton(
        point: Point,
        runToken: Long,
        afterPause: (suspend () -> Unit)? = null,
        onSend: (() -> Unit)? = null,
        decidedOn: Long? = null
    ) {
        // Validate the calibration before clipping an offset at a display edge. A pause retry
        // reuses this sampled point, so cancelling a gesture cannot move it to another control.
        runGuard.validatePoint(point)
        val target = local.clashfarmer.core.buttonTapPoint(point, runGuard.displayWidth, runGuard.displayHeight)
        tap(target, runToken, afterPause, onSend, decidedOn)
    }

    /**
     * [tapButton] that is never sent twice. A pause cancels a gesture and [tap] would send it
     * again afterwards, which for the press that accepts a purchase could buy twice. A hold under
     * the shade or a person's Pause just before it refuses it too: the dialog it was read from
     * may have changed. A frame that only aged may still be looked at, once [afterPause] is given.
     */
    suspend fun tapButtonOnce(
        point: Point,
        runToken: Long,
        decidedOn: Long? = null,
        afterPause: (suspend () -> Unit)? = null,
        onSend: (() -> Unit)? = null
    ) {
        runGuard.validatePoint(point)
        val target = local.clashfarmer.core.buttonTapPoint(point, runGuard.displayWidth, runGuard.displayHeight)
        tapOnce(target, runToken, refuseAfterWait = true, decidedOn = decidedOn, afterPause = afterPause, onSend = onSend)
    }

    private suspend fun tapOnce(
        point: Point,
        runToken: Long,
        refuseAfterWait: Boolean = false,
        decidedOn: Long? = null,
        afterPause: (suspend () -> Unit)? = null,
        onSend: (() -> Unit)? = null
    ) {
        val stamp = clearToSend("tap", point, decidedOn, refuseAfterWait, afterPause)
        withinGestureTimeout(5000L, "Tap at $point") {
            suspendCancellableCoroutine { cont ->
                try {
                    runGuard.validatePoint(point)
                    runGuard.checkCanAct(
                        token = runToken,
                        foregroundPackage = service.getActiveForegroundPackage(),
                        isUnlocked = service.isDeviceUnlocked(),
                        currentViewport = service.currentDisplayViewport()
                    )
                    heldIfPausedSince(stamp, "tap", point)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                val actionToken = runGuard.nextActionToken(runToken)
                if (actionToken == 0L) {
                    cont.resumeWithException(StopException("Failed to obtain action token; run is invalid"))
                    return@suspendCancellableCoroutine
                }

                val stroke = GestureStroke(point, point, 0, 50)

                onSend?.invoke()
                val dispatched = surface.dispatchGesture(
                    listOf(stroke),
                    object : GestureCallback {
                        override fun onCompleted() {
                            if (!runGuard.validateCallback(runToken, actionToken)) {
                                if (cont.isActive) {
                                    cont.resumeWithException(StopException("Action callback rejected: stale action token $actionToken"))
                                }
                                return
                            }
                            if (cont.isActive) {
                                cont.resume(Unit)
                            }
                        }

                        override fun onCancelled() {
                            if (cont.isActive) {
                                // Stopping is left to the caller: a press of Pause cancels too.
                                cont.resumeWithException(
                                    if (runGuard.validateCallback(runToken, actionToken)) GestureCancelledException("Tap gesture at $point was cancelled")
                                    else StopException("Tap gesture at $point was cancelled")
                                )
                            }
                        }
                    }
                )

                if (!dispatched) {
                    runGuard.stop("Failed to dispatch tap gesture (dispatchGesture returned false)", expectedRunToken = runToken)
                    if (cont.isActive) {
                        cont.resumeWithException(StopException("Failed to dispatch tap gesture at $point"))
                    }
                }
            }
        }
    }

    /**
     * [holdMs] above zero keeps the finger all but still at [end] that long before it lifts. A
     * finger that lifts while it is moving flings a list on after it, and a list read while it
     * coasts is read in the wrong place: on 15 October a saved army's Use moved between two looks
     * and a Wall row picked at the list's bottom edge was gone on the next frame. A finger that
     * has stopped should leave the list where the drag put it; no run has shown that yet, and
     * wall_list_page's overlap is where it will show. The hold is a second stroke continuing the
     * first from its end point, so the finger never leaves the screen between them.
     *
     * After a hold or a pause the swipe is sent only once [afterPause] has looked, and is held
     * with [TapAfterHold.HELD] when there is no look.
     */
    suspend fun swipe(
        start: Point,
        end: Point,
        durationMs: Long,
        runToken: Long,
        holdMs: Long = 0L,
        afterPause: (suspend () -> Unit)? = null
    ) = resendingAfterPause("Swipe from $start to $end", start, runToken, afterPause) {
        swipeOnce(start, end, durationMs, runToken, holdMs, afterPause)
    }

    private suspend fun swipeOnce(
        start: Point,
        end: Point,
        durationMs: Long,
        runToken: Long,
        holdMs: Long,
        afterPause: (suspend () -> Unit)?
    ) {
        val stamp = clearToSend("swipe", start, decidedOn = null, once = false, afterPause = afterPause)
        val strokes = swipeStrokes(start, end, durationMs, holdMs)
        withinGestureTimeout(durationMs + holdMs + 5000L, "Swipe from $start to $end") {
            suspendCancellableCoroutine { cont ->
                try {
                    for (part in strokes) {
                        runGuard.validatePoint(part.from)
                        runGuard.validatePoint(part.to)
                    }
                    runGuard.checkCanAct(
                        token = runToken,
                        foregroundPackage = service.getActiveForegroundPackage(),
                        isUnlocked = service.isDeviceUnlocked(),
                        currentViewport = service.currentDisplayViewport()
                    )
                    heldIfPausedSince(stamp, "swipe", start)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                val actionToken = runGuard.nextActionToken(runToken)
                if (actionToken == 0L) {
                    cont.resumeWithException(StopException("Failed to obtain action token; run is invalid"))
                    return@suspendCancellableCoroutine
                }

                val drag = strokes.first()
                val stroke = GestureStroke(drag.from, drag.to, 0, drag.durationMs, drag.willContinue)
                // The system cancels a continuation that does not start exactly where the stroke
                // before it ended; swipeStrokes starts the hold at the drag's end.
                val hold = strokes.getOrNull(1)?.let { GestureStroke(it.from, it.to, 0, it.durationMs, it.willContinue, continues = stroke) }

                // The hold is sent when the drag completes.
                fun send(part: GestureStroke, next: GestureStroke?) {
                    val dispatched = surface.dispatchGesture(
                        listOf(part),
                        object : GestureCallback {
                            override fun onCompleted() {
                                val current = runGuard.validateCallback(runToken, actionToken)
                                // Read once. A cancel landing between two reads sent neither the
                                // lift nor the hold, and the drag had left the finger down.
                                val waiting = cont.isActive
                                if (next != null) when (afterDrag(current, waiting)) {
                                    AfterDrag.HOLD -> {
                                        send(next, null)
                                        return
                                    }
                                    // A stop or a timeout came during the drag and nobody waits for
                                    // the swipe any more. The finger is still lifted where it is,
                                    // or Clash keeps a finger held on the list until the next touch.
                                    AfterDrag.LIFT ->
                                        surface.dispatchGesture(listOf(next), null)
                                }
                                if (!current) {
                                    if (waiting) {
                                        cont.resumeWithException(StopException("Action callback rejected: stale action token $actionToken"))
                                    }
                                    return
                                }
                                if (waiting) cont.resume(Unit)
                            }

                            override fun onCancelled() {
                                if (cont.isActive) {
                                    // Stopping is left to the caller: a press of Pause cancels too.
                                    cont.resumeWithException(
                                        if (runGuard.validateCallback(runToken, actionToken)) GestureCancelledException("Swipe gesture cancelled")
                                        else StopException("Swipe gesture cancelled")
                                    )
                                }
                            }
                        }
                    )

                    if (!dispatched) {
                        runGuard.stop("Failed to dispatch swipe gesture (dispatchGesture returned false)", expectedRunToken = runToken)
                        if (cont.isActive) {
                            cont.resumeWithException(StopException("Failed to dispatch swipe gesture from $start to $end"))
                        }
                    }
                }
                send(stroke, hold)
            }
        }
    }

    /**
     * Two fingers dragged together or apart, for the game's camera zoom.
     *
     * The attack view opens zoomed in far enough that the strip of ground a troop may be dropped
     * on is off the screen entirely: on 17 September forty Valkyries sat in their card through
     * eight attacks while the spells beside them went down fine, because a spell may be thrown
     * anywhere and a troop may not. Five taps spread to the corners of the screen placed nothing.
     * Zooming out first is what puts deployable ground in frame.
     *
     * `startRadius` greater than `endRadius` zooms out. Both fingers move at once, which is what
     * separates this from two swipes and the reason it needs the two-stroke form.
     *
     * After a hold or a pause the pinch is sent only once [afterPause] has looked, and is held
     * with [TapAfterHold.HELD] when there is no look.
     */
    suspend fun pinch(
        center: Point,
        startRadius: Int,
        endRadius: Int,
        durationMs: Long,
        runToken: Long,
        afterPause: (suspend () -> Unit)? = null
    ) = resendingAfterPause("Pinch at $center", center, runToken, afterPause) {
        pinchOnce(center, startRadius, endRadius, durationMs, runToken, afterPause)
    }

    private suspend fun pinchOnce(
        center: Point,
        startRadius: Int,
        endRadius: Int,
        durationMs: Long,
        runToken: Long,
        afterPause: (suspend () -> Unit)?
    ) {
        val left = Point(center.x - startRadius, center.y)
        val right = Point(center.x + startRadius, center.y)
        val leftEnd = Point(center.x - endRadius, center.y)
        val rightEnd = Point(center.x + endRadius, center.y)

        val stamp = clearToSend("pinch", center, decidedOn = null, once = false, afterPause = afterPause)
        withinGestureTimeout(durationMs + 5000L, "Pinch at $center") {
            suspendCancellableCoroutine { cont ->
                try {
                    for (pt in listOf(left, right, leftEnd, rightEnd)) {
                        runGuard.validatePoint(pt)
                    }
                    runGuard.checkCanAct(
                        token = runToken,
                        foregroundPackage = service.getActiveForegroundPackage(),
                        isUnlocked = service.isDeviceUnlocked(),
                        currentViewport = service.currentDisplayViewport()
                    )
                    heldIfPausedSince(stamp, "pinch", center)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                val actionToken = runGuard.nextActionToken(runToken)
                if (actionToken == 0L) {
                    cont.resumeWithException(StopException("Failed to obtain action token; run is invalid"))
                    return@suspendCancellableCoroutine
                }

                fun strokeFrom(from: Point, to: Point): GestureStroke = GestureStroke(from, to, 0, durationMs)

                val gesture = listOf(strokeFrom(left, leftEnd), strokeFrom(right, rightEnd))

                val dispatched = surface.dispatchGesture(
                    gesture,
                    object : GestureCallback {
                        override fun onCompleted() {
                            if (!runGuard.validateCallback(runToken, actionToken)) {
                                if (cont.isActive) {
                                    cont.resumeWithException(StopException("Action callback rejected: stale action token $actionToken"))
                                }
                                return
                            }
                            if (cont.isActive) {
                                cont.resume(Unit)
                            }
                        }

                        override fun onCancelled() {
                            if (cont.isActive) {
                                // Stopping is left to the caller: a press of Pause cancels too.
                                cont.resumeWithException(
                                    if (runGuard.validateCallback(runToken, actionToken)) GestureCancelledException("Pinch gesture cancelled")
                                    else StopException("Pinch gesture cancelled")
                                )
                            }
                        }
                    }
                )

                if (!dispatched) {
                    runGuard.stop("Failed to dispatch pinch gesture (dispatchGesture returned false)", expectedRunToken = runToken)
                    if (cont.isActive) {
                        cont.resumeWithException(StopException("Failed to dispatch pinch gesture at $center"))
                    }
                }
            }
        }
    }

    /**
     * Waits out the gate, then decides whether a gesture read from an earlier frame may go out.
     * A hold, a pause since the last screenshot or a [decidedOn] past
     * [TapAfterHold.MAX_DECISION_AGE_MS] calls for [afterPause], at most [TapAfterHold.MAX_LOOKS]
     * times, and the gate is asked again after each look because the shade can come down during
     * it. Returns the pause counter the gesture must still match just before it is dispatched.
     *
     * 15 October, 05:31 UTC: the Attack tap went out 19 s after the frame it was read from, past
     * a pause, and landed about 10 px under the store's $0.99 card.
     */
    private suspend fun clearToSend(
        kind: String,
        point: Point,
        decidedOn: Long?,
        once: Boolean,
        afterPause: (suspend () -> Unit)?
    ): Long {
        var basis = decidedOn
        var stamp = pauseStamp
        var looks = 0
        while (true) {
            // The gate is always asked; the counter catches a pause and Resume it never saw.
            val waited = service.awaitClearToAct(kind) || runGuard.pauses != stamp
            val now = System.currentTimeMillis()
            val aged = TapAfterHold.aged(basis, now)
            when (TapAfterHold.next(waited, aged, runGuard.isStopped(), once, afterPause != null, looks)) {
                TapAfterHold.Next.SEND -> return stamp
                TapAfterHold.Next.LOOK -> {
                    looks++
                    try {
                        afterPause?.invoke()
                    } catch (e: StopException) {
                        if (TapAfterHold.isHeld(e)) logHeld("$kind look", point, basis, now, waited, looks)
                        throw e
                    }
                    if (decidedOn != null) basis = lastCaptureAt
                    stamp = pauseStamp
                }
                TapAfterHold.Next.REFUSE -> {
                    logHeld(kind, point, basis, now, waited, looks)
                    throw StopException(if (once && waited) TapAfterHold.REFUSED else TapAfterHold.HELD)
                }
            }
        }
    }

    /** The last check before dispatch: a pause since [clearToSend] passed holds the gesture. */
    private fun heldIfPausedSince(stamp: Long, kind: String, point: Point) {
        if (runGuard.pauses == stamp) return
        logHeld("$kind dispatch", point, basis = null, now = 0L, waited = true, looks = 0)
        throw StopException(TapAfterHold.HELD)
    }

    private fun logHeld(where: String, point: Point, basis: Long?, now: Long, waited: Boolean, looks: Int) {
        AppLog.log(
            "gesture_held",
            "where" to where,
            "x" to point.x,
            "y" to point.y,
            "ageMs" to basis?.let { now - it },
            "waited" to waited,
            "looks" to looks
        )
    }

    private suspend fun resendingAfterPause(
        what: String,
        at: Point,
        runToken: Long,
        afterPause: (suspend () -> Unit)? = null,
        send: suspend () -> Unit
    ) =
        sendResendingAfterPause(
            what = what,
            isPaused = {
                service.holdIfShadeOver("cancelled gesture")
                runGuard.isPaused()
            },
            awaitResume = { service.awaitResume(what) },
            isStopped = runGuard::isStopped,
            stop = { reason -> runGuard.stop(reason, expectedRunToken = runToken) },
            log = { event ->
                if (event == "gesture_held") logHeld("resend", at, basis = null, now = 0L, waited = true, looks = 0)
                else AppLog.log(event, "gesture" to what)
            },
            afterResume = afterPause,
            send = send
        )

    /** Desktop: the global Back, which the phone sent through FarmerService.performGlobalAction. */
    fun back(): Boolean = surface.back()

    fun stop(reason: String) {
        runGuard.stop(reason)
    }
}

/**
 * How far a held swipe's finger creeps on while it is held, in pixels. A finger held dead still
 * sends no moves, so whatever takes the release speed from the last moves would still find the
 * drag's; Android's own VelocityTracker does, and ignores the lift's time. A creep leaves the last
 * moves at almost no speed.
 */
private const val HOLD_CREEP = 2

/** One stroke of a swipe. [willContinue] keeps the finger down for the next, which starts at [to]. */
internal data class SwipeStroke(val from: Point, val to: Point, val durationMs: Long, val willContinue: Boolean)

/**
 * A swipe's strokes in the order they are sent: the drag, then with [holdMs] above zero the hold,
 * which continues the drag from [end] and creeps [HOLD_CREEP] further the way it went. The hold
 * is sent only once the drag has completed.
 */
internal fun swipeStrokes(start: Point, end: Point, durationMs: Long, holdMs: Long): List<SwipeStroke> {
    val drag = SwipeStroke(start, end, durationMs, willContinue = holdMs > 0)
    if (holdMs <= 0) return listOf(drag)
    val creep = Point(end.x + HOLD_CREEP * Integer.signum(end.x - start.x), end.y + HOLD_CREEP * Integer.signum(end.y - start.y))
    return listOf(drag, SwipeStroke(end, creep, holdMs, willContinue = false))
}

/** What a held swipe's completed drag sends: the hold the swipe waits on, or the hold as a lift alone. */
internal enum class AfterDrag { HOLD, LIFT }

/**
 * The drag left the finger down, and only the hold lifts it, so one of the two goes whatever the
 * callback finds: the hold to wait on when the action is still current and the swipe still
 * waits, and otherwise the lift alone.
 */
internal fun afterDrag(current: Boolean, waiting: Boolean): AfterDrag =
    if (current && waiting) AfterDrag.HOLD else AfterDrag.LIFT
