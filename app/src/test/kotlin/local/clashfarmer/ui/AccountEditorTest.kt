package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.GoalMatch
import local.clashfarmer.core.Launch
import local.clashfarmer.core.Length
import local.clashfarmer.core.Policy
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account editor's rules: parse, error placement, sections and the round trip of today's accounts. */
class AccountEditorTest {
    /** Sweat and Blood as the phone's index held them on 29 September (wall-gap-phone-final-index.json). */
    private val sweatJson = """
        {"id":"f4a3bda6-b13d-3d81-a8e5-c5602dbbb500","villageName":"Sweat","supercellName":"Philly0105","playerTag":"#8YQCYG2RU",
         "settings":{"attacks":15,"maxMinutes":120,"wallsEnabled":true,"maxWallLevel":18,"currencyPreference":"elixir",
          "maxWallsPerPhase":20,"goal":{"match":"all","targets":{}},"capacities":{"gold":25500000,"elixir":26000000},
          "opponent":{"enabled":true,"minimums":{"gold":650000},"maxSkips":5,"maxSearchMs":90000,"maxSearchGold":7500},
          "collectBeforeFarming":false,"collectAfterFarming":false,
          "earlyEnd":{"enabled":false,"afterDeploySeconds":0,"battleSecondsLeft":0,"lootLeftBelow":{}},
          "triggerPercent":90,"resumePercent":75},
         "lastResult":{"timestamp":1790674566313,"attacksCompleted":10,"attackLimit":40,"wallsUpgraded":0,"goldSpent":0,
          "elixirSpent":0,"purchaseOutcomeUnknown":false,"unknownPurchaseCurrency":null,"unknownPurchaseCost":0,
          "unknownPurchaseWalls":0,"status":"Stopped","stopReason":"Stopped from floating panel","completionReason":null}}
    """.trimIndent()

    private val bloodJson = """
        {"id":"0130d53a-41a1-38cf-9069-f88b32445475","villageName":"Blood","supercellName":"VastMaNiSh","playerTag":"#Y2G29YJQ8",
         "settings":{"attacks":40,"maxMinutes":120,"wallsEnabled":true,"maxWallLevel":19,"currencyPreference":"auto",
          "maxWallsPerPhase":40,"goal":{"match":"all","targets":{}},"capacities":{},
          "opponent":{"enabled":false,"minimums":{},"maxSkips":10,"maxSearchMs":90000,"maxSearchGold":0},
          "collectBeforeFarming":false,"collectAfterFarming":false,
          "earlyEnd":{"enabled":false,"afterDeploySeconds":0,"battleSecondsLeft":0,"lootLeftBelow":{}}}}
    """.trimIndent()

    private fun record(json: String) = AccountRecord.fromJson(JSONObject(json))

    /** What the sheet hands back when nothing is touched: every box as it was filled. */
    private fun untouched(account: AccountRecord): SettingsFields {
        val fields = FarmingSettingsEditor.fieldsOf(account.settings)
        return FarmingSettingsEditor.withTexts(fields.copy(reserveGold = "0", reserveElixir = "0"), FarmingSettingsEditor.textsOf(fields))
    }

    private fun sweat() = record(sweatJson)

    @Test
    fun triggerBelowResumeIsRefusedUnderResume() {
        val account = sweat()
        val fields = untouched(account).copy(triggerPercent = "60", resumePercent = "70")
        val edit = FarmingSettingsEditor.parseFor(account, fields) as SettingsEdit.Invalid
        val message = edit.errors.getValue("form")
        assertTrue(message, message.startsWith("Resume percent must be between 0 and 59"))
        val placed = FarmingSettingsEditor.place(edit.errors)
        assertEquals(setOf("resumePercent"), placed.byField.keys)
        assertTrue(placed.top.isEmpty())
        assertEquals(SettingsSection.WALLS, FarmingSettingsEditor.sectionOf("resumePercent"))
    }

    @Test
    fun blankPlayerTagIsRefused() {
        val account = sweat().copy(playerTag = " ")
        val edit = FarmingSettingsEditor.parseFor(account, untouched(account)) as SettingsEdit.Invalid
        assertEquals(mapOf("form" to "Player tag cannot be blank"), edit.errors)
        // No box owns the tag, so it shows at the top of the sheet.
        val placed = FarmingSettingsEditor.place(edit.errors)
        assertEquals(listOf("Player tag cannot be blank"), placed.top)
        assertTrue(placed.byField.isEmpty())
    }

    @Test
    fun todaysSweatRoundTripsUnchanged() {
        val account = sweat()
        val edit = FarmingSettingsEditor.parseFor(account, untouched(account)) as SettingsEdit.Valid
        assertEquals(account.settings, edit.settings)
        // Saved as the editor saves: only the settings replaced, the run result kept.
        assertEquals(account.toJson().toString(), account.copy(settings = edit.settings).toJson().toString())
        assertEquals(JSONObject(sweatJson).getJSONObject("lastResult").toString(),
            account.copy(settings = edit.settings).toJson().getJSONObject("lastResult").toString())
    }

    @Test
    fun bloodWithCapacitiesRoundTripsUnchanged() {
        val obj = JSONObject(bloodJson)
        obj.getJSONObject("settings").put("capacities", JSONObject().put("gold", 25_500_000L).put("elixir", 26_000_000L))
        val account = AccountRecord.fromJson(obj)
        assertNull(account.settings.wallSetupRequired)
        val edit = FarmingSettingsEditor.parseFor(account, untouched(account)) as SettingsEdit.Valid
        assertEquals(account.settings, edit.settings)
        assertEquals(account.toJson().toString(), account.copy(settings = edit.settings).toJson().toString())
    }

    @Test
    fun bloodWithoutCapacitiesKeepsWallsOnAndSaysWhyUnderGoldCapacity() {
        // Loaded walls-off for want of capacities; the editor shows them on so saving names the reason.
        val account = record(bloodJson)
        assertEquals("Wall spending needs the gold capacity.", account.settings.wallSetupRequired)
        val fields = untouched(account)
        assertTrue(fields.wallsEnabled)
        val edit = FarmingSettingsEditor.parseFor(account, fields) as SettingsEdit.Invalid
        val placed = FarmingSettingsEditor.place(edit.errors)
        assertEquals(mapOf("capacity_gold" to "Wall spending needs the gold capacity."), placed.byField)
        assertEquals(SettingsSection.STORAGE_GOAL, FarmingSettingsEditor.sectionOf("capacity_gold"))
    }

    @Test
    fun everyRangeRuleLandsUnderItsBox() {
        val base = untouched(sweat())
        val cases = listOf(
            base.copy(attacks = "0") to "attacks",
            base.copy(maxMinutes = "121") to "maxMinutes",
            base.copy(triggerPercent = "101") to "triggerPercent",
            base.copy(maxWallLevel = "26") to "maxWallLevel",
            base.copy(maxWallsPerPhase = "0") to "maxWallsPerPhase",
            base.copy(capacities = base.capacities + (Resource.DARK_ELIXIR to "0")) to "capacity_dark_elixir",
            base.copy(goalTargets = mapOf(Resource.DARK_ELIXIR to "5")) to "goal_dark_elixir",
            base.copy(goalTargets = mapOf(Resource.GOLD to "30000000")) to "goal_gold",
            base.copy(minimums = mapOf(Resource.ELIXIR to "0")) to "minimum_elixir",
            base.copy(minimumGoldAndElixir = "0") to "minimumGoldAndElixir",
            base.copy(maxSkips = "51") to "maxSkips",
            base.copy(maxSearchSeconds = "301") to "maxSearchSeconds",
            base.copy(endLootLeftBelow = mapOf(Resource.GOLD to "0")) to "lootLeft_gold",
            base.copy(endDamageMin = "0") to "endDamageMin",
            base.copy(endDamageMin = "90", endDamageMax = "80") to "endDamageMin",
            base.copy(endBattleSecondsLeft = "180") to "endBattleSecondsLeft",
            // A goal above the wall trigger: elixir walls at 90% of 26,000,000.
            base.copy(goalTargets = mapOf(Resource.ELIXIR to "25000000")) to "goal_elixir"
        )
        for ((fields, key) in cases) {
            val edit = FarmingSettingsEditor.parseFor(sweat(), fields)
            assertTrue("$key: $edit", edit is SettingsEdit.Invalid)
            val placed = FarmingSettingsEditor.place((edit as SettingsEdit.Invalid).errors)
            assertEquals("$key: $edit", setOf(key), placed.byField.keys)
            assertTrue(placed.top.isEmpty())
            val message = placed.byField.getValue(key)
            assertTrue(message, !message.contains('_'))
        }
    }

    @Test
    fun everyBoxBelongsToASection() {
        val keys = FarmingSettingsEditor.textsOf(untouched(sweat())).keys
        assertEquals(27, keys.size)
        for (key in keys) assertTrue(key, FarmingSettingsEditor.sectionOf(key) != null)
        assertNull(FarmingSettingsEditor.sectionOf("form"))
        assertEquals(SettingsSection.OPPONENTS, FarmingSettingsEditor.sectionOf("minimumGoldAndElixir"))
        assertEquals(SettingsSection.ENDING, FarmingSettingsEditor.sectionOf("lootLeft_dark_elixir"))
    }

    @Test
    fun blankAmountBoxesMeanNotSet() {
        val fields = FarmingSettingsEditor.withTexts(untouched(sweat()), mapOf("capacity_gold" to " ", "goal_elixir" to "1000"))
        assertEquals(mapOf(Resource.ELIXIR to "26000000"), fields.capacities)
        assertEquals(mapOf(Resource.ELIXIR to "1000"), fields.goalTargets)
    }

    @Test
    fun sectionSummariesReadTheBoxesAsTyped() {
        val sweat = untouched(sweat())
        assertEquals("15 attacks, 2 h", FarmingSettingsEditor.summaryOf(SettingsSection.RUN_LIMITS, sweat))
        assertEquals("On: buy at 90% storage, up to level 18", FarmingSettingsEditor.summaryOf(SettingsSection.WALLS, sweat))
        // The resume level is not in it: most runs spend down to 0% whatever the box says.
        assertEquals("On: buy at 90% storage, up to level 18",
            FarmingSettingsEditor.summaryOf(SettingsSection.WALLS, sweat.copy(resumePercent = "40")))
        assertEquals("Off", FarmingSettingsEditor.summaryOf(SettingsSection.WALLS, sweat.copy(wallsEnabled = false)))
        assertEquals("No goal; 2 capacities set", FarmingSettingsEditor.summaryOf(SettingsSection.STORAGE_GOAL, sweat))
        assertEquals("Goal: any of gold 8.0M; 2 capacities set", FarmingSettingsEditor.summaryOf(SettingsSection.STORAGE_GOAL,
            sweat.copy(goalTargets = mapOf(Resource.GOLD to "8000000"), goalMatch = GoalMatch.ANY)))
        assertEquals("Skip bases under gold 650k, up to 5 skips", FarmingSettingsEditor.summaryOf(SettingsSection.OPPONENTS, sweat))
        assertEquals("Off: every base is attacked", FarmingSettingsEditor.summaryOf(SettingsSection.OPPONENTS, sweat.copy(opponentEnabled = false)))
        assertEquals("Off: battles run to the end", FarmingSettingsEditor.summaryOf(SettingsSection.ENDING, sweat))
        assertEquals("Surrender at 70 to 85% damage, 20 s after deploy", FarmingSettingsEditor.summaryOf(SettingsSection.ENDING,
            sweat.copy(earlyEndEnabled = true, endAtDamage = true, endDamageMax = "85", endAfterDeploySeconds = "20")))
        assertEquals("Off", FarmingSettingsEditor.summaryOf(SettingsSection.COLLECTION, sweat))
        assertEquals("Before and after farming", FarmingSettingsEditor.summaryOf(SettingsSection.COLLECTION,
            sweat.copy(collectBeforeFarming = true, collectAfterFarming = true)))
        assertEquals("Off: the current army is used", FarmingSettingsEditor.summaryOf(SettingsSection.ARMY, sweat))
        assertEquals("Load Army 5 before the first attack", FarmingSettingsEditor.summaryOf(SettingsSection.ARMY,
            sweat.copy(savedArmy = " Army 5 ")))
    }

    @Test
    fun theSavedArmyIsSavedTrimmedAndMustNameACard() {
        val obj = JSONObject(bloodJson)
        obj.getJSONObject("settings").put("capacities", JSONObject().put("gold", 25_500_000L).put("elixir", 26_000_000L))
        val blood = AccountRecord.fromJson(obj)
        assertEquals("", blood.settings.savedArmy)
        val typed = FarmingSettingsEditor.withTexts(untouched(blood), mapOf("savedArmy" to "  Army 5 "))
        val edit = FarmingSettingsEditor.parseFor(blood, typed) as SettingsEdit.Valid
        assertEquals("Army 5", edit.settings.savedArmy)
        assertEquals(blood.settings.copy(savedArmy = "Army 5"), edit.settings)
        // Cleared again, the box keeps the current army.
        val cleared = FarmingSettingsEditor.withTexts(untouched(blood), mapOf("savedArmy" to " "))
        assertEquals("", (FarmingSettingsEditor.parseFor(blood, cleared) as SettingsEdit.Valid).settings.savedArmy)

        val dash = FarmingSettingsEditor.parseFor(blood, typed.copy(savedArmy = "-")) as SettingsEdit.Invalid
        val placed = FarmingSettingsEditor.place(dash.errors)
        assertEquals(mapOf("savedArmy" to "Saved army must be a card's title, such as Army 5"), placed.byField)
        assertTrue(placed.top.isEmpty())
        assertEquals(SettingsSection.ARMY, FarmingSettingsEditor.sectionOf("savedArmy"))
    }

    @Test
    fun resumeNoteSaysWhatTheRunsDo() {
        // The caption under the resume box, held to RunPlan: one account spends down to 0%, both keep the saved level.
        val sweat = sweat()
        val blood = record(bloodJson)
        val accounts = listOf(sweat, blood)
        val one = RunPlan(listOf(sweat.id), Length.Attacks(10), walls = true).launch(accounts, 0L) as Launch.Single
        assertEquals(Policy.TIMED_RUN_RESUME_PERCENT, one.settings.resumePercent)
        val both = RunPlan(listOf(sweat.id, blood.id), Length.Attacks(10), walls = false).launch(accounts, 0L) as Launch.Combined
        assertEquals(75, both.firstSettings.resumePercent)
        assertEquals("Only runs on both accounts stop spending here. Runs on one account, and Walls only, spend down to 0%.",
            FarmingSettingsEditor.RESUME_NOTE)
    }

    @Test
    fun saveFollowsTheRunAndTheSaveInFlight() {
        assertTrue(AccountEditor.saveEnabled(busy = false, saving = false))
        // A run that ends turns it back on; the sheet no longer keeps the state it opened with.
        assertFalse(AccountEditor.saveEnabled(busy = true, saving = false))
        assertFalse(AccountEditor.saveEnabled(busy = false, saving = true))
    }

    @Test
    fun damageUnderTheLeagueFloorGetsANote() {
        assertNull(FarmingSettingsEditor.damageFloorHint("70"))
        assertNull(FarmingSettingsEditor.damageFloorHint(""))
        val hint = FarmingSettingsEditor.damageFloorHint("60")
        assertTrue(hint, hint!!.startsWith("Surrendering under 70% damage loses part of the league bonus"))
    }

    @Test
    fun defaultSettingsRoundTrip() {
        val account = sweat().copy(settings = AccountSettings(capacities = mapOf(Resource.GOLD to 1_000_000L, Resource.ELIXIR to 1_000_000L)))
        val edit = FarmingSettingsEditor.parseFor(account, untouched(account)) as SettingsEdit.Valid
        assertEquals(account.settings, edit.settings)
    }
}
