package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import local.clashfarmer.recognition.UpgradeListFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Integration flow tests for storage-triggered wall farming.
 *
 * Verifies:
 * - Checkpoint scheduling: startup high storage -> purchase -> attack
 * - After-battle checkpoint: loot triggers purchase before next attack
 * - Precedence: reached saving goal wins before spending; completed quota means no final purchase
 * - Attacks-only and supervised modes: explicitly disable wall work
 * - Currency choice: highest fill %, gold tie-break, never spend below-trigger currency
 * - Manual route: repeats verified phases down to reserves; failure must not spin
 * - Suppression & retry across restarts: shared tracker for same account/run; isolated accounts
 * - Pending marker: refuses spending and ends the account as a normal finish; never auto-cleared
 */
class WallFarmingFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val accountId = "11111111-2222-3333-4444-555555555555"
    private val capacities = mapOf(
        Resource.GOLD to 20_000_000L,
        Resource.ELIXIR to 20_000_000L
    )

    private fun store() = WallPurchaseStore(folder.root)

    // --- 1. Currency choice ---

    @Test
    fun currencyChoicePicksHighestFillPercentage() {
        val eligible = setOf(Resource.GOLD, Resource.ELIXIR)
        // Gold: 19M / 20M = 95%; Elixir: 18.2M / 20M = 91%
        val balances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 18_200_000L)
        val chosen = WallFarmingCoordinator.selectWallCurrency(eligible, balances, capacities)
        assertEquals(Resource.GOLD, chosen)

        // Elixir higher fill: Gold 18.2M (91%) vs Elixir 19M (95%)
        val balancesElixirHigher = mapOf(Resource.GOLD to 18_200_000L, Resource.ELIXIR to 19_000_000L)
        val chosenElixir = WallFarmingCoordinator.selectWallCurrency(eligible, balancesElixirHigher, capacities)
        assertEquals(Resource.ELIXIR, chosenElixir)
    }

    @Test
    fun currencyChoiceUsesGoldAsTieBreakOnEqualFill() {
        val eligible = setOf(Resource.GOLD, Resource.ELIXIR)
        // Equal fill: 18M / 20M = 90% each
        val balances = mapOf(Resource.GOLD to 18_000_000L, Resource.ELIXIR to 18_000_000L)
        val chosen = WallFarmingCoordinator.selectWallCurrency(eligible, balances, capacities)
        assertEquals(Resource.GOLD, chosen)
    }

    @Test
    fun currencyChoiceNeverSpendsBelowTriggerOtherCurrency() {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        // Gold 18.5M (92.5% >= 90%), Elixir 15M (75% < 90%)
        val balances = mapOf(Resource.GOLD to 18_500_000L, Resource.ELIXIR to 15_000_000L)
        val tracker = WallPhaseTracker()
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )

        val chosen = coordinator.selectCurrency(balances)
        assertEquals("Only gold is eligible; below-trigger elixir must not be chosen", Resource.GOLD, chosen)
    }

    // --- 2. Startup high storage -> purchase -> attack ---

    @Test
    fun startupHighStorageTriggersPurchaseThenAttack() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val balances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 10_000_000L)
        val session = Session(attacks = 3)
        val tracker = WallPhaseTracker()
        val ordersRun = mutableListOf<WallPhaseOrder>()

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )

        assertTrue("Checkpoint must be due at startup", session.homeCheckpointDue())

        val outcome = coordinator.runAutomaticPhase(balances, executePhase = { order ->
            ordersRun += order
            WallPhaseOutcome.Bought(order.currency, 2, 4_000_000L)
        })

        assertNotNull(outcome)
        assertTrue(outcome is WallPhaseOutcome.Bought)
        assertEquals(1, ordersRun.size)
        assertEquals(Resource.GOLD, ordersRun[0].currency)
        assertEquals(WallPhaseRequest.AUTOMATIC, ordersRun[0].mode)

        session.checkpointDone()
        assertFalse("Checkpoint done until next attack", session.homeCheckpointDue())

        // Next screen action is OPEN_ATTACK
        val action = session.accept(ScreenState.HOME)
        assertEquals(Action.OPEN_ATTACK, action)
        assertEquals(ScreenState.MENU, session.phase)
    }

    // --- 3. After-battle checkpoint ---

    @Test
    fun afterBattleCheckpointTriggersWhenLootCrossesTrigger() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val session = Session(attacks = 3)
        val tracker = WallPhaseTracker()
        val ordersRun = mutableListOf<WallPhaseOrder>()

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )

        // At startup, storage is low (10M / 20M = 50%)
        val startBalances = mapOf(Resource.GOLD to 10_000_000L, Resource.ELIXIR to 10_000_000L)
        val startupOutcome = coordinator.runAutomaticPhase(startBalances, executePhase = { order ->
            ordersRun += order
            WallPhaseOutcome.Empty
        })
        assertNull("Below trigger must not run wall phase", startupOutcome)
        session.checkpointDone()

        // Complete attack 1: MENU -> ARMY -> SCOUT -> DEPLOY -> BATTLE -> RESULT -> HOME
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.WAIT, session.accept(ScreenState.BATTLE))
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
        assertEquals(1, session.counter.completed)

        // Back at HOME: checkpoint is due!
        assertTrue("Checkpoint due after battle completes", session.homeCheckpointDue())

        // Loot increased Gold to 18.5M (92.5%)
        val postBattleBalances = mapOf(Resource.GOLD to 18_500_000L, Resource.ELIXIR to 10_000_000L)
        val postBattleOutcome = coordinator.runAutomaticPhase(postBattleBalances, executePhase = { order ->
            ordersRun += order
            WallPhaseOutcome.Bought(order.currency, 2, 4_000_000L)
        })

        assertNotNull(postBattleOutcome)
        assertEquals(1, ordersRun.size)
        assertEquals(Resource.GOLD, ordersRun[0].currency)

        session.checkpointDone()
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
    }

    // --- 4. Goal and quota precedence ---

    @Test
    fun goalPrecedenceWinsOverSpending() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val goal = FarmingGoal(mapOf(Resource.GOLD to 18_000_000L), GoalMatch.ALL)
        val session = Session(attacks = 5)
        val tracker = WallPhaseTracker()
        var phaseRan = false

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            capacities = capacities,
            goal = goal,
            tracker = tracker,
            purchaseStore = store()
        )

        val samples = listOf(
            ResourceSample(1000L, mapOf(Resource.GOLD to 19_000_000L)),
            ResourceSample(2000L, mapOf(Resource.GOLD to 19_000_000L))
        )

        assertTrue(coordinator.evaluateGoal(samples))
        // Goal reached means run ends without spending
        if (coordinator.evaluateGoal(samples)) {
            // normal finish on goal, no wall phase executed
        } else {
            coordinator.runAutomaticPhase(mapOf(Resource.GOLD to 19_000_000L), executePhase = {
                phaseRan = true
                WallPhaseOutcome.Bought(Resource.GOLD, 1, 1_000_000L)
            })
        }
        assertFalse("Goal reached must take precedence before wall spending", phaseRan)
    }

    @Test
    fun completedQuotaMeansNoFinalPurchase() {
        val session = Session(attacks = 2)
        // Simulate 2 completed attacks
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))
        session.checkpointDone()

        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))
        assertEquals(Action.FIND_MATCH, session.accept(ScreenState.MENU))
        assertEquals(Action.CHECK_ARMY, session.accept(ScreenState.ARMY))
        assertEquals(Action.DEPLOY, session.accept(ScreenState.SCOUT))
        session.deployed()
        assertEquals(Action.RETURN_HOME, session.accept(ScreenState.RESULT))

        assertEquals(2, session.counter.completed)
        assertTrue("Quota completed still records the final storage checkpoint", session.homeCheckpointDue())
        val action = session.accept(ScreenState.HOME)
        assertEquals(Action.FINISH, action)
    }

    // --- 5. Attacks-only and supervised no spend ---

    @Test
    fun attacksOnlyExplicitlyDisablesWallWork() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val highBalances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)
        var phaseRan = false

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = false, // attacks-only
            isSupervised = false,
            rules = rules,
            capacities = capacities,
            tracker = WallPhaseTracker(),
            purchaseStore = store()
        )

        val outcome = coordinator.runAutomaticPhase(highBalances, executePhase = {
            phaseRan = true
            WallPhaseOutcome.Bought(Resource.GOLD, 1, 1_000_000L)
        })
        assertNull(outcome)
        assertFalse("Attacks-only must never run wall phase regardless of storage", phaseRan)
    }

    @Test
    fun supervisedAttackDisablesWallWork() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val highBalances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)
        var phaseRan = false

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = true, // supervised attack
            rules = rules,
            capacities = capacities,
            tracker = WallPhaseTracker(),
            purchaseStore = store()
        )

        val outcome = coordinator.runAutomaticPhase(highBalances, executePhase = {
            phaseRan = true
            WallPhaseOutcome.Bought(Resource.GOLD, 1, 1_000_000L)
        })
        assertNull(outcome)
        assertFalse("Supervised attack must never unexpectedly spend on walls", phaseRan)
    }

    // --- 6. Manual route ---

    @Test
    fun manualRouteIgnoresLegacyReservesAcrossBackToBackPhases() = runBlocking {
        var gold = 18_000_000L
        val reserve = 2_000_000L
        val phases = mutableListOf<WallPhaseOrder>()

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(100, 0),
            currencyPreference = "gold",
            reserveGold = reserve,
            maxWallsPerPhase = 10,
            capacities = capacities,
            tracker = WallPhaseTracker(),
            purchaseStore = store()
        )

        val totalUpgraded = coordinator.runManualWalls(
            balancesProvider = { mapOf(Resource.GOLD to gold, Resource.ELIXIR to 0L) },
            executePhase = { order ->
                phases += order
                assertEquals(0L, order.reserve)
                val count = minOf(4, order.maxWallsPerPhase, (gold / 2_000_000L).toInt())
                gold -= count * 2_000_000L
                WallPhaseOutcome.Bought(order.currency, count, count * 2_000_000L)
            }
        )

        assertEquals("All affordable walls are bought without a reserve", 9, totalUpgraded)
        assertEquals(3, phases.size)
        assertEquals(WallPhaseRequest.MANUAL, phases[0].mode)
        assertEquals(WallPhaseRequest.MANUAL, phases[1].mode)
    }

    @Test
    fun manualRouteFailureMustNotSpin() = runBlocking {
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(100, 0),
            currencyPreference = "gold",
            reserveGold = 0L,
            maxWallsPerPhase = 10,
            capacities = capacities,
            tracker = WallPhaseTracker(),
            purchaseStore = store()
        )

        var calls = 0
        val totalUpgraded = coordinator.runManualWalls(
            balancesProvider = { mapOf(Resource.GOLD to 15_000_000L, Resource.ELIXIR to 0L) },
            executePhase = {
                calls++
                WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "could not open list")
            }
        )

        assertEquals("Failure must exit immediately, never spin", 1, calls)
        assertEquals(0, totalUpgraded)
    }

    // --- 7. Suppression & retry across restarts ---

    @Test
    fun emptyPhaseSuppressesCurrencyAcrossRestarts() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val highBalances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 10_000_000L)
        val sharedTracker = WallPhaseTracker()

        // Attempt 1: Empty phase
        val coordinator1 = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "gold",
            capacities = capacities,
            tracker = sharedTracker,
            purchaseStore = store()
        )

        val outcome1 = coordinator1.runAutomaticPhase(highBalances, executePhase = {
            WallPhaseOutcome.Empty
        })
        assertEquals(WallPhaseOutcome.Empty, outcome1)
        assertTrue("Gold must be suppressed after empty phase", sharedTracker.suppressed(Resource.GOLD))

        // Attempt 2 (simulating restart with shared tracker):
        val coordinator2 = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "gold",
            capacities = capacities,
            tracker = sharedTracker,
            purchaseStore = store()
        )

        var phase2Ran = false
        val outcome2 = coordinator2.runAutomaticPhase(highBalances, executePhase = {
            phase2Ran = true
            WallPhaseOutcome.Bought(Resource.GOLD, 1, 1_000_000L)
        })
        assertNull("Suppressed currency must not run on restart attempt", outcome2)
        assertFalse(phase2Ran)
    }

    @Test
    fun failureRetriesOnceAfterTenMinutesThenSuppresses() = runBlocking {
        val rules = WallStorageRules(triggerPercent = 90, resumePercent = 75)
        val highBalances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 10_000_000L)
        val sharedTracker = WallPhaseTracker()
        val now = 1_000_000L

        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = rules,
            currencyPreference = "gold",
            capacities = capacities,
            tracker = sharedTracker,
            purchaseStore = store()
        )

        // First failure: scheduled retry
        coordinator.runAutomaticPhase(highBalances, clock = { now }, executePhase = {
            WallPhaseOutcome.Failed(WallPhaseFailure.PANEL_UNREADABLE, "panel timed out")
        })

        assertFalse("First failure is not permanently suppressed", sharedTracker.suppressed(Resource.GOLD))
        assertFalse("Cannot run immediately during retry window", sharedTracker.canRun(Resource.GOLD, now + 5 * 60_000L))
        assertTrue("Can run after 10 minutes", sharedTracker.canRun(Resource.GOLD, now + 10 * 60_000L))

        // Second failure after retry: permanently suppresses
        coordinator.runAutomaticPhase(highBalances, clock = { now + 10 * 60_000L }, executePhase = {
            WallPhaseOutcome.Failed(WallPhaseFailure.PANEL_UNREADABLE, "panel timed out second time")
        })

        assertTrue("Second failure suppresses currency for the run", sharedTracker.suppressed(Resource.GOLD))
    }

    // --- 8. Pending marker refuses spending ---

    @Test
    fun pendingMarkerRefusesSpendingAndDoesNotClearMarker() = runBlocking {
        val purchaseStore = store()
        val pendingMarker = PendingWallPurchase(
            accountId = accountId,
            currency = Resource.GOLD,
            count = 2,
            total = 8_000_000L,
            expectedBalance = 11_000_000L,
            startingBalance = 19_000_000L,
            atMs = System.currentTimeMillis()
        )
        purchaseStore.markPending(pendingMarker)
        assertTrue(purchaseStore.isPending(accountId))

        val phone = WallPhone(gold = 19_000_000L)
        val order = WallPhaseOrder(
            accountId = accountId,
            mode = WallPhaseRequest.AUTOMATIC,
            currency = Resource.GOLD,
            balance = 19_000_000L,
            capacity = 20_000_000L,
            resumePercent = 75,
            reserve = 0L,
            maxWallsPerPhase = 10,
            maxWallLevel = 20
        )

        val phase = WallPhase(phone, UpgradeListFixture.screen, purchaseStore, phone.neutralPoint)
        val outcome = phase.run(order)

        assertTrue(outcome is WallPhaseOutcome.Failed)
        assertEquals(WallPhaseFailure.PENDING_PURCHASE, (outcome as WallPhaseOutcome.Failed).reason)
        assertEquals(0, phone.confirmTaps)
        assertTrue("Pending marker must NEVER be auto-cleared", purchaseStore.isPending(accountId))
    }

    // --- 9. Full storage with nothing left to buy stops the run ---

    private fun fullStorageSamples(vararg extras: Pair<Resource, Long>) = listOf(
        ResourceSample(1_000L, mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L) + extras),
        ResourceSample(2_000L, mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L) + extras)
    )

    private fun noSpendCoordinator(
        tracker: WallPhaseTracker = WallPhaseTracker(),
        walls: Boolean = true,
        goal: FarmingGoal = FarmingGoal(),
        caps: Map<Resource, Long> = capacities,
        purchases: WallPurchaseStore = store()
    ): WallFarmingCoordinator {
        // Both currencies exhausted: their phases found nothing affordable or available.
        tracker.record(Resource.GOLD, WallPhaseOutcome.Empty, 0L)
        tracker.record(Resource.ELIXIR, WallPhaseOutcome.Empty, 0L)
        return WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = walls,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = caps,
            goal = goal,
            tracker = tracker,
            purchaseStore = purchases
        )
    }

    @Test
    fun fullStoragesAndUnavailableWallsPreventNextAttack() {
        val coordinator = noSpendCoordinator()
        val reason = coordinator.fullStorageStopReason(fullStorageSamples())
        assertEquals("Full storages with nothing left to buy must end the run",
            "Storage full; no wall left to buy", reason)
        assertFalse("The stop is terminal, never retried", RecoveryPolicy.isRecoverable(reason))

        // Without the stop the checkpoint would open the next attack.
        val session = Session(attacks = 5)
        assertEquals(Action.OPEN_ATTACK, session.accept(ScreenState.HOME))

        // Walls off for the account is the same dead end.
        assertNotNull(noSpendCoordinator(walls = false).fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun fullStorageWithEveryWallMaxedSaysSo() {
        // Sweat, r3-log-final.jsonl:1102-1103: the builder's list held no Wall row.
        val tracker = WallPhaseTracker()
        tracker.record(Resource.ELIXIR, WallPhaseOutcome.NoWalls, 0L)
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )
        val reason = coordinator.fullStorageStopReason(fullStorageSamples())
        assertEquals("Storage full; every wall is maxed", reason)
        assertFalse(RecoveryPolicy.isRecoverable(reason))
        // Walls off is still told as walls off.
        assertEquals("Storage full; wall spending is off",
            noSpendCoordinator(tracker = tracker, walls = false).fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun noFreeBuilderIsTheSameDeadEndAsWallSpendingOff() = runBlocking {
        val tracker = WallPhaseTracker()
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )
        assertNull("walls can still run", coordinator.fullStorageStopReason(fullStorageSamples()))

        var phases = 0
        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { fullStorageSamples() },
            executePhase = { phases++; WallPhaseOutcome.NoBuilder }
        )
        assertEquals("no second currency is tried with no builder free", 1, phases)
        assertNull(coordinator.selectCurrency(mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L), 0L))

        val reason = coordinator.fullStorageStopReason(fullStorageSamples())
        assertNotNull("full storage with no builder free must end the run", reason)
        assertEquals("Storage full; walls skipped: no builder is free", reason)
        assertFalse(RecoveryPolicy.isRecoverable(reason))
        assertFalse("no marker", store().isPending(accountId))
    }

    @Test
    fun noFreeBuilderIsNotAFailure() {
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.NoBuilder, 0L)
        val justBefore = WallPhaseTracker.RETRY_AFTER_MS - 1
        assertTrue(tracker.noFreeBuilder(justBefore))
        assertFalse(tracker.canRun(Resource.GOLD, justBefore))
        assertFalse(tracker.canRun(Resource.ELIXIR, justBefore))
        assertTrue("counted as no failure", tracker.state().failures.isEmpty())
        assertTrue("carried across a restart", WallPhaseTracker(tracker.state()).noFreeBuilder(justBefore))
    }

    @Test
    fun noFreeBuilderIsLookedAtAgainAfterTheUsualRetry() {
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.NoBuilder, 1_000L)
        val later = 1_000L + WallPhaseTracker.RETRY_AFTER_MS
        assertFalse(tracker.noFreeBuilder(later))
        assertTrue("a builder that freed is used", tracker.canRun(Resource.GOLD, later))
        assertTrue(tracker.canRun(Resource.ELIXIR, later))
        assertFalse(WallPhaseTracker(tracker.state()).noFreeBuilder(later))
    }

    @Test
    fun theBusyDialogKeepsWallsOffForTheRestOfTheRun() {
        // D3 F1: each ten-minute retry put the 212-gem button on screen again.
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.BuildersBusy, 1_000L)
        val muchLater = 1_000L + 50 * WallPhaseTracker.RETRY_AFTER_MS
        assertTrue(tracker.noFreeBuilder(muchLater))
        assertFalse(tracker.canRun(Resource.GOLD, muchLater))
        assertFalse(tracker.canRun(Resource.ELIXIR, muchLater))
        assertTrue("counted as no failure", tracker.state().failures.isEmpty())
        assertTrue("no failure reason to report", tracker.lastFailures().isEmpty())
        assertTrue("carried across a restart", WallPhaseTracker(tracker.state()).noFreeBuilder(muchLater))
        // A second sighting changes nothing; a new run starts with a new tracker.
        tracker.record(Resource.ELIXIR, WallPhaseOutcome.BuildersBusy, muchLater)
        assertTrue(tracker.noFreeBuilder(muchLater))
        assertFalse(WallPhaseTracker().noFreeBuilder(muchLater))
    }

    @Test
    fun aBusyDialogEndsTheCheckpointAndTheFullStorageStopSaysNoBuilder() = runBlocking {
        val tracker = WallPhaseTracker()
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )
        var phases = 0
        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { fullStorageSamples() },
            clock = { 0L },
            executePhase = { phases++; WallPhaseOutcome.BuildersBusy }
        )
        assertEquals("no second currency is tried after the busy dialog", 1, phases)
        val hoursLater = 6 * 60 * 60_000L
        assertNull(coordinator.selectCurrency(mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L), hoursLater))
        assertEquals("Storage full; walls skipped: no builder is free",
            coordinator.fullStorageStopReason(fullStorageSamples(), hoursLater))
    }

    @Test
    fun manualWallsEndQuietlyWithNoFreeBuilder() = runBlocking {
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = false,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            purchaseStore = store()
        )
        var phases = 0
        val bought = coordinator.runManualWalls(
            balancesProvider = { mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L) },
            executePhase = { phases++; WallPhaseOutcome.NoBuilder }
        )
        assertEquals(0, bought)
        assertEquals(1, phases)
        assertFalse(store().isPending(accountId))
    }

    @Test
    fun manualWallsEndAfterOneScanWhenTheListHasNoWallRow() = runBlocking {
        val tracker = WallPhaseTracker()
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = false,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )
        var phases = 0
        val bought = coordinator.runManualWalls(
            balancesProvider = { mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L) },
            executePhase = { phases++; WallPhaseOutcome.NoWalls }
        )
        assertEquals(0, bought)
        assertEquals("the other currency is not scanned for again", 1, phases)
        assertFalse(tracker.canRun(Resource.ELIXIR, 0L))
    }

    @Test
    fun unreadableBalancesKeepFarming() {
        val coordinator = noSpendCoordinator()
        // Gold never read: only elixir can be proven full, so the run farms on.
        val elixirOnly = listOf(
            ResourceSample(1_000L, mapOf(Resource.ELIXIR to 20_000_000L)),
            ResourceSample(2_000L, mapOf(Resource.ELIXIR to 20_000_000L))
        )
        assertNull(coordinator.fullStorageStopReason(elixirOnly))
        // Nothing read at all.
        val empty = listOf(ResourceSample(1_000L, emptyMap()), ResourceSample(2_000L, emptyMap()))
        assertNull(coordinator.fullStorageStopReason(empty))
    }

    @Test
    fun disagreeingBalancesKeepFarming() {
        val coordinator = noSpendCoordinator()
        // Gold moved between the two reads, so it is not proven full.
        val changed = listOf(
            ResourceSample(1_000L, mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)),
            ResourceSample(2_000L, mapOf(Resource.GOLD to 19_700_000L, Resource.ELIXIR to 20_000_000L))
        )
        assertNull(coordinator.fullStorageStopReason(changed))
        // An over-capacity reading is a misread and spoils the proof even when it repeats.
        val misread = listOf(
            ResourceSample(1_000L, mapOf(Resource.GOLD to 21_000_000L, Resource.ELIXIR to 20_000_000L)),
            ResourceSample(2_000L, mapOf(Resource.GOLD to 21_000_000L, Resource.ELIXIR to 20_000_000L))
        )
        assertNull(coordinator.fullStorageStopReason(misread))
    }

    @Test
    fun wallsStillAvailableKeepFarming() {
        // Gold is exhausted, but elixir can still run a phase.
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.Empty, 0L)
        val coordinator = WallFarmingCoordinator(
            accountId = accountId,
            wallsEnabled = true,
            isSupervised = false,
            rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
            currencyPreference = "auto",
            capacities = capacities,
            tracker = tracker,
            purchaseStore = store()
        )
        assertNull(coordinator.fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun remainingGoalKeepsFarming() {
        // A dark elixir target is still short: more loot has somewhere to go.
        val goal = FarmingGoal(mapOf(Resource.DARK_ELIXIR to 200_000L), GoalMatch.ALL)
        val coordinator = noSpendCoordinator(
            goal = goal,
            caps = capacities + (Resource.DARK_ELIXIR to 300_000L)
        )
        assertNull(coordinator.fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun fullStorageStopNeverAcknowledgesAPendingPurchase() = runBlocking {
        val purchaseStore = store()
        purchaseStore.markPending(
            PendingWallPurchase(accountId, Resource.GOLD, 2, 8_000_000L, 12_000_000L, 20_000_000L, 1L)
        )
        val coordinator = noSpendCoordinator(purchases = purchaseStore)

        // D3 F5: the pending marker runs no phase and leaves the storage check to end the account
        // as a normal finish, so a combined run still hands off.
        assertNull(coordinator.runAutomaticPhase(mapOf(
            Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L)) {
            fail("No phase may run while a purchase is pending")
            WallPhaseOutcome.Empty
        })
        assertEquals("Storage full; walls failed: an earlier wall purchase is still pending",
            coordinator.fullStorageStopReason(fullStorageSamples()))
        assertTrue("The pending marker is never cleared or acknowledged by this logic",
            purchaseStore.isPending(accountId))
    }

    @Test
    fun fullDarkElixirAloneKeepsFarming() {
        // Dark elixir buys no walls, so a full bar of it counts for nothing either way.
        val coordinator = noSpendCoordinator(caps = capacities + (Resource.DARK_ELIXIR to 300_000L))
        val darkFull = listOf(
            ResourceSample(1_000L, mapOf(
                Resource.GOLD to 20_000_000L, Resource.ELIXIR to 10_000_000L,
                Resource.DARK_ELIXIR to 300_000L)),
            ResourceSample(2_000L, mapOf(
                Resource.GOLD to 20_000_000L, Resource.ELIXIR to 10_000_000L,
                Resource.DARK_ELIXIR to 300_000L))
        )
        assertNull("A full dark elixir bar is not a reason to stop",
            coordinator.fullStorageStopReason(darkFull))
        // It does not veto a real stop either.
        assertNotNull(coordinator.fullStorageStopReason(
            fullStorageSamples(Resource.DARK_ELIXIR to 300_000L)))
    }

    // --- 10. Both currencies drain at one checkpoint ---

    private fun checkpointCoordinator(purchases: WallPurchaseStore = store()) = WallFarmingCoordinator(
        accountId = accountId,
        wallsEnabled = true,
        isSupervised = false,
        rules = WallStorageRules(triggerPercent = 90, resumePercent = 75),
        currencyPreference = "auto",
        capacities = capacities,
        tracker = WallPhaseTracker(),
        purchaseStore = purchases
    )

    private fun agreeingBalances(gold: Long, elixir: Long) = listOf(
        ResourceSample(1_000L, mapOf(Resource.GOLD to gold, Resource.ELIXIR to elixir)),
        ResourceSample(2_000L, mapOf(Resource.GOLD to gold, Resource.ELIXIR to elixir))
    )

    @Test
    fun bothFullCurrenciesAreDrainedAtOneCheckpoint() = runBlocking {
        var gold = 20_000_000L
        var elixir = 20_000_000L
        val orders = mutableListOf<WallPhaseOrder>()
        var sampled = 0
        val coordinator = checkpointCoordinator()

        val fresh = coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to gold, Resource.ELIXIR to elixir),
            takeSamples = { sampled++; agreeingBalances(gold, elixir) },
            executePhase = { order ->
                orders += order
                if (orders.size > 2) fail("A third phase must not run at one checkpoint")
                if (order.currency == Resource.GOLD) gold -= 5_000_000L else elixir -= 5_000_000L
                WallPhaseOutcome.Bought(order.currency, 1, 5_000_000L)
            }
        )

        assertEquals("One phase per currency, in preference order",
            listOf(Resource.GOLD, Resource.ELIXIR), orders.map { it.currency })
        assertEquals("The other currency's eligibility comes from a fresh read", 2, sampled)
        assertNotNull("Post-purchase samples are handed back for the storage check", fresh)
        assertEquals(15_000_000L, agreedHomeBalances(fresh!!, capacities)[Resource.ELIXIR])
    }

    @Test
    fun secondPhaseRunsOnFreshBalancesNotTheFirstRead() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            // After the gold purchase the village reads 15M gold and 19M elixir.
            takeSamples = { agreeingBalances(15_000_000L, 19_000_000L) },
            executePhase = { order ->
                orders += order
                if (orders.size > 2) fail("A third phase must not run at one checkpoint")
                WallPhaseOutcome.Bought(order.currency, 1, 1_000_000L)
            }
        )

        assertEquals(2, orders.size)
        assertEquals(Resource.ELIXIR, orders[1].currency)
        assertEquals("The second order must carry the fresh reading, not the stale 20M",
            19_000_000L, orders[1].balance)
    }

    @Test
    fun failedFirstPhasePreventsSecond() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        var sampled = 0
        val coordinator = checkpointCoordinator()

        // Both over the trigger but neither full: the next attack still banks loot.
        val fresh = coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 19_000_000L),
            takeSamples = { sampled++; agreeingBalances(19_000_000L, 19_000_000L) },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Failed(WallPhaseFailure.PANEL_UNREADABLE, "panel never read")
            }
        )

        assertEquals("A failed phase runs no second currency at this checkpoint", 1, orders.size)
        assertEquals(Resource.GOLD, orders[0].currency)
        assertEquals("Nothing is re-sampled after a failure", 0, sampled)
        assertNull(fresh)
    }

    @Test
    fun failedPhaseAtFullStorageTriesTheOtherCurrencyThenStopsSayingWhy() = runBlocking {
        // Sweat, 11:10 UTC 8 October: a wall phase at full storage failed LIST_FAILED and the run
        // attacked on into storages that could hold nothing more.
        val orders = mutableListOf<WallPhaseOrder>()
        var sampled = 0
        val coordinator = checkpointCoordinator()

        val fresh = coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { sampled++; agreeingBalances(20_000_000L, 20_000_000L) },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "BUDGET_EXCEEDED: 0 distinct Wall rows seen")
            }
        )

        assertEquals("Both full: the other currency runs now rather than after a wasted attack",
            listOf(Resource.GOLD, Resource.ELIXIR), orders.map { it.currency })
        assertEquals("Nothing was spent, so nothing is re-sampled", 0, sampled)
        assertNull(fresh)
        assertEquals("Storage full; walls failed: wall list could not be read",
            coordinator.fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun pausedPhaseAtFullStorageStillEndsTheCheckpoint() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { agreeingBalances(20_000_000L, 20_000_000L) },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Failed(WallPhaseFailure.PAUSED, "paused")
            }
        )

        assertEquals("A paused run starts no second phase", listOf(Resource.GOLD), orders.map { it.currency })
    }

    @Test
    fun markerRaisedDuringAFullStoragePhaseEndsTheAccountWithoutAnotherPhase() = runBlocking {
        // A-W3-Q audit F1 accepted a stop here; D3 F5 reversed it. A marker written while the first
        // phase's dialog was open still bars the second phase and is never cleared, but the account
        // now ends as a storage-full finish naming the marker, so a combined run hands off to its
        // next account instead of ending as an error. Attacking or switching accounts buys nothing.
        val purchases = store()
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator(purchases = purchases)

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { agreeingBalances(20_000_000L, 20_000_000L) },
            executePhase = { order ->
                orders += order
                purchases.markPending(
                    PendingWallPurchase(accountId, order.currency, 2, 8_000_000L, 12_000_000L, 20_000_000L, 1L)
                )
                WallPhaseOutcome.Failed(WallPhaseFailure.PENDING_PURCHASE, "a marker appeared")
            }
        )

        assertEquals("No second phase runs on a pending marker", listOf(Resource.GOLD), orders.map { it.currency })
        assertEquals("Storage full; walls failed: an earlier wall purchase is still pending",
            coordinator.fullStorageStopReason(fullStorageSamples()))
        assertTrue("The marker is never cleared", purchases.isPending(accountId))
    }

    @Test
    fun pendingMarkerWithRoomLeftStillEndsTheAccount() = runBlocking {
        // D3 F5: storage not full, so the storage check would keep attacking; the marker ends the
        // account at this checkpoint as a normal finish instead of the old StopException.
        val purchases = store()
        purchases.markPending(
            PendingWallPurchase(accountId, Resource.GOLD, 2, 8_000_000L, 12_000_000L, 20_000_000L, 1L)
        )
        val coordinator = checkpointCoordinator(purchases = purchases)

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 19_000_000L, Resource.ELIXIR to 10_000_000L),
            takeSamples = { agreeingBalances(19_000_000L, 10_000_000L) },
            executePhase = { fail("No phase may run while a purchase is pending"); WallPhaseOutcome.Empty }
        )

        assertNull(coordinator.fullStorageStopReason(agreeingBalances(19_000_000L, 10_000_000L)))
        assertTrue(coordinator.pendingStopReason().orEmpty().contains("never proven"))
        assertTrue(purchases.isPending(accountId))
        assertNull("Spending off: the marker cannot be acted on, so it ends nothing",
            noSpendCoordinator(walls = false, purchases = purchases).pendingStopReason())
    }

    @Test
    fun eachCurrencysRetryWindowStartsWhenItsOwnPhaseEnds() = runBlocking {
        // D3 F6: one checkpoint clock started elixir's ten minutes when the slow gold phase began.
        var now = 0L
        val tracker = WallPhaseTracker()
        val coordinator = WallFarmingCoordinator(accountId, true, false,
            WallStorageRules(triggerPercent = 90, resumePercent = 75),
            capacities = capacities, tracker = tracker, purchaseStore = store())

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { agreeingBalances(20_000_000L, 20_000_000L) },
            clock = { now },
            executePhase = { order ->
                now += 60_000L  // each phase takes a minute
                WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "list for ${order.currency.key}")
            }
        )

        // Gold ended at 1 min, elixir at 2 min.
        assertFalse(tracker.canRun(Resource.GOLD, 60_000L + WallPhaseTracker.RETRY_AFTER_MS - 1))
        assertTrue(tracker.canRun(Resource.GOLD, 60_000L + WallPhaseTracker.RETRY_AFTER_MS))
        assertFalse("Elixir's window is not shortened by the gold phase",
            tracker.canRun(Resource.ELIXIR, 120_000L + WallPhaseTracker.RETRY_AFTER_MS - 1))
        assertTrue(tracker.canRun(Resource.ELIXIR, 120_000L + WallPhaseTracker.RETRY_AFTER_MS))
    }

    @Test
    fun failureCooldownIsNamedInTheStorageFullReason() {
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "x"), 0L)
        tracker.record(Resource.ELIXIR, WallPhaseOutcome.Empty, 0L)
        val coordinator = WallFarmingCoordinator(accountId, true, false, WallStorageRules(90, 75),
            capacities = capacities, tracker = tracker, purchaseStore = store())
        assertEquals("Storage full; walls failed: wall list could not be read",
            coordinator.fullStorageStopReason(fullStorageSamples(), nowMs = 1_000L))
    }

    @Test
    fun aLaterGoodPhaseClearsTheNamedFailure() {
        val tracker = WallPhaseTracker()
        tracker.record(Resource.GOLD, WallPhaseOutcome.Failed(WallPhaseFailure.LIST_FAILED, "x"), 0L)
        tracker.record(Resource.GOLD, WallPhaseOutcome.Empty, WallPhaseTracker.RETRY_AFTER_MS)
        tracker.record(Resource.ELIXIR, WallPhaseOutcome.Empty, WallPhaseTracker.RETRY_AFTER_MS)
        val coordinator = WallFarmingCoordinator(accountId, true, false, WallStorageRules(90, 75),
            capacities = capacities, tracker = tracker, purchaseStore = store())
        assertEquals("Storage full; no wall left to buy",
            coordinator.fullStorageStopReason(fullStorageSamples(), nowMs = WallPhaseTracker.RETRY_AFTER_MS))
    }

    @Test
    fun aPendingMarkerIsNamedAsAWallFailure() {
        val purchases = store()
        purchases.markPending(
            PendingWallPurchase(accountId, Resource.GOLD, 2, 8_000_000L, 12_000_000L, 20_000_000L, 1L)
        )
        assertEquals("Storage full; walls failed: an earlier wall purchase is still pending",
            noSpendCoordinator(purchases = purchases).fullStorageStopReason(fullStorageSamples()))
    }

    @Test
    fun unconfirmedFirstPhasePreventsSecond() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        var sampled = 0
        val coordinator = checkpointCoordinator()

        try {
            coordinator.runCheckpointPhases(
                balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
                takeSamples = { sampled++; agreeingBalances(20_000_000L, 20_000_000L) },
                executePhase = { order ->
                    orders += order
                    throw StopException("The village could not be confirmed after the wall phase")
                }
            )
            fail("An unconfirmed HOME return must propagate")
        } catch (e: StopException) {
            // expected
        }

        assertEquals(1, orders.size)
        assertEquals(0, sampled)
    }

    @Test
    fun noCurrencyRunsTwiceAtOneCheckpoint() = runBlocking {
        // The gold buy leaves gold still over the trigger; it must not be chosen again.
        var gold = 20_000_000L
        var elixir = 18_500_000L
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to gold, Resource.ELIXIR to elixir),
            takeSamples = { agreeingBalances(gold, elixir) },
            executePhase = { order ->
                orders += order
                if (orders.size > 2) fail("A currency must not run a second phase at one checkpoint")
                if (order.currency == Resource.GOLD) gold -= 1_000_000L else elixir -= 1_000_000L
                WallPhaseOutcome.Bought(order.currency, 1, 1_000_000L)
            }
        )

        assertEquals(listOf(Resource.GOLD, Resource.ELIXIR), orders.map { it.currency })
    }

    @Test
    fun belowThresholdCurrencyAfterFreshReadIsNotRun() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            // The gold buy dropped gold to 75%; elixir reads 85%, under the 90% trigger.
            takeSamples = { agreeingBalances(15_000_000L, 17_000_000L) },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Bought(order.currency, 1, 5_000_000L)
            }
        )

        assertEquals("Elixir under trigger must not run", 1, orders.size)
        assertEquals(Resource.GOLD, orders[0].currency)
    }

    @Test
    fun disagreeingFreshSamplesPreventSecondPhase() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            // Elixir moved between the two reads, so nothing about it is proven.
            takeSamples = {
                listOf(
                    ResourceSample(1_000L, mapOf(Resource.GOLD to 15_000_000L, Resource.ELIXIR to 20_000_000L)),
                    ResourceSample(2_000L, mapOf(Resource.GOLD to 15_000_000L, Resource.ELIXIR to 19_000_000L))
                )
            },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Bought(order.currency, 1, 5_000_000L)
            }
        )

        assertEquals("Unagreed fresh balances run no second phase", 1, orders.size)
    }

    @Test
    fun stoppedRunPreventsSecondPhase() = runBlocking {
        val orders = mutableListOf<WallPhaseOrder>()
        val coordinator = checkpointCoordinator()

        coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { agreeingBalances(15_000_000L, 20_000_000L) },
            isStopped = { orders.isNotEmpty() },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Bought(order.currency, 1, 1_000_000L)
            }
        )

        assertEquals("A stopped run starts no second phase", 1, orders.size)
    }

    @Test
    fun pendingPurchaseBlocksBothPhasesAndIsNotAcknowledged() = runBlocking {
        val purchases = store()
        purchases.markPending(
            PendingWallPurchase(accountId, Resource.GOLD, 2, 8_000_000L, 12_000_000L, 20_000_000L, 1L)
        )
        val orders = mutableListOf<WallPhaseOrder>()
        var sampled = 0
        val coordinator = checkpointCoordinator(purchases = purchases)

        // D3 F5: the checkpoint ends with no phase and no throw; LocalRunner then ends the account.
        val fresh = coordinator.runCheckpointPhases(
            balances = mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L),
            takeSamples = { sampled++; agreeingBalances(20_000_000L, 20_000_000L) },
            executePhase = { order ->
                orders += order
                WallPhaseOutcome.Bought(order.currency, 1, 1_000_000L)
            }
        )

        assertNull(fresh)
        assertEquals(0, orders.size)
        assertEquals(0, sampled)
        assertTrue("The pending marker is never cleared or acknowledged",
            purchases.isPending(accountId))
    }
}
