package local.clashfarmer.desktop.sidecar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `find reads a window closed by hand, and a reply without the flag as visible`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        fun JSONObject.window() = put("found", true).put("top", 1).put("game", 2).put("title", "Clash of Clans - Blood")
            .put("iconic", false).put("width", 1740).put("height", 978).put("foreground", false).put("monitorDpi", 120)
        val hidden = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window().put("visible", false) }
        assertEquals(ClashWindow(1, 2, "Clash of Clans - Blood", false, 1740, 978, false, 120, visible = false), hidden.await())
        val older = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window() }
        assertTrue(older.await()!!.visible)
    }

    @Test
    fun `find reads the room the monitor holds, and null when the sidecar can't measure it`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        fun JSONObject.window() = put("found", true).put("top", 1).put("game", 2).put("title", "Clash of Clans")
            .put("iconic", false).put("width", 1740).put("height", 978).put("foreground", false).put("monitorDpi", 120)
        val measured = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window().put("roomWidth", 1904).put("roomHeight", 1001) }
        assertEquals(SurfaceSize(1904, 1001), measured.await()!!.room)
        val unmeasured = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window().put("roomWidth", JSONObject.NULL).put("roomHeight", JSONObject.NULL) }
        assertNull(unmeasured.await()!!.room)
        val older = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window() }
        assertNull(older.await()!!.room)
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
    fun `hello carries the text reader's load error, and a null or missing one as none`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        fun JSONObject.base() = put("version", "1").put("pid", 7).put("ocrReady", false)
        val failed = async(Dispatchers.Default) { sidecar.hello() }
        fake.ok(fake.next()) { base().put("ocrError", "Unable to load DLL 'onnxruntime'") }
        assertEquals(Hello("1", 7, false, "Unable to load DLL 'onnxruntime'"), failed.await())
        val loading = async(Dispatchers.Default) { sidecar.hello() }
        fake.ok(fake.next()) { base().put("ocrError", JSONObject.NULL) }
        assertEquals(Hello("1", 7, false, null), loading.await())
        val older = async(Dispatchers.Default) { sidecar.hello() }
        fake.ok(fake.next()) { base() }
        assertEquals(Hello("1", 7, false, null), older.await())
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

    @Test
    fun `capture reads the frame's age, and an older sidecar's frame as new`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val aged = async(Dispatchers.Default) { sidecar.capture() }
        fake.ok(fake.next(), byteArrayOf(1, 2, 3, 0)) { put("width", 1).put("height", 1).put("timestampMs", 5L).put("ageMs", 12_345L) }
        assertEquals(12_345L, aged.await().ageMs)
        val older = async(Dispatchers.Default) { sidecar.capture() }
        fake.ok(fake.next(), byteArrayOf(1, 2, 3, 0)) { put("width", 1).put("height", 1).put("timestampMs", 5L) }
        assertEquals(0L, older.await().ageMs)
    }

    @Test
    fun `find reads the window's process and full screen, and an older reply without them`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        fun JSONObject.window() = put("found", true).put("top", 1).put("game", 2).put("title", "Clash of Clans")
            .put("iconic", false).put("width", 1740).put("height", 978).put("foreground", false).put("monitorDpi", 120)
        val full = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window().put("ownerPid", 4321).put("ownerStartedMs", 1_700_000_000_000L).put("fullscreen", true) }
        val window = full.await()!!
        assertEquals(4321L, window.ownerPid)
        assertEquals(1_700_000_000_000L, window.ownerStartedMs)
        assertTrue(window.fullscreen)
        val unknown = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window().put("ownerPid", 4321).put("ownerStartedMs", JSONObject.NULL) }
        assertEquals(null, unknown.await()!!.ownerStartedMs)
        val older = async(Dispatchers.Default) { sidecar.find() }
        fake.ok(fake.next()) { window() }
        val old = older.await()!!
        assertEquals(null, old.ownerPid)
        assertFalse(old.fullscreen)
    }

    @Test
    fun `sessionState reads the lock and the display, null where Windows doesn't say`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val locked = async(Dispatchers.Default) { sidecar.sessionState() }
        val request = fake.next()
        assertEquals("sessionState", request.op)
        fake.ok(request) { put("locked", true).put("displayOn", JSONObject.NULL) }
        val state = locked.await()
        assertEquals(SessionState(locked = true, displayOn = null), state)
        assertTrue(state.framesStopped)
        assertTrue(SessionState(locked = false, displayOn = false).framesStopped)
        assertFalse(SessionState(locked = false, displayOn = true).framesStopped)
        assertFalse(SessionState(locked = null, displayOn = null).framesStopped)
    }

    @Test
    fun `pcInfo reads the Windows build and what is installed`() = runBlocking {
        val fake = FakeSidecar()
        val sidecar = Sidecar { fake.connection }
        val info = async(Dispatchers.Default) { sidecar.pcInfo() }
        val request = fake.next()
        assertEquals("pcInfo", request.op)
        fake.ok(request) { put("osBuild", 19045).put("gpgInstalled", true).put("clashInstalled", false) }
        assertEquals(PcInfo(osBuild = 19045, gpgInstalled = true, clashInstalled = false), info.await())

        // A shortcut scan that could not finish leaves Clash unknown.
        val unknown = async(Dispatchers.Default) { sidecar.pcInfo() }
        val again = fake.next()
        fake.ok(again) { put("osBuild", 22631).put("gpgInstalled", true).put("clashInstalled", JSONObject.NULL) }
        assertEquals(PcInfo(osBuild = 22631, gpgInstalled = true, clashInstalled = null), unknown.await())
    }

    @Test
    fun `Setup asks often while the text reader loads and seldom once it is ready or failed`() {
        assertFalse(SidecarStatus.settled(null))
        assertFalse(SidecarStatus.settled(Hello("1", 7, ocrReady = false)))
        assertTrue(SidecarStatus.settled(Hello("1", 7, ocrReady = true)))
        assertTrue(SidecarStatus.settled(Hello("1", 7, ocrReady = false, ocrError = "Unable to load DLL")))
    }
}
