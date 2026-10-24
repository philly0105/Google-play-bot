package local.clashfarmer.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Point
import local.clashfarmer.core.Resource
import local.clashfarmer.insight.CalibrationHealth
import local.clashfarmer.insight.Finding
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.screens.CheckLine
import local.clashfarmer.ui.screens.SetupChecklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setup's checklist rules: each severity on the health rows, the count, the dot and the Fix button. */
class SetupChecklistTest {
    private val now = 1_790_700_000_000L
    private val sweat = AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU")
    private val blood = AccountRecord(BLOOD, "Blood", "VastMaNiSh", "#Y2G29YJQ8")
    private val anchors = mapOf(
        "valkyries" to Point(302, 650), "king" to Point(399, 650), "queen" to Point(496, 650),
        "warden" to Point(593, 650), "champion" to Point(690, 650), "rages" to Point(787, 650)
    )
    private val profile = PhoneProfile.createDefault().copy(troopAnchors = anchors, isVerified = true)
    private val builders = BuilderReading(free = 2, total = 6, atMs = now - 2 * 3_600_000L)

    private fun lines(account: AccountRecord, profile: PhoneProfile?, findings: List<Finding>, builders: BuilderReading? = this.builders) =
        SetupChecklist.account(account, profile, findings, profileError = null, builders = builders, nowMs = now)

    private fun List<CheckLine>.row(area: String) = single { it.key.endsWith(":$area") }

    @Test
    fun healthRowsRenderEachSeverity() {
        // OK: a clean, verified profile. Walls are off, so that row is Off rather than a problem.
        val ok = lines(sweat, profile, emptyList())
        assertEquals(listOf("calibration", "tray", "walls", "builders"), ok.map { it.key.substringAfter(':') })
        ok.row("calibration").let {
            assertEquals(Tone.OK, it.tone)
            assertEquals("OK", it.chip)
            assertEquals("Verified on this phone", it.detail)
            assertNull(it.fix)
        }
        assertEquals("6 cards, spacing 97 px", ok.row("tray").detail)
        ok.row("walls").let {
            assertEquals(Tone.IDLE, it.tone)
            assertEquals("Off", it.chip)
        }

        // WARN: Blood's tray copied from Sweat's, as CalibrationHealth finds it. Amber "Check", not an error.
        val health = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile, BLOOD to profile))
        val tray = lines(blood, profile, health.getValue(BLOOD)).row("tray")
        assertEquals(Tone.WARN, tray.tone)
        assertEquals("Check", tray.chip)
        assertEquals("Same taps as Sweat's (6 of 6)", tray.detail)
        assertEquals(CheckLine.FixKind.CALIBRATION, tray.fix?.kind)
        assertEquals(BLOOD, tray.fix?.accountId)
        assertEquals(Finding.Kind.COPIED_ANCHORS, tray.findings.single().kind)

        // BAD: no calibration at all. No profile, so no tray row.
        val bad = lines(blood, null, CalibrationHealth.check(listOf(blood), emptyMap()).getValue(BLOOD))
        bad.row("calibration").let {
            assertEquals(Tone.BAD, it.tone)
            assertEquals("Needs you", it.chip)
            assertEquals("Not calibrated", it.detail)
            assertEquals("Open calibration", it.fix?.label)
        }
        assertTrue(bad.none { it.key.endsWith(":tray") })
    }

    @Test
    fun notVerifiedIsAmberWithoutAFix() {
        val finding = Finding(Finding.Kind.NOT_VERIFIED, Finding.Area.CALIBRATION, local.clashfarmer.insight.Tone.WARN,
            "Needs one supervised attack", "Run one supervised attack to verify it.")
        val line = lines(sweat, profile.copy(isVerified = false), listOf(finding)).row("calibration")
        assertEquals(Tone.WARN, line.tone)
        assertEquals("Needs one supervised attack", line.detail)
        assertNull(line.fix)
    }

    @Test
    fun bloodsTrayRowGoesGreenOnceItsCardsAreTappedAgain() {
        // D4: Blood holds Sweat's taps, so its tray row is amber.
        val copied = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile, BLOOD to profile))
        assertEquals(Tone.WARN, lines(blood, profile, copied.getValue(BLOOD)).row("tray").tone)
        // Calibrate moves each tap and its card area onto Blood's own card, from the King on one card
        // further right, past the siege machine that has no target of its own.
        val recalibrated = mapOf("king" to 496, "queen" to 593, "warden" to 690, "champion" to 787, "rages" to 884)
            .entries.fold(profile) { p, (name, x) -> p.withTroopCard(name, Point(x, 650)) }
        val health = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile, BLOOD to recalibrated))
        lines(blood, recalibrated, health.getValue(BLOOD)).row("tray").let {
            assertEquals(Tone.OK, it.tone)
            assertEquals("6 cards, spacing 97 px", it.detail)
            assertNull(it.fix)
        }
    }

    @Test
    fun wallAndGoalRowsOfferTheRightFix() {
        val walls = sweat.copy(settings = AccountSettings(wallsEnabled = true,
            capacities = mapOf(Resource.GOLD to 25_500_000L, Resource.ELIXIR to 26_000_000L)))
        assertEquals("Capacities set, buys at 90%", lines(walls, profile, emptyList()).row("walls").detail)

        val setup = Finding(Finding.Kind.WALL_SETUP, Finding.Area.WALLS, local.clashfarmer.insight.Tone.WARN,
            "Wall setup needed", "Wall spending needs the gold capacity.")
        lines(sweat, profile, listOf(setup)).row("walls").let {
            assertEquals(Tone.WARN, it.tone)
            assertEquals(CheckLine.FixKind.EDIT_SETTINGS, it.fix?.kind)
        }
        val incomplete = Finding(Finding.Kind.WALLS_INCOMPLETE, Finding.Area.WALLS, local.clashfarmer.insight.Tone.BAD,
            "Wall calibration incomplete", "Wall workflow configuration incomplete.")
        lines(walls, profile, listOf(setup, incomplete)).row("walls").let {
            assertEquals(Tone.BAD, it.tone)
            assertEquals("Wall calibration incomplete and 1 more", it.detail)
            assertEquals(CheckLine.FixKind.CALIBRATION, it.fix?.kind)
        }

        val goal = Finding(Finding.Kind.GOAL_UNREACHABLE, Finding.Area.GOAL, local.clashfarmer.insight.Tone.WARN,
            "Goal or rule cannot work", "Goal for Dark elixir has no village balance region.")
        lines(sweat, profile, listOf(goal)).row("goal").let {
            assertEquals(Tone.WARN, it.tone)
            assertEquals("Goal and rules", it.title)
            assertEquals(CheckLine.FixKind.EDIT_SETTINGS, it.fix?.kind)
        }
    }

    @Test
    fun aProfileThatWillNotLoadIsRed() {
        val line = SetupChecklist.account(sweat, null, emptyList(), "Bad JSON at line 3", null, now).row("calibration")
        assertEquals(Tone.BAD, line.tone)
        assertEquals("Could not be loaded: Bad JSON at line 3", line.detail)
        assertEquals("Bad JSON at line 3", line.findings.first().detail)
    }

    @Test
    fun buildersAreInformationOnly() {
        val read = lines(sweat, profile, emptyList()).row("builders")
        assertEquals("2 of 6 free, read 2 h ago", read.detail)
        assertEquals("2 free", read.chip)
        assertFalse(read.counts)
        val busy = lines(sweat, profile, emptyList(), BuilderReading(0, 6, now - 60_000L)).row("builders")
        assertEquals(Tone.WARN, busy.tone)
        assertEquals("Busy", busy.chip)
        assertFalse(busy.counts)
        assertEquals("Not read", lines(sweat, profile, emptyList(), null).row("builders").chip)
        // Not counted, so busy builders alone raise no dot.
        assertNull(SetupChecklist.badge(lines(sweat, profile, emptyList(), BuilderReading(0, 6, now))))
    }

    @Test
    fun readyCountDotAndFixOrder() {
        val phone = listOf(
            SetupChecklist.accessibility(true),
            SetupChecklist.notifications(true),
            SetupChecklist.battery(DeviceHealth(82, true, 34.1)),
            SetupChecklist.remote(true, "100.64.0.7")
        )
        assertEquals("82%, charging, 34.1 C", phone[2].detail)
        val health = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile, BLOOD to profile))
        val setup = Finding(Finding.Kind.WALL_SETUP, Finding.Area.WALLS, local.clashfarmer.insight.Tone.WARN,
            "Wall setup needed", "Wall spending needs the gold capacity.")
        // Blood: its copied tray and a wall setup problem, both amber. (A default profile's attack
        // finding is left out so the red calibration row does not take the Fix button.)
        val bloodFindings = health.getValue(BLOOD).filter { it.area == Finding.Area.TRAY } + setup
        val all = phone + lines(sweat, profile, emptyList()) + lines(blood, profile, bloodFindings)

        // Counted: accessibility, notifications, battery, then calibration, tray and walls per account.
        assertEquals(7 to 9, SetupChecklist.ready(all))
        assertEquals(TabBadge(0, Tone.WARN), SetupChecklist.badge(all))
        // Two amber lines on Blood: the first on screen, its tray, is the primary fix.
        val primary = SetupChecklist.primaryFix(all)!!
        assertEquals("$BLOOD:tray", primary.key)
        assertEquals("Fix: Open calibration (Blood)", SetupChecklist.fixLabel(primary))

        // Red beats amber wherever it sits.
        val off = listOf(SetupChecklist.accessibility(false)) + all.drop(1)
        assertEquals("accessibility", SetupChecklist.primaryFix(off)?.key)
        assertEquals("Fix: Open accessibility settings", SetupChecklist.fixLabel(SetupChecklist.primaryFix(off)!!))

        // All clear: no dot, no fix.
        val clear = phone + lines(sweat, profile, emptyList())
        assertNull(SetupChecklist.badge(clear))
        assertNull(SetupChecklist.primaryFix(clear))
        assertEquals(6 to 6, SetupChecklist.ready(clear))
    }

    @Test
    fun aCardOffersCalibrationOnce() {
        // Clean rows offer no fix, so the card's own "Open calibration" shows.
        assertFalse(SetupChecklist.rowOffersCalibration(lines(sweat, profile, emptyList())))
        // A red calibration row or an amber copied tray offers it, so the card's button hides.
        val notCalibrated = lines(sweat, null, CalibrationHealth.check(listOf(sweat), emptyMap()).getValue(SWEAT))
        assertTrue(SetupChecklist.rowOffersCalibration(notCalibrated))
        val health = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile, BLOOD to profile))
        assertTrue(SetupChecklist.rowOffersCalibration(lines(blood, profile, health.getValue(BLOOD).filter { it.area == Finding.Area.TRAY })))
        // A wall setup problem offers Edit settings, so the card keeps its button.
        val setup = Finding(Finding.Kind.WALL_SETUP, Finding.Area.WALLS, local.clashfarmer.insight.Tone.WARN,
            "Wall setup needed", "Wall spending needs the gold capacity.")
        assertFalse(SetupChecklist.rowOffersCalibration(lines(sweat, profile, listOf(setup))))

        // Each card's fix names its own account, whichever one Home has picked: none is passed in.
        val primary = SetupChecklist.primaryFix(notCalibrated + lines(blood, profile, emptyList()))!!
        assertEquals("Fix: Open calibration (Sweat)", SetupChecklist.fixLabel(primary))
        assertEquals(SWEAT, primary.fix?.accountId)
    }

    @Test
    fun phoneLines() {
        SetupChecklist.notifications(false).let {
            assertEquals(Tone.WARN, it.tone)
            assertEquals(CheckLine.FixKind.NOTIFICATIONS, it.fix?.kind)
        }
        assertFalse(SetupChecklist.notifications(null).counts)
        SetupChecklist.battery(DeviceHealth(50, false, 46.0)).let {
            assertEquals(Tone.BAD, it.tone)
            assertEquals("Needs you", it.chip)
            assertTrue(it.detail, it.detail.contains("46.0 C"))
            assertNull(it.fix)
        }
        SetupChecklist.battery(DeviceHealth(10, false, 30.0)).let { assertEquals(Tone.BAD, it.tone) }
        assertFalse(SetupChecklist.battery(null).counts)
    }

    @Test
    fun remoteLineShowsAddressAndPortOnly() {
        val on = SetupChecklist.remote(true, "100.64.0.7")
        assertTrue(on.detail, on.detail.startsWith("100.64.0.7:8765 "))
        assertFalse(on.detail, on.detail.contains("key="))
        assertFalse(on.detail, on.detail.contains("http"))
        assertFalse(on.counts)
        assertEquals("No Tailscale", SetupChecklist.remote(true, null).chip)
        assertEquals(Tone.IDLE, SetupChecklist.remote(false, null).tone)
    }
}
