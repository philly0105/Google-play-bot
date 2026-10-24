package local.clashfarmer.core

import local.clashfarmer.log.AppLog

/**
 * Coordinates storage checkpoints, currency choice, and wall phase execution for both
 * automatic farming and manual wall upgrades.
 */
class WallFarmingCoordinator(
    val accountId: String?,
    val wallsEnabled: Boolean,
    val isSupervised: Boolean,
    val rules: WallStorageRules,
    val currencyPreference: String = "auto",
    val reserveGold: Long = Policy.DEFAULT_RESERVE_GOLD,
    val reserveElixir: Long = Policy.DEFAULT_RESERVE_ELIXIR,
    val maxWallLevel: Int = Policy.DEFAULT_MAX_WALL_LEVEL,
    val maxWallsPerPhase: Int = 20,
    val capacities: Map<Resource, Long> = emptyMap(),
    val goal: FarmingGoal = FarmingGoal(),
    val tracker: WallPhaseTracker = WallPhaseTracker(),
    val purchaseStore: WallPurchaseStore? = null,
    val runner: WallRunner? = null
) {

    fun evaluateGoal(samples: List<ResourceSample>): Boolean =
        goal.isSet && goalReached(goal, samples, capacities)

    /**
     * Why this checkpoint must end the run instead of opening another attack, or null while the
     * loot is still usable.
     *
     * The stop needs all three proven: the samples agree gold and elixir are each at a known
     * capacity, no wall phase can spend them (spending off for this run or account, a purchase
     * still pending, no builder free, or every currency suppressed or waiting out a failure's retry), and no
     * goal is configured that more loot could still reach. An unread or disagreeing balance, a
     * currency a phase could still spend, or a goal still short all keep the run attacking.
     * Dark elixir buys no walls and never takes part.
     *
     * The reason says why walls did not spend, so a broken wall phase never reads as a plain
     * finish: "Storage full; walls failed: wall list could not be read".
     */
    fun fullStorageStopReason(samples: List<ResourceSample>, nowMs: Long = System.currentTimeMillis()): String? {
        if (goal.isSet) return null
        val agreed = agreedHomeBalances(samples, capacities)
        if (!storageFull(Resource.GOLD, agreed, capacities) ||
            !storageFull(Resource.ELIXIR, agreed, capacities)) return null
        val pending = accountId?.let { purchaseStore?.isPending(it) } == true
        if (!pending && selectCurrency(agreed, nowMs) != null) return null
        val failures = tracker.lastFailures().values.distinct()
        val why = when {
            pending -> "walls failed: an earlier wall purchase is still pending"
            !wallsEnabled -> "wall spending is off"
            isSupervised -> "a supervised run spends nothing"
            accountId == null -> "no account is bound"
            // A free builder or walls turned on would buy nothing here, so the reason says so.
            tracker.noWallsLeft() -> ALL_WALLS_MAXED
            // Every wall upgrade needs a free builder, so this is the same dead end as spending off.
            tracker.noFreeBuilder(nowMs) -> "walls skipped: no builder is free"
            failures.isNotEmpty() -> "walls failed: " + failures.joinToString(", ") { describe(it) }
            else -> "no wall left to buy"
        }
        return "Storage full; $why"
    }

    private fun describe(failure: WallPhaseFailure): String = when (failure) {
        WallPhaseFailure.INVALID_ORDER -> "the wall order was invalid"
        WallPhaseFailure.PENDING_PURCHASE -> "an earlier wall purchase is still pending"
        WallPhaseFailure.LIST_FAILED -> "wall list could not be read"
        WallPhaseFailure.PANEL_UNREADABLE -> "wall panel could not be read"
        WallPhaseFailure.PRICE_CHANGED -> "wall price changed"
        WallPhaseFailure.PRICE_UNKNOWN -> "wall price could not be read"
        WallPhaseFailure.MULTIPLE_SELECTION -> "more walls were selected than planned"
        WallPhaseFailure.WRONG_CURRENCY -> "the wall price was in the wrong currency"
        WallPhaseFailure.NOT_PAYABLE -> "the wall price was not payable"
        WallPhaseFailure.BALANCE_UNREAD -> "balance could not be read"
        WallPhaseFailure.NOTHING_PLANNED -> "no wall purchase could be planned"
        WallPhaseFailure.SELECTION_REFUSED -> "wall selection was refused"
        WallPhaseFailure.CONFIRMATION_REFUSED -> "wall confirmation was refused"
        WallPhaseFailure.MARKER_WRITE_FAILED -> "purchase marker could not be saved"
        WallPhaseFailure.PAUSED -> "the run was paused"
        WallPhaseFailure.ERROR -> "wall phase error"
    }

    fun selectCurrency(
        balances: Map<Resource, Long>,
        nowMs: Long = System.currentTimeMillis(),
        exclude: Set<Resource> = emptySet()
    ): Resource? {
        if (!wallsEnabled || isSupervised || accountId == null) return null
        val eligible = wallCurrencies(
            rules = rules,
            balances = balances,
            capacities = capacities,
            preference = currencyPreference,
            active = emptySet(),
            request = WallPhaseRequest.AUTOMATIC
        ).filter { it !in exclude && tracker.canRun(it, nowMs) }.toSet()

        return selectWallCurrency(eligible, balances, capacities)
    }

    /**
     * Why this account must end at a checkpoint even with storage room left, or null: an earlier
     * wall purchase was never proven, so no wall phase may run on it until a person acknowledges
     * the marker. Attacking spends nothing, so a combined run hands off to its next account.
     */
    fun pendingStopReason(): String? {
        if (!wallsEnabled || isSupervised || accountId == null) return null
        if (purchaseStore?.isPending(accountId) != true) return null
        return "An earlier wall purchase was never proven; check the village before acknowledging it"
    }

    /**
     * One automatic wall phase, or null when none may run, a pending purchase marker included.
     * The currency is picked at [clock]'s reading and the outcome recorded at a fresh one once the
     * phase is over, so a currency's retry window starts when its own phase ends.
     */
    suspend fun runAutomaticPhase(
        balances: Map<Resource, Long>,
        clock: () -> Long = System::currentTimeMillis,
        exclude: Set<Resource> = emptySet(),
        executePhase: (suspend (WallPhaseOrder) -> WallPhaseOutcome)? = null
    ): WallPhaseOutcome? {
        if (!wallsEnabled || isSupervised || accountId == null) return null
        if (purchaseStore?.isPending(accountId) == true) {
            AppLog.log("wall_phase_blocked_by_marker", "account" to accountId)
            return null
        }
        val chosen = selectCurrency(balances, clock(), exclude) ?: return null
        val balance = balances[chosen] ?: return null
        val capacity = capacities[chosen] ?: return null
        val order = WallPhaseOrder(
            accountId = accountId,
            mode = WallPhaseRequest.AUTOMATIC,
            currency = chosen,
            balance = balance,
            capacity = capacity,
            resumePercent = rules.resumePercent,
            reserve = 0L,
            maxWallsPerPhase = maxWallsPerPhase,
            maxWallLevel = maxWallLevel,
            cappedPrices = tracker.cappedPrices
        )

        AppLog.log("wall_checkpoint_triggered", "currency" to chosen.key, "balance" to balance, "capacity" to capacity)
        val outcome = executePhase?.invoke(order) ?: runner?.runPhase(order)
            ?: throw StopException("No wall runner or executePhase available")
        tracker.record(chosen, outcome, clock())
        return outcome
    }

    /**
     * The automatic wall phases one HOME checkpoint may run: at most one per currency.
     *
     * The first phase spends the currency [selectCurrency] prefers under the usual rules. A
     * phase that returns an outcome left the village at a confirmed HOME, so the balances are
     * then sampled again - spending changed them and the pre-purchase readings say nothing
     * about the other currency. While the fresh agreed readings still hold an eligible
     * currency it gets its one phase here too. A currency bought at this checkpoint is never
     * run twice; a failed phase, a stopped run, a pending purchase or samples that will not
     * agree ends the checkpoint where it stands.
     *
     * One exception: a failed phase while gold and elixir both read full. It spent nothing, so
     * the readings still hold, and the next attack's loot has nowhere to go; the other currency
     * gets its phase now instead of one wasted attack later. A paused one still ends it.
     *
     * Returns the freshest samples taken, so the caller's storage-full check measures what the
     * purchases left behind rather than the readings that triggered them, or null when no
     * phase completed and the caller's samples are still the freshest there are.
     */
    suspend fun runCheckpointPhases(
        balances: Map<Resource, Long>,
        takeSamples: suspend () -> List<ResourceSample>,
        isStopped: () -> Boolean = { false },
        clock: () -> Long = System::currentTimeMillis,
        executePhase: (suspend (WallPhaseOrder) -> WallPhaseOutcome)? = null
    ): List<ResourceSample>? {
        var current = balances
        var fresh: List<ResourceSample>? = null
        // A bought currency can still read over its trigger; it gets no second phase here.
        // One that came back empty, capped or failed is already barred by the tracker.
        val spent = mutableSetOf<Resource>()
        while (!isStopped()) {
            val outcome = runAutomaticPhase(current, clock, exclude = spent, executePhase = executePhase)
                ?: break
            if (outcome is WallPhaseOutcome.Failed) {
                val bothFull = storageFull(Resource.GOLD, current, capacities) &&
                    storageFull(Resource.ELIXIR, current, capacities)
                if (!bothFull || outcome.reason == WallPhaseFailure.PAUSED) break
                // The tracker now bars the failed currency, so the loop cannot pick it again.
                continue
            }
            if (outcome == WallPhaseOutcome.NoBuilder || outcome == WallPhaseOutcome.BuildersBusy ||
                outcome == WallPhaseOutcome.NoWalls) break
            if (outcome is WallPhaseOutcome.Bought) spent += outcome.currency
            fresh = takeSamples()
            current = agreedHomeBalances(fresh, capacities)
        }
        return fresh
    }

    suspend fun runManualWalls(
        balancesProvider: suspend () -> Map<Resource, Long>,
        deadlineMs: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
        awaitResume: suspend () -> Unit = {},
        executePhase: (suspend (WallPhaseOrder) -> WallPhaseOutcome)? = null
    ): Int {
        if (accountId == null) throw StopException("Wall upgrades require an account ID")
        val store = purchaseStore ?: throw StopException("Wall upgrades require a purchase store")
        if (store.isPending(accountId)) {
            AppLog.log("wall_phase_blocked_by_marker", "account" to accountId)
            throw StopException("An earlier wall purchase was never proven; acknowledge it after checking the village")
        }

        var totalBought = 0
        val manualRules = WallStorageRules(100, 0)
        while (!isStopped() && System.currentTimeMillis() < deadlineMs && totalBought < maxWallsPerPhase) {
            awaitResume()
            if (isStopped()) break
            val balances = balancesProvider()
            val remainingWalls = maxWallsPerPhase - totalBought

            val spendable = wallCurrencies(
                rules = manualRules,
                balances = balances,
                capacities = capacities,
                preference = currencyPreference,
                active = emptySet(),
                request = WallPhaseRequest.MANUAL
            ).filter { currency ->
                val bal = balances[currency] ?: 0L
                bal > 0L && tracker.canRun(currency, System.currentTimeMillis())
            }.toSet()

            if (spendable.isEmpty()) {
                val unread = wallBalancesUnread(balances, currencyPreference)
                AppLog.log("manual_walls_stopped_nothing_to_spend",
                    "reason" to if (unread) "village balances could not be read" else "no currency left to spend",
                    "unread" to unread,
                    "balances" to balances.entries.joinToString(",") { "${it.key.key}=${it.value}" })
                break
            }

            val chosen = selectWallCurrency(spendable, balances, capacities) ?: break
            val balance = balances[chosen] ?: break
            val capacity = capacities[chosen] ?: balance

            val order = WallPhaseOrder(
                accountId = accountId,
                mode = WallPhaseRequest.MANUAL,
                currency = chosen,
                balance = balance,
                capacity = capacity,
                resumePercent = 0,
                reserve = 0L,
                maxWallsPerPhase = remainingWalls,
                maxWallLevel = maxWallLevel,
                cappedPrices = tracker.cappedPrices
            )

            val outcome = executePhase?.invoke(order) ?: runner?.runPhase(order)
                ?: throw StopException("No wall runner or executePhase available")

            tracker.record(chosen, outcome, System.currentTimeMillis())

            when (outcome) {
                is WallPhaseOutcome.Bought -> totalBought += outcome.count
                is WallPhaseOutcome.Empty -> continue
                is WallPhaseOutcome.Capped -> continue
                // The list has no Wall row, so the other currency has nothing to buy either.
                WallPhaseOutcome.NoWalls -> break
                // Every wall upgrade needs a builder, so neither currency can buy one now.
                WallPhaseOutcome.NoBuilder, WallPhaseOutcome.BuildersBusy -> {
                    AppLog.log("manual_walls_stopped_no_builder")
                    break
                }
                is WallPhaseOutcome.Failed -> {
                    // Failure must not spin! Break out of manual loop immediately
                    AppLog.log("manual_walls_stopped_on_failure", "reason" to outcome.reason.name, "detail" to outcome.detail)
                    break
                }
            }
        }
        return totalBought
    }

    companion object {
        /**
         * The storage-full reason after a phase in this run found no Wall row: "Storage full;
         * every wall is maxed". The inbox matches it and does not tell the user to free a builder
         * or turn walls on. A run that never read the list (walls off, no free builder) cannot
         * know, and an earlier storage-full entry the same day keeps the inbox slot.
         */
        const val ALL_WALLS_MAXED = "every wall is maxed"

        fun selectWallCurrency(
            eligible: Set<Resource>,
            balances: Map<Resource, Long>,
            capacities: Map<Resource, Long>
        ): Resource? {
            if (eligible.isEmpty()) return null
            if (eligible.size == 1) return eligible.first()
            if (!eligible.contains(Resource.GOLD)) return eligible.first()
            if (!eligible.contains(Resource.ELIXIR)) return Resource.GOLD

            val goldBal = (balances[Resource.GOLD] ?: 0L).toBigInteger()
            val goldCap = (capacities[Resource.GOLD] ?: 1L).toBigInteger()
            val elixirBal = (balances[Resource.ELIXIR] ?: 0L).toBigInteger()
            val elixirCap = (capacities[Resource.ELIXIR] ?: 1L).toBigInteger()

            return if (elixirBal * goldCap > goldBal * elixirCap) {
                Resource.ELIXIR
            } else {
                Resource.GOLD
            }
        }
    }
}
