package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.PendingWallPurchase
import local.clashfarmer.core.Resource
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.insight.LogFixtures.event
import local.clashfarmer.insight.LogFixtures.real
import local.clashfarmer.insight.LogFixtures.shaped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ProblemsTest {
    private val utc = ZoneOffset.UTC
    private val accounts = listOf(
        AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU"),
        AccountRecord(BLOOD, "Blood", "Philly0105", "#Y2G29YJQ8")
    )
    private val marker = PendingWallPurchase(BLOOD, Resource.GOLD, 3, 24_000_000, 5_000_000, 29_000_000,
        Format.parseTime("2026-10-08T09:33:25.580Z")!!)

    private fun collect(
        events: List<org.json.JSONObject>,
        markers: List<PendingWallPurchase> = emptyList(),
        dismissed: Map<String, Long> = emptyMap(),
        health: Map<String, List<Finding>> = emptyMap()
    ) = Problems.collect(events, accounts, markers, health, dismissed, utc)

    @Test
    fun purchaseOutcomeUnknownIsRedAndDismissibleWithoutAMarker() {
        val problems = collect(listOf(real("wall_purchase_outcome_unknown")))
        val p = problems.single()
        assertEquals(Tone.BAD, p.tone)
        assertEquals("Blood: wall purchase not confirmed", p.title)
        assertTrue(p.detail.startsWith("Wall purchase not confirmed: 3 walls for 24.0M gold"))
        assertTrue(p.dismissible)
    }

    @Test
    fun aPendingMarkerReplacesTheLogEntryAndCannotBeDismissed() {
        val first = collect(listOf(real("wall_purchase_outcome_unknown")), listOf(marker))
        val p = first.single()
        assertEquals("marker:$BLOOD", p.key)
        assertEquals(Problem.Action.CHECK_VILLAGE, p.action)
        assertFalse(p.dismissible)
        val dismissed = Problems.dismissAll(emptyMap(), first, 1L)
        assertTrue(dismissed.isEmpty())
        // Even a stored dismissal of its key does not hide it.
        assertEquals(1, collect(emptyList(), listOf(marker), mapOf("marker:$BLOOD" to 1L)).size)
    }

    @Test
    fun deviceGuardStopIsAnAmberEntry() {
        val p = collect(listOf(shaped("device_guard_stop"))).single()
        assertEquals(Tone.WARN, p.tone)
        assertEquals("Sweat: ended early to protect the phone", p.title)
        assertTrue(p.detail, p.detail.contains("battery 12%, not charging, 36.5 C"))
    }

    @Test
    fun storageFullFromAFinishAndAStopIsOneEntryPerDay() {
        val finished = event("2026-10-08T13:52:22.246Z", "session_finished", "accountId" to BLOOD, "accountName" to "Blood",
            "completed" to 0, "reason" to "storage_full", "detail" to "Storage full; walls skipped: no builder is free")
        val stopped = real("run_stopped").put("accountId", BLOOD).put("time", "2026-10-08T14:00:00Z")
        val problems = collect(listOf(finished, stopped))
        val p = problems.single()
        assertEquals("full:$BLOOD:2026-10-08", p.key)
        assertEquals("Blood: storage full", p.title)
        assertEquals(Tone.WARN, p.tone)
    }

    @Test
    fun storageFullWithEveryWallMaxedDoesNotAskForABuilderOrWalls() {
        val p = collect(Timeline.parse(LogFixtures.FULL_WALLS_MAXED)).single()
        assertEquals("full:$SWEAT:2026-10-14", p.key)
        assertEquals("Sweat: storage full", p.title)
        assertEquals("The builder's list has no wall left to upgrade, so every wall is maxed. " +
            "Farming adds nothing until gold or elixir is spent another way in Clash.", p.detail)
        // The same stop at the end of a combined run's second part.
        assertEquals(p.detail, collect(Timeline.parse(LogFixtures.FULL_WALLS_MAXED_COMBINED)).single().detail)
        // A walls-only run on maxed walls needs nobody.
        assertTrue(collect(Timeline.parse(LogFixtures.WALLS_ONLY_MAXED + LogFixtures.WALLS_ONLY_MAXED_FINISH)).isEmpty())
    }

    @Test
    fun storageFullWithNoBuilderOrWallsOffKeepsItsAdvice() {
        fun detail(why: String) = collect(listOf(event("2026-10-08T13:52:22.246Z", "session_finished", "accountId" to BLOOD,
            "accountName" to "Blood", "completed" to 0, "reason" to "storage_full", "detail" to why))).single().detail
        assertEquals("Farming adds nothing until gold or elixir is spent: storage full, no free builder. Free a builder or turn walls on.",
            detail("Storage full; walls skipped: no builder is free"))
        assertEquals("Farming adds nothing until gold or elixir is spent: storage full, nothing left to spend it on. Free a builder or turn walls on.",
            detail("Storage full; wall spending is off"))
    }

    @Test
    fun aDismissedKeyStaysHidden() {
        val events = listOf(shaped("device_guard_stop"), real("wall_purchase_outcome_unknown"))
        val all = collect(events)
        assertEquals(2, all.size)
        val guard = all.first { it.key.startsWith("guard:") }
        val at = guard.timeMs!! + 60_000L
        val dismissed = Problems.dismiss(emptyMap(), guard, at)
        val text = Problems.dismissedToJson(dismissed, at + 1_000L)
        val after = collect(events, dismissed = Problems.dismissedFromJson(text))
        assertEquals(1, after.size)
        assertFalse(after.any { it.key == guard.key })
    }

    @Test
    fun oldDismissalsArePrunedAndABrokenFileDismissesNothing() {
        val now = 100L * 86_400_000L
        val text = Problems.dismissedToJson(mapOf("old" to 0L, "new" to now - 1_000L), now)
        assertEquals(setOf("new"), Problems.dismissedFromJson(text).keys)
        assertTrue(Problems.dismissedFromJson("{not json").isEmpty())
        assertTrue(Problems.dismissedFromJson(null).isEmpty())
    }

    @Test
    fun needsYouStopsAreRedAndAPersonsStopIsNotAProblem() {
        val taken = event("2026-10-08T07:00:00Z", "run_stopped", "accountName" to "Sweat", "reason" to "Village opened on another device")
        val user = event("2026-10-08T07:05:00Z", "run_stopped", "reason" to "Stopped from floating panel")
        val overlay = event("2026-10-08T07:06:00Z", "run_stopped", "reason" to "Overlay Stop clicked")
        val p = collect(listOf(taken, user, overlay)).single()
        assertEquals("Sweat: run stopped, the village was opened on another device", p.title)
        assertEquals(Tone.BAD, p.tone)
    }

    @Test
    fun threeBuilderSkipsInADayBecomeOneEntry() {
        val skip = { t: String -> real("wall_phase_skipped_no_builder").put("time", t) }
        assertTrue(collect(listOf(skip("2026-10-08T01:00:00Z"), skip("2026-10-08T02:00:00Z"))).isEmpty())
        val p = collect(listOf(skip("2026-10-08T01:00:00Z"), skip("2026-10-08T02:00:00Z"), skip("2026-10-08T03:00:00Z"),
            skip("2026-10-09T03:00:00Z"))).single()
        assertEquals("Blood: walls skipped 3 times: no free builder", p.title)
        assertEquals("nobuilder:$BLOOD:2026-10-08", p.key)
    }

    @Test
    fun refusedRemoteVisitorsAreGroupedByPeerAndDay() {
        val denied = { t: String -> real("remote_denied").put("time", t) }
        val p = collect(listOf(denied("2026-10-08T06:08:11Z"), denied("2026-10-08T06:08:12Z"),
            event("2026-10-08T06:09:00Z", "remote_peer_refused", "peer" to "100.103.97.114"))).single()
        assertEquals("Remote page refused 100.103.97.114", p.title)
        assertTrue(p.detail, p.detail.startsWith("3 requests from 100.103.97.114 were refused"))
    }

    @Test
    fun calibrationWarningsBecomeSetupEntriesAndRedSortsFirst() {
        val health = mapOf(BLOOD to listOf(
            Finding(Finding.Kind.ATTACK_INCOMPLETE, Finding.Area.CALIBRATION, Tone.BAD, "Attack calibration incomplete", "detail"),
            Finding(Finding.Kind.COPIED_ANCHORS, Finding.Area.TRAY, Tone.WARN, "Same taps as Sweat's (5 of 6)", "detail", SWEAT, "1a2b")
        ))
        val problems = collect(listOf(shaped("device_guard_stop")), health = health)
        assertEquals(3, problems.size)
        assertEquals("Blood: attack calibration incomplete", problems[0].title)
        assertEquals(Problem.Action.OPEN_SETUP, problems[0].action)
        assertEquals("health:$BLOOD:attack_incomplete", problems[0].key)
        assertEquals(Tone.WARN, problems[2].tone)
        assertEquals(3, Problems.toJson(problems).length())
    }

    @Test
    fun aDismissedCopiedTrayComesBackOnlyWhenTheTapsChange() {
        fun copied(print: String) = mapOf(BLOOD to listOf(
            Finding(Finding.Kind.COPIED_ANCHORS, Finding.Area.TRAY, Tone.WARN, "Same taps as Sweat's (5 of 6)", "detail", SWEAT, print)))
        val p = collect(emptyList(), health = copied("1a2b")).single()
        assertEquals("health:$BLOOD:copied_anchors:1a2b", p.key)
        val dismissed = Problems.dismiss(emptyMap(), p, 1_000L)
        assertTrue(collect(emptyList(), dismissed = dismissed, health = copied("1a2b")).isEmpty())
        assertEquals(1, collect(emptyList(), dismissed = dismissed, health = copied("9f9f")).size)
    }

    @Test
    fun aDayKeyedProblemShowsAgainWhenItRepeatsAfterTheDismissal() {
        val denied = { t: String -> real("remote_denied").put("time", t) }
        val morning = listOf(denied("2026-10-08T06:08:11Z"), denied("2026-10-08T06:08:12Z"))
        val p = collect(morning).single()
        val dismissed = Problems.dismiss(emptyMap(), p, Format.parseTime("2026-10-08T09:00:00Z")!!)
        assertTrue(collect(morning, dismissed = dismissed).isEmpty())
        val again = collect(morning + denied("2026-10-08T14:00:00Z"), dismissed = dismissed).single()
        assertEquals(p.key, again.key)
        assertTrue(again.detail, again.detail.startsWith("3 requests"))
    }

    @Test
    fun aCheckedVillageDoesNotBringBackTheUnconfirmedPurchase() {
        val unknown = real("wall_purchase_outcome_unknown")
        val checked = { t: String -> event(t, "pending_purchase_acknowledged", "accountId" to BLOOD) }
        assertTrue(collect(listOf(unknown, checked("2026-10-08T10:00:00Z"))).isEmpty())
        // A check before this purchase, or of another account, does not resolve it.
        assertEquals(1, collect(listOf(checked("2026-10-08T08:00:00Z"), unknown)).size)
        assertEquals(1, collect(listOf(unknown, event("2026-10-08T10:00:00Z", "pending_purchase_acknowledged", "accountId" to SWEAT))).size)
    }
}
