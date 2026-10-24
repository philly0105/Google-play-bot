package local.clashfarmer.core

import org.json.JSONObject
import java.time.Instant

data class SessionSnapshot(
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val lastEventAt: Long? = null,
    val phase: String = "Ready when you are",
    val completed: Int = 0,
    val limit: Int = 0,
    val maxMinutes: Int = 0,
    val wallOnly: Boolean = false,
    val wallsEnabled: Boolean = false,
    val triggerPercent: Int = 90,
    val resumePercent: Int = 75,
    val cadence: Int = 0,
    val wallsUpgraded: Int = 0,
    val goldSpent: Long = 0,
    val elixirSpent: Long = 0,
    val wallsDisabled: Boolean = false,
    val purchaseOutcomeUnknown: Boolean = false,
    val unknownPurchaseCurrency: String? = null,
    val unknownPurchaseCost: Long = 0,
    val unknownPurchaseWalls: Int = 0,
    val stopReason: String? = null,
    /**
     * "quota_reached", "goal_reached", "storage_full", "wall_pending", "time_limit" or "device_guard" once the
     * session has finished normally.
     */
    val completionReason: String? = null,
    /** When the newest session started; [startedAt] stays at the run's first when a restart continues it. */
    val sessionStartedAt: Long? = null,
    /** Attacks the run completed before its newest session started; already in [completed]. */
    val completedBefore: Int = 0,
    /**
     * The account the run's newest session farmed, from its session_started; null for a log from
     * before sessions named it. A restart that continues the run keeps it.
     */
    val accountId: String? = null,
    val accountName: String? = null
) {
    fun elapsedMs(now: Long): Long = startedAt?.let { ((endedAt ?: now) - it).coerceAtLeast(0) } ?: 0
    val attacksUntilWalls: Int?
        get() = if (wallOnly || cadence < 1 || cadence > limit || wallsDisabled || endedAt != null) null
        else (cadence - completed % cadence).takeIf { completed + it <= limit }
}

/** Presentation-only projection. No decisions or game input depend on these values. */
class SessionSummary {
    var snapshot = SessionSnapshot()
        private set

    /** A restart was logged since the last session_started, so the next one continues the run. */
    private var restarting = false

    /**
     * The service connected again, or a combined run handed off, since the last session_started,
     * so the next one starts afresh whatever was restarted: no run outlives its process, and the
     * next account's switch may itself be restarted.
     */
    private var fresh = false

    fun accept(event: JSONObject) {
        val time = runCatching { Instant.parse(event.optString("time")).toEpochMilli() }.getOrNull()
        val name = event.optString("event")
        if (name == "session_started") {
            val continues = restarting && !fresh && snapshot.startedAt != null && snapshot.endedAt == null
            restarting = false
            fresh = false
            // The restart's session is told only the attacks still owed; the run keeps its start,
            // its limit and settings, its walls and spend, and counts on from what it had done.
            if (continues) {
                snapshot = snapshot.copy(
                    lastEventAt = time ?: snapshot.lastEventAt, phase = "Starting session",
                    sessionStartedAt = time, completedBefore = snapshot.completed
                )
                return
            }
            val limit = event.optInt("maxAttacks")
            val cadence = event.optInt("attacksPerWallPhase")
            val wallsEnabled = if (event.has("wallsEnabled")) event.optBoolean("wallsEnabled")
                else if (cadence in 1..limit) true
                else false
            snapshot = SessionSnapshot(
                startedAt = time, lastEventAt = time,
                phase = if (event.optBoolean("walls")) "Upgrading walls" else "Starting session",
                limit = limit, maxMinutes = event.optInt("maxMinutes"),
                wallOnly = event.optBoolean("walls"),
                wallsEnabled = wallsEnabled,
                triggerPercent = event.optInt("triggerPercent", 90),
                resumePercent = event.optInt("resumePercent", 75),
                cadence = cadence,
                sessionStartedAt = time,
                accountId = event.optString("accountId").ifBlank { null },
                accountName = event.optString("accountName").ifBlank { null }
            )
            return
        }
        // A process killed mid-restart leaves its run open with the restart logged.
        if (name == "service_connected") {
            fresh = true
            return
        }
        // A combined run's handoff reopens the finished first account, so a stop during the
        // second account's switch reads as a stop rather than as the first account's finish.
        if (snapshot.startedAt == null ||
            (snapshot.endedAt != null && name != "wall_purchase_outcome_unknown" && name != "account_handoff")) return
        if (!name.startsWith("wall") && name !in RESTARTS && name !in setOf(
                "action_step", "matchmaking_searching", "waiting_unrecognized", "attack_completed",
                "session_finished", "run_stopped", "account_handoff"
            )) return
        if (name in RESTARTS) restarting = true
        // The next account of a combined run starts afresh.
        if (name == "account_handoff") fresh = true
        snapshot = snapshot.copy(lastEventAt = time ?: snapshot.lastEventAt)
        snapshot = when (name) {
            "action_step" -> snapshot.copy(phase = when (event.optString("action")) {
                "OPEN_ATTACK" -> "Opening attack menu"
                "FIND_MATCH", "CHECK_ARMY" -> "Preparing army"
                "DEPLOY" -> "Deploying army"
                "WAIT" -> "Battle in progress"
                "REWARD" -> "Collecting reward"
                "RETURN_HOME" -> "Returning to village"
                "WALLS" -> "Upgrading walls"
                "FINISH", "TIME_LIMIT" -> "Finishing session"
                else -> "Reading game screen"
            })
            "matchmaking_searching" -> snapshot.copy(phase = "Finding a village")
            "waiting_unrecognized" -> snapshot.copy(phase = "Waiting for a readable screen")
            "village_retry_wait" -> snapshot.copy(phase = "Village opened elsewhere; waiting 10 minutes")
            "village_retry_reopening" -> snapshot.copy(phase = "Reopening village")
            "village_retry_verified" -> snapshot.copy(phase = "Village verified; resuming")
            "attack_completed" -> snapshot.copy(completed = sessionCount(event))
            "wall_upgraded" -> {
                val cost = event.optLong("cost").coerceAtLeast(0)
                snapshot.copy(
                    wallsUpgraded = snapshot.wallsUpgraded + event.optInt("walls").coerceAtLeast(0),
                    goldSpent = snapshot.goldSpent + if (event.optString("currency") == "gold") cost else 0,
                    elixirSpent = snapshot.elixirSpent + if (event.optString("currency") == "elixir") cost else 0
                )
            }
            "wall_spend_unverified", "walls_disabled_after_mismatch" -> snapshot.copy(wallsDisabled = true)
            "wall_purchase_outcome_unknown" -> snapshot.copy(
                wallsDisabled = true,
                purchaseOutcomeUnknown = true,
                unknownPurchaseCurrency = event.optString("currency").ifBlank { null },
                // Before 8 October a cancelled confirmation logged its price as "total".
                unknownPurchaseCost = (if (event.has("cost")) event.optLong("cost") else event.optLong("total"))
                    .coerceAtLeast(0),
                unknownPurchaseWalls = event.optInt("walls").coerceAtLeast(0)
            )
            "session_finished", "wall_session_finished" -> snapshot.copy(
                // A storage-full finish carries why walls did not spend, e.g. a failed wall list.
                phase = event.optString("detail").ifBlank { null } ?: when (event.optString("reason")) {
                    CompletionReason.GOAL_REACHED.key -> "Goal reached"
                    CompletionReason.STORAGE_FULL.key -> "Storage full"
                    CompletionReason.TIME_LIMIT.key -> "Time limit"
                    CompletionReason.DEVICE_GUARD.key -> "Battery or heat guard"
                    else -> "Complete"
                },
                completionReason = event.optString("reason").ifBlank { null },
                endedAt = time ?: snapshot.lastEventAt ?: snapshot.startedAt,
                completed = sessionCount(event)
            )
            "account_handoff" -> snapshot.copy(endedAt = null, completionReason = null, phase = "Switching account")
            "run_stopped" -> snapshot.copy(
                phase = "Stopped", endedAt = time ?: snapshot.lastEventAt ?: snapshot.startedAt,
                stopReason = event.optString("reason", "Session stopped")
            )
            else -> snapshot
        }
    }

    /** The run's attacks from [event]'s "completed", which counts the newest session's only. */
    private fun sessionCount(event: JSONObject): Int =
        snapshot.completedBefore + event.optInt("completed", snapshot.completed - snapshot.completedBefore)

    private companion object {
        /** Logged between a stop the run recovers from and the session_started that resumes it. */
        val RESTARTS = setOf(
            "village_retry_wait", "village_retry_reopening", "village_retry_verified", "run_auto_restart", "run_recovered"
        )
    }
}
