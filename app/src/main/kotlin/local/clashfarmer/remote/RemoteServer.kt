package local.clashfarmer.remote

import android.content.Context
import android.os.PowerManager
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.Launch
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StorageStore
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.history.LootSummary
import local.clashfarmer.insight.ActivityProblems
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.HistoryCsv
import local.clashfarmer.insight.Problem
import local.clashfarmer.insight.Problems
import local.clashfarmer.insight.RunGroup
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.insight.Timeline
import local.clashfarmer.log.AppLog
import local.clashfarmer.log.LogTail
import local.clashfarmer.log.RunLog
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.FarmerService
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's control page, for a browser on the same tailnet. Plain sockets, one short request
 * per connection. Peers outside Tailscale and loopback are dropped before a byte is read, and
 * every request needs the key besides.
 */
class RemoteServer(private val port: Int, private val api: RemoteApi, private val bindAddress: java.net.InetAddress? = null) {
    @Volatile private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null

    /** Desktop: whether [start] bound its socket and [stop] has not closed it. */
    val isListening: Boolean get() = socket?.isClosed == false

    fun start() {
        val server = try {
            ServerSocket(port, 50, bindAddress)
        } catch (e: Exception) {
            AppLog.log("remote_server_failed", "port" to port, "error" to e.message)
            return
        }
        socket = server
        val pool = Executors.newFixedThreadPool(2).also { workers = it }
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (!RemoteAccess.peerAllowed(client.inetAddress)) {
                    AppLog.log("remote_peer_refused", "peer" to client.inetAddress.hostAddress)
                    runCatching { client.close() }
                    continue
                }
                runCatching { pool.execute { serve(client) } }.onFailure { runCatching { client.close() } }
            }
        }, "remote-accept").apply { isDaemon = true }.start()
        AppLog.log("remote_server_started", "port" to port)
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                it.soTimeout = 5_000
                val request = HttpRequest.read(it.getInputStream()) ?: return
                val response = api.handle(request)
                if (response.status == 401) {
                    AppLog.log("remote_denied", "peer" to it.inetAddress.hostAddress, "path" to request.path)
                }
                response.writeTo(it.getOutputStream())
            } catch (_: Exception) {
                // A dropped phone connection is routine; the next poll tries again.
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        workers?.shutdownNow()
        socket = null
        workers = null
    }

    companion object {
        const val PORT = 8765

        /** The phone's Tailscale address, if the Tailscale app is connected. */
        fun tailscaleAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && RemoteAccess.peerAllowed(it) && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

/** The helper's side of the page: the same checks the dashboard makes before it starts a run. */
class ServiceRemoteBackend(private val service: FarmerService) : RemoteBackend {
    private val store get() = AccountStore(service.filesDir)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun status(): JSONObject {
        val snap = AppLog.sessionSnapshot()
        val context = AppLog.runContextSnapshot()
        val power = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        val selected = runCatching { store.selectedId() }.getOrNull()
        val shown = accounts.firstOrNull { it.id == (context.activeAccountId.takeIf { service.isBusy } ?: selected) }
        val now = System.currentTimeMillis()
        val records = records(now)
        return RemoteJson.status(RemoteJson.StatusInput(
            running = service.isBusy,
            paused = service.runGuard.isPaused(),
            frameAt = service.latestFrame.store.snapshot?.atMs ?: 0L,
            screenOn = power?.isInteractive == true,
            unlocked = service.isDeviceUnlocked(),
            accountName = context.currentAccountName ?: context.activeAccountName,
            snap = snap,
            lootToday = LootSummary.of(records, SessionStats.sinceMs(SessionStats.Period.TODAY, now, zone)),
            accounts = accounts,
            selectedId = selected,
            shown = shown,
            storage = shown?.let { runCatching { StorageStore(service.filesDir).latest(it.id) }.getOrNull() },
            builders = shown?.let { service.builderStore.latest(it.id) },
            health = service.readDeviceHealth(),
            records = records,
            problems = runCatching { tail(now).problems.size }.getOrDefault(0),
            lastPlan = runCatching { PresetStore(service.filesDir).last()?.resolve(accounts, selected) }.getOrNull(),
            eventActive = Policy.EVENT_ACTIVE,
            nowMs = now
        ))
    }

    @Volatile private var recordsCache: Pair<Long, List<BattleRecord>>? = null

    /**
     * The battle history, read at most every half minute: the page polls every few seconds and
     * the history is up to a thousand files.
     */
    private fun records(now: Long): List<BattleRecord> {
        recordsCache?.takeIf { now - it.first in 0 until CACHE_MS }?.let { return it.second }
        val records = runCatching { BattleHistoryStore(File(service.filesDir, "history/battles")).load().records }
            .getOrDefault(emptyList())
        recordsCache = now to records
        return records
    }

    /** The log tail with what is built from it, for [TAIL_MS]; every route that reads the log shares it. */
    private class Tail(val atMs: Long, val events: List<JSONObject>, val groups: List<RunGroup>, val problems: List<Problem>)

    private val tailLock = Any()
    @Volatile private var tailCache: Tail? = null

    /**
     * Yesterday's and today's events as the Activity screen reads them ([LogTail], merged with the
     * live buffer), grouped into runs, and the "Needs you" inbox built the app's way. Read at most
     * every [TAIL_MS] and by one request at a time: during a run the log grows on every event, and
     * the page's polls arrive together. Dismissals saved in the app are honoured; the page cannot dismiss.
     */
    private fun tail(now: Long): Tail = synchronized(tailLock) { tailCache?.takeIf { now - it.atMs in 0 until TAIL_MS } ?: readTail(now) }

    private fun readTail(now: Long): Tail {
        val lines = LogTail.read(File(service.filesDir, "logs"), now, zone = zone)
        val events = LogTail.merge(Timeline.parse(lines), AppLog.readRecentLogs(LIVE_LINES))
            .sortedBy { Format.parseTime(it.optString("time")) ?: Long.MIN_VALUE }
        val all = Timeline.group(events)
        // As in the app: a capped tail's oldest stretch was cut at the cap, so it starts at the next whole group.
        val groups = if (lines.size >= LogTail.MAX_LINES && all.size > 1 && all.last().kind == RunGroup.Kind.IDLE) all.dropLast(1) else all
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        val dismissed = runCatching { File(service.filesDir, Problems.FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()
        val problems = runCatching {
            ActivityProblems.inbox(service.filesDir, accounts, profiles(accounts), events, Problems.dismissedFromJson(dismissed), zone)
        }.getOrDefault(emptyList())
        return Tail(now, events, groups, problems).also { tailCache = it }
    }

    private fun profiles(accounts: List<AccountRecord>): Map<String, PhoneProfile?> =
        accounts.associate { it.id to runCatching { store.readProfile(it.id) }.getOrNull() }

    override fun presets(): List<RunPlan> {
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        val selected = runCatching { store.selectedId() }.getOrNull()
        return PresetStore(service.filesDir).presets().map { it.resolve(accounts, selected) }
    }

    override fun preview(plan: RunPlan): JSONObject {
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        val selected = runCatching { store.selectedId() }.getOrNull()
        val now = System.currentTimeMillis()
        // The page's Stop shows only during a run, so the waiting start is cancelled on the phone.
        val waiting = service.pendingStart?.let { "A start is set for ${Format.time(it.atMs, zone)}; cancel it on the phone first" }
        val problems = listOfNotNull(awakeProblem(), FarmerService.INSPECTING.takeIf { service.inspecting }, waiting, eventProblem(plan)) +
            plan.readiness(accounts, profiles(accounts), connected = true, busy = service.isBusy, selectedId = selected, nowMs = now, zone = zone).problems
        return RemoteJson.preview(problems, plan.summary(accounts, now, selected, zone))
    }

    override fun startPlan(plan: RunPlan) {
        val accounts = store.load()
        val selected = runCatching { store.selectedId() }.getOrNull()
        val picked = plan.resolve(accounts, selected).accountIds.map { id ->
            accounts.firstOrNull { it.id == id } ?: throw IllegalStateException("No such account")
        }
        if (picked.size == 2) {
            AppLog.log("remote_command", "command" to "combined", "first" to picked[0].villageName, "second" to picked[1].villageName)
        } else {
            AppLog.log("remote_command", "command" to "start", "account" to picked[0].villageName)
        }
        guarded(picked.joinToString(" + ") { it.villageName }) {
            requireAwake()
            requireNoInspection()
            eventProblem(plan)?.let { throw IllegalStateException(it) }
            val now = System.currentTimeMillis()
            plan.readiness(accounts, profiles(accounts), connected = true, busy = service.isBusy, selectedId = selected, nowMs = now, zone = zone)
                .reason?.let { throw IllegalStateException(it) }
            service.overlay?.updateStatus("Remote start for ${picked.joinToString(" then ") { it.displayName }}...")
            when (val launch = plan.launch(accounts, now, selected, zone)) {
                is Launch.Single -> service.startPlan(picked[0], launch)
                is Launch.Combined -> service.startPlanCombined(picked[0], picked[1], launch)
                is Launch.Refused -> throw IllegalStateException(launch.reason)
            }
        }
        PresetStore(service.filesDir).setLast(plan)
    }

    private fun eventProblem(plan: RunPlan): String? =
        if (plan.eventTroops && !Policy.EVENT_ACTIVE) "Event troops are not available now" else null

    override fun stats(days: Int): JSONObject {
        val now = System.currentTimeMillis()
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        // The same reader as the Stats tab, so a session older than the two-day tail keeps its end word.
        val endings = runCatching { RunLog.shared(File(service.filesDir, "logs")).endings() }.getOrDefault(emptyMap())
        return RemoteJson.stats(records(now), accounts, endings, days, now, zone)
    }

    override fun timeline(n: Int): JSONObject {
        val tail = tail(System.currentTimeMillis())
        return RemoteJson.timeline(tail.groups, tail.problems, zone, tail.events.takeLast(RemoteJson.TECHNICAL), lines = n)
    }

    override fun historyCsv(days: Int): String {
        val now = System.currentTimeMillis()
        val accounts = runCatching { store.load() }.getOrDefault(emptyList())
        return HistoryCsv.of(records(now), accounts, zone, RemoteJson.csvSince(days, now, zone))
    }

    override fun recentEvents(n: Int): List<String> = AppLog.readRecentLogs(n)
    override fun frame(): local.clashfarmer.service.FrameStore.Snapshot? = service.latestFrame.store.snapshot

    override fun start(accountId: String, mode: RemoteMode) {
        val account = store.load().firstOrNull { it.id == accountId }
            ?: throw IllegalStateException("No such account")
        AppLog.log("remote_command", "command" to "start", "mode" to mode.key, "account" to account.villageName)
        guarded(account.villageName) {
            requireAwake()
            requireNoInspection()
            val long = local.clashfarmer.core.Policy.LONG_RUN_ATTACKS
            if (mode != RemoteMode.SWITCH) {
                val profile = store.readProfile(account.id)
                    ?: throw IllegalStateException("${account.villageName} is not calibrated")
                if (mode == RemoteMode.WALLS) {
                    profile.validateForWalls()
                    account.settings.wallCapacityProblem()?.let { throw IllegalStateException(it) }
                } else profile.validateForAttack()
                val farming = mode == RemoteMode.FARM || mode == RemoteMode.FARM40 || mode == RemoteMode.EVENT
                if (mode == RemoteMode.FARM2H) {
                    local.clashfarmer.core.Policy.timedRunSettings(account.settings).wallSetupProblem()
                        ?.let { throw IllegalStateException(it) }
                    profile.validateForWalls()
                }
                if (farming && account.settings.wallsEnabled) {
                    account.settings.wallSetupProblem()?.let { throw IllegalStateException(it) }
                    profile.validateForWalls()
                }
                // The dashboard lets only the supervised attack and the wall phase run unverified.
                if (mode != RemoteMode.SUPERVISED && mode != RemoteMode.WALLS && !profile.isVerified) {
                    throw IllegalStateException(local.clashfarmer.core.Policy.verificationRequired(account.villageName))
                }
            }
            service.overlay?.updateStatus("Remote start (${mode.key}) for ${account.displayName}...")
            when (mode) {
                RemoteMode.ATTACKS -> service.startAttacksOnlyAccount(account)
                RemoteMode.ATTACKS40 -> service.startAttacksOnlyAccount(account, long)
                RemoteMode.FARM -> service.startFarmingAccount(account)
                RemoteMode.FARM40 -> service.startFarmingAccount(account, long)
                RemoteMode.FARM2H -> service.startTimedFarmingAccount(account)
                RemoteMode.EVENT -> service.startEventAccount(account, long)
                RemoteMode.SUPERVISED -> service.startSupervisedAccount(account)
                RemoteMode.WALLS -> service.startWallPhaseAccount(account)
                RemoteMode.SWITCH -> service.startSwitchOnly(account)
            }
        }
    }

    override fun startCombined(first: String, second: String, firstAttacks: Int, secondAttacks: Int, minutes: Int) {
        val accounts = store.load()
        val a = accounts.firstOrNull { it.id == first } ?: throw IllegalStateException("No such account")
        val b = accounts.firstOrNull { it.id == second } ?: throw IllegalStateException("No such account")
        AppLog.log("remote_command", "command" to "combined", "first" to a.villageName, "firstAttacks" to firstAttacks,
            "second" to b.villageName, "secondAttacks" to secondAttacks, "minutes" to minutes)
        guarded("${a.villageName} + ${b.villageName}") {
            requireAwake()
            requireNoInspection()
            local.clashfarmer.core.Policy.validateLimits(firstAttacks, minutes)
            local.clashfarmer.core.Policy.validateLimits(secondAttacks, minutes)
            service.overlay?.updateStatus("Remote combined run: ${a.displayName} then ${b.displayName}...")
            service.startCombinedRun(a, b, firstAttacks, secondAttacks, minutes)
        }
    }

    private fun requireAwake() {
        awakeProblem()?.let { throw IllegalStateException(it) }
    }

    /** Inspect and the tray check bring Clash up for one frame; a run started then is stopped when the app returns. */
    private fun requireNoInspection() {
        if (service.inspecting) throw IllegalStateException(FarmerService.INSPECTING)
    }

    private fun awakeProblem(): String? {
        val power = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive != true) return "The phone's screen is off"
        if (!service.isDeviceUnlocked()) return "The phone is locked"
        return null
    }

    /** Every refusal reaches the page as a readable IllegalStateException, and the log. */
    private fun guarded(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            AppLog.log("remote_start_refused", "account" to what, "reason" to e.message)
            throw e as? IllegalStateException ?: IllegalStateException(e.message ?: "Could not start")
        }
    }

    override fun stop() {
        AppLog.log("remote_command", "command" to "stop")
        service.stop(local.clashfarmer.core.UserStop.REMOTE)
    }

    override fun setDamageEnd(accountId: String, on: Boolean, min: Int, max: Int) {
        val account = store.load().firstOrNull { it.id == accountId }
            ?: throw IllegalStateException("No such account")
        AppLog.log("remote_command", "command" to "damage", "account" to account.villageName,
            "on" to on, "min" to min, "max" to max)
        // A run keeps the settings it started with, so a change now would not be the one in use.
        if (service.isBusy) throw IllegalStateException("Settings cannot be saved while a run is active")
        val e = account.settings.earlyEnd
        val earlyEnd = e.copy(enabled = e.enabled || on, atDamage = on, damageMin = min, damageMax = max)
        try {
            store.update(account.copy(settings = account.settings.copy(earlyEnd = earlyEnd)))
        } catch (x: IllegalArgumentException) {
            throw IllegalStateException(x.message ?: "Settings are not valid")
        }
    }

    override fun togglePause(): Boolean {
        val guard = service.runGuard
        // With nothing running a Pause is ignored, as the notification's is: kept between runs,
        // it held the next Start with no Resume shown (UO-21 review, 15 October). A Resume
        // still lets go of a pause carried that far.
        if (guard.isPaused()) guard.resume() else if (service.isBusy) guard.pause()
        AppLog.log("pause_button", "paused" to guard.isPaused(), "source" to "remote")
        return guard.isPaused()
    }

    private companion object {
        const val CACHE_MS = 30_000L
        const val TAIL_MS = 15_000L
        /** The live buffer's size; the app's Activity screen merges the same. */
        const val LIVE_LINES = 150
    }
}
