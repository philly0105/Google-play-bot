package local.clashfarmer.desktop.platform

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import local.clashfarmer.core.Point
import local.clashfarmer.desktop.sidecar.InputState
import local.clashfarmer.desktop.sidecar.Sidecar
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.desktop.sidecar.Stroke
import kotlin.math.hypot

/**
 * [GameSurface] over the sidecar: frames from Windows Graphics Capture, gestures as one mouse
 * button posted to the game window, Esc for Back. Calls return at once and the answers come on
 * [scope]. Gestures and Back go out one at a time, in the order they were asked for.
 *
 * Where the PC differs from Android's dispatch:
 * - The sidecar takes a finger's whole path in one call. A stroke with
 *   [GestureStroke.willContinue] is answered at once without moving anything, and goes out with
 *   the stroke that [GestureStroke.continues] it. A continuation nobody waits for only lifts the
 *   finger, which never went down here, so nothing is sent.
 * - Two strokes at once are the phone's pinch, and the bot only pinches out, to the zoom-out
 *   limit. A pinch-out is [LIMIT_NOTCHES] wheel notches out around its centre; a pinch-in returns
 *   false.
 * - A sent gesture can't be cut short. Android cancels one when a real finger lands; here one is
 *   answered as cancelled when [personUsedGame] finds someone using Clash while it ran, and the
 *   transport stops the run as it does for a touch.
 * - A send the sidecar fails is logged and never answered, so the transport's deadline stops the
 *   run.
 */
class SidecarGameSurface(
    private val sidecar: Sidecar,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit
) : GameSurface {
    private val lock = Any()

    /** The path of a finger a stroke with willContinue left down, not sent yet. */
    private var heldPath: List<GestureStroke>? = null

    private val input = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (send in input) {
                try {
                    send()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Game input failed: ${e.message}")
                }
            }
        }
    }

    override fun takeScreenshot(callback: ScreenshotCallback) {
        scope.launch {
            val frame = try {
                sidecar.capture()
            } catch (e: SidecarException) {
                callback.onFailure(e.message ?: e.code, retryable = e.code in RETRYABLE)
                return@launch
            }
            callback.onSuccess(Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888))
        }
    }

    override fun dispatchGesture(strokes: List<GestureStroke>, callback: GestureCallback?): Boolean {
        val stroke = strokes.singleOrNull() ?: return pinch(strokes, callback)
        val held = synchronized(lock) { heldPath.also { heldPath = null } }
        val continued = stroke.continues
        val path = when {
            continued == null -> listOf(stroke)
            held != null && held.last() === continued && stroke.from == continued.to -> held + stroke
            else -> {
                // Android cancels a continuation that doesn't carry on a finger still down where it stopped.
                scope.launch { callback?.onCancelled() }
                return true
            }
        }
        when {
            stroke.willContinue -> {
                synchronized(lock) { heldPath = path }
                scope.launch { callback?.onCompleted() }
            }
            continued != null && callback == null -> Unit
            else -> send(callback) { sidecar.gesture(path.map { Stroke(it.from.x, it.from.y, it.to.x, it.to.y, it.durationMs) }) }
        }
        return true
    }

    override fun back(): Boolean {
        send(null) { sidecar.key(VK_ESCAPE) }
        return true
    }

    private fun pinch(strokes: List<GestureStroke>, callback: GestureCallback?): Boolean {
        if (strokes.size != 2) return false
        val (a, b) = strokes
        if (distance(a.to, b.to) >= distance(a.from, b.from)) return false
        val centre = Point((a.from.x + b.from.x) / 2, (a.from.y + b.from.y) / 2)
        send(callback) { sidecar.wheel(centre.x, centre.y, -LIMIT_NOTCHES) }
        return true
    }

    /** Queues [action]; once it has run, answers [callback], as cancelled when someone used Clash meanwhile. */
    private fun send(callback: GestureCallback?, action: suspend () -> Unit) {
        input.trySend {
            val started = System.currentTimeMillis()
            action()
            if (callback != null) {
                val withinMs = System.currentTimeMillis() - started + INPUT_SLACK_MS
                val person = try {
                    sidecar.input()
                } catch (e: SidecarException) {
                    null
                }
                if (person != null && personUsedGame(person, withinMs)) callback.onCancelled() else callback.onCompleted()
            }
        }
    }

    private fun distance(p: Point, q: Point) = hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble())

    companion object {
        /** Wheel notches past the PC's zoom-out limit, which 20 reach from any zoom (docs/gpg-spike.md). */
        const val LIMIT_NOTCHES = 25

        private const val VK_ESCAPE = 27

        /** Input this long before a gesture went out counts as input during it. */
        private const val INPUT_SLACK_MS = 250L

        /** No frame in time, or a sidecar that died; the next call starts another. */
        private val RETRYABLE = setOf("timeout", "failed")
    }
}

/**
 * Whether someone used Clash in the last [withinMs]: real input that recent, with the Clash window
 * active and the cursor over the game. Input while another window is active is someone working
 * beside the bot, which goes on.
 */
internal fun personUsedGame(input: InputState, withinMs: Long): Boolean =
    input.gameForeground && input.cursorInGame && input.idleMs <= withinMs
