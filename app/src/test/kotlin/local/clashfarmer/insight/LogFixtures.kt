package local.clashfarmer.insight

import org.json.JSONObject

/**
 * Log lines for the insight tests. Lines marked "real" are copied unchanged from the phone's logs
 * (the audit-evidence/overnight JSONL logs and earlier runs, 2026-09-20 to 2026-10-08). The rest follow
 * the fields of the matching AppLog.log call for events no saved log happens to hold.
 */
object LogFixtures {
    const val SWEAT = "f4a3bda6-b13d-3d81-a8e5-c5602dbbb500"
    const val BLOOD = "0130d53a-41a1-38cf-9069-f88b32445475"

    /** Real: smoke-final.jsonl, one Sweat attack ending on full storage, chatter left out. */
    val SMOKE_RUN = listOf(
        """{"time":"2026-10-08T19:57:16.257Z","event":"account_switch_start","accountId":"$SWEAT","accountName":"Sweat","targetId":"$SWEAT","targetVillage":"Sweat"}""",
        """{"time":"2026-10-08T19:57:32.800Z","event":"start_reached_home","accountId":"$SWEAT","accountName":"Sweat","fromState":"UNKNOWN","state":"HOME","steps":8,"elapsedMs":13665}""",
        """{"time":"2026-10-08T19:57:38.483Z","event":"account_switch_required","accountId":"$SWEAT","accountName":"Sweat","currentVillage":"Blood","currentTag":"#Y2G29YJQ8","targetVillage":"Sweat","targetTag":"#8YQCYG2RU"}""",
        """{"time":"2026-10-08T19:58:01.785Z","event":"account_switch_verified","accountId":"$SWEAT","accountName":"Sweat","village":"Sweat","tag":"#8YQCYG2RU"}""",
        """{"time":"2026-10-08T19:58:02.760Z","event":"early_end_damage_floor","accountId":"$SWEAT","accountName":"Sweat","damageMin":65,"damageMax":70,"target":70,"floor":70,"message":"Surrendering under 70% damage loses part of the league bonus"}""",
        """{"time":"2026-10-08T19:58:02.951Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":40,"maxMinutes":120,"verified":true,"sessionId":"0c9e9bf1-ef3b-496a-af95-e9821475f22a"}""",
        """{"time":"2026-10-08T19:58:02.969Z","event":"device_health","accountId":"$SWEAT","accountName":"Sweat","level":39,"charging":false,"tempC":33.8}""",
        """{"time":"2026-10-08T19:58:11.368Z","event":"storage_read","accountId":"$SWEAT","accountName":"Sweat","gold":25240000,"elixir":25240000}""",
        """{"time":"2026-10-08T19:59:20.397Z","event":"opponent_accepted","accountId":"$SWEAT","accountName":"Sweat","loot":"gold=942097,elixir=1023917,dark_elixir=15723","skips":0,"maxSkips":5,"spentGold":0,"maxSearchGold":7500,"elapsedMs":8371,"maxSearchMs":90000,"reason":"loot_met"}""",
        """{"time":"2026-10-08T19:59:23.024Z","event":"battle_started","accountId":"$SWEAT","accountName":"Sweat","battleId":"50f837a6-63a7-41d5-8b5c-8e563a4c87a8","sessionId":"0c9e9bf1-ef3b-496a-af95-e9821475f22a"}""",
        """{"time":"2026-10-08T20:00:12.033Z","event":"early_end","accountId":"$SWEAT","accountName":"Sweat","reason":"damage","sinceDeployMs":18184,"secondsLeft":null,"damagePercent":72,"damageTarget":70,"lootLeft":"","try":1,"x":148,"y":536}""",
        """{"time":"2026-10-08T20:00:23.161Z","event":"battle_result","accountId":"$SWEAT","accountName":"Sweat","frames":3,"earned":"gold=560437,elixir=656826,dark_elixir=5467","damagePercent":73,"stars":1}""",
        """{"time":"2026-10-08T20:00:28.595Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":1,"limit":40}""",
        """{"time":"2026-10-08T20:00:33.691Z","event":"storage_full_stop","accountId":"$SWEAT","accountName":"Sweat","reason":"Storage full; no wall left to buy","completed":1}""",
        """{"time":"2026-10-08T20:00:33.696Z","event":"session_finished","accountId":"$SWEAT","accountName":"Sweat","completed":1,"reason":"storage_full","detail":"Storage full; no wall left to buy"}""",
        """{"time":"2026-10-08T20:00:33.811Z","event":"notification_posted","kind":"finished","title":"Sweat: Run finished","reason":"Storage full; no wall left to buy, 1 attack"}"""
    )

    /**
     * Real: r3-log-final.jsonl:695, 697 and 710-713, a walls-only run on Sweat, where every wall is
     * maxed. The list held no Wall row, so the phase ended NoWalls. Logged before the line carried
     * "noWalls"; [WALLS_ONLY_MAXED_FINISH] is the same finish as logged since.
     */
    val WALLS_ONLY_MAXED = listOf(
        """{"time":"2026-10-14T07:55:31.660Z","event":"account_switch_verified","accountId":"$SWEAT","accountName":"Sweat","village":"Sweat","tag":"#8YQCYG2RU"}""",
        """{"time":"2026-10-14T07:55:32.814Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":true,"walls":true,"wallsEnabled":false,"triggerPercent":90,"resumePercent":75,"maxAttacks":15,"maxMinutes":30,"verified":true,"sessionId":"409a5a7a-2ef5-41a3-9199-49e188affc6d"}""",
        """{"time":"2026-10-14T07:56:07.786Z","event":"wall_phase_no_walls","accountId":"$SWEAT","accountName":"Sweat","reason":"no walls left to upgrade in the builder's list","scrolls":8}""",
        """{"time":"2026-10-14T07:56:12.211Z","event":"wall_phase_outcome","accountId":"$SWEAT","accountName":"Sweat","outcome":"NoWalls"}""",
        """{"time":"2026-10-14T07:56:12.219Z","event":"wall_session_finished","accountId":"$SWEAT","accountName":"Sweat","upgrades":0,"noFreeBuilder":false}""",
        """{"time":"2026-10-14T07:56:12.502Z","event":"notification_posted","kind":"finished","title":"Sweat: Run finished","reason":"Complete"}"""
    )

    /** Shaped: [WALLS_ONLY_MAXED]'s finish with the fields LocalRunner.runWalls logs now. */
    val WALLS_ONLY_MAXED_FINISH =
        """{"time":"2026-10-14T07:56:12.219Z","event":"wall_session_finished","accountId":"$SWEAT","accountName":"Sweat","upgrades":0,"noFreeBuilder":false,"balancesUnread":false,"noWalls":true}"""

    /**
     * Shaped: a farm run on Sweat whose wall phase found no Wall row, then filled storage. No log
     * has this ending. The first seven lines are real (r3-log-final.jsonl:1084, 1086, 1090, 1102,
     * 1103, 1140, 1148), from the second part of a combined run that ended on its quota (:1206),
     * so they carry its combinedRunId; with the combined lines left out they read as a run of its
     * own. The storage-full end is shaped on [SMOKE_RUN]'s, with the reason it gives now, and its
     * stop and finish carry the same combinedRunId.
     * [FULL_WALLS_MAXED_COMBINED] is the same run with its combined lines.
     */
    val FULL_WALLS_MAXED = listOf(
        """{"time":"2026-10-14T08:43:39.571Z","event":"account_switch_verified","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","village":"Sweat","tag":"#8YQCYG2RU"}""",
        """{"time":"2026-10-14T08:43:40.615Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","token":4,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":15,"resumePercent":10,"maxAttacks":2,"maxMinutes":111,"verified":true,"sessionId":"371c3e07-d681-41af-9b88-baa93b3b492f"}""",
        """{"time":"2026-10-14T08:43:50.887Z","event":"wall_checkpoint_triggered","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","currency":"elixir","balance":11927750,"capacity":26000000}""",
        """{"time":"2026-10-14T08:44:20.812Z","event":"wall_phase_no_walls","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","reason":"no walls left to upgrade in the builder's list","scrolls":8}""",
        """{"time":"2026-10-14T08:44:28.513Z","event":"wall_phase_outcome","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","outcome":"NoWalls"}""",
        """{"time":"2026-10-14T08:45:30.172Z","event":"battle_result","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","frames":2,"earned":"gold=412788,elixir=302103,dark_elixir=2428","damagePercent":73,"stars":2}""",
        """{"time":"2026-10-14T08:45:55.350Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","completed":1,"limit":2}""",
        """{"time":"2026-10-14T08:46:01.000Z","event":"storage_full_stop","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","reason":"Storage full; every wall is maxed","completed":1}""",
        """{"time":"2026-10-14T08:46:01.005Z","event":"session_finished","accountId":"$SWEAT","accountName":"Sweat","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","completed":1,"reason":"storage_full","detail":"Storage full; every wall is maxed"}""",
        """{"time":"2026-10-14T08:46:01.120Z","event":"notification_posted","kind":"finished","title":"Sweat: Run finished","reason":"Storage full; every wall is maxed, 1 attack"}"""
    )

    /**
     * [FULL_WALLS_MAXED] as the combined run it came from: its real start and handoff
     * (r3-log-final.jsonl:916, 1075) ahead, and a shaped end for a second part that filled storage.
     */
    val FULL_WALLS_MAXED_COMBINED = listOf(
        """{"time":"2026-10-14T08:35:03.111Z","event":"combined_run_started","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","firstAccount":"Blood","firstQuota":2,"secondAccount":"Sweat","secondQuota":2,"globalDeadlineMinutes":120}""",
        """{"time":"2026-10-14T08:43:09.665Z","event":"account_handoff","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","from":"Blood","to":"Sweat","completed":2,"reason":"quota_reached"}"""
    ) + FULL_WALLS_MAXED.dropLast(1) + listOf(
        """{"time":"2026-10-14T08:46:01.010Z","event":"combined_run_completed","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","firstCompleted":2,"firstReason":"quota_reached","secondCompleted":1,"secondReason":"storage_full"}""",
        """{"time":"2026-10-14T08:46:01.120Z","event":"notification_posted","combinedRunId":"a919dd0a-bc65-4662-9c89-b5e0eacb7b63","kind":"finished","title":"Combined run finished","reason":"Blood: 2 attacks, Sweat: 1 attacks (Storage full; every wall is maxed)"}"""
    )

    /**
     * Real: accept/run2h-log.jsonl, the 2-hour Blood run of 14 October with only its run lines kept.
     * The village was opened on another device mid-battle after 12 attacks; the run waited, took it
     * back and went on in a second session told the 88 attacks still owed, until its time limit.
     */
    val TAKEOVER_RUN = listOf(
        """{"time":"2026-10-14T15:26:21.444Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":4,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":100,"maxMinutes":120,"verified":true,"sessionId":"1c720eea-bd11-4268-9167-d9ca27e2cdeb"}""",
        """{"time":"2026-10-14T15:30:03.445Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=75716,elixir=222050,dark_elixir=820","damagePercent":27,"stars":0}""",
        """{"time":"2026-10-14T15:30:13.843Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":1,"limit":100}""",
        """{"time":"2026-10-14T15:34:21.626Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=847,elixir=207105,dark_elixir=2714","damagePercent":23,"stars":0}""",
        """{"time":"2026-10-14T15:34:30.643Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":2,"limit":100}""",
        """{"time":"2026-10-14T15:38:23.143Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=123760,elixir=103995,dark_elixir=3800","damagePercent":19,"stars":0}""",
        """{"time":"2026-10-14T15:38:32.462Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":3,"limit":100}""",
        """{"time":"2026-10-14T15:42:21.682Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=93556,elixir=92968,dark_elixir=930","damagePercent":22,"stars":0}""",
        """{"time":"2026-10-14T15:42:30.982Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":4,"limit":100}""",
        """{"time":"2026-10-14T15:46:37.903Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=4748,elixir=3585,dark_elixir=31","damagePercent":21,"stars":0}""",
        """{"time":"2026-10-14T15:46:47.182Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":5,"limit":100}""",
        """{"time":"2026-10-14T15:53:20.408Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=319164,elixir=638220,dark_elixir=6300","damagePercent":25,"stars":0}""",
        """{"time":"2026-10-14T15:53:29.960Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":6,"limit":100}""",
        """{"time":"2026-10-14T15:57:19.970Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=899660,elixir=391393,dark_elixir=300","damagePercent":29,"stars":0}""",
        """{"time":"2026-10-14T15:57:26.620Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":7,"limit":100}""",
        """{"time":"2026-10-14T16:01:18.402Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=471728,elixir=503640,dark_elixir=7690","damagePercent":43,"stars":0}""",
        """{"time":"2026-10-14T16:01:26.626Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":8,"limit":100}""",
        """{"time":"2026-10-14T16:05:21.256Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":4,"earned":"gold=211456,elixir=135877","damagePercent":16,"stars":0}""",
        """{"time":"2026-10-14T16:05:30.252Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":9,"limit":100}""",
        """{"time":"2026-10-14T16:09:29.263Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=376030,elixir=552460,dark_elixir=8000","damagePercent":22,"stars":0}""",
        """{"time":"2026-10-14T16:09:38.447Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":10,"limit":100}""",
        """{"time":"2026-10-14T16:13:29.131Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=324342,elixir=416665,dark_elixir=1393","damagePercent":28,"stars":0}""",
        """{"time":"2026-10-14T16:13:37.446Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":11,"limit":100}""",
        """{"time":"2026-10-14T16:17:33.214Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=91330,elixir=241294,dark_elixir=5565","damagePercent":22,"stars":0}""",
        """{"time":"2026-10-14T16:17:42.587Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":12,"limit":100}""",
        """{"time":"2026-10-14T16:18:36.331Z","event":"wall_upgraded","accountId":"$BLOOD","accountName":"Blood","currency":"elixir","walls":3,"toLevel":19,"cost":24000000}""",
        """{"time":"2026-10-14T16:21:56.387Z","event":"village_taken_elsewhere","accountId":"$BLOOD","accountName":"Blood","kind":"connection_lost","text":"XtReMe Tactical 11 Available Loot: 706 006 602 739 10 238 SURReNdeR X5 12 X2 XO 12 Connection lost Battle ends in: IM 475 Another device is connecting to this Village. Reload 100 100 22 109 836 ( 2463"}""",
        """{"time":"2026-10-14T16:21:58.355Z","event":"notification_posted","kind":"needs_you","title":"Blood: Village opened on another device","reason":"Retrying in 10 minutes. Close the game on the other device, or the run takes the village back."}""",
        """{"time":"2026-10-14T16:21:58.358Z","event":"village_retry_wait","account":"Blood","waitMs":600000,"remaining":88}""",
        """{"time":"2026-10-14T16:31:59.906Z","event":"village_retry_reopening","account":"Blood"}""",
        """{"time":"2026-10-14T16:32:19.710Z","event":"village_retry_verified","account":"Blood","remaining":88}""",
        """{"time":"2026-10-14T16:32:28.869Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":8,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":88,"maxMinutes":120,"verified":true,"sessionId":"626404c2-3db9-4eff-8ac1-487d97a3d3ac"}""",
        """{"time":"2026-10-14T16:36:19.079Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=302385,elixir=300888,dark_elixir=2630","damagePercent":28,"stars":0}""",
        """{"time":"2026-10-14T16:36:28.458Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":1,"limit":88}""",
        """{"time":"2026-10-14T16:40:27.841Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=153820,elixir=17595,dark_elixir=132","damagePercent":31,"stars":0}""",
        """{"time":"2026-10-14T16:40:36.301Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":2,"limit":88}""",
        """{"time":"2026-10-14T16:44:42.783Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=34918,elixir=24503,dark_elixir=10","damagePercent":20,"stars":0}""",
        """{"time":"2026-10-14T16:44:52.244Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":3,"limit":88}""",
        """{"time":"2026-10-14T16:48:54.740Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=189860,elixir=148818,dark_elixir=1224","damagePercent":19,"stars":0}""",
        """{"time":"2026-10-14T16:49:04.059Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":4,"limit":88}""",
        """{"time":"2026-10-14T16:53:02.028Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=137003,elixir=82111,dark_elixir=240","damagePercent":36,"stars":0}""",
        """{"time":"2026-10-14T16:53:10.027Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":5,"limit":88}""",
        """{"time":"2026-10-14T16:57:00.205Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=461479,elixir=546094,dark_elixir=4324","damagePercent":24,"stars":0}""",
        """{"time":"2026-10-14T16:57:10.649Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":6,"limit":88}""",
        """{"time":"2026-10-14T17:00:59.575Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=184002,elixir=191540,dark_elixir=989","damagePercent":28,"stars":0}""",
        """{"time":"2026-10-14T17:01:09.323Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":7,"limit":88}""",
        """{"time":"2026-10-14T17:02:10.836Z","event":"wall_upgraded","accountId":"$BLOOD","accountName":"Blood","currency":"gold","walls":3,"toLevel":19,"cost":24000000}""",
        """{"time":"2026-10-14T17:06:12.847Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=76545,elixir=6812,dark_elixir=894","damagePercent":14,"stars":0}""",
        """{"time":"2026-10-14T17:06:20.720Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":8,"limit":88}""",
        """{"time":"2026-10-14T17:12:53.779Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=321192,elixir=222518,dark_elixir=3845","damagePercent":27,"stars":0}""",
        """{"time":"2026-10-14T17:13:03.397Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":9,"limit":88}""",
        """{"time":"2026-10-14T17:17:02.900Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=6725,elixir=135769,dark_elixir=5257","damagePercent":18,"stars":0}""",
        """{"time":"2026-10-14T17:17:11.968Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":10,"limit":88}""",
        """{"time":"2026-10-14T17:21:04.802Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=106030,elixir=149600,dark_elixir=1124","damagePercent":14,"stars":0}""",
        """{"time":"2026-10-14T17:21:12.481Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":11,"limit":88}""",
        """{"time":"2026-10-14T17:25:04.096Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=54406,elixir=87967,dark_elixir=799","damagePercent":39,"stars":0}""",
        """{"time":"2026-10-14T17:25:13.880Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":12,"limit":88}""",
        """{"time":"2026-10-14T17:28:59.646Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=204013,elixir=212400,dark_elixir=1434","damagePercent":28,"stars":0}""",
        """{"time":"2026-10-14T17:29:07.085Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":13,"limit":88}""",
        """{"time":"2026-10-14T17:29:15.601Z","event":"session_finished","accountId":"$BLOOD","accountName":"Blood","completed":13,"reason":"time_limit","maxMinutes":120}""",
        """{"time":"2026-10-14T17:29:15.673Z","event":"notification_posted","kind":"finished","title":"Blood: Run finished","reason":"Complete, 25 attacks, 3 walls, 24000000 gold spent"}"""
    )

    /**
     * Real: overnight/baseline-log.jsonl, 8 October, run lines only. A Blood run of 40 restarted itself
     * after 4 attacks, went on in a session told the 36 still owed, made 1 more and was stopped; the
     * next run started 2 minutes later.
     */
    val AUTO_RESTART_RUN = listOf(
        """{"time":"2026-10-08T05:38:11.359Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":6,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":40,"maxMinutes":120,"verified":true,"sessionId":"0afb6f25-626a-4cc6-85a9-dd94b9fb3f4d"}""",
        """{"time":"2026-10-08T05:39:22.920Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=90340,elixir=71128,dark_elixir=1078","damagePercent":null}""",
        """{"time":"2026-10-08T05:39:28.198Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":1,"limit":40}""",
        """{"time":"2026-10-08T05:43:20.918Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=389138,elixir=1109010,dark_elixir=5303","damagePercent":null}""",
        """{"time":"2026-10-08T05:43:26.501Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":2,"limit":40}""",
        """{"time":"2026-10-08T05:45:08.548Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=627664,elixir=635446,dark_elixir=4578","damagePercent":null}""",
        """{"time":"2026-10-08T05:45:13.918Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":3,"limit":40}""",
        """{"time":"2026-10-08T05:47:07.933Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=722226,elixir=500131,dark_elixir=5068","damagePercent":null}""",
        """{"time":"2026-10-08T05:47:15.289Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":4,"limit":40}""",
        """{"time":"2026-10-08T05:47:42.051Z","event":"run_auto_restart","reason":"Unexpected transition screen: HOME (expected [ARMY]) reading: 259 M 29 Blood Attack! Collect Boost 24d 2h 2di7H Request 1\/2 1\/7 J4H 18M 22 604 71 19 S13709 470 000 1964 SHOP ","restart":1,"maxRestarts":3,"remaining":36}""",
        """{"time":"2026-10-08T05:47:46.904Z","event":"run_recovered","restart":1}""",
        """{"time":"2026-10-08T05:47:53.736Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":9,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":36,"maxMinutes":120,"verified":true,"sessionId":"5425559b-14d5-418f-bae4-318106d1d2aa"}""",
        """{"time":"2026-10-08T05:49:29.301Z","event":"battle_result","accountId":"$BLOOD","accountName":"Blood","frames":2,"earned":"gold=223623,elixir=662518,dark_elixir=2449","damagePercent":null}""",
        """{"time":"2026-10-08T05:49:34.733Z","event":"attack_completed","accountId":"$BLOOD","accountName":"Blood","completed":1,"limit":36}""",
        """{"time":"2026-10-08T05:49:42.126Z","event":"run_stopped","accountId":"$BLOOD","accountName":"Blood","reason":"Stopped from floating panel"}""",
        """{"time":"2026-10-08T05:51:25.973Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":11,"supervised":false,"walls":false,"wallsEnabled":false,"triggerPercent":90,"resumePercent":75,"maxAttacks":40,"maxMinutes":120,"verified":true,"sessionId":"893bbfd6-4933-41fc-b801-f43221053038"}""",
        """{"time":"2026-10-08T05:52:54.760Z","event":"run_stopped","accountId":"$BLOOD","accountName":"Blood","reason":"Stopped from floating panel"}"""
    )

    /**
     * Real: clash-18647/r4-log-run1.jsonl, 14 October, run lines only. A Sweat run of 3 restarted itself
     * and was stopped while recovering; a fresh run of 3 started 8 minutes later and finished.
     */
    val RESTART_STOPPED = listOf(
        """{"time":"2026-10-14T06:16:43.309Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":3,"maxMinutes":120,"verified":true,"sessionId":"33120033-8a2c-4396-806b-90ae491b1015"}""",
        """{"time":"2026-10-14T06:18:26.037Z","event":"run_auto_restart","reason":"Screen transition timed out waiting for [RESULT, REWARD] (last seen: UNKNOWN) reading: GOViP BEETLENUT ARMY SoUR ElixiR 200 X40 10 66 Total damage: 72% BEETLENU VictoRY 10 You got: 199 620 110 o00 4 824 Troops expended: 61 Claim RewaRd i 100% BONUS: +270000 +270000 +1800 7732 939 7388 1","restart":1,"maxRestarts":3,"remaining":3}""",
        """{"time":"2026-10-14T06:19:08.308Z","event":"run_stopped","reason":"Stopped from dashboard"}""",
        """{"time":"2026-10-14T06:19:08.367Z","event":"notification_posted","kind":"stopped","title":"Run stopped","reason":"Stopped from dashboard"}""",
        """{"time":"2026-10-14T06:27:37.235Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":3,"maxMinutes":120,"verified":true,"sessionId":"c1aabb33-f337-4adc-b55d-8b9c639fda75"}""",
        """{"time":"2026-10-14T06:29:13.501Z","event":"battle_result","accountId":"$SWEAT","accountName":"Sweat","frames":2,"earned":"gold=758263,elixir=487369,dark_elixir=708","damagePercent":63,"stars":1}""",
        """{"time":"2026-10-14T06:29:42.796Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":1,"limit":3}""",
        """{"time":"2026-10-14T06:31:42.545Z","event":"battle_result","accountId":"$SWEAT","accountName":"Sweat","frames":4,"earned":"gold=932706,elixir=653982,dark_elixir=15376","damagePercent":60,"stars":1}""",
        """{"time":"2026-10-14T06:31:49.542Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":2,"limit":3}""",
        """{"time":"2026-10-14T06:33:52.948Z","event":"battle_result","accountId":"$SWEAT","accountName":"Sweat","frames":3,"earned":"gold=703317,elixir=591070,dark_elixir=2594","damagePercent":56,"stars":1}""",
        """{"time":"2026-10-14T06:34:42.364Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":3,"limit":3}""",
        """{"time":"2026-10-14T06:34:48.613Z","event":"session_finished","accountId":"$SWEAT","accountName":"Sweat","completed":3,"reason":"quota_reached"}""",
        """{"time":"2026-10-14T06:34:48.753Z","event":"notification_posted","kind":"finished","title":"Sweat: Run finished","reason":"Complete, 3 attacks"}"""
    )

    /**
     * Real: debate/logs-1005.jsonl, 5 October, run lines only, each session's attacks down to its
     * last count. A 2-hour Sweat run restarted itself after 56 attacks and its process died in the
     * restart. The service connected again 8 hours later, and a run of 40 restarted itself before
     * its first attack, went on, and was stopped after 25.
     */
    val PROCESS_DIED_RUN = listOf(
        """{"time":"2026-10-05T09:34:33.595Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":0,"maxAttacks":100,"maxMinutes":120,"verified":true,"sessionId":"9f377567-71a7-4ebe-bf8f-4834f0d4ce25"}""",
        """{"time":"2026-10-05T09:35:29.531Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"gold","walls":5,"toLevel":17,"cost":25000000}""",
        """{"time":"2026-10-05T10:15:59.363Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"elixir","walls":4,"toLevel":17,"cost":20000000}""",
        """{"time":"2026-10-05T10:25:36.634Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"gold","walls":4,"toLevel":17,"cost":20000000}""",
        """{"time":"2026-10-05T11:01:19.520Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"elixir","walls":4,"toLevel":17,"cost":20000000}""",
        """{"time":"2026-10-05T11:18:09.526Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"gold","walls":5,"toLevel":17,"cost":25000000}""",
        """{"time":"2026-10-05T11:29:33.499Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":56,"limit":100}""",
        """{"time":"2026-10-05T11:31:06.724Z","event":"run_auto_restart","reason":"Clash is not in foreground (detected: android)","restart":1,"maxRestarts":3,"remaining":44}""",
        """{"time":"2026-10-05T19:31:39.515Z","event":"service_connected"}""",
        """{"time":"2026-10-05T19:34:32.645Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":2,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":75,"maxAttacks":40,"maxMinutes":120,"verified":true,"sessionId":"e77dbae9-8ad2-4f1e-ba1b-3bfe2856f37e"}""",
        """{"time":"2026-10-05T19:35:07.170Z","event":"run_auto_restart","reason":"Screen transition timed out waiting for [MENU] (last seen: UNKNOWN) reading: 239 Ati League OveRVieW Back ToWN Halls Top PlayeRS You've been demoted due to iNactivity! Sign up For Ranked to get back to your Town Hall's minimurm League! UNRaNked Top ClaNS Next X 6 ","restart":1,"maxRestarts":3,"remaining":40}""",
        """{"time":"2026-10-05T19:35:35.028Z","event":"run_recovered","restart":1}""",
        """{"time":"2026-10-05T19:35:42.033Z","event":"session_started","accountId":"$SWEAT","accountName":"Sweat","token":5,"supervised":false,"walls":false,"wallsEnabled":true,"triggerPercent":90,"resumePercent":75,"maxAttacks":40,"maxMinutes":120,"verified":true,"sessionId":"368ba131-fa0c-4cab-9c01-e1da62ca599d"}""",
        """{"time":"2026-10-05T20:18:46.071Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"elixir","walls":1,"toLevel":17,"cost":5000000}""",
        """{"time":"2026-10-05T20:31:34.789Z","event":"wall_upgraded","accountId":"$SWEAT","accountName":"Sweat","currency":"elixir","walls":1,"toLevel":17,"cost":5000000}""",
        """{"time":"2026-10-05T20:32:45.618Z","event":"attack_completed","accountId":"$SWEAT","accountName":"Sweat","completed":25,"limit":40}""",
        """{"time":"2026-10-05T20:32:51.194Z","event":"run_stopped","accountId":"$SWEAT","accountName":"Sweat","reason":"Stopped from dashboard"}"""
    )

    /** Real, one line per event: the shapes the required mappings are pinned on. */
    val REAL = mapOf(
        "session_started" to """{"time":"2026-10-08T05:35:33.971Z","event":"session_started","accountId":"$BLOOD","accountName":"Blood","token":4,"supervised":true,"walls":false,"wallsEnabled":false,"triggerPercent":90,"resumePercent":75,"maxAttacks":1,"maxMinutes":15,"verified":true,"sessionId":"608717d6-cfa9-4054-9591-711258cfffe9"}""",
        "run_stopped" to """{"time":"2026-10-08T05:47:00.000Z","event":"run_stopped","reason":"Gold and elixir are full and no wall phase can run again; attacking would waste the loot"}""",
        "account_switch_verified" to """{"time":"2026-10-08T19:58:01.785Z","event":"account_switch_verified","accountId":"$SWEAT","accountName":"Sweat","village":"Sweat","tag":"#8YQCYG2RU"}""",
        "wall_upgraded" to """{"time":"2026-09-20T04:03:57.904Z","event":"wall_upgraded","accountId":"$BLOOD","accountName":"Blood","currency":"gold","walls":1,"toLevel":18,"cost":5600000,"balance":8578841}""",
        "wall_phase_skipped_no_builder" to """{"time":"2026-10-08T13:52:19.809Z","event":"wall_phase_skipped_no_builder","accountId":"$BLOOD","accountName":"Blood","total":6,"reason":"second read is zero too","secondText":null}""",
        "wall_purchase_outcome_unknown" to """{"time":"2026-10-08T09:33:25.580Z","event":"wall_purchase_outcome_unknown","accountId":"$BLOOD","accountName":"Blood","stage":"unproven","currency":"gold","cost":24000000,"walls":3,"cause":"none"}""",
        "wall_spend_unverified" to """{"time":"2026-10-08T09:33:25.576Z","event":"wall_spend_unverified","accountId":"$BLOOD","accountName":"Blood","currency":"gold","cost":24000000,"expected":5000000,"settled":-1}""",
        "pending_purchase_acknowledged" to """{"time":"2026-10-08T11:05:26.048Z","event":"pending_purchase_acknowledged","accountId":"$BLOOD"}""",
        "village_taken_elsewhere" to """{"time":"2026-10-02T16:25:19.293Z","event":"village_taken_elsewhere","accountId":"$BLOOD","accountName":"Blood","text":"Melo 29 S0o Available Loot: 1 192 328 539 405 12 699 SURReNdeR X25) 100 Connection lost Battle ends in: 2M 555 Another device is connecting to this Village. Reload 9141997 3191 898 437 781 OveRalI Dam"}""",
        "village_retry_wait" to """{"time":"2026-10-02T16:25:20.463Z","event":"village_retry_wait","account":"Blood","waitMs":600000,"remaining":98}""",
        "run_auto_restart" to """{"time":"2026-10-08T05:47:42.051Z","event":"run_auto_restart","reason":"Unexpected transition screen: HOME (expected [ARMY]) reading: 259 M 29 Blood Attack! Collect Boost 24d 2h 2di7H Request 1\/2 1\/7 J4H 18M 22 604 71 19 S13709 470 000 1964 SHOP ","restart":1,"maxRestarts":3,"remaining":36}""",
        "run_recovered" to """{"time":"2026-10-08T05:47:46.904Z","event":"run_recovered","restart":1}""",
        "run_paused" to """{"time":"2026-10-08T05:42:50.025Z","event":"run_paused","accountId":"$BLOOD","accountName":"Blood","at":"SCOUT"}""",
        "run_resumed" to """{"time":"2026-10-08T05:43:11.579Z","event":"run_resumed","accountId":"$BLOOD","accountName":"Blood","at":"SCOUT"}""",
        "early_end" to """{"time":"2026-10-08T05:36:39.547Z","event":"early_end","accountId":"$BLOOD","accountName":"Blood","reason":"damage","sinceDeployMs":8741,"secondsLeft":141,"damagePercent":76,"damageTarget":66,"lootLeft":"","try":1,"x":148,"y":536}""",
        "collection_finished" to """{"time":"2026-10-08T05:52:51.891Z","event":"collection_finished","accountId":"$BLOOD","accountName":"Blood","attempted":17,"verified":6,"skipped":0,"reason":null,"before":"gold=23201660,elixir=20551227","after":"gold=23429258,elixir=20778549","delta":"gold=227598,elixir=227322"}""",
        "premium_dialog_refused" to """{"time":"2026-10-05T07:37:14.370Z","event":"premium_dialog_refused","accountId":"$SWEAT","accountName":"Sweat","where":"wall_confirmation","text":"239 ! Sweat, Attack! Collect 27 UpgRade Wall to Level 17? Hitpoints: Walls are great"}""",
        "notification_posted" to """{"time":"2026-10-08T20:00:33.811Z","event":"notification_posted","kind":"finished","title":"Sweat: Run finished","reason":"Storage full; no wall left to buy, 1 attack"}""",
        "inspect_completed" to """{"time":"2026-10-08T05:32:39.560Z","event":"inspect_completed","state":"UNKNOWN","boxes":3}""",
        "remote_command" to """{"time":"2026-10-02T15:05:00.703Z","event":"remote_command","command":"start","mode":"farm2h","account":"Blood"}""",
        "remote_denied" to """{"time":"2026-10-08T06:08:11.435Z","event":"remote_denied","accountId":"$SWEAT","accountName":"Sweat","peer":"100.103.97.114","path":"\/apple-touch-icon-precomposed.png"}""",
        "combined_run_started" to """{"time":"2026-09-24T10:48:00.904Z","event":"combined_run_started","combinedRunId":"f03ce3e5-79e5-49b6-ad1b-24c54d171513","firstAccount":"Sweat","firstQuota":1,"secondAccount":"Blood","secondQuota":40,"globalDeadlineMinutes":120}""",
        "combined_run_completed" to """{"time":"2026-09-24T11:07:54.610Z","event":"combined_run_completed","combinedRunId":"940bd73f-110a-460a-8e1d-7bb9e0da66dc","firstCompleted":0,"firstReason":"goal_reached","secondCompleted":0,"secondReason":"goal_reached"}""",
        "wall_session_finished" to """{"time":"2026-10-08T11:06:41.821Z","event":"wall_session_finished","accountId":"$BLOOD","accountName":"Blood","upgrades":0,"noFreeBuilder":true}"""
    )

    /** Shaped on the source's log call; no saved log holds one. */
    val SHAPED = mapOf(
        "account_handoff" to """{"time":"2026-10-08T07:00:00.000Z","event":"account_handoff","accountId":"$SWEAT","accountName":"Sweat","from":"Sweat","to":"Blood","completed":40,"reason":"quota_reached"}""",
        "walls_off_builders_busy" to """{"time":"2026-10-08T07:00:00.000Z","event":"walls_off_builders_busy","accountId":"$SWEAT","accountName":"Sweat","currency":"gold"}""",
        "device_guard_stop" to """{"time":"2026-10-08T07:00:00.000Z","event":"device_guard_stop","accountId":"$SWEAT","accountName":"Sweat","level":12,"charging":false,"tempC":36.5}""",
        "popup_blocked_premium" to """{"time":"2026-10-08T07:00:00.000Z","event":"popup_blocked_premium","popup":"gold_pass","text":"Gold Pass"}""",
        "connection_lost" to """{"time":"2026-10-08T07:00:00.000Z","event":"connection_lost","kind":"server","text":"Connection lost"}""",
        "maintenance_break" to """{"time":"2026-10-08T07:00:00.000Z","event":"maintenance_break","where":"scout","text":"Maintenance break"}""",
        "game_update_required" to """{"time":"2026-10-08T07:00:00.000Z","event":"game_update_required","text":"Update required"}""",
        "remote_start_refused" to """{"time":"2026-10-08T07:00:00.000Z","event":"remote_start_refused","account":"Blood","reason":"Device is locked"}""",
        // As written: the snapshot's own "event" field replaces the name (see EventText.nameOf).
        "failure_snapshot" to """{"time":"2026-10-08T07:00:00.000Z","event":"run_stopped","accountId":"$SWEAT","accountName":"Sweat","file":"captures/failure-run_stopped-20261008-070000.png"}"""
    )

    fun json(line: String) = JSONObject(line)
    fun real(name: String) = JSONObject(REAL.getValue(name))
    fun shaped(name: String) = JSONObject(SHAPED.getValue(name))

    fun event(time: String, name: String, vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("time", time)
        put("event", name)
        fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }
}
