package local.clashfarmer.desktop.platform

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.clashfarmer.core.Point
import local.clashfarmer.desktop.sidecar.FakeSidecar
import local.clashfarmer.desktop.sidecar.InputState
import local.clashfarmer.desktop.sidecar.Sidecar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarGameSurfaceTest {

    private val fake = FakeSidecar()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val surface = SidecarGameSurface(Sidecar { fake.connection }, scope) {}

    @After
    fun stop() = scope.cancel()

    private class Answer : GestureCallback {
        val result = CompletableDeferred<String>()
        override fun onCompleted() { result.complete("completed") }
        override fun onCancelled() { result.complete("cancelled") }
        suspend fun await() = withTimeout(5000) { result.await() }
    }

    private fun stroke(from: Point, to: Point, ms: Long, willContinue: Boolean = false, continues: GestureStroke? = null) =
        GestureStroke(from, to, 0, ms, willContinue, continues)

    private fun strokesOf(request: FakeSidecar.Request): List<String> {
        assertEquals("gesture", request.op)
        val strokes = request.json.getJSONArray("strokes")
        return List(strokes.length()) { i ->
            val s = strokes.getJSONObject(i)
            "${s.getJSONArray("from")}->${s.getJSONArray("to")}/${s.getLong("ms")}"
        }
    }

    private fun answerInput(idleMs: Long, foreground: Boolean = true, cursorInGame: Boolean = true) {
        val request = fake.next()
        assertEquals("input", request.op)
        fake.ok(request) { put("idleMs", idleMs).put("gameForeground", foreground).put("cursorInGame", cursorInGame) }
    }

    private fun fail(request: FakeSidecar.Request, code: String, error: String) =
        fake.reply(JSONObject().put("id", request.id).put("ok", false).put("code", code).put("error", error))

    @Test
    fun `a tap is one stroke, answered once nobody is seen using Clash`() = runBlocking {
        val answer = Answer()
        assertTrue(surface.dispatchGesture(listOf(stroke(Point(10, 20), Point(10, 20), 50)), answer))
        val gesture = fake.next()
        assertEquals(listOf("[10,20]->[10,20]/50"), strokesOf(gesture))
        fake.ok(gesture)
        answerInput(idleMs = 60_000)
        assertEquals("completed", answer.await())
    }

    @Test
    fun `a held swipe goes out as one path once the hold continues the drag`() = runBlocking {
        val dragAnswer = Answer()
        val drag = stroke(Point(1505, 650), Point(1505, 320), 500, willContinue = true)
        assertTrue(surface.dispatchGesture(listOf(drag), dragAnswer))
        assertEquals("completed", dragAnswer.await())

        val holdAnswer = Answer()
        assertTrue(surface.dispatchGesture(listOf(stroke(Point(1505, 320), Point(1505, 318), 300, continues = drag)), holdAnswer))
        val gesture = fake.next()
        assertEquals(listOf("[1505,650]->[1505,320]/500", "[1505,320]->[1505,318]/300"), strokesOf(gesture))
        fake.ok(gesture)
        answerInput(idleMs = 60_000)
        assertEquals("completed", holdAnswer.await())
    }

    @Test
    fun `a lift nobody waits for sends nothing`() = runBlocking {
        val drag = stroke(Point(100, 600), Point(100, 300), 500, willContinue = true)
        surface.dispatchGesture(listOf(drag), Answer())
        assertTrue(surface.dispatchGesture(listOf(stroke(Point(100, 300), Point(100, 298), 300, continues = drag)), null))

        surface.dispatchGesture(listOf(stroke(Point(5, 5), Point(5, 5), 50)), null)
        assertEquals(listOf("[5,5]->[5,5]/50"), strokesOf(fake.next()))
    }

    @Test
    fun `a continuation that doesn't start where the finger stopped is cancelled`() = runBlocking {
        val drag = stroke(Point(100, 600), Point(100, 300), 500, willContinue = true)
        surface.dispatchGesture(listOf(drag), Answer())
        val answer = Answer()
        assertTrue(surface.dispatchGesture(listOf(stroke(Point(100, 310), Point(100, 308), 300, continues = drag)), answer))
        assertEquals("cancelled", answer.await())
    }

    @Test
    fun `someone using Clash during a gesture cancels it`() = runBlocking {
        val answer = Answer()
        surface.dispatchGesture(listOf(stroke(Point(10, 20), Point(10, 20), 50)), answer)
        fake.ok(fake.next())
        answerInput(idleMs = 30)
        assertEquals("cancelled", answer.await())
    }

    @Test
    fun `only input in Clash counts as someone using it`() {
        assertTrue(personUsedGame(InputState(idleMs = 100, gameForeground = true, cursorInGame = true), withinMs = 400))
        assertFalse(personUsedGame(InputState(idleMs = 100, gameForeground = false, cursorInGame = true), withinMs = 400))
        assertFalse(personUsedGame(InputState(idleMs = 100, gameForeground = true, cursorInGame = false), withinMs = 400))
        assertFalse(personUsedGame(InputState(idleMs = 900, gameForeground = true, cursorInGame = true), withinMs = 400))
    }

    @Test
    fun `a pinch-out turns the wheel out to the limit around its centre`() = runBlocking {
        val answer = Answer()
        val out = listOf(stroke(Point(570, 489), Point(830, 489), 350), stroke(Point(1170, 489), Point(910, 489), 350))
        assertTrue(surface.dispatchGesture(out, answer))
        val wheel = fake.next()
        assertEquals("wheel", wheel.op)
        assertEquals(870, wheel.json.getInt("x"))
        assertEquals(489, wheel.json.getInt("y"))
        assertEquals(-SidecarGameSurface.LIMIT_NOTCHES, wheel.json.getInt("notches"))
        fake.ok(wheel)
        answerInput(idleMs = 60_000)
        assertEquals("completed", answer.await())

        val apart = listOf(stroke(Point(830, 489), Point(570, 489), 350), stroke(Point(910, 489), Point(1170, 489), 350))
        assertFalse(surface.dispatchGesture(apart, Answer()))
    }

    @Test
    fun `back is Esc, sent after the gesture before it`() = runBlocking {
        surface.dispatchGesture(listOf(stroke(Point(5, 5), Point(5, 5), 50)), null)
        assertTrue(surface.back())
        fake.ok(fake.next())
        val key = fake.next()
        assertEquals("key", key.op)
        assertEquals(27, key.json.getInt("vk"))
    }

    @Test
    fun `a frame arrives as a bitmap, and only failures that pass are retryable`() = runBlocking {
        val answers = Channel<String>(Channel.UNLIMITED)
        val callback = object : ScreenshotCallback {
            override fun onSuccess(bitmap: Bitmap) { answers.trySend("frame ${bitmap.width}x${bitmap.height} ${Integer.toHexString(bitmap.getPixel(1, 0))}") }
            override fun onFailure(message: String, retryable: Boolean) { answers.trySend("$message retryable=$retryable") }
        }
        surface.takeScreenshot(callback)
        val capture = fake.next()
        assertEquals("capture", capture.op)
        fake.ok(capture, byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0)) { put("width", 2).put("height", 1).put("timestampMs", 1L) }
        assertEquals("frame 2x1 ff060504", withTimeout(5000) { answers.receive() })

        surface.takeScreenshot(callback)
        fail(fake.next(), "timeout", "No frame within 3000 ms")
        answerSession(locked = false, displayOn = true)
        assertEquals("No frame within 3000 ms retryable=true", withTimeout(5000) { answers.receive() })

        surface.takeScreenshot(callback)
        fail(fake.next(), "no_window", "No Clash window")
        assertEquals("No Clash window retryable=false", withTimeout(5000) { answers.receive() })
    }

    private fun screenshots(): Pair<Channel<String>, ScreenshotCallback> {
        val answers = Channel<String>(Channel.UNLIMITED)
        return answers to object : ScreenshotCallback {
            override fun onSuccess(bitmap: Bitmap) { answers.trySend("frame") }
            override fun onFailure(message: String, retryable: Boolean) { answers.trySend("$message retryable=$retryable") }
        }
    }

    private fun answerCapture(ageMs: Long) {
        val capture = fake.next()
        assertEquals("capture", capture.op)
        fake.ok(capture, byteArrayOf(1, 2, 3, 0)) { put("width", 1).put("height", 1).put("timestampMs", 1L).put("ageMs", ageMs) }
    }

    private fun answerSession(locked: Boolean?, displayOn: Boolean?) {
        val request = fake.next()
        assertEquals("sessionState", request.op)
        fake.ok(request) { put("locked", locked ?: JSONObject.NULL).put("displayOn", displayOn ?: JSONObject.NULL) }
    }

    @Test
    fun `an old frame from a locked PC stops the run, not to be retried`() = runBlocking {
        val (answers, callback) = screenshots()
        surface.takeScreenshot(callback)
        answerCapture(ageMs = 12_000)
        answerSession(locked = true, displayOn = true)
        assertEquals("${SidecarGameSurface.FRAMES_STOPPED} retryable=false", withTimeout(5000) { answers.receive() })
    }

    @Test
    fun `an old frame with the display off stops the run`() = runBlocking {
        val (answers, callback) = screenshots()
        surface.takeScreenshot(callback)
        answerCapture(ageMs = 12_000)
        answerSession(locked = false, displayOn = false)
        assertEquals("${SidecarGameSurface.FRAMES_STOPPED} retryable=false", withTimeout(5000) { answers.receive() })
    }

    @Test
    fun `no frame in time on a locked PC stops the run too`() = runBlocking {
        val (answers, callback) = screenshots()
        surface.takeScreenshot(callback)
        fail(fake.next(), "timeout", "No frame within 3000 ms")
        answerSession(locked = true, displayOn = null)
        assertEquals("${SidecarGameSurface.FRAMES_STOPPED} retryable=false", withTimeout(5000) { answers.receive() })
    }

    @Test
    fun `an old frame from a quiet village on an unlocked PC is still a frame`() = runBlocking {
        val (answers, callback) = screenshots()
        surface.takeScreenshot(callback)
        answerCapture(ageMs = 60_000)
        answerSession(locked = false, displayOn = true)
        assertEquals("frame", withTimeout(5000) { answers.receive() })

        // Windows not saying either way counts as unlocked.
        surface.takeScreenshot(callback)
        answerCapture(ageMs = 60_000)
        answerSession(locked = null, displayOn = null)
        assertEquals("frame", withTimeout(5000) { answers.receive() })

        // A sidecar without the call counts as unlocked as well.
        surface.takeScreenshot(callback)
        answerCapture(ageMs = 60_000)
        fail(fake.next(), "bad_request", "Unknown op 'sessionState'")
        assertEquals("frame", withTimeout(5000) { answers.receive() })
    }

    @Test
    fun `a new frame is not checked against the lock`() = runBlocking {
        val (answers, callback) = screenshots()
        surface.takeScreenshot(callback)
        answerCapture(ageMs = SidecarGameSurface.STALE_MS - 1)
        assertEquals("frame", withTimeout(5000) { answers.receive() })
        surface.back()
        assertEquals("key", fake.next().op)
    }
}
