package local.clashfarmer.remote

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Length
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.StopWhen
import local.clashfarmer.core.StorageReading
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.history.LootSummary
import local.clashfarmer.insight.EventText
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.HistoryCsv
import local.clashfarmer.insight.Problem
import local.clashfarmer.insight.Problems
import local.clashfarmer.insight.RunGroup
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.insight.Timeline
import local.clashfarmer.ui.screens.StatsModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId

/** The dashboard's run buttons, keyed as the page sends them. */
enum class RemoteMode(val key: String) {
    ATTACKS("attacks"), FARM("farm"), ATTACKS40("attacks40"), FARM40("farm40"), FARM2H("farm2h"), EVENT("event"),
    SUPERVISED("supervised"), WALLS("walls"), SWITCH("switch");

    companion object {
        fun of(key: String?): RemoteMode? = values().firstOrNull { it.key == key }
    }
}

/** What the remote page may ask of the helper. The service decides whether a start is allowed. */
interface RemoteBackend {
    fun status(): JSONObject
    fun recentEvents(n: Int): List<String>
    /** The newest frame the bot captured, or null when there has been none since the service started. */
    fun frame(): local.clashfarmer.service.FrameStore.Snapshot?
    /** Throws IllegalStateException with a readable reason when the run may not start. */
    fun start(accountId: String, mode: RemoteMode)
    /** Same contract as [start]; limits are already inside 1..100 attacks and 1..120 minutes. */
    fun startCombined(first: String, second: String, firstAttacks: Int, secondAttacks: Int, minutes: Int)
    fun stop()
    /** Returns whether the run is paused afterwards. */
    fun togglePause(): Boolean
    /**
     * Turns the surrender-at-damage trigger on or off for an account and sets its range, already
     * inside 1..100 with [min] <= [max]. Throws IllegalStateException when it cannot be saved.
     */
    fun setDamageEnd(accountId: String, on: Boolean, min: Int, max: Int)
    /** The saved presets, with "selected" and "other" already replaced by account ids. */
    fun presets(): List<RunPlan>
    /** Starts [plan] through the service's plan entry points; same contract as [start]. */
    fun startPlan(plan: RunPlan)
    /** What [plan] would do and what stands in its way: `ready`, `problems`, `summary`. */
    fun preview(plan: RunPlan): JSONObject
    /** Totals, per account and per session for [days] 1 (today), 7 or 30. */
    fun stats(days: Int): JSONObject
    /** The plain timeline grouped by run, each group with its newest [n] lines, plus the problems inbox. */
    fun timeline(n: Int): JSONObject
    /** Battle history as CSV, the last [days] days. */
    fun historyCsv(days: Int): String
}

/**
 * The run composer's form as the page sends it: `accounts=id[,id]`, `length=attacks|minutes|endby`,
 * `value` (a count, minutes, or HH:MM), `second` (both accounts only), `walls=true|false`,
 * `stop=limit|full|goal|saved` and `event=true|false`.
 */
object RemotePlanForm {

    /** The plan in [form]. Throws IllegalArgumentException with a readable reason when it is not one. */
    fun parse(form: Map<String, String>): RunPlan {
        val ids = form["accounts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(ids.size in 1..2 && ids.distinct().size == ids.size) { "Pick one account or two different ones" }
        val value = form["value"]
        val length = when (form["length"]) {
            "attacks" -> Length.Attacks(number(value, RunPlan.MAX_ATTACKS, ATTACKS_RANGE))
            "minutes" -> Length.Minutes(number(value, RunPlan.MAX_MINUTES, "Minutes must be 1 to ${RunPlan.MAX_MINUTES}"))
            "endby" -> clock(value)
            else -> throw IllegalArgumentException("Length must be attacks, minutes or endby")
        }
        val second = form["second"]?.takeIf { it.isNotBlank() }?.let { number(it, RunPlan.MAX_ATTACKS, ATTACKS_RANGE) }
        val stop = when (form["stop"] ?: "limit") {
            "limit" -> StopWhen.LIMIT
            "full" -> StopWhen.STORAGE_FULL
            "goal" -> StopWhen.ACCOUNT_GOAL
            "saved" -> StopWhen.SAVED_GOAL
            else -> throw IllegalArgumentException("Stop must be limit, full, goal or saved")
        }
        return RunPlan(
            accountIds = ids,
            length = length,
            secondAttacks = if (ids.size == 2) second else null,
            // Never defaulted: a run that may spend on walls is always asked for by name.
            walls = flag(form["walls"]) ?: throw IllegalArgumentException("Walls must be true or false"),
            stopWhen = stop,
            eventTroops = if (form["event"] == null) false
                else flag(form["event"]) ?: throw IllegalArgumentException("Event must be true or false")
        )
    }

    private const val ATTACKS_RANGE = "Attacks must be 1 to ${RunPlan.MAX_ATTACKS}"

    private fun number(text: String?, max: Int, reason: String): Int =
        text?.trim()?.toIntOrNull()?.takeIf { it in 1..max } ?: throw IllegalArgumentException(reason)

    private fun flag(text: String?): Boolean? = when (text) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun clock(text: String?): Length.EndBy {
        val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text?.trim().orEmpty())
        val hour = match?.groupValues?.get(1)?.toInt()
        val minute = match?.groupValues?.get(2)?.toInt()
        require(hour != null && minute != null && hour in 0..23 && minute in 0..59) { "End by must be a time like 07:40" }
        return Length.EndBy(hour, minute)
    }
}

/**
 * The JSON the page reads, built from plain values so its shape is tested off the phone. Every
 * number and sentence comes from the insight package, the same code the app's screens use.
 */
object RemoteJson {

    /** What /api/status reports; the service gathers it, this only lays it out. */
    data class StatusInput(
        val running: Boolean,
        val paused: Boolean,
        val frameAt: Long,
        val screenOn: Boolean,
        val unlocked: Boolean,
        val accountName: String?,
        val snap: SessionSnapshot,
        val lootToday: LootSummary,
        val accounts: List<AccountRecord>,
        val selectedId: String?,
        /** The account the storage, builder and rate fields describe: the active one in a run, else the selected one. */
        val shown: AccountRecord?,
        val storage: StorageReading?,
        val builders: BuilderReading?,
        val health: DeviceHealth?,
        val records: List<BattleRecord>,
        val problems: Int,
        val lastPlan: RunPlan?,
        val eventActive: Boolean,
        val nowMs: Long
    )

    /** The original page's fields first, unchanged, then the redesigned page's. */
    fun status(i: StatusInput): JSONObject {
        val snap = i.snap
        return JSONObject()
            .put("running", i.running)
            .put("paused", i.paused)
            .put("frameAt", i.frameAt)
            .put("screenOn", i.screenOn)
            .put("unlocked", i.unlocked)
            .put("account", i.accountName ?: JSONObject.NULL)
            .put("phase", snap.phase)
            .put("completed", snap.completed)
            .put("limit", snap.limit)
            .put("wallsUpgraded", snap.wallsUpgraded)
            .put("goldSpent", snap.goldSpent)
            .put("elixirSpent", snap.elixirSpent)
            .put("startedAt", snap.startedAt ?: JSONObject.NULL)
            .put("endedAt", snap.endedAt ?: JSONObject.NULL)
            .put("stopReason", snap.stopReason ?: JSONObject.NULL)
            .put("completionReason", snap.completionReason ?: JSONObject.NULL)
            .put("purchaseOutcomeUnknown", snap.purchaseOutcomeUnknown)
            .put("lootToday", loot(i.lootToday))
            .put("storage", storage(i.shown, i.storage))
            .put("accounts", JSONArray(i.accounts.map { account(it, i.selectedId) }))
            .put("shownAccountId", i.shown?.id ?: JSONObject.NULL)
            .put("maxMinutes", snap.maxMinutes)
            .put("builders", i.builders?.let {
                JSONObject().put("accountId", i.shown?.id ?: JSONObject.NULL)
                    .put("free", it.free).put("total", it.total).put("at", it.atMs)
            } ?: JSONObject.NULL)
            .put("health", i.health?.let {
                JSONObject().put("battery", it.levelPercent ?: JSONObject.NULL)
                    .put("charging", it.charging ?: JSONObject.NULL)
                    .put("tempC", it.tempC ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("lootRate", JSONObject().apply {
                for (a in i.accounts) {
                    val rate = SessionStats.lootRate7d(i.records, a.id, i.nowMs)
                    put(a.id, JSONObject().put("perHour", rate ?: JSONObject.NULL).put("text", Format.rate(rate))
                        .put("lastRaid", SessionStats.lastRaid(i.records, a.id)?.headline ?: JSONObject.NULL))
                }
            })
            .put("problems", i.problems)
            .put("lastPlan", i.lastPlan?.toJson() ?: JSONObject.NULL)
            .put("eventActive", i.eventActive)
            .put("stopText", snap.stopReason?.let(EventText::stopLine) ?: JSONObject.NULL)
    }

    /** Today's battles, as the original page's loot row reads them. */
    fun loot(summary: LootSummary): JSONObject {
        val json = JSONObject()
            .put("battles", summary.battles)
            .put("unread", summary.unread)
            .put("farmingMinutes", summary.farmingMs / 60_000L)
        for (r in Resource.entries) {
            json.put(r.key, summary.loot[r] ?: 0L)
            json.put(r.key + "PerHour", summary.perHour(r) ?: JSONObject.NULL)
        }
        return json
    }

    /** The last gold and elixir reading for [account], with its capacities, for the storage bars. */
    private fun storage(account: AccountRecord?, reading: StorageReading?): Any {
        account ?: return JSONObject.NULL
        val out = JSONObject().put("at", reading?.atMs ?: JSONObject.NULL)
        for (resource in listOf(Resource.GOLD, Resource.ELIXIR)) {
            out.put(resource.key, JSONObject()
                .put("value", reading?.values?.get(resource) ?: JSONObject.NULL)
                .put("capacity", account.settings.capacities[resource] ?: JSONObject.NULL))
        }
        return out
    }

    private fun account(account: AccountRecord, selectedId: String?): JSONObject {
        val last = account.lastResult
        return JSONObject()
            .put("id", account.id)
            .put("name", account.displayName)
            .put("selected", account.id == selectedId)
            .put("attacks", account.settings.attacks)
            .put("wallsEnabled", account.settings.wallsEnabled)
            .put("damageEnd", account.settings.earlyEnd.let {
                JSONObject().put("on", it.enabled && it.atDamage).put("min", it.damageMin).put("max", it.damageMax)
            })
            .put("lastResult", last?.let {
                JSONObject().put("status", it.status).put("attacks", it.attacksCompleted)
                    .put("limit", it.attackLimit).put("at", it.timestamp)
                    .put("reason", it.stopReason ?: it.completionReason ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
    }

    /** /api/stats: the window's totals, each account's rate, and its newest 14 sessions. */
    fun stats(
        records: List<BattleRecord>,
        accounts: List<AccountRecord>,
        endings: Map<String, String>,
        days: Int,
        nowMs: Long,
        zone: ZoneId
    ): JSONObject {
        val period = SessionStats.periodForDays(days)
        val since = SessionStats.sinceMs(period, nowMs, zone)
        val t = SessionStats.summary(records, since)
        val totals = JSONObject()
            .put("battles", t.battles)
            .put("unread", t.unread)
            .put("lootPerHour", t.lootPerHour ?: JSONObject.NULL)
            .put("lootTotal", t.lootTotal ?: JSONObject.NULL)
            .put("farmingMs", t.farmingMs)
            .put("rateText", Format.rate(t.lootPerHour))
            .put("totalText", Format.amount(t.lootTotal))
            .put("hoursText", Format.hours(t.farmingMs))
        val byAccount = SessionStats.byAccount(records, since, accounts).map {
            JSONObject().put("id", it.accountId).put("name", it.name)
                .put("lootPerHour", it.lootPerHour ?: JSONObject.NULL).put("farmingMs", it.farmingMs).put("battles", it.battles)
                .put("rateText", Format.rate(it.lootPerHour)).put("hoursText", Format.hours(it.farmingMs))
        }
        val sessions = SessionStats.sessions(records, accounts, endings, since, limit = SESSIONS).map { row ->
            JSONObject().put("id", row.sessionId).put("accountId", row.accountId).put("account", row.accountName)
                .put("start", row.startedAtMs).put("end", row.endedAtMs).put("battles", row.battles)
                .put("lootPerHour", row.lootPerHour ?: JSONObject.NULL)
                .put("title", SessionStats.sessionTitle(row, zone))
                .put("cells", JSONArray(SessionStats.sessionCells(row, zone)))
        }
        val shownDays = when (period) {
            SessionStats.Period.TODAY -> 1
            SessionStats.Period.DAYS_7 -> 7
            SessionStats.Period.DAYS_30 -> 30
        }
        // The period in the name, so a download never takes the name of the app's export of every battle.
        val suffix = if (shownDays == 1) "today" else "${shownDays}d"
        return JSONObject()
            .put("days", shownDays)
            .put("period", period.label)
            .put("csvName", HistoryCsv.fileName(nowMs, zone).removeSuffix(".csv") + "-$suffix.csv")
            // The Stats tab's line for a store whose cap cut the period short; null when the period is whole.
            .put("coverage", StatsModel.coverage(records, since, zone) ?: JSONObject.NULL)
            .put("totals", totals)
            .put("accounts", JSONArray(byAccount))
            .put("sessions", JSONArray(sessions))
    }

    /**
     * /api/timeline: the newest [GROUPS] run groups and the inbox, each line with its clock time, and
     * the [technical] events newest first in the technical log's words, each with its `raw` JSON for
     * the page's technical toggle. [groups] are grouped from the whole tail, so a long run keeps its
     * start; each keeps only its newest [lines] lines, and `earlier` counts the ones left out.
     */
    fun timeline(
        groups: List<RunGroup>,
        problems: List<Problem>,
        zone: ZoneId,
        technical: List<JSONObject> = emptyList(),
        lines: Int = Int.MAX_VALUE
    ): JSONObject {
        val runs = Timeline.toJson(groups.take(GROUPS), zone)
        for (g in 0 until runs.length()) {
            val run = runs.getJSONObject(g)
            val all = run.getJSONArray("lines")
            val kept = JSONArray()
            // Newest first, so the first [lines] are the newest.
            for (l in 0 until minOf(lines, all.length())) {
                val line = all.getJSONObject(l)
                kept.put(line.put("clock", Format.time(time(line), zone)))
            }
            run.put("lines", kept).put("earlier", all.length() - kept.length())
        }
        val inbox = Problems.toJson(problems)
        for (p in 0 until inbox.length()) inbox.getJSONObject(p).let { it.put("clock", Format.dayTime(time(it), zone)) }
        val log = technical.asReversed().map {
            JSONObject().put("clock", Format.time(Format.parseTime(it.optString("time")), zone)).put("text", EventText.technical(it))
                .put("raw", it.toString())
        }
        return JSONObject().put("groups", runs).put("problems", inbox).put("technical", JSONArray(log))
    }

    /** Groups /api/timeline sends; the page shows at most this many. */
    const val GROUPS = 12

    /** Where the history CSV for [days] starts: the same instant Stats uses for 1, 7 and 30 days. */
    fun csvSince(days: Int, nowMs: Long, zone: ZoneId): Long =
        if (days == 1 || days == 7 || days == 30) SessionStats.sinceMs(SessionStats.periodForDays(days), nowMs, zone)
        else nowMs - days * 86_400_000L

    /** Events the technical log shows. */
    const val TECHNICAL = 100

    private fun time(json: JSONObject): Long? = if (json.isNull("time")) null else json.optLong("time")

    /** /api/preview: the plan's summary and everything that stands in its way. */
    fun preview(problems: List<String>, summary: String): JSONObject = JSONObject()
        .put("ready", problems.isEmpty())
        .put("problems", JSONArray(problems.distinct()))
        .put("summary", summary)

    const val SESSIONS = 14
}

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val body: String
) {
    companion object {
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 4 * 1024

        /** Reads one request, or null when it is malformed or larger than a phone page ever sends. */
        fun read(input: InputStream): HttpRequest? {
            val head = ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                if (head.size() > MAX_HEAD) return null
                tail = (tail shl 8 or b) and 0xFFFFFFFF.toInt()
                if (tail == 0x0D0A0D0A) break
            }
            val lines = head.toString("UTF-8").split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
            val headers = lines.drop(1).filter { it.contains(':') }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            val target = parts[1]
            return HttpRequest(
                method = parts[0],
                path = target.substringBefore('?'),
                query = if ('?' in target) form(target.substringAfter('?')) else emptyMap(),
                headers = headers,
                body = String(body, Charsets.UTF_8)
            )
        }

        fun form(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
    }
}

/** One response. Text routes build it from a String; the frame route hands it JPEG bytes as they are. */
class HttpResponse(val status: Int, val contentType: String, val bytes: ByteArray) {
    constructor(status: Int, contentType: String, body: String) : this(status, contentType, body.toByteArray(Charsets.UTF_8))

    /** The body as text, for JSON and the page. Not meaningful for an image. */
    val body: String get() = String(bytes, Charsets.UTF_8)

    fun writeTo(out: OutputStream) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}

object RemoteAccess {
    private val TAILSCALE_V6 = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())

    /** Tailscale's 100.64.0.0/10 and fd7a:115c:a1e0::/48, and loopback for adb forward. */
    fun peerAllowed(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            is Inet6Address -> b.copyOfRange(0, 6).contentEquals(TAILSCALE_V6)
            else -> false
        }
    }
}

object RemoteKey {
    fun loadOrCreate(file: File): String {
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        file.parentFile?.mkdirs()
        file.writeText(key)
        return key
    }
}

class RemoteApi(private val key: String, private val backend: RemoteBackend, private val page: String) {

    fun handle(request: HttpRequest): HttpResponse {
        if (request.path in BROWSER_ICONS) return json(404, JSONObject().put("error", "Not found"))
        val offered = request.headers["x-key"] ?: request.query["key"] ?: ""
        if (!MessageDigest.isEqual(offered.toByteArray(), key.toByteArray())) {
            return json(401, JSONObject().put("error", "Wrong or missing key"))
        }
        val command = request.path in setOf("/api/start", "/api/combined", "/api/stop", "/api/pause", "/api/damage", "/api/run")
        if (command && request.method != "POST") return json(405, JSONObject().put("error", "Use POST"))
        return when (request.path) {
            "/" -> HttpResponse(200, "text/html; charset=utf-8", page)
            "/api/status" -> json(200, backend.status())
            "/api/log" -> {
                val n = (request.query["n"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                HttpResponse(200, JSON, JSONArray(backend.recentEvents(n).map { JSONObject(it) }).toString())
            }
            "/api/frame.jpg" -> backend.frame()?.let { HttpResponse(200, "image/jpeg", it.bytes) }
                ?: json(404, JSONObject().put("error", "No capture yet"))
            "/api/start" -> start(HttpRequest.form(request.body))
            "/api/combined" -> combined(HttpRequest.form(request.body))
            "/api/stop" -> { backend.stop(); json(200, JSONObject().put("ok", true)) }
            "/api/pause" -> json(200, JSONObject().put("paused", backend.togglePause()))
            "/api/damage" -> damage(HttpRequest.form(request.body))
            "/api/run" -> run(HttpRequest.form(request.body))
            "/api/preview" -> withPlan(request.query) { json(200, backend.preview(it)) }
            "/api/presets" -> HttpResponse(200, JSON, JSONArray(backend.presets().map { it.toJson() }).toString())
            "/api/stats" -> json(200, backend.stats(request.query["days"]?.toIntOrNull()?.takeIf { it in STATS_DAYS } ?: 7))
            // "activity" is the page's word for the same thing.
            "/api/timeline", "/api/activity" ->
                json(200, backend.timeline((request.query["n"]?.toIntOrNull() ?: 200).coerceIn(1, 3000)))
            "/api/history.csv" ->
                HttpResponse(200, "text/csv; charset=utf-8", backend.historyCsv((request.query["days"]?.toIntOrNull() ?: 30).coerceIn(1, 365)))
            else -> json(404, JSONObject().put("error", "Not found"))
        }
    }

    /** A saved preset by name, or the composer's form; nothing reaches the service unless it reads as a plan. */
    private fun run(form: Map<String, String>): HttpResponse {
        val name = form["preset"] ?: return withPlan(form) { plan -> attempt { backend.startPlan(plan) } }
        val preset = backend.presets().firstOrNull { it.name == name }
            ?: return json(400, JSONObject().put("error", "Unknown preset"))
        return attempt { backend.startPlan(preset) }
    }

    private fun withPlan(form: Map<String, String>, use: (RunPlan) -> HttpResponse): HttpResponse {
        val plan = try {
            RemotePlanForm.parse(form)
        } catch (e: IllegalArgumentException) {
            return json(400, JSONObject().put("error", e.message ?: "Not a run plan"))
        }
        return use(plan)
    }

    private fun start(form: Map<String, String>): HttpResponse {
        val account = form["account"]?.takeIf { it.isNotBlank() }
            ?: return json(400, JSONObject().put("error", "No account"))
        val mode = RemoteMode.of(form["mode"])
            ?: return json(400, JSONObject().put("error", "Unknown mode"))
        return attempt { backend.start(account, mode) }
    }

    private fun combined(form: Map<String, String>): HttpResponse {
        val first = form["first"]?.takeIf { it.isNotBlank() }
        val second = form["second"]?.takeIf { it.isNotBlank() }
        if (first == null || second == null || first == second) {
            return json(400, JSONObject().put("error", "Pick two different accounts"))
        }
        val firstAttacks = form["firstAttacks"]?.toIntOrNull()?.takeIf { it in 1..100 }
        val secondAttacks = form["secondAttacks"]?.toIntOrNull()?.takeIf { it in 1..100 }
        val minutes = form["minutes"]?.toIntOrNull()?.takeIf { it in 1..120 }
        if (firstAttacks == null || secondAttacks == null || minutes == null) {
            return json(400, JSONObject().put("error", "Attacks must be 1 to 100 and minutes 1 to 120"))
        }
        return attempt { backend.startCombined(first, second, firstAttacks, secondAttacks, minutes) }
    }

    private fun damage(form: Map<String, String>): HttpResponse {
        val account = form["account"]?.takeIf { it.isNotBlank() }
            ?: return json(400, JSONObject().put("error", "No account"))
        val on = when (form["on"]) {
            "true" -> true
            "false" -> false
            else -> return json(400, JSONObject().put("error", "On must be true or false"))
        }
        val min = form["min"]?.toIntOrNull()?.takeIf { it in 1..100 }
        val max = form["max"]?.toIntOrNull()?.takeIf { it in 1..100 }
        if (min == null || max == null || min > max) {
            return json(400, JSONObject().put("error", "Damage must be 1 to 100, lowest first"))
        }
        return attempt { backend.setDamageEnd(account, on, min, max) }
    }

    private fun attempt(action: () -> Unit): HttpResponse = try {
        action()
        json(200, JSONObject().put("ok", true))
    } catch (e: IllegalStateException) {
        json(409, JSONObject().put("error", e.message ?: "Could not start"))
    }

    private fun json(status: Int, body: JSONObject) = HttpResponse(status, JSON, body.toString())

    private companion object {
        const val JSON = "application/json; charset=utf-8"
        val STATS_DAYS = setOf(1, 7, 30)

        /**
         * What a phone's browser asks for beside the page, without the key: on 15 October each
         * visit logged three refusals for these. There are none, so they are not found, key or not.
         */
        val BROWSER_ICONS = setOf("/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png")
    }
}
