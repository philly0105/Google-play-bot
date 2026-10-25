package local.clashfarmer.insight

import local.clashfarmer.core.CompletionReason
import local.clashfarmer.insight.LogFixtures.event
import local.clashfarmer.insight.LogFixtures.real
import local.clashfarmer.insight.LogFixtures.shaped
import local.clashfarmer.recognition.ArmyRecipes
import local.clashfarmer.recognition.AttackAfterPause
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventTextTest {

    /** Every event name the app logs, read from the source so a new one cannot go unnamed. */
    private fun sourceEventNames(): Set<String> {
        val root = File("src/main/kotlin")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val names = sortedSetOf<String>()
        val direct = Regex("""\b(?:log|logDeviceHealth)\(\s*"([a-z_]+)"""")
        val chosen = Regex("""\blog\(if \(.*?\) "([a-z_]+)" else "([a-z_]+)"""")
        // VillageCamera logs "<prefix>_camera_<what>", the prefix being each caller's last argument.
        val cameraSuffix = Regex(""""\$\{event\}(_camera_[a-z_]+)"""")
        val cameraPrefix = Regex("""registerVillageCamera\([^)]*?"([a-z_]+)"\s*\)""")
        val suffixes = sortedSetOf<String>()
        val prefixes = sortedSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && "insight" !in it.path }.forEach { f ->
            val text = f.readText()
            direct.findAll(text).forEach { names += it.groupValues[1] }
            chosen.findAll(text).forEach { names += it.groupValues[1]; names += it.groupValues[2] }
            cameraSuffix.findAll(text).forEach { suffixes += it.groupValues[1] }
            cameraPrefix.findAll(text).forEach { prefixes += it.groupValues[1] }
        }
        assertEquals(setOf("_camera_registered", "_camera_unmatched", "_camera_unreferenced"), suffixes)
        assertTrue("no registerVillageCamera caller found", prefixes.isNotEmpty())
        for (p in prefixes) for (s in suffixes) names += p + s
        // Named indirectly (ScreenReader, OpponentSelector, GestureRetry) or only in older logs.
        names += listOf(
            "scout_loot_unreadable", "battle_loot_unreadable", "opponent_accepted", "opponent_fallback",
            "opponent_battle_started", "gesture_resent_after_pause", "hero_card_unverified", "walls_disabled_after_mismatch"
        )
        return names
    }

    private fun assertPlain(name: String, text: String) {
        assertFalse("$name: '$text' has an underscore", text.contains('_'))
        assertFalse("$name: '$text' has the raw name", text.contains(name))
        assertTrue("$name: empty", text.isNotBlank())
    }

    @Test
    fun everyLoggedEventHasAPlainSentence() {
        val names = sourceEventNames()
        assertTrue("found only ${names.size} names", names.size > 150)
        for (name in names) {
            assertTrue("no sentence for $name", EventText.isKnown(name))
            val bare = JSONObject().put("time", "2026-10-08T07:00:00.000Z").put("event", name)
            assertPlain(name, EventText.technical(bare))
            EventText.describe(bare)?.let { assertPlain(name, it.text) }
        }
    }

    @Test
    fun everyFixtureLineReadsPlainly() {
        for ((name, line) in LogFixtures.REAL + LogFixtures.SHAPED) {
            val e = JSONObject(line)
            assertPlain(name, EventText.technical(e))
        }
        for (line in LogFixtures.SMOKE_RUN + LogFixtures.WALLS_ONLY_MAXED + LogFixtures.WALLS_ONLY_MAXED_FINISH +
                LogFixtures.FULL_WALLS_MAXED_COMBINED) {
            val e = JSONObject(line)
            assertPlain(e.getString("event"), EventText.technical(e))
        }
    }

    @Test
    fun chatterAndUnknownNamesStayOffTheTimeline() {
        for (name in listOf("action_step", "amount_second_frame", "wall_list_page", "card_retry", "deploy_timing",
                "handle_action", "account_identity_candidate", "new_intent_action", "opponent_reread", "wall_buy_reread")) {
            assertNull(name, EventText.describe(event("2026-10-08T07:00:00Z", name)))
        }
        val unknown = event("2026-10-08T07:00:00Z", "brand_new_thing")
        assertNull(EventText.describe(unknown))
        assertEquals("Technical: brand new thing", EventText.technical(unknown))
    }

    private fun text(e: JSONObject, result: JSONObject? = null): String = EventText.describe(e, result)!!.text

    @Test
    fun runStartAndStop() {
        assertEquals("Test attack started (supervised)", text(real("session_started")))
        val sweat = JSONObject(LogFixtures.SMOKE_RUN[5])
        assertEquals("Run started: 40 attacks, walls on", text(sweat))
        val timed = JSONObject(sweat.toString()).put("maxAttacks", 100).put("maxMinutes", 120)
        assertEquals("Run started: 2 h, walls on", text(timed))

        val stop = EventText.describe(real("run_stopped"))!!
        assertEquals("Stopped: storage full, nothing left to spend it on", stop.text)
        assertEquals(Tone.BAD, stop.tone)
        val user = EventText.describe(event("2026-10-08T07:00:00Z", "run_stopped", "reason" to "Stopped from floating panel"))!!
        assertEquals("Stopped: you pressed Stop on the status panel", user.text)
        assertEquals(Tone.NEUTRAL, user.tone)
    }

    @Test
    fun sessionFinishedHasASentencePerCompletionReason() {
        val expected = mapOf(
            CompletionReason.QUOTA_REACHED to "Finished: 12 attacks done",
            CompletionReason.GOAL_REACHED to "Finished: farming goal reached after 12 attacks",
            CompletionReason.STORAGE_FULL to "Finished: storage full after 12 attacks",
            CompletionReason.WALL_PENDING to "Finished: an earlier wall purchase still needs checking after 12 attacks",
            CompletionReason.TIME_LIMIT to "Finished: time limit reached after 12 attacks",
            CompletionReason.DEVICE_GUARD to "Finished early to save the battery after 12 attacks"
        )
        assertEquals(CompletionReason.entries.toSet(), expected.keys)
        for ((reason, sentence) in expected) {
            assertEquals(sentence, text(event("2026-10-08T07:00:00Z", "session_finished", "completed" to 12, "reason" to reason.key)))
        }
        val real = JSONObject(LogFixtures.SMOKE_RUN.last { it.contains("session_finished") })
        assertEquals("Finished: storage full after 1 attack; no wall left to buy", text(real))
        val hot = event("2026-10-08T07:00:00Z", "session_finished", "completed" to 3, "reason" to "device_guard",
            "detail" to "Phone too hot: 41.2 C (limit 41.0 C)")
        assertEquals("Finished early to save the battery after 3 attacks: phone too hot, 41.2 C (limit 41.0 C)", text(hot))
    }

    @Test
    fun attackCompletedTakesLootFromItsBattleResult() {
        val attack = JSONObject(LogFixtures.SMOKE_RUN.first { it.contains("attack_completed") })
        val result = JSONObject(LogFixtures.SMOKE_RUN.first { it.contains("battle_result") })
        assertEquals("Attack 1 done: +560k gold, +657k elixir, +5.5k dark elixir", text(attack, result))
        assertEquals("Attack 1 of 40 done; loot not read", text(attack))
        val unread = JSONObject(result.toString()).put("earned", "")
        assertEquals("Attack 1 of 40 done; loot not read", text(attack, unread))
    }

    @Test
    fun requiredMappings() {
        val expected = mapOf(
            "account_switch_verified" to "Switched to Sweat",
            "wall_upgraded" to "Bought 1 wall to level 18 for 5.6M gold",
            "wall_phase_skipped_no_builder" to "Walls skipped: no builder free (0 of 6)",
            "wall_purchase_outcome_unknown" to "Wall purchase not confirmed: 3 walls for 24.0M gold; check the village",
            "wall_spend_unverified" to "Wall spend not proven: 24.0M gold may have been spent; check the village",
            "pending_purchase_acknowledged" to "Pending wall purchase checked and cleared",
            "village_taken_elsewhere" to "Village opened on another device",
            "village_retry_wait" to "Waiting 10 min before taking the village back (98 tries left)",
            "run_auto_restart" to "Restarting the run (try 1 of 3) after: the game showed a screen the run did not expect",
            "run_recovered" to "Run recovered and carried on",
            "run_paused" to "Paused on the scout screen",
            "run_resumed" to "Resumed on the scout screen",
            "early_end" to "Ended the battle early at 76% damage (target 66%)",
            "collection_finished" to "Collected +228k gold, +227k elixir (6 of 17 collectors)",
            "premium_dialog_refused" to "Closed a gem offer without buying",
            "notification_posted" to "Notification sent: Sweat: Run finished",
            "inspect_completed" to "Inspected the screen: an unknown screen, 3 text boxes",
            "remote_command" to "Remote page started 2 h + walls on Blood",
            "combined_run_started" to "Both accounts run started: Sweat 1 attack, then Blood 40 attacks, up to 2 h",
            "combined_run_completed" to "Both accounts run finished: first 0 attacks (goal reached), second 0 attacks (goal reached)",
            "wall_session_finished" to "Walls-only run finished: 0 purchases; no builder was free"
        )
        for ((name, sentence) in expected) assertEquals(name, sentence, text(real(name)))

        val shapedExpected = mapOf(
            "account_handoff" to "Sweat done after 40 attacks (complete); handing over to Blood",
            "walls_off_builders_busy" to "Walls paused for this run: every builder is busy",
            "device_guard_stop" to "Ending at home to save the battery: battery 12%, not charging, 36.5 C",
            "popup_blocked_premium" to "A gem offer is in the way; nothing was bought",
            "connection_lost" to "Clash lost its server connection",
            "maintenance_break" to "Clash is in a maintenance break; waiting it out",
            "game_update_required" to "Clash needs an update from the store",
            "remote_start_refused" to "Remote start refused for Blood: Windows was locked",
            "failure_snapshot" to "Saved the screen and log for the stop"
        )
        for ((name, sentence) in shapedExpected) assertEquals(name, sentence, text(shaped(name)))
        assertEquals(Tone.BAD, EventText.describe(real("wall_purchase_outcome_unknown"))!!.tone)
        assertEquals(Tone.BAD, EventText.describe(real("village_taken_elsewhere"))!!.tone)
    }

    @Test
    fun aWallsOnlyRunSaysWhenTheVillageDidNotRead() {
        val finished = event("2026-10-14T07:58:00Z", "wall_session_finished",
            "upgrades" to 0, "noFreeBuilder" to false, "balancesUnread" to true)
        assertEquals("Walls-only run finished: 0 purchases; the village balances could not be read", text(finished))
        val unread = event("2026-10-14T07:58:00Z", "manual_walls_stopped_nothing_to_spend",
            "reason" to "village balances could not be read", "balances" to "")
        assertEquals("Walls-only run stopped: village balances could not be read", text(unread))
        assertEquals(Tone.WARN, EventText.describe(unread)!!.tone)
        val spent = event("2026-10-14T07:58:00Z", "manual_walls_stopped_nothing_to_spend",
            "reason" to "no currency left to spend", "balances" to "gold=120000")
        assertEquals(Tone.NEUTRAL, EventText.describe(spent)!!.tone)
        val darkOnly = event("2026-10-14T07:58:00Z", "manual_walls_stopped_nothing_to_spend",
            "reason" to "village balances could not be read", "unread" to true, "balances" to "dark_elixir=180940")
        assertEquals(Tone.WARN, EventText.describe(darkOnly)!!.tone)
    }

    @Test
    fun aWallsOnlyRunOnMaxedWallsSaysEveryWallIsMaxed() {
        val lines = LogFixtures.WALLS_ONLY_MAXED.map { JSONObject(it) }
        // Logged as supervised too: it is still a walls-only run, not a test attack.
        assertEquals("Walls-only run started, up to 30 min", text(lines[1]))
        assertEquals("No walls left to upgrade in the builder's list", text(lines[2]))
        assertEquals("Wall phase result: no walls left to upgrade", EventText.technical(lines[3]))
        // Logged before the line carried noWalls.
        assertEquals("Walls-only run finished: 0 purchases", text(lines[4]))
        val now = EventText.describe(JSONObject(LogFixtures.WALLS_ONLY_MAXED_FINISH))!!
        assertEquals("Walls-only run finished: 0 purchases; every wall is maxed", now.text)
        assertEquals(Tone.OK, now.tone)
        // No free builder still reads as before.
        val noBuilder = JSONObject(LogFixtures.WALLS_ONLY_MAXED_FINISH).put("noFreeBuilder", true)
        assertEquals("Walls-only run finished: 0 purchases; no builder was free", text(noBuilder))
        assertEquals(Tone.WARN, EventText.describe(noBuilder)!!.tone)
    }

    @Test
    fun aStorageStopWithEveryWallMaxedSaysSoAndTheOtherStorageStopsReadAsBefore() {
        assertEquals("storage full, every wall is maxed", EventText.stopLine("Storage full; every wall is maxed"))
        assertEquals("storage full, no free builder", EventText.stopLine("Storage full; walls skipped: no builder is free"))
        assertEquals("storage full, nothing left to spend it on", EventText.stopLine("Storage full; wall spending is off"))
        assertEquals("storage full, nothing left to spend it on", EventText.stopLine("Storage full; no wall left to buy"))
        val finished = JSONObject(LogFixtures.FULL_WALLS_MAXED.first { it.contains("session_finished") })
        assertEquals("Finished: storage full after 1 attack; every wall is maxed", text(finished))
    }

    @Test
    fun failureSnapshotLinesGetTheirNameBack() {
        val snap = shaped("failure_snapshot")
        assertEquals("run_stopped", snap.getString("event"))
        assertEquals("failure_snapshot", EventText.nameOf(snap))
        val failed = event("2026-10-08T07:00:00Z", "auto_restart", "error" to "disk full")
        assertEquals("failure_snapshot_failed", EventText.nameOf(failed))
        assertEquals("run_stopped", EventText.nameOf(real("run_stopped")))
        assertEquals("run_stopped", EventText.triggerOf(snap))
        assertEquals("Could not save the screen and log for the automatic restart", text(failed))

        // A transition timeout's snapshot (old shape) is not a second timeout line.
        val timeoutSnap = event("2026-10-08T07:00:00Z", "transition_timeout", "file" to "captures/failure-transition_timeout-20261008-070000.png")
        assertEquals("failure_snapshot", EventText.nameOf(timeoutSnap))
        assertEquals("Saved the screen and log for the screen that did not arrive", text(timeoutSnap))
        val timeout = event("2026-10-08T07:00:00Z", "transition_timeout", "expected" to "HOME", "actual" to "UNKNOWN")
        assertEquals("transition_timeout", EventText.nameOf(timeout))
        assertEquals(null, EventText.triggerOf(timeout))
    }

    @Test
    fun failureSnapshotLinesSince3637028CarryTheirTrigger() {
        val snap = event("2026-10-08T07:00:00Z", "failure_snapshot", "trigger" to "recovery_failed",
            "file" to "captures/failure-recovery_failed-20261008-070000.png")
        assertEquals("failure_snapshot", EventText.nameOf(snap))
        assertEquals("recovery_failed", EventText.triggerOf(snap))
        assertEquals("Saved the screen and log for the failed recovery", text(snap))
        val failed = event("2026-10-08T07:00:00Z", "failure_snapshot_failed", "trigger" to "run_stopped", "error" to "disk full")
        assertEquals("failure_snapshot_failed", EventText.nameOf(failed))
        assertEquals("Could not save the screen and log for the stop", text(failed))
        // An unfamiliar trigger still reads plainly.
        assertEquals("Saved the screen and log for this failure",
            text(event("2026-10-08T07:00:00Z", "failure_snapshot", "trigger" to "brand_new", "file" to "captures/x.png")))
    }

    @Test
    fun aTrayCheckReadsAsATrayCheckNotAsAnInspection() {
        fun tray(vararg fields: Pair<String, Any?>) =
            EventText.describe(event("2026-10-14T09:00:00Z", "inspect_completed", "check" to "tray", *fields))!!
        val slots = "valkyries=40/97/ok king=-/112/ok queen=-/121/ok warden=-/104/ok champion=-/99/ok rages=5/105/ok jumps=1/101/ok"
        val right = tray("state" to "SCOUT", "layout" to "plain", "wrong" to 0, "blank" to false, "slots" to slots)
        assertEquals("Checked the battle tray: all 7 slots look right", right.text)
        assertEquals(Tone.OK, right.tone)

        val wrong = tray("state" to "SCOUT", "layout" to "plain", "wrong" to 1, "blank" to false,
            "slots" to slots.replace("rages=5/105/ok", "rages=-/105/bad"))
        assertEquals("Checked the battle tray: 1 of 7 slots look wrong", wrong.text)
        assertEquals(Tone.BAD, wrong.tone)

        val unsure = tray("state" to "SCOUT", "layout" to "event", "wrong" to 0, "blank" to false,
            "slots" to slots.replace("valkyries=40/97/ok", "event=-/58/warn"))
        assertEquals("Checked the battle tray: 1 of 7 slots need a look" to Tone.WARN, unsure.text to unsure.tone)

        val blank = tray("state" to "SCOUT", "layout" to "unknown", "wrong" to 0, "blank" to true,
            "slots" to slots.replace("/ok", "/warn"))
        assertEquals("Checked the battle tray: no count badge read; take the check again" to Tone.WARN, blank.text to blank.tone)

        // A frame it could not check is logged without a layout.
        assertEquals("Tray check stopped: not a scout screen (village screen)",
            tray("state" to "HOME", "width" to 1600, "height" to 720).text)
        assertEquals("Tray check stopped: Clash was still turning", tray("state" to "SCOUT", "width" to 720, "height" to 1600).text)
        // A portrait frame is refused for its size whatever screen it was read as.
        assertEquals("Tray check stopped: Clash was still turning", tray("state" to "UNKNOWN", "width" to 720, "height" to 1600).text)

        val failed = EventText.describe(event("2026-10-14T09:00:00Z", "inspect_failed", "check" to "tray", "error" to "timeout"))!!
        assertEquals("The tray check failed" to Tone.WARN, failed.text to failed.tone)
        // Inspect itself reads as before.
        assertEquals("Inspecting the screen failed", text(event("2026-10-14T09:00:00Z", "inspect_failed", "error" to "timeout")))
        assertEquals("Remote start refused for Sweat: the app is reading the game screen for Inspect or the tray check; try again when it ends",
            text(event("2026-10-14T09:00:00Z", "remote_start_refused", "account" to "Sweat",
                "reason" to "The app is reading the game screen for Inspect or the tray check; try again when it ends")))
    }

    @Test
    fun theSavedArmyReadsByItsTitleAndOutcome() {
        fun recipe(outcome: String, vararg more: Pair<String, Any?>) =
            EventText.describe(event("2026-10-15T08:00:00Z", "army_recipe", "title" to "Army 5", "outcome" to outcome,
                "swipes" to 1, *more))!!
        val loaded = recipe("loaded", "read" to "ARMY 5", "toast" to true)
        assertEquals("Loaded the saved army Army 5" to Tone.OK, loaded.text to loaded.tone)
        val again = recipe("loaded", "read" to "ARMY 5", "toast" to true, "tries" to 2)
        assertEquals("Loaded the saved army Army 5 on a second try" to Tone.OK, again.text to again.tone)
        // Logged before Attack!, so a miss says what the run goes on to do.
        val missing = recipe("not_found")
        assertEquals("Could not find the saved army Army 5 in Saved Recipes; going on with the current army" to Tone.WARN,
            missing.text to missing.tone)
        assertEquals("Tapped Use on the saved army Army 5 but could not confirm it loaded; going on with the army on screen",
            recipe("not_confirmed", "read" to "ARMY 5").text)
        assertEquals("Could not open Saved Recipes to load the saved army Army 5; going on with the current army", recipe("no_list").text)
        assertEquals("Lost the Saved Recipes list while looking for the saved army Army 5; going on with the current army",
            recipe("lost_list").text)
        assertEquals("Could not read the saved army Army 5 in the same place twice, so Use was not tapped; going on with the current army",
            recipe("moved", "read" to "ARMY 5").text)
        assertEquals("A pause cut short the tap on Use for the saved army Army 5 and the list had changed after it; the tap may have gone through, so Use was not tapped again; going on with the army on screen",
            recipe("use_interrupted", "read" to "ARMY 5").text)
        for (outcome in listOf("not_found", "not_confirmed", "no_list", "lost_list", "moved", "use_interrupted")) {
            assertEquals(outcome, Tone.WARN, recipe(outcome).tone)
        }

        val switched = EventText.describe(event("2026-10-15T08:00:00Z", "army_tab_switched", "from" to "SAVED_RECIPES"))!!
        assertEquals("Tapped My Army to leave the Saved Recipes list" to Tone.NEUTRAL, switched.text to switched.tone)
        val unknown = EventText.describe(event("2026-10-15T08:00:00Z", "army_tab_unknown", "tab" to "UNKNOWN", "state" to "ARMY"))!!
        assertEquals("Could not tell which army tab was showing, so Attack! was not pressed" to Tone.WARN, unknown.text to unknown.tone)
        assertEquals("The Saved Recipes list stayed open, so Attack! was not pressed",
            text(event("2026-10-15T08:00:00Z", "army_tab_unknown", "tab" to "SAVED_RECIPES", "state" to "ARMY")))
        assertEquals("the screen stayed unrecognized as My Army, so Attack! was not pressed", EventText.stopLine(ArmyRecipes.TAB_UNKNOWN))

        fun afterPause(decision: AttackAfterPause, state: String, tab: String) = EventText.describe(event(
            "2026-10-15T08:00:00Z", "army_after_pause", "state" to state, "tab" to tab, "decision" to decision.name))!!
        assertEquals("My Army was showing after the pause; pressing Attack!" to Tone.NEUTRAL,
            afterPause(AttackAfterPause.SEND, "ARMY", "MY_ARMY").let { it.text to it.tone })
        assertEquals("Attack! had gone through before the pause" to Tone.NEUTRAL,
            afterPause(AttackAfterPause.PASSED, "SEARCHING", "UNKNOWN").let { it.text to it.tone })
        assertEquals("My Army was not showing after the pause; going back to it before Attack!" to Tone.NEUTRAL,
            afterPause(AttackAfterPause.READY_AGAIN, "ARMY", "SAVED_RECIPES").let { it.text to it.tone })
        assertEquals("Could not tell the army tab after the pause; looking again before Attack!" to Tone.NEUTRAL,
            afterPause(AttackAfterPause.READY_AGAIN, "ARMY", "UNKNOWN").let { it.text to it.tone })
        assertEquals("My Army was still not showing after a second pause, so Attack! was not pressed" to Tone.WARN,
            afterPause(AttackAfterPause.STOP, "ARMY", "SAVED_RECIPES").let { it.text to it.tone })
        assertEquals("Could still not tell the army tab after a second pause, so Attack! was not pressed" to Tone.WARN,
            afterPause(AttackAfterPause.STOP, "ARMY", "UNKNOWN").let { it.text to it.tone })
        assertEquals("The army screen was gone after the pause, so Attack! was not pressed" to Tone.WARN,
            afterPause(AttackAfterPause.STOP, "HOME", "UNKNOWN").let { it.text to it.tone })
    }

    @Test
    fun stopReasonsFromRealLogsReadPlainly() {
        // Every distinct run_stopped and run_cancelled reason in the saved logs.
        val reasons = mapOf(
            "Stopped from floating panel" to "you pressed Stop on the status panel",
            "Stopped from dashboard" to "you pressed Stop in the app",
            "Stopped from remote" to "you pressed Stop on the remote page",
            "Overlay Stop clicked" to "you pressed Stop on the status panel",
            "Service destroyed" to "Clash Farmer closed",
            "Screenshot capture failed: Internal error (1)" to "the Clash window could not be captured",
            "Active session interrupted: app switched to com.android.vending" to "the Clash window could not be found",
            "Run error: Clash is not in foreground (detected: local.clashfarmer)" to "the Clash window could not be found",
            "Current account identity is unreadable or is not one of the enrolled accounts" to "the signed-in village could not be confirmed",
            "Cannot verify account identity because the village home screen is not visible" to "the signed-in village could not be confirmed",
            "Run error: StandaloneCoroutine was cancelled" to "the run was cancelled",
            "Pinch gesture cancelled" to "a touch on the screen cancelled a tap",
            "Tap gesture at Point(x=302, y=650) was cancelled" to "a touch on the screen cancelled a tap",
            "Pinch at Point(x=800, y=360) was cancelled by a touch on the screen" to "a touch on the screen cancelled a tap",
            "Unexpected screen WALL during phase HOME" to "the game showed a screen the run did not expect",
            "Unexpected transition screen: HOME (expected [ARMY]) reading: 259 M 29 Blood" to "the game showed a screen the run did not expect",
            "Screen transition timed out waiting for [SCOUT, BATTLE] (last seen: UNKNOWN) reading: x" to "the game did not reach the expected screen in time",
            "Timed out waiting for home village screen after account switch" to "the game did not reach the expected screen in time",
            "Gold and elixir are full and no wall phase can run again; attacking would waste the loot" to "storage full, nothing left to spend it on",
            "Blood ended before quota reached (14/40 attacks)" to "Blood ended early (14 of 40 attacks)",
            "A wall purchase was confirmed but its spend could not be proven; look at the village and acknowledge the pending purchase before walls run again"
                to "a wall purchase could not be proven; check the village",
            "Village opened on another device" to "the village was opened on another device",
            "Clash lost its server connection" to "Clash lost its server connection",
            "Battery low: 14% and not charging (limit 15%)" to "battery low, 14% and not charging (limit 15%)"
        )
        for ((reason, line) in reasons) {
            assertEquals(reason, line, EventText.stopLine(reason))
            assertFalse(reason, EventText.stopLine(reason).contains('_'))
        }
        assertEquals("no reason given", EventText.stopLine(null))
        assertEquals("missing calibrated attack button anchor", EventText.stopLine("Missing calibrated attack button anchor"))
        assertEquals("unknown state during phase home", EventText.clean("UNKNOWN_STATE during phase HOME."))
    }

    @Test
    fun thePcsOwnStopsSayWhatToDoNext() {
        val reasons = mapOf(
            "The Clash window was closed, and Google Play Games keeps the game running hidden. Opening it again stops " +
                "the bot's taps from reaching the game, so restart the PC, then start again."
                to "the Clash window was closed; restart the PC, then start again",
            "No calibration profile for Blood" to "Blood is not calibrated; calibrate it in Setup",
            "Run error: No calibration profile for Blood" to "Blood is not calibrated; calibrate it in Setup",
            "Clash of Clans could not be started through Google Play Games: launcher missing"
                to "Google Play Games did not open Clash; open Clash by hand, then start again",
            "Clash of Clans was still drawing a blank screen after 30000ms"
                to "Clash stayed on a blank screen; wait for it to load, then start again",
            "The village is not on screen (read SHOP); open it with nothing selected"
                to "Clash was not on the village; close any open window in Clash, then start again",
            "Timed out waiting for Clash of Clans (com.supercell.clashofclans) to enter foreground (read: x)"
                to "the Clash window did not open in time; open Clash, then start again",
            "Device is locked" to "Windows was locked",
            "Stopped from notification" to "you chose Stop from the tray icon"
        )
        for ((reason, line) in reasons) assertEquals(reason, line, EventText.stopLine(reason))
    }

    @Test
    fun aCrashSaysWhereTheDetailsAreNotTheExceptionText() {
        val line = "something went wrong; the Technical log on the Activity tab has the details"
        assertEquals(line, EventText.stopLine("Run error: java.lang.IllegalStateException: index 3 out of bounds"))
        assertEquals(line, EventText.stopLine("Switch error: null"))
        // A crash whose message is a known stop still reads as that stop.
        assertEquals("the run was cancelled", EventText.stopLine("Run error: StandaloneCoroutine was cancelled"))
        // An unknown reason that is not a crash is still shown as it is.
        assertEquals("missing calibrated attack button anchor", EventText.stopLine("Missing calibrated attack button anchor"))
    }

    @Test
    fun endWords() {
        assertEquals("Complete", EventText.endWord("quota_reached"))
        assertEquals("Done", EventText.endWord("quota_reached", short = true))
        assertEquals("Time", EventText.endWord("time_limit", short = true))
        assertEquals("Storage full", EventText.endWord("storage_full"))
        assertEquals("Stopped", EventText.endWord(null))
        assertEquals("Stopped", EventText.endWord("Stopped from remote"))
    }

    @Test
    fun timelineLineCarriesTimeAccountAndToneAsJson() {
        val line = EventText.describe(real("wall_purchase_outcome_unknown"))
        assertNotNull(line)
        val json = line!!.toJson()
        assertEquals(Format.parseTime("2026-10-08T09:33:25.580Z"), json.getLong("time"))
        assertEquals("bad", json.getString("tone"))
        assertEquals("Blood", json.getString("account"))
        assertFalse(json.has("event"))
    }

    @Test
    fun theShadesHoldAndTheNotificationStopReadPlainly() {
        val held = event("2026-10-14T07:58:00Z", "run_held", "reason" to "system window open")
        assertEquals("Paused while the notification shade was open", text(held))
        assertEquals(Tone.IDLE, EventText.describe(held)!!.tone)
        assertEquals("Resumed once Clash was back in front", text(event("2026-10-14T07:58:09Z", "run_hold_released")))
        assertEquals("you chose Stop from the tray icon", EventText.stopLine("Stopped from notification"))
        assertEquals("you chose Stop from the tray icon", EventText.stopLine("Session is stopped: Stopped from notification"))
    }

    @Test
    fun aPauseDuringAGestureNamesTheGestureNotAScreen() {
        val tap = event("2026-10-14T07:58:00Z", "run_paused", "at" to "Tap at Point(x=218, y=188)")
        assertEquals("Paused during a tap", text(tap))
        val swipe = event("2026-10-14T07:58:09Z", "run_resumed", "at" to "Swipe from Point(x=1, y=2) to Point(x=3, y=4)")
        assertEquals("Resumed during a swipe", text(swipe))
        assertEquals("Paused during a pinch", text(event("2026-10-14T07:58:00Z", "run_paused", "at" to "Pinch at Point(x=800, y=360)")))
        assertEquals("Paused on the village screen", text(event("2026-10-14T07:58:00Z", "run_paused", "at" to "HOME")))
        assertEquals("Paused", text(event("2026-10-14T07:58:00Z", "run_paused")))
    }

    @Test
    fun aLongHoldAndItsCapReadPlainly() {
        val held = event("2026-10-14T07:58:00Z", "run_held", "reason" to "system window open", "active" to "com.android.systemui", "at" to "tap")
        assertEquals("Paused while the notification shade was open", text(held))
        val still = event("2026-10-14T07:58:30Z", "run_still_held", "active" to "com.android.systemui", "source" to "announced")
        assertEquals("Still paused after 30 s: the notification shade still reads as open", text(still))
        assertEquals(Tone.IDLE, EventText.describe(still)!!.tone)
        val timeout = event("2026-10-14T08:08:00Z", "run_hold_timeout", "active" to "com.android.systemui")
        // The Stopped line below is the cap's one line on the timeline; this one is detail.
        assertNull(EventText.describe(timeout))
        assertEquals("The notification shade was open for 10 minutes; ending the run", EventText.technical(timeout))

        val reason = "The notification shade stayed open for 10 minutes"
        assertEquals("the notification shade stayed open for 10 minutes", EventText.stopLine(reason))
        assertEquals("the notification shade stayed open for 10 minutes", EventText.stopLine("Session is stopped: $reason"))
        val stopped = event("2026-10-14T08:08:00Z", "run_stopped", "reason" to reason)
        assertEquals("Stopped: the notification shade stayed open for 10 minutes", text(stopped))
        // An ordinary end, amber rather than a stop that needs a person.
        assertEquals(Tone.WARN, EventText.describe(stopped)!!.tone)
    }

    @Test
    fun aTrayThatNoLongerMatchesReadsPlainly() {
        val left = event("2026-10-14T15:26:10Z", "tray_mismatch", "slot" to "king", "raw" to "x1,x1,x1",
            "verdict" to "King: shows x1, so this is not a hero card (a hero card has no count badge)",
            "rawAgain" to "x1,x1", "confirmed" to true, "state" to "SCOUT")
        assertEquals("Left before deploying: the Barbarian King's card showed a troop count", text(left))
        assertEquals(Tone.BAD, EventText.describe(left)!!.tone)
        val once = event("2026-10-14T15:26:10Z", "tray_mismatch", "slot" to "warden", "confirmed" to false)
        assertEquals("The Grand Warden's card read a troop count once, not on a second look; attacked as usual", text(once))
        assertEquals(Tone.WARN, EventText.describe(once)!!.tone)

        // The village's name is beside the line already, so the stop line drops it rather than lower its capital.
        val mismatch = "Blood's army no longer matches its tray setup: the King's card shows a troop count. " +
            "Check the tray in Setup, then recalibrate it."
        assertEquals("the army no longer matches its tray setup: the King's card shows a troop count", EventText.stopLine(mismatch))
        assertEquals("the army no longer matches its tray setup: the King's card shows a troop count, and End Battle was not found",
            EventText.stopLine("$mismatch End Battle was not found, so the scout was left on screen."))
        val stayed = "This account's heroes did not leave the tray on 2 attacks in a row, so the tray setup may be wrong. " +
            "Check the tray in Setup, then recalibrate it."
        assertEquals("the heroes did not leave the tray on 2 attacks in a row", EventText.stopLine(stayed))
        val stopped = event("2026-10-14T15:26:40Z", "run_stopped", "reason" to mismatch)
        assertEquals("Stopped: the army no longer matches its tray setup: the King's card shows a troop count", text(stopped))
        assertEquals(Tone.BAD, EventText.describe(stopped)!!.tone)

        // Only a hero seen on the field is deployed; the others say so.
        assertEquals("Could not confirm the Royal Champion left the tray",
            EventText.technical(event("2026-10-14T15:27:00Z", "hero_card_unverified", "hero" to "champion", "barRows" to 0)))
    }

    @Test
    fun aTroopReadAsTheEventCardReadsPlainly() {
        // The stop line keeps the first sentence, which says the tray was read as an event tray.
        val mismatch = "Blood's army no longer matches its tray setup: the Champion's card shows a troop count, with the " +
            "tray read as starting with an event card. If there is no event card, the bot read another troop with " +
            "a count of x5 or more right of the main troop as one, and the hero cards are not where they were " +
            "calibrated either. Check the tray in Setup, then recalibrate it."
        assertEquals("the army no longer matches its tray setup: the Champion's card shows a troop count, with the tray " +
            "read as starting with an event card", EventText.stopLine(mismatch))

        // Each attack an event layout is deployed plain says so in the technical log only.
        val ruledOut = event("2026-10-25T09:00:00Z", "event_card_ruled_out", "slot1" to 44, "slot2" to 20, "heroCheck" to true)
        assertNull(EventText.describe(ruledOut))
        assertEquals("The card right of the main troop was another troop, not an event card; deployed the normal army",
            EventText.technical(ruledOut))
    }

    @Test
    fun aWallRowLostBeforeItsTapIsOnTheTimelineAndEachLookIsNot() {
        val lost = EventText.describe(event("2026-10-15T08:00:00Z", "wall_list_pick_lost", "picked" to "Wall x80@582",
            "missed" to false, "rows" to 10))!!
        assertEquals("The Wall row moved before it could be tapped; reading on down the list" to Tone.NEUTRAL, lost.text to lost.tone)
        val look = event("2026-10-15T08:00:00Z", "wall_list_confirm", "frame" to 1, "agreed" to null, "pick" to null)
        assertNull(EventText.describe(look))
        assertEquals("Looked again at the Wall row before tapping it", EventText.technical(look))
    }
}
