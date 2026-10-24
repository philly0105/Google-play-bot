package local.clashfarmer.core

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Each old run button, written out as the exact arguments its FarmerService start passes to
 * startGuardedAccountSession (or startCombinedRun), must equal the plan that replaces it.
 */
class RunPlanTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 8, 22, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val saved = AccountSettings(
        attacks = 15,
        maxMinutes = 110,
        wallsEnabled = true,
        triggerPercent = 90,
        resumePercent = 75,
        capacities = mapOf(Resource.GOLD to 29_000_000L, Resource.ELIXIR to 29_000_000L),
        earlyEnd = EarlyEndRules(enabled = true, afterDeploySeconds = 60)
    )
    private val sweat = AccountRecord("11111111-2222-3333-4444-555555555555", "Sweat", "sweat", "#AAA", saved)
    private val blood = AccountRecord("66666666-7777-8888-9999-000000000000", "Blood", "blood", "#BBB",
        saved.copy(attacks = 20, wallsEnabled = false))
    private val accounts = listOf(sweat, blood)
    private val wallsOff = sweat.copy(settings = saved.copy(wallsEnabled = false))

    private fun plan(length: Length, walls: Boolean, eventTroops: Boolean = false, stop: StopWhen = StopWhen.LIMIT) =
        RunPlan(listOf(sweat.id), length, walls = walls, stopWhen = stop, eventTroops = eventTroops)

    private fun launch(p: RunPlan, list: List<AccountRecord> = accounts, selected: String? = sweat.id) =
        p.launch(list, now, selected, utc)

    // --- the old buttons, as FarmerService passes them --------------------------------------

    /** startFarmingAccount(account, overrideAttacks): the Farm 40 and Farm buttons. */
    private fun oldFarm(a: AccountRecord, attacks: Int?) = Launch.Single.resolve(
        a.id, Policy.farmingRunSettings(a.settings), supervised = false, wallOnly = false, overrideAttacks = attacks)

    /** startTimedFarmingAccount(account): the 2 h button. */
    private fun oldTimed(a: AccountRecord) = Launch.Single.resolve(
        a.id, Policy.timedRunSettings(a.settings), supervised = false, wallOnly = false,
        overrideAttacks = Policy.TIMED_RUN_ATTACKS, overrideMaxMinutes = Policy.TIMED_RUN_MINUTES,
        overrideWallsEnabled = true)

    /** startAttacksOnlyAccount(account, overrideAttacks): Attacks 40 and Attacks only. */
    private fun oldAttacks(a: AccountRecord, attacks: Int?) = Launch.Single.resolve(
        a.id, a.settings, supervised = false, wallOnly = false, overrideAttacks = attacks, overrideWallsEnabled = false)

    /** startEventAccount(account, LONG_RUN_ATTACKS): the event card. */
    private fun oldEvent(a: AccountRecord) = Launch.Single.resolve(
        a.id, Policy.eventRunSettings(a.settings), supervised = false, wallOnly = false,
        overrideAttacks = Policy.LONG_RUN_ATTACKS, withEventTroops = true)

    /** startWallPhaseAccount(account): Walls only. */
    private fun oldWalls(a: AccountRecord) = Launch.Single.resolve(
        a.id, a.settings, supervised = true, wallOnly = true, overrideMaxMinutes = 30)

    /** startSupervisedAccount(account). */
    private fun oldSupervised(a: AccountRecord) = Launch.Single.resolve(
        a.id, a.settings, supervised = true, wallOnly = false, overrideAttacks = 1, overrideMaxMinutes = 15,
        overrideWallsEnabled = false)

    /** The combined dialog's defaults: each account's attacks, 120 minutes, settings untouched. */
    private fun oldCombined(a: AccountRecord, b: AccountRecord, q1: Int = a.settings.attacks, q2: Int = b.settings.attacks) =
        Launch.Combined(a.id, b.id, a.settings, b.settings, q1, q2, 120)

    // --- equivalence ----------------------------------------------------------------------------

    @Test
    fun farm40EqualsAttacks40WithTheSavedWallSwitch() {
        assertEquals(oldFarm(sweat, 40), launch(plan(Length.Attacks(40), walls = sweat.settings.wallsEnabled)))
    }

    @Test
    fun farmEqualsTheAccountsOwnAttackCount() {
        assertEquals(oldFarm(sweat, null), launch(plan(Length.Attacks(15), walls = true)))
    }

    @Test
    fun farmWithWallsSavedOffIsTheSameRunAsAttacksOnly() {
        val list = listOf(wallsOff, blood)
        val old = oldFarm(wallsOff, 40)
        val now = launch(plan(Length.Attacks(40), walls = false), list) as Launch.Single
        // Only the resume level differs, and with walls off nothing reads it.
        assertNotEquals(old, now)
        assertTrue(now.sameRunAs(old))
        assertEquals(oldAttacks(wallsOff, 40), now)
    }

    @Test
    fun sameRunAsStillTellsDifferentWallRunsApart() {
        val old = oldFarm(sweat, 40)
        val other = old.copy(settings = old.settings.copy(resumePercent = 75))
        assertFalse(old.sameRunAs(other))
    }

    @Test
    fun timedEqualsTwoHoursWithWalls() {
        assertEquals(oldTimed(sweat), launch(plan(Length.Minutes(120), walls = true)))
        // Walls saved off: the 2 h card still forces them on, and so does the plan.
        assertEquals(oldTimed(wallsOff), launch(plan(Length.Minutes(120), walls = true), listOf(wallsOff, blood)))
    }

    @Test
    fun attacksOnlyEqualsWallsOff() {
        assertEquals(oldAttacks(sweat, 40), launch(plan(Length.Attacks(40), walls = false)))
        assertEquals(oldAttacks(sweat, null), launch(plan(Length.Attacks(15), walls = false)))
    }

    @Test
    fun eventEqualsEventTroopsWithTheSavedWallSwitch() {
        assertEquals(oldEvent(sweat), launch(plan(Length.Attacks(40), walls = true, eventTroops = true)))
        assertEquals(oldEvent(wallsOff),
            launch(plan(Length.Attacks(40), walls = false, eventTroops = true), listOf(wallsOff, blood)))
        val event = launch(plan(Length.Attacks(40), walls = true, eventTroops = true)) as Launch.Single
        assertTrue(event.withEventTroops)
        assertFalse(event.settings.earlyEnd.enabled)
    }

    @Test
    fun wallsOnlyAndSupervisedToolsEqualTheirButtons() {
        assertEquals(oldWalls(sweat), Launch.wallsOnly(sweat))
        assertEquals(oldSupervised(sweat), Launch.supervised(sweat))
        val noCapacity = sweat.copy(settings = saved.copy(capacities = emptyMap(), wallsEnabled = false))
        assertTrue(Launch.wallsOnly(noCapacity) is Launch.Refused)
    }

    @Test
    fun combinedEqualsBothWithEachAccountsAttacks() {
        val both = RunPlan(listOf(RunPlan.SELECTED, RunPlan.OTHER), Length.Attacks(15), secondAttacks = 20, walls = false)
        assertEquals(oldCombined(sweat, blood), launch(both))
        // Blood selected: Blood goes first, as the old dialog chose.
        val reversed = RunPlan(listOf(RunPlan.SELECTED, RunPlan.OTHER), Length.Attacks(20), secondAttacks = 15, walls = true)
        assertEquals(oldCombined(blood, sweat), launch(reversed, selected = blood.id))
    }

    @Test
    fun aSavedGoalIsKeptByAccountGoalAndClearedByLimit() {
        val goal = FarmingGoal(mapOf(Resource.GOLD to 20_000_000L))
        val withGoal = sweat.copy(settings = saved.copy(goal = goal))
        val list = listOf(withGoal, blood)
        assertEquals(oldFarm(withGoal, 40), launch(plan(Length.Attacks(40), walls = true, stop = StopWhen.ACCOUNT_GOAL), list))
        val limit = launch(plan(Length.Attacks(40), walls = true), list) as Launch.Single
        assertEquals(FarmingGoal(), limit.settings.goal)
        // "Limit only" clears the saved goal on purpose, so it is not the old Farm 40 here; the
        // seed, which follows the saved goal, is.
        assertNotEquals(oldFarm(withGoal, 40), launch(PresetStore.SEEDS[0].copy(stopWhen = StopWhen.LIMIT), list))
        assertEquals(oldFarm(withGoal, 40), launch(PresetStore.SEEDS[0], list))
        // A fresh composer plan follows the saved goal, as the old buttons did.
        assertEquals(StopWhen.ACCOUNT_GOAL, RunPlan.newFor(withGoal).stopWhen)
        assertEquals(StopWhen.LIMIT, RunPlan.newFor(sweat).stopWhen)
    }

    @Test
    fun theSeededPresetsEqualTheirButtons() {
        val (farm40, twoHours, both) = PresetStore.SEEDS
        assertEquals(oldFarm(sweat, 40), launch(farm40))
        assertEquals(oldTimed(sweat), launch(twoHours))
        assertEquals(oldCombined(sweat, blood, 40, 40), launch(both))
        // The selected account decides who the single-account presets run on.
        assertEquals(oldTimed(blood), launch(twoHours, selected = blood.id))
    }

    @Test
    fun theSeededPresetsKeepASavedGoalLikeTheirButtons() {
        val goalSweat = sweat.copy(settings = saved.copy(goal = FarmingGoal(mapOf(Resource.GOLD to 20_000_000L))))
        val goalBlood = blood.copy(settings = blood.settings.copy(
            goal = FarmingGoal(mapOf(Resource.ELIXIR to 25_000_000L), GoalMatch.ALL)))
        val list = listOf(goalSweat, goalBlood)
        val (farm40, twoHours, both) = PresetStore.SEEDS
        assertEquals(oldFarm(goalSweat, 40), launch(farm40, list))
        assertEquals(oldTimed(goalSweat), launch(twoHours, list))
        assertEquals(oldCombined(goalSweat, goalBlood, 40, 40), launch(both, list))
        // Only one account with a goal: the other runs to its limit, as the old dialog ran it.
        val mixed = listOf(goalSweat, blood)
        assertEquals(oldCombined(goalSweat, blood, 40, 40), launch(both, mixed))
        assertEquals("Ends early at the account goal.", farm40.summary(list, now, sweat.id, utc).lines()[1])
        assertTrue(launch(farm40, list).let { it is Launch.Single && it.settings.goal.isSet })
    }

    @Test
    fun theWallsToggleDefaultsToTheSavedSwitch() {
        assertTrue(RunPlan.newFor(sweat).walls)
        assertFalse(RunPlan.newFor(wallsOff).walls)
        assertFalse(RunPlan.newFor(blood).walls)
    }

    @Test
    fun aLaunchNeverChangesTheSavedSettings() {
        val before = accounts.toList()
        launch(plan(Length.Minutes(60), walls = true))
        launch(plan(Length.Attacks(40), walls = false, stop = StopWhen.STORAGE_FULL))
        assertEquals(before, accounts)
    }

    // --- lengths and stop rules ----------------------------------------------------------------

    @Test
    fun minutesWithWallsOffRunsAHundredAttacksForThatLong() {
        val l = launch(plan(Length.Minutes(45), walls = false)) as Launch.Single
        assertEquals(100, l.attacks)
        assertEquals(45, l.maxMinutes)
        assertFalse(l.wallsEnabled)
        assertEquals(saved.copy(goal = FarmingGoal()), l.settings)
    }

    @Test
    fun endByBecomesWholeMinutesAndRefusesOutsideFiveTo120() {
        val l = launch(plan(Length.EndBy(23, 30), walls = true)) as Launch.Single
        assertEquals(90, l.maxMinutes)
        assertEquals(Policy.TIMED_RUN_ATTACKS, l.attacks)
        assertEquals(Policy.timedRunSettings(saved), l.settings)
        assertTrue(launch(plan(Length.EndBy(22, 4), walls = true)) is Launch.Refused)
        assertEquals(5, RunPlan.endByMinutes(Length.EndBy(22, 5), now, utc))
        assertTrue(launch(plan(Length.EndBy(0, 1), walls = true)) is Launch.Refused)
        // A time already past today means tomorrow, which is far too long.
        assertEquals(23 * 60 + 59, RunPlan.endByMinutes(Length.EndBy(21, 59), now, utc))
        assertTrue(launch(plan(Length.EndBy(25, 0), walls = true)) is Launch.Refused)
    }

    @Test
    fun endByIsJudgedFromTheStartTime() {
        val hour = 3_600_000L
        // Set at 22:00 to start at 02:00: End by 04:00 is two hours from the start.
        val fromTwo = plan(Length.EndBy(4, 0), walls = true).launch(accounts, now + 4 * hour, sweat.id, utc) as Launch.Single
        assertEquals(120, fromTwo.maxMinutes)
        // Starting at 23:00 the same End by is five hours away, past the 120-minute limit.
        assertTrue(plan(Length.EndBy(4, 0), walls = true).launch(accounts, now + hour, sweat.id, utc) is Launch.Refused)
        // Judged from now (22:00) it is refused too, which is why the start time is what counts.
        assertTrue(launch(plan(Length.EndBy(4, 0), walls = true)) is Launch.Refused)
    }

    @Test
    fun storageFullIsAGoalOfEveryCapacityAndNeedsWallsOff() {
        val l = launch(plan(Length.Attacks(40), walls = false, stop = StopWhen.STORAGE_FULL)) as Launch.Single
        assertEquals(FarmingGoal(saved.capacities, GoalMatch.ALL), l.settings.goal)
        val refused = launch(plan(Length.Attacks(40), walls = true, stop = StopWhen.STORAGE_FULL))
        assertTrue(refused is Launch.Refused)
    }

    @Test
    fun accountGoalWithoutOneIsRefused() {
        assertEquals(Launch.Refused("No goal set"),
            launch(plan(Length.Attacks(40), walls = true, stop = StopWhen.ACCOUNT_GOAL)))
    }

    @Test
    fun bothRefusesTimeLimitsAndEventTroops() {
        val both = RunPlan(listOf(sweat.id, blood.id), Length.Minutes(60), walls = false)
        assertTrue(launch(both) is Launch.Refused)
        assertTrue(launch(both.copy(length = Length.Attacks(10), eventTroops = true)) is Launch.Refused)
        assertTrue(launch(both.copy(length = Length.Attacks(10)), listOf(sweat)) is Launch.Refused)
    }

    @Test
    fun wallsOnWithABrokenWallSetupIsRefused() {
        val noCapacity = sweat.copy(settings = saved.copy(capacities = emptyMap(), wallsEnabled = false))
        val refused = launch(plan(Length.Attacks(40), walls = true), listOf(noCapacity, blood))
        assertEquals(Launch.Refused("Wall spending needs the gold capacity."), refused)
    }

    // --- readiness and text ---------------------------------------------------------------------

    private fun profile(verified: Boolean) = PhoneProfile(
        resolutionWidth = 1600,
        resolutionHeight = 720,
        viewport = Viewport(0, 0, 1600, 720),
        stateAnchors = mapOf(
            "attack" to Point(100, 600), "find_match" to Point(300, 500), "army" to Point(500, 650),
            "return_home" to Point(800, 620), "village_neutral" to Point(150, 200)
        ),
        troopAnchors = mapOf("valkyrie" to Point(200, 650), "rage" to Point(300, 650)),
        deploymentPoints = listOf(Point(100, 100), Point(1500, 650)),
        homeResourceRois = mapOf(Resource.GOLD to Region(1300, 20, 1500, 60), Resource.ELIXIR to Region(1300, 80, 1500, 120)),
        isVerified = verified
    )

    @Test
    fun readinessHasTheOldGates() {
        val p = plan(Length.Attacks(40), walls = true)
        val profiles = mapOf(sweat.id to profile(true), blood.id to profile(false))
        assertTrue(p.readiness(accounts, profiles, connected = true, busy = false, nowMs = now, zone = utc).ready)
        assertEquals("A run is already going",
            p.readiness(accounts, profiles, connected = true, busy = true, nowMs = now, zone = utc).reason)
        assertEquals("Turn on the accessibility service in Setup",
            p.readiness(accounts, profiles, connected = false, busy = false, nowMs = now, zone = utc).reason)
        assertEquals("Sweat is not calibrated",
            p.readiness(accounts, emptyMap(), connected = true, busy = false, nowMs = now, zone = utc).reason)
        val both = RunPlan(listOf(sweat.id, blood.id), Length.Attacks(40), walls = false)
        assertEquals("Blood needs a supervised attack first",
            both.readiness(accounts, profiles, connected = true, busy = false, nowMs = now, zone = utc).reason)
        val noWallRois = mapOf(sweat.id to profile(true).copy(homeResourceRois = emptyMap()))
        assertTrue(p.readiness(accounts, noWallRois, true, false, nowMs = now, zone = utc).reason!!.startsWith("Sweat walls:"))
        // Walls off needs no wall calibration.
        assertTrue(plan(Length.Attacks(40), walls = false).readiness(accounts, noWallRois, true, false, nowMs = now, zone = utc).ready)
        assertEquals("No goal set", plan(Length.Attacks(40), walls = true, stop = StopWhen.ACCOUNT_GOAL)
            .readiness(accounts, profiles, true, false, nowMs = now, zone = utc).reason)
    }

    @Test
    fun theSummaryIsTwoPlainLines() {
        val text = plan(Length.Attacks(40), walls = true).summary(accounts, now, zone = utc)
        assertEquals("Sweat: up to 40 attacks or 110 min. Walls at 90%, spend down to 0%.\nEnds at the limit.", text)
        val both = PresetStore.SEEDS[2].summary(accounts, now, sweat.id, utc)
        assertEquals("Sweat 40 then Blood 40 attacks, up to 120 min. Walls follow each account.\nEnds at the limit.", both)
        assertFalse(text.contains("_"))
    }

    @Test
    fun aPlanRoundTripsThroughJson() {
        val plans = PresetStore.SEEDS + RunPlan(listOf(sweat.id), Length.EndBy(7, 5), walls = false,
            stopWhen = StopWhen.STORAGE_FULL, eventTroops = true, name = "Night")
        plans.forEach { assertEquals(it, RunPlan.fromJson(it.toJson())) }
    }
}
