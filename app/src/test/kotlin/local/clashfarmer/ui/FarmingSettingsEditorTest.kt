package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.EarlyEndRules
import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.OpponentRules
import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmingSettingsEditorTest {

    private val full = AccountSettings(
        attacks = 12,
        maxMinutes = 45,
        wallsEnabled = true,
        reserveGold = 0L,
        goal = FarmingGoal(mapOf(Resource.GOLD to 8_000_000L), GoalMatch.ANY),
        capacities = mapOf(Resource.GOLD to 12_000_000L, Resource.ELIXIR to 12_000_000L),
        opponent = OpponentRules(
            enabled = true,
            minimums = mapOf(Resource.GOLD to 650_000L),
            minimumGoldAndElixir = 1_200_000L,
            maxSkips = 5,
            maxSearchMs = 90_000L,
            maxSearchGold = 7_500L
        ),
        collectBeforeFarming = true
    )

    private fun edit(change: (SettingsFields) -> SettingsFields) =
        FarmingSettingsEditor.parse(change(FarmingSettingsEditor.fieldsOf(full)))

    private fun errors(result: SettingsEdit) = (result as SettingsEdit.Invalid).errors

    @Test fun untouchedFieldsSaveTheSameSettings() {
        assertEquals(SettingsEdit.Valid(full), edit { it })
    }

    @Test fun collectionTogglesAreTheirOwn() {
        val after = (edit { it.copy(collectBeforeFarming = false, collectAfterFarming = true) } as SettingsEdit.Valid).settings
        assertEquals(false, after.collectBeforeFarming)
        assertEquals(true, after.collectAfterFarming)
    }

    @Test fun defaultsRoundTrip() {
        val defaults = AccountSettings()
        assertEquals(SettingsEdit.Valid(defaults), FarmingSettingsEditor.parse(FarmingSettingsEditor.fieldsOf(defaults)))
    }

    @Test fun triggerAndResumeAreEditableFields() {
        val fields = FarmingSettingsEditor.fieldsOf(full)
        assertEquals("90", fields.triggerPercent)
        assertEquals("75", fields.resumePercent)
        val after = (edit { it.copy(triggerPercent = "80", resumePercent = "50") } as SettingsEdit.Valid).settings
        assertEquals(80, after.triggerPercent)
        assertEquals(50, after.resumePercent)
    }

    @Test fun triggerAndResumeAreCheckedAsTypedAndAgainstEachOther() {
        assertEquals("Trigger percent is required", errors(edit { it.copy(triggerPercent = " ") })["triggerPercent"])
        assertEquals("Resume percent must be a whole number", errors(edit { it.copy(resumePercent = "7.5") })["resumePercent"])
        assertTrue(errors(edit { it.copy(triggerPercent = "101") })["form"]!!.startsWith("Trigger percent must be between 1 and 100"))
        assertTrue(errors(edit { it.copy(triggerPercent = "60", resumePercent = "60") })["form"]!!
            .startsWith("Resume percent must be between 0 and 59"))
    }

    @Test fun wallSettingsThatFightTheGoalOrTheReserveAreRefusedWithAReason() {
        assertEquals(
            "Gold goal 11,000,000 is above the wall trigger 10,800,000. " +
                "Lower the goal, raise the trigger, or set walls to elixir only.",
            errors(edit { it.copy(goalTargets = mapOf(Resource.GOLD to "11000000")) })["form"]
        )
        // Elixir-only walls leave the gold goal alone.
        assertTrue(edit {
            it.copy(goalTargets = mapOf(Resource.GOLD to "11000000"), currencyPreference = "elixir")
        } is SettingsEdit.Valid)
        assertTrue(edit { it.copy(reserveGold = "10800000") } is SettingsEdit.Valid)
        assertTrue(errors(edit { it.copy(capacities = emptyMap(), goalTargets = emptyMap()) })["form"]!!
            .contains("capacity"))
    }

    @Test fun anAccountNeedingWallSetupStillShowsItsWallsToggleOn() {
        val needsSetup = AccountSettings(wallSetupRequired = "Wall spending needs the gold capacity.")
        assertEquals(true, FarmingSettingsEditor.fieldsOf(needsSetup).wallsEnabled)
        assertTrue(errors(FarmingSettingsEditor.parse(FarmingSettingsEditor.fieldsOf(needsSetup)))["form"]!!.contains("capacity"))
    }

    @Test fun aBlankRequiredNumberIsAnErrorNotTheOldValue() {
        val e = errors(edit { it.copy(attacks = " ") })
        assertEquals("Attacks is required", e["attacks"])
    }

    @Test fun zeroAndNegativeNumbersAreRefusedWithTheirRange() {
        assertTrue(errors(edit { it.copy(attacks = "0") })["form"]!!.startsWith("Attacks must be between 1 and 100"))
        assertTrue(errors(edit { it.copy(maxMinutes = "-5") })["form"]!!.startsWith("Max minutes must be between 1 and 120"))
        assertTrue(errors(edit { it.copy(capacities = it.capacities + (Resource.ELIXIR to "0")) })["form"]!!
            .startsWith("Capacity for elixir must be positive"))
        assertTrue(edit { it.copy(reserveGold = "-1") } is SettingsEdit.Valid)
    }

    @Test fun oversizedNumbersAreRefusedNotClamped() {
        assertTrue(errors(edit { it.copy(attacks = "101") })["form"]!!.startsWith("Attacks must be between 1 and 100"))
        assertEquals("Attacks is too large", errors(edit { it.copy(attacks = "99999999999") })["attacks"])
        assertEquals("Gold capacity is too large",
            errors(edit { it.copy(capacities = it.capacities + (Resource.GOLD to "99999999999999999999")) })["capacity_gold"])
        assertTrue(errors(edit { it.copy(maxSkips = "51") })["form"]!!.startsWith("Max skips must be between 0 and 50"))
        assertTrue(errors(edit { it.copy(maxSearchSeconds = "301") })["form"]!!.startsWith("Search time must be between 1 and 300"))
    }

    @Test fun textThatIsNotAWholeNumberIsNamed() {
        assertEquals("Attacks must be a whole number", errors(edit { it.copy(attacks = "12a") })["attacks"])
        assertEquals("Search time must be a whole number of seconds", errors(edit { it.copy(maxSearchSeconds = "1.5") })["maxSearchSeconds"])
    }

    @Test fun aGoalAboveItsCapacityOrWithoutOneIsRefused() {
        assertTrue(errors(edit { it.copy(goalTargets = mapOf(Resource.GOLD to "13000000")) })["form"]!!
            .startsWith("Goal for gold (13000000) is more than its capacity"))
        assertTrue(errors(edit { it.copy(goalTargets = mapOf(Resource.DARK_ELIXIR to "50000")) })["form"]!!
            .startsWith("Goal for dark_elixir needs that resource's capacity"))
    }

    @Test fun blankOptionalAmountsClearOnlyThatAmount() {
        val result = edit { it.copy(goalTargets = emptyMap(), minimumGoldAndElixir = "") } as SettingsEdit.Valid
        assertEquals(FarmingGoal(emptyMap(), GoalMatch.ANY), result.settings.goal)
        assertEquals(null, result.settings.opponent.minimumGoldAndElixir)
        assertEquals(full.capacities, result.settings.capacities)
        assertEquals(full.opponent.minimums, result.settings.opponent.minimums)
    }

    @Test fun theSummaryNamesTargetThresholdsLimitsAndFallback() {
        assertEquals(
            "Goal: stop when any of Gold 8,000,000 is in storage.\n" +
                "Opponents: skip bases below Gold 650,000, Gold plus elixir 1,200,000. " +
                "Up to 5 skips, 90 s and 7,500 gold per attack; then the current base is attacked.\n" +
                "Battles: each one runs to its end.",
            FarmingSettingsEditor.summary(full)
        )
        assertEquals(
            "Goal: none; the attack quota ends the run.\nOpponents: every base found is attacked.\n" +
                "Battles: each one runs to its end.",
            FarmingSettingsEditor.summary(AccountSettings())
        )
    }

    @Test fun earlyEndFieldsRoundTripAndBlankIsOff() {
        val rules = EarlyEndRules(true, afterDeploySeconds = 40, battleSecondsLeft = 0, lootLeftBelow = mapOf(Resource.GOLD to 50_000L))
        val withRules = full.copy(earlyEnd = rules)
        val fields = FarmingSettingsEditor.fieldsOf(withRules)
        assertEquals("", fields.endBattleSecondsLeft)
        assertEquals(SettingsEdit.Valid(withRules), FarmingSettingsEditor.parse(fields))
        assertEquals(
            "Battles: surrender 40 s after the deploy, or once the base has less than Gold 50,000 left.",
            FarmingSettingsEditor.summary(withRules).lines().last()
        )
    }

    @Test fun damageTriggerRoundTripsAndIsSummarised() {
        val withRules = full.copy(earlyEnd = EarlyEndRules(true, atDamage = true))
        val fields = FarmingSettingsEditor.fieldsOf(withRules)
        assertTrue(fields.endAtDamage)
        assertEquals(SettingsEdit.Valid(withRules), FarmingSettingsEditor.parse(fields))
        assertEquals(
            "Battles: surrender at a damage picked between 70% and 70% for each battle.",
            FarmingSettingsEditor.summary(withRules).lines().last()
        )
        val custom = (edit { it.copy(earlyEndEnabled = true, endAtDamage = true, endDamageMin = "40", endDamageMax = "70") }
            as SettingsEdit.Valid).settings
        assertEquals(40, custom.earlyEnd.damageMin)
        assertEquals(70, custom.earlyEnd.damageMax)
        assertEquals("Battles: surrender at a damage picked between 70% and 70% for each battle " +
            "(saved 40% lifted to 70%: Surrendering under 70% damage loses part of the league bonus).",
            FarmingSettingsEditor.summary(custom).lines().last())
    }

    @Test fun aDamageRangeBelowTheLeagueFloorIsSummarisedWithItsEffectiveRange() {
        val low = (edit { it.copy(earlyEndEnabled = true, endAtDamage = true, endDamageMin = "60", endDamageMax = "75") }
            as SettingsEdit.Valid).settings
        assertEquals("Battles: surrender at a damage picked between 70% and 75% for each battle " +
            "(saved 60% lifted to 70%: Surrendering under 70% damage loses part of the league bonus).",
            FarmingSettingsEditor.summary(low).lines().last())
        val atFloor = (edit { it.copy(earlyEndEnabled = true, endAtDamage = true, endDamageMin = "70", endDamageMax = "80") }
            as SettingsEdit.Valid).settings
        assertEquals("Battles: surrender at a damage picked between 70% and 80% for each battle.",
            FarmingSettingsEditor.summary(atFloor).lines().last())
    }

    @Test fun aDamageRangeOutOfOrderOrOutsideAPercentIsRefused() {
        assertEquals("Lowest damage is required", errors(edit { it.copy(endDamageMin = "") })["endDamageMin"])
        assertTrue(errors(edit { it.copy(endDamageMin = "60", endDamageMax = "55") })["form"]!!
            .startsWith("Lowest damage 60% is above the highest 55%"))
        assertTrue(errors(edit { it.copy(endDamageMax = "101") })["form"]!!
            .startsWith("Damage percents must be between 1 and 100"))
    }

    @Test fun earlyEndTimesOutsideABattleAreRefused() {
        assertEquals("Battle times must be between 0 and 180 seconds",
            errors(edit { it.copy(earlyEndEnabled = true, endAfterDeploySeconds = "181") })["endAfterDeploySeconds"])
        assertEquals("Seconds after deploy must be a whole number",
            errors(edit { it.copy(endAfterDeploySeconds = "4s") })["endAfterDeploySeconds"])
        assertTrue(errors(edit { it.copy(endLootLeftBelow = mapOf(Resource.GOLD to "0")) })["form"]!!
            .startsWith("Loot left for gold must be positive"))
    }

    @Test fun readinessNamesMissingLootRowsForTheLootTrigger() {
        val settings = AccountSettings(earlyEnd = EarlyEndRules(true, lootLeftBelow = mapOf(Resource.GOLD to 1L)))
        assertEquals(
            listOf("Ending battles on loot left needs the scout gold loot calibrated; without it that trigger never fires."),
            FarmingSettingsEditor.readiness(settings, emptySet(), emptyList(), lootMissing = listOf("gold loot"))
        )
    }

    @Test fun readinessNamesUncalibratedGoalAndScoutRegions() {
        val settings = full.copy(
            goal = FarmingGoal(mapOf(Resource.GOLD to 1L, Resource.DARK_ELIXIR to 1L)),
            capacities = mapOf(Resource.GOLD to 2L, Resource.DARK_ELIXIR to 2L)
        )
        assertEquals(
            listOf(
                "Goal for Dark elixir has no village balance region, so it can never be met. Calibrate it in Setup.",
                "Opponent rules are on but the scout Next price is not calibrated; without them the run attacks rather than skips."
            ),
            FarmingSettingsEditor.readiness(settings, homeRegions = setOf(Resource.GOLD), scoutMissing = listOf("Next price"))
        )
        assertEquals(emptyList<String>(),
            FarmingSettingsEditor.readiness(AccountSettings(), homeRegions = emptySet(), scoutMissing = listOf("loot")))
    }

    @Test fun everyBadFieldIsReportedAtOnce() {
        val e = errors(edit { it.copy(attacks = "", maxMinutes = "x", maxSearchGold = "") })
        assertEquals(setOf("attacks", "maxMinutes", "maxSearchGold"), e.keys)
    }
}
