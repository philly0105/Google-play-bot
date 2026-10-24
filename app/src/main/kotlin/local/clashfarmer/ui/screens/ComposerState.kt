package local.clashfarmer.ui.screens

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.Length
import local.clashfarmer.core.Policy
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StopWhen
import local.clashfarmer.insight.Format
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.components.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Every control of the run composer worked out from a [RunPlan], so the rules are tested off the
 * phone. RunScreen only applies it; the edit helpers below return the next plan.
 */
data class ComposerState(
    /** Up to three account names, then "Both". Empty when there is no account yet. */
    val accountOptions: List<String>,
    val accountIndex: Int,
    val bothEnabled: Boolean,
    val lengthIndex: Int,
    /** Time and End by; a run on both accounts counts attacks only. */
    val timeModesEnabled: Boolean,
    val attackCountEnabled: Boolean,
    val attacks: Int?,
    /** The two accounts in farming order when the plan runs both; null for one account. */
    val bothNames: Pair<String, String>?,
    val secondAttacks: Int?,
    val minutes: Int?,
    val endBy: Length.EndBy?,
    val endByLine: String?,
    val walls: Boolean,
    val wallsSubtitle: String,
    val wallsDisabledReason: String?,
    val stopIndex: Int,
    val accountGoalEnabled: Boolean,
    val stopNote: String?,
    val armyVisible: Boolean,
    val armyIndex: Int,
    val eventTroopsEnabled: Boolean,
    val summary: String,
    val problems: List<Problem>,
    val startEnabled: Boolean,
    val startReason: String?
) {
    data class Problem(val text: String, val tone: Tone)

    companion object {
        const val BOTH = "Both"
        const val MAX_ACCOUNT_OPTIONS = 3
        const val ATTACKS = 0
        const val TIME = 1
        const val END_BY = 2
        val LENGTHS = listOf("Attacks", "Time", "End by")
        val STOPS = listOf("Limit only", "Storage full", "Account goal")
        val ARMIES = listOf("Normal", "Event troops")
        const val MAX_NAME = 24

        fun of(
            plan: RunPlan,
            accounts: List<AccountRecord>,
            profiles: Map<String, PhoneProfile?>,
            selectedId: String?,
            connected: Boolean,
            busy: Boolean,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            fromStart: Boolean = false
        ): ComposerState {
            val shown = accounts.take(MAX_ACCOUNT_OPTIONS)
            val resolved = plan.resolve(accounts, selectedId)
            val picked = resolved.accountIds.mapNotNull { id -> accounts.firstOrNull { it.id == id } }
            val both = resolved.isBoth
            val accountIndex = if (both) shown.size else shown.indexOfFirst { it.id == resolved.accountIds.firstOrNull() }.coerceAtLeast(0)
            // Both accounts keep their own goals, so one goal between them is enough (RunPlan.combined).
            val goalsSet = picked.isNotEmpty() && if (both) picked.any { it.settings.goal.isSet } else picked.all { it.settings.goal.isSet }
            val problems = readiness(plan, accounts, profiles, connected, busy, selectedId, nowMs, zone)
            val endBy = plan.length as? Length.EndBy
            return ComposerState(
                accountOptions = if (shown.isEmpty()) emptyList() else shown.map { it.villageName } + BOTH,
                accountIndex = accountIndex,
                bothEnabled = accounts.size >= 2,
                lengthIndex = when (plan.length) {
                    is Length.Attacks -> ATTACKS
                    is Length.Minutes -> TIME
                    is Length.EndBy -> END_BY
                },
                timeModesEnabled = !both,
                attackCountEnabled = plan.length is Length.Attacks,
                attacks = (plan.length as? Length.Attacks)?.n,
                bothNames = if (both && picked.size == 2) picked[0].villageName to picked[1].villageName else null,
                secondAttacks = if (both) plan.secondAttacks ?: (plan.length as? Length.Attacks)?.n else null,
                minutes = (plan.length as? Length.Minutes)?.m,
                endBy = endBy,
                endByLine = endBy?.let { endByLine(it, nowMs, zone, fromStart) },
                walls = if (both) picked.isNotEmpty() && picked.all { it.settings.wallsEnabled } else plan.walls,
                wallsSubtitle = when {
                    both -> ""
                    !plan.walls -> wallsRule(false, 0, 0)
                    else -> picked.firstOrNull()?.settings?.let { saved ->
                        // The rule the run will use, as RunPlan.single sets it: never the saved resume.
                        val run = if (plan.length is Length.Attacks) Policy.farmingRunSettings(saved) else Policy.timedRunSettings(saved)
                        wallsRule(true, run.triggerPercent, run.resumePercent)
                    }.orEmpty()
                },
                wallsDisabledReason = if (both) "Each account uses its own wall setting" else null,
                stopIndex = when (plan.stopWhen) {
                    StopWhen.LIMIT -> 0
                    StopWhen.STORAGE_FULL -> 1
                    StopWhen.ACCOUNT_GOAL -> 2
                    StopWhen.SAVED_GOAL -> if (goalsSet) 2 else 0
                },
                accountGoalEnabled = goalsSet,
                stopNote = if (goalsSet) null else "No goal set. Set one in Setup to use Account goal.",
                armyVisible = Policy.EVENT_ACTIVE,
                armyIndex = if (plan.eventTroops) 1 else 0,
                eventTroopsEnabled = !both,
                summary = plan.summary(accounts, nowMs, selectedId, zone),
                problems = problems.map { Problem(it, toneOf(it)) },
                startEnabled = problems.isEmpty(),
                startReason = problems.firstOrNull()
            )
        }

        /**
         * [RunPlan.readiness] in plain words: a calibration the validator refuses reads as
         * incomplete instead of the validator's message, which names anchors and ROIs.
         */
        fun readiness(
            plan: RunPlan,
            accounts: List<AccountRecord>,
            profiles: Map<String, PhoneProfile?>,
            connected: Boolean,
            busy: Boolean,
            selectedId: String?,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault()
        ): List<String> {
            val plain = mutableMapOf<String, String>()
            for (account in accounts) {
                val profile = profiles[account.id] ?: continue
                val name = account.villageName
                runCatching { profile.validateForAttack() }.exceptionOrNull()?.let {
                    plain["$name: ${it.message}"] = "$name calibration is incomplete; finish it in Setup"
                }
                runCatching { profile.validateForWalls() }.exceptionOrNull()?.let {
                    plain["$name walls: ${it.message}"] = "$name wall calibration is incomplete; finish it in Setup"
                }
            }
            return plan.readiness(accounts, profiles, connected, busy, selectedId, nowMs, zone).problems.map { plain[it] ?: it }
        }

        /** A run already going and a missing supervised test wait on the user; the rest are faults. */
        fun toneOf(problem: String): Tone =
            if (problem == "A run is already going" || problem.endsWith("needs a supervised attack first")) Tone.WARN else Tone.BAD

        fun wallsRule(on: Boolean, trigger: Int, resume: Int): String =
            if (on) "Buy walls at $trigger% storage, spend down to $resume%" else "Walls off for this run"

        /** "Ends at 08:30, in 1 h 10 min", or what is wrong with the time. With [fromStart], [nowMs]
         * is the start time set for later, and the line says "after the start" instead. */
        fun endByLine(end: Length.EndBy, nowMs: Long, zone: ZoneId, fromStart: Boolean = false): String {
            val clock = String.format(Locale.US, "%02d:%02d", end.hour, end.minute)
            val minutes = RunPlan.endByMinutes(end, nowMs, zone)
            val range = "${RunPlan.MIN_END_BY_MINUTES} min to ${Format.minutes(RunPlan.MAX_MINUTES)}"
            return when {
                minutes == null -> "Pick a clock time"
                minutes < RunPlan.MIN_END_BY_MINUTES || minutes > RunPlan.MAX_MINUTES -> if (fromStart)
                    "$clock is ${Format.minutes(minutes)} after the start; pick $range after it"
                else
                    "$clock is ${Format.minutes(minutes)} away; pick $range from now"
                fromStart -> "Ends at $clock, ${Format.minutes(minutes)} after the start"
                else -> "Ends at $clock, in ${Format.minutes(minutes)}"
            }
        }

        /** The plan Run opens with: the last one started, else a fresh one for the selected account. */
        fun initial(last: RunPlan?, accounts: List<AccountRecord>, selectedId: String?): RunPlan {
            if (last != null) return last
            val account = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
            return account?.let { RunPlan.newFor(it).copy(accountIds = listOf(RunPlan.SELECTED)) }
                ?: RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(Policy.LONG_RUN_ATTACKS), walls = false)
        }

        /**
         * One account: the selected one, with the walls toggle back at its saved switch. An
         * account goal falls back to the limit when this account has none, as [RunPlan.newFor] does.
         */
        fun withSingle(plan: RunPlan, account: AccountRecord): RunPlan = plan.copy(
            accountIds = listOf(RunPlan.SELECTED),
            secondAttacks = null,
            walls = account.settings.wallsEnabled,
            stopWhen = if (plan.stopWhen == StopWhen.ACCOUNT_GOAL && !account.settings.goal.isSet) StopWhen.LIMIT else plan.stopWhen,
            name = null
        )

        /** Both accounts, selected first; only an attack count works for two. */
        fun withBoth(plan: RunPlan): RunPlan {
            val n = (plan.length as? Length.Attacks)?.n ?: Policy.LONG_RUN_ATTACKS
            return plan.copy(
                accountIds = listOf(RunPlan.SELECTED, RunPlan.OTHER),
                length = Length.Attacks(n),
                secondAttacks = plan.secondAttacks ?: n,
                eventTroops = false,
                name = null
            )
        }

        fun withLength(plan: RunPlan, index: Int, attacks: Int, minutes: Int, endBy: Length.EndBy): RunPlan = plan.copy(
            length = when (index) {
                TIME -> Length.Minutes(minutes)
                END_BY -> endBy
                else -> Length.Attacks(attacks)
            },
            name = null
        )

        fun stopAt(index: Int): StopWhen = when (index) {
            1 -> StopWhen.STORAGE_FULL
            2 -> StopWhen.ACCOUNT_GOAL
            else -> StopWhen.LIMIT
        }

        /**
         * The attack stepper moves by 1 below 10 so a short test run is reachable, then by 5 on
         * multiples of 5. [next] is one step of 1 away from [prev].
         */
        fun stepAttacks(prev: Int, next: Int): Int {
            val n = when {
                next > prev -> if (prev < 10) prev + 1 else (prev / 5 + 1) * 5
                next < prev -> if (prev <= 10) prev - 1 else (prev - 1) / 5 * 5
                else -> prev
            }
            return n.coerceIn(1, RunPlan.MAX_ATTACKS)
        }

        /** The minutes stepper: 5, then 15 to 120 in steps of 15. */
        fun stepMinutes(prev: Int, next: Int): Int {
            val m = when {
                next > prev -> if (prev < 15) 15 else (prev / 15 + 1) * 15
                next < prev -> if (prev <= 15) 5 else (prev - 1) / 15 * 15
                else -> prev
            }
            return m.coerceIn(5, RunPlan.MAX_MINUTES)
        }

        /** About an hour from now, on the next 5 minutes. */
        fun defaultEndBy(nowMs: Long, zone: ZoneId): Length.EndBy {
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).plusMinutes(60)
            val rounded = t.withSecond(0).withNano(0).plusMinutes(((5 - t.minute % 5) % 5).toLong())
            return Length.EndBy(rounded.hour, rounded.minute)
        }
    }
}
