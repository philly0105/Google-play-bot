package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.PendingWallPurchase
import local.clashfarmer.core.WallFarmingCoordinator
import local.clashfarmer.service.RunNotice
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** One entry of the "Needs you" inbox. */
data class Problem(
    /** Stable across reloads; the dismissal file stores it. */
    val key: String,
    val tone: Tone,
    val title: String,
    val detail: String,
    val timeMs: Long?,
    val accountId: String?,
    val action: Action,
    /** False for a pending wall purchase: only checking the village clears it. */
    val dismissible: Boolean
) {
    enum class Action { CHECK_VILLAGE, OPEN_SETUP, DISMISS }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("tone", tone.name.lowercase())
        .put("title", title)
        .put("detail", detail)
        .put("time", timeMs ?: JSONObject.NULL)
        .put("accountId", accountId ?: JSONObject.NULL)
        .put("action", action.name.lowercase())
        .put("dismissible", dismissible)
}

/**
 * What needs a person, gathered from the log, the pending wall markers and calibration health.
 * Red first, then newest first. Dismissals are a key-to-time map stored as
 * `files/problems-dismissed.json`; the caller reads and writes the file.
 */
object Problems {
    const val FILE_NAME = "problems-dismissed.json"
    /** Repeated builder skips in one day before they become one entry. */
    const val NO_BUILDER_REPEATS = 3
    /** Dismissals older than this are dropped when the file is written. */
    const val KEEP_DISMISSED_MS = 30L * 86_400_000L

    fun collect(
        events: List<JSONObject>,
        accounts: List<AccountRecord>,
        pendingMarkers: List<PendingWallPurchase>,
        health: Map<String, List<Finding>> = emptyMap(),
        dismissed: Map<String, Long> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Problem> {
        val out = linkedMapOf<String, Problem>()
        fun add(p: Problem) { if (p.key !in out) out[p.key] = p }
        fun name(accountId: String?, e: JSONObject? = null): String? =
            e?.let { EventText.str(it, "accountName") } ?: accounts.firstOrNull { it.id == accountId }?.displayName

        val markerAccounts = pendingMarkers.map { it.accountId }.toSet()
        // Check village clears a marker and logs this; an earlier "not confirmed" for that account is resolved.
        val checkedAt = events.filter { EventText.nameOf(it) == "pending_purchase_acknowledged" }
            .mapNotNull { e -> EventText.str(e, "accountId")?.let { id -> Format.parseTime(e.optString("time"))?.let { id to it } } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, times) -> times.max() }
        for (m in pendingMarkers) {
            val who = name(m.accountId)
            add(Problem(
                key = "marker:${m.accountId}",
                tone = Tone.BAD,
                title = prefixed(who, "wall purchase not confirmed"),
                detail = "${Format.count(m.count, "wall")} for ${Format.amount(m.total)} ${Format.resourceName(m.currency).lowercase()} " +
                    "may have been bought at ${Format.dayTime(m.atMs, zone)}. Look at the village, then confirm. " +
                    "Walls do not run on this account until then.",
                timeMs = m.atMs,
                accountId = m.accountId,
                action = Problem.Action.CHECK_VILLAGE,
                dismissible = false
            ))
        }

        val noBuilder = linkedMapOf<String, MutableList<JSONObject>>()
        val remote = linkedMapOf<String, MutableList<JSONObject>>()
        for (e in events) {
            val time = Format.parseTime(e.optString("time"))
            val accountId = EventText.str(e, "accountId")
            val who = name(accountId, e)
            when (EventText.nameOf(e)) {
                "wall_purchase_outcome_unknown" -> if (accountId == null || (accountId !in markerAccounts && !checkedAfter(checkedAt[accountId], time))) add(Problem(
                    key = "unknown:${time ?: e.toString().hashCode()}",
                    tone = Tone.BAD,
                    title = prefixed(who, "wall purchase not confirmed"),
                    detail = EventText.describe(e)?.text?.let { "$it." } ?: "A wall purchase could not be confirmed; check the village.",
                    timeMs = time,
                    accountId = accountId,
                    action = Problem.Action.DISMISS,
                    dismissible = true
                ))
                "run_stopped" -> {
                    val reason = EventText.str(e, "reason")
                    if (reason != null && !EventText.isUserStop(reason)) {
                        val notice = RunNotice.stopped(null, reason)
                        if (notice.kind == RunNotice.Kind.NEEDS_YOU) {
                            if (notice.title == "Storage full") add(storageFull(who, accountId, time, reason, zone))
                            else add(Problem(
                                key = "stop:${time ?: reason.hashCode()}",
                                tone = Tone.BAD,
                                title = prefixed(who, "run stopped, ${EventText.stopLine(reason)}"),
                                detail = EventText.clean(reason).replaceFirstChar { it.uppercase() } + ".",
                                timeMs = time,
                                accountId = accountId,
                                action = Problem.Action.DISMISS,
                                dismissible = true
                            ))
                        }
                    }
                }
                "session_finished" -> if (EventText.str(e, "reason") == CompletionReason.STORAGE_FULL.key) {
                    add(storageFull(who, accountId, time, EventText.str(e, "detail"), zone))
                }
                "device_guard_stop" -> add(Problem(
                    key = "guard:${time ?: e.toString().hashCode()}",
                    tone = Tone.WARN,
                    title = prefixed(who, "ended early to save the battery"),
                    detail = (EventText.describe(e)?.text ?: "The battery guard ended the run at home") +
                        ". Plug the laptop in before the next run.",
                    timeMs = time,
                    accountId = accountId,
                    action = Problem.Action.DISMISS,
                    dismissible = true
                ))
                "wall_phase_skipped_no_builder" ->
                    noBuilder.getOrPut("${accountId ?: "-"}:${day(time, zone)}") { mutableListOf() } += e
                "remote_denied", "remote_peer_refused" ->
                    remote.getOrPut("${EventText.str(e, "peer") ?: "unknown"}:${day(time, zone)}") { mutableListOf() } += e
            }
        }

        for ((k, list) in noBuilder) {
            if (list.size < NO_BUILDER_REPEATS) continue
            val last = list.last()
            val accountId = EventText.str(last, "accountId")
            add(Problem(
                key = "nobuilder:$k",
                tone = Tone.WARN,
                title = prefixed(name(accountId, last), "walls skipped ${list.size} times: no free builder"),
                detail = "Every builder was busy each time walls were due. Free a builder in Clash, or farming fills storage with nothing to spend it on.",
                timeMs = Format.parseTime(last.optString("time")),
                accountId = accountId,
                action = Problem.Action.DISMISS,
                dismissible = true
            ))
        }

        for ((k, list) in remote) {
            val peer = EventText.str(list.last(), "peer") ?: "an unknown device"
            add(Problem(
                key = "remote:$k",
                tone = Tone.WARN,
                title = "Remote page refused $peer",
                detail = "${Format.count(list.size, "request")} from $peer ${if (list.size == 1) "was" else "were"} refused " +
                    "(no key, a wrong key, or not on Tailscale). If this was not you, check who can reach this PC.",
                timeMs = Format.parseTime(list.last().optString("time")),
                accountId = null,
                action = Problem.Action.DISMISS,
                dismissible = true
            ))
        }

        for ((accountId, findings) in health) {
            val who = name(accountId)
            for (f in findings) {
                if (f.tone != Tone.BAD && f.tone != Tone.WARN) continue
                add(Problem(
                    key = "health:$accountId:${f.kind.name.lowercase()}" + (f.fingerprint?.let { ":$it" } ?: ""),
                    tone = f.tone,
                    title = prefixed(who, f.title.replaceFirstChar { it.lowercase() }),
                    detail = f.detail,
                    timeMs = null,
                    accountId = accountId,
                    action = Problem.Action.OPEN_SETUP,
                    dismissible = true
                ))
            }
        }

        return out.values
            .filter { !isDismissed(it, dismissed) }
            .sortedWith(compareBy<Problem> { if (it.tone == Tone.BAD) 0 else 1 }.thenByDescending { it.timeMs ?: Long.MIN_VALUE })
    }

    /**
     * Dismissed only by a dismissal made at or after the problem's time, so a day-keyed problem
     * (`remote:`, `nobuilder:`, `full:`) that happens again later that day shows again.
     */
    fun isDismissed(p: Problem, dismissed: Map<String, Long>): Boolean {
        if (!p.dismissible) return false
        val at = dismissed[p.key] ?: return false
        return at >= (p.timeMs ?: Long.MIN_VALUE)
    }

    private fun checkedAfter(checked: Long?, time: Long?) = checked != null && (time == null || checked >= time)

    private fun storageFull(who: String?, accountId: String?, time: Long?, reason: String?, zone: ZoneId) = Problem(
        key = "full:${accountId ?: "-"}:${day(time, zone)}",
        tone = Tone.WARN,
        title = prefixed(who, "storage full"),
        // With every wall maxed, a free builder or walls turned on would buy nothing.
        detail = if (reason?.contains(WallFarmingCoordinator.ALL_WALLS_MAXED) == true) {
            "The builder's list has no wall left to upgrade, so every wall is maxed. Farming adds nothing until gold or elixir is spent another way in Clash."
        } else {
            "Farming adds nothing until gold or elixir is spent" +
                (reason?.let { ": ${EventText.stopLine(it)}" } ?: "") + ". Free a builder or turn walls on."
        },
        timeMs = time,
        accountId = accountId,
        action = Problem.Action.DISMISS,
        dismissible = true
    )

    private fun prefixed(name: String?, what: String) = if (name.isNullOrBlank()) what.replaceFirstChar { it.uppercase() } else "$name: $what"

    private fun day(ms: Long?, zone: ZoneId): String = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "undated"

    // ----- Dismissals -----

    /** Reads the dismissal file's text; a missing or broken file dismisses nothing. */
    fun dismissedFromJson(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().mapNotNull { k -> (obj.opt(k) as? Number)?.toLong()?.let { k to it } }.toMap()
    }

    /** The file's text, dropping dismissals older than [KEEP_DISMISSED_MS]. */
    fun dismissedToJson(dismissed: Map<String, Long>, nowMs: Long): String = JSONObject().apply {
        dismissed.filter { nowMs - it.value <= KEEP_DISMISSED_MS }.forEach { (k, v) -> put(k, v) }
    }.toString()

    fun dismiss(dismissed: Map<String, Long>, problem: Problem, nowMs: Long): Map<String, Long> =
        if (problem.dismissible) dismissed + (problem.key to nowMs) else dismissed

    /** "Clear resolved": every dismissible problem shown; a pending marker stays. */
    fun dismissAll(dismissed: Map<String, Long>, problems: List<Problem>, nowMs: Long): Map<String, Long> =
        dismissed + problems.filter { it.dismissible }.associate { it.key to nowMs }

    fun toJson(problems: List<Problem>): JSONArray = JSONArray().apply { problems.forEach { put(it.toJson()) } }
}
