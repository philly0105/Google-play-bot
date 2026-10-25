package local.clashfarmer.service

import local.clashfarmer.core.AppSwitchLatch
import local.clashfarmer.core.DeviceGuard
import local.clashfarmer.core.Policy
import local.clashfarmer.core.Resource
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.insight.Format
import local.clashfarmer.log.RunContextSnapshot
import java.time.ZoneId
import java.util.Locale

/** What a run-end notification says. Pure, so the wording can be tested off the phone. */
data class RunNotice(val kind: Kind, val title: String, val text: String) {
    enum class Kind(val key: String) { FINISHED("finished"), STOPPED("stopped"), NEEDS_YOU("needs_you") }

    companion object {
        /** Stops a person asked for, or a time limit the run was given or the shade's hold reached; nothing needs doing. */
        private val ORDINARY_STOPS = listOf("stopped from ", "timeout elapsed", "deadline elapsed",
            AppSwitchLatch.HOLD_TIMEOUT_REASON.lowercase(Locale.ROOT))

        fun finished(account: String?, attacks: Int?, walls: Int, goldSpent: Long, elixirSpent: Long, detail: String? = null): RunNotice {
            val parts = mutableListOf<String>()
            if (attacks != null) parts += "$attacks attack${if (attacks == 1) "" else "s"}"
            if (walls > 0) parts += "$walls wall${if (walls == 1) "" else "s"}"
            if (goldSpent > 0) parts += "${Format.amount(goldSpent)} gold spent"
            if (elixirSpent > 0) parts += "${Format.amount(elixirSpent)} elixir spent"
            // A finish that has more to say than "Complete", such as walls that failed, leads with it.
            val lead = detail ?: "Complete"
            val summary = if (parts.isEmpty()) lead else "$lead, " + parts.joinToString(", ")
            return RunNotice(Kind.FINISHED, title(account, "Run finished"), summary)
        }

        fun stopped(account: String?, reason: String): RunNotice {
            val r = reason.lowercase(Locale.ROOT)
            val needsYou = when {
                r.contains(Policy.VILLAGE_TAKEN.lowercase(Locale.ROOT)) || r.contains("another device") ->
                    "Village opened on another device"
                r.contains("login") -> "Login required"
                r.contains("update") -> "Game update required"
                r.contains("are full") || r.contains("storage") -> "Storage full"
                ORDINARY_STOPS.any { r.contains(it) } -> null
                else -> "Run needs you"
            }
            return if (needsYou == null) {
                RunNotice(Kind.STOPPED, title(account, "Run stopped"), reason)
            } else {
                RunNotice(Kind.NEEDS_YOU, title(account, needsYou), reason)
            }
        }

        /** A run the battery or heat guard ended at home; [reason] is [DeviceGuard.stopReason]'s text when known. */
        fun deviceGuard(account: String?, reason: String?): RunNotice {
            val what = when {
                reason == null -> "Battery or heat guard"
                reason.startsWith(DeviceGuard.HOT_PREFIX) -> DeviceGuard.HOT_PREFIX
                reason.startsWith(DeviceGuard.LOW_BATTERY_PREFIX) -> DeviceGuard.LOW_BATTERY_PREFIX
                else -> "Battery or heat guard"
            }
            return RunNotice(Kind.NEEDS_YOU, title(account, what), reason ?: "The run ended at home to save the battery")
        }

        fun takeoverWait(account: String?, waitMinutes: Int): RunNotice = RunNotice(
            Kind.NEEDS_YOU,
            title(account, "Village opened on another device"),
            "Retrying in $waitMinutes minutes. Close the game on the other device, or the run takes the village back."
        )

        private fun title(account: String?, what: String) =
            if (account.isNullOrBlank()) what else "$account: $what"
    }
}

/**
 * What a run in progress says, for the ongoing notification and the floating panel. Pure, so the
 * wording for each phase can be tested off the phone.
 */
data class LiveStatus(
    val state: State,
    /** The collapsed pill: "12/40", "42:10" for a timed run, "Ready", or "At 23:30". */
    val short: String,
    /** "Sweat · 12 of 40", "Blood 2/2 · 52/80" in a combined run, or "Ready". */
    val title: String,
    /** What the run is doing, in words. A waiting state says what it waits for. */
    val phase: String,
    /** How far the run is, 0..1, or null when nothing is known yet. */
    val fraction: Float?,
    /** The run is held or waiting: the panel shows [phase] in place of a bar that would not move. */
    val waiting: Boolean,
    /** Beside the bar: "30%  42:10", or "Next: Blood" in a combined run. */
    val meta: String
) {
    /** HELD is paused by the notification shade over Clash, and lets go when it closes. */
    enum class State { IDLE, PENDING, RUNNING, PAUSED, HELD }

    val busy: Boolean get() = state == State.RUNNING || state == State.PAUSED || state == State.HELD

    companion object {
        val IDLE = LiveStatus(State.IDLE, "Ready", "Ready", "", null, false, "")

        /** Phases that are waits, with the words shown instead of a bar that would not move. */
        private val WAITS = mapOf(
            "Village opened elsewhere; waiting 10 minutes" to "Waiting 10 min, village opened elsewhere",
            "Reopening village" to "Reopening Clash",
            "Waiting for a readable screen" to "Waiting for a readable screen"
        )

        /**
         * [runStartedAt] is when the service began the current run: until that run's session
         * starts, [snapshot] is still the last run's, and its numbers must not show. [held] is a
         * pause the shade made rather than one a person pressed.
         */
        fun of(
            snapshot: SessionSnapshot,
            context: RunContextSnapshot,
            busy: Boolean,
            paused: Boolean,
            runStartedAt: Long?,
            pending: PendingStart?,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            held: Boolean = false
        ): LiveStatus {
            if (!busy) {
                if (pending == null) return IDLE
                val at = Format.time(pending.atMs, zone)
                val minutes = ((pending.atMs - nowMs + 59_999) / 60_000).coerceAtLeast(0).toInt()
                return LiveStatus(State.PENDING, "At $at", "${pending.label} · starts $at",
                    "Waiting for the start time", null, true, "in ${Format.minutes(minutes)}")
            }
            val state = when {
                paused && held -> State.HELD
                paused -> State.PAUSED
                else -> State.RUNNING
            }
            val startedAt = snapshot.startedAt
            val current = startedAt != null && (runStartedAt == null || startedAt >= runStartedAt)
            val elapsed = if (current) snapshot.elapsedMs(nowMs) else runStartedAt?.let { nowMs - it } ?: 0L
            val clock = Format.clock(elapsed)
            val phase = when {
                state == State.HELD -> "Paused: notification shade open"
                paused -> "Paused"
                !current -> "Starting"
                else -> WAITS[snapshot.phase] ?: snapshot.phase
            }
            val waiting = paused || !current || snapshot.phase in WAITS
            val account = context.currentAccountName ?: context.activeAccountName
            if (context.combinedRunId != null && context.combinedTotal > 0) {
                val done = "${context.combinedCompleted}/${context.combinedTotal}"
                // The first account names the second as next; the second names none.
                val which = if (context.nextAccountName != null) "1/2" else "2/2"
                return LiveStatus(state, done,
                    if (account == null) "Both accounts · $done" else "$account $which · $done",
                    phase, fraction(context.combinedCompleted.toLong(), context.combinedTotal.toLong()), waiting,
                    "Next: ${context.nextAccountName ?: "none"}")
            }
            val name = account ?: "Run"
            if (!current) return LiveStatus(state, clock, name, phase, null, true, clock)
            val timed = snapshot.wallOnly || (snapshot.limit == Policy.TIMED_RUN_ATTACKS && snapshot.maxMinutes > 0)
            val progress = if (timed) fraction(elapsed, snapshot.maxMinutes * 60_000L)
                else fraction(snapshot.completed.toLong(), snapshot.limit.toLong())
            val title = when {
                snapshot.wallOnly -> "$name · ${Format.count(snapshot.wallsUpgraded, "wall")}"
                timed -> "$name · ${Format.count(snapshot.completed, "attack")}"
                else -> "$name · ${snapshot.completed} of ${snapshot.limit}"
            }
            val short = if (timed) clock else "${snapshot.completed}/${snapshot.limit}"
            val meta = if (waiting || progress == null) clock else "${(progress * 100).toInt()}%  $clock"
            return LiveStatus(state, short, title, phase, progress, waiting, meta)
        }

        /**
         * The panel's last line: "+412k gold last raid · 3 walls". [earned] is the last result's
         * loot text from the log; gold leads, else the largest resource read.
         */
        fun lastLine(earned: String?, walls: Int, wallsOn: Boolean): String {
            val loot = Format.parseLoot(earned).filterValues { it > 0 }
            val lead = loot[Resource.GOLD]?.let { Resource.GOLD to it } ?: loot.maxByOrNull { it.value }?.toPair()
            val raid = lead?.let { (r, v) -> "${Format.signed(v)} ${Format.resourceName(r).lowercase(Locale.ROOT)} last raid" }
                ?: "No raid read yet"
            return if (wallsOn || walls > 0) "$raid · ${Format.count(walls, "wall")}" else raid
        }

        private fun fraction(part: Long, whole: Long): Float? =
            if (whole <= 0) null else (part.toFloat() / whole).coerceIn(0f, 1f)
    }
}
