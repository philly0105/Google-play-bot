package local.clashfarmer.core

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecorder
import local.clashfarmer.log.AppLog
import local.clashfarmer.recognition.ArmyRecipes
import local.clashfarmer.recognition.ArmyTab
import local.clashfarmer.recognition.AttackAfterPause
import local.clashfarmer.recognition.BATTLE_READS
import local.clashfarmer.recognition.BattleClock
import local.clashfarmer.recognition.BattleDamage
import local.clashfarmer.recognition.BattleReading
import local.clashfarmer.recognition.BattleResultReader
import local.clashfarmer.recognition.HeroCards
import local.clashfarmer.recognition.ImageMatcher
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.ScreenReader
import local.clashfarmer.recognition.confirmSurrenderTarget
import local.clashfarmer.recognition.nextButtonTarget
import local.clashfarmer.recognition.returnHomeTarget
import local.clashfarmer.recognition.surrenderTarget
import local.clashfarmer.recognition.SurrenderTracker
import local.clashfarmer.recognition.UseCheck
import local.clashfarmer.service.FarmerService
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

class LocalRunner(
    private val service: FarmerService,
    private val profile: PhoneProfile,
    private val isSupervised: Boolean,
    private val maxAttacks: Int = 1,
    private val maxMinutes: Int = 120,
    val wallsEnabled: Boolean = false,
    val triggerPercent: Int = 90,
    val resumePercent: Int = 75,
    private val maxWallLevel: Int = Policy.DEFAULT_MAX_WALL_LEVEL,
    private val reserveGold: Long = Policy.DEFAULT_RESERVE_GOLD,
    private val reserveElixir: Long = Policy.DEFAULT_RESERVE_ELIXIR,
    private val profileFile: File? = null,
    val currencyPreference: String = "auto",
    val maxWallsPerPhase: Int = 20,
    /**
     * Applies a change to the saved profile as it stands on disk, under the store's lock. Without
     * one, changes go straight to [profileFile].
     */
    private val profileUpdater: ((update: (PhoneProfile) -> PhoneProfile) -> PhoneProfile)? = null,
    /** The balances that end the run early. Without targets the run ends on its quota alone. */
    private val goal: FarmingGoal = FarmingGoal(),
    private val capacities: Map<Resource, Long> = emptyMap(),
    /** Which bases to attack. Off, the first base found is attacked as before. */
    private val opponent: OpponentRules = OpponentRules(),
    /** Where each battle is recorded, under [accountId]. Without one nothing is recorded. */
    private val history: BattleHistoryStore? = null,
    private val accountId: String? = null,
    /** Where the gold and elixir bars read between attacks are kept for the storage rings. */
    private val storage: StorageStore? = null,
    val wallTracker: WallPhaseTracker = WallPhaseTracker(),
    val purchaseStore: WallPurchaseStore? = null,
    /** Tap the calibrated collectors before the first search. */
    private val collectBefore: Boolean = false,
    /** Tap them again once the run has finished on its quota or goal. */
    private val collectAfter: Boolean = false,
    /** The temporary troop event attack: the tray gains an event card and its troops go down too. */
    private val withEventTroops: Boolean = false,
    /** When a battle is surrendered instead of waited out. Off by default. */
    private val earlyEnd: EarlyEndRules = EarlyEndRules(),
    /**
     * When the run's minutes are spent, on [SystemClock.elapsedRealtime]. Passed in so a restart
     * keeps the session's original limit; without one the limit is [maxMinutes] from the start.
     */
    private val timeLimitAt: Long? = null
) {

    private companion object {
        /** Beside profile.json: the village at the camera the collectors were measured on. */
        const val WALL_CAMERA_REFERENCE = "wall_camera.png"

        /** The banner can queue several offers behind one another during a single battle. */
        const val MAX_REWARD_OFFERS = 6

        /** Where a battle may end up without passing a result screen the reader caught. */
        val BATTLE_EXITS = setOf(ScreenState.HOME)

        /** Phases a run is in before matchmaking can have started. */
        val PRE_SEARCH_PHASES = setOf(ScreenState.HOME, ScreenState.MENU, ScreenState.ARMY)

        /** Known-popup closes one settle may send before it waits like any unknown screen. */
        const val MAX_POPUP_TAPS = 3

        /** Backs out of the store one settle or transition may send, as ReturnHome's walks do. */
        const val MAX_SHOP_BACKS = ReturnHome.MAX_SHOP_BACKS

        /** The store's slide out after Back, before the screen is read again. */
        const val SHOP_BACK_SETTLE_MS = 1_200L

        /**
         * How far a button's word may sit from where the frame its press was decided on read it,
         * as the wall confirmation's look allows its Okay.
         */
        const val BUTTON_DRIFT_PX = 24.0

        /** About fifteen seconds of out-of-phase matchmaking readings before the run gives up. */
        const val MAX_STRAY_SEARCHING = 15

        /** Neutral taps on a wall panel left open before the run gives up. */
        const val MAX_STRAY_WALL_PANELS = 5

        /** Between the two village frames a goal check reads. */
        const val GOAL_SAMPLE_GAP_MS = 600L

        /** The wait before a second look, when a village frame did not read as the village. */
        const val HOME_REREAD_MS = 1_500L

        /** How many places a retry tries when a card is still stocked. */
        const val RETRY_DROPS = 10
        /** How long a card is given to grey after its last troop lands. */
        const val CARD_SETTLE_MS = 400L

        /** How long matchmaking may go on searching before the run gives up on it. */
        const val SEARCH_TIMEOUT_MS = 180_000L

        /**
         * How long a screen may sit unchanged under a tap before the tap is sent again, and
         * how many times.
         *
         * A village still animating its way home swallows a tap without trace. At 14:29 on
         * 18 September the attack button was tapped once from home, nothing moved, and the
         * session ended three attacks into fifteen. A transition that is going to happen has
         * happened within about two seconds, so four is long enough to be sure nothing did.
         */
        const val RETAP_AFTER_MS = 4000L
        const val MAX_RETAPS = 2

        /**
         * How many times to pinch the attack view out before dropping anything.
         *
         * The view opens zoomed in far enough that no part of the ground a troop may be placed
         * on is on the screen. Each pinch takes the camera one step out and the game clamps at
         * its own limit, so a few more than are strictly needed costs nothing and guarantees the
         * same framing on every base - which is what makes calibrated drop points mean anything.
         */
        const val ZOOM_OUT_PINCHES = 4
        const val ZOOM_START_RADIUS = 300
        const val ZOOM_END_RADIUS = 40
        const val ZOOM_DURATION_MS = 350L

        /** How many result frames may be read, a moment apart, while its rows count up. */
        const val RESULT_FRAMES = 4
        const val RESULT_FRAME_GAP_MS = 600L

        /** Surrender taps dispatched per battle before it is left to run out on its own. */
        const val MAX_SURRENDER_TRIES = 2
        /** Frames a fired trigger may find no Surrender button on before giving up. */
        const val MAX_SURRENDER_MISSES = 4
        /** Frames looked at for the surrender dialog after Surrender is tapped. */
        const val CONFIRM_FRAMES = 4
        /** In-battle loot misses; 127 of 191 `scout_loot_unreadable` on 8 October were these. */
        const val BATTLE_LOOT_UNREADABLE = "battle_loot_unreadable"

        /**
         * Attacks in a row on which no calibrated hero card showed its hero landed before the run
         * stops. Blood's wrong tray of 14 October kept every hero home for two hours.
         */
        const val HEROES_STAYED_ATTACKS = 2
    }

    /** Opened by [run] or [runWalls] inside the boundary that closes it. */
    private lateinit var screenReader: ScreenReader
    private var attackAccepted = false
    /** When the army was sent to look for a base, on the monotonic clock; the search counts from here. */
    private var searchStartedAt: Long? = null
    /** Ties this run's battles together in the history. */
    private val sessionId = UUID.randomUUID().toString()
    private var recorder: BattleRecorder? = null
    /** When the last troop of the current battle went down, on the monotonic clock. */
    private var deployedAt: Long? = null
    /** The clock the running deployment charges its steps to, or null outside one. */
    private var deployTimer: StepTimer? = null
    /** What each spell card read while still stocked this battle, before any of its drops. */
    private var spellBaselines: Map<String, CardReading> = emptyMap()
    /** The battle's surrender budget: taps on a located button, and frames without one. */
    private var surrenderTracker = SurrenderTracker(MAX_SURRENDER_TRIES, MAX_SURRENDER_MISSES)
    /** The overall damage this battle is surrendered at, when [EarlyEndRules.atDamage] is on. */
    private var damageTarget = pickDamageTarget(earlyEnd)
    /** Keeps early end off a battle's damage until a reading proves it past 9; see [DamageProof]. */
    private val damageProof = DamageProof()
    /** Watches each battle wait for a stall; its decision is logged, and under [EarlyEndRules.onStall] also surrendered on. */
    private var stallDetector: StallDetector? = null
    /** Bubble taps since a known screen was last read; only for the cleared or reset log line. */
    private var dialogueAdvances = 0
    /** When this run's time limit falls, on the monotonic clock; set by [beginRun]. */
    private var softDeadline = Long.MAX_VALUE
    /** Whether an attack of this run has judged its hero slots yet, and slot 1's count on the last attack; see [heroCheckDue]. */
    private var heroSlotsJudged = false
    private var lastSlot1Count: Int? = null
    /** Slots 1 and 2's counts when the last hero check read an event layout as the plain tray; see [readTray]. */
    private var troopInSlot2Counts: Pair<Int?, Int?>? = null
    /** Attacks in a row whose calibrated hero cards showed no hero landed; see [countHeroLandings]. */
    private var heroesStayed = 0
    /** Why the run stops once the battle under way is home; set by [countHeroLandings]. */
    private var heroesStayedStop: String? = null

    private fun timeLimitReached() = SystemClock.elapsedRealtime() >= softDeadline

    /** Holds each village balance against the last one this run trusted. */
    private val balanceGuard by lazy {
        HomeBalanceGuard(capacities, prior = homeBalancePrior(wallsOnly, accountId, storage))
    }
    /** Set by [runWalls] before the guard is first used. */
    private var wallsOnly = false

    /**
     * Everything a run has to be sure of before it is allowed to touch the screen: the
     * calibration covers the work, Clash is actually in front, the display is the one the
     * profile was measured on, and the guard is holding a fresh token.
     */
    private suspend fun beginRun(forWalls: Boolean): Long {
        Policy.validateLimits(maxAttacks, maxMinutes)

        // Only the watched one-attack session may run before the profile has earned verification.
        if (!profile.isVerified && !Policy.mayStartUnverified(isSupervised, maxAttacks, forWalls)) {
            throw StopException(Policy.verificationRequired("This account"))
        }

        if (forWalls) profile.validateForWalls() else profile.validateForAttack()

        // Wait until Clash is truly active and foreground before starting input. Starting from
        // the helper puts Clash in the background, and it comes back through its loading screen;
        // thirty seconds was not enough for that and the run died before it took a single frame.
        service.overlay?.updateStatus("Waiting for Clash foreground...")
        service.ensureClashForeground(timeoutMs = 90_000)

        // The helper screen is portrait and Clash is landscape, so for a second or two after the
        // game is launched the display still reports 720x1600, and the run used to die on the
        // spot complaining about the orientation. The rotation is waited for, not demanded.
        val expectedDisplay = Viewport(0, 0, profile.resolutionWidth, profile.resolutionHeight)
        val rotateDeadline = System.currentTimeMillis() + 60_000L
        var measuredDisplay = service.currentDisplayViewport()
        while (measuredDisplay != expectedDisplay && System.currentTimeMillis() < rotateDeadline) {
            delay(500)
            measuredDisplay = service.currentDisplayViewport()
        }
        if (measuredDisplay != expectedDisplay) {
            throw StopException(
                "The screen is $measuredDisplay but this profile was calibrated for $expectedDisplay. " +
                    "Put Clash in the orientation you calibrated, or calibrate again."
            )
        }

        // The time limit ends the run at home; the guard's own deadline sits past it as the hard
        // stop for a run that never gets home.
        val limitAt = timeLimitAt ?: (SystemClock.elapsedRealtime() + maxMinutes * 60 * 1000L)
        softDeadline = limitAt
        val runToken = service.runGuard.startRun(
            timeoutMs = Policy.hardDeadlineMs(limitAt, SystemClock.elapsedRealtime()),
            expectedViewport = expectedDisplay,
            width = profile.resolutionWidth,
            height = profile.resolutionHeight
        )

        AppLog.log("session_started",
            "token" to runToken,
            "supervised" to isSupervised,
            "walls" to forWalls,
            "wallsEnabled" to (wallsEnabled && !isSupervised),
            "triggerPercent" to triggerPercent,
            "resumePercent" to resumePercent,
            "maxAttacks" to maxAttacks,
            "maxMinutes" to maxMinutes,
            "verified" to profile.isVerified,
            "sessionId" to sessionId
        )
        logDeviceHealth("device_health", service.readDeviceHealth())
        return runToken
    }

    private fun wallRunnerFor(runToken: Long) = WallRunner(
        service = service,
        profile = profile,
        screenReader = screenReader,
        runToken = runToken,
        maxWallLevel = maxWallLevel,
        reserveGold = reserveGold,
        reserveElixir = reserveElixir,
        currencyPreference = currencyPreference,
        maxWallsPerPhase = maxWallsPerPhase,
        purchaseStore = purchaseStore ?: WallPurchaseStore(service.filesDir)
    )

    private fun wallCoordinator(wallRunner: WallRunner) = WallFarmingCoordinator(
        accountId = accountId,
        wallsEnabled = wallsEnabled,
        isSupervised = isSupervised,
        rules = WallStorageRules(triggerPercent, resumePercent),
        currencyPreference = currencyPreference,
        reserveGold = reserveGold,
        reserveElixir = reserveElixir,
        maxWallLevel = maxWallLevel,
        maxWallsPerPhase = maxWallsPerPhase,
        capacities = capacities,
        goal = goal,
        tracker = wallTracker,
        purchaseStore = purchaseStore ?: WallPurchaseStore(service.filesDir),
        runner = wallRunner
    )

    private suspend fun collect(runToken: Long, whenRun: String) {
        service.overlay?.updateStatus("Collecting resources ($whenRun farming)...")
        val cameraReference = profileFile?.let { File(it.parentFile, WALL_CAMERA_REFERENCE) }
        val result = CollectionRunner(service, profile, screenReader, runToken, cameraReference).run()
        AppLog.log("collection_phase", "when" to whenRun, "attempted" to result.attempted,
            "verified" to result.verified, "skipped" to result.skipped, "reason" to result.reason)
    }

    /** Writes a change to the saved profile, or returns null when there is nowhere to save it. */
    private fun saveProgress(update: (PhoneProfile) -> PhoneProfile): PhoneProfile? {
        profileUpdater?.let { return it(update) }
        val file = profileFile ?: return null
        return update(PhoneProfile.loadFromFile(file)).also { it.saveToFile(file) }
    }

    private fun openReader(): ScreenReader = ScreenReader().also { screenReader = it }

    /**
     * Runs manual wall purchases without the automatic storage trigger.
     */
    suspend fun runWalls() {
        wallsOnly = true
        if (accountId == null) throw StopException("Wall upgrades require an account ID")
        if (wallSpendable(currencyPreference).any { (capacities[it] ?: 0L) <= 0L }) {
            throw StopException("Wall upgrades require storage capacities for the selected currencies")
        }

        runWithReader(::openReader, begin = { beginRun(forWalls = true) }) { _, runToken ->
            try {
                service.overlay?.updateStatus("Running wall upgrades...")
                val wallRunner = wallRunnerFor(runToken)
                val coordinator = wallCoordinator(wallRunner)
                val deadlineMs = System.currentTimeMillis() + maxMinutes * 60 * 1000L
                var unread = false
                val upgraded = coordinator.runManualWalls(
                    balancesProvider = {
                        // Every read after the first follows a phase that may have spent.
                        balanceGuard.spendingPossible()
                        agreeBalances(takeHomeSamples(runToken)).also { unread = wallBalancesUnread(it, currencyPreference) }
                    },
                    deadlineMs = deadlineMs,
                    isStopped = { service.runGuard.isStopped() },
                    awaitResume = { service.awaitResume("Manual wall phase") }
                )
                val noBuilder = wallTracker.noFreeBuilder(System.currentTimeMillis())
                val noWalls = wallTracker.noWallsLeft()
                AppLog.log("wall_session_finished", "upgrades" to upgraded, "noFreeBuilder" to noBuilder,
                    "balancesUnread" to unread, "noWalls" to noWalls)
                service.overlay?.updateStatus(
                    when {
                        noBuilder -> "No builder is free; walls skipped"
                        unread -> "Village balances could not be read; $upgraded walls upgraded"
                        noWalls -> "Every wall is maxed; $upgraded walls upgraded"
                        else -> "Wall phase complete: $upgraded upgraded"
                    }
                )
            } finally {
                service.runGuard.stop("Wall phase finished")
            }
        }
    }

    /**
     * Farms until the quota or the goal is reached, the storage is too full to farm into, or the
     * time limit has passed by the time the run is home between attacks, and says which. A Stop,
     * the guard's hard deadline or any failure is an exception, never an outcome: only those four
     * reasons may move a combined run on to its next account.
     */
    suspend fun run(): RunOutcome {
        var thrown: StopException? = null
        return try {
            farm()
        } catch (e: StopException) {
            thrown = e
            throw e
        } finally {
            // Whatever ended the run, a battle it was still in ends unknown rather than won.
            logStallTrajectory()
            recordBattle { recorder?.interrupt(interruptReason(service.runGuard.stopReason, thrown)) }
        }
    }

    private suspend fun farm(): RunOutcome = runWithReader(::openReader, begin = { beginRun(forWalls = false) }) { _, runToken ->
        val session = Session(maxAttacks, ::timeLimitReached)
        recorder = history?.let { store ->
            // Only one run at a time, so a battle still open on disk belonged to a process that died.
            recordBattle { store.interruptUnfinished("process_restart") }
            BattleRecorder(store, sessionId, accountId ?: "unknown")
        }
        val wallRunner = wallRunnerFor(runToken)
        val coordinator = wallCoordinator(wallRunner)

        if (opponent.enabled && (profile.scoutLootRois.isEmpty() || profile.scoutCountdownRoi == null || profile.scoutFeeRoi == null)) {
            // Selection still runs, but with nothing to read it can only fall back to attacking.
            AppLog.log("opponent_selection_uncalibrated",
                "loot" to profile.scoutLootRois.keys.joinToString(",") { it.key },
                "countdown" to (profile.scoutCountdownRoi != null), "fee" to (profile.scoutFeeRoi != null))
        }

        if (collectBefore) collect(runToken, "before")

        var outcome: RunOutcome? = null
        var lastState: ScreenState? = null
        var straySearching = 0
        var strayWallPanels = 0
        while (!service.runGuard.isStopped()) {
            service.awaitResume(lastState?.name ?: "start")
            if (service.runGuard.isStopped()) break
            val obs = observeSettled(runToken)
            lastState = obs.state

            // A battle that seems to have ended at home is read once more before it counts.
            // One frame of village between battle frames would otherwise end an attack that
            // is still running.
            if (session.phase == ScreenState.BATTLE && obs.state == ScreenState.HOME) {
                delay(500)
                val again = observeSettled(runToken)
                if (again.state != ScreenState.HOME) {
                    AppLog.log("home_unconfirmed", "next" to again.state.name)
                    continue
                }
            }

            if (obs.state == ScreenState.HOME && session.homeCheckpointDue()) {
                // Checked before anything else at home, so a flat or hot phone opens no attack.
                val health = service.readDeviceHealth()
                val guardReason = if (session.counter.isQuotaReached) null else health?.let { DeviceGuard.stopReason(it) }
                if (guardReason != null) {
                    logDeviceHealth("device_guard_stop", health)
                    AppLog.log("session_finished", "completed" to session.counter.completed,
                        "reason" to CompletionReason.DEVICE_GUARD.key, "detail" to guardReason)
                    if (isSupervised && attackAccepted) {
                        markEarnedVerification()
                    }
                    service.overlay?.updateStatus(guardReason)
                    service.runGuard.stop(guardReason)
                    outcome = RunOutcome(session.counter.completed, CompletionReason.DEVICE_GUARD, guardReason)
                    break
                }
                service.overlay?.updateStatus("Checking village storage...")
                val samples = takeHomeSamples(runToken)
                if (samples.size >= 2) {
                    val agreed = agreeBalances(samples)
                    recordStorageValues(agreed)
                    if (coordinator.evaluateGoal(samples)) {
                        outcome = finishOnGoal(session.counter.completed, runToken)
                        break
                    }
                    if (!session.counter.isQuotaReached) {
                        val postPhaseSamples = coordinator.runCheckpointPhases(
                            balances = agreed,
                            takeSamples = {
                                // Only called after a wall phase, which may have spent.
                                balanceGuard.spendingPossible()
                                takeHomeSamples(runToken)
                            },
                            isStopped = { service.runGuard.isStopped() }
                        )
                        // A purchase lowers the balance, and the next runner's prior is the last
                        // one stored. At 16:21 UTC on 14 October the takeover restart still had
                        // the 26.2M elixir from before the 16:18 phase and threw out 5,119,558.
                        postPhaseSamples?.let { recordStorageValues(agreeBalances(it)) }
                        // Loot a full storage cannot hold is wasted, so with nothing left to
                        // spend it on this account ends here rather than open another attack.
                        // A normal finish, so a combined run moves on to its next account. So is
                        // an unproven wall purchase: this account buys nothing more, but attacking
                        // or switching accounts is no purchase.
                        val fullReason = coordinator.fullStorageStopReason(postPhaseSamples ?: samples)
                        val pendingReason = if (fullReason == null) coordinator.pendingStopReason() else null
                        val endReason = fullReason ?: pendingReason
                        if (endReason != null) {
                            val reason = if (fullReason != null) CompletionReason.STORAGE_FULL else CompletionReason.WALL_PENDING
                            AppLog.log(if (fullReason != null) "storage_full_stop" else "wall_pending_stop",
                                "reason" to endReason, "completed" to session.counter.completed)
                            AppLog.log("session_finished", "completed" to session.counter.completed,
                                "reason" to reason.key, "detail" to endReason)
                            if (isSupervised && attackAccepted) {
                                markEarnedVerification()
                            }
                            service.overlay?.updateStatus(endReason)
                            service.stopBetweenRuns(endReason)
                            outcome = RunOutcome(session.counter.completed, reason, endReason)
                            break
                        }
                    }
                }
                session.checkpointDone()
                continue
            }

            // Matchmaking only follows the army check, so a search read before it is some other
            // screen misread. At 08:14 UTC on 5 October one came a second after a battle had
            // returned home, and the run stopped three attacks into fifteen. Look again instead.
            if (obs.state == ScreenState.SEARCHING && session.phase in PRE_SEARCH_PHASES &&
                straySearching < MAX_STRAY_SEARCHING
            ) {
                straySearching++
                AppLog.log("searching_out_of_phase", "phase" to session.phase.name,
                    "count" to straySearching, "text" to obs.rawText.take(200))
                delay(1000)
                continue
            }
            straySearching = 0

            // The wall stays selected after an upgrade, and its panel can still be up a second
            // after the wall phase saw the village. At 09:15 UTC on 5 October that stopped the run
            // right after four walls were bought. Close it with a tap on open ground.
            val neutral = profile.stateAnchors["village_neutral"]
            if (obs.state == ScreenState.WALL && session.phase in PRE_SEARCH_PHASES &&
                neutral != null && strayWallPanels < MAX_STRAY_WALL_PANELS
            ) {
                strayWallPanels++
                AppLog.log("wall_panel_out_of_phase", "phase" to session.phase.name,
                    "count" to strayWallPanels)
                // Held across a pause or on an old frame, nothing went out: the next pass reads again.
                if (sentOrHeld { service.transport.tap(neutral, runToken, decidedOn = obs.timestampMs) }) delay(1000)
                continue
            }
            strayWallPanels = 0

            // The wait between attacks and the attack cooldown are sat out before the frame that
            // decides Attack or Find Match is read, and the loop reads again after them. On
            // 15 October at 05:31 UTC the Attack tap was decided before the wait, went out 19 s
            // later past a pause, and landed on the gem store under its $0.99 card.
            if (obs.state == ScreenState.HOME && session.pacingDue()) {
                if (waitBeforeAttack()) session.pacingDone()
                continue
            }
            if (obs.state == ScreenState.MENU && session.phase == ScreenState.MENU &&
                MultiplayerCooldown.showing(obs.rawText)
            ) {
                waitOutCooldown(runToken)
                continue
            }

            val action = session.accept(obs.state)
            AppLog.log("action_step", "state" to obs.state.name, "action" to action.name)

            when (action) {
                Action.OPEN_ATTACK -> {
                    val attackBtn = profile.stateAnchors["attack"]
                        ?: throw StopException("Missing calibrated attack button anchor")
                    pressFirst(obs, attackBtn, ScreenState.HOME, runToken, labelStill(obs, attackBtn, ::readsAttack))
                    transitionTo(
                        setOf(ScreenState.MENU), runToken, timeoutSeconds = 15,
                        from = obs.state, retap = attackBtn
                    )
                }

                Action.FIND_MATCH -> {
                    val findMatchBtn = profile.stateAnchors["find_match"]
                        ?: throw StopException("Missing calibrated find_match button anchor")
                    pressFirst(obs, findMatchBtn, ScreenState.MENU, runToken, labelStill(obs, findMatchBtn, ::readsFindMatch))
                    transitionTo(
                        setOf(ScreenState.ARMY), runToken, timeoutSeconds = 15,
                        from = obs.state, retap = findMatchBtn
                    )
                }

                Action.CHECK_ARMY -> {
                    val armyBtn = profile.stateAnchors["army"] ?: profile.stateAnchors["find_match"]
                        ?: throw StopException("Missing calibrated army confirm button")
                    readyArmy(obs, runToken)
                    pressAttack(armyBtn, runToken)
                    // After the press, so a pause before it does not count against the search.
                    searchStartedAt = SystemClock.elapsedRealtime()
                    // Either the scout screen or the battle it turns into after thirty
                    // seconds; both are somewhere troops can be dropped.
                    transitionTo(
                        setOf(ScreenState.SCOUT, ScreenState.BATTLE),
                        runToken, timeoutSeconds = 30, from = obs.state
                    )
                }

                Action.DEPLOY -> {
                    // A battle already under way is deployed into; there is nothing left to choose.
                    val selection = if (obs.state == ScreenState.SCOUT && opponent.enabled) selectOpponent(runToken) else null
                    searchStartedAt = null
                    recordBattle {
                        recorder?.start(selection?.loot.orEmpty(), selection?.skips ?: 0, selection?.spentGold ?: 0)
                            ?.let { AppLog.log("battle_started", "battleId" to it.id, "sessionId" to sessionId) }
                    }
                    executeDeployment(session, runToken)
                }

                Action.WAIT -> {
                    if (obs.state == ScreenState.BATTLE && endBattleEarlyIfDue(runToken)) continue
                    if (obs.state == ScreenState.BATTLE) feedStallShadow(runToken, obs)
                    // Damage climbs a few percent a second, so it is looked at without the pause.
                    if (!(earlyEnd.enabled && earlyEnd.atDamage)) delay(2000)
                    transitionTo(
                        setOf(ScreenState.BATTLE, ScreenState.RESULT, ScreenState.REWARD), runToken,
                        timeoutSeconds = 30, from = obs.state, confirmedExits = BATTLE_EXITS
                    )
                }

                Action.REWARD -> {
                    handleRewardOffer(runToken)
                }

                Action.RETURN_HOME -> {
                    // The result screen is the one screen calibration cannot reach without
                    // attacking, so the button is located from the reading we already have,
                    // but only where the button is drawn: a defender's name can say Return.
                    val calibrated = profile.stateAnchors["return_home"]
                    val returnHomeBtn = returnHomeTarget(obs.boxes, obs.viewport, calibrated)
                        ?: throw StopException(
                            "No Return Home button where the result screen draws it " +
                                "(reading: ${obs.rawText.take(200)})"
                        )
                    AppLog.log("return_home_target", "calibrated" to (returnHomeBtn == calibrated),
                        "x" to returnHomeBtn.x, "y" to returnHomeBtn.y)
                    // Read before leaving: once home, the screen that said what was taken is gone.
                    // Return Home is pressed only from a frame that is still the result screen; the
                    // reading takes up to four frames, and the button is decided again on the last.
                    var decided = obs
                    if (recorder?.open != null) {
                        val (result, last) = readResult(runToken)
                        recordBattle { recorder?.result(result.earned, result.damagePercent, result.stars) }
                        decided = last ?: obs
                    }
                    fun returnHomeAt(look: Observation) =
                        returnHomeTarget(look.boxes, look.viewport, calibrated)?.let { near(it, returnHomeBtn) } == true
                    val pressed = decided.state == ScreenState.RESULT && returnHomeAt(decided) &&
                        pressFirst(decided, returnHomeBtn, ScreenState.RESULT, runToken, ::returnHomeAt)
                    // The loading clouds that follow read as unrecognised and are waited through,
                    // so only the result screen's own fade needs to pass before the first look.
                    if (pressed) delay(1000)
                    transitionTo(
                        setOf(ScreenState.HOME), runToken, timeoutSeconds = 30,
                        from = obs.state, retap = returnHomeBtn
                    )

                    attackAccepted = true
                    recordBattle { recorder?.finish("result_screen") }
                    logStallTrajectory()
                    balanceGuard.attackCompleted()
                    AppLog.log("attack_completed",
                        "completed" to session.counter.completed,
                        "limit" to session.counter.limit
                    )
                    // Home, with the battle counted: the heroes' second attack in a row left in the tray ends here.
                    heroesStayedStop?.let { throw StopException(it) }
                }

                Action.ARRIVED_HOME -> {
                    attackAccepted = true
                    // Home without a result screen: the battle is over, how it went is not known.
                    recordBattle { recorder?.finish("result_not_seen") }
                    logStallTrajectory()
                    balanceGuard.attackCompleted()
                    AppLog.log("attack_completed",
                        "completed" to session.counter.completed,
                        "limit" to session.counter.limit,
                        "resultScreenSeen" to false
                    )
                    heroesStayedStop?.let { throw StopException(it) }
                }

                Action.FINISH -> {
                    AppLog.log("session_finished", "completed" to session.counter.completed,
                        "reason" to CompletionReason.QUOTA_REACHED.key)
                    if (isSupervised && attackAccepted) {
                        markEarnedVerification()
                    }
                    if (collectAfter) collect(runToken, "after")
                    service.overlay?.updateStatus("Session Complete: ${session.counter.completed} attacks")
                    service.stopBetweenRuns("Session limits reached successfully")
                    outcome = RunOutcome(session.counter.completed, CompletionReason.QUOTA_REACHED)
                    break
                }

                Action.TIME_LIMIT -> {
                    outcome = finishOnTimeLimit(session.counter.completed, runToken)
                    break
                }
            }
        }
        // The guard stopped under the loop: the Stop button, the hard deadline or a failure elsewhere.
        outcome ?: throw StopException(service.runGuard.stopReason ?: "Run stopped")
    }

    private fun logDeviceHealth(event: String, health: DeviceHealth?) {
        AppLog.log(event, "level" to health?.levelPercent, "charging" to health?.charging, "tempC" to health?.tempC)
    }

    /**
     * Sits at home for an [AttackPacing] wait. False when the run was stopped during it, so the
     * loop exits without tapping Attack.
     */
    private suspend fun waitBeforeAttack(): Boolean {
        val waitMs = AttackPacing.nextWaitMs()
        AppLog.log("attack_wait", "ms" to waitMs)
        service.overlay?.updateStatus("Waiting ${waitMs / 1000}s before the next attack")
        val until = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < until) {
            if (service.runGuard.isStopped()) return false
            delay(minOf(500L, until - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        }
        return !service.runGuard.isStopped()
    }

    /**
     * Holds on the attack menu while it shows the multiplayer cooldown. False when the run was
     * stopped; a cooldown that outlasts [MultiplayerCooldown.MAX_WAIT_MS] stops the run.
     */
    private suspend fun waitOutCooldown(runToken: Long): Boolean {
        AppLog.log("multiplayer_cooldown")
        service.overlay?.updateStatus("Waiting for the attack cooldown...")
        val until = SystemClock.elapsedRealtime() + MultiplayerCooldown.MAX_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (service.runGuard.isStopped()) return false
            delay(MultiplayerCooldown.POLL_MS)
            val cap = service.transport.capture(runToken)
            val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            if (!MultiplayerCooldown.showing(obs.rawText)) {
                AppLog.log("multiplayer_cooldown_over", "state" to obs.state.name)
                return !service.runGuard.isStopped()
            }
        }
        throw StopException("The attack cooldown was still showing after ${MultiplayerCooldown.MAX_WAIT_MS / 1000}s")
    }

    /** One frame, taken and read now. */
    private suspend fun lookNow(runToken: Long): Observation {
        val cap = service.transport.capture(runToken)
        return try {
            held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
        } finally {
            cap.bitmap.recycle()
        }
    }

    /**
     * Sends [button], the first press of a transition, decided on [decided]. A frame older than
     * [TapAfterHold.MAX_DECISION_AGE_MS] or a pause before the send takes a look first, and the
     * press goes only if the look is still [state], shows no store and [onButton] passes. False
     * when it was held: nothing went out, and the caller's transitionTo presses the button again
     * from its own fresh reads while [state] is still up. On 15 October at 05:31 UTC an Attack tap
     * went out 19 s after its frame, past a pause, and landed on the gem store's $0.99 card.
     */
    private suspend fun pressFirst(
        decided: Observation,
        button: Point,
        state: ScreenState,
        runToken: Long,
        onButton: (Observation) -> Boolean
    ): Boolean = sentOrHeld {
        service.transport.tapButton(button, runToken, decidedOn = decided.timestampMs, afterPause = {
            val look = lookNow(runToken)
            if (look.state != state || Policy.showsShop(look.rawText) || !onButton(look)) {
                throw StopException(TapAfterHold.HELD)
            }
        })
    }

    /**
     * Whether a look still reads the button's word where [decided] read it, the box nearest
     * [button] that [word] accepts. Measured from the word rather than the point: the calibrated
     * Attack sits on the button's swords at (134, 637), and its "Battle" is read at (140, 685).
     */
    private fun labelStill(decided: Observation, button: Point, word: (String) -> Boolean): (Observation) -> Boolean {
        val before = labelNear(decided, button, word)
        return { look -> before != null && labelNear(look, button, word)?.let { near(it, before) } == true }
    }

    private fun labelNear(obs: Observation, point: Point, word: (String) -> Boolean): Point? =
        obs.boxes.filter { word(it.text) }.minByOrNull { hypot((it.center.x - point.x).toDouble(), (it.center.y - point.y).toDouble()) }?.center

    private fun near(a: Point, b: Point): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= BUTTON_DRIFT_PX

    /** The village's Attack!, or since Clash 18.647 its "Battle", as the reader spells it. */
    private fun readsAttack(text: String): Boolean {
        val letters = text.lowercase(java.util.Locale.ROOT).filter { it.isLetter() }
        return letters.contains("attack") || letters in BATTLE_READS
    }

    /** The menu's "Find a Match", down to the "find a mat" the reader keeps. */
    private fun readsFindMatch(text: String): Boolean =
        text.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").contains("find a mat")

    /**
     * Backs out of the gem store or a priced offer, which nothing here answers with a tap: on
     * 15 October at 05:31 UTC an Attack tap landed on the store under its $0.99 card. Back is
     * withheld while paused, and then this waits the pause out and sends nothing, so the caller
     * reads the screen again and only a fresh frame of the store gets Back. True when Back went.
     * [decided] is what [obs] was read as; Back goes only if one more frame still shows it. That
     * frame is taken only after a store or offer was read, so no battle frame costs a capture.
     */
    private suspend fun leaveShop(obs: Observation, decided: PopupGuard.Verdict, where: String, runToken: Long): Boolean {
        if (!service.shopStillUp(where, decided) { lookNow(runToken) }) return false
        AppLog.log("gem_shop_left", "where" to where, "text" to obs.rawText.take(200))
        service.overlay?.updateStatus("Leaving the gem store...")
        if (!service.backIfClear(runToken, "gem_shop")) {
            service.awaitClearToAct("gem_shop")
            return false
        }
        delay(SHOP_BACK_SETTLE_MS)
        return true
    }

    /**
     * The account's saved army, by the title on its Saved Recipes card; blank leaves the army as
     * it is. Settings cannot be saved while a run is active, so one read serves the run.
     */
    private val savedArmy: String by lazy {
        val id = accountId ?: return@lazy ""
        AccountStore(service.filesDir).load().firstOrNull { it.id == id }?.settings?.savedArmy.orEmpty().trim()
    }

    /** Whether this runner has tried the saved army: once a run, and again in a restart's new runner. */
    private var savedArmyTried = false

    /**
     * Thrown by a look after a pause when the saved army's card is no longer where it was read, or
     * the list is gone ([check]). The Use tap is then not sent again, whether it had gone out
     * before the pause or not.
     */
    private class ArmyScreenChanged(val check: UseCheck) : RuntimeException("The army screen changed during the pause")

    /** Thrown by the look after a pause just before Attack! when [after] says not to send it. */
    private class AttackHeld(val look: Observation, val after: AttackAfterPause) :
        RuntimeException("Attack! was held after a pause")

    /**
     * Readies the army screen for Attack!: loads the saved army on the runner's first attack,
     * then makes sure the My Army tab is showing. The calibrated Attack! sits where the Saved
     * Recipes list has its third card's Use, so it is pressed only once the tab is told by what
     * it shows, and never on a screen that cannot be told.
     */
    private suspend fun readyArmy(obs: Observation, runToken: Long) {
        var look = obs
        var visited = false
        if (!savedArmyTried && savedArmy.isNotEmpty()) {
            savedArmyTried = true
            visited = true
            look = loadSavedArmy(look, savedArmy, runToken)
        }
        repeat(ArmyRecipes.LOOKS) {
            val tab = armyTab(look)
            if (tab == ArmyTab.MY_ARMY) return
            val label = ArmyRecipes.myArmyLabel(look.boxes, profile.viewport)
            // After the runner's own visit to Saved Recipes, an army screen it cannot tell is
            // still that visit's doing, and My Army is the way back.
            if (label != null && (tab == ArmyTab.SAVED_RECIPES || (visited && look.state == ScreenState.ARMY))) {
                AppLog.log("army_tab_switched", "from" to tab.name)
                tapUnderPanel(label, look.timestampMs, runToken)
            }
            look = lookAtArmy(runToken)
        }
        if (armyTab(look) == ArmyTab.MY_ARMY) return
        AppLog.log("army_tab_unknown", "tab" to armyTab(look).name, "state" to look.state.name, "text" to look.rawText.take(200))
        throw StopException(ArmyRecipes.TAB_UNKNOWN)
    }

    /**
     * Presses Attack!, which is where the Saved Recipes list has a card's Use. A pause or the
     * shade's hold just before the press looks again, and [ArmyRecipes.attackAfterPause] says
     * what the look means: press on My Army; leave it when the search has already begun; ready My
     * Army and press once more; or stop, recoverably. The session is in its scout phase already,
     * so the army screen is never handed back to the run loop.
     */
    private suspend fun pressAttack(armyBtn: Point, runToken: Long) {
        var retried = false
        suspend fun lookAfterPause(): AttackHeld? {
            val look = lookAtArmy(runToken)
            val tab = armyTab(look)
            val after = ArmyRecipes.attackAfterPause(look.state, tab, retried)
            AppLog.log("army_after_pause", "state" to look.state.name, "tab" to tab.name, "decision" to after.name)
            return if (after == AttackAfterPause.SEND) null else AttackHeld(look, after)
        }
        while (true) {
            val held = try {
                service.transport.tapButton(armyBtn, runToken, afterPause = { lookAfterPause()?.let { throw it } })
                return
            } catch (held: AttackHeld) {
                held
            } catch (e: StopException) {
                // Held by a pause just before it went, or by a third pause across its two looks. A
                // supervised run has no restart to spend on it, so a look decides, as after any
                // pause (UO-21 review, 15 October).
                if (!TapAfterHold.isHeld(e)) throw e
                lookAfterPause() ?: continue
            }
            when (held.after) {
                AttackAfterPause.PASSED -> return
                AttackAfterPause.READY_AGAIN -> {
                    retried = true
                    readyArmy(held.look, runToken)
                }
                else -> throw StopException(ArmyRecipes.TAB_UNKNOWN)
            }
        }
    }

    /**
     * Loads the saved army [title] and returns the last look at the screen. The taps are the Saved
     * Recipes label and the card's Use, both at their OCR boxes, and the gestures are swipes along
     * the list, each held at its end so the list stops where the finger does. A find that ends
     * without Use pressed is tried once more ([ArmyRecipes.FIND_TRIES]), from the top of the list
     * unless the card is in view; the way up is the same swipe reversed. A find whose Use tap a
     * pause cut short is never tried again ([ArmyRecipes.afterInterruptedUse]). A title still not found,
     * or a load that is not confirmed, is logged and the run goes on with the army it has: the
     * tray check catches a wrong army, and a stop here would end runs over a renamed card.
     * army_recipe's "looks" says where each look at the list put the card's title and Use, or why
     * it found no card, so a list that moved can be told from a read that missed.
     */
    private suspend fun loadSavedArmy(first: Observation, title: String, runToken: Long): Observation {
        service.overlay?.updateStatus("Loading the saved army $title...")
        var look = first
        var swipes = 0
        var tries = 0
        var read: String? = null
        val looks = mutableListOf<String>()
        // Numbers the looks alone: "again" between two tries is no look.
        var looked = 0
        var noted: Observation? = null
        fun done(outcome: String, vararg more: Pair<String, Any?>): Observation {
            AppLog.log("army_recipe", "title" to title, "outcome" to outcome, "swipes" to swipes, "tries" to tries,
                "looks" to looks.joinToString(" / ").take(1000), *more)
            return look
        }
        fun note() {
            if (noted === look) return
            noted = look
            looks += "${++looked} " +
                if (armyTab(look) == ArmyTab.SAVED_RECIPES) ArmyRecipes.describe(look.boxes, profile.viewport, title) else "no list"
        }
        suspend fun lookAgain() {
            look = lookAtArmy(runToken)
            note()
        }
        val (down, up) = ArmyRecipes.listSwipe(profile.viewport)
        // A swipe after a pause goes only onto the list it was meant for. Held, it is not sent,
        // and the look after it finds the list or ends the find as lost_list.
        val listStillUp: suspend () -> Unit = {
            lookAgain()
            if (armyTab(look) != ArmyTab.SAVED_RECIPES) throw StopException(TapAfterHold.HELD)
        }
        // One find, from the tab to Use: null once Use is pressed, else why it was not.
        suspend fun findAndPressUse(): String? {
            read = null
            if (armyTab(look) != ArmyTab.SAVED_RECIPES) {
                // One frame can miss the label while the screen settles, so look again before giving up.
                fun label() = if (look.state == ScreenState.ARMY) ArmyRecipes.savedRecipesLabel(look.boxes, profile.viewport) else null
                var labelLooks = 0
                while (label() == null && labelLooks++ < ArmyRecipes.LOOKS) look = lookAtArmy(runToken)
                // Held by a pause or an old frame, the label was not tapped, and the looks below
                // find the tab unchanged: the find ends no_list and the next try taps it afresh.
                val at = label() ?: return "no_list"
                sentOrHeld { service.transport.tapButton(at, runToken, decidedOn = look.timestampMs) }
                repeat(ArmyRecipes.LOOKS) { if (armyTab(look) != ArmyTab.SAVED_RECIPES) look = lookAtArmy(runToken) }
                if (armyTab(look) != ArmyTab.SAVED_RECIPES) return "no_list"
            }
            if (tries > 1 && ArmyRecipes.find(look.boxes, profile.viewport, title) == null) {
                // Back up to the top: the card may be above the list's window, or read past. A
                // list reopened at its top costs one swipe that moves nothing.
                var before = ArmyRecipes.titles(look.boxes, profile.viewport)
                for (back in 1..ArmyRecipes.MAX_SWIPES) {
                    if (sentOrHeld {
                            service.transport.swipe(up, down, ArmyRecipes.SWIPE_MS, runToken, ArmyRecipes.HOLD_MS, listStillUp)
                        }) swipes++
                    lookAgain()
                    if (armyTab(look) != ArmyTab.SAVED_RECIPES) return "lost_list"
                    val now = ArmyRecipes.titles(look.boxes, profile.viewport)
                    if (now == before) break
                    before = now
                }
            }
            note()
            var card = ArmyRecipes.find(look.boxes, profile.viewport, title)
            var seen = ArmyRecipes.titles(look.boxes, profile.viewport)
            var forward = 0
            while (card == null && forward < ArmyRecipes.MAX_SWIPES) {
                if (sentOrHeld {
                        service.transport.swipe(down, up, ArmyRecipes.SWIPE_MS, runToken, ArmyRecipes.HOLD_MS, listStillUp)
                    }) swipes++
                forward++
                lookAgain()
                if (armyTab(look) != ArmyTab.SAVED_RECIPES) return "lost_list"
                card = ArmyRecipes.find(look.boxes, profile.viewport, title)
                val now = ArmyRecipes.titles(look.boxes, profile.viewport)
                // The swipe moved nothing: the end of the list.
                if (card == null && now == seen) break
                seen = now
            }
            var found = card ?: return "not_found"
            fun missed(check: UseCheck): String {
                read = found.title.text
                return ArmyRecipes.useMiss(check)
            }
            // Use is tapped only where two reads in a row put it: a list still coasting from the
            // swipe carries it, and the card's Edit is 59 px under it.
            var useLooks = 1
            while (true) {
                lookAgain()
                useLooks++
                val again = ArmyRecipes.find(look.boxes, profile.viewport, title)
                val check = ArmyRecipes.useCheck(armyTab(look), again, found.use.center, useLooks, profile.viewport)
                if (check == UseCheck.LOST_LIST) return missed(check)
                found = again ?: found
                if (check == UseCheck.TAP) break
                if (check == UseCheck.MOVED) return missed(check)
            }
            read = found.title.text
            val use = found.use.center
            var sent = false
            try {
                service.transport.tapButton(use, runToken, afterPause = {
                    // A pause leaves no look to spare: the card is where it was, or Use is not sent.
                    lookAgain()
                    val check = ArmyRecipes.useCheck(armyTab(look), ArmyRecipes.find(look.boxes, profile.viewport, title),
                        use, ArmyRecipes.USE_LOOKS, profile.viewport)
                    if (check != UseCheck.TAP) throw ArmyScreenChanged(check)
                }, onSend = { sent = true })
            } catch (changed: ArmyScreenChanged) {
                // A tap the pause cut short may have gone through, so that find ends here whatever
                // this look shows and Use is never pressed twice; one never sent is a plain miss.
                lookAgain()
                return ArmyRecipes.afterInterruptedUse(sent, changed.check, armyTab(look), look.rawText)
            } catch (e: StopException) {
                // Held by a pause just before it went, or by a third pause across its looks: the
                // same answer, so a supervised run is not stopped by it (UO-21 review, 15 October).
                if (!TapAfterHold.isHeld(e)) throw e
                lookAgain()
                return ArmyRecipes.afterInterruptedUse(sent, UseCheck.MOVED, armyTab(look), look.rawText)
            }
            return null
        }
        val miss = ArmyRecipes.findTries(again = { looks += "again" }) { tries = it; findAndPressUse() }
        if (miss != null) return done(miss, "read" to read)
        // Use goes back to My Army with "Recipe Army 5 set as active army!" over it.
        val pressed = "read" to read
        repeat(ArmyRecipes.LOOKS) {
            look = lookAtArmy(runToken)
            val toast = ArmyRecipes.loadToast(look.rawText)
            if (toast || armyTab(look) == ArmyTab.MY_ARMY) return done("loaded", pressed, "toast" to toast)
        }
        return done("not_confirmed", pressed)
    }

    /** The army screen's tab; any other screen is [ArmyTab.UNKNOWN]. */
    private fun armyTab(look: Observation): ArmyTab =
        if (look.state == ScreenState.ARMY) ArmyRecipes.tab(look.boxes, profile.viewport) else ArmyTab.UNKNOWN

    /**
     * A look at the army screen, and the pause point for the work done on it between the run
     * loop's own: the shade's hold and a person's Pause are waited out here, a stop meanwhile ends
     * the run, and the frame is taken a settle after the resume, so nothing on this screen is
     * tapped from a frame older than a pause. The capture waits out a shade of its own with no
     * settle after it, so a frame taken behind such a wait is dropped and the look starts over.
     */
    private suspend fun lookAtArmy(runToken: Long): Observation {
        while (true) {
            do {
                service.awaitResume(ScreenState.ARMY.name)
                if (service.runGuard.isStopped()) throw StopException(service.runGuard.stopReason ?: "Run stopped")
                delay(ArmyRecipes.SETTLE_MS)
            } while (service.runGuard.isPaused())
            val held0 = service.heldMs
            val cap = service.transport.capture(runToken)
            if (service.heldMs != held0) {
                cap.bitmap.recycle()
                continue
            }
            return try {
                held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            } finally {
                cap.bitmap.recycle()
            }
        }
    }

    /**
     * Taps the My Army label with the bot's panel hidden. The open card's header, with Pause and
     * Stop, lies over the label at (408, 63), and a tap that lands in it goes to the panel; an
     * invisible panel takes no touches. The capture hides it by alpha, so OCR reads the label.
     * The panel comes back as the helper would have it: hidden while the helper is on screen.
     * A tap held by a pause or by the age of the frame taken at [decidedOn] is not sent, and the
     * caller's next look decides again.
     */
    private suspend fun tapUnderPanel(label: Point, decidedOn: Long, runToken: Long) {
        val overlay = service.overlay
        overlay?.setVisible(false)
        try {
            delay(ArmyRecipes.PANEL_HIDE_MS)
            sentOrHeld { service.transport.tapButton(label, runToken, decidedOn = decidedOn) }
        } finally {
            overlay?.setVisible(!service.helperActivityVisible)
        }
    }

    /**
     * Presses Next until a base passes the account's rules or a limit says attack this one. It
     * returns only to deploy; a wrong screen after Next is thrown like any other.
     */
    private suspend fun selectOpponent(runToken: Long): Selection {
        service.overlay?.updateStatus("Choosing an opponent...")
        val selector = OpponentSelector(
            rules = opponent,
            readScout = { readScout(runToken) },
            tapNext = { point -> service.transport.tapButton(point, runToken) },
            clock = SystemClock::elapsedRealtime,
            log = { event, fields -> AppLog.log(event, *fields.toList().toTypedArray()) }
        )
        val selection = selector.select(searchStartedAt ?: SystemClock.elapsedRealtime(), readScout(runToken))
        service.overlay?.updateStatus("Attacking after ${local.clashfarmer.insight.Format.count(selection.skips, "skip")}")
        return selection
    }

    /**
     * What the result screen took, read over a few frames until its rows stop counting up, and
     * the last frame read, which Return Home is decided on. Values that never settle stay unknown.
     */
    private suspend fun readResult(runToken: Long): Pair<BattleReading, Observation?> {
        val frames = mutableListOf<BattleReading>()
        var last: Observation? = null
        for (i in 0 until RESULT_FRAMES) {
            if (i > 0) delay(RESULT_FRAME_GAP_MS)
            val cap = service.transport.capture(runToken)
            try {
                val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
                last = obs
                if (obs.state != ScreenState.RESULT) break
                val rows = BattleResultReader.lootRegions(obs.boxes, obs.viewport)
                val damageRoi = BattleResultReader.damageRegion(obs.boxes, obs.viewport)
                val earned = rows.orEmpty()
                    .mapNotNull { (resource, roi) -> screenReader.readResultLoot(cap.bitmap, roi)?.let { resource to it } }
                    .toMap()
                val damage = damageRoi?.let { screenReader.readResultDamage(cap.bitmap, it) }
                val stars = BattleResultReader.starCentres(obs.boxes, obs.viewport)
                    ?.let { screenReader.readResultStars(cap.bitmap, it) }
                frames += BattleReading(earned, damage, stars)
                AppLog.log("result_frame", "rowsFound" to (rows != null), "damageFound" to (damageRoi != null),
                    "earned" to earned.entries.joinToString(",") { "${it.key.key}=${it.value}" }, "damagePercent" to damage,
                    "stars" to stars,
                    "text" to if (rows == null || damageRoi == null) obs.rawText.take(200) else null)
            } finally {
                cap.bitmap.recycle()
            }
            // The loot is what the history is kept for. Waiting on the damage as well took all
            // four frames on 385 of 590 battles from 25 to 28 September, and the extra two frames
            // settled a damage the first two had missed on 14 of them.
            val settled = BattleResultReader.settled(frames)
            if (settled.earned.size == Resource.entries.size) break
        }
        val settled = BattleResultReader.settled(frames)
        AppLog.log("battle_result", "frames" to frames.size,
            "earned" to settled.earned.entries.joinToString(",") { "${it.key.key}=${it.value}" },
            "damagePercent" to settled.damagePercent, "stars" to settled.stars)
        val stars = settled.stars
        val damage = settled.damagePercent
        if (stars != null && damage != null && !BattleResultReader.starsAgreeWithDamage(stars, damage)) {
            AppLog.log("stars_damage_conflict", "stars" to stars, "damagePercent" to damage)
        }
        return settled to last
    }

    /** History is kept alongside the farming, never in its way: a failed write is logged, not thrown. */
    private inline fun recordBattle(write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            AppLog.log("battle_history_failed", "error" to e.message)
        }
    }

    /** The scout as it stands: its loot rows, countdown, Next price and Next button. */
    private suspend fun readScout(runToken: Long): ScoutFrame {
        val capturedAt = SystemClock.elapsedRealtime()
        val cap = service.transport.capture(runToken)
        try {
            val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            if (obs.state != ScreenState.SCOUT) return ScoutFrame(obs.state, capturedAt)
            // The countdown first: it is the reading that goes stale while the others are taken.
            val seconds = profile.scoutCountdownRoi?.let { screenReader.readScoutCountdown(cap.bitmap, it) }
            val loot = profile.scoutLootRois.entries.mapNotNull { (resource, roi) ->
                screenReader.readScoutLoot(cap.bitmap, roi)?.let { resource to it }
            }.toMap()
            val fee = profile.scoutFeeRoi?.let { screenReader.readNextFee(cap.bitmap, it) }
            val next = nextButtonTarget(obs.boxes, obs.viewport, profile.stateAnchors["next"])
            return ScoutFrame(ScreenState.SCOUT, capturedAt, loot, fee, seconds, next)
        } finally {
            cap.bitmap.recycle()
        }
    }

    private suspend fun finishOnGoal(completed: Int, runToken: Long): RunOutcome {
        AppLog.log("session_finished", "completed" to completed, "reason" to CompletionReason.GOAL_REACHED.key)
        if (isSupervised && attackAccepted) {
            markEarnedVerification()
        }
        if (collectAfter) collect(runToken, "after")
        service.overlay?.updateStatus("Farming goal reached after $completed attacks")
        service.stopBetweenRuns("Farming goal reached")
        return RunOutcome(completed, CompletionReason.GOAL_REACHED)
    }

    /** Home with the minutes spent: a normal finish, so a combined run moves on to its next account. */
    private suspend fun finishOnTimeLimit(completed: Int, runToken: Long): RunOutcome {
        AppLog.log("session_finished", "completed" to completed, "reason" to CompletionReason.TIME_LIMIT.key,
            "maxMinutes" to maxMinutes)
        if (isSupervised && attackAccepted) {
            markEarnedVerification()
        }
        if (collectAfter) collect(runToken, "after")
        service.overlay?.updateStatus("Time limit reached after $completed attacks")
        service.stopBetweenRuns("Time limit reached")
        return RunOutcome(completed, CompletionReason.TIME_LIMIT)
    }

    /** The tray as one scout frame showed it; see [readTray]. */
    private class TrayRead(
        /** The raw cut readings of the first two slots' badge bands, slot 1 first; null when uncalibrated. */
        val badges: List<List<String>>?,
        val layout: AttackPlanner.TrayLayout,
        val tray: AttackPlanner.TrayPlan,
        /** The hero slots of [tray], when this attack judged them. */
        val heroes: TrayCheck.Report?,
        /**
         * The same slots one card right, judged only when a tray not read as an event tray showed a
         * count on one, or when [troopInSlot2] turned an event layout plain.
         */
        val shifted: TrayCheck.Report?,
        /** An event layout deployed as the plain tray: slot 2 holds a troop, not the main troop; see [readTray]. */
        val troopInSlot2: Boolean
    )

    /**
     * Reads the tray off one scout frame. The first two slots' "xNN" badge bands pick the layout
     * and so the tray deployed; when [heroCheckDue], that tray's hero slots are read off the same
     * frame and judged as Setup's tray check judges them. Should one show a count on a tray not
     * read as an event tray, the slots one card right are judged too: one more band unless the
     * profile's card areas are uneven. No frame is taken when the profile cannot place the badges.
     *
     * Should one show a count on an event layout, the slots where they were calibrated are judged
     * too, and [TrayCheck.troopInSlot2] may find the plain tray with a troop in slot 2, which is
     * then deployed plain. Until the next hero check, the same two counts read as an event layout
     * are deployed plain as well.
     */
    private suspend fun readTray(runToken: Long, timer: StepTimer): TrayRead {
        val bands = AttackPlanner.trayBadgeRegions(profile)
        val cap = bands?.let { service.transport.capture(runToken) }
        try {
            val badges = cap?.let { shot -> bands?.map { screenReader.readTrayBadge(shot.bitmap, it) } }
            val slot1 = badges?.get(0).orEmpty()
            val slot2 = badges?.get(1).orEmpty()
            val counts = AttackPlanner.badgeCount(slot1) to AttackPlanner.badgeCount(slot2)
            val read = AttackPlanner.trayLayout(slot1, slot2)
            val asRead = AttackPlanner.forTray(profile, read, eventMode = withEventTroops)
            var shifted: TrayCheck.Report? = null
            var troop = false
            val heroes = if (cap == null || !heroCheckDue(counts.first)) {
                troop = read == AttackPlanner.TrayLayout.EVENT && counts == troopInSlot2Counts
                null
            } else {
                heroSlotsJudged = true
                val bandReads = HashMap<Region, List<String>>()
                timer.step("heroBadges") {
                    val report = judgeHeroes(asRead.profile, read, cap.bitmap, bandReads)
                    // An event card that did not read leaves every hero slot one card left of its
                    // hero, so a count there is judged one card right as well before it stops a run.
                    if (read != AttackPlanner.TrayLayout.EVENT && TrayCheck.wrongHero(report) != null) {
                        shifted = runCatching { AttackPlanner.withEventCard(profile) }.getOrNull()
                            ?.let { judgeHeroes(it, read, cap.bitmap, bandReads) }
                    }
                    // A troop of x5 or more right of the main troop reads as an event card in front.
                    val plain = if (read == AttackPlanner.TrayLayout.EVENT && TrayCheck.wrongHero(report) != null) {
                        judgeHeroes(profile, AttackPlanner.TrayLayout.PLAIN, cap.bitmap, bandReads)
                    } else null
                    troop = plain != null && TrayCheck.troopInSlot2(report, plain)
                    if (troop) {
                        shifted = report
                        plain
                    } else report
                }.also { troopInSlot2Counts = counts.takeIf { troop } }
            }
            val layout = if (troop) AttackPlanner.TrayLayout.PLAIN else read
            val tray = if (troop) AttackPlanner.forTray(profile, layout, eventMode = withEventTroops) else asRead
            return TrayRead(badges, layout, tray, heroes, shifted, troop)
        } finally {
            cap?.bitmap?.recycle()
        }
    }

    /** Each judged hero slot as name=badge/saturation/verdict, for event_card_detected's heroes. */
    private fun heroSummary(report: TrayCheck.Report): String = report.slots.joinToString(" ") {
        "${it.name}=${it.badge ?: "-"}/${it.saturation.roundToInt()}/${it.verdict.level.name.lowercase()}"
    }

    /** [tray]'s hero slots judged off [bitmap]; a band already in [bandReads] is not read again. */
    private suspend fun judgeHeroes(
        tray: PhoneProfile,
        layout: AttackPlanner.TrayLayout,
        bitmap: Bitmap,
        bandReads: MutableMap<Region, List<String>>
    ): TrayCheck.Report =
        TrayCheck.heroSlots(tray, layout, { band -> bandReads.getOrPut(band) { screenReader.readTrayBadge(bitmap, band) } }) {
            ImageMatcher.meanSaturation(bitmap, it)
        }

    /**
     * Whether this attack judges its hero slots as well. Four more badge bands are sixteen more
     * recogniser passes, about 1.2 to 2.1 s at the 71 to 130 ms a pass took in October's tray
     * reads, so they are read on the run's first attack and whenever slot 1's count is not the
     * last attack's. Blood's army change of 14 October took slot 1 from x44 to x10. A tray that
     * changes without moving slot 1 is left to [countHeroLandings].
     */
    private fun heroCheckDue(slot1Count: Int?): Boolean {
        val due = !heroSlotsJudged || slot1Count != lastSlot1Count
        lastSlot1Count = slot1Count
        return due
    }

    /**
     * Leaves a scout whose tray is not the one calibrated before a single troop is down, and
     * stops the run once home. [slot] is the hero slot that showed a count; it is read again
     * off the next frame so one misread frame cannot end a run, and false says that look saw
     * no count and the attack goes ahead. [eventCard]: the tray was read as an event tray, which
     * the stop reason says.
     *
     * End Battle is found as a surrender finds it, by its word or the calibrated end_battle point
     * in the button's corner. With nothing on the ground it goes straight home without asking
     * (audit-evidence/clash-18647, 10-after-endbattle), so it may be sent again while the scout
     * is still up.
     */
    private suspend fun refuseTray(slot: TrayCheck.Slot, tray: PhoneProfile, runToken: Long, eventCard: Boolean): Boolean {
        val reason = TrayCheck.trayMismatch(AppLog.runContextSnapshot().currentAccountName, slot, eventCard)
        for (look in 1..MAX_SURRENDER_MISSES) {
            val cap = service.transport.capture(runToken)
            val state: ScreenState
            val target: Point?
            try {
                val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
                state = obs.state
                if (look == 1) {
                    val raw = screenReader.readTrayBadge(cap.bitmap, AttackPlanner.badgeBand(slot.roi))
                    val again = TrayCheck.slot(tray, slot.name, slot.roi, raw, ImageMatcher.meanSaturation(cap.bitmap, slot.roi))
                    val confirmed = again.verdict.level == TrayCheck.Level.BAD
                    AppLog.log("tray_mismatch", "slot" to slot.name, "raw" to slot.raw.joinToString(","),
                        "verdict" to slot.verdict.text, "rawAgain" to raw.joinToString(","),
                        "confirmed" to confirmed, "state" to state.name)
                    if (!confirmed) return false
                }
                target = if (state == ScreenState.SCOUT || state == ScreenState.BATTLE) {
                    surrenderTarget(obs.boxes, obs.viewport, profile.stateAnchors["end_battle"])
                } else null
            } finally {
                cap.bitmap.recycle()
            }
            if (target == null) continue
            service.overlay?.updateStatus("Leaving: the tray does not match its setup")
            // A held End Battle did not go out; the scout is read again for the next one.
            if (!sentOrHeld { service.transport.tapButton(target, runToken, afterPause = { battleStillUp(runToken) }) }) continue
            transitionTo(setOf(ScreenState.HOME), runToken, timeoutSeconds = 30, from = state, retap = target)
            throw StopException(reason)
        }
        throw StopException("$reason End Battle was not found, so the scout was left on screen.")
    }

    private suspend fun executeDeployment(session: Session, runToken: Long) {
        deployedAt = null
        val timer = StepTimer(SystemClock::elapsedRealtime)
        deployTimer = timer
        // What was being placed when the battle left, for battle_left.
        var placing = "setup"
        try {
            // The tray decides whether the event card is there, not the run mode: a plain run
            // during an event shifts past it, and an event run after it has gone does not shift.
            val read = timer.step("trayRead") { readTray(runToken, timer) }
            val slot1 = read.badges?.get(0).orEmpty()
            val slot2 = read.badges?.get(1).orEmpty()
            val rawBadges = read.badges?.joinToString(" / ") { it.joinToString(",") } ?: "uncalibrated"
            val layout = read.layout
            val tray = read.tray
            if (layout == AttackPlanner.TrayLayout.AMBIGUOUS) {
                AppLog.log("event_card_ambiguous", "slot1" to slot1.joinToString(","),
                    "slot2" to slot2.joinToString(","), "decision" to tray.decision)
            }
            if (read.troopInSlot2) {
                AppLog.log("event_card_ruled_out", "slot1" to AttackPlanner.badgeCount(slot1),
                    "slot2" to AttackPlanner.badgeCount(slot2), "heroCheck" to (read.heroes != null))
            }
            val troopDrops = AttackPlanner.dropCounts(layout, slot1, slot2)
            AppLog.log("event_card_detected", "slot1" to AttackPlanner.badgeCount(slot1),
                "slot2" to AttackPlanner.badgeCount(slot2),
                "layout" to layout.name.lowercase(), "eventMode" to withEventTroops,
                "decision" to tray.decision, "raw" to rawBadges,
                "valkyrieDrops" to troopDrops.valkyries,
                "eventDrops" to troopDrops.event.takeIf { tray.withEventTroops },
                "heroes" to read.heroes?.let { heroSummary(it) + read.shifted?.let { s -> " | one card right " + heroSummary(s) }.orEmpty() })
            // A hero slot showing a count means the hero groups would tap troop cards: Blood's army
            // change of 14 October left every hero in the tray for two hours that way.
            read.heroes?.let { TrayCheck.wrongHero(it, read.shifted) }
                ?.let { refuseTray(it, tray.profile, runToken, eventCard = layout == AttackPlanner.TrayLayout.EVENT) }
            timer.step("zoomOut") { zoomOutForDeployment(runToken) }
            val deployProfile = tray.profile
            val plan = AttackPlanner.buildDeployment(deployProfile, withEventTroops = tray.withEventTroops,
                troopDrops = troopDrops)
            if (tray.withEventTroops) AppLog.log("event_attack_planned", "groups" to plan.joinToString(",") { it.name })
            session.deployed()

            // Each spell card is read once while it is still stocked, because a later reading is
            // only believed when it moved from this one. A mis-calibrated slot reports one constant
            // saturation whether or not the card is spent, and a constant is not stock. The badge
            // count read here is what a cast that leaves stock behind is measured against.
            placing = "spell_baselines"
            spellBaselines = cardReadings(plan.filter { it.isSpell && it.cardRoi != null }, runToken) { it.isSpell }

            // Whether the screen was just looked at for a reward banner, by the read that closed the
            // phase before. Nothing can be offered before the first troop starts the battle.
            var justChecked = true
            val abilities = HeroAbilities()
            val deferred = mutableListOf<DeploymentGroup>()
            val phaseDetails = mutableListOf<String>()
            for (phase in AttackPlanner.phases(plan)) {
                placing = phase.joinToString("+") { it.name }
                // A hold here may outlast the battle, as one before a deploy tap may.
                if (service.awaitClearToAct("battle phase")) battleStillUp(runToken)
                val phaseStartedAt = timer.elapsedMs()
                val attemptsBefore = timer.count("verifyAttempts")
                service.runGuard.checkCanAct(
                    token = runToken,
                    foregroundPackage = service.getActiveForegroundPackage(),
                    isUnlocked = service.isDeviceUnlocked(),
                    currentViewport = service.currentDisplayViewport()
                )

                if (!justChecked) {
                    timer.step("reward") { settleRewardsIfOffered(runToken) }
                }

                // Everything in the phase goes down before anything is read back, so the four
                // heroes land within half a second of each other.
                for (group in phase) {
                    timer.step("gestures") { deployGroup(group, group.drops, runToken) }
                    abilities.dropped(group, SystemClock.elapsedRealtime())
                }

                // An optional card's check waits until everything is down: the heroes are not
                // held up while its retries run. It is still verified, just last. A hero card is
                // never retried: once landed it is the ability button and stays coloured (see
                // CardRetry). Whether it landed is read off its health bar instead, on the frame
                // the abilities clear a reward banner on, and no hero card is tapped to find out.
                val (optional, verifiable) = phase.filter { it.cardRoi != null && !it.isHero }.partition { it.isOptional }
                deferred += optional
                justChecked = verifyCardsSpent(verifiable, runToken)
                if (phase.any { it.isHero }) {
                    var landed = emptySet<String>()
                    timer.step("abilities") {
                        abilities.fire(
                            now = SystemClock::elapsedRealtime,
                            wait = { delay(it) },
                            clearBanner = { landed = heroLandings(phase.filter { it.isHero }, runToken) },
                            tap = { hero, card, delayMs ->
                                // A held tap fired nothing, and the hero already counts as used.
                                val sent = sentOrHeld { service.transport.tapButton(card, runToken, afterPause = { battleStillUp(runToken) }) }
                                // A hero not seen on the field may still be in its card, so this tap
                                // only selected it: nothing says an ability went off.
                                if (sent && hero in landed) AppLog.log("hero_ability_used", "hero" to hero, "delayMs" to delayMs)
                            }
                        )
                    }
                }
                phaseDetails += "phase=" + phase.joinToString("+") { it.name } +
                    ":verify=" + (timer.count("verifyAttempts") - attemptsBefore) +
                    ":ms=" + (timer.elapsedMs() - phaseStartedAt)
            }
            if (deferred.isNotEmpty()) {
                placing = "deferred"
                val deferredStartedAt = timer.elapsedMs()
                val attemptsBefore = timer.count("verifyAttempts")
                verifyCardsSpent(deferred, runToken)
                phaseDetails += "deferred=" + deferred.joinToString("+") { it.name } +
                    ":verify=" + (timer.count("verifyAttempts") - attemptsBefore) +
                    ":ms=" + (timer.elapsedMs() - deferredStartedAt)
            }
            awaitBattleAfterDeployment(timer, runToken)
            try {
                AppLog.log("deploy_timing", *deployTimingSummary(timer, phaseDetails).toTypedArray())
            } catch (e: Exception) {
                AppLog.log("deploy_timing_failed", "error" to e.message)
            }
        } catch (left: BattleLeft) {
            // Before a troop is down nothing is spent, and the restart reads the screen afresh.
            if (session.phase != ScreenState.BATTLE) {
                throw StopException("Unexpected transition screen: ${left.state} before the deployment")
            }
            // The rest of the plan is not placed onto whatever replaced the battle. The battle
            // may still be up behind it, or over, so the result is waited for as after any plan.
            AppLog.log("battle_left", "state" to left.state.name, "phase" to placing)
            awaitBattleAfterDeployment(timer, runToken)
        } finally {
            deployTimer = null
        }
    }

    /** Readies the early end and waits for the battle the deployment opened, or what ended it. */
    private suspend fun awaitBattleAfterDeployment(timer: StepTimer, runToken: Long) {
        deployedAt = SystemClock.elapsedRealtime()
        surrenderTracker = SurrenderTracker(MAX_SURRENDER_TRIES, MAX_SURRENDER_MISSES)
        damageTarget = pickDamageTarget(earlyEnd)
        damageProof.reset()
        stallDetector = if (earlyEnd.stallShadow || earlyEnd.onStall) StallDetector() else null

        timer.step("transition") {
            transitionTo(
                setOf(ScreenState.BATTLE, ScreenState.REWARD, ScreenState.RESULT), runToken,
                timeoutSeconds = 30, from = ScreenState.SCOUT, confirmedExits = BATTLE_EXITS
            )
        }
    }

    /**
     * Pinches the attack camera all the way out before a single troop is placed.
     *
     * Troops may only be dropped on ground outside the defender's red line, and at the zoom the
     * attack view opens on, none of that ground is on the screen: five taps spread to the corners
     * of a live scout screen placed nothing at all, while the spells - which may be thrown
     * anywhere, buildings included - went down every time. That is what left forty Valkyries in
     * their card through eight attacks on 17 September.
     */
    private suspend fun zoomOutForDeployment(runToken: Long) {
        val vp = profile.viewport
        val center = Point(vp.left + vp.width / 2, vp.top + vp.height / 2)
        // A held pinch did not go out and is sent again after the pause, so the camera ends as far
        // out as before. Each hold takes another pause, and the tries are bounded (UO-21 review,
        // 15 October).
        var pinches = 0
        var tries = 0
        while (pinches < ZOOM_OUT_PINCHES && tries++ < ZOOM_OUT_PINCHES * 2) {
            val sent = sentOrHeld {
                service.transport.pinch(
                    center = center,
                    startRadius = ZOOM_START_RADIUS,
                    endRadius = ZOOM_END_RADIUS,
                    durationMs = ZOOM_DURATION_MS,
                    runToken = runToken,
                    // After a pause the scout may be gone: a pinch is then not sent at all.
                    afterPause = { battleStillUp(runToken) }
                )
            }
            if (sent) pinches++
            delay(250)
        }
        // The camera glides to a stop rather than snapping, and a drop that lands while it is
        // still moving lands somewhere other than where it was aimed.
        delay(700)
        AppLog.log("attack_zoomed_out", "pinches" to pinches)
    }

    /**
     * Selects a card and places everything on it.
     *
     * A hero's card is not tapped again here after the hero lands: that tap fires the ability,
     * which [HeroAbilities] does once, on its own schedule. The drop taps going out do not say
     * the hero landed: on Blood's wrong tray of 14 October they selected troop cards for two
     * hours. [heroLandings] reads the landing off the cards and logs hero_deployed.
     */
    private suspend fun deployGroup(group: DeploymentGroup, drops: List<Point>, runToken: Long) {
        val afterPause: suspend () -> Unit = { battleStillUp(runToken) }
        // A held gesture did not go out, and the next one waits out the pause and looks first.
        // A held select places nothing, since its drops would land the card selected before; a
        // checked card is found unspent and retried (UO-21 review, 15 October).
        if (!sentOrHeld { service.transport.tapButton(group.selectPoint, runToken, afterPause) }) return
        delay(AttackPacing.selectWaitMs())
        for (drop in drops) {
            sentOrHeld { service.transport.tap(drop, runToken, afterPause) }
            delay(AttackPacing.dropGapMs())
        }
    }

    /**
     * Which of [heroes] left the tray, read off one frame with no reward banner on it. A landed
     * hero's card rises and carries the hero's green health bar ([HeroCards]); a hero still in
     * the tray has none. Logs hero_deployed for each landed hero and hero_card_unverified for the
     * rest, then counts the attack toward the backstop in [countHeroLandings].
     *
     * It stands in for [settleRewardsIfOffered] as [HeroAbilities]'s banner clearing, so the frame
     * is the one that clearing already took, four seconds after the King's drops and a second or
     * more after the Champion's: no capture is added on an attack without a banner.
     */
    private suspend fun heroLandings(heroes: List<DeploymentGroup>, runToken: Long): Set<String> {
        for (look in 1..MAX_REWARD_OFFERS) {
            val captured = deployTimer.timed("capture") { service.transport.capture(runToken) }
            val state: ScreenState
            val bars: Map<String, HeroCards.Bar?>
            try {
                state = deployTimer.timed("ocr") {
                    held(screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs))
                }.state
                bars = if (state == ScreenState.REWARD) emptyMap() else heroes.associate { group ->
                    group.name to group.cardRoi?.let { HeroCards.bar(it) { x, y -> pixel(captured.bitmap, x, y) } }
                }
            } finally {
                captured.bitmap.recycle()
            }
            if (state == ScreenState.REWARD) {
                deployTimer.timed("reward") { handleRewardOffer(runToken) }
                continue
            }
            battleStillOn(state)
            for (group in heroes) {
                val bar = bars[group.name]
                if (bar?.landed == true) {
                    AppLog.log("hero_deployed", "hero" to group.name, "drops" to group.drops.size, "barRows" to bar.rows)
                } else {
                    AppLog.log("hero_card_unverified", "hero" to group.name, "barRows" to bar?.rows,
                        "cover" to bar?.cover?.let { (it * 100).roundToInt() }, "state" to state.name)
                }
            }
            val landed = bars.filterValues { it?.landed == true }.keys
            // Only a battle frame with a calibrated card to read says the heroes stayed home.
            if (state == ScreenState.BATTLE && bars.values.any { it != null }) countHeroLandings(landed.size)
            return landed
        }
        for (group in heroes) AppLog.log("hero_card_unverified", "hero" to group.name, "state" to ScreenState.REWARD.name)
        return emptySet()
    }

    /** [bitmap]'s pixel at [x], [y], or null off its edge. */
    private fun pixel(bitmap: Bitmap, x: Int, y: Int): Int? =
        if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null

    /**
     * The backstop behind [refuseTray], which looks only when slot 1's count changes:
     * [HEROES_STAYED_ATTACKS] attacks in a row on which no calibrated hero card showed its hero
     * landed stop the run once the battle under way is home. One hero landing resets it, so a
     * hero away upgrading, whose card is not in the tray, does not count against the others.
     */
    private fun countHeroLandings(landed: Int) {
        heroesStayed = if (landed > 0) 0 else heroesStayed + 1
        if (heroesStayed >= HEROES_STAYED_ATTACKS) {
            heroesStayedStop = TrayCheck.heroesStayed(AppLog.runContextSnapshot().currentAccountName, heroesStayed)
        }
    }

    /**
     * The look a battle-side tap takes when the run was paused or held just before it: the tap's
     * point was read before the pause. [held] throws on the takeover and server dialogs, and a
     * reward banner is cleared as [settleRewardsIfOffered] clears it. Up to two frames 500 ms
     * apart are read; with neither the battle nor its scout on them, [BattleLeft] is thrown and
     * the tap is not sent. After a pause an unread frame may be anything a person opened.
     */
    private suspend fun battleStillUp(runToken: Long) {
        var state = ScreenState.UNKNOWN
        for (read in 1..2) {
            if (read > 1) delay(500)
            val shot = service.transport.capture(runToken)
            state = try {
                held(screenReader.observe(shot.bitmap, profile.viewport, shot.timestampMs)).state
            } finally {
                shot.bitmap.recycle()
            }
            AppLog.log("battle_checked_after_pause", "state" to state.name, "read" to read)
            if (state == ScreenState.REWARD) state = handleRewardOffer(runToken)
            if (TapAfterHold.battle(state, afterPause = true) == TapAfterHold.Battle.GO) return
        }
        throw BattleLeft(state)
    }

    /**
     * Throws [BattleLeft] when a frame read between drops shows neither the battle nor its scout.
     * CardRetry's drops would otherwise go out onto whatever replaced it, the village included.
     */
    private fun battleStillOn(state: ScreenState) {
        if (TapAfterHold.battle(state, afterPause = false) == TapAfterHold.Battle.LEFT) throw BattleLeft(state)
    }

    /**
     * Checks that every card in a phase is spent, placing the leftovers again where one is not.
     * Returns whether it looked at the screen, which also cleared any reward banner on it.
     *
     * Each look is one capture for the whole phase. The banner check and every card's reading
     * used to be a capture apiece, and the screenshot service allows one every 1.1 seconds, so a
     * clean attack spent seventeen of them - about twenty seconds - confirming what one frame
     * per phase shows.
     */
    private suspend fun verifyCardsSpent(groups: List<DeploymentGroup>, runToken: Long): Boolean {
        if (groups.isEmpty()) return false
        // A card greys a moment after its last troop lands, and one read before that would send
        // the retry drops out under whichever card the game selected next.
        deployTimer.timed("cardSettle") { delay(CARD_SETTLE_MS) }
        var attempt = 0
        return CardRetry.verify(
            groups = groups,
            baselines = spellBaselines,
            read = { pending ->
                attempt++
                deployTimer?.mark("verifyAttempts")
                cardReadings(pending, runToken) { CardRetry.readsBadge(it, spellBaselines[it.name]) }
            },
            giveUp = { group, reading, at ->
                if (group.isSpell) {
                    val baseline = spellBaselines[group.name]
                    AppLog.log("spell_card_unverified", "spell" to group.name,
                        "saturation" to reading.saturation,
                        "baseline" to baseline?.saturation,
                        "badge" to reading.badge,
                        "baselineBadge" to baseline?.badge,
                        "attempt" to at)
                } else {
                    AppLog.log("troop_card_partially_unspent", "troop" to group.name, "saturation" to reading.saturation)
                }
            },
            onSpent = { group, reading ->
                if (group.isSpell) {
                    val baseline = spellBaselines[group.name]
                    AppLog.log("spell_card_spent", "spell" to group.name,
                        "by" to if (reading.saturation <= ImageMatcher.SPENT_CARD_SATURATION_THRESHOLD) "grey" else "badge",
                        "saturation" to reading.saturation,
                        "baseline" to baseline?.saturation,
                        "badge" to reading.badge,
                        "baselineBadge" to baseline?.badge,
                        "attempt" to attempt)
                }
            },
            retry = { pending ->
                // Retry somewhere else. A point the game refused once it will refuse again,
                // so the leftovers go to a fresh sample rather than back to the same four.
                deployTimer.timed("retryDrops") {
                    for (group in pending) {
                        AppLog.log("card_retry", "group" to group.name, "hero" to group.isHero,
                            "spell" to group.isSpell, "attempt" to attempt)
                        deployGroup(group, group.drops.shuffled().take(RETRY_DROPS), runToken)
                    }
                }
                deployTimer?.mark("retryGroups", pending.size)
                deployTimer.timed("cardSettle") { delay(CARD_SETTLE_MS) }
            }
        )
    }

    /**
     * Each card's saturation, read off one frame with no reward banner on it.
     *
     * A reward banner takes the taps meant for the game, which is how the Royal Champion was left
     * standing in her card through two live attacks. It is also drawn straight over the card
     * tray, and a card read through it measures the banner rather than the card: on 18 September
     * it put a king who was already on the field at 182 against a threshold of 60, and each retry
     * then tapped his card again - which, once a hero is out, is his ability button. So the frame
     * the cards are read from is the frame that was checked for a banner, and a banner is cleared
     * and the tray read again from a fresh frame.
     */
    private suspend fun cardReadings(
        groups: List<DeploymentGroup>,
        runToken: Long,
        readBadge: (DeploymentGroup) -> Boolean
    ): Map<String, CardReading> {
        for (look in 1..MAX_REWARD_OFFERS) {
            val captured = deployTimer.timed("capture") { service.transport.capture(runToken) }
            try {
                val obs = deployTimer.timed("ocr") {
                    held(screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs))
                }
                if (obs.state != ScreenState.REWARD) {
                    battleStillOn(obs.state)
                    return deployTimer.timed("ocr") {
                        groups.associate { group ->
                            val roi = group.cardRoi!!
                            // A spell card with stock left stays coloured after a cast; only its
                            // "xNN" count shows the cast (Blood's x11 card, 8 October).
                            val badge = if (readBadge(group)) {
                                AttackPlanner.spellBadgeCount(screenReader.readTrayBadge(captured.bitmap, AttackPlanner.badgeBand(roi)))
                            } else null
                            group.name to CardReading(ImageMatcher.meanSaturation(captured.bitmap, roi), badge)
                        }
                    }
                }
            } finally {
                captured.bitmap.recycle()
            }
            deployTimer.timed("reward") { handleRewardOffer(runToken) }
        }
        throw StopException("A reward banner stayed over the card tray")
    }

    /**
     * Settles every reward banner waiting on the screen. Another offer is often queued behind
     * the one just taken and is already drawn by the time it lands, so they are worked through
     * one at a time rather than treated as a surprise.
     */
    /**
     * Surrenders the battle on screen once [earlyEnd] says it is no longer worth waiting for.
     * True when the battle has been left; false leaves it running, including when the button or
     * the dialog's Okay could not be read.
     */
    private suspend fun endBattleEarlyIfDue(runToken: Long): Boolean {
        if (!earlyEnd.enabled || !earlyEnd.anyTrigger || surrenderTracker.exhausted) return false
        val since = deployedAt?.let { SystemClock.elapsedRealtime() - it } ?: return false
        val cap = service.transport.capture(runToken)
        val target: Point
        val reason: EarlyEndReason
        try {
            val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            if (obs.state != ScreenState.BATTLE) return false
            val secondsLeft = BattleClock.secondsLeft(obs.rawText)
            // Each loot row costs several recogniser passes, so they are read only once they can decide.
            val lootLeft = if (since < MIN_MS_BEFORE_LOOT_END) emptyMap() else
                earlyEnd.lootLeftBelow.keys.mapNotNull { resource ->
                    profile.scoutLootRois[resource]?.let { roi ->
                        screenReader.readScoutLoot(cap.bitmap, roi, BATTLE_LOOT_UNREADABLE)?.let { resource to it }
                    }
                }.toMap()
            val damage = if (earlyEnd.atDamage) damageProof.admit(BattleDamage.overallPercent(obs.rawText)) else null
            reason = earlyEndReason(earlyEnd, since, secondsLeft, lootLeft, damage, damageTarget,
                stalled = stallDetector?.decision != null) ?: return false
            target = surrenderTracker.locate(obs.boxes, obs.viewport, profile.stateAnchors["end_battle"]) ?: run {
                AppLog.log("early_end_no_button", "reason" to reason.key,
                    "misses" to surrenderTracker.misses, "text" to obs.rawText.take(200))
                return false
            }
            AppLog.log("early_end", "reason" to reason.key, "sinceDeployMs" to since,
                "secondsLeft" to secondsLeft, "damagePercent" to damage, "damageTarget" to damageTarget,
                "lootLeft" to lootLeft.entries.joinToString(",") { "${it.key.key}=${it.value}" },
                "try" to surrenderTracker.tries, "x" to target.x, "y" to target.y)
        } finally {
            cap.bitmap.recycle()
        }

        service.overlay?.updateStatus("Ending battle early")
        // After a pause Surrender goes only while the battle is still up; one that has left is
        // over already, and a tap held past its looks leaves the battle to the next check.
        try {
            if (!sentOrHeld { service.transport.tapButton(target, runToken, afterPause = { battleStillUp(runToken) }) }) {
                return false
            }
        } catch (left: BattleLeft) {
            AppLog.log("battle_left", "state" to left.state.name, "phase" to "surrender")
            return true
        }
        var dialogUp = false
        for (frame in 0 until CONFIRM_FRAMES) {
            delay(500)
            val shot = service.transport.capture(runToken)
            val okay: Point
            try {
                val obs = held(screenReader.observe(shot.bitmap, profile.viewport, shot.timestampMs))
                // End Battle with nothing on the ground leaves at once, without asking.
                if (obs.state == ScreenState.RESULT || obs.state == ScreenState.HOME) return true
                dialogUp = obs.state == ScreenState.UNKNOWN
                // Over a battle the quit prompt can read BATTLE, and its Okay is where this one's
                // is; it is never pressed (UO-21 review). Back closes it if it reads UNKNOWN.
                if (Policy.showsQuitPrompt(obs.rawText)) {
                    AppLog.log("early_end_quit_prompt", "frame" to frame, "text" to obs.rawText.take(200))
                    continue
                }
                val found = confirmSurrenderTarget(obs.boxes, obs.viewport)
                if (found == null) {
                    AppLog.log("early_end_confirm_unread", "frame" to frame, "text" to obs.rawText.take(200))
                    continue
                }
                if (Policy.spendsPremium(obs.rawText)) {
                    AppLog.log("premium_dialog_refused", "where" to "early_end_confirm", "text" to obs.rawText.take(200))
                    throw StopException("Refused a dialog that spends gems or money: ${obs.rawText.take(120)}")
                }
                okay = found
            } finally {
                shot.bitmap.recycle()
            }
            // Held by a pause, Okay was not sent, and the dialog is read again on the next frame.
            if (!sentOrHeld { service.transport.tapButton(okay, runToken) }) continue
            AppLog.log("early_end_confirmed", "x" to okay.x, "y" to okay.y)
            transitionTo(
                setOf(ScreenState.RESULT, ScreenState.REWARD), runToken, timeoutSeconds = 30,
                from = ScreenState.BATTLE, confirmedExits = BATTLE_EXITS
            )
            return true
        }
        // A dialog whose Okay cannot be read would hold the run on an unrecognised screen, so it
        // is closed with Back and the battle is left to run out.
        if (dialogUp) {
            // After a hold the dialog read before it may be gone, and Back would go to whatever
            // is up now. A short hold leaves it up, and then Back still closes it. The shade can
            // come down again while the frame is read, so the gate is asked until it lets go.
            while (service.awaitClearToAct("early-end back")) {
                val cap = service.transport.capture(runToken)
                val state = try {
                    held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs)).state
                } finally {
                    cap.bitmap.recycle()
                }
                if (state != ScreenState.UNKNOWN) {
                    AppLog.log("early_end_back_skipped_after_hold", "state" to state.name)
                    return false
                }
            }
            service.runGuard.checkCanAct(
                token = runToken,
                foregroundPackage = service.getActiveForegroundPackage(),
                isUnlocked = service.isDeviceUnlocked(),
                currentViewport = service.currentDisplayViewport()
            )
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            AppLog.log("early_end_dialog_closed")
        }
        return false
    }

    /**
     * Feeds the battle's stall watcher one reading per wait and logs what it decided. Under
     * [EarlyEndRules.onStall] the latched decision is what [endBattleEarlyIfDue] surrenders on;
     * off, it is a shadow only and the battle still ends in that check or not at all. The
     * damage comes off the reading already in hand; the loot rows cost one capture a wait, and
     * are read only when the profile has them calibrated.
     */
    private suspend fun feedStallShadow(runToken: Long, obs: local.clashfarmer.recognition.Observation) {
        val detector = stallDetector ?: return
        val since = deployedAt?.let { SystemClock.elapsedRealtime() - it } ?: return
        val damage = BattleDamage.overallPercent(obs.rawText)
        // The 8 October logs could not say why Blood's damage missed on 19 of 25 samples. The result
        // screen sweeping in is no miss, and is left out so the count stays the reader's.
        if (damage == null && !BattleDamage.resultWipe(obs.rawText)) AppLog.log("stall_damage_unreadable", "text" to obs.rawText.takeLast(80))
        val loot = if (profile.scoutLootRois.isEmpty()) emptyMap() else stallLoot(runToken)
        val decision = detector.feed(since, damage, loot) ?: return
        AppLog.log("stall_would_surrender",
            "rule" to decision.rule.key,
            "sinceDeployMs" to since,
            "windowStartMs" to decision.windowStartMs,
            "windowMs" to decision.windowMs,
            "damagePercent" to damage,
            "lootLeft" to loot.entries.joinToString(",") { "${it.key.key}=${it.value}" },
            "samples" to detector.samples.size,
            "readable" to detector.readableCount,
            "unreadable" to (detector.samples.size - detector.readableCount))
    }

    /** The scout's loot rows off one fresh frame; nothing when the frame has left the battle. */
    private suspend fun stallLoot(runToken: Long): Map<Resource, Long> {
        val cap = service.transport.capture(runToken)
        return try {
            val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            if (obs.state != ScreenState.BATTLE) emptyMap() else
                profile.scoutLootRois.entries.mapNotNull { (resource, roi) ->
                    screenReader.readScoutLoot(cap.bitmap, roi, BATTLE_LOOT_UNREADABLE)?.let { resource to it }
                }.toMap()
        } finally {
            cap.bitmap.recycle()
        }
    }

    /** What the stall watcher was fed, on one line, so a stall window can be replayed offline. */
    private fun logStallTrajectory() {
        val detector = stallDetector ?: return
        stallDetector = null
        if (detector.samples.isEmpty()) return
        AppLog.log("stall_trajectory",
            "samples" to detector.samples.size,
            "readable" to detector.readableCount,
            "decision" to detector.decision?.rule?.key,
            "trajectory" to detector.trajectory())
    }

    private suspend fun handleRewardOffer(runToken: Long): ScreenState {
        for (offer in 1..MAX_REWARD_OFFERS) {
            val left = takeRewardOffer(runToken)
            if (left != ScreenState.REWARD) return left
        }
        throw StopException("Reward offers did not stop coming after $MAX_REWARD_OFFERS")
    }

    /**
     * Takes a readable resource, or waits out an offer this bot cannot read. The banner also
     * offers troop cards named after whatever event is running that week, which no list can
     * cover; those are never tapped, because the offer withdraws itself and waiting sends no
     * input at all. Returns the screen the offer left behind.
     */
    private suspend fun takeRewardOffer(runToken: Long): ScreenState {
        val captured = deployTimer.timed("capture") { service.transport.capture(runToken) }
        val obs = deployTimer.timed("ocr") {
            held(screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs))
        }
        if (obs.state != ScreenState.REWARD) return obs.state

        // Walls are paid for in gold and elixir, so those come first.
        for (resource in listOf("gold", "elixir", "dark elixir")) {
            val card = obs.boxes.firstOrNull { normalizedLabel(it.text) == resource } ?: continue
            // Held by a pause, the card was not tapped: the banner is read again from a fresh frame.
            if (!sentOrHeld { service.transport.tapButton(card.center, runToken) }) return ScreenState.REWARD
            AppLog.log("reward_taken", "resource" to resource,
                "x" to card.center.x, "y" to card.center.y)
            return transitionTo(
                setOf(ScreenState.BATTLE, ScreenState.RESULT, ScreenState.REWARD),
                runToken, timeoutSeconds = 15, from = ScreenState.REWARD,
                confirmedExits = BATTLE_EXITS
            )
        }

        AppLog.log("reward_declined", "text" to obs.rawText.take(200))
        return transitionTo(
            setOf(ScreenState.BATTLE, ScreenState.RESULT),
            runToken, timeoutSeconds = 30, from = ScreenState.REWARD,
            confirmedExits = BATTLE_EXITS
        )
    }

    /** A reward card's own label, with the punctuation and case the reading may carry. */
    private fun normalizedLabel(text: String): String =
        text.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Clears a reward banner if one is up. The banner is drawn over the battle and takes the
     * taps meant for it, which is how a hero was left standing on its card during the first
     * live attack.
     */
    private suspend fun settleRewardsIfOffered(runToken: Long) {
        val captured = deployTimer.timed("capture") { service.transport.capture(runToken) }
        val obs = deployTimer.timed("ocr") {
            held(screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs))
        }
        if (obs.state == ScreenState.REWARD) {
            deployTimer.timed("reward") { handleRewardOffer(runToken) }
        } else {
            battleStillOn(obs.state)
        }
    }

    /**
     * Every reading passes through here. When another device opens the village, Clash covers it
     * with Connection lost and Reload. The attempt ends; unattended farming waits before
     * reclaiming it. A generic server disconnect or the idle dialog is not proof of another
     * device playing: that attempt ends too, and the restart's account check presses Reload once
     * without spending the restart budget. A forced update stops the run; Update is never tapped.
     */
    private fun held(obs: local.clashfarmer.recognition.Observation): local.clashfarmer.recognition.Observation {
        if (Policy.villageTakenElsewhere(obs.rawText)) {
            val takeover = Policy.anotherDeviceConnected(obs.rawText)
            AppLog.log(if (takeover) "village_taken_elsewhere" else "connection_lost",
                "kind" to "connection_lost", "text" to obs.rawText.take(200))
            throw StopException(if (takeover) Policy.VILLAGE_TAKEN else PopupGuard.SERVER_DIALOG)
        }
        if (obs.state != ScreenState.UNKNOWN) {
            if (dialogueAdvances > 0) {
                // Only the village means the intro is over; any other screen just ends the count.
                AppLog.log(if (obs.state == ScreenState.HOME) "popup_dialogue_cleared" else "popup_dialogue_reset",
                    "advances" to dialogueAdvances, "state" to obs.state.name)
                dialogueAdvances = 0
            }
            return obs
        }
        when (val verdict = PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)) {
            is PopupGuard.Verdict.ServerDialog -> {
                AppLog.log("connection_lost", "kind" to verdict.kind, "text" to obs.rawText.take(200))
                throw StopException(PopupGuard.SERVER_DIALOG)
            }
            PopupGuard.Verdict.UpdateRequired -> {
                AppLog.log("game_update_required", "text" to obs.rawText.take(200))
                throw StopException("${PopupGuard.UPDATE_REQUIRED} (read: ${obs.rawText.take(160)})")
            }
            else -> {}
        }
        return obs
    }

    /**
     * The bubble taps one wait may send, at most [PopupGuard.MAX_DIALOGUE_ADVANCES]. Each wait
     * makes its own and nothing resets it, so a screen flickering between a recognised frame and
     * a dialogue-like one cannot keep a wait tapping and restarting its timeout for ever.
     */
    private inner class DialogueBudget {
        private var taps = 0

        /**
         * Taps the bubble read off a frame taken at [decidedOn] and true, or false when nothing was
         * sent: the budget is spent, or a pause or the frame's age held the tap, which spends
         * nothing and leaves the caller to read again.
         */
        suspend fun advance(verdict: PopupGuard.Verdict.AdvanceDialogue, decidedOn: Long, runToken: Long): Boolean {
            if (taps >= PopupGuard.MAX_DIALOGUE_ADVANCES) {
                if (taps++ == PopupGuard.MAX_DIALOGUE_ADVANCES) {
                    AppLog.log("popup_dialogue_budget_spent", "advances" to PopupGuard.MAX_DIALOGUE_ADVANCES,
                        "text" to verdict.text.take(200))
                }
                return false
            }
            service.overlay?.updateStatus("Tapping through an event dialogue...")
            if (!sentOrHeld { service.transport.tapButton(verdict.tap, runToken, decidedOn = decidedOn) }) return false
            taps++
            dialogueAdvances++
            AppLog.log("popup_dialogue_advanced", "advance" to taps, "x" to verdict.tap.x,
                "y" to verdict.tap.y, "text" to verdict.text.take(200))
            // The same settle a dismissed popup gets before the screen is read again.
            delay(1500)
            return true
        }
    }

    /**
     * Waits out a maintenance break without sending any input, Back included. The break is over
     * only when [MaintenanceWait] says so: the dialog gone on two reads 5 s apart and then a
     * screen the runner recognises, so the splash and the loading clouds are waited on here
     * rather than handed to recovery's Back. The attempt then ends, and the run starts over from
     * the village without spending a restart. A break that outlasts
     * [PopupGuard.MAINTENANCE_MAX_MS] stops the run.
     */
    private suspend fun waitOutMaintenance(runToken: Long, first: local.clashfarmer.recognition.Observation): Nothing {
        val startedAt = System.currentTimeMillis()
        val wait = MaintenanceWait(startedAt)
        var pollMs = PopupGuard.MAINTENANCE_POLL_MS
        var lifting = false
        AppLog.log("maintenance_break", "text" to first.rawText.take(200))
        service.overlay?.updateStatus("Clash is in maintenance; waiting without tapping...")
        while (true) {
            val waited = System.currentTimeMillis() - startedAt
            if (wait.timedOut(System.currentTimeMillis())) {
                AppLog.log("maintenance_timeout", "waitedMs" to waited)
                throw StopException(PopupGuard.maintenanceTimedOut(PopupGuard.MAINTENANCE_MAX_MS / 60_000L))
            }
            delay(pollMs)
            service.awaitResume("Maintenance")
            if (service.runGuard.isStopped()) throw StopException(service.runGuard.stopReason ?: "Run stopped")
            val cap = service.transport.capture(runToken)
            val obs = try {
                screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs)
            } finally {
                cap.bitmap.recycle()
            }
            val verdict = if (obs.state == ScreenState.UNKNOWN) {
                PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)
            } else {
                PopupGuard.Verdict.None
            }
            val read = MaintenanceWait.classify(obs.state, verdict, obs.rawText,
                local.clashfarmer.recognition.reloadTarget(obs.boxes, obs.viewport) != null)
            when (val decision = wait.next(read, System.currentTimeMillis())) {
                MaintenanceWait.Decision.Over -> {
                    AppLog.log("maintenance_over", "waitedMs" to System.currentTimeMillis() - startedAt,
                        "state" to obs.state.name, "text" to obs.rawText.take(200))
                    throw StopException(PopupGuard.MAINTENANCE_OVER)
                }
                is MaintenanceWait.Decision.Wait -> {
                    val nowLifting = read != MaintenanceWait.Read.MAINTENANCE
                    if (nowLifting != lifting) {
                        AppLog.log(if (nowLifting) "maintenance_lifting" else "maintenance_back",
                            "state" to obs.state.name, "text" to obs.rawText.take(200))
                        lifting = nowLifting
                    }
                    pollMs = decision.pollMs
                }
            }
        }
    }

    /**
     * Captures until the screen reads as something. Loading clouds, the victory wipe and a
     * panel still sliding in all read as unrecognized, and handing one of those to the session
     * stops a run that was going fine. No input is sent while waiting.
     */
    private suspend fun observeSettled(runToken: Long, timeoutSeconds: Int = 60): local.clashfarmer.recognition.Observation {
        var deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var held0 = service.heldMs
        var popupTaps = 0
        var shopBacks = 0
        var quitCancels = 0
        var noticeTaps = 0
        val dialogue = DialogueBudget()
        val popupsLogged = mutableSetOf<String>()
        while (true) {
            // A person's Pause stops the reading too, and the wait is not charged to the timeout.
            service.awaitClearToAct("observe")
            val captured = service.transport.capture(runToken)
            val obs = held(screenReader.observe(captured.bitmap, profile.viewport, captured.timestampMs))
            obs.assertFresh(5000)
            // Clash's quit prompt reads as the village, Battle and Build legible behind it, and Back
            // on the bare village opens it (UO-21 review). It is not the village: before the
            // checkpoint and the Attack press it gets Cancel, MAX_POPUP_TAPS times at most, and no
            // popup's tap, and is read again within this wait.
            val quit = ReturnHome.quitPrompt(obs.rawText, obs.boxes)
            // The PC's rating dialog reads as the village too, and gets LATER the same way, as
            // Clash's star bonus notice gets its Okay.
            val notice = if (quit == null) {
                RatePrompt.later(obs.boxes)?.let { RatePrompt.POPUP to it }
                    ?: StarBonusNotice.okay(obs.boxes)?.let { StarBonusNotice.POPUP to it }
            } else null
            if (obs.state != ScreenState.UNKNOWN && quit == null && notice == null) {
                return obs
            }
            if (quit != null && quitCancels < MAX_POPUP_TAPS &&
                service.cancelQuitPrompt(quit.cancel, runToken, obs.timestampMs, "observe", obs.rawText)) {
                quitCancels++
                delay(1500)
                continue
            }
            if (notice != null && noticeTaps < MAX_POPUP_TAPS &&
                sentOrHeld { service.transport.tapButton(notice.second, runToken, decidedOn = obs.timestampMs) }
            ) {
                noticeTaps++
                AppLog.log("popup_dismissed", "popup" to notice.first, "x" to notice.second.x,
                    "y" to notice.second.y, "text" to obs.rawText.take(200))
                delay(1500)
                continue
            }
            // A known popup is closed by its X or its Okay and nothing else; one with nothing safe
            // to tap is waited on, and an unknown one keeps the wait below. A close held by a
            // pause or an old frame is not counted, and the timeout below still applies.
            if (quit == null) when (val verdict = PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)) {
                PopupGuard.Verdict.Maintenance -> waitOutMaintenance(runToken, obs)
                // Each bubble restarts the wait: the taps are bounded, and eight of them can
                // outlast a wait that began on loading clouds.
                is PopupGuard.Verdict.AdvanceDialogue -> if (dialogue.advance(verdict, obs.timestampMs, runToken)) {
                    deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
                    held0 = service.heldMs
                    continue
                }
                is PopupGuard.Verdict.Dismiss -> if (popupTaps < MAX_POPUP_TAPS &&
                    sentOrHeld { service.transport.tapButton(verdict.tap, runToken, decidedOn = obs.timestampMs) }
                ) {
                    popupTaps++
                    AppLog.log("popup_dismissed", "popup" to verdict.popup, "x" to verdict.tap.x,
                        "y" to verdict.tap.y, "text" to obs.rawText.take(200))
                    delay(1500)
                    continue
                }
                // The store and a priced offer get Back and nothing else.
                PopupGuard.Verdict.Shop -> if (shopBacks < MAX_SHOP_BACKS && leaveShop(obs, verdict, "observe", runToken)) {
                    shopBacks++
                    continue
                }
                is PopupGuard.Verdict.RefusedPremium -> {
                    if (popupsLogged.add(verdict.popup)) {
                        AppLog.log("popup_blocked_premium", "popup" to verdict.popup, "text" to obs.rawText.take(200))
                    }
                    if (shopBacks < MAX_SHOP_BACKS && leaveShop(obs, verdict, "observe", runToken)) {
                        shopBacks++
                        continue
                    }
                }
                is PopupGuard.Verdict.Wait -> if (popupsLogged.add(verdict.popup)) {
                    AppLog.log("popup_waiting", "popup" to verdict.popup, "text" to obs.rawText.take(200))
                }
                else -> {}
            }
            if (System.currentTimeMillis() >= deadline + (service.heldMs - held0)) {
                throw StopException(
                    "The screen stayed unrecognized for ${timeoutSeconds}s (last text: ${obs.rawText.take(200)})"
                )
            }
            if (quit == null) AppLog.log("waiting_unrecognized", "text" to obs.rawText.take(200))
            delay(1000)
        }
    }

    /**
     * Waits for one of [allowed]. The screen we are leaving stays on display for a moment after
     * a tap, so [from] is tolerated until the timeout rather than treated as a wrong screen.
     *
     * [retap] is the button that was tapped to start the transition, for the callers whose
     * button may be tapped again without harm. It is sent again only while [from] is still
     * plainly on display, which is the one case that means the tap never landed.
     */
    private suspend fun transitionTo(
        allowed: Set<ScreenState>,
        runToken: Long,
        timeoutSeconds: Int,
        from: ScreenState? = null,
        retap: Point? = null,
        confirmedExits: Set<ScreenState> = emptySet()
    ): ScreenState {
        var deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var held0 = service.heldMs
        var retapsSent = 0
        val dialogue = DialogueBudget()
        var retapDue = System.currentTimeMillis() + RETAP_AFTER_MS
        var last: ScreenState = ScreenState.UNKNOWN
        // A timeout used to report the name UNKNOWN and nothing else, which says only that the
        // screen was not recognised - not what was on it. The last reading is kept so the message
        // names the words the classifier had to work with.
        var lastText = ""
        // A screen that is genuinely the wrong one stays on display, so it comes back on the
        // next reading half a second later. A screen on its way out does not. At 20:42 on
        // 17 September, two seconds after the army button was tapped, one frame carried nothing
        // legible but the words "Return Home" - the previous battle's button caught mid-fade -
        // and the run ended three attacks in. One stray frame is not a wrong screen.
        var unexpected: ScreenState? = null
        var searchingSince = 0L
        var shopBacks = 0
        while (System.currentTimeMillis() < deadline + (service.heldMs - held0)) {
            delay(500)
            // A person's Pause stops the reading too, and the wait is not charged to the timeout.
            service.awaitClearToAct("transition")
            val cap = deployTimer.timed("capture") { service.transport.capture(runToken) }
            val obs = deployTimer.timed("ocr") {
                held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            }
            last = obs.state
            lastText = obs.rawText
            if (obs.state in allowed) {
                return obs.state
            }
            if (obs.state == ScreenState.UNKNOWN) {
                when (val verdict = PopupGuard.inspect(obs.boxes, obs.viewport, obs.rawText)) {
                    // A timeout here would send recovery's Back at a maintenance screen.
                    PopupGuard.Verdict.Maintenance -> waitOutMaintenance(runToken, obs)
                    // An event intro can come up on the village a battle returns to.
                    is PopupGuard.Verdict.AdvanceDialogue -> if (dialogue.advance(verdict, obs.timestampMs, runToken)) {
                        deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
                        held0 = service.heldMs
                        continue
                    }
                    // The store and a priced offer get Back and nothing else.
                    PopupGuard.Verdict.Shop, is PopupGuard.Verdict.RefusedPremium ->
                        if (shopBacks < MAX_SHOP_BACKS && leaveShop(obs, verdict, "transition", runToken)) {
                            shopBacks++
                            continue
                        }
                    else -> {}
                }
            }
            // Matchmaking is not the screen being waited for, but it is not a wrong screen
            // either: it stands between the army check and the scout, and how long it stands
            // there is the game's business rather than this clock's. The wait starts over for
            // as long as it is up, and the search itself is what runs out of time.
            if (obs.state == ScreenState.SEARCHING) {
                val now = System.currentTimeMillis()
                if (searchingSince == 0L) {
                    searchingSince = now
                    AppLog.log("matchmaking_searching", "expected" to allowed.joinToString(",") { it.name })
                }
                if (now - searchingSince > SEARCH_TIMEOUT_MS) {
                    throw StopException(
                        "Matchmaking was still searching for an opponent after " +
                            "${SEARCH_TIMEOUT_MS / 1000}s"
                    )
                }
                deadline = now + timeoutSeconds * 1000L
                held0 = service.heldMs
                unexpected = null
                continue
            }
            if (obs.state != ScreenState.UNKNOWN && obs.state != from) {
                if (obs.state == unexpected && obs.state in confirmedExits) {
                    // Read twice running, so not a stray frame: the battle really has ended
                    // somewhere the caller knows how to carry on from.
                    AppLog.log(
                        "transition_confirmed_exit", "state" to obs.state.name,
                        "expected" to allowed.joinToString(",") { it.name }
                    )
                    return obs.state
                }
                if (obs.state == unexpected) {
                    throw StopException(
                        "Unexpected transition screen: ${obs.state.name} (expected $allowed) " +
                            "reading: ${obs.rawText.take(200)}"
                    )
                }
                AppLog.log(
                    "transition_stray_frame", "state" to obs.state.name,
                    "expected" to allowed.joinToString(",") { it.name },
                    "text" to obs.rawText.take(200)
                )
                unexpected = obs.state
            } else {
                unexpected = null
                val now = System.currentTimeMillis()
                if (retap != null && obs.state == from && retapsSent < MAX_RETAPS && now >= retapDue) {
                    // Held by a pause or an old frame, it is not counted, and the screen it was
                    // decided on is read again after the same wait.
                    if (sentOrHeld { service.transport.tapButton(retap, runToken, decidedOn = cap.timestampMs) }) {
                        retapsSent++
                        AppLog.log(
                            "tap_retried", "attempt" to retapsSent,
                            "x" to retap.x, "y" to retap.y,
                            "state" to obs.state.name,
                            "expected" to allowed.joinToString(",") { it.name }
                        )
                    }
                    retapDue = System.currentTimeMillis() + RETAP_AFTER_MS
                }
            }
        }
        AppLog.log(
            "transition_timeout", "expected" to allowed.joinToString(",") { it.name },
            "lastState" to last.name, "text" to lastText.take(400)
        )
        service.saveFailureSnapshot("transition_timeout")
        throw StopException(
            "Screen transition timed out waiting for $allowed (last seen: ${last.name}) " +
                "reading: ${lastText.take(200)}"
        )
    }

    /**
     * Reads the current balances on HOME. Used by manual wall upgrades to get fresh balances
     * before each phase.
     */
    private suspend fun readHomeBalances(runToken: Long): Map<Resource, Long> {
        val cap = service.transport.capture(runToken)
        return try {
            val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
            if (obs.state != ScreenState.HOME) return emptyMap()
            profile.homeResourceRois.mapNotNull { (resource, roi) ->
                screenReader.readBalance(cap.bitmap, roi)?.let { resource to it }
            }.toMap()
        } finally {
            cap.bitmap.recycle()
        }
    }

    /**
     * Reads the village twice, a moment apart. Both samples feed goal evaluation, dashboard
     * storage rings, and wall eligibility from the same agreed readings.
     */
    private suspend fun takeHomeSamples(runToken: Long): List<ResourceSample> {
        if (profile.homeResourceRois.isEmpty()) return emptyList()
        val samples = mutableListOf<ResourceSample>()
        val raw = mutableListOf<Map<Resource, String>>()
        val everyMask = mutableListOf<Set<Resource>>()
        // The top bar of the frames already in hand; nothing is captured or tapped for it.
        var builders: BuilderCount? = null
        var reread = false
        while (true) {
            if (samples.isNotEmpty()) delay(GOAL_SAMPLE_GAP_MS)
            service.awaitResume("Storage checkpoint")
            val cap = service.transport.capture(runToken)
            val capturedAt = System.currentTimeMillis()
            try {
                val obs = held(screenReader.observe(cap.bitmap, profile.viewport, cap.timestampMs))
                if (obs.state != ScreenState.HOME) {
                    // With its boxes, so a village that will not read as one can become a test as read.
                    AppLog.log("home_sample_not_home", "state" to obs.state.name, "reread" to reread,
                        "ocr" to obs.boxes.joinToString(" | ") { "${it.text}@${it.left},${it.top},${it.right},${it.bottom}" })
                    // One frame that does not read as the village ended Blood's wall phases at
                    // 07:58 and 07:59 on 14 October before a single balance was read. Look once more.
                    if (!reread) {
                        reread = true
                        delay(HOME_REREAD_MS)
                        continue
                    }
                    return emptyList()
                }
                Builders.read(obs.boxes, profile.viewport)?.let { builders = it }
                val frameRaw = mutableMapOf<Resource, String>()
                val frameEveryMask = mutableSetOf<Resource>()
                val values = profile.homeResourceRois.mapNotNull { (resource, roi) ->
                    val cuts = mutableListOf<String>()
                    val value = screenReader.readBalance(cap.bitmap, roi, cuts)
                    frameRaw[resource] = cuts.joinToString(" | ")
                    if (value != null && ScreenReader.everyMaskRead(cuts, value)) frameEveryMask += resource
                    value?.let { resource to it }
                }.toMap()
                samples += ResourceSample(capturedAt, values)
                raw += frameRaw
                everyMask += frameEveryMask
            } finally {
                cap.bitmap.recycle()
            }
            if (samples.size < 2) continue
            val settled = balanceGuard.settle(samples, everyMask) as? BalanceSettlement.Settled ?: continue
            settled.flagged.forEach { flag ->
                AppLog.log("balance_implausible", "resource" to flag.resource.key,
                    "readings" to flag.readings.joinToString(",") { it?.toString() ?: "-" },
                    "trusted" to flag.trusted, "accepted" to flag.accepted, "everyMask" to flag.everyMask,
                    "raw" to raw.joinToString(" / ") { it[flag.resource].orEmpty() })
            }
            accountId?.let { service.builderStore.record(it, builders, System.currentTimeMillis()) }
            return settled.samples
        }
    }

    /**
     * Reconciles two samples: checks each resource is plausible (<= capacity) in both frames and
     * uses the lower reading to prevent false triggering on OCR spikes.
     */
    private fun agreeBalances(samples: List<ResourceSample>): Map<Resource, Long> =
        agreedHomeBalances(samples, capacities)

    /**
     * Records the agreed gold and elixir balances for the storage rings on the dashboard.
     */
    private fun recordStorageValues(values: Map<Resource, Long>) {
        val store = storage ?: return
        val id = accountId ?: return
        if (values.isEmpty()) return
        runCatching { store.recordKeepingUnread(id, StorageReading(System.currentTimeMillis(), values)) }
            .onFailure { AppLog.log("storage_save_failed", "error" to (it.message ?: it.javaClass.simpleName)) }
        AppLog.log("storage_read", *values.map { it.key.key to it.value }.toTypedArray())
    }

    /**
     * Marks the saved profile verified, if it is still the calibration this attack ran on. A
     * calibration saved during the run was not what the attack proved.
     */
    private fun markEarnedVerification() {
        try {
            var recalibrated = false
            saveProgress { current ->
                val samePoints = current.wallPoints.toSet() == profile.wallPoints.toSet()
                val ranOn = current.copy(
                    wallCursor = profile.wallCursor,
                    wallPoints = if (samePoints) profile.wallPoints else current.wallPoints,
                    isVerified = profile.isVerified
                ) == profile
                recalibrated = !ranOn
                if (ranOn) current.copy(isVerified = true) else current
            } ?: return
            AppLog.log(if (recalibrated) "profile_verification_skipped_recalibrated" else "profile_earned_verification")
        } catch (e: Exception) {
            AppLog.log("failed_to_save_verification", "error" to e.message)
        }
    }
}

/**
 * Opens the reader, starts the run and does the work inside one boundary that closes the reader
 * exactly once. Starting used to happen before that boundary, so a run that never got Clash to
 * the front left its recogniser open.
 */
internal suspend fun <R : AutoCloseable, T> runWithReader(
    openReader: () -> R,
    begin: suspend () -> Long,
    work: suspend (reader: R, runToken: Long) -> T
): T {
    val reader = openReader()
    try {
        val runToken = begin()
        return work(reader, runToken)
    } finally {
        reader.close()
    }
}

/**
 * Why a battle still open when the run ended was cut short. A stopped guard says why it stopped,
 * but not every stop goes through the guard: a pause that saw the village taken throws without
 * stopping it, so at 16:21 UTC on 14 October the battle that takeover cut short was saved as
 * "run_ended".
 */
internal fun interruptReason(guardReason: String?, thrown: StopException?): String =
    guardReason ?: thrown?.message ?: "run_ended"
