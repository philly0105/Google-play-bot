package local.clashfarmer.desktop.platform

import android.graphics.Bitmap
import local.clashfarmer.core.Point

/**
 * The game window's screen and input: what [local.clashfarmer.service.AndroidTransport] took from
 * the phone's AccessibilityService, which is the screenshot, gesture dispatch and the global Back.
 * On the PC the native sidecar provides them (WGC capture, mouse messages posted to the GPG
 * window, Esc for Back). Any thread may call it, and callbacks may come on any thread, including
 * from inside a call to it: the transport sends a held swipe's second stroke from the first
 * stroke's [GestureCallback.onCompleted].
 */
interface GameSurface {
    /**
     * Takes one screenshot of the game window's client area and answers [callback] once, as the
     * phone's `takeScreenshot` did. The answer may never come; the transport stops waiting after a
     * few seconds and recycles a bitmap that arrives later.
     */
    fun takeScreenshot(callback: ScreenshotCallback)

    /**
     * Sends [strokes] as one gesture, with Android's `dispatchGesture` semantics: each stroke's
     * times count from the dispatch, the gesture ends when its last stroke does, and a newer
     * gesture cancels one still running. False when the gesture could not be sent, and [callback]
     * then gets no answer; otherwise [callback], when given, gets exactly one.
     */
    fun dispatchGesture(strokes: List<GestureStroke>, callback: GestureCallback?): Boolean

    /** Sends the game Android's Back, which GPG maps from Esc. True when it was sent. */
    fun back(): Boolean
}

/**
 * One finger's part in a gesture, Android's `GestureDescription.StrokeDescription` for the paths
 * the bot draws: a point when [from] equals [to], otherwise a straight line between them. It
 * starts [startMs] after the dispatch and lasts [durationMs]. With [willContinue] the finger stays
 * down at [to] when the gesture ends, for a stroke in a later gesture that [continues] this one.
 * That stroke must start at [to]; the surface cancels it otherwise, as Android does.
 */
data class GestureStroke(
    val from: Point,
    val to: Point,
    val startMs: Long,
    val durationMs: Long,
    val willContinue: Boolean = false,
    /** The stroke whose finger this one carries on with, still down; null for a new finger. */
    val continues: GestureStroke? = null
)

/** The answer to one dispatched gesture, Android's `GestureResultCallback`. */
interface GestureCallback {
    /** Every stroke ran to its end. */
    fun onCompleted()

    /** The gesture was cut short, by a newer gesture or by the surface. */
    fun onCancelled()
}

/** The answer to [GameSurface.takeScreenshot], Android's `TakeScreenshotCallback`. */
interface ScreenshotCallback {
    /** [bitmap] holds the client area at its real size; the receiver owns it and recycles it. */
    fun onSuccess(bitmap: Bitmap)

    /**
     * [retryable] marks a failure that passes on its own, which the transport waits out rather
     * than stopping the run; on the phone, the rate limit and the error during a rotation.
     */
    fun onFailure(message: String, retryable: Boolean)
}
