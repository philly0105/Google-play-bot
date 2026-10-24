package local.clashfarmer.remote

import local.clashfarmer.accounts.AccountLastResult
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Length
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.StopWhen
import local.clashfarmer.core.StorageReading
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.history.LootSummary
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.LogFixtures
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.insight.Problems
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.insight.SessionStats.Period
import local.clashfarmer.insight.Timeline
import local.clashfarmer.ui.screens.StatsModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.time.ZoneOffset

class RemoteApiTest {

    private val key = "0123456789abcdef0123456789abcdef"

    private class FakeBackend : RemoteBackend {
        val calls = mutableListOf<String>()
        var paused = false
        var startError: String? = null
        override fun status(): JSONObject = JSONObject().put("running", false)
        override fun recentEvents(n: Int): List<String> { calls += "log:$n"; return List(n) { "{\"event\":\"e$it\"}" } }
        var frame: local.clashfarmer.service.FrameStore.Snapshot? = null
        override fun frame(): local.clashfarmer.service.FrameStore.Snapshot? = frame
        override fun start(accountId: String, mode: RemoteMode) {
            calls += "start:$accountId:$mode"
            startError?.let { throw IllegalStateException(it) }
        }
        override fun startCombined(first: String, second: String, firstAttacks: Int, secondAttacks: Int, minutes: Int) {
            calls += "combined:$first:$second:$firstAttacks:$secondAttacks:$minutes"
        }
        override fun stop() { calls += "stop" }
        override fun togglePause(): Boolean { calls += "pause"; paused = !paused; return paused }
        override fun setDamageEnd(accountId: String, on: Boolean, min: Int, max: Int) {
            calls += "damage:$accountId:$on:$min:$max"
            startError?.let { throw IllegalStateException(it) }
        }
        val presets = listOf(
            RunPlan(listOf(SWEAT), Length.Attacks(40), walls = true, stopWhen = StopWhen.SAVED_GOAL, name = "Farm 40 + walls"),
            RunPlan(listOf(SWEAT, BLOOD), Length.Attacks(15), secondAttacks = 40, walls = false, name = "Both accounts")
        )
        val plans = mutableListOf<RunPlan>()
        override fun presets(): List<RunPlan> { calls += "presets"; return presets }
        override fun startPlan(plan: RunPlan) {
            calls += "plan"
            plans += plan
            startError?.let { throw IllegalStateException(it) }
        }
        override fun preview(plan: RunPlan): JSONObject {
            calls += "preview"
            plans += plan
            return RemoteJson.preview(listOf("Sweat needs a supervised attack first"), "Sweat: up to 3 attacks")
        }
        override fun stats(days: Int): JSONObject { calls += "stats:$days"; return JSONObject().put("days", days) }
        override fun timeline(n: Int): JSONObject { calls += "timeline:$n"; return JSONObject().put("groups", JSONArray()) }
        override fun historyCsv(days: Int): String { calls += "csv:$days"; return "battle id\r\n" }
    }

    private fun api(backend: FakeBackend) = RemoteApi(key, backend, page = "<html>page</html>")

    private fun req(method: String, path: String, query: Map<String, String> = emptyMap(),
                    headers: Map<String, String> = mapOf("x-key" to key), body: String = "") =
        HttpRequest(method, path, query, headers, body)

    @Test
    fun aMissingOrWrongKeyIsRefusedAndNothingRuns() {
        val backend = FakeBackend()
        assertEquals(401, api(backend).handle(req("POST", "/api/stop", headers = emptyMap())).status)
        assertEquals(401, api(backend).handle(req("POST", "/api/stop", headers = mapOf("x-key" to key.reversed()))).status)
        assertEquals(401, api(backend).handle(req("GET", "/", query = mapOf("key" to "short"), headers = emptyMap())).status)
        assertEquals(401, api(backend).handle(req("GET", "/api/frame.jpg", headers = emptyMap())).status)
        assertTrue(backend.calls.isEmpty())
    }

    /**
     * 15 October: each visit from the phone's browser logged three refusals, for its icons. They
     * are not found, so the server logs nothing for them; anything else without the key is still
     * refused, and so logged.
     */
    @Test
    fun theBrowsersIconRequestsAreNotFoundRatherThanRefused() {
        val backend = FakeBackend()
        for (path in listOf("/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png")) {
            val response = api(backend).handle(req("GET", path, headers = emptyMap()))
            assertEquals(path, 404, response.status)
            assertFalse(path, response.body.contains("<html>"))
            assertEquals(path, 404, api(backend).handle(req("GET", path)).status)
        }
        for (path in listOf("/favicon.png", "/apple-touch-icon-120x120.png", "/favicon.ico/", "/api/favicon.ico", "/")) {
            assertEquals(path, 401, api(backend).handle(req("GET", path, headers = emptyMap())).status)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun theKeyIsAcceptedFromTheHeaderOrTheQuery() {
        val backend = FakeBackend()
        assertEquals(200, api(backend).handle(req("GET", "/api/status")).status)
        val page = api(backend).handle(req("GET", "/", query = mapOf("key" to key), headers = emptyMap()))
        assertEquals(200, page.status)
        assertTrue(page.contentType.startsWith("text/html"))
        assertEquals("<html>page</html>", page.body)
    }

    @Test
    fun statusIsTheBackendsJson() {
        val response = api(FakeBackend()).handle(req("GET", "/api/status"))
        assertEquals(false, JSONObject(response.body).getBoolean("running"))
        assertTrue(response.contentType.startsWith("application/json"))
    }

    @Test
    fun commandsNeedPostSoALinkPreviewCannotStartARun() {
        val backend = FakeBackend()
        for (path in listOf("/api/start", "/api/stop", "/api/pause", "/api/damage")) {
            assertEquals(405, api(backend).handle(req("GET", path, query = mapOf("account" to "a", "mode" to "attacks"))).status)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun startPassesTheAccountAndMode() {
        val backend = FakeBackend()
        val response = api(backend).handle(req("POST", "/api/start", body = "account=abc-1&mode=farm"))
        assertEquals(200, response.status)
        assertEquals(listOf("start:abc-1:FARM"), backend.calls)
        api(backend).handle(req("POST", "/api/start", body = "account=abc-1&mode=attacks"))
        assertEquals("start:abc-1:ATTACKS", backend.calls.last())
    }

    @Test
    fun everyDashboardModeCanBeStarted() {
        val backend = FakeBackend()
        val keys = listOf("attacks", "farm", "attacks40", "farm40", "event", "supervised", "walls", "switch")
        for (mode in keys) {
            assertEquals(mode, 200, api(backend).handle(req("POST", "/api/start", body = "account=a&mode=$mode")).status)
        }
        assertEquals(
            listOf("ATTACKS", "FARM", "ATTACKS40", "FARM40", "EVENT", "SUPERVISED", "WALLS", "SWITCH").map { "start:a:$it" },
            backend.calls
        )
    }

    @Test
    fun aCombinedRunPassesBothAccountsAndLimits() {
        val backend = FakeBackend()
        val response = api(backend).handle(req("POST", "/api/combined", body = "first=a&second=b&firstAttacks=15&secondAttacks=40&minutes=120"))
        assertEquals(200, response.status)
        assertEquals(listOf("combined:a:b:15:40:120"), backend.calls)
    }

    @Test
    fun aCombinedRunOutsideTheLimitsIsRefused() {
        val backend = FakeBackend()
        for (body in listOf(
            "first=a&second=b&firstAttacks=0&secondAttacks=15&minutes=60",
            "first=a&second=b&firstAttacks=15&secondAttacks=101&minutes=60",
            "first=a&second=b&firstAttacks=15&secondAttacks=15&minutes=121",
            "first=a&second=a&firstAttacks=15&secondAttacks=15&minutes=60",
            "first=a&firstAttacks=15&secondAttacks=15&minutes=60",
            "first=a&second=b&firstAttacks=x&secondAttacks=15&minutes=60"
        )) {
            assertEquals(body, 400, api(backend).handle(req("POST", "/api/combined", body = body)).status)
        }
        assertEquals(405, api(backend).handle(req("GET", "/api/combined")).status)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aBadStartIsRefusedBeforeTheBackend() {
        val backend = FakeBackend()
        assertEquals(400, api(backend).handle(req("POST", "/api/start", body = "account=abc&mode=delete")).status)
        assertEquals(400, api(backend).handle(req("POST", "/api/start", body = "mode=farm")).status)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aRefusedStartReportsWhy() {
        val backend = FakeBackend().apply { startError = "A farming session is already active" }
        val response = api(backend).handle(req("POST", "/api/start", body = "account=abc&mode=farm"))
        assertEquals(409, response.status)
        assertEquals("A farming session is already active", JSONObject(response.body).getString("error"))
    }

    @Test
    fun stopAndPauseReachTheBackend() {
        val backend = FakeBackend()
        assertEquals(200, api(backend).handle(req("POST", "/api/stop")).status)
        val paused = api(backend).handle(req("POST", "/api/pause"))
        assertTrue(JSONObject(paused.body).getBoolean("paused"))
        assertEquals(listOf("stop", "pause"), backend.calls)
    }

    @Test
    fun theLogIsBounded() {
        val backend = FakeBackend()
        api(backend).handle(req("GET", "/api/log", query = mapOf("n" to "5000")))
        api(backend).handle(req("GET", "/api/log", query = mapOf("n" to "nope")))
        val response = api(backend).handle(req("GET", "/api/log", query = mapOf("n" to "3")))
        assertEquals(listOf("log:100", "log:20", "log:3"), backend.calls)
        assertEquals(3, org.json.JSONArray(response.body).length())
    }

    @Test
    fun anUnknownPathIsNotFound() {
        assertEquals(404, api(FakeBackend()).handle(req("GET", "/etc/passwd")).status)
    }

    @Test
    fun onlyTailscaleAndLoopbackPeersAreServed() {
        fun allowed(host: String) = RemoteAccess.peerAllowed(InetAddress.getByName(host))
        assertTrue(allowed("100.108.200.120"))
        assertTrue(allowed("100.64.0.1"))
        assertTrue(allowed("100.127.255.254"))
        assertTrue(allowed("127.0.0.1"))
        assertTrue(allowed("::1"))
        assertTrue(allowed("fd7a:115c:a1e0::1"))
        assertFalse(allowed("100.128.0.1"))
        assertFalse(allowed("100.63.255.255"))
        assertFalse(allowed("10.0.0.126"))
        assertFalse(allowed("192.168.1.5"))
        assertFalse(allowed("8.8.8.8"))
        assertFalse(allowed("2601:645:d03:dbb0::1"))
        assertFalse(allowed("fd7a:115c:a1e1::1"))
    }

    @Test
    fun aRequestIsParsedWithQueryHeadersAndBody() {
        val raw = "POST /api/start?key=a%20b HTTP/1.1\r\nHost: x\r\nX-Key: K\r\nContent-Length: 9\r\n\r\naccount=1"
        val request = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", request.method)
        assertEquals("/api/start", request.path)
        assertEquals("a b", request.query["key"])
        assertEquals("K", request.headers["x-key"])
        assertEquals("account=1", request.body)
    }

    @Test
    fun anOversizedOrBrokenRequestIsDropped() {
        val longHeader = "GET / HTTP/1.1\r\nX: ${"a".repeat(20_000)}\r\n\r\n"
        assertNull(HttpRequest.read(ByteArrayInputStream(longHeader.toByteArray())))
        val bigBody = "POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"
        assertNull(HttpRequest.read(ByteArrayInputStream(bigBody.toByteArray())))
        assertNull(HttpRequest.read(ByteArrayInputStream("garbage".toByteArray())))
    }

    @Test
    fun theKeyIsMadeOnceAndKept() {
        val dir = Files.createTempDirectory("remote").toFile()
        val file = File(dir, "remote_key.txt")
        val first = RemoteKey.loadOrCreate(file)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertEquals(first, RemoteKey.loadOrCreate(file))
        dir.deleteRecursively()
    }

    @Test
    fun theResponseIsWrittenAsHttp() {
        val out = java.io.ByteArrayOutputStream()
        HttpResponse(200, "application/json", "{}").writeTo(out)
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.contains("Cache-Control: no-store\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{}"))
    }

    @Test
    fun damageSetsTheRangeAndRefusesABadOne() {
        val backend = FakeBackend()
        assertEquals(200, api(backend).handle(req("POST", "/api/damage", body = "account=b1&on=true&min=45&max=60")).status)
        assertEquals(200, api(backend).handle(req("POST", "/api/damage", body = "account=b1&on=false&min=50&max=50")).status)
        for (body in listOf("account=b1&on=true&min=60&max=55", "account=b1&on=true&min=0&max=55",
                "account=b1&on=true&min=50&max=101", "account=b1&on=yes&min=50&max=55", "on=true&min=50&max=55")) {
            assertEquals(body, 400, api(backend).handle(req("POST", "/api/damage", body = body)).status)
        }
        assertEquals(listOf("damage:b1:true:45:60", "damage:b1:false:50:50"), backend.calls)
        backend.startError = "Settings cannot be saved while a run is active"
        val busy = api(backend).handle(req("POST", "/api/damage", body = "account=b1&on=true&min=50&max=55"))
        assertEquals(409, busy.status)
        assertEquals("Settings cannot be saved while a run is active", JSONObject(busy.body).getString("error"))
    }

    @Test
    fun theFrameIs404UntilThereIsOne() {
        val backend = FakeBackend()
        val none = api(backend).handle(req("GET", "/api/frame.jpg"))
        assertEquals(404, none.status)
        assertTrue(none.contentType.startsWith("application/json"))
    }

    @Test
    fun theFrameIsServedAsJpegBytes() {
        val backend = FakeBackend().apply {
            frame = local.clashfarmer.service.FrameStore.Snapshot(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x7F), 1234L)
        }
        val response = api(backend).handle(req("GET", "/api/frame.jpg"))
        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.contentType)
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte(), 0x00.toByte(), 0x7F.toByte()), response.bytes.toList())
    }

    @Test
    fun aBinaryResponseKeepsItsBytes() {
        val out = java.io.ByteArrayOutputStream()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x80.toByte(), 0x00)
        HttpResponse(200, "image/jpeg", bytes).writeTo(out)
        val written = out.toByteArray()
        val head = String(written, Charsets.US_ASCII).substringBefore("\r\n\r\n")
        assertTrue(head.contains("Content-Length: 4\r\n"))
        assertTrue(head.contains("Content-Type: image/jpeg\r\n"))
        assertEquals(bytes.toList(), written.takeLast(4))
    }

    // ----- The redesigned page's routes -----

    private val newRoutes = listOf("/api/run", "/api/preview", "/api/presets", "/api/stats", "/api/timeline", "/api/activity", "/api/history.csv")

    @Test
    fun theNewRoutesRefuseAMissingOrWrongKey() {
        val backend = FakeBackend()
        for (path in newRoutes) for (method in listOf("GET", "POST")) {
            assertEquals("$method $path", 401, api(backend).handle(req(method, path, headers = emptyMap(),
                body = "preset=Farm+40+%2B+walls")).status)
            assertEquals("$method $path", 401, api(backend).handle(req(method, path, query = mapOf("key" to key.reversed()),
                headers = emptyMap())).status)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aRunNeedsPost() {
        val backend = FakeBackend()
        val body = "accounts=$SWEAT&length=attacks&value=3&walls=false"
        assertEquals(405, api(backend).handle(req("GET", "/api/run", query = HttpRequest.form(body), body = body)).status)
        assertEquals(405, api(backend).handle(req("GET", "/api/run", query = mapOf("preset" to "Farm 40 + walls"))).status)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun anUnknownPresetIs400AndStartsNothing() {
        val backend = FakeBackend()
        val response = api(backend).handle(req("POST", "/api/run", body = "preset=Farm+100"))
        assertEquals(400, response.status)
        assertEquals("Unknown preset", JSONObject(response.body).getString("error"))
        assertTrue(backend.plans.isEmpty())
    }

    @Test
    fun aPresetStartsItsSavedPlan() {
        val backend = FakeBackend()
        assertEquals(200, api(backend).handle(req("POST", "/api/run", body = "preset=Both+accounts&length=minutes&value=5")).status)
        assertEquals(listOf(backend.presets[1]), backend.plans)
    }

    @Test
    fun theComposerFormBecomesExactlyThatPlan() {
        val backend = FakeBackend()
        fun run(body: String): RunPlan {
            assertEquals(body, 200, api(backend).handle(req("POST", "/api/run", body = body)).status)
            return backend.plans.last()
        }
        assertEquals(RunPlan(listOf(SWEAT), Length.Attacks(3), walls = false),
            run("accounts=$SWEAT&length=attacks&value=3&walls=false"))
        assertEquals(RunPlan(listOf(SWEAT), Length.Minutes(90), walls = true, stopWhen = StopWhen.ACCOUNT_GOAL),
            run("accounts=$SWEAT&length=minutes&value=90&walls=true&stop=goal"))
        assertEquals(RunPlan(listOf(BLOOD), Length.EndBy(7, 40), walls = false, stopWhen = StopWhen.STORAGE_FULL, eventTroops = true),
            run("accounts=$BLOOD&length=endby&value=07%3A40&walls=false&stop=full&event=true"))
        assertEquals(RunPlan(listOf(SWEAT, BLOOD), Length.Attacks(15), secondAttacks = 40, walls = false, stopWhen = StopWhen.SAVED_GOAL),
            run("accounts=$SWEAT%2C$BLOOD&length=attacks&value=15&second=40&walls=false&stop=saved"))
        // A second count means nothing for one account.
        assertNull(run("accounts=$SWEAT&length=attacks&value=100&second=40&walls=false").secondAttacks)
    }

    @Test
    fun aBadFormIs400BeforeTheBackend() {
        val backend = FakeBackend()
        val good = mapOf("accounts" to SWEAT, "length" to "attacks", "value" to "3", "walls" to "false")
        val bad = listOf(
            good - "walls", good + ("walls" to "yes"), good - "accounts", good + ("accounts" to " , "),
            good + ("accounts" to "$SWEAT,$SWEAT"), good + ("accounts" to "$SWEAT,$BLOOD,c"),
            good + ("value" to "0"), good + ("value" to "101"), good + ("value" to "three"), good - "value",
            good + ("length" to "forever"), good - "length", good + mapOf("length" to "minutes", "value" to "121"),
            good + mapOf("length" to "endby", "value" to "24:00"), good + mapOf("length" to "endby", "value" to "07:60"),
            good + mapOf("length" to "endby", "value" to "740"), good + ("stop" to "never"), good + ("event" to "maybe"),
            good + mapOf("accounts" to "$SWEAT,$BLOOD", "second" to "0")
        )
        for (form in bad) {
            val body = form.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            val response = api(backend).handle(req("POST", "/api/run", body = body))
            assertEquals(body, 400, response.status)
            assertTrue(body, JSONObject(response.body).getString("error").isNotBlank())
            assertEquals(body, 400, api(backend).handle(req("GET", "/api/preview", query = form)).status)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aRefusedRunIs409WithItsReason() {
        val backend = FakeBackend().apply { startError = "Sweat needs a supervised attack first" }
        val response = api(backend).handle(req("POST", "/api/run", body = "accounts=$SWEAT&length=attacks&value=40&walls=true"))
        assertEquals(409, response.status)
        assertEquals("Sweat needs a supervised attack first", JSONObject(response.body).getString("error"))
    }

    @Test
    fun previewReadsTheQueryAndStartsNothing() {
        val backend = FakeBackend()
        val response = api(backend).handle(req("GET", "/api/preview",
            query = mapOf("key" to key, "accounts" to SWEAT, "length" to "attacks", "value" to "3", "walls" to "false")))
        assertEquals(200, response.status)
        val json = JSONObject(response.body)
        assertFalse(json.getBoolean("ready"))
        assertEquals("Sweat needs a supervised attack first", json.getJSONArray("problems").getString(0))
        assertEquals("Sweat: up to 3 attacks", json.getString("summary"))
        assertEquals(listOf(RunPlan(listOf(SWEAT), Length.Attacks(3), walls = false)), backend.plans)
        assertEquals(listOf("preview"), backend.calls)
    }

    @Test
    fun presetsAreListedAsPlans() {
        val response = api(FakeBackend()).handle(req("GET", "/api/presets"))
        val list = JSONArray(response.body)
        assertEquals(2, list.length())
        assertEquals("Farm 40 + walls", list.getJSONObject(0).getString("name"))
        assertEquals(RunPlan.fromJson(list.getJSONObject(1)), FakeBackend().presets[1])
    }

    @Test
    fun statsDaysAreTodaySevenOrThirty() {
        val backend = FakeBackend()
        for (days in listOf("1", "7", "30", "2", "365", "nope")) {
            api(backend).handle(req("GET", "/api/stats", query = mapOf("days" to days)))
        }
        api(backend).handle(req("GET", "/api/stats"))
        assertEquals(listOf("stats:1", "stats:7", "stats:30", "stats:7", "stats:7", "stats:7", "stats:7"), backend.calls)
    }

    @Test
    fun theTimelineIsBoundedAndActivityIsTheSameRoute() {
        val backend = FakeBackend()
        api(backend).handle(req("GET", "/api/timeline", query = mapOf("n" to "99999")))
        api(backend).handle(req("GET", "/api/timeline", query = mapOf("n" to "0")))
        api(backend).handle(req("GET", "/api/timeline"))
        val activity = api(backend).handle(req("GET", "/api/activity", query = mapOf("n" to "50")))
        assertEquals(200, activity.status)
        assertTrue(activity.contentType.startsWith("application/json"))
        assertEquals(listOf("timeline:3000", "timeline:1", "timeline:200", "timeline:50"), backend.calls)
    }

    @Test
    fun historyIsCsvForBoundedDays() {
        val backend = FakeBackend()
        val response = api(backend).handle(req("GET", "/api/history.csv", query = mapOf("key" to key), headers = emptyMap()))
        assertEquals(200, response.status)
        assertEquals("text/csv; charset=utf-8", response.contentType)
        assertEquals("battle id\r\n", response.body)
        api(backend).handle(req("GET", "/api/history.csv", query = mapOf("days" to "0")))
        api(backend).handle(req("GET", "/api/history.csv", query = mapOf("days" to "9999")))
        assertEquals(listOf("csv:30", "csv:1", "csv:365"), backend.calls)
    }

    // ----- What the page reads -----

    private val accounts = listOf(
        AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU",
            lastResult = AccountLastResult(timestamp = 1_000L, attacksCompleted = 12, attackLimit = 40, status = "Stopped", stopReason = "Stopped from remote")),
        AccountRecord(BLOOD, "Blood", "Philly0105", "#Y2G29YJQ8")
    )
    private val t0 = Format.parseTime("2026-10-08T06:51:00Z")!!
    private val records = listOf(
        BattleRecord("a", "s1", SWEAT, t0, t0 + 180_000L, earnedLoot = mapOf(Resource.GOLD to 400_000L, Resource.ELIXIR to 300_000L),
            stars = 2, outcome = BattleRecord.COMPLETED),
        BattleRecord("b", "s1", SWEAT, t0 + 1_800_000L, t0 + 1_980_000L, earnedLoot = mapOf(Resource.GOLD to 600_000L, Resource.ELIXIR to 500_000L),
            stars = 1, outcome = BattleRecord.COMPLETED)
    )

    private fun statusInput() = RemoteJson.StatusInput(
        running = true, paused = false, frameAt = 5L, screenOn = true, unlocked = true, accountName = "Sweat",
        snap = SessionSnapshot(startedAt = t0, phase = "Attacking", completed = 12, limit = 40, maxMinutes = 120),
        lootToday = LootSummary.of(records, Long.MIN_VALUE),
        accounts = accounts, selectedId = SWEAT, shown = accounts[0],
        storage = StorageReading(t0, mapOf(Resource.GOLD to 25_240_000L, Resource.ELIXIR to 20_000_000L)),
        builders = BuilderReading(1, 6, t0), health = DeviceHealth(39, false, 33.8),
        records = records, problems = 2, lastPlan = RunPlan(listOf(SWEAT), Length.Attacks(40), walls = true),
        eventActive = true, nowMs = t0 + 3_600_000L
    )

    /** Every field the original page read, pinned so the redesign cannot drop one. */
    @Test
    fun statusKeepsEveryFieldItHadBefore() {
        val status = RemoteJson.status(statusInput())
        val before = listOf("running", "paused", "frameAt", "screenOn", "unlocked", "account", "phase", "completed", "limit",
            "wallsUpgraded", "goldSpent", "elixirSpent", "startedAt", "endedAt", "stopReason", "completionReason",
            "purchaseOutcomeUnknown", "lootToday", "storage", "accounts")
        val added = listOf("shownAccountId", "maxMinutes", "builders", "health", "lootRate", "problems", "lastPlan", "eventActive", "stopText")
        assertEquals((before + added).toSet(), status.keys().asSequence().toSet())
        assertEquals(setOf("battles", "unread", "farmingMinutes", "gold", "goldPerHour", "elixir", "elixirPerHour",
            "dark_elixir", "dark_elixirPerHour"), status.getJSONObject("lootToday").keys().asSequence().toSet())
        assertEquals(setOf("at", "gold", "elixir"), status.getJSONObject("storage").keys().asSequence().toSet())
        assertEquals(setOf("value", "capacity"), status.getJSONObject("storage").getJSONObject("gold").keys().asSequence().toSet())
        val sweat = status.getJSONArray("accounts").getJSONObject(0)
        assertEquals(setOf("id", "name", "selected", "attacks", "wallsEnabled", "damageEnd", "lastResult"), sweat.keys().asSequence().toSet())
        assertEquals(setOf("on", "min", "max"), sweat.getJSONObject("damageEnd").keys().asSequence().toSet())
        assertEquals(setOf("status", "attacks", "limit", "at", "reason"), sweat.getJSONObject("lastResult").keys().asSequence().toSet())
        assertEquals("Stopped from remote", sweat.getJSONObject("lastResult").getString("reason"))
        status.getJSONArray("accounts").getJSONObject(1).let { assertTrue(it.has("lastResult") && it.isNull("lastResult")) }
        assertEquals("Sweat", status.getString("account"))
        assertEquals(12, status.getInt("completed"))
        assertEquals(1_000_000L, status.getJSONObject("lootToday").getLong("gold"))
        assertEquals(25_240_000L, status.getJSONObject("storage").getJSONObject("gold").getLong("value"))
    }

    @Test
    fun statusAddsBuildersHealthRatesAndTheLastPlan() {
        val status = RemoteJson.status(statusInput())
        assertEquals(1, status.getJSONObject("builders").getInt("free"))
        assertEquals(6, status.getJSONObject("builders").getInt("total"))
        assertEquals(39, status.getJSONObject("health").getInt("battery"))
        val rate = status.getJSONObject("lootRate").getJSONObject(SWEAT)
        assertEquals(Format.rate(SessionStats.lootRate7d(records, SWEAT, t0 + 3_600_000L)), rate.getString("text"))
        assertEquals("+1.1M", rate.getString("lastRaid"))
        assertTrue(status.getJSONObject("lootRate").getJSONObject(BLOOD).isNull("lastRaid"))
        assertEquals(2, status.getInt("problems"))
        assertEquals(RunPlan(listOf(SWEAT), Length.Attacks(40), walls = true), RunPlan.fromJson(status.getJSONObject("lastPlan")))
        assertEquals(SWEAT, status.getString("shownAccountId"))

        val idle = RemoteJson.status(statusInput().copy(shown = null, storage = null, builders = null, health = null, lastPlan = null))
        for (k in listOf("storage", "builders", "health", "lastPlan", "shownAccountId", "stopText")) assertTrue(k, idle.has(k) && idle.isNull(k))
    }

    @Test
    fun statsAreTheInsightNumbersAndText() {
        val utc = ZoneOffset.UTC
        val now = t0 + 3_600_000L
        val stats = RemoteJson.stats(records, accounts, mapOf("s1" to "Stopped"), 7, now, utc)
        val totals = SessionStats.summary(records, now - 7 * 86_400_000L)
        assertEquals(7, stats.getInt("days"))
        assertEquals("7 days", stats.getString("period"))
        assertEquals("clashfarmer-history-20261008-7d.csv", stats.getString("csvName"))
        assertTrue(stats.has("coverage") && stats.isNull("coverage"))
        assertEquals(2, stats.getJSONObject("totals").getInt("battles"))
        assertEquals(Format.rate(totals.lootPerHour), stats.getJSONObject("totals").getString("rateText"))
        assertEquals(Format.amount(totals.lootTotal), stats.getJSONObject("totals").getString("totalText"))
        assertEquals(1, stats.getJSONArray("accounts").length())
        assertEquals("Sweat", stats.getJSONArray("accounts").getJSONObject(0).getString("name"))
        val row = SessionStats.sessions(records, accounts, mapOf("s1" to "Stopped")).single()
        val cells = stats.getJSONArray("sessions").getJSONObject(0).getJSONArray("cells")
        assertEquals(SessionStats.sessionCells(row, utc), (0 until cells.length()).map { cells.getString(it) })
        assertEquals("Stopped", cells.getString(5))
        assertEquals(1, RemoteJson.stats(records, accounts, emptyMap(), 1, now, utc).getInt("days"))
        assertEquals(30, RemoteJson.stats(records, accounts, emptyMap(), 30, now, utc).getInt("days"))
        // The period in the name: the app's own export of every battle keeps the plain one.
        assertEquals("clashfarmer-history-20261008-today.csv", RemoteJson.stats(records, accounts, emptyMap(), 1, now, utc).getString("csvName"))
        assertEquals("clashfarmer-history-20261008-30d.csv", RemoteJson.stats(records, accounts, emptyMap(), 30, now, utc).getString("csvName"))
    }

    @Test
    fun statsCarryTheStatsTabCoverageLineWhenTheCapCutsThePeriod() {
        val utc = ZoneOffset.UTC
        val now = t0 + 3_600_000L
        // A full store from Oct 4 06:12, five minutes apart: inside 7 days, so the week is cut short.
        val start = Format.parseTime("2026-10-04T06:12:00Z")!!
        val full = (0 until BattleHistoryStore.MAX_BATTLES).map { k ->
            BattleRecord("f$k", "s${k / 40}", SWEAT, start + k * 300_000L, start + k * 300_000L + 180_000L,
                earnedLoot = mapOf(Resource.GOLD to 100_000L, Resource.ELIXIR to 100_000L), stars = 2, outcome = BattleRecord.COMPLETED)
        }
        val week = RemoteJson.stats(full, accounts, emptyMap(), 7, now, utc)
        assertEquals("History keeps the newest 1,000 battles: these numbers start Oct 4, 06:12", week.getString("coverage"))
        assertEquals(StatsModel.build(full, accounts, emptyMap(), Period.DAYS_7, null, now, utc).coverage, week.getString("coverage"))
        // Today starts after the oldest battle, so it is whole.
        assertTrue(RemoteJson.stats(full, accounts, emptyMap(), 1, now, utc).isNull("coverage"))
    }

    @Test
    fun theTimelineIsTheInsightGroupsWithClockTimes() {
        val utc = ZoneOffset.UTC
        val groups = Timeline.group(Timeline.parse(LogFixtures.SMOKE_RUN))
        val events = Timeline.parse(LogFixtures.SMOKE_RUN)
        val json = RemoteJson.timeline(groups, emptyList(), utc, events)
        val technical = json.getJSONArray("technical")
        assertEquals(events.size, technical.length())
        assertEquals("20:00", technical.getJSONObject(0).getString("clock"))
        for (i in 0 until technical.length()) {
            val text = technical.getJSONObject(i).getString("text")
            assertFalse(text, text.contains('_'))
        }
        val run = json.getJSONArray("groups").getJSONObject(0)
        assertEquals(Timeline.toJson(groups, utc).getJSONObject(0).getString("header"), run.getString("header"))
        val lines = run.getJSONArray("lines")
        assertTrue(lines.length() > 0)
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            assertEquals(Format.time(line.getLong("time"), utc), line.getString("clock"))
            assertFalse(line.getString("text"), line.getString("text").contains('_'))
        }
        assertEquals(0, json.getJSONArray("problems").length())
    }

    @Test
    fun everyWallMaxedReachesTheRemoteTimelineAndStats() {
        val utc = ZoneOffset.UTC
        val events = Timeline.parse(LogFixtures.WALLS_ONLY_MAXED + LogFixtures.FULL_WALLS_MAXED)
        val groups = Timeline.group(events)
        val json = RemoteJson.timeline(groups, Problems.collect(events, accounts, emptyList(), zone = utc), utc, events)
        val runs = json.getJSONArray("groups")
        assertEquals(2, runs.length())
        assertEquals("Sweat · Farm 2 + walls · 08:43-08:46 · Storage full", runs.getJSONObject(0).getString("header"))
        assertEquals("Storage full", runs.getJSONObject(0).getString("ending"))
        assertEquals("Sweat · Walls only · 07:55-07:56 · Complete", runs.getJSONObject(1).getString("header"))
        val inbox = json.getJSONArray("problems")
        assertEquals(1, inbox.length())
        assertEquals("Sweat: storage full", inbox.getJSONObject(0).getString("title"))
        val detail = inbox.getJSONObject(0).getString("detail")
        assertTrue(detail, detail.contains("so every wall is maxed"))
        assertFalse(detail, detail.contains("Free a builder"))

        // Stats rows come from battles: the farm run's row ends Full, and the walls-only run fought none.
        val endings = Timeline.endings(groups)
        assertEquals(mapOf("409a5a7a-2ef5-41a3-9199-49e188affc6d" to "Done", "371c3e07-d681-41af-9b88-baa93b3b492f" to "Full"), endings)
        val at = Format.parseTime("2026-10-14T08:43:56Z")!!
        val battles = listOf(BattleRecord("w1", "371c3e07-d681-41af-9b88-baa93b3b492f", SWEAT, at, at + 94_000L,
            earnedLoot = mapOf(Resource.GOLD to 412_788L, Resource.ELIXIR to 302_103L), stars = 2, outcome = BattleRecord.COMPLETED))
        val sessions = RemoteJson.stats(battles, accounts, endings, 1, at + 3_600_000L, utc).getJSONArray("sessions")
        assertEquals(1, sessions.length())
        assertEquals("Full", sessions.getJSONObject(0).getJSONArray("cells").getString(5))
    }

    @Test
    fun theStopReasonIsAlsoSentInPlainWords() {
        val snap = SessionSnapshot(startedAt = t0, endedAt = t0 + 60_000L, phase = "Stopped", stopReason = "Stopped from remote")
        val status = RemoteJson.status(statusInput().copy(running = false, snap = snap))
        assertEquals("Stopped from remote", status.getString("stopReason"))
        assertEquals("you pressed Stop on the remote page", status.getString("stopText"))
    }

    @Test
    fun theTodayCsvStartsAtLocalMidnightLikeStats() {
        val zone = ZoneOffset.ofHours(2)
        val now = Format.parseTime("2026-10-08T06:00:00Z")!!
        // 08:00 local: today began eight hours ago, not twenty-four.
        assertEquals(Format.parseTime("2026-10-07T22:00:00Z"), RemoteJson.csvSince(1, now, zone))
        assertEquals(SessionStats.sinceMs(SessionStats.Period.TODAY, now, zone), RemoteJson.csvSince(1, now, zone))
        assertEquals(now - 7 * 86_400_000L, RemoteJson.csvSince(7, now, zone))
        assertEquals(now - 30 * 86_400_000L, RemoteJson.csvSince(30, now, zone))
        assertEquals(now - 5 * 86_400_000L, RemoteJson.csvSince(5, now, zone))
    }

    /** Real runs log 200 events after a few attacks; one of 1,200 must still read as a run, not idle. */
    @Test
    fun aLongRunStaysARunAndKeepsItsNewestLines() {
        val utc = ZoneOffset.UTC
        val start = Format.parseTime("2026-10-08T06:00:00Z")!!
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
        val events = listOf(LogFixtures.event(at(start), "session_started", "accountId" to SWEAT, "accountName" to "Sweat",
            "supervised" to false, "walls" to false, "maxAttacks" to 1200, "maxMinutes" to 120, "sessionId" to "long")) +
            (1..1200).map { i ->
                LogFixtures.event(at(start + i * 1_000L), "attack_completed", "accountId" to SWEAT, "accountName" to "Sweat",
                    "completed" to i, "limit" to 1200)
            }
        val groups = Timeline.group(events)
        val json = RemoteJson.timeline(groups, emptyList(), utc, lines = 200)
        val run = json.getJSONArray("groups").getJSONObject(0)
        assertEquals("run", run.getString("kind"))
        assertTrue(run.getString("header"), run.getString("header").startsWith("Sweat"))
        val lines = run.getJSONArray("lines")
        assertEquals(200, lines.length())
        assertEquals(groups[0].lines.size - 200, run.getInt("earlier"))
        assertTrue(run.getInt("earlier") >= 1000)
        // Newest first: the first line kept is the last attack.
        assertEquals(groups[0].lines.first().text, lines.getJSONObject(0).getString("text"))
        assertEquals(Format.time(start + 1_200_000L, utc), lines.getJSONObject(0).getString("clock"))
        assertTrue(json.getJSONArray("groups").length() <= RemoteJson.GROUPS)
    }

    @Test
    fun aTechnicalEntryCarriesItsRawEvent() {
        val event = LogFixtures.event("2026-10-08T06:00:00Z", "device_health_failed", "error" to "battery read failed")
        val entry = RemoteJson.timeline(emptyList(), emptyList(), ZoneOffset.UTC, listOf(event)).getJSONArray("technical").getJSONObject(0)
        val raw = JSONObject(entry.getString("raw"))
        assertEquals("device_health_failed", raw.getString("event"))
        assertEquals("battery read failed", raw.getString("error"))
        assertFalse(entry.getString("text"), entry.getString("text").contains('_'))
    }

    /** The remote inbox is the app's: a wall marker that will not parse still shows, as walls stay blocked. */
    @Test
    fun theInboxListsAWallMarkerThatWillNotRead() {
        val dir = Files.createTempDirectory("inbox").toFile()
        try {
            File(dir, "accounts/$BLOOD").mkdirs()
            File(dir, "accounts/$BLOOD/wall-pending.json").writeText("{not json")
            val problems = local.clashfarmer.insight.ActivityProblems.inbox(dir, accounts, emptyMap(), emptyList(), emptyMap(), ZoneOffset.UTC)
            assertEquals("marker:$BLOOD", problems.first().key)
            assertTrue(problems.none { it.key == "marker:$SWEAT" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
