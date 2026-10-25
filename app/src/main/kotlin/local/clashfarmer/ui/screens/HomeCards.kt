package local.clashfarmer.ui.screens

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.Length
import local.clashfarmer.core.PendingWallPurchase
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.StorageReading
import local.clashfarmer.history.BattleRecord
import local.clashfarmer.insight.EventText
import local.clashfarmer.insight.Format
import local.clashfarmer.insight.SessionStats
import local.clashfarmer.log.RunContextSnapshot
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.RunNotice
import local.clashfarmer.ui.components.ResourceBar
import local.clashfarmer.ui.components.Tone
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * Home's words, tiles and attention card, worked out from plain inputs so the rules are tested
 * off the phone. HomeScreen only lays these out.
 */
object HomeCards {
    /** A reading older than this shows its age in amber. */
    const val STALE_BUILDERS_MS = 6 * 3_600_000L

    enum class Kind { WALL_PENDING, UNKNOWN_PURCHASE, NOT_CALIBRATED, NEEDS_YOU, FULL_WALLS_OFF, FULL_NO_BUILDER, DEVICE_GUARD }

    /** The one card Home shows when something needs attention. [action] is a host action. */
    data class Attention(
        val kind: Kind,
        val tone: Tone,
        val title: String,
        val text: String,
        val button: String? = null,
        val action: String? = null,
        val enabled: Boolean = true
    )

    /** A stat tile's value, label and optional chip. */
    data class TileText(val value: String, val label: String, val chip: String? = null, val chipTone: Tone? = null)

    /**
     * The first card that applies to [account], in this order: an unresolved wall purchase, a
     * missing calibration, a last run stopped for a reason that needs a person, full storage with
     * walls off, full storage with no free builder, and the battery or heat guard ending the last
     * run. Null when none applies. The rest live in Activity's inbox. [wallsOn] is whether the
     * run Start would begin buys walls.
     */
    fun attention(
        account: AccountRecord?,
        profile: PhoneProfile?,
        markers: Map<String, PendingWallPurchase?>,
        storage: StorageReading?,
        builders: BuilderReading?,
        connected: Boolean,
        busy: Boolean,
        wallsOn: Boolean = account?.settings?.wallsEnabled == true
    ): Attention? {
        if (account == null) return null
        val name = account.villageName
        val last = account.lastResult
        if (markers.containsKey(account.id)) {
            val check = "I checked the village"
            val acknowledge = "acknowledge_wall:${account.id}"
            val blocked = " Wall spending stays blocked until you check the village."
            if (last?.purchaseOutcomeUnknown == true) {
                val what = if (last.unknownPurchaseWalls > 0 && last.unknownPurchaseCost > 0) {
                    "A purchase of ${Format.count(last.unknownPurchaseWalls, "wall")} for ${Format.amount(last.unknownPurchaseCost)}" +
                        (last.unknownPurchaseCurrency?.let { " ${EventText.clean(it)}" } ?: "")
                } else {
                    "A wall purchase"
                }
                return Attention(Kind.UNKNOWN_PURCHASE, Tone.BAD, "Wall purchase outcome unknown",
                    "$what on $name may or may not have gone through.$blocked", check, acknowledge, enabled = !busy)
            }
            val marker = markers[account.id]
            val what = marker?.let {
                "${Format.count(it.count, "wall")} for ${Format.amount(it.total)} ${Format.resourceName(it.currency).lowercase()} on $name " +
                    "were sent but never confirmed."
            } ?: "A wall purchase on $name was never confirmed."
            return Attention(Kind.WALL_PENDING, Tone.BAD, "Wall purchase not confirmed", what + blocked, check, acknowledge, enabled = !busy)
        }
        if (profile == null) {
            return Attention(Kind.NOT_CALIBRATED, Tone.BAD, "$name is not calibrated",
                "Calibrate it before the first run.", "Open calibration", "calibrate", enabled = !busy)
        }
        val stop = last?.stopReason
        // A run with a completion reason finished; only a stop can need a person.
        if (stop != null && last.completionReason == null && !EventText.isUserStop(stop)) {
            val notice = RunNotice.stopped(null, stop)
            if (notice.kind == RunNotice.Kind.NEEDS_YOU) {
                return Attention(Kind.NEEDS_YOU, Tone.BAD, notice.title, "The last run on $name stopped: ${EventText.stopLine(stop)}.")
            }
        }
        if (storageFull(account, storage)) {
            if (!wallsOn) {
                return Attention(Kind.FULL_WALLS_OFF, Tone.WARN, "Storage is full",
                    "Walls are off; turn them on in Run or loot is wasted.")
            }
            if (builders != null && builders.free == 0) {
                val wallError = runCatching { profile.validateForWalls() }.exceptionOrNull()?.message
                    ?: account.settings.wallCapacityProblem()
                return Attention(Kind.FULL_NO_BUILDER, Tone.WARN, "Storage is full and walls cannot spend",
                    "No builder is free (0 of ${builders.total}). Free a builder in Clash, or farming adds nothing.",
                    "Walls only", "walls", enabled = connected && !busy && wallError == null)
            }
        }
        if (last?.completionReason == CompletionReason.DEVICE_GUARD.key) {
            return Attention(Kind.DEVICE_GUARD, Tone.WARN, RunNotice.deviceGuard(null, stop).title,
                "The battery guard ended the last run at home. Plug the laptop in before the next one.")
        }
        return null
    }

    /**
     * Whether the run Start would begin on [account] buys walls: a one-account [last] plan's own
     * toggle, else the account's saved switch, which both-account runs follow.
     */
    fun wallsOn(account: AccountRecord?, last: RunPlan?, accounts: List<AccountRecord>, selectedId: String?): Boolean {
        val planned = last?.resolve(accounts, selectedId)?.takeIf { !it.isBoth && it.accountIds.firstOrNull() == account?.id }
        return planned?.walls ?: (account?.settings?.wallsEnabled == true)
    }

    /** Gold or elixir at [ResourceBar.FULL_AT] of its saved capacity or more. */
    fun storageFull(account: AccountRecord, storage: StorageReading?): Boolean = listOf(Resource.GOLD, Resource.ELIXIR).any { r ->
        val value = storage?.values?.get(r)
        val capacity = account.settings.capacities[r]
        value != null && capacity != null && capacity > 0 && value.toDouble() / capacity >= ResourceBar.FULL_AT
    }

    /** The state word: Idle, Starting, Running, Paused, or how the last run ended. */
    fun headline(snapshot: SessionSnapshot, busy: Boolean, paused: Boolean): String = when {
        busy && paused -> "Paused"
        busy && (snapshot.startedAt == null || snapshot.endedAt != null) -> "Starting"
        busy -> "Running"
        snapshot.startedAt == null -> "Idle"
        snapshot.completionReason != null -> EventText.endWord(snapshot.completionReason)
        else -> "Stopped"
    }

    /**
     * "Last run 06:51-07:40 · 12 of 40 attacks · storage full, no free builder"; "Last run on Sweat
     * 06:51-07:40 ..." when [other] names the account it ran on, which is not the one shown.
     */
    fun lastRunLine(snapshot: SessionSnapshot, zone: ZoneId, other: String? = null): String {
        val start = snapshot.startedAt ?: return "No run yet."
        val run = if (other == null) "Last run " else "Last run on $other "
        val parts = mutableListOf(run + Format.timeRange(start, snapshot.endedAt ?: snapshot.lastEventAt ?: start, zone))
        parts += if (snapshot.wallOnly) "walls only" else Format.attacks(snapshot.completed, snapshot.limit)
        when {
            snapshot.stopReason != null -> parts += EventText.stopLine(snapshot.stopReason)
            // A finish with more to say than its word, such as why walls did not spend.
            snapshot.completionReason != null && snapshot.phase != EventText.endWord(snapshot.completionReason) -> parts += snapshot.phase
        }
        return parts.joinToString(" · ")
    }

    /**
     * Whose the log's last run was: the account its newest session named, which [snapshot] keeps
     * however many lines follow. A log from before sessions named it falls back to the first of the
     * run's lines in [events], the log's raw lines, oldest first, that names one; a retry or a return
     * home inside a run can name none, and lines after a handoff inside the run are the next
     * account's switch. Null before any run, or when nothing names an account in [accounts].
     */
    fun lastRunAccount(snapshot: SessionSnapshot, events: List<String>, accounts: List<AccountRecord>): AccountRecord? {
        val start = snapshot.startedAt ?: return null
        snapshot.accountId?.let { id -> return accounts.firstOrNull { it.id == id } }
        val end = snapshot.endedAt ?: snapshot.lastEventAt ?: start
        for (raw in events) {
            val e = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val at = Format.parseTime(e.optString("time"))?.takeIf { it >= start } ?: continue
            if (at > end || e.optString("event") == "account_handoff") return null
            val id = EventText.str(e, "accountId") ?: continue
            return accounts.firstOrNull { it.id == id }
        }
        return null
    }

    /**
     * The frame's chip once the run is over: "Last seen 14:59" when [accountId], the account whose
     * session was running when the frame was captured, is the one shown, "Sweat, last seen 14:59"
     * when it is not. Null hides the frame: one captured during a switch, an inspect or anything
     * else outside a session, or naming an account not in [accounts], could be either village.
     */
    fun lastSeen(frameAt: Long, accountId: String?, accounts: List<AccountRecord>, shownId: String?, zone: ZoneId): String? {
        val account = accountId?.let { id -> accounts.firstOrNull { it.id == id } } ?: return null
        val at = Format.time(frameAt, zone)
        return if (account.id == shownId) "Last seen $at" else "${account.villageName}, last seen $at"
    }

    /** "52 of 80 attacks · 1:12:05 · ends by 08:50"; a combined run counts both accounts. */
    fun progressLine(snapshot: SessionSnapshot, runContext: RunContextSnapshot, nowMs: Long, zone: ZoneId): String {
        val parts = mutableListOf<String>()
        parts += when {
            snapshot.wallOnly -> "Walls only"
            runContext.combinedRunId != null && runContext.combinedTotal > 0 ->
                Format.attacks(runContext.combinedCompleted, runContext.combinedTotal)
            else -> Format.attacks(snapshot.completed, snapshot.limit)
        }
        val started = snapshot.startedAt
        if (started != null) {
            parts += Format.clock(snapshot.elapsedMs(nowMs))
            if (snapshot.maxMinutes > 0) parts += "ends by " + Format.time(started + snapshot.maxMinutes * 60_000L, zone)
        }
        return parts.joinToString(" · ")
    }

    /** How far the run is, 0..1, or null when it has no attack limit to measure against. */
    fun progress(snapshot: SessionSnapshot, runContext: RunContextSnapshot): Float? = when {
        snapshot.wallOnly -> null
        runContext.combinedRunId != null && runContext.combinedTotal > 0 ->
            (runContext.combinedCompleted.toFloat() / runContext.combinedTotal).coerceIn(0f, 1f)
        snapshot.limit > 0 -> (snapshot.completed.toFloat() / snapshot.limit).coerceIn(0f, 1f)
        else -> null
    }

    fun buildersTile(reading: BuilderReading?, nowMs: Long): TileText =
        if (reading == null) {
            TileText(Format.UNKNOWN, "builders, not read yet")
        } else {
            TileText("${reading.free}/${reading.total}", "builders free", Format.ago(reading.atMs, nowMs),
                if (reading.ageMs(nowMs) > STALE_BUILDERS_MS) Tone.WARN else Tone.NEUTRAL)
        }

    /** [records] is null while the battles are still being read, here and in [lastRaidTile]. */
    fun lootTile(records: List<BattleRecord>?, accountId: String?, nowMs: Long): TileText {
        if (records == null) return TileText(Format.UNKNOWN, "reading battles")
        val rate = accountId?.let { SessionStats.lootRate7d(records, it, nowMs) }
        return if (rate == null) TileText(Format.UNKNOWN, "no battles read") else TileText(Format.rate(rate), "loot per hour, 7 days")
    }

    fun lastRaidTile(records: List<BattleRecord>?, accountId: String?, nowMs: Long): TileText {
        if (records == null) return TileText(Format.UNKNOWN, "reading battles")
        val raid = accountId?.let { SessionStats.lastRaid(records, it) } ?: return TileText(Format.UNKNOWN, "no raid read yet")
        val stars = raid.battle.stars?.let { ", " + Format.count(it, "star") }.orEmpty()
        return TileText(raid.headline, "last raid$stars", Format.ago(raid.battle.endedAtMs ?: raid.battle.startedAtMs, nowMs), Tone.NEUTRAL)
    }

    /** Loot per hour since the running session started, for the shown account. */
    fun runLootTile(records: List<BattleRecord>, accountId: String?, startedAt: Long?): TileText {
        val rate = if (accountId == null || startedAt == null) null else SessionStats.summary(records, startedAt, accountId).lootPerHour
        return TileText(Format.rate(rate), "loot per hour, this run")
    }

    /** "Start Farm 40 + walls on Sweat"; "Choose a run" before any run was started. */
    fun startLabel(plan: RunPlan?, accounts: List<AccountRecord>, selectedId: String?): String {
        if (plan == null) return "Choose a run"
        val what = plan.name ?: planName(plan)
        val resolved = plan.resolve(accounts, selectedId)
        if (resolved.isBoth) return "Start $what"
        val name = accounts.firstOrNull { it.id == resolved.accountIds.firstOrNull() }?.villageName ?: return "Start $what"
        return "Start $what on $name"
    }

    /** A short name for an unnamed plan: "40 attacks + walls", "2 h", "until 08:30", "both accounts". */
    fun planName(plan: RunPlan): String {
        if (plan.isBoth) return "both accounts"
        val length = when (val l = plan.length) {
            is Length.Attacks -> Format.count(l.n, "attack")
            is Length.Minutes -> Format.minutes(l.m)
            is Length.EndBy -> String.format(Locale.US, "until %02d:%02d", l.hour, l.minute)
        }
        return if (plan.walls) "$length + walls" else length
    }

    /** The other account in one row, or null to hide the row. */
    fun nextLine(other: AccountRecord?, runContext: RunContextSnapshot, busy: Boolean, nowMs: Long): String? {
        if (busy) return runContext.nextAccountName?.let { "Next: $it, after this account" }
        if (other == null) return null
        val last = other.lastResult ?: return "Next: ${other.villageName} · no run yet"
        return "Next: ${other.villageName} · last run ${Format.attacks(last.attacksCompleted, last.attackLimit)} · " +
            Format.ago(last.timestamp, nowMs)
    }
}
