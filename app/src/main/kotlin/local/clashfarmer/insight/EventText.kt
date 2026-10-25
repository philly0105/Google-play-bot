package local.clashfarmer.insight

import local.clashfarmer.core.AppSwitchLatch
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.DeviceGuard
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.Resource
import local.clashfarmer.core.ReturnHome
import local.clashfarmer.core.TrayCheck
import local.clashfarmer.core.UserStop
import local.clashfarmer.core.WallFarmingCoordinator
import local.clashfarmer.service.RunNotice
import org.json.JSONObject
import java.util.Locale

/** One plain line of the Activity timeline or the remote page: what happened, when, and how it reads. */
data class TimelineLine(
    val timeMs: Long?,
    val text: String,
    val tone: Tone,
    /** The log's event name. For grouping and tests only; never shown. */
    val event: String,
    val accountId: String? = null,
    val accountName: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", timeMs ?: JSONObject.NULL)
        .put("text", text)
        .put("tone", tone.name.lowercase(Locale.ROOT))
        .put("account", accountName ?: JSONObject.NULL)
}

/**
 * Turns a log event into a sentence a person can read. Every event the app logs has one.
 *
 * [describe] is the timeline: it returns null for step-by-step detail ([isDetail]) and for names
 * it does not know. [technical] is the technical log: every event, unknown names as
 * "Technical: <words>". No output carries an underscore or a raw event name.
 */
object EventText {

    /** Never on the timeline: the spec's chatter, the *_reread family and per-step detail. */
    private val DETAIL = setOf(
        // Spec chatter.
        "action_step", "amount_second_frame", "wall_list_page", "card_retry", "deploy_timing", "handle_action",
        "account_identity_candidate", "new_intent_action",
        // Paired into attack_completed, or covered by a run-level line.
        "battle_result", "battle_started", "pause_button", "run_cancelled", "storage_full_stop", "wall_pending_stop",
        "run_hold_timeout",
        // Account switch steps.
        "account_switch_start", "account_switch_required", "picker_row_selected", "welcome_back_dialog_detected",
        // Start and screen navigation.
        "start_return_home", "start_reached_home", "recovery_step", "return_home_target", "tap_retried",
        "transition_stray_frame", "transition_confirmed_exit", "matchmaking_searching", "searching_out_of_phase",
        "village_camera_normalized", "home_sample_not_home", "gesture_resent_after_pause", "waiting_unrecognized",
        "back_withheld", "stop_side_effect", "camera_reference_saved", "camera_reference_retired", "collection_targets_cleared", "layout_editor_closed",
        "main_activity_opened", "gesture_held", "gem_shop_gone", "quit_prompt_no_cancel",
        // Readings.
        "storage_read", "builders_read", "builders_read_disagree", "device_health", "device_health_failed",
        "balance_unreadable", "balance_implausible", "amount_unreadable", "scout_loot_unreadable",
        "scout_countdown_unreadable", "scout_fee_unreadable", "battle_loot_unreadable", "result_loot_unreadable",
        "result_damage_unreadable", "result_frame", "stars_damage_conflict", "frame_encode_failed",
        // Opponent search.
        "opponent_accepted", "opponent_fallback", "opponent_skipped", "opponent_battle_started",
        "opponent_selection_uncalibrated", "opponent_next_held",
        // Inside a battle.
        "attack_wait", "attack_zoomed_out", "hero_deployed", "hero_ability_used", "hero_card_unverified",
        "spell_card_spent", "spell_card_unverified", "troop_card_partially_unspent", "event_card_detected",
        "event_card_ambiguous", "event_attack_planned", "stall_trajectory", "stall_damage_unreadable",
        "stall_would_surrender", "early_end_confirmed", "early_end_confirm_unread", "early_end_damage_floor",
        "early_end_dialog_closed", "early_end_no_button", "deploy_timing_failed",
        "early_end_back_skipped_after_hold", "battle_checked_after_pause", "early_end_quit_prompt",
        // Wall phase steps.
        "wall_checkpoint_triggered", "wall_phase_planned", "wall_phase_outcome", "wall_phase_no_builder",
        "wall_builders_busy", "wall_builders_busy_close", "wall_builders_busy_gone", "wall_balance_unreadable",
        "wall_bulk_growth_ended", "wall_bulk_growth_stopped", "wall_bulk_growth_unread", "wall_bulk_selected",
        "wall_bulk_short", "wall_bulk_trim", "wall_confirmation_agreed", "wall_confirmation_dispatched",
        "wall_confirmation_unpriced", "wall_dialog_level_reads", "wall_dialog_yellow_price", "wall_evidence_frame",
        "wall_evidence_missing", "wall_level_capped", "wall_panel_missing", "wall_panel_out_of_phase",
        "wall_settle_impossible", "wall_settle_unreadable", "wall_bulk_unavailable", "wall_phase_rescan",
        "wall_settle_reads",
        "wall_list_confirm", "wall_confirmation_not_sent",
        // Collection steps.
        "collection_phase", "collection_camera_registered", "collection_camera_unmatched",
        "collection_camera_unreferenced", "collection_target_skipped", "collection_target_tapped",
        // Popups handled without a person.
        "popup_dismissed", "popup_waiting", "popup_dialogue_advanced", "popup_dialogue_cleared",
        "popup_dialogue_reset", "popup_dialogue_budget_spent", "reward_taken", "reward_declined",
        "takeover_reload_tapped", "takeover_reloaded", "maintenance_lifting", "maintenance_back",
        "village_retry_reopening",
        // App housekeeping; the security ones are in the problems inbox instead.
        "handle_action_busy", "intent_action_rejected", "notification_skipped", "retention_pruned",
        "remote_server_started", "remote_denied", "remote_peer_refused", "inspect_started"
    )

    /** The names the failure snapshot passed as its "event" field before 3637028, overwriting the log's own. */
    private val SNAPSHOT_FOR = setOf("run_stopped", "auto_restart", "recovery_failed", "transition_timeout")

    /**
     * The event's name. Before 3637028, `failure_snapshot` logged a field called "event", which
     * replaced the name in the written line, so such a line reads as "run_stopped" (or
     * "auto_restart") with a capture file and no reason. This gives it its real name back. Newer
     * lines carry the failing event as "trigger" and keep their own name.
     */
    fun nameOf(event: JSONObject): String {
        val name = event.optString("event")
        if (name in SNAPSHOT_FOR && !event.has("reason") && !event.has("trigger")) {
            if (str(event, "file")?.startsWith("captures/") == true) return "failure_snapshot"
            if (event.has("error")) return "failure_snapshot_failed"
        }
        return name
    }

    /** The failure a snapshot was taken for: "trigger" on new lines, the overwritten name on old ones. */
    fun triggerOf(event: JSONObject): String? {
        val name = nameOf(event)
        if (name != "failure_snapshot" && name != "failure_snapshot_failed") return null
        return str(event, "trigger") ?: event.optString("event").takeIf { it in SNAPSHOT_FOR }
    }

    private fun snapshotFor(trigger: String?): String = when (trigger) {
        "run_stopped" -> "the stop"
        "auto_restart" -> "the automatic restart"
        "recovery_failed" -> "the failed recovery"
        "transition_timeout" -> "the screen that did not arrive"
        else -> "this failure"
    }

    fun isDetail(name: String): Boolean = name in DETAIL || name.endsWith("_reread")

    /** The timeline line, or null for detail and unknown names. [battleResult] supplies an attack's loot. */
    fun describe(event: JSONObject, battleResult: JSONObject? = null): TimelineLine? {
        val name = nameOf(event)
        if (isDetail(name)) return null
        val (text, tone) = line(name, event, battleResult) ?: return null
        return TimelineLine(Format.parseTime(event.optString("time")), text, tone, name,
            str(event, "accountId"), str(event, "accountName"))
    }

    /** The sentence for the technical log; unknown names read "Technical: <words>". */
    fun technical(event: JSONObject): String {
        val name = nameOf(event)
        return line(name, event, null)?.first ?: "Technical: ${words(name)}"
    }

    /** Whether [name] has a sentence of its own. */
    fun isKnown(name: String): Boolean = line(name, JSONObject().put("event", name), null) != null

    fun tone(event: JSONObject): Tone = line(nameOf(event), event, null)?.second ?: Tone.NEUTRAL

    /** "word words" from "word_words": the last resort for a name nothing else describes. */
    fun words(name: String): String = name.replace('_', ' ').trim().ifEmpty { "event" }

    private fun line(name: String, e: JSONObject, result: JSONObject?): Pair<String, Tone>? = when (name) {
        // Run lifecycle.
        "session_started" -> sessionStarted(e) to Tone.NEUTRAL
        "session_finished" -> sessionFinished(e)
        "run_stopped" -> "Stopped: ${stopLine(str(e, "reason"))}" to stopTone(str(e, "reason"))
        "run_cancelled" -> "Run cancelled: ${stopLine(str(e, "reason"))}" to stopTone(str(e, "reason"))
        "run_auto_restart" -> {
            val tries = int(e, "restart")?.let { r -> " (try $r${int(e, "maxRestarts")?.let { " of $it" } ?: ""})" } ?: ""
            "Restarting the run$tries after: ${stopLine(str(e, "reason"))}" to Tone.WARN
        }
        "run_recovered" -> "Run recovered and carried on" to Tone.OK
        "run_paused" -> "Paused${pausedAt(str(e, "at"))}" to Tone.IDLE
        "run_resumed" -> "Resumed${pausedAt(str(e, "at"))}" to Tone.NEUTRAL
        "run_held" -> "Paused while the notification shade was open" to Tone.IDLE
        "run_hold_released" -> "Resumed once Clash was back in front" to Tone.NEUTRAL
        "run_still_held" -> "Still paused after ${AppSwitchLatch.STILL_HELD_AFTER_MS / 1000} s: the notification shade still reads as open" to Tone.IDLE
        "run_hold_timeout" -> "The notification shade was open for ${AppSwitchLatch.HOLD_CAP_MS / 60_000} minutes; ending the run" to Tone.WARN
        "pause_button" -> (if (bool(e, "paused") == false) "Resume pressed" else "Pause pressed") to Tone.NEUTRAL
        "combined_run_started" -> {
            val first = "${str(e, "firstAccount") ?: "first account"} ${int(e, "firstQuota")?.let { Format.count(it, "attack") } ?: ""}".trim()
            val second = "${str(e, "secondAccount") ?: "second account"} ${int(e, "secondQuota")?.let { Format.count(it, "attack") } ?: ""}".trim()
            val limit = int(e, "globalDeadlineMinutes")?.let { ", up to ${Format.minutes(it)}" } ?: ""
            "Both accounts run started: $first, then $second$limit" to Tone.NEUTRAL
        }
        "combined_run_completed" -> {
            val first = "${int(e, "firstCompleted") ?: 0} attacks (${endWord(str(e, "firstReason")).lowercase(Locale.ROOT)})"
            val second = "${int(e, "secondCompleted") ?: 0} attacks (${endWord(str(e, "secondReason")).lowercase(Locale.ROOT)})"
            "Both accounts run finished: first $first, second $second" to Tone.OK
        }
        "account_handoff" -> {
            val after = int(e, "completed")?.let { " after ${Format.count(it, "attack")}" } ?: ""
            val why = str(e, "reason")?.let { " (${endWord(it).lowercase(Locale.ROOT)})" } ?: ""
            "${str(e, "from") ?: "First account"} done$after$why; handing over to ${str(e, "to") ?: "the second account"}" to Tone.NEUTRAL
        }
        "account_skipped" -> {
            val why = when (str(e, "reason")) {
                CompletionReason.TIME_LIMIT.key -> "not enough time left" + (int(e, "leftMinutes")?.let { " ($it min)" } ?: "")
                CompletionReason.DEVICE_GUARD.key -> "the battery is low"
                null -> "no reason given"
                else -> stopLine(str(e, "reason"))
            }
            "Skipped ${str(e, "account") ?: "the second account"}: $why" to Tone.WARN
        }
        "storage_full_stop" -> "Storage full: ${clean(str(e, "reason") ?: "nothing left to spend it on")}" to Tone.WARN
        "wall_pending_stop" -> "Stopped before walls: an earlier wall purchase still needs checking" to Tone.BAD
        "pause_carried" -> "Kept the pause for the next run after: ${stopLine(str(e, "reason"))}" to Tone.IDLE

        // Battles.
        "attack_completed" -> attackCompleted(e, result)
        "battle_started" -> "Battle started" to Tone.NEUTRAL
        "battle_result" -> "Result read: ${resultText(e)}" to Tone.NEUTRAL
        "attack_wait" -> "Waited ${seconds(long(e, "ms"))} for the army" to Tone.NEUTRAL
        "attack_zoomed_out" -> "Zoomed out before deploying" to Tone.NEUTRAL
        "hero_deployed" -> "Deployed the ${hero(str(e, "hero"))}" to Tone.NEUTRAL
        "hero_ability_used" -> "Used the ${hero(str(e, "hero"))}'s ability" to Tone.NEUTRAL
        "hero_card_unverified" -> "Could not confirm the ${hero(str(e, "hero"))} left the tray" to Tone.WARN
        "spell_card_spent" -> "Cast ${spell(str(e, "spell"))}" to Tone.NEUTRAL
        "spell_card_unverified" -> "Could not confirm ${spell(str(e, "spell"))} were cast" to Tone.WARN
        "troop_card_partially_unspent" -> "Some ${hero(str(e, "troop"))} stayed in the tray" to Tone.WARN
        "event_card_detected" -> "Read the tray's troop cards" to Tone.NEUTRAL
        "tray_mismatch" -> if (bool(e, "confirmed") == false) {
            "The ${hero(str(e, "slot"))}'s card read a troop count once, not on a second look; attacked as usual" to Tone.WARN
        } else {
            "Left before deploying: the ${hero(str(e, "slot"))}'s card showed a troop count" to Tone.BAD
        }
        "event_card_ambiguous" -> "The event troop card was unclear; deployed the normal army" to Tone.WARN
        "event_attack_planned" -> "Planned an attack with the event troops" to Tone.NEUTRAL
        "card_retry" -> "Tapped a tray card again" to Tone.NEUTRAL
        "deploy_timing" -> "Recorded how long deploying took" to Tone.NEUTRAL
        "deploy_timing_failed" -> "Could not record how long deploying took" to Tone.NEUTRAL
        "stall_trajectory" -> "Tracked the battle's damage and loot" to Tone.NEUTRAL
        "stall_damage_unreadable" -> "Battle damage could not be read" to Tone.NEUTRAL
        "stall_would_surrender" -> "The battle stalled${int(e, "damagePercent")?.let { " at $it% damage" } ?: ""}" to Tone.NEUTRAL
        "early_end" -> earlyEnd(e) to Tone.NEUTRAL
        "early_end_confirmed" -> "Confirmed ending the battle" to Tone.NEUTRAL
        "early_end_confirm_unread" -> "The end-battle confirmation could not be read" to Tone.WARN
        "early_end_damage_floor" -> "Battles end no earlier than ${int(e, "floor")?.let { "$it%" } ?: "the league bonus"} damage" to Tone.NEUTRAL
        "early_end_dialog_closed" -> "Closed the end-battle dialog" to Tone.NEUTRAL
        "early_end_back_skipped_after_hold" -> "The end-battle dialog was gone after the pause; nothing pressed" to Tone.NEUTRAL
        "battle_checked_after_pause" -> "Looked at the battle again after the pause" to Tone.NEUTRAL
        "early_end_quit_prompt" -> "Clash asked to quit the game; Okay was not pressed" to Tone.WARN
        "early_end_no_button" -> "The End Battle button was not found" to Tone.WARN
        "result_frame" -> "Read the battle result screen" to Tone.NEUTRAL
        "result_loot_unreadable" -> "Result loot could not be read" to Tone.WARN
        "result_damage_unreadable" -> "Result damage could not be read" to Tone.WARN
        "battle_loot_unreadable" -> "Loot left in the battle could not be read" to Tone.NEUTRAL
        "stars_damage_conflict" -> "Stars and damage on the result screen disagree" to Tone.WARN
        "return_home_target" -> "Returning home from the result screen" to Tone.NEUTRAL
        "battle_history_failed" -> "A battle could not be saved to history" to Tone.WARN
        "army_recipe" -> armyRecipe(e)
        "army_tab_switched" -> "Tapped My Army to leave the Saved Recipes list" to Tone.NEUTRAL
        "army_tab_unknown" -> if (str(e, "tab") == "SAVED_RECIPES") {
            "The Saved Recipes list stayed open, so Attack! was not pressed" to Tone.WARN
        } else {
            "Could not tell which army tab was showing, so Attack! was not pressed" to Tone.WARN
        }
        "army_after_pause" -> attackAfterPause(e)
        "battle_left" -> if (str(e, "phase") == "surrender") {
            "The battle was over before Surrender went out" to Tone.NEUTRAL
        } else {
            "The battle screen went away during deployment; the rest of the army was not placed" to Tone.WARN
        }

        // Opponent search.
        "matchmaking_searching" -> "Searching for an opponent" to Tone.NEUTRAL
        "searching_out_of_phase" -> "Saw the search screen at an unexpected moment" to Tone.WARN
        "opponent_accepted" -> "Attacking a base with ${lootOffered(str(e, "loot"))}" to Tone.NEUTRAL
        "opponent_fallback" -> "Attacking anyway after ${Format.count(int(e, "skips") ?: 0, "skip")}: ${searchReason(str(e, "reason"))}" to Tone.WARN
        "opponent_skipped" -> "Skipped a base with ${lootOffered(str(e, "loot"))}" to Tone.NEUTRAL
        "opponent_battle_started" -> "The battle started while searching" to Tone.NEUTRAL
        "opponent_selection_uncalibrated" -> "Opponent rules are on but the scout screen is not calibrated; attacking the first base" to Tone.WARN
        "opponent_reread", "opponent_next_reread" -> "Read the scout screen again" to Tone.NEUTRAL
        "opponent_next_unconfirmed" -> "Could not confirm the Next button moved on" to Tone.WARN
        "scout_loot_unreadable" -> "Scout loot could not be read" to Tone.NEUTRAL
        "scout_countdown_unreadable" -> "Scout countdown could not be read" to Tone.NEUTRAL
        "scout_fee_unreadable" -> "Next price could not be read" to Tone.NEUTRAL
        "multiplayer_cooldown" -> "Multiplayer attacks are on cooldown; waiting" to Tone.WARN
        "multiplayer_cooldown_over" -> "Cooldown over; attacking again" to Tone.OK
        "opponent_next_held" -> "Next was not sent after a pause; reading the scout screen again" to Tone.NEUTRAL

        // Accounts.
        "account_switch_verified" -> "Switched to ${str(e, "village") ?: "the account"}" to Tone.OK
        "account_identity_already_active" -> "${str(e, "village") ?: "The account"} was already signed in" to Tone.OK
        "account_switch_required" -> "Switching from ${str(e, "currentVillage") ?: "another village"} to ${str(e, "targetVillage") ?: "the account"}" to Tone.NEUTRAL
        "account_switch_start" -> "Checking which village is signed in" to Tone.NEUTRAL
        "account_switch_verify_failed" -> "Account switch could not be confirmed: ${stopLine(str(e, "reason"))}" to Tone.BAD
        "account_identity_candidate" -> "Checking whether this is ${str(e, "village") ?: "the account"}" to Tone.NEUTRAL
        // Desktop: the first watched run on a fresh install reads the account (PcAccount).
        "account_identity_enrolled" -> "Read your account: ${str(e, "village") ?: "your village"}" to Tone.OK
        // Desktop: a read the check did not confirm, or Setup's Read again (PcAccount.forget).
        "account_identity_forgotten" -> "The bot will read your account again on the next supervised attack" to Tone.WARN
        "settings_close_failed" -> "The settings menu was still open after ${int(e, "presses") ?: 3} presses of its close button" to Tone.WARN
        "picker_row_selected" -> "Picked ${str(e, "village") ?: "the village"} in the account list" to Tone.NEUTRAL
        "switch_only_completed" -> "Switched to ${str(e, "village") ?: "the account"}; nothing else run" to Tone.OK
        "welcome_back_dialog_detected" -> "Saw the welcome back message" to Tone.NEUTRAL
        "record_account_result_failed" -> "The run's result could not be saved to the account" to Tone.WARN

        // Walls.
        "wall_upgraded" -> {
            val walls = int(e, "walls")?.let { Format.count(it, "wall") } ?: "walls"
            val level = int(e, "toLevel")?.let { " to level $it" } ?: ""
            "Bought $walls$level for ${Format.amount(long(e, "cost"))} ${currency(str(e, "currency"))}" to Tone.OK
        }
        "wall_phase_skipped_no_builder" ->
            "Walls skipped: no builder free${int(e, "total")?.let { " (0 of $it)" } ?: ""}" to Tone.WARN
        "walls_off_builders_busy" -> "Walls paused for this run: every builder is busy" to Tone.WARN
        "wall_phase_no_walls" -> "No walls left to upgrade in the builder's list" to Tone.NEUTRAL
        "wall_purchase_outcome_unknown" -> {
            val what = int(e, "walls")?.let { Format.count(it, "wall") } ?: "walls"
            "Wall purchase not confirmed: $what for ${Format.amount(long(e, "cost"))} ${currency(str(e, "currency"))}; check the village" to Tone.BAD
        }
        "wall_spend_unverified" ->
            "Wall spend not proven: ${Format.amount(long(e, "cost"))} ${currency(str(e, "currency"))} may have been spent; check the village" to Tone.BAD
        "pending_purchase_acknowledged" -> "Pending wall purchase checked and cleared" to Tone.OK
        "wall_phase_blocked_by_marker" -> "Walls did not run: an earlier purchase still needs checking" to Tone.WARN
        "wall_marker_not_cleared" -> "The pending wall purchase could not be cleared" to Tone.BAD
        "walls_disabled_after_mismatch" -> "Walls turned off for this run after a balance mismatch" to Tone.WARN
        "wall_session_finished" -> {
            val bought = int(e, "upgrades")?.let { Format.count(it, "purchase") } ?: "no purchases"
            val why = when {
                bool(e, "noFreeBuilder") == true -> "; no builder was free"
                bool(e, "balancesUnread") == true -> "; the village balances could not be read"
                else -> ""
            }
            // Nothing went wrong when every wall is maxed; there is just nothing left to buy.
            val maxed = if (why.isEmpty() && bool(e, "noWalls") == true) "; every wall is maxed" else ""
            "Walls-only run finished: $bought$why$maxed" to (if (why.isNotEmpty()) Tone.WARN else Tone.OK)
        }
        "manual_walls_stopped_no_builder" -> "Walls-only run stopped: no builder free" to Tone.WARN
        "manual_walls_stopped_nothing_to_spend" -> "Walls-only run stopped: ${clean(str(e, "reason") ?: "nothing left to spend")}" to
            (if (bool(e, "unread") ?: str(e, "balances").isNullOrEmpty()) Tone.WARN else Tone.NEUTRAL)
        "manual_walls_stopped_on_failure" -> "Walls-only run stopped: ${clean(str(e, "detail") ?: str(e, "reason") ?: "a wall step failed")}" to Tone.WARN
        "wall_phase_abandoned" -> "Wall phase abandoned: ${clean(str(e, "reason") ?: "no reason given")}" to Tone.WARN
        "wall_phase_refused" -> "Walls refused: ${clean(str(e, "detail") ?: str(e, "reason") ?: "no reason given")}" to Tone.WARN
        "wall_village_timeout" -> "The village did not come back after the wall phase" to Tone.WARN
        "wall_checkpoint_triggered" -> "${Format.resourceName(resource(str(e, "currency")))} at ${Format.ofCapacity(long(e, "balance"), long(e, "capacity"))}; wall phase due" to Tone.NEUTRAL
        "wall_phase_planned" -> "Planning ${Format.count(int(e, "count") ?: 0, "wall")} at ${Format.amount(long(e, "unit"))} ${currency(str(e, "currency"))} each" to Tone.NEUTRAL
        "wall_phase_outcome" -> "Wall phase result: ${wallOutcome(str(e, "outcome"))}" to Tone.NEUTRAL
        "wall_phase_no_builder" -> "No builder free for walls" to Tone.WARN
        "wall_builders_busy" -> "Every builder is busy; walls not bought" to Tone.WARN
        "wall_builders_busy_close" -> "Closed the builders busy message" to Tone.NEUTRAL
        "wall_builders_busy_gone" -> "The builders busy message went away" to Tone.NEUTRAL
        "wall_balance_unreadable" -> "The balance for walls could not be read" to Tone.WARN
        "wall_bulk_selected" -> "Selected ${Format.count(int(e, "count") ?: 0, "wall")} to buy at once" to Tone.NEUTRAL
        "wall_bulk_short" -> "Fewer walls were selectable than planned" to Tone.NEUTRAL
        "wall_bulk_unavailable" -> "Only one wall could be selected; buying it alone" to Tone.NEUTRAL
        "wall_phase_rescan" -> "No wall in the upgrade list; looking through it once more" to Tone.NEUTRAL
        "wall_bulk_trim" -> "Trimmed the wall selection to what the balance pays for" to Tone.NEUTRAL
        "wall_bulk_growth_ended", "wall_bulk_growth_stopped" -> "Stopped adding walls to the selection" to Tone.NEUTRAL
        "wall_bulk_growth_unread" -> "The wall selection could not be read while adding walls" to Tone.WARN
        "wall_bulk_reread", "wall_buy_reread", "wall_panel_reread", "wall_confirmation_reread" -> "Read the wall panel again" to Tone.NEUTRAL
        "wall_confirmation_agreed" -> "The wall price on the confirmation matched" to Tone.NEUTRAL
        "wall_confirmation_dispatched" -> "Confirmed buying ${Format.count(int(e, "walls") ?: 0, "wall")}" to Tone.NEUTRAL
        "wall_confirmation_unpriced" -> "The wall confirmation showed no price" to Tone.WARN
        "wall_dialog_level_reads" -> "Read the wall levels on the dialog" to Tone.NEUTRAL
        "wall_dialog_yellow_price" -> "Read the wall price on the dialog" to Tone.NEUTRAL
        "wall_evidence_frame" -> "Saved a frame as proof of the wall purchase" to Tone.NEUTRAL
        "wall_evidence_missing" -> "No frame proved the wall purchase" to Tone.WARN
        "wall_level_capped" -> "Walls are already at the target level" to Tone.NEUTRAL
        "wall_list_page" -> "Scrolled the upgrade list" to Tone.NEUTRAL
        "wall_panel_missing" -> "The wall panel did not open" to Tone.WARN
        "wall_panel_out_of_phase" -> "The wall panel showed at an unexpected moment" to Tone.WARN
        "wall_settle_impossible" -> "The balance after buying walls could not settle" to Tone.WARN
        "wall_settle_unreadable" -> "The balance after buying walls could not be read" to Tone.WARN
        "wall_settle_reads" -> "Recorded each reading of the balance after buying walls" to Tone.NEUTRAL
        "wall_list_confirm" -> "Looked again at the Wall row before tapping it" to Tone.NEUTRAL
        "wall_list_pick_lost" -> "The Wall row moved before it could be tapped; reading on down the list" to Tone.NEUTRAL
        "wall_confirmation_not_sent" -> {
            val walls = int(e, "walls")?.let { Format.count(it, "wall") } ?: "walls"
            "Did not confirm buying $walls: the dialog could not be trusted after a pause or a slow read; nothing was spent" to Tone.NEUTRAL
        }

        // Collection.
        "collection_finished" -> collectionFinished(e)
        "collection_phase" -> "Collection ${if (str(e, "when") == "after") "after" else "before"} farming: ${int(e, "verified") ?: 0} of ${int(e, "attempted") ?: 0} collected" to Tone.NEUTRAL
        "collection_abandoned" -> "Collection stopped: ${clean(str(e, "reason") ?: "no reason given")}" to Tone.WARN
        "collection_home_timeout" -> "The village did not come back during collection" to Tone.WARN
        "collection_camera_registered" -> "Lined up the village view for collecting" to Tone.NEUTRAL
        "collection_camera_unmatched" -> "The village view did not match the saved one; collection skipped" to Tone.WARN
        "collection_camera_unreferenced" -> "No saved village view to collect against" to Tone.WARN
        "collection_target_skipped" -> "Skipped ${str(e, "name") ?: "a collector"}" to Tone.NEUTRAL
        "collection_target_tapped" -> "Collected from ${str(e, "name") ?: "a collector"}" to Tone.NEUTRAL

        // Clash's own screens.
        "premium_dialog_refused" -> "Closed a gem offer without buying" to Tone.NEUTRAL
        "popup_blocked_premium" -> "A gem offer is in the way; nothing was bought" to Tone.WARN
        "connection_lost" -> "Clash lost its server connection" to Tone.WARN
        "maintenance_break" -> "Clash is in a maintenance break; waiting it out" to Tone.WARN
        "maintenance_lifting" -> "The maintenance break looks to be ending" to Tone.NEUTRAL
        "maintenance_back" -> "Still in the maintenance break" to Tone.WARN
        "maintenance_over" -> "The maintenance break is over${long(e, "waitedMs")?.let { " after ${Format.span(it)}" } ?: ""}" to Tone.NEUTRAL
        "maintenance_timeout" -> "Maintenance lasted too long; the run stopped" to Tone.BAD
        "game_update_required" -> "Clash needs an update from the store" to Tone.BAD
        "village_taken_elsewhere" -> "Village opened on another device" to Tone.BAD
        "village_retry_wait" -> {
            val wait = long(e, "waitMs")?.let { " ${Format.span(it)}" } ?: ""
            val left = int(e, "remaining")?.let { " (${Format.count(it, "try", "tries")} left)" } ?: ""
            "Waiting$wait before taking the village back$left" to Tone.WARN
        }
        "village_retry_reopening" -> "Reopening Clash to take the village back" to Tone.NEUTRAL
        "village_retry_verified" -> "Village taken back" to Tone.OK
        "takeover_reload_tapped" -> "Pressed Reload on the village taken message" to Tone.NEUTRAL
        "takeover_reloaded" -> "Clash reloaded the village" to Tone.NEUTRAL
        "server_dialog_backoff" -> "Clash's server message came back; waiting${long(e, "waitMs")?.let { " ${Format.span(it)}" } ?: ""}" to Tone.WARN
        "server_dialog_repeated" -> "Clash's server message keeps coming back" to Tone.WARN
        "popup_dismissed" -> "Closed a popup" to Tone.NEUTRAL
        "popup_waiting" -> "Waiting for a popup to go away" to Tone.NEUTRAL
        "popup_dialogue_advanced" -> "Tapped through a game message" to Tone.NEUTRAL
        "popup_dialogue_cleared" -> "Game messages cleared" to Tone.NEUTRAL
        "popup_dialogue_reset" -> "A game message is still showing" to Tone.NEUTRAL
        "popup_dialogue_budget_spent" -> "A game message would not go away" to Tone.WARN
        "reward_taken" -> "Took a free reward" to Tone.NEUTRAL
        "reward_declined" -> "Left a reward offer alone" to Tone.NEUTRAL
        "app_switched_recovering" -> "Another app came to the front; bringing Clash back" to Tone.WARN
        "gem_shop_left" -> "Left the gem store with Back; nothing was bought" to Tone.WARN
        "gem_shop_gone" -> "The gem store was gone on a second look; Back was not pressed" to Tone.NEUTRAL
        "quit_prompt_cancelled" -> "Clash asked to quit the game; Cancel was pressed, not Okay" to Tone.WARN
        "quit_prompt_no_cancel" -> "Clash asked to quit the game; its Cancel was not read, so nothing was pressed" to Tone.WARN

        // Getting to the village.
        "start_return_home" -> "Heading for the village screen" to Tone.NEUTRAL
        "start_reached_home" -> "Reached the village screen" to Tone.NEUTRAL
        "start_home_timeout" -> "The village screen did not come up at the start" to Tone.WARN
        "start_refused_battle" -> "Did not start: Clash was in a battle the bot did not start" to Tone.WARN
        "home_unconfirmed" -> "The village screen could not be confirmed" to Tone.WARN
        "home_sample_not_home" -> "A village reading came from another screen" to Tone.NEUTRAL
        "recovery_step" -> "Finding the way back to the village" to Tone.NEUTRAL
        "village_camera_normalized" -> "Reset the village view" to Tone.NEUTRAL
        "transition_timeout" -> "The game did not reach the expected screen in time" to Tone.WARN
        "transition_stray_frame" -> "Saw an unexpected screen for a moment" to Tone.NEUTRAL
        "transition_confirmed_exit" -> "Left the screen as expected" to Tone.NEUTRAL
        "tap_retried" -> "Tapped again: the screen did not change" to Tone.NEUTRAL
        "gesture_resent_after_pause" -> "Repeated a tap that a pause interrupted" to Tone.NEUTRAL
        "waiting_unrecognized" -> "Waiting on a screen it does not know" to Tone.NEUTRAL
        "back_withheld" -> "Did not press Back" to Tone.NEUTRAL
        "gesture_held" -> (if (bool(e, "waited") == false) "Did not send a tap: the screen it was meant for was too old"
            else "Did not send a tap: a pause came between") to Tone.NEUTRAL
        // Desktop: the game window, put back to the size the run was calibrated at.
        "surface_resized" -> "Resized the game window${str(e, "from")?.let { " from $it" } ?: ""} to ${str(e, "to") ?: "its calibrated size"}" to Tone.NEUTRAL
        "surface_aspect_differs" -> "The game window is not the shape it was calibrated at${str(e, "profile")?.let { " ($it)" } ?: ""}, so it was left as it is" to Tone.WARN
        "surface_resize_failed" -> "The game window could not be resized" to Tone.WARN
        "clash_launch_failed" -> "Clash of Clans could not be opened through Google Play Games" to Tone.WARN
        "surface_resize_capped" -> "The screen could not hold the game window at ${str(e, "asked") ?: "its calibrated size"}, so it is ${str(e, "reached") ?: "smaller"}" to Tone.NEUTRAL
        "profile_moved" -> "Moved the calibration${str(e, "calibrated")?.let { " from $it" } ?: ""} onto the game window${str(e, "surface")?.let { " at $it" } ?: ""}" to Tone.NEUTRAL
        "surface_verified" -> "The calibration is now verified on a game window of this shape${str(e, "surface")?.let { " ($it)" } ?: ""}" to Tone.OK
        "surface_verification_skipped_unverified_calibration" -> "Did not verify this game window's shape: the calibration is not verified where it was taken" to Tone.WARN

        // Readings.
        "storage_read" -> "Storage read: ${storageText(e)}" to Tone.NEUTRAL
        "builders_read" -> {
            val free = int(e, "free"); val total = int(e, "total")
            (if (free == null || total == null) "Builders could not be read" else "Builders: $free of $total free") to Tone.NEUTRAL
        }
        "builders_read_disagree" -> "Two builder readings disagreed" to Tone.NEUTRAL
        "balance_unreadable" -> "A balance could not be read" to Tone.NEUTRAL
        "balance_implausible" -> "A balance reading looked wrong and was ignored" to Tone.NEUTRAL
        "amount_unreadable" -> "An amount could not be read" to Tone.NEUTRAL
        "amount_second_frame" -> "Read an amount on a second frame" to Tone.NEUTRAL
        "device_health" -> "PC: ${health(e)}" to Tone.NEUTRAL
        "device_guard_stop" -> "Ending at home to save the battery: ${health(e)}" to Tone.WARN
        "device_health_failed" -> "Battery and temperature could not be read" to Tone.NEUTRAL
        "frame_encode_failed" -> "A live frame could not be saved" to Tone.NEUTRAL

        // Calibration and files.
        "profile_saved" -> "Calibration saved" to Tone.NEUTRAL
        "profile_load_failed" -> "Calibration could not be loaded" to Tone.BAD
        "profile_earned_verification" -> "Calibration verified by this run" to Tone.OK
        "profile_verification_skipped_recalibrated" -> "Not marked verified: the calibration changed during the run" to Tone.WARN
        "failed_to_save_verification" -> "The calibration's verification could not be saved" to Tone.WARN
        "camera_reference_saved" -> "Saved the village view for collecting" to Tone.NEUTRAL
        "camera_reference_retired" -> "Set the old village view aside to take a new one" to Tone.NEUTRAL
        "collection_targets_cleared" -> "Cleared the saved collector spots with the old village view" to Tone.NEUTRAL
        "layout_editor_closed" -> "Closed the layout editor" to Tone.NEUTRAL
        "storage_save_failed" -> "Storage settings could not be saved" to Tone.WARN
        "migration_failed" -> "Old settings could not be moved to the new format" to Tone.WARN
        "retention_pruned" -> "Removed ${Format.count(int(e, "capturesDeleted") ?: 0, "old capture")}" to Tone.NEUTRAL
        "retention_failed" -> "Old captures and logs could not be cleaned up" to Tone.NEUTRAL

        // App and service.
        "service_connected" -> "Clash Farmer started" to Tone.NEUTRAL
        "service_destroyed" -> "Clash Farmer closed" to Tone.WARN
        "main_activity_opened" -> "App opened" to Tone.NEUTRAL
        "handle_action" -> "Button pressed in the app" to Tone.NEUTRAL
        "new_intent_action" -> "The app was asked to act" to Tone.NEUTRAL
        "handle_action_busy" -> "Ignored a button: the helper is busy" to Tone.NEUTRAL
        "handle_action_error" -> "A button's action failed" to Tone.WARN
        "intent_action_rejected" -> "Ignored a request from outside the app" to Tone.NEUTRAL
        "action_step" -> "Moved to the next step" to Tone.NEUTRAL
        "stop_side_effect" -> "Work in flight ended after the stop" to Tone.NEUTRAL
        "screen_off_receiver_failed" -> "Could not watch for the screen turning off" to Tone.WARN
        "inspect_started" -> "Inspecting the game screen" to Tone.NEUTRAL
        "inspect_completed" -> if (str(e, "check") == "tray") trayCheck(e) else
            "Inspected the screen: ${screen(str(e, "state") ?: "UNKNOWN")}${int(e, "boxes")?.let { ", ${Format.count(it, "text box", "text boxes")}" } ?: ""}" to Tone.NEUTRAL
        "inspect_failed" -> (if (str(e, "check") == "tray") "The tray check failed" else "Inspecting the screen failed") to Tone.WARN
        "capture_layout_wait_timeout" -> "Captured before Clash had filled the screen" to Tone.NEUTRAL
        "notification_posted" -> "Notification sent: ${(str(e, "title") ?: "run update").replace('_', ' ')}" to Tone.NEUTRAL
        "notification_failed" -> "A notification could not be sent" to Tone.WARN
        "notification_skipped" -> "No notification sent: notifications are off" to Tone.NEUTRAL
        "failure_snapshot" -> "Saved the screen and log for ${snapshotFor(triggerOf(e))}" to Tone.NEUTRAL
        "failure_snapshot_failed" -> "Could not save the screen and log for ${snapshotFor(triggerOf(e))}" to Tone.NEUTRAL
        "scheduled_start" -> "Started the run set for ${str(e, "at") ?: "later"}${str(e, "account")?.let { " ($it)" } ?: ""}" to Tone.NEUTRAL
        "scheduled_start_unreadable" -> "A start set for later could not be read and was dropped" to Tone.WARN
        "scheduled_start_refused" -> "The run set for ${str(e, "at") ?: "later"}${str(e, "account")?.let { " ($it)" } ?: ""} did not start: ${stopLine(str(e, "reason"))}" to Tone.WARN

        // Remote page.
        "remote_command" -> remoteCommand(e) to Tone.NEUTRAL
        "remote_start_refused" -> "Remote start refused${str(e, "account")?.let { " for $it" } ?: ""}: ${stopLine(str(e, "reason"))}" to Tone.WARN
        "remote_denied" -> "Remote page refused a visitor${str(e, "peer")?.let { " from $it" } ?: ""}" to Tone.WARN
        "remote_peer_refused" -> "Remote page refused a device outside Tailscale${str(e, "peer")?.let { " ($it)" } ?: ""}" to Tone.WARN
        "remote_server_started" -> "Remote page ready${int(e, "port")?.let { " on port $it" } ?: ""}" to Tone.NEUTRAL
        "remote_server_failed" -> "The remote page could not start" to Tone.WARN
        else -> null
    }

    // ----- Run start, finish and stop -----

    private fun sessionStarted(e: JSONObject): String {
        val attacks = int(e, "maxAttacks")
        val minutes = int(e, "maxMinutes")
        return when {
            // A walls-only run is logged as supervised too (r3-log-final.jsonl:697), so walls goes first.
            bool(e, "walls") == true -> "Walls-only run started${minutes?.let { ", up to ${Format.minutes(it)}" } ?: ""}"
            bool(e, "supervised") == true -> "Test attack started (supervised)"
            else -> {
                val length = if (attacks != null && attacks >= 100 && minutes != null) Format.minutes(minutes)
                    else attacks?.let { Format.count(it, "attack") } ?: "attacks"
                "Run started: $length, walls ${if (bool(e, "wallsEnabled") == true) "on" else "off"}"
            }
        }
    }

    private fun sessionFinished(e: JSONObject): Pair<String, Tone> {
        val n = int(e, "completed")
        val after = n?.let { " after ${Format.count(it, "attack")}" } ?: ""
        val detail = str(e, "detail")
        return when (CompletionReason.fromKey(str(e, "reason") ?: "")) {
            CompletionReason.QUOTA_REACHED -> "Finished: ${n?.let { Format.count(it, "attack") } ?: "every attack"} done" to Tone.OK
            CompletionReason.GOAL_REACHED -> "Finished: farming goal reached$after" to Tone.OK
            CompletionReason.STORAGE_FULL -> {
                val why = detail?.removePrefix("Storage full")?.trimStart(';', ',', ' ', ':')?.takeIf { it.isNotBlank() }
                "Finished: storage full$after${why?.let { "; ${clean(it)}" } ?: ""}" to Tone.WARN
            }
            CompletionReason.WALL_PENDING -> "Finished: an earlier wall purchase still needs checking$after" to Tone.BAD
            CompletionReason.TIME_LIMIT -> "Finished: time limit reached$after" to Tone.OK
            CompletionReason.DEVICE_GUARD -> "Finished early to save the battery$after${detail?.let { ": ${guardText(it)}" } ?: ""}" to Tone.WARN
            null -> "Finished$after" to Tone.OK
        }
    }

    /**
     * The headline word for how a run ended: Complete, Goal reached, Storage full, Walls pending,
     * Time limit, Battery guard. [short] gives the session-row word: Done, Goal, Full, Walls, Time, Guard.
     * Anything else, a stop reason included, is Stopped.
     */
    fun endWord(reasonKey: String?, short: Boolean = false): String = when (CompletionReason.fromKey(reasonKey ?: "")) {
        CompletionReason.QUOTA_REACHED -> if (short) "Done" else "Complete"
        CompletionReason.GOAL_REACHED -> if (short) "Goal" else "Goal reached"
        CompletionReason.STORAGE_FULL -> if (short) "Full" else "Storage full"
        CompletionReason.WALL_PENDING -> if (short) "Walls" else "Walls pending"
        CompletionReason.TIME_LIMIT -> if (short) "Time" else "Time limit"
        CompletionReason.DEVICE_GUARD -> if (short) "Guard" else "Battery guard"
        null -> "Stopped"
    }

    /** Whether a stop reason is a person's own Stop. */
    fun isUserStop(reason: String?): Boolean = UserStop.matches(reason) || reason?.contains("Overlay Stop clicked") == true

    /** A person's stop is neutral; a stop [RunNotice] says needs a person is bad; the rest amber. */
    fun stopTone(reason: String?): Tone = when {
        isUserStop(reason) -> Tone.NEUTRAL
        reason == null -> Tone.WARN
        RunNotice.stopped(null, reason).kind == RunNotice.Kind.NEEDS_YOU -> Tone.BAD
        else -> Tone.WARN
    }

    /** A stop or failure reason as a short plain phrase, lower case first, without screen dumps. */
    fun stopLine(reason: String?): String {
        if (reason.isNullOrBlank()) return "no reason given"
        var r = reason.trim()
        // A crash's message is raw exception text; the PC says where the details are instead.
        val crashed = r.startsWith("Run error: ") || r.startsWith("Switch error: ")
        for (prefix in listOf("Session is stopped: ", "Run error: ", "Switch error: ", "Active session interrupted: ")) r = r.removePrefix(prefix)
        // Match on the reason alone, not on the screen text read after it.
        r = cutDump(r)
        val low = r.lowercase(Locale.ROOT)
        return when {
            r.contains(UserStop.FLOATING_PANEL) || r.contains("Overlay Stop clicked") -> "you pressed Stop on the status panel"
            r.contains(UserStop.DASHBOARD) -> "you pressed Stop in the app"
            r.contains(UserStop.REMOTE) -> "you pressed Stop on the remote page"
            r.contains(UserStop.NOTIFICATION) -> "you chose Stop from the tray icon"
            r.contains(UserStop.SCREEN_OFF) -> "the screen turned off"
            AppSwitchLatch.isHoldTimeout(r) -> "the notification shade stayed open for ${AppSwitchLatch.HOLD_CAP_MS / 60_000} minutes"
            low.startsWith("service destroyed") -> "Clash Farmer closed"
            // Desktop: written out, since clean() would lower the PC's capitals.
            low.contains("so the game stopped updating") -> "the PC was locked or the screen turned off, so the game stopped updating"
            low.contains("opened again while google play games kept the game running") ->
                "the Clash window was opened again while the game kept running, so restart the PC"
            low.startsWith("the pc was asleep") -> "the PC was asleep at the start time"
            low.startsWith("the pc was locked or its screen was off") -> "the PC was locked or its screen was off at the start time"
            // The PC's own stops (FarmerService), each with what to do next.
            low.startsWith("the clash window was closed") -> "the Clash window was closed; restart the PC, then start again"
            low.startsWith("no calibration profile for ") ->
                "${r.substring("No calibration profile for ".length).trim()} is not calibrated; calibrate it in Setup"
            low.contains("could not be started through google play games") ->
                "Google Play Games did not open Clash; open Clash by hand, then start again"
            low.contains("drawing a blank screen") -> "Clash stayed on a blank screen; wait for it to load, then start again"
            low.startsWith("the village is not on screen") ->
                "Clash was not on the village; close any open window in Clash, then start again"
            low.startsWith("timed out waiting for clash of clans") -> "the Clash window did not open in time; open Clash, then start again"
            low.contains("screenshot capture failed") -> "the Clash window could not be captured"
            low.contains("app switched to") || low.contains("not in foreground") -> "the Clash window could not be found"
            low.contains("device is locked") -> "Windows was locked"
            r.contains(Policy.VILLAGE_TAKEN) || low.contains("another device") -> "the village was opened on another device"
            r.contains(PopupGuard.SERVER_DIALOG) || low.contains("lost its connection") -> "Clash lost its server connection"
            r.contains(PopupGuard.MAINTENANCE_OVER) -> "Clash came back from a maintenance break"
            r.contains(ReturnHome.MAINTENANCE_AT_START) -> "Clash was in a maintenance break at the start"
            low.contains("stayed in maintenance") -> "Clash stayed in maintenance too long"
            r.contains(PopupGuard.UPDATE_REQUIRED) || low.contains("needs an update") -> "Clash needs an update from the store"
            low.contains("login") -> "Clash asks for a login"
            low.contains("spend could not be proven") || low.contains("never proven") -> "a wall purchase could not be proven; check the village"
            low.contains("could not be confirmed after the wall phase") -> "the village could not be confirmed after buying walls"
            r.startsWith(DeviceGuard.HOT_PREFIX) || r.startsWith(DeviceGuard.LOW_BATTERY_PREFIX) -> guardText(r)
            r.startsWith(ReturnHome.BATTLE_REFUSED.take(40)) -> "Clash was in a battle the bot did not start"
            r.startsWith(ReturnHome.START_TIMEOUT_PREFIX) -> "the village screen did not come up before the account check"
            low.contains("are full") || low.startsWith("storage full") -> when {
                low.contains(WallFarmingCoordinator.ALL_WALLS_MAXED) -> "storage full, every wall is maxed"
                low.contains("builder") -> "storage full, no free builder"
                else -> "storage full, nothing left to spend it on"
            }
            low.contains("account switch verification failed") -> "the account switch could not be confirmed"
            low.contains("account identity") || low.contains("identity template") -> "the signed-in village could not be confirmed"
            low.contains("gesture") && low.contains("cancel") || low.contains("was cancelled by a touch") ||
                (low.startsWith("pinch") || low.startsWith("tap gesture")) && low.contains("cancel") -> "a touch on the screen cancelled a tap"
            low.contains("coroutine was cancelled") -> "the run was cancelled"
            // The village's name is already beside the line, and clean() would lower its capital.
            r.contains(TrayCheck.TRAY_MISMATCH) -> "the ${TrayCheck.TRAY_MISMATCH}" +
                r.substringAfter(TrayCheck.TRAY_MISMATCH).substringBefore('.') +
                if (low.contains("end battle was not found")) ", and End Battle was not found" else ""
            r.contains(TrayCheck.HEROES_STAYED) ->
                "the ${TrayCheck.HEROES_STAYED}" + r.substringAfter(TrayCheck.HEROES_STAYED).substringBefore(',')
            low.startsWith("unexpected screen") || low.startsWith("unexpected transition screen") -> "the game showed a screen the run did not expect"
            low.startsWith("screen transition timed out") || low.startsWith("timed out waiting") -> "the game did not reach the expected screen in time"
            low.contains("timeout elapsed") || low.contains("deadline elapsed") -> "the time limit ran out"
            Regex("""^(.+?) ended before quota reached \((\d+)/(\d+) attacks\)""").find(r) != null -> {
                val m = Regex("""^(.+?) ended before quota reached \((\d+)/(\d+) attacks\)""").find(r)!!
                "${m.groupValues[1]} ended early (${m.groupValues[2]} of ${m.groupValues[3]} attacks)"
            }
            crashed -> "something went wrong; the Technical log on the Activity tab has the details"
            else -> clean(r)
        }
    }

    private fun cutDump(text: String): String {
        var t = text
        for (cut in listOf(" reading:", " (read:", " text:", " (state:")) {
            val i = t.indexOf(cut)
            if (i > 0) t = t.substring(0, i)
        }
        return t
    }

    /** "Phone too hot: 41.2 C (limit 41.0 C)" reads "phone too hot, 41.2 C (limit 41.0 C)". */
    private fun guardText(reason: String): String = clean(reason.replaceFirst(": ", ", "))

    /**
     * The last resort for free text: drops screen dumps after "reading:", "(read:" or "text:",
     * turns underscores into spaces, lower-cases shouting words and the first letter, and drops a
     * trailing full stop.
     */
    fun clean(text: String): String {
        var t = cutDump(text).replace('_', ' ')
        t = Regex("""\b[A-Z]{2,}\b""").replace(t) { it.value.lowercase(Locale.ROOT) }
        t = t.replace(Regex("\\s+"), " ").trim().trimEnd('.', ';', ',', ':')
        if (t.isEmpty()) return "no reason given"
        return if (t.length > 1 && t[1].isLowerCase()) t[0].lowercaseChar() + t.substring(1) else t
    }

    // ----- Pieces -----

    private fun attackCompleted(e: JSONObject, result: JSONObject?): Pair<String, Tone> {
        val n = int(e, "completed")
        val label = n?.let { "Attack $it" } ?: "Attack"
        val loot = result?.let { Format.parseLoot(str(it, "earned")) } ?: emptyMap()
        if (loot.isEmpty()) {
            val of = int(e, "limit")?.let { if (n != null) " of $it" else "" } ?: ""
            return "$label$of done; loot not read" to Tone.OK
        }
        return "$label done: ${earnedText(loot)}" to Tone.OK
    }

    /** "+412k gold, +398k elixir, +2.1k dark elixir" in that order, read amounts only. */
    fun earnedText(loot: Map<Resource, Long>): String =
        Resource.entries.mapNotNull { r -> loot[r]?.let { "${Format.signed(it)} ${currency(r.key)}" } }.joinToString(", ")
            .ifEmpty { "loot not read" }

    private fun resultText(e: JSONObject): String {
        val parts = mutableListOf(earnedText(Format.parseLoot(str(e, "earned"))))
        int(e, "stars")?.let { parts += Format.count(it, "star") }
        int(e, "damagePercent")?.let { parts += "$it% damage" }
        return parts.joinToString(", ")
    }

    private fun lootOffered(text: String?): String {
        val loot = Format.parseLoot(text)
        if (loot.isEmpty()) return "unread loot"
        return Resource.entries.mapNotNull { r -> loot[r]?.let { "${Format.amount(it)} ${currency(r.key)}" } }.joinToString(", ")
    }

    private fun earlyEnd(e: JSONObject): String = when (str(e, "reason")) {
        "damage" -> "Ended the battle early at ${int(e, "damagePercent")?.let { "$it%" } ?: "the target"} damage" +
            (int(e, "damageTarget")?.let { " (target $it%)" } ?: "")
        "loot" -> "Ended the battle early: little loot left"
        "stall" -> "Ended a stalled battle early"
        null -> "Ended the battle early"
        else -> "Ended the battle early: ${words(str(e, "reason")!!)}"
    }

    private fun collectionFinished(e: JSONObject): Pair<String, Tone> {
        val of = "${int(e, "verified") ?: 0} of ${int(e, "attempted") ?: 0} collectors"
        val loot = Format.parseLoot(str(e, "delta"))
        return (if (loot.isEmpty()) "Collection done: $of" else "Collected ${earnedText(loot)} ($of)") to Tone.OK
    }

    /**
     * Setup's tray check logs as an inspection tagged "check":"tray". A frame it could not check has
     * no "layout"; otherwise each "slots" entry is name=badge/saturation/level.
     */
    private fun trayCheck(e: JSONObject): Pair<String, Tone> {
        if (str(e, "layout") == null) {
            val state = str(e, "state") ?: "UNKNOWN"
            // The check refuses a frame of the wrong size before it looks at the screen, and a
            // scout screen is refused for nothing else.
            val portrait = (int(e, "height") ?: 0) > (int(e, "width") ?: 0)
            return (if (portrait || state.equals("SCOUT", ignoreCase = true)) "Tray check stopped: Clash was still turning"
                else "Tray check stopped: not a scout screen (${screen(state)})") to Tone.WARN
        }
        val levels = str(e, "slots")?.split(' ')?.map { it.substringAfterLast('/') }.orEmpty()
        val of = if (levels.isEmpty()) "the" else "${levels.size}"
        val wrong = int(e, "wrong") ?: levels.count { it == "bad" }
        val unsure = levels.count { it == "warn" }
        return when {
            bool(e, "blank") == true -> "Checked the battle tray: no count badge read; take the check again" to Tone.WARN
            wrong > 0 -> "Checked the battle tray: $wrong of $of slots look wrong" to Tone.BAD
            unsure > 0 -> "Checked the battle tray: $unsure of $of slots need a look" to Tone.WARN
            else -> "Checked the battle tray: all $of slots look right" to Tone.OK
        }
    }

    /**
     * The first attack's saved army: "title" is the card's title and "outcome" how loading it went.
     * It is logged before Attack!, so a miss says what the run goes on to do, not what it did.
     */
    private fun armyRecipe(e: JSONObject): Pair<String, Tone> {
        val title = str(e, "title") ?: return "Tried to load a saved army" to Tone.NEUTRAL
        return when (str(e, "outcome")) {
            "loaded" -> if ((int(e, "tries") ?: 1) > 1) {
                "Loaded the saved army $title on a second try" to Tone.OK
            } else "Loaded the saved army $title" to Tone.OK
            "not_found" -> "Could not find the saved army $title in Saved Recipes; going on with the current army" to Tone.WARN
            "not_confirmed" -> "Tapped Use on the saved army $title but could not confirm it loaded; going on with the army on screen" to Tone.WARN
            "no_list" -> "Could not open Saved Recipes to load the saved army $title; going on with the current army" to Tone.WARN
            "lost_list" -> "Lost the Saved Recipes list while looking for the saved army $title; going on with the current army" to Tone.WARN
            "moved" -> "Could not read the saved army $title in the same place twice, so Use was not tapped; going on with the current army" to Tone.WARN
            "use_interrupted" -> "A pause cut short the tap on Use for the saved army $title and the list had changed after it; the tap may have gone through, so Use was not tapped again; going on with the army on screen" to Tone.WARN
            else -> "Tried to load the saved army $title" to Tone.NEUTRAL
        }
    }

    /** A look after a pause just before Attack!: "decision" is what it meant for the press. */
    private fun attackAfterPause(e: JSONObject): Pair<String, Tone> {
        val untold = str(e, "tab") == "UNKNOWN"
        return when (str(e, "decision")) {
            "SEND" -> "My Army was showing after the pause; pressing Attack!" to Tone.NEUTRAL
            "PASSED" -> "Attack! had gone through before the pause" to Tone.NEUTRAL
            "READY_AGAIN" -> if (untold) {
                "Could not tell the army tab after the pause; looking again before Attack!" to Tone.NEUTRAL
            } else {
                "My Army was not showing after the pause; going back to it before Attack!" to Tone.NEUTRAL
            }
            "STOP" -> when {
                str(e, "state") != "ARMY" -> "The army screen was gone after the pause, so Attack! was not pressed" to Tone.WARN
                untold -> "Could still not tell the army tab after a second pause, so Attack! was not pressed" to Tone.WARN
                else -> "My Army was still not showing after a second pause, so Attack! was not pressed" to Tone.WARN
            }
            else -> "Looked at the army screen again after the pause" to Tone.NEUTRAL
        }
    }

    private fun remoteCommand(e: JSONObject): String = when (str(e, "command")) {
        "start" -> "Remote page started ${remoteMode(str(e, "mode"))}${str(e, "account")?.let { " on $it" } ?: ""}"
        "combined" -> "Remote page started both accounts: ${str(e, "first") ?: "first"} then ${str(e, "second") ?: "second"}"
        "stop" -> "Remote page pressed Stop"
        "damage" -> if (bool(e, "on") == false) "Remote page turned off ending battles on damage"
            else "Remote page set battles to end at ${int(e, "min") ?: "?"}-${int(e, "max") ?: "?"}% damage"
        null -> "Remote page sent a command"
        else -> "Remote page: ${words(str(e, "command")!!)}"
    }

    private fun remoteMode(mode: String?): String = when (mode) {
        "attacks" -> "an attacks-only run"
        "farm" -> "a farming run"
        "attacks40" -> "40 attacks"
        "farm40" -> "Farm 40 + walls"
        "farm2h" -> "2 h + walls"
        "event" -> "an event troops run"
        "supervised" -> "a test attack"
        "walls" -> "a walls-only run"
        "switch" -> "an account switch"
        null -> "a run"
        else -> "a ${words(mode)} run"
    }

    private fun searchReason(reason: String?): String = when (reason) {
        "loot_met" -> "loot target met"
        "skip_limit" -> "skip limit reached"
        "search_time_limit" -> "search time ran out"
        "search_gold_limit" -> "search gold limit reached"
        "next_not_found" -> "the Next button was not found"
        "next_unconfirmed" -> "the Next button did not move on"
        "fee_unreadable" -> "the Next price could not be read"
        "countdown_unreadable" -> "the countdown could not be read"
        "countdown_too_short" -> "too little scout time left"
        "loot_unreadable" -> "the loot could not be read"
        null -> "no reason given"
        else -> words(reason)
    }

    private fun wallOutcome(outcome: String?): String {
        if (outcome == null) return "unknown"
        if (outcome == "Empty") return "nothing to buy"
        if (outcome == "NoWalls") return "no walls left to upgrade"
        val reason = Regex("""reason=([A-Z_]+)""").find(outcome)?.groupValues?.get(1)
        val head = outcome.substringBefore('(')
        return when {
            reason != null -> "${words(head).lowercase(Locale.ROOT)}, ${words(reason).lowercase(Locale.ROOT)}"
            else -> words(head).replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.ROOT)
        }
    }

    private fun storageText(e: JSONObject): String =
        Resource.entries.mapNotNull { r -> long(e, r.key)?.let { "${Format.amount(it)} ${currency(r.key)}" } }
            .joinToString(", ").ifEmpty { "nothing read" }

    private fun health(e: JSONObject): String {
        val parts = mutableListOf<String>()
        int(e, "level")?.let { parts += "battery $it%" }
        when (bool(e, "charging")) { true -> parts += "charging"; false -> parts += "not charging"; null -> {} }
        double(e, "tempC")?.let { parts += String.format(Locale.US, "%.1f C", it) }
        return parts.joinToString(", ").ifEmpty { "battery and temperature not read" }
    }

    /**
     * Where a pause took. A gesture's own name ("Tap at Point(x=218, y=188)") reads as the
     * gesture it interrupted; anything else is the screen or step it was on.
     */
    private fun pausedAt(at: String?): String {
        if (at == null) return ""
        val gesture = GESTURE_PAUSE.find(at)?.groupValues?.get(1)
        return if (gesture != null) " during a ${gesture.lowercase(Locale.ROOT)}" else " on the ${screen(at)}"
    }

    private val GESTURE_PAUSE = Regex("^(Tap|Swipe|Pinch) ")

    private fun screen(state: String): String = when (state.uppercase(Locale.ROOT)) {
        "HOME" -> "village screen"
        "SCOUT" -> "scout screen"
        "BATTLE" -> "battle"
        "ARMY" -> "army screen"
        "RESULT" -> "result screen"
        "MENU", "ATTACK_MENU" -> "attack menu"
        "WALL" -> "wall panel"
        "SEARCHING" -> "search screen"
        "UNKNOWN" -> "an unknown screen"
        else -> words(state).lowercase(Locale.ROOT) + " screen"
    }

    private fun hero(name: String?): String = when (name) {
        "king" -> "Barbarian King"
        "queen" -> "Archer Queen"
        "warden" -> "Grand Warden"
        "champion" -> "Royal Champion"
        "prince" -> "Minion Prince"
        null -> "hero"
        else -> words(name)
    }

    private fun spell(name: String?): String = when (name) {
        "rages" -> "rage spells"
        "jumps" -> "jump spells"
        null -> "spells"
        else -> words(name) + " spells"
    }

    private fun currency(key: String?): String = when (key) {
        "gold" -> "gold"
        "elixir" -> "elixir"
        "dark_elixir" -> "dark elixir"
        null -> "resources"
        else -> words(key)
    }

    private fun resource(key: String?): Resource = Resource.fromKey(key ?: "") ?: Resource.GOLD

    private fun seconds(ms: Long?): String = if (ms == null) "a moment" else "${ms / 1000} s"

    // ----- JSON helpers that treat null and "null" as absent -----

    internal fun str(e: JSONObject, key: String): String? =
        if (!e.has(key) || e.isNull(key)) null else e.optString(key).takeIf { it.isNotEmpty() && it != "null" }

    internal fun int(e: JSONObject, key: String): Int? =
        if (!e.has(key) || e.isNull(key)) null else (e.opt(key) as? Number)?.toInt() ?: e.optString(key).toIntOrNull()

    internal fun long(e: JSONObject, key: String): Long? =
        if (!e.has(key) || e.isNull(key)) null else (e.opt(key) as? Number)?.toLong() ?: e.optString(key).toLongOrNull()

    private fun double(e: JSONObject, key: String): Double? =
        if (!e.has(key) || e.isNull(key)) null else (e.opt(key) as? Number)?.toDouble() ?: e.optString(key).toDoubleOrNull()

    internal fun bool(e: JSONObject, key: String): Boolean? =
        if (!e.has(key) || e.isNull(key)) null else when (val v = e.opt(key)) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            else -> null
        }
}
