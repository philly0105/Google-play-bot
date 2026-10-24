package local.clashfarmer.accounts

import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.OpponentRules
import local.clashfarmer.core.Resource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AccountSettingsTest {

    private fun rejects(json: String) {
        try {
            AccountSettings.fromJson(JSONObject(json))
            fail("Expected $json to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun settingsWrittenBeforeGoalsLoadWithNoGoal() {
        val old = JSONObject(
            """{"attacks":40,"maxMinutes":90,"wallsEnabled":true,"attacksPerWallPhase":7,
               "maxWallLevel":17,"reserveGold":0,"reserveElixir":0,"currencyPreference":"auto",
               "maxWallsPerPhase":20,"capacities":{"gold":20000000,"elixir":20000000}}"""
        )
        val settings = AccountSettings.fromJson(old)
        assertEquals(40, settings.attacks)
        assertEquals(90, settings.triggerPercent)
        assertEquals(75, settings.resumePercent)
        assertEquals(FarmingGoal(), settings.goal)
        assertEquals(mapOf(Resource.GOLD to 20_000_000L, Resource.ELIXIR to 20_000_000L), settings.capacities)
    }

    @Test fun goalAndCapacitiesRoundTrip() {
        val settings = AccountSettings(
            goal = FarmingGoal(mapOf(Resource.GOLD to 8_000_000L, Resource.ELIXIR to 7_500_000L), GoalMatch.ANY),
            capacities = mapOf(Resource.GOLD to 12_000_000L, Resource.ELIXIR to 12_000_000L)
        )
        val json = settings.toJson().toString()
        assertEquals(settings, AccountSettings.fromJson(JSONObject(json)))
    }

    @Test fun earlyEndRoundTripsAndOlderSettingsLoadWithItOff() {
        val settings = AccountSettings(earlyEnd = local.clashfarmer.core.EarlyEndRules(
            enabled = true, afterDeploySeconds = 45, battleSecondsLeft = 60,
            lootLeftBelow = mapOf(Resource.GOLD to 40_000L), atDamage = true,
            damageMin = 45, damageMax = 60
        ))
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
        assertEquals(local.clashfarmer.core.EarlyEndRules(), AccountSettings.fromJson(JSONObject("{}")).earlyEnd)
    }

    @Test fun damageDefaultsToTheLeagueBonusFloorAndASavedLowerValueStillLoads() {
        assertEquals(70, AccountSettings.fromJson(JSONObject("""{"earlyEnd":{"atDamage":true}}""")).earlyEnd.damageMin)
        // Saved profiles below the floor are not rewritten or refused; the run lifts the target.
        val saved = AccountSettings.fromJson(JSONObject("""{"earlyEnd":{"atDamage":true,"damageMin":50,"damageMax":55}}"""))
        assertEquals(50, saved.earlyEnd.damageMin)
        assertEquals(55, saved.earlyEnd.damageMax)
    }

    @Test fun stallSurrenderStartsOffAndRoundTrips() {
        assertEquals(false, AccountSettings().earlyEnd.onStall)
        assertEquals(false, AccountSettings.fromJson(JSONObject("{}")).earlyEnd.onStall)
        val settings = AccountSettings(
            earlyEnd = local.clashfarmer.core.EarlyEndRules(enabled = true, onStall = true)
        )
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
        rejects("""{"earlyEnd":{"onStall":"yes"}}""")
    }

    @Test fun earlyEndOutsideABattleIsRejected() {
        rejects("""{"earlyEnd":{"enabled":true,"afterDeploySeconds":181}}""")
        rejects("""{"earlyEnd":{"enabled":true,"battleSecondsLeft":-1}}""")
        rejects("""{"earlyEnd":{"enabled":true,"lootLeftBelow":{"gold":0}}}""")
        rejects("""{"earlyEnd":true}""")
        rejects("""{"earlyEnd":{"damageMin":0}}""")
        rejects("""{"earlyEnd":{"damageMax":101}}""")
        rejects("""{"earlyEnd":{"damageMin":60,"damageMax":55}}""")
    }

    @Test fun unknownResourcesAndMatchesAreRejected() {
        rejects("""{"capacities":{"gems":5}}""")
        rejects("""{"capacities":{"gold":10},"goal":{"match":"most","targets":{"gold":5}}}""")
        rejects("""{"capacities":{"gold":10},"goal":{"match":"all","targets":{"GOLD":5}}}""")
    }

    @Test fun amountsMustBeWholePositiveNumbers() {
        rejects("""{"capacities":{"gold":10.5}}""")
        rejects("""{"capacities":{"gold":"10"}}""")
        rejects("""{"capacities":{"gold":0}}""")
        rejects("""{"capacities":{"gold":10},"goal":{"targets":{"gold":-1}}}""")
        rejects("""{"capacities":{"gold":10},"goal":{"targets":{"gold":0}}}""")
    }

    @Test fun aTargetNeedsACapacityThatCanHoldIt() {
        rejects("""{"goal":{"targets":{"gold":5}}}""")
        rejects("""{"capacities":{"gold":10},"goal":{"targets":{"gold":11}}}""")
        val ok = AccountSettings.fromJson(JSONObject("""{"capacities":{"gold":10},"goal":{"targets":{"gold":10}}}"""))
        assertEquals(GoalMatch.ALL, ok.goal.match)
    }

    @Test fun opponentSelectionStartsOffAndRoundTrips() {
        assertEquals(OpponentRules(), AccountSettings.fromJson(JSONObject("{}")).opponent)
        val settings = AccountSettings(
            opponent = OpponentRules(
                enabled = true, minimums = mapOf(Resource.DARK_ELIXIR to 3_000L),
                minimumGoldAndElixir = 900_000L, maxSkips = 4, maxSearchMs = 60_000, maxSearchGold = 12_000
            )
        )
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
    }

    @Test fun opponentRulesOutsideTheirBoundsAreRejected() {
        rejects("""{"opponent":{"enabled":"yes"}}""")
        rejects("""{"opponent":{"minimums":{"gems":5}}}""")
        rejects("""{"opponent":{"minimums":{"gold":0}}}""")
        rejects("""{"opponent":{"minimumGoldAndElixir":0}}""")
        rejects("""{"opponent":{"maxSkips":-1}}""")
        rejects("""{"opponent":{"maxSkips":51}}""")
        rejects("""{"opponent":{"maxSearchMs":500}}""")
        rejects("""{"opponent":{"maxSearchMs":300001}}""")
        rejects("""{"opponent":{"maxSearchGold":-1}}""")
    }

    @Test fun collectionStartsOffAndRoundTrips() {
        assertEquals(false, AccountSettings().collectBeforeFarming)
        assertEquals(false, AccountSettings().collectAfterFarming)
        val settings = AccountSettings(collectBeforeFarming = true, collectAfterFarming = true)
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
        rejects("""{"collectBeforeFarming":"yes"}""")
    }

    @Test fun theSavedArmyStartsBlankAndRoundTrips() {
        assertEquals("", AccountSettings().savedArmy)
        // Settings saved before the field load with it blank, which keeps the current army.
        assertEquals("", AccountSettings.fromJson(JSONObject("{}")).savedArmy)
        val settings = AccountSettings(savedArmy = "Army 5")
        assertEquals("Army 5", settings.toJson().getString("savedArmy"))
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
        rejects("""{"savedArmy":5}""")
    }

    private val walls = """"wallsEnabled":true,"capacities":{"gold":20000000,"elixir":20000000}"""

    private fun json(vararg extra: String) = JSONObject("{" + (listOf(walls) + extra).joinToString(",") + "}")

    @Test fun oldCadenceIsIgnoredAndKeepsNoRuntimeMeaning() {
        val settings = AccountSettings.fromJson(
            json(""""attacksPerWallPhase":7""", """"maxWallsPerPhase":9""", """"reserveGold":250000""")
        )
        assertEquals(9, settings.maxWallsPerPhase)
        assertEquals(0L, settings.reserveGold)
        assertEquals(false, settings.toJson().has("attacksPerWallPhase"))
        // Even garbage in the retired field cannot break a load.
        AccountSettings.fromJson(JSONObject("""{"attacksPerWallPhase":"often"}"""))
    }

    @Test fun triggerAndResumeDefaultAndRoundTrip() {
        val defaults = AccountSettings()
        assertEquals(90, defaults.triggerPercent)
        assertEquals(75, defaults.resumePercent)
        val settings = AccountSettings(triggerPercent = 80, resumePercent = 60)
        assertEquals(settings, AccountSettings.fromJson(JSONObject(settings.toJson().toString())))
    }

    @Test fun triggerAndResumeOutsideTheirRangesAreRejected() {
        rejects("""{"triggerPercent":0}""")
        rejects("""{"triggerPercent":101}""")
        rejects("""{"resumePercent":-1}""")
        rejects("""{"triggerPercent":50,"resumePercent":50}""")
        rejects("""{"resumePercent":90}""")
        rejects("""{"triggerPercent":"90"}""")
        AccountSettings.fromJson(JSONObject("""{"triggerPercent":1,"resumePercent":0}"""))
        AccountSettings.fromJson(JSONObject("""{"triggerPercent":100,"resumePercent":99}"""))
    }

    @Test fun wallsNeedACapacityForEverySpendableCurrency() {
        rejects("""{"wallsEnabled":true}""")
        rejects("""{"wallsEnabled":true,"capacities":{"gold":20000000}}""")
        rejects("""{"wallsEnabled":true,"currencyPreference":"elixir","capacities":{"gold":20000000}}""")
        AccountSettings.fromJson(JSONObject("""{"wallsEnabled":true,"currencyPreference":"gold","capacities":{"gold":20000000}}"""))
        AccountSettings.fromJson(JSONObject("""{"wallsEnabled":false}"""))
    }

    @Test fun aGoalAboveTheTriggerLevelIsRejectedForASpendableResource() {
        val message = try {
            AccountSettings.fromJson(json(""""goal":{"targets":{"gold":19000000}}"""))
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        assertEquals(
            "Gold goal 19,000,000 is above the wall trigger 18,000,000. " +
                "Lower the goal, raise the trigger, or set walls to elixir only.",
            message
        )
        // At the trigger level is fine, and so is a raised trigger.
        AccountSettings.fromJson(json(""""goal":{"targets":{"gold":18000000}}"""))
        AccountSettings.fromJson(json(""""triggerPercent":100""", """"goal":{"targets":{"gold":19000000}}"""))
    }

    @Test fun aGoalAboveTheTriggerIsAcceptedForAResourceThePreferenceExcludes() {
        val goal = """"goal":{"targets":{"gold":19000000}}"""
        AccountSettings.fromJson(json(""""currencyPreference":"elixir"""", goal))
        for (preference in listOf("gold", "auto")) {
            try {
                AccountSettings.fromJson(json(""""currencyPreference":"$preference"""", goal))
                fail("Expected $preference to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        // Walls off: goals are never in conflict.
        AccountSettings.fromJson(JSONObject("""{"capacities":{"gold":20000000},$goal}"""))
    }

    @Test fun savedReservesAreRetiredEvenAboveTheTrigger() {
        val settings = AccountSettings.fromJson(json("\"reserveGold\":18000000", "\"reserveElixir\":19000000"))
        assertEquals(0L, settings.reserveGold)
        assertEquals(0L, settings.reserveElixir)
    }

    @Test fun aSavedAccountThatFailsTheWallChecksLoadsWithWallsOffAndAReason() {
        val loaded = AccountSettings.fromJsonForLoad(JSONObject("""{"wallsEnabled":true,"attacks":33}"""))
        assertEquals(false, loaded.wallsEnabled)
        assertEquals(33, loaded.attacks)
        assertEquals("Wall spending needs the gold capacity.", loaded.wallSetupRequired)
        // The saved intent survives a rewrite so fixing the setup does not lose it.
        assertEquals(true, loaded.toJson().getBoolean("wallsEnabled"))
        assertEquals(loaded, AccountSettings.fromJsonForLoad(loaded.toJson()))
        // Settings that pass load untouched.
        val fine = AccountSettings.fromJsonForLoad(json())
        assertEquals(true, fine.wallsEnabled)
        assertEquals(null, fine.wallSetupRequired)
        // Only wall problems are forgiven.
        try {
            AccountSettings.fromJsonForLoad(JSONObject("""{"attacks":0}"""))
            fail("attacks 0 must still be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
