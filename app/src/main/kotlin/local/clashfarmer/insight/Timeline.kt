package local.clashfarmer.insight

import local.clashfarmer.core.Policy
import local.clashfarmer.core.Resource
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * One run, or a stretch of events between runs, as the Activity screen shows it.
 * [lines] are newest first and hold only what [EventText.describe] puts on the timeline.
 */
data class RunGroup(
    val kind: Kind,
    val accountId: String?,
    val accountName: String?,
    /** "Farm 40 + walls", "2 h + walls", "Supervised", "Walls only", "Both accounts (1 of 2)". */
    val mode: String?,
    val startMs: Long?,
    /** Null while the run has not closed in the log. */
    val endMs: Long?,
    /** The [CompletionReason] key it finished on, or null for a stop or an open run. */
    val endReason: String?,
    val closed: Boolean,
    /** The tone of the line that closed the run; [Tone.NEUTRAL] while open. */
    val tone: Tone,
    val completed: Int?,
    val limit: Int?,
    /** Loot from the battle results read in this run. */
    val loot: Map<Resource, Long>,
    val wallsBought: Int,
    val sessionIds: List<String>,
    val lines: List<TimelineLine>,
    /** Idle only: short labels of what happened, first seen first. */
    val topics: List<String> = emptyList()
) {
    enum class Kind { RUN, IDLE }

    /** Complete, Goal reached, Storage full, Time limit, Stopped; "In progress" while open. */
    val endWord: String get() = if (!closed) "In progress" else EventText.endWord(endReason)

    /** "Sweat · Farm 40 + walls · 06:51-07:40 · Stopped" or "Idle · 3 events (inspect, calibration saved)". */
    fun header(zone: ZoneId): String = when (kind) {
        Kind.IDLE -> "Idle · ${Format.count(lines.size, "event")}" +
            if (topics.isEmpty()) "" else " (${topics.take(3).joinToString(", ")})"
        Kind.RUN -> listOfNotNull(accountName, mode ?: "Run", Format.timeRange(startMs, endMs, zone), endWord).joinToString(" · ")
    }

    /** "12 of 40 attacks · +5.1M gold · +4.9M elixir"; null for an idle stretch. */
    fun summary(): String? {
        if (kind == Kind.IDLE) return null
        val parts = mutableListOf<String>()
        if (completed != null || (limit != null && mode != "Walls only")) parts += Format.attacks(completed ?: 0, limit)
        if (completed != null && completed > 0) {
            val read = Resource.entries.filter { (loot[it] ?: 0L) > 0L }
            if (read.isEmpty()) parts += "loot not read"
            else read.filter { it != Resource.DARK_ELIXIR }.forEach { r ->
                parts += "${Format.signed(loot[r])} ${Format.resourceName(r).lowercase()}"
            }
        }
        if (wallsBought > 0) parts += Format.count(wallsBought, "wall") + " bought"
        return parts.joinToString(" · ").ifEmpty { null }
    }

    fun toJson(zone: ZoneId): JSONObject = JSONObject()
        .put("kind", kind.name.lowercase())
        .put("header", header(zone))
        .put("summary", summary() ?: JSONObject.NULL)
        .put("tone", tone.name.lowercase())
        .put("account", accountName ?: JSONObject.NULL)
        .put("start", startMs ?: JSONObject.NULL)
        .put("end", endMs ?: JSONObject.NULL)
        .put("ending", if (kind == Kind.RUN) endWord else JSONObject.NULL)
        .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
}

/**
 * Splits log events into runs. A run opens on `session_started` or `combined_run_started` and
 * closes on `session_finished`, `wall_session_finished`, `run_stopped` or `combined_run_completed`;
 * `account_handoff` closes one account's part of a combined run and opens the next. Events between
 * runs form idle stretches.
 *
 * What the real logs taught it: the account switch is logged before `session_started`, so a run
 * takes the switch lines just before it; `battle_result` comes before its `attack_completed`, so an
 * attack takes the result logged since the previous attack; an automatic restart, and the retry
 * after the village was opened on another device, log a fresh `session_started` inside the same
 * run, told only the attacks still owed and counting them from 0; a run still open when the
 * service connects again died with its process, so no session joins it; and the notification and
 * cancellation lines logged just after a close belong to the run that closed.
 */
object Timeline {

    private val OPENERS = setOf("session_started", "combined_run_started")
    private val CLOSERS = setOf("session_finished", "wall_session_finished", "run_stopped", "combined_run_completed")
    /** Logged before the session_started that resumes a run; a takeover the run gives up on ends in run_stopped. */
    private val RESTARTS = setOf(
        "run_auto_restart", "run_recovered", "village_retry_wait", "village_retry_reopening", "village_retry_verified"
    )
    /**
     * Every event that opens, restarts, hands off or closes a run, and the service connect that
     * marks a run still open as dead: all [endings] needs from a log.
     */
    internal val RUN_EVENTS: Set<String> = OPENERS + CLOSERS + RESTARTS + "account_handoff" + "service_connected"
    /** Events whose "completed" field is the run's attack count. */
    private val COUNTED = setOf("attack_completed", "session_finished")

    /** Logged just before a run starts, as part of starting it. */
    private val PRELUDE = setOf(
        "account_switch_start", "account_switch_required", "account_switch_verified", "account_switch_verify_failed",
        "account_identity_candidate", "account_identity_already_active", "picker_row_selected",
        "welcome_back_dialog_detected", "start_return_home", "start_reached_home", "early_end_damage_floor",
        "handle_action", "new_intent_action", "remote_command", "action_step", "tap_retried", "recovery_step",
        "village_camera_normalized", "popup_dismissed", "popup_dialogue_advanced", "popup_dialogue_cleared"
    )
    private const val PRELUDE_MS = 3 * 60_000L

    /** Logged just after a run closes, about that run. */
    private val TRAILING = setOf(
        "run_cancelled", "run_stopped", "notification_posted", "notification_skipped", "notification_failed",
        "profile_earned_verification", "profile_verification_skipped_recalibrated", "combined_run_completed",
        "failure_snapshot", "failure_snapshot_failed", "stop_side_effect", "storage_full_stop"
    )
    private const val TRAILING_MS = 30_000L

    /** Parses JSONL lines, skipping malformed ones and exact repeats (the live buffer overlaps the file). */
    fun parse(lines: List<String>): List<JSONObject> {
        val seen = HashSet<String>()
        return lines.mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || !seen.add(t)) null else runCatching { JSONObject(t) }.getOrNull()
        }
    }

    /** Groups [events] (oldest first) into runs and idle stretches, newest first. */
    fun group(events: List<JSONObject>): List<RunGroup> {
        val seen = HashSet<String>()
        // Callers may pass events that did not come through parse, so repeats are dropped here too.
        val ordered = events.filter { seen.add(it.toString()) }
            .mapIndexed { i, e -> Triple(e, i, Format.parseTime(e.optString("time")) ?: Long.MIN_VALUE) }
            .sortedWith(compareBy({ it.third }, { it.second }))
            .map { it.first }

        val done = mutableListOf<Builder>()
        var current: Builder? = null
        var idle: Builder? = null
        var lastClosed: Builder? = null

        fun flushIdle() { idle?.let { if (it.entries.isNotEmpty()) done += it }; idle = null }
        fun open(combinedPart: Int?, at: Long?): Builder {
            val b = Builder(RunGroup.Kind.RUN, combinedPart)
            // Take the switch and start lines logged just before this run from the idle stretch.
            idle?.let { stretch ->
                val take = stretch.entries.takeLastWhile { entry ->
                    val t = entry.time
                    entry.name in PRELUDE && (at == null || t == null || at - t <= PRELUDE_MS)
                }
                repeat(take.size) { stretch.entries.removeAt(stretch.entries.size - 1) }
                b.entries += take
            }
            flushIdle()
            return b
        }
        fun close(b: Builder) { b.closed = true; done += b; lastClosed = b; current = null }

        for (e in ordered) {
            val name = EventText.nameOf(e)
            val time = Format.parseTime(e.optString("time"))
            val run = current
            when {
                name == "combined_run_started" -> {
                    run?.let { it.closeTone = Tone.WARN; close(it) }
                    current = open(1, time).also { it.add(e); it.combinedFirst = EventText.str(e, "firstAccount"); it.combinedSecond = EventText.str(e, "secondAccount") }
                }
                name == "session_started" -> {
                    if (run != null && !run.orphaned && (run.sessionIds.isEmpty() || run.restarting)) {
                        run.add(e); run.restarting = false
                    } else {
                        run?.let { it.closeTone = Tone.WARN; close(it) }
                        current = open(null, time).also { it.add(e) }
                    }
                }
                name == "account_handoff" && run != null -> {
                    run.add(e)
                    run.endReason = EventText.str(e, "reason")
                    if (run.closeTone == Tone.NEUTRAL) run.closeTone = Tone.OK
                    close(run)
                    current = Builder(RunGroup.Kind.RUN, 2).also {
                        it.accountName = EventText.str(e, "to")
                        it.combinedFirst = run.combinedFirst; it.combinedSecond = run.combinedSecond
                    }
                }
                name in CLOSERS && run != null -> {
                    run.add(e)
                    when (name) {
                        "session_finished" -> {
                            run.endReason = EventText.str(e, "reason")
                            run.closeTone = EventText.describe(e)?.tone ?: Tone.OK
                        }
                        "run_stopped" -> { run.endReason = null; run.closeTone = EventText.stopTone(EventText.str(e, "reason")) }
                        "wall_session_finished" -> { run.endReason = "quota_reached"; run.closeTone = EventText.describe(e)?.tone ?: Tone.OK }
                        "combined_run_completed" -> if (run.endReason == null) run.endReason = EventText.str(e, "secondReason")
                    }
                    // A combined run's account part ends on its handoff or on the run's own end.
                    if (run.combinedPart == null || name != "session_finished") close(run)
                }
                name == "service_connected" && run != null -> { run.add(e); run.orphaned = true }
                name in RESTARTS && run != null -> { run.add(e); run.restarting = true }
                run != null -> run.add(e)
                else -> {
                    val closed = lastClosed
                    val closedAt = closed?.lastTime
                    if (closed != null && name in TRAILING && time != null && closedAt != null && time - closedAt <= TRAILING_MS) {
                        closed.add(e)
                        if (name == "run_stopped" && closed.endReason == null) closed.closeTone = EventText.stopTone(EventText.str(e, "reason"))
                    } else {
                        val stretch = idle ?: Builder(RunGroup.Kind.IDLE, null).also { idle = it }
                        stretch.add(e)
                    }
                }
            }
        }
        current?.let { done += it }
        flushIdle()
        return done.map { it.build() }
            .filter { it.kind == RunGroup.Kind.RUN || it.lines.isNotEmpty() }
            .sortedWith(compareByDescending<RunGroup> { it.startMs ?: Long.MIN_VALUE })
    }

    /** Session id to the short end word ("Done", "Time", "Stopped"), for [SessionStats.sessions]. */
    fun endings(groups: List<RunGroup>): Map<String, String> = groups
        .filter { it.kind == RunGroup.Kind.RUN && it.closed }
        .flatMap { g -> g.sessionIds.map { it to EventText.endWord(g.endReason, short = true) } }
        .toMap()

    /** `/api/timeline`: the groups newest first, each with its lines. */
    fun toJson(groups: List<RunGroup>, zone: ZoneId): JSONArray = JSONArray().apply { groups.forEach { put(it.toJson(zone)) } }

    private class Entry(val event: JSONObject, val name: String, val time: Long?, var result: JSONObject? = null)

    private class Builder(val kind: RunGroup.Kind, val combinedPart: Int?) {
        val entries = mutableListOf<Entry>()
        val sessionIds = mutableListOf<String>()
        var accountName: String? = null
        var accountId: String? = null
        var combinedFirst: String? = null
        var combinedSecond: String? = null
        var restarting = false
        /** The service connected again while this run was open, so its process died and no session joins it. */
        var orphaned = false
        var closed = false
        var endReason: String? = null
        var closeTone = Tone.NEUTRAL
        private var pendingResult: JSONObject? = null

        val lastTime: Long? get() = entries.lastOrNull { it.time != null }?.time

        fun add(e: JSONObject) {
            val name = EventText.nameOf(e)
            val entry = Entry(e, name, Format.parseTime(e.optString("time")))
            when (name) {
                "battle_result" -> pendingResult = e
                "attack_completed" -> { entry.result = pendingResult; pendingResult = null }
                "session_started" -> EventText.str(e, "sessionId")?.let { if (it !in sessionIds) sessionIds += it }
            }
            if (accountName == null && kind == RunGroup.Kind.RUN) accountName = EventText.str(e, "accountName")
            if (accountId == null && kind == RunGroup.Kind.RUN) accountId = EventText.str(e, "accountId")
            entries += entry
        }

        fun build(): RunGroup {
            val lines = mutableListOf<TimelineLine>()
            for (entry in entries) EventText.describe(entry.event, entry.result)?.let { lines += it }
            // A restart's session is given only the attacks still owed; the run is what it started as.
            val session = entries.firstOrNull { it.name == "session_started" }?.event
            val loot = linkedMapOf<Resource, Long>()
            entries.filter { it.name == "attack_completed" }.forEach { a ->
                Format.parseLoot(a.result?.let { EventText.str(it, "earned") }).forEach { (r, v) -> loot[r] = (loot[r] ?: 0L) + v }
            }
            // A timed run's quota is a placeholder the clock ends long before.
            val limit = if (combinedPart == null && session != null && timed(session)) null
                else session?.let { EventText.int(it, "maxAttacks") }
                    ?: entries.lastOrNull { it.name == "attack_completed" }?.let { EventText.int(it.event, "limit") }
            val topics = if (kind == RunGroup.Kind.IDLE) lines.map { topic(it.event) }.distinct() else emptyList()
            return RunGroup(
                kind = kind,
                accountId = accountId,
                accountName = if (kind == RunGroup.Kind.RUN) accountName else null,
                mode = if (kind == RunGroup.Kind.RUN) mode(session) else null,
                startMs = entries.firstOrNull { it.name in OPENERS }?.time ?: entries.firstNotNullOfOrNull { it.time },
                endMs = if (closed || kind == RunGroup.Kind.IDLE) lastTime else null,
                endReason = endReason,
                closed = closed || kind == RunGroup.Kind.IDLE,
                tone = if (kind == RunGroup.Kind.IDLE) Tone.IDLE else if (closed) closeTone else Tone.NEUTRAL,
                completed = completed(),
                limit = limit,
                loot = loot,
                wallsBought = entries.filter { it.name == "wall_upgraded" }.sumOf { EventText.int(it.event, "walls") ?: 0 },
                sessionIds = sessionIds.toList(),
                lines = lines.reversed(),
                topics = topics
            )
        }

        private fun mode(session: JSONObject?): String? {
            if (combinedPart != null) return "Both accounts ($combinedPart of 2)"
            if (session == null) return null
            // A walls-only run is logged as supervised too, so walls goes first.
            if (EventText.bool(session, "walls") == true) return "Walls only"
            if (EventText.bool(session, "supervised") == true) return "Supervised"
            val walls = if (EventText.bool(session, "wallsEnabled") == true) " + walls" else ""
            val attacks = EventText.int(session, "maxAttacks")
            val minutes = EventText.int(session, "maxMinutes")
            return when {
                minutes != null && timed(session) -> Format.minutes(minutes) + walls
                attacks != null -> "Farm $attacks$walls"
                else -> "Run$walls"
            }
        }

        private fun timed(session: JSONObject): Boolean =
            (EventText.int(session, "maxAttacks") ?: 0) >= Policy.TIMED_RUN_ATTACKS && EventText.int(session, "maxMinutes") != null

        /** Each session counts its attacks from 0, so the run's are each session's last count added up. */
        private fun completed(): Int? {
            var total: Int? = null
            var session: Int? = null
            for (entry in entries) {
                if (entry.name == "session_started") {
                    session?.let { total = (total ?: 0) + it }
                    session = null
                } else if (entry.name in COUNTED && entry.event.has("completed")) {
                    session = EventText.int(entry.event, "completed")
                }
            }
            return session?.let { (total ?: 0) + it } ?: total
        }
    }

    /** The idle header's short words for what happened. */
    private fun topic(name: String): String = when (name) {
        "inspect_completed", "inspect_failed", "inspect_started" -> "inspect"
        "profile_saved", "profile_load_failed", "failed_to_save_verification" -> "calibration saved"
        "service_connected", "service_destroyed" -> "service"
        "switch_only_completed", "account_switch_verified", "account_identity_already_active", "account_switch_verify_failed" -> "account switch"
        "notification_posted", "notification_failed" -> "notification"
        "pending_purchase_acknowledged" -> "wall check"
        "remote_command", "remote_start_refused", "remote_server_failed" -> "remote"
        "collection_finished", "collection_abandoned" -> "collection"
        else -> "other"
    }
}
