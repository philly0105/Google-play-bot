package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.Length
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StopWhen
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.ComposerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ComposerStateTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 13, 7, 42, 0, 0, utc).toInstant().toEpochMilli()

    private val saved = AccountSettings(
        wallsEnabled = true,
        triggerPercent = 90,
        resumePercent = 75,
        capacities = mapOf(Resource.GOLD to 29_000_000L, Resource.ELIXIR to 29_000_000L)
    )
    private val sweat = AccountRecord("11111111-2222-3333-4444-555555555555", "Sweat", "sweat", "#AAA", saved)
    private val blood = AccountRecord("66666666-7777-8888-9999-000000000000", "Blood", "blood", "#BBB", saved.copy(wallsEnabled = false))
    private val both = listOf(sweat, blood)
    private val profile = PhoneProfile(
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
        isVerified = true
    )
    private val profiles = mapOf(sweat.id to profile, blood.id to profile)

    private val farm40 = RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(40), walls = true)

    private fun state(
        plan: RunPlan = farm40,
        accounts: List<AccountRecord> = both,
        profiles: Map<String, PhoneProfile?> = this.profiles,
        connected: Boolean = true,
        busy: Boolean = false
    ) = ComposerState.of(plan, accounts, profiles, sweat.id, connected, busy, now, utc)

    @Test
    fun twoAccountsEnableBoth() {
        val two = state()
        assertEquals(listOf("Sweat", "Blood", "Both"), two.accountOptions)
        assertTrue(two.bothEnabled)
        assertEquals(0, two.accountIndex)

        val one = state(accounts = listOf(sweat))
        assertEquals(listOf("Sweat", "Both"), one.accountOptions)
        assertFalse(one.bothEnabled)

        assertEquals(emptyList<String>(), state(accounts = emptyList()).accountOptions)
    }

    @Test
    fun bothRunsCountAttacksOnlyAndFollowEachAccountsWalls() {
        val plan = ComposerState.withBoth(farm40.copy(length = Length.Minutes(60), eventTroops = true))
        assertEquals(Length.Attacks(40), plan.length)
        assertEquals(40, plan.secondAttacks)
        assertFalse(plan.eventTroops)
        val s = state(plan = plan)
        assertEquals(2, s.accountIndex)
        assertEquals("Sweat" to "Blood", s.bothNames)
        assertFalse(s.timeModesEnabled)
        assertEquals("Each account uses its own wall setting", s.wallsDisabledReason)
        assertFalse(s.eventTroopsEnabled)
    }

    @Test
    fun aTimeLimitDisablesTheAttackCount() {
        val timed = state(plan = farm40.copy(length = Length.Minutes(120)))
        assertFalse(timed.attackCountEnabled)
        assertNull(timed.attacks)
        assertEquals(120, timed.minutes)
        assertEquals(ComposerState.TIME, timed.lengthIndex)

        val endBy = state(plan = farm40.copy(length = Length.EndBy(8, 45)))
        assertFalse(endBy.attackCountEnabled)
        assertEquals("Ends at 08:45, in 1 h 3 min", endBy.endByLine)

        val attacks = state()
        assertTrue(attacks.attackCountEnabled)
        assertEquals(40, attacks.attacks)
        assertNull(attacks.minutes)
    }

    @Test
    fun startIsDisabledWithTheReadinessReason() {
        val off = state(connected = false)
        assertFalse(off.startEnabled)
        assertEquals("Turn on the accessibility service in Setup", off.startReason)
        assertEquals(listOf(ComposerState.Problem("Turn on the accessibility service in Setup", Tone.BAD)), off.problems)

        val running = state(busy = true)
        assertFalse(running.startEnabled)
        assertEquals("A run is already going", running.startReason)
        assertEquals(Tone.WARN, running.problems.first().tone)

        val unverified = state(profiles = mapOf(sweat.id to profile.copy(isVerified = false), blood.id to profile))
        assertEquals("Sweat needs a supervised attack first", unverified.startReason)
        assertEquals(Tone.WARN, unverified.problems.first().tone)

        val missing = state(profiles = emptyMap())
        assertEquals("Sweat is not calibrated", missing.startReason)
        assertEquals(Tone.BAD, missing.problems.first().tone)

        val ready = state()
        assertTrue(ready.startEnabled)
        assertNull(ready.startReason)
        assertTrue(ready.problems.isEmpty())
    }

    @Test
    fun anIncompleteCalibrationReadsPlainly() {
        val noFindMatch = profile.copy(stateAnchors = profile.stateAnchors - "find_match")
        val attack = state(profiles = mapOf(sweat.id to noFindMatch, blood.id to profile))
        assertEquals("Sweat calibration is incomplete; finish it in Setup", attack.startReason)
        assertFalse(attack.startEnabled)

        val noElixirRoi = profile.copy(homeResourceRois = profile.homeResourceRois - Resource.ELIXIR)
        val walls = state(profiles = mapOf(sweat.id to noElixirRoi, blood.id to profile))
        assertEquals("Sweat wall calibration is incomplete; finish it in Setup", walls.startReason)
        // With walls off the wall calibration is not needed.
        assertTrue(state(plan = farm40.copy(walls = false), profiles = mapOf(sweat.id to noElixirRoi)).startEnabled)

        (attack.problems + walls.problems).forEach { assertFalse(it.text, it.text.contains('_')) }
    }

    @Test
    fun wallsSubtitleStatesTheRunsRule() {
        // A farming run with walls spends down to 0, not the account's saved 75.
        assertEquals("Buy walls at 90% storage, spend down to 0%", state().wallsSubtitle)
        assertEquals("Walls off for this run", state(plan = farm40.copy(walls = false)).wallsSubtitle)
        assertNull(state().wallsDisabledReason)
        // A plan that will not launch still shows the rule it would run with.
        val refused = state(plan = farm40.copy(stopWhen = StopWhen.STORAGE_FULL))
        assertFalse(refused.startEnabled)
        assertEquals("Buy walls at 90% storage, spend down to 0%", refused.wallsSubtitle)
        assertEquals("Buy walls at 90% storage, spend down to 0%", state(plan = farm40.copy(length = Length.Minutes(60))).wallsSubtitle)
    }

    @Test
    fun accountGoalNeedsAGoal() {
        val noGoal = state(plan = farm40.copy(stopWhen = StopWhen.SAVED_GOAL))
        assertFalse(noGoal.accountGoalEnabled)
        assertEquals(0, noGoal.stopIndex)
        assertNotNull(noGoal.stopNote)

        val withGoal = sweat.copy(settings = saved.copy(goal = FarmingGoal(mapOf(Resource.GOLD to 20_000_000L))))
        val s = ComposerState.of(farm40.copy(stopWhen = StopWhen.SAVED_GOAL), listOf(withGoal, blood), profiles, sweat.id,
            connected = true, busy = false, nowMs = now, zone = utc)
        assertTrue(s.accountGoalEnabled)
        assertEquals(2, s.stopIndex)
        assertNull(s.stopNote)
        assertEquals(StopWhen.STORAGE_FULL, ComposerState.stopAt(1))

        // Both accounts: one goal between them is enough, as RunPlan.combined accepts it.
        val bothPlan = ComposerState.withBoth(farm40.copy(stopWhen = StopWhen.SAVED_GOAL))
        val b = ComposerState.of(bothPlan, listOf(withGoal, blood), profiles, sweat.id,
            connected = true, busy = false, nowMs = now, zone = utc)
        assertTrue(b.accountGoalEnabled)
        assertEquals(2, b.stopIndex)
        assertTrue(b.startEnabled)

        // Moving an account-goal plan to an account without a goal falls back to the limit.
        val goalPlan = farm40.copy(stopWhen = StopWhen.ACCOUNT_GOAL)
        assertEquals(StopWhen.LIMIT, ComposerState.withSingle(goalPlan, blood).stopWhen)
        assertEquals(StopWhen.ACCOUNT_GOAL, ComposerState.withSingle(goalPlan, withGoal).stopWhen)
    }

    @Test
    fun aFreshPlanFollowsTheSelectedAccount() {
        val fresh = ComposerState.initial(null, both, blood.id)
        assertEquals(listOf(RunPlan.SELECTED), fresh.accountIds)
        assertEquals(Length.Attacks(40), fresh.length)
        assertFalse(fresh.walls)
        val last = farm40.copy(name = "Farm 40 + walls")
        assertEquals(last, ComposerState.initial(last, both, blood.id))

        val single = ComposerState.withSingle(ComposerState.withBoth(farm40), blood)
        assertEquals(listOf(RunPlan.SELECTED), single.accountIds)
        assertNull(single.secondAttacks)
        assertFalse(single.walls)
    }

    @Test
    fun attackStepsAreOneUnderTenThenFive() {
        assertEquals(4, ComposerState.stepAttacks(3, 4))
        assertEquals(10, ComposerState.stepAttacks(9, 10))
        assertEquals(15, ComposerState.stepAttacks(10, 11))
        assertEquals(15, ComposerState.stepAttacks(12, 13))
        assertEquals(10, ComposerState.stepAttacks(15, 14))
        assertEquals(10, ComposerState.stepAttacks(12, 11))
        assertEquals(9, ComposerState.stepAttacks(10, 9))
        assertEquals(1, ComposerState.stepAttacks(1, 0))
        assertEquals(100, ComposerState.stepAttacks(100, 101))
    }

    @Test
    fun minuteStepsAreFiveThenFifteens() {
        assertEquals(15, ComposerState.stepMinutes(5, 6))
        assertEquals(30, ComposerState.stepMinutes(15, 16))
        assertEquals(5, ComposerState.stepMinutes(15, 14))
        assertEquals(15, ComposerState.stepMinutes(30, 29))
        assertEquals(120, ComposerState.stepMinutes(120, 121))
    }

    @Test
    fun endByDefaultsToAboutAnHourOnTheFiveMinutes() {
        assertEquals(Length.EndBy(8, 45), ComposerState.defaultEndBy(now, utc))
        val tooSoon = ComposerState.endByLine(Length.EndBy(7, 44), now, utc)
        assertTrue(tooSoon, tooSoon.contains("pick 5 min to 2 h from now"))
    }

    @Test
    fun endByFromAStartTimeIsWordedFromTheStart() {
        val fine = ComposerState.endByLine(Length.EndBy(8, 45), now, utc, fromStart = true)
        assertTrue(fine, fine.startsWith("Ends at 08:45, ") && fine.endsWith(" after the start"))
        val tooSoon = ComposerState.endByLine(Length.EndBy(7, 44), now, utc, fromStart = true)
        assertTrue(tooSoon, tooSoon.contains("after the start; pick 5 min to 2 h after it"))
    }
}
