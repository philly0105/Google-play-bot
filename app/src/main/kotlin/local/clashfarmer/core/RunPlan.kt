package local.clashfarmer.core

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.recognition.PhoneProfile
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** How long a run lasts: an attack count, a number of minutes, or a clock time to end by. */
sealed class Length {
    data class Attacks(val n: Int) : Length()
    data class Minutes(val m: Int) : Length()
    data class EndBy(val hour: Int, val minute: Int) : Length()
}

/** What ends a run before its limit. */
enum class StopWhen(val key: String) {
    /** Only the limit; the account goal is cleared for this run. */
    LIMIT("limit"),
    /** Every storage at capacity, as a goal matching all of them. Needs walls off. */
    STORAGE_FULL("storage_full"),
    /** The account's own saved goal. */
    ACCOUNT_GOAL("account_goal"),
    /**
     * Whatever the account has saved: its goal when one is set, else only the limit. The old run
     * buttons behaved this way, so the seeded presets do too.
     */
    SAVED_GOAL("saved_goal");

    companion object {
        fun fromKey(key: String?): StopWhen? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Exactly what a start hands the service, or why it will not start. Nothing here is written back
 * to the saved account settings.
 */
sealed class Launch {

    /**
     * One account's session, with every limit resolved the way the service resolves it, so two
     * launches that run the same session compare equal however their caller spelled them.
     */
    data class Single(
        val accountId: String,
        val settings: AccountSettings,
        val attacks: Int,
        val maxMinutes: Int,
        val wallsEnabled: Boolean,
        val withEventTroops: Boolean = false,
        val supervised: Boolean = false,
        val wallOnly: Boolean = false
    ) : Launch() {

        /**
         * The same run as [other]. With walls off the trigger and resume levels are read only by
         * wall phases that never start, so they may differ: the old Farm button with walls off
         * carries resume 0 and Attacks only carries the account's own.
         */
        fun sameRunAs(other: Single): Boolean {
            if (wallsEnabled || other.wallsEnabled) return this == other
            fun Single.wallFree() = copy(settings = settings.copy(triggerPercent = 0, resumePercent = 0))
            return wallFree() == other.wallFree()
        }

        companion object {
            /**
             * The launch the service's own start would run for these arguments: the rules of
             * FarmerService.startGuardedAccountSession and runSingleAccountSession, an override
             * winning over the setting, a walls-only phase defaulting to 30 minutes, and a watched
             * session never spending on walls.
             */
            fun resolve(
                accountId: String,
                settings: AccountSettings,
                supervised: Boolean,
                wallOnly: Boolean,
                overrideAttacks: Int? = null,
                overrideMaxMinutes: Int? = null,
                overrideWallsEnabled: Boolean? = null,
                withEventTroops: Boolean = false
            ) = Single(
                accountId = accountId,
                settings = settings,
                attacks = overrideAttacks ?: settings.attacks,
                maxMinutes = overrideMaxMinutes ?: (if (wallOnly) WALLS_ONLY_MINUTES else settings.maxMinutes),
                wallsEnabled = if (supervised) false else (overrideWallsEnabled ?: settings.wallsEnabled),
                withEventTroops = withEventTroops,
                supervised = supervised,
                wallOnly = wallOnly
            )
        }
    }

    /** Both accounts, [first] farmed first, under one [minutes] deadline; walls follow each account. */
    data class Combined(
        val firstId: String,
        val secondId: String,
        val firstSettings: AccountSettings,
        val secondSettings: AccountSettings,
        val firstAttacks: Int,
        val secondAttacks: Int,
        val minutes: Int
    ) : Launch()

    /** Will not start, and [reason] says why in plain words. */
    data class Refused(val reason: String) : Launch()

    companion object {
        const val WALLS_ONLY_MINUTES = 30
        const val SUPERVISED_MINUTES = 15

        /** The "Test one attack" tool: what FarmerService.startSupervisedAccount runs. */
        fun supervised(account: AccountRecord): Launch = Single.resolve(
            account.id, account.settings, supervised = true, wallOnly = false,
            overrideAttacks = 1, overrideMaxMinutes = SUPERVISED_MINUTES, overrideWallsEnabled = false
        )

        /** The "Walls only" tool: what FarmerService.startWallPhaseAccount runs, or its refusal. */
        fun wallsOnly(account: AccountRecord): Launch {
            account.settings.wallCapacityProblem()?.let { return Refused(it) }
            return Single.resolve(
                account.id, account.settings, supervised = true, wallOnly = true,
                overrideMaxMinutes = WALLS_ONLY_MINUTES
            )
        }
    }
}

/** What stands between a plan and its Start button; empty means ready. */
data class Readiness(val problems: List<String>) {
    val ready: Boolean get() = problems.isEmpty()
    /** The first problem, for the disabled button's reason. */
    val reason: String? get() = problems.firstOrNull()
}

/**
 * The run composer's plan. [launch] turns it into exactly the arguments the service's existing
 * starts take, and [readiness] holds the same gates the old run cards had.
 *
 * [accountIds] may name [SELECTED] and [OTHER] instead of an id, so a saved preset follows
 * whichever account is selected. Two ids mean both accounts, in that order.
 */
data class RunPlan(
    val accountIds: List<String>,
    val length: Length,
    /** Both only: the second account's attacks; the first's come from [length]. */
    val secondAttacks: Int? = null,
    /** Single account only; both accounts follow each account's own wall setting. */
    val walls: Boolean,
    val stopWhen: StopWhen = StopWhen.LIMIT,
    val eventTroops: Boolean = false,
    /** Presets only. */
    val name: String? = null
) {
    val isBoth: Boolean get() = accountIds.size == 2

    /** [SELECTED] and [OTHER] replaced by real ids, the way the old combined dialog chose them. */
    fun resolve(accounts: List<AccountRecord>, selectedId: String?): RunPlan {
        if (accountIds.none { it == SELECTED || it == OTHER }) return this
        val selected = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
        val other = accounts.firstOrNull { it.id != selected?.id }
        return copy(accountIds = accountIds.map {
            when (it) {
                SELECTED -> selected?.id ?: it
                OTHER -> other?.id ?: it
                else -> it
            }
        })
    }

    fun launch(
        accounts: List<AccountRecord>,
        nowMs: Long,
        selectedId: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Launch {
        val plan = resolve(accounts, selectedId)
        if (plan.accountIds.size !in 1..2) return Launch.Refused("Pick one account or both")
        val picked = plan.accountIds.map { id -> accounts.firstOrNull { it.id == id } }
        if (picked.any { it == null }) {
            return Launch.Refused(if (plan.isBoth) "Both needs two accounts" else "Pick an account first")
        }
        val found = picked.filterNotNull()
        return if (plan.isBoth) plan.combined(found[0], found[1]) else plan.single(found[0], nowMs, zone)
    }

    private fun single(account: AccountRecord, nowMs: Long, zone: ZoneId): Launch {
        val saved = account.settings
        val attacks: Int
        val minutes: Int
        val timed: Boolean
        when (val l = length) {
            is Length.Attacks -> {
                if (l.n !in 1..MAX_ATTACKS) return Launch.Refused("Attacks must be 1 to $MAX_ATTACKS")
                attacks = l.n
                minutes = saved.maxMinutes
                timed = false
            }
            is Length.Minutes -> {
                if (l.m !in 1..MAX_MINUTES) return Launch.Refused("A run lasts 1 to $MAX_MINUTES minutes")
                attacks = Policy.TIMED_RUN_ATTACKS
                minutes = l.m
                timed = true
            }
            is Length.EndBy -> {
                val m = endByMinutes(l, nowMs, zone) ?: return Launch.Refused("That end time is not a clock time")
                if (m < MIN_END_BY_MINUTES) return Launch.Refused("${clock(l)} is less than $MIN_END_BY_MINUTES minutes away")
                if (m > MAX_MINUTES) return Launch.Refused("${clock(l)} is more than 2 hours away; a run lasts at most $MAX_MINUTES minutes")
                attacks = Policy.TIMED_RUN_ATTACKS
                minutes = m
                timed = true
            }
        }
        var settings = when {
            timed && walls -> Policy.timedRunSettings(saved)
            walls || eventTroops -> Policy.farmingRunSettings(saved)
            else -> saved
        }
        if (eventTroops) settings = settings.copy(earlyEnd = settings.earlyEnd.copy(enabled = false))
        settings = withStopRule(settings, walls) ?: return Launch.Refused(stopRefusal(saved, walls))
        if (walls) settings.copy(wallsEnabled = true).wallSetupProblem()?.let { return Launch.Refused(it) }
        return Launch.Single.resolve(
            account.id, settings, supervised = false, wallOnly = false,
            overrideAttacks = attacks, overrideMaxMinutes = minutes, overrideWallsEnabled = walls,
            withEventTroops = eventTroops
        )
    }

    private fun combined(first: AccountRecord, second: AccountRecord): Launch {
        if (first.id == second.id) return Launch.Refused("Both needs two different accounts")
        if (eventTroops) return Launch.Refused("Event troops run on one account at a time")
        val n = (length as? Length.Attacks)?.n
            ?: return Launch.Refused("For both accounts, set attack counts; the run stops at $COMBINED_MINUTES min")
        val n2 = secondAttacks ?: n
        if (n !in 1..MAX_ATTACKS || n2 !in 1..MAX_ATTACKS) return Launch.Refused("Attacks must be 1 to $MAX_ATTACKS")
        if (stopWhen == StopWhen.ACCOUNT_GOAL && !first.settings.goal.isSet && !second.settings.goal.isSet) {
            return Launch.Refused("No goal set")
        }
        val a = withStopRule(first.settings, first.settings.wallsEnabled)
            ?: return Launch.Refused("${first.villageName}: ${stopRefusal(first.settings, first.settings.wallsEnabled)}")
        val b = withStopRule(second.settings, second.settings.wallsEnabled)
            ?: return Launch.Refused("${second.villageName}: ${stopRefusal(second.settings, second.settings.wallsEnabled)}")
        return Launch.Combined(first.id, second.id, a, b, n, n2, COMBINED_MINUTES)
    }

    /** [settings] with this plan's stop rule as its goal, or null when the rule cannot apply. */
    private fun withStopRule(settings: AccountSettings, walls: Boolean): AccountSettings? = when (stopWhen) {
        StopWhen.LIMIT -> settings.copy(goal = FarmingGoal())
        StopWhen.STORAGE_FULL ->
            if (walls || settings.capacities.isEmpty()) null
            else settings.copy(goal = FarmingGoal(settings.capacities, GoalMatch.ALL))
        // Both accounts keep their own goals, set or not; one of them having one is enough.
        StopWhen.ACCOUNT_GOAL -> if (settings.goal.isSet || isBoth) settings else null
        StopWhen.SAVED_GOAL -> settings
    }

    private fun stopRefusal(settings: AccountSettings, walls: Boolean): String = when {
        stopWhen == StopWhen.ACCOUNT_GOAL -> "No goal set"
        walls -> "Storage full needs walls off; walls spend the storage down"
        else -> "Storage full needs the storage sizes in Setup"
    }

    /**
     * The same gates the old run cards had: the service connected and idle, a calibration that
     * covers attacks, verification for an unwatched run, wall calibration and a working wall setup
     * when walls are on, and two such accounts for both. A plan that will not launch says why.
     */
    fun readiness(
        accounts: List<AccountRecord>,
        profiles: Map<String, PhoneProfile?>,
        connected: Boolean,
        busy: Boolean,
        selectedId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Readiness {
        val problems = mutableListOf<String>()
        if (busy) problems += "A run is already going"
        if (!connected) problems += "Turn on the accessibility service in Setup"
        val plan = resolve(accounts, selectedId)
        for (account in plan.accountIds.mapNotNull { id -> accounts.firstOrNull { it.id == id } }) {
            val name = account.villageName
            val profile = profiles[account.id]
            if (profile == null) {
                problems += "$name is not calibrated"
                continue
            }
            runCatching { profile.validateForAttack() }.exceptionOrNull()?.let { problems += "$name: ${it.message}" }
            if (!profile.isVerified) problems += "$name needs a supervised attack first"
            val walls = if (plan.isBoth) account.settings.wallsEnabled else plan.walls
            if (walls) {
                runCatching { profile.validateForWalls() }.exceptionOrNull()?.let { problems += "$name walls: ${it.message}" }
            }
        }
        (plan.launch(accounts, nowMs, selectedId, zone) as? Launch.Refused)?.let {
            if (it.reason !in problems) problems += it.reason
        }
        return Readiness(problems)
    }

    /** Two plain lines saying what the run will do, or the reason it will not start. */
    fun summary(
        accounts: List<AccountRecord>,
        nowMs: Long,
        selectedId: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val launch = launch(accounts, nowMs, selectedId, zone)
        fun name(id: String) = accounts.firstOrNull { it.id == id }?.villageName ?: "Account"
        val first = when (launch) {
            is Launch.Refused -> return launch.reason
            is Launch.Combined ->
                "${name(launch.firstId)} ${launch.firstAttacks} then ${name(launch.secondId)} " +
                    "${launch.secondAttacks} attacks, up to ${launch.minutes} min. Walls follow each account."
            is Launch.Single -> {
                val howLong = when (val l = length) {
                    is Length.Attacks -> "up to ${launch.attacks} attacks or ${launch.maxMinutes} min"
                    is Length.Minutes -> "farms for ${launch.maxMinutes} min"
                    is Length.EndBy -> "farms until ${clock(l)} (${launch.maxMinutes} min)"
                }
                val walls = if (launch.wallsEnabled) {
                    "Walls at ${launch.settings.triggerPercent}%, spend down to ${launch.settings.resumePercent}%."
                } else {
                    "Walls off."
                }
                "${name(launch.accountId)}: $howLong. $walls"
            }
        }
        val stop = when (stopWhen) {
            StopWhen.LIMIT -> "Ends at the limit."
            StopWhen.STORAGE_FULL -> "Ends early when storage is full."
            StopWhen.ACCOUNT_GOAL -> "Ends early at the account goal."
            StopWhen.SAVED_GOAL -> {
                val goalSet = when (launch) {
                    is Launch.Single -> launch.settings.goal.isSet
                    is Launch.Combined -> launch.firstSettings.goal.isSet || launch.secondSettings.goal.isSet
                    is Launch.Refused -> false
                }
                if (goalSet) "Ends early at the account goal." else "Ends at the limit."
            }
        }
        return "$first\n$stop" + if (eventTroops) " Event troops; battles run to the end." else ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("accounts", JSONArray(accountIds))
        put("length", when (val l = length) {
            is Length.Attacks -> JSONObject().put("kind", "attacks").put("n", l.n)
            is Length.Minutes -> JSONObject().put("kind", "minutes").put("m", l.m)
            is Length.EndBy -> JSONObject().put("kind", "end_by").put("hour", l.hour).put("minute", l.minute)
        })
        secondAttacks?.let { put("secondAttacks", it) }
        put("walls", walls)
        put("stopWhen", stopWhen.key)
        put("eventTroops", eventTroops)
        name?.let { put("name", it) }
    }

    companion object {
        /** Stands for the selected account in a saved plan. */
        const val SELECTED = "selected"
        /** Stands for the first account that is not the selected one. */
        const val OTHER = "other"

        const val MAX_ATTACKS = 100
        const val MAX_MINUTES = 120
        const val MIN_END_BY_MINUTES = 5
        /** The old combined dialog's deadline. */
        const val COMBINED_MINUTES = 120

        /**
         * A fresh composer plan for [account]: the walls toggle starts at the saved wall switch
         * and the stop rule at the saved goal, which is what the old Farm buttons followed.
         */
        fun newFor(account: AccountRecord, length: Length = Length.Attacks(Policy.LONG_RUN_ATTACKS)) = RunPlan(
            accountIds = listOf(account.id),
            length = length,
            walls = account.settings.wallsEnabled,
            stopWhen = if (account.settings.goal.isSet) StopWhen.ACCOUNT_GOAL else StopWhen.LIMIT
        )

        /** Whole minutes from [nowMs] to the next [end] on the clock, or null for an impossible time. */
        fun endByMinutes(end: Length.EndBy, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
            if (end.hour !in 0..23 || end.minute !in 0..59) return null
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            var target = now.withHour(end.hour).withMinute(end.minute).withSecond(0).withNano(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return ((target.toInstant().toEpochMilli() - nowMs) / 60_000L).toInt()
        }

        private fun clock(end: Length.EndBy) =
            "${end.hour.toString().padStart(2, '0')}:${end.minute.toString().padStart(2, '0')}"

        /** The plan in [json], or null when it is not a whole plan. */
        fun fromJson(json: JSONObject): RunPlan? = runCatching {
            val ids = json.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (ids.size !in 1..2) return null
            val l = json.getJSONObject("length")
            val length = when (l.getString("kind")) {
                "attacks" -> Length.Attacks(l.getInt("n"))
                "minutes" -> Length.Minutes(l.getInt("m"))
                "end_by" -> Length.EndBy(l.getInt("hour"), l.getInt("minute"))
                else -> return null
            }
            RunPlan(
                accountIds = ids,
                length = length,
                secondAttacks = if (json.has("secondAttacks")) json.getInt("secondAttacks") else null,
                walls = json.getBoolean("walls"),
                stopWhen = StopWhen.fromKey(json.optString("stopWhen", StopWhen.LIMIT.key)) ?: return null,
                eventTroops = json.optBoolean("eventTroops", false),
                name = if (json.has("name")) json.getString("name") else null
            )
        }.getOrNull()
    }
}
