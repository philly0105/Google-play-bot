package local.clashfarmer.desktop.sidecar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SidecarTest {

    @Test
    fun `capture returns the frame as opaque ARGB`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val frame = async(Dispatchers.Default) { sidecar.capture() }
        val request = fake.next()
        assertEquals("capture", request.op)
        assertTrue(request.json.getBoolean("restore"))
        assertEquals(3000, request.json.getInt("timeoutMs"))
        fake.ok(request, byteArrayOf(1, 2, 3, 0, 4, 5, 6, -1)) {
            put("width", 2).put("height", 1).put("timestampMs", 1_700_000_000_123L)
        }
        val result = frame.await()
        assertEquals(2, result.width)
        assertEquals(1, result.height)
        assertEquals(1_700_000_000_123L, result.timestampMs)
        assertArrayEquals(intArrayOf(0xFF030201.toInt(), 0xFF060504.toInt()), result.pixels)
    }

    @Test
    fun `ocr sends the image as the payload and reads the lines`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val lines = async(Dispatchers.Default) { sidecar.ocr(1, 1, byteArrayOf(0x30, 0x20, 0x10, -1)) }
        val request = fake.next()
        assertEquals("ocr", request.op)
        assertEquals(1, request.json.getInt("width"))
        assertEquals(1, request.json.getInt("height"))
        assertArrayEquals(byteArrayOf(0x30, 0x20, 0x10, -1), request.payload)
        fake.ok(request) {
            put("lines", org.json.JSONArray().put(JSONObject().put("text", "Battle").put("box", org.json.JSONArray(listOf(10, 20, 90, 40))).put("score", 0.97)))
        }
        assertEquals(listOf(TextLine("Battle", 10, 20, 90, 40, 0.97f)), lines.await())
    }

    @Test
    fun `a gesture sends its strokes and waits as long as they take`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val done = async(Dispatchers.Default) { sidecar.gesture(listOf(Stroke(1, 2, 300, 400, 600), Stroke(300, 400, 302, 402, 900))) }
        val request = fake.next()
        val strokes = request.json.getJSONArray("strokes")
        assertEquals(2, strokes.length())
        assertEquals("[1,2]", strokes.getJSONObject(0).getJSONArray("from").toString())
        assertEquals("[302,402]", strokes.getJSONObject(1).getJSONArray("to").toString())
        assertEquals(900, strokes.getJSONObject(1).getInt("ms"))
        fake.ok(request)
        done.await()
    }

    @Test
    fun `replies are matched by id, not by order`() = runBlocking {
        val fake = FakeSidecar()
        val first = async(Dispatchers.Default) { fake.connection.call("hello", timeoutMs = 5000) }
        val a = fake.next()
        val second = async(Dispatchers.Default) { fake.connection.call("power", timeoutMs = 5000) }
        val b = fake.next()
        fake.ok(b) { put("echo", b.op) }
        fake.ok(a) { put("echo", a.op) }
        assertEquals("hello", first.await().json.getString("echo"))
        assertEquals("power", second.await().json.getString("echo"))
    }

    @Test
    fun `an error reply throws with the sidecar's code`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val result = async(Dispatchers.Default) { runCatching { sidecar.find() } }
        val request = fake.next()
        fake.reply(JSONObject().put("id", request.id).put("ok", false).put("code", "no_window").put("error", "No Clash window"))
        val error = result.await().exceptionOrNull() as SidecarException
        assertEquals("no_window", error.code)
        assertEquals("No Clash window", error.message)
    }

    @Test
    fun `a call with no reply times out`() = runBlocking {
        val fake = FakeSidecar()
        try {
            fake.connection.call("hello", timeoutMs = 100)
            fail("expected a timeout")
        } catch (e: SidecarException) {
            assertEquals("timeout", e.code)
        }
        assertTrue(fake.connection.isOpen)
    }

    @Test
    fun `a sidecar that dies fails the waiting call and the next call starts another`() = runBlocking {
        val dead = FakeSidecar()
        val live = FakeSidecar()
        val fakes = ArrayDeque(listOf(dead, live))
        val sidecar = Sidecar { fakes.removeFirst().connection }
        val lost = async(Dispatchers.Default) { runCatching { sidecar.hello() } }
        dead.next()
        dead.die()
        assertEquals("failed", (lost.await().exceptionOrNull() as SidecarException).code)
        assertFalse(dead.connection.isOpen)

        val hello = async(Dispatchers.Default) { sidecar.hello() }
        val request = live.next()
        assertEquals("hello", request.op)
        live.ok(request) { put("version", "1").put("pid", 42).put("ocrReady", true) }
        assertEquals(Hello("1", 42, true), hello.await())
    }

    @Test
    fun `a restarted sidecar is asked to keep the PC awake again`() = runBlocking {
        val first = FakeSidecar()
        val second = FakeSidecar()
        val fakes = ArrayDeque(listOf(first, second))
        val sidecar = Sidecar { fakes.removeFirst().connection }
        val awake = async(Dispatchers.Default) { sidecar.keepAwake(true) }
        first.ok(first.next())
        awake.await()
        first.die()
        while (first.connection.isOpen) Thread.sleep(5)

        val power = async(Dispatchers.Default) { sidecar.power() }
        val again = second.next()
        assertEquals("keepAwake", again.op)
        assertTrue(again.json.getBoolean("on"))
        second.ok(again)
        val request = second.next()
        assertEquals("power", request.op)
        second.ok(request) { put("ac", true).put("batteryPercent", -1).put("saver", false) }
        assertEquals(PowerState(ac = true, batteryPercent = -1, saver = false), power.await())
    }
}
