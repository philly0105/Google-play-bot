package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountLastResult
import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.CompletionReason
import local.clashfarmer.core.Length
import local.clashfarmer.core.PendingWallPurchase
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.SessionSnapshot
import local.clashfarmer.core.SessionSummary
import local.clashfarmer.core.StorageReading
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.HomeCards
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeCardsTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L

    private val settings = AccountSettings(
        wallsEnabled = true,
        capacities = mapOf(Resource.GOLD to 29_000_000L, Resource.ELIXIR to 29_000_000L)
    )
    private val sweat = AccountRecord("11111111-2222-3333-4444-555555555555", "Sweat", "sweat", "#AAA", settings)
    private val blood = AccountRecord("66666666-7777-8888-9999-000000000000", "Blood", "blood", "#BBB", settings)
    private val utc = ZoneId.of("UTC")
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
    private val full = StorageReading(now, mapOf(Resource.GOLD to 29_000_000L, Resource.ELIXIR to 12_000_000L))
    private val half = StorageReading(now, mapOf(Resource.GOLD to 14_000_000L, Resource.ELIXIR to 12_000_000L))
    private val noBuilder = BuilderReading(0, 6, now)
    private val oneBuilder = BuilderReading(1, 6, now)

    private fun attention(
        account: AccountRecord = sweat,
        profile: PhoneProfile? = this.profile,
        markers: Map<String, PendingWallPurchase?> = emptyMap(),
        storage: StorageReading? = half,
        builders: BuilderReading? = oneBuilder,
        connected: Boolean = true,
        busy: Boolean = false
    ) = HomeCards.attention(account, profile, markers, storage, builders, connected, busy)

    private fun withResult(result: AccountLastResult) = sweat.copy(lastResult = result)

    @Test
    fun nothingToSayGivesNoCard() {
        assertNull(attention())
        assertNull(attention(account = sweat.copy(settings = settings.copy(wallsEnabled = false))))
        assertNull(HomeCards.attention(null, null, emptyMap(), null, null, connected = true, busy = false))
    }

    @Test
    fun fullStorageWithWallsOffSaysTurnThemOn() {
        val card = attention(account = sweat.copy(settings = settings.copy(wallsEnabled = false)), storage = full)!!
        assertEquals(HomeCards.Kind.FULL_WALLS_OFF, card.kind)
        assertEquals(Tone.WARN, card.tone)
        assertEquals("Storage is full", card.title)
        assertEquals("Walls are off; turn them on in Run or loot is wasted.", card.text)
        assertNull(card.button)
        // Walls off in the planned run says the same even when the account's switch is on.
        val planned = HomeCards.attention(sweat, profile, emptyMap(), full, oneBuilder, connected = true, busy = false, wallsOn = false)!!
        assertEquals(HomeCards.Kind.FULL_WALLS_OFF, planned.kind)
    }

    @Test
    fun wallsFollowTheRunStartWouldBegin() {
        val off = sweat.copy(settings = settings.copy(wallsEnabled = false))
        val blood = AccountRecord("66666666-7777-8888-9999-000000000000", "Blood", "blood", "#BBB", settings)
        val accounts = listOf(off, blood)
        val farm40Walls = RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(40), walls = true)
        assertTrue(HomeCards.wallsOn(off, farm40Walls, accounts, off.id))
        assertFalse(HomeCards.wallsOn(sweat, farm40Walls.copy(walls = false), listOf(sweat, blood), sweat.id))
        // Both accounts follow each account's saved switch; so does a plan for the other account.
        val both = RunPlan(listOf(RunPlan.SELECTED, RunPlan.OTHER), Length.Attacks(40), secondAttacks = 40, walls = true)
        assertFalse(HomeCards.wallsOn(off, both, accounts, off.id))
        assertFalse(HomeCards.wallsOn(off, farm40Walls.copy(accountIds = listOf(blood.id)), accounts, off.id))
        assertFalse(HomeCards.wallsOn(off, null, accounts, off.id))
        assertTrue(HomeCards.wallsOn(sweat, null, listOf(sweat), sweat.id))

        // With the planned run buying walls, full storage and no builder offers Walls only, not "walls are off".
        val card = HomeCards.attention(off, profile, emptyMap(), full, noBuilder, connected = true, busy = false,
            wallsOn = HomeCards.wallsOn(off, farm40Walls, accounts, off.id))!!
        assertEquals(HomeCards.Kind.FULL_NO_BUILDER, card.kind)
    }

    @Test
    fun fullStorageWithNoFreeBuilderOffersWallsOnly() {
        val card = attention(storage = full, builders = noBuilder)!!
        assertEquals(HomeCards.Kind.FULL_NO_BUILDER, card.kind)
        assertEquals(Tone.WARN, card.tone)
        assertEquals("Storage is full and walls cannot spend", card.title)
        assertTrue(card.text, card.text.contains("0 of 6"))
        assertEquals("Walls only", card.button)
        assertEquals("walls", card.action)
        assertTrue(card.enabled)
        // The same gate as the Walls only tool: no service or no wall calibration turns it off.
        assertFalse(attention(storage = full, builders = noBuilder, connected = false)!!.enabled)
        val noWallRois = profile.copy(homeResourceRois = emptyMap())
        assertFalse(attention(profile = noWallRois, storage = full, builders = noBuilder)!!.enabled)
    }

    @Test
    fun fullStorageWithAFreeBuilderOrNoReadingNeedsNothing() {
        assertNull(attention(storage = full, builders = oneBuilder))
        assertNull(attention(storage = full, builders = null))
        // 97% is not full; 98.3% is.
        assertNull(attention(storage = StorageReading(now, mapOf(Resource.GOLD to 28_130_000L)), builders = noBuilder))
        assertEquals(HomeCards.Kind.FULL_NO_BUILDER,
            attention(storage = StorageReading(now, mapOf(Resource.GOLD to 28_500_000L)), builders = noBuilder)!!.kind)
    }

    @Test
    fun pendingWallPurchaseAsksForACheck() {
        val marker = PendingWallPurchase(sweat.id, Resource.GOLD, 3, 1_500_000L, 27_500_000L, 29_000_000L, now - hour)
        val card = attention(markers = mapOf(sweat.id to marker), storage = full, builders = noBuilder)!!
        assertEquals(HomeCards.Kind.WALL_PENDING, card.kind)
        assertEquals(Tone.BAD, card.tone)
        assertEquals("Wall purchase not confirmed", card.title)
        assertTrue(card.text, card.text.startsWith("3 walls for "))
        assertTrue(card.text, card.text.contains(" gold on Sweat"))
        assertEquals("I checked the village", card.button)
        assertEquals("acknowledge_wall:${sweat.id}", card.action)

        // A marker that would not parse still blocks spending and still gets the card.
        val unreadable = attention(markers = mapOf(sweat.id to null))!!
        assertEquals(HomeCards.Kind.WALL_PENDING, unreadable.kind)
        assertTrue(unreadable.text, unreadable.text.startsWith("A wall purchase on Sweat was never confirmed."))

        // Another account's marker is not this account's card.
        assertNull(attention(markers = mapOf("other" to marker)))
    }

    @Test
    fun unknownPurchaseOutcomeIsItsOwnCard() {
        val account = withResult(AccountLastResult(timestamp = now - hour, purchaseOutcomeUnknown = true,
            unknownPurchaseCurrency = "GOLD", unknownPurchaseCost = 900_000L, unknownPurchaseWalls = 2))
        val marker = PendingWallPurchase(sweat.id, Resource.GOLD, 2, 900_000L, 0L, 900_000L, now - hour)
        val card = attention(account = account, markers = mapOf(sweat.id to marker))!!
        assertEquals(HomeCards.Kind.UNKNOWN_PURCHASE, card.kind)
        assertEquals(Tone.BAD, card.tone)
        assertEquals("Wall purchase outcome unknown", card.title)
        assertTrue(card.text, card.text.startsWith("A purchase of 2 walls for "))
        assertTrue(card.text, card.text.contains("gold on Sweat may or may not have gone through"))
        assertEquals("acknowledge_wall:${sweat.id}", card.action)
        assertFalse(card.text, card.text.contains("_"))
    }

    @Test
    fun deviceGuardEndingTheLastRunIsShown() {
        val account = withResult(AccountLastResult(timestamp = now - hour, completionReason = CompletionReason.DEVICE_GUARD.key))
        val card = attention(account = account)!!
        assertEquals(HomeCards.Kind.DEVICE_GUARD, card.kind)
        assertEquals(Tone.WARN, card.tone)
        assertEquals("Battery or heat guard", card.title)
    }

    @Test
    fun missingCalibrationOpensCalibration() {
        val card = attention(profile = null, storage = full, builders = noBuilder)!!
        assertEquals(HomeCards.Kind.NOT_CALIBRATED, card.kind)
        assertEquals(Tone.BAD, card.tone)
        assertEquals("Sweat is not calibrated", card.title)
        assertEquals("calibrate", card.action)
        assertTrue(card.enabled)
        assertFalse(attention(profile = null, busy = true)!!.enabled)
    }

    @Test
    fun aStopThatNeedsAPersonComesBeforeFullStorage() {
        val taken = withResult(AccountLastResult(timestamp = now - hour, stopReason = "Village was opened on another device"))
        val card = attention(account = taken, storage = full, builders = noBuilder)!!
        assertEquals(HomeCards.Kind.NEEDS_YOU, card.kind)
        assertEquals("Village opened on another device", card.title)
        assertTrue(card.text, card.text.startsWith("The last run on Sweat stopped: "))
        // The user's own Stop is not a problem.
        val own = withResult(AccountLastResult(timestamp = now - hour, stopReason = "Overlay Stop clicked"))
        assertNull(attention(account = own))
    }

    @Test
    fun firstMatchWinsInSpecOrder() {
        val marker = PendingWallPurchase(sweat.id, Resource.GOLD, 3, 1_500_000L, 27_500_000L, 29_000_000L, now)
        val guard = withResult(AccountLastResult(timestamp = now, completionReason = CompletionReason.DEVICE_GUARD.key))
        assertEquals(HomeCards.Kind.WALL_PENDING,
            attention(account = guard, profile = null, markers = mapOf(sweat.id to marker), storage = full, builders = noBuilder)!!.kind)
        assertEquals(HomeCards.Kind.NOT_CALIBRATED, attention(account = guard, profile = null, storage = full)!!.kind)
        assertEquals(HomeCards.Kind.FULL_NO_BUILDER, attention(account = guard, storage = full, builders = noBuilder)!!.kind)
        assertEquals(HomeCards.Kind.DEVICE_GUARD, attention(account = guard, storage = half)!!.kind)
    }

    @Test
    fun buildersTileShowsAgeAndGoesAmberWhenStale() {
        val fresh = HomeCards.buildersTile(BuilderReading(0, 6, now - 10 * 60_000L), now)
        assertEquals("0/6", fresh.value)
        assertEquals("builders free", fresh.label)
        assertEquals("10 min ago", fresh.chip)
        assertEquals(Tone.NEUTRAL, fresh.chipTone)
        assertEquals(Tone.WARN, HomeCards.buildersTile(BuilderReading(2, 6, now - 7 * hour), now).chipTone)
        val never = HomeCards.buildersTile(null, now)
        assertEquals("-", never.value)
        assertEquals("builders, not read yet", never.label)
    }

    @Test
    fun lootAndRaidTilesSayWhenNothingWasRead() {
        assertEquals(HomeCards.TileText("-", "no battles read"), HomeCards.lootTile(emptyList(), sweat.id, now))
        assertEquals(HomeCards.TileText("-", "no raid read yet"), HomeCards.lastRaidTile(emptyList(), sweat.id, now))
    }

    @Test
    fun battlesStillBeingReadAreNotNoBattles() {
        assertEquals(HomeCards.TileText("-", "reading battles"), HomeCards.lootTile(null, sweat.id, now))
        assertEquals(HomeCards.TileText("-", "reading battles"), HomeCards.lastRaidTile(null, sweat.id, now))
    }

    /** A log line at [at] ms, naming [account] when it is given, as AppLog writes them. */
    private fun line(at: Long, event: String, account: AccountRecord? = null): String =
        JSONObject().put("time", Instant.ofEpochMilli(at).toString()).put("event", event).apply {
            if (account != null) put("accountId", account.id).put("accountName", account.villageName)
        }.toString()

    /** The snapshot AppLog keeps for [lines], replayed in full as at start-up. */
    private fun replayed(lines: List<String>): SessionSnapshot =
        SessionSummary().apply { lines.forEach { accept(JSONObject(it)) } }.snapshot

    @Test
    fun anotherAccountsLastRunAndFrameSayWhoseTheyAre() {
        // accept/81: Blood selected, three minutes after Sweat's one-attack run. now is 08:53:20 UTC.
        val end = now - 3 * 60_000L
        val start = end - 2 * 60_000L
        val run = replayed(listOf(
            line(start - hour, "session_started", blood),
            line(start - hour + 60_000L, "session_finished", blood),
            line(start, "session_started", sweat),
            line(end, "session_finished", sweat)
        )).copy(completed = 1, limit = 1)
        val last = HomeCards.lastRunAccount(run, emptyList(), listOf(blood, sweat))
        assertEquals(sweat, last)
        assertEquals("Last run on Sweat 08:48-08:50 · 1 of 1 attacks", HomeCards.lastRunLine(run, utc, last?.villageName))
        // Collecting after the run is still the run's session, so its frame is Sweat's.
        assertEquals("Sweat, last seen 08:51", HomeCards.lastSeen(end + 65_000L, sweat.id, listOf(blood, sweat), blood.id, utc))
        // Sweat's own Home says nothing more than before.
        assertEquals("Last run 08:48-08:50 · 1 of 1 attacks", HomeCards.lastRunLine(run, utc))
        assertEquals("Last seen 08:50", HomeCards.lastSeen(end - 1_000L, sweat.id, listOf(blood, sweat), sweat.id, utc))
    }

    @Test
    fun theLastRunKeepsItsAccountHoweverManyLinesFollowIt() {
        // day-log-2157: Blood's walls run 07:05-07:06 was stopped, Sweat was switched to and selected,
        // and from 07:53 none of the run's lines was among Home's last 150, so Home named nobody.
        val start = now - 2 * hour
        val end = start + 72_000L
        val log = listOf(
            line(start - 7_000L, "account_switch_start", blood),
            line(start, "session_started", blood),
            line(start + 67_000L, "wall_upgraded", blood),
            line(end, "run_stopped", blood),
            line(end + 79_000L, "notification_posted"),
            line(end + 4 * 60_000L, "account_switch_start"),
            line(end + 4 * 60_000L + 31_000L, "account_switch_verified")
        ) + (1..160).map { line(end + 5 * 60_000L + it * 10_000L, "camera_reference_retired", sweat) }
        val run = replayed(log)
        val recent = log.takeLast(150)
        val last = HomeCards.lastRunAccount(run, recent, listOf(blood, sweat))
        assertEquals(blood, last)
        assertTrue(HomeCards.lastRunLine(run, utc, last?.takeIf { it.id != sweat.id }?.villageName).startsWith("Last run on Blood 06:53-06:54"))
        // The fallback alone, all a log from before sessions named their account has, finds no line to go on.
        assertNull(HomeCards.lastRunAccount(run.copy(accountId = null), recent, listOf(blood, sweat)))
        assertEquals(blood, HomeCards.lastRunAccount(run.copy(accountId = null), log, listOf(blood, sweat)))
    }

    @Test
    fun aFrameOrRunNobodyCanBeSureOfIsNotPutUnderAName() {
        val end = now - 3 * 60_000L
        val start = end - 2 * 60_000L
        val accounts = listOf(blood, sweat)
        // A frame captured outside a session, such as during a switch or an inspect from Setup,
        // names no account, and could be either village.
        assertNull(HomeCards.lastSeen(end + 2 * 60_000L, null, accounts, blood.id, utc))
        assertNull(HomeCards.lastSeen(end + 2 * 60_000L, null, accounts, null, utc))
        // Nor is a frame put under an account that is no longer set up.
        assertNull(HomeCards.lastSeen(end, sweat.id, listOf(blood), blood.id, utc))
        // A log from before sessions named their account: the run's first line naming one decides.
        val old = SessionSnapshot(startedAt = start, endedAt = end, completed = 1, limit = 1)
        val ran = listOf(line(start, "session_started", sweat), line(end, "session_finished", sweat))
        assertEquals(sweat, HomeCards.lastRunAccount(old, ran, accounts))
        // No line of that run is left, or none names a known account: the run is not put under a name.
        assertNull(HomeCards.lastRunAccount(old, listOf(line(end + 1_000L, "notification_posted")), accounts))
        assertNull(HomeCards.lastRunAccount(old, ran, listOf(blood)))
        assertNull(HomeCards.lastRunAccount(SessionSnapshot(), ran, accounts))
        // A session that named an account no longer set up is not put under the lines' name instead.
        assertNull(HomeCards.lastRunAccount(old.copy(accountId = "0130d53a-41a1-38cf-9069-f88b32445475"), ran, accounts))
    }

    @Test
    fun aRunStoppedDuringTheNextAccountsSwitchIsTheFirstAccounts() {
        // A combined run stopped while switching to Sweat: the log's run is Blood's, from its start to
        // the stop, and the switch's frames after the handoff name nobody.
        val start = now - hour
        val handoff = now - 10 * 60_000L
        val events = listOf(
            line(start, "session_started", blood),
            line(handoff - 60_000L, "session_finished", blood),
            line(handoff, "account_handoff", blood),
            line(handoff + 5_000L, "account_switch_start", sweat),
            line(now - 9 * 60_000L, "run_stopped", sweat)
        )
        val run = replayed(events)
        assertEquals(blood, HomeCards.lastRunAccount(run, events, listOf(blood, sweat)))
        assertEquals(blood, HomeCards.lastRunAccount(run.copy(accountId = null), events, listOf(blood, sweat)))
        assertNull(HomeCards.lastSeen(handoff + 30_000L, null, listOf(blood, sweat), sweat.id, utc))
        assertEquals("Blood, last seen 08:41", HomeCards.lastSeen(handoff - 2 * 60_000L, blood.id, listOf(blood, sweat), sweat.id, utc))
    }

    @Test
    fun headlineIsTheStateWordOnly() {
        val idle = SessionSnapshot()
        assertEquals("Idle", HomeCards.headline(idle, busy = false, paused = false))
        assertEquals("Starting", HomeCards.headline(idle, busy = true, paused = false))
        val running = SessionSnapshot(startedAt = now - hour)
        assertEquals("Running", HomeCards.headline(running, busy = true, paused = false))
        assertEquals("Paused", HomeCards.headline(running, busy = true, paused = true))
        assertEquals("Storage full", HomeCards.headline(running.copy(endedAt = now, completionReason = "storage_full"), false, false))
        assertEquals("Stopped", HomeCards.headline(running.copy(endedAt = now, stopReason = "Overlay Stop clicked"), false, false))
    }

    @Test
    fun startLabelNamesThePlanAndAccount() {
        val accounts = listOf(sweat)
        val farm40 = RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(40), walls = true, name = "Farm 40 + walls")
        assertEquals("Start Farm 40 + walls on Sweat", HomeCards.startLabel(farm40, accounts, sweat.id))
        assertEquals("Start 3 attacks on Sweat", HomeCards.startLabel(farm40.copy(length = Length.Attacks(3), walls = false, name = null),
            accounts, sweat.id))
        assertEquals("Start 2 h + walls on Sweat", HomeCards.startLabel(farm40.copy(length = Length.Minutes(120), name = null),
            accounts, sweat.id))
        assertEquals("Choose a run", HomeCards.startLabel(null, accounts, sweat.id))
    }
}
