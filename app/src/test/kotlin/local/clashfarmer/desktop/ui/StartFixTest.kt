package local.clashfarmer.desktop.ui

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.Length
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.ui.screens.gameChip
import local.clashfarmer.desktop.ui.screens.shownPresets
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.ui.Tab
import local.clashfarmer.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link under a Start that is off, the presets a single account sees, and Home's game chip. */
class StartFixTest {

    private val main = AccountRecord("11111111-2222-3333-4444-555555555555", "Main", "main", "#AAA", AccountSettings())
    private val second = AccountRecord("66666666-7777-8888-9999-000000000000", "Second", "second", "#BBB", AccountSettings())
    private val ready = PhoneProfile(
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
    private val farm = RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(40), walls = false)

    private fun fix(
        profiles: Map<String, PhoneProfile?>,
        plan: RunPlan = farm,
        reason: String? = "something",
        connected: Boolean = true,
        busy: Boolean = false
    ) = StartFix.of(plan, listOf(main, second), profiles, main.id, connected, busy, reason)

    @Test
    fun `an account with no calibration links to calibrating it`() {
        val fix = fix(emptyMap())
        assertTrue(fix is StartFix.Calibrate)
        assertEquals(main.id, (fix as StartFix.Calibrate).accountId)
        assertEquals("Calibrate Main >", fix.label)
    }

    @Test
    fun `an unverified calibration links to the supervised attack`() {
        val fix = fix(mapOf(main.id to ready.copy(isVerified = false)))
        assertTrue(fix is StartFix.SupervisedAttack)
        assertEquals("Run a supervised attack >", fix?.label)
    }

    @Test
    fun `walls on with no wall calibration link to calibrating, after the supervised attack as the reasons go`() {
        val noWalls = ready.copy(homeResourceRois = mapOf(Resource.GOLD to Region(1300, 20, 1500, 60)))
        assertTrue(fix(mapOf(main.id to noWalls), farm.copy(walls = true)) is StartFix.Calibrate)
        assertTrue(fix(mapOf(main.id to noWalls), farm) == null)
        assertTrue(fix(mapOf(main.id to noWalls.copy(isVerified = false)), farm.copy(walls = true)) is StartFix.SupervisedAttack)
    }

    @Test
    fun `a reason that names Setup opens Setup, and a choice made on Run has no link`() {
        val profiles = mapOf(main.id to ready)
        val open = fix(profiles, reason = "Storage full needs the storage sizes in Setup")
        assertTrue(open is StartFix.Open)
        assertEquals(Tab.SETUP, (open as StartFix.Open).tab)
        assertNull(fix(profiles, reason = "Storage full needs walls off; walls spend the storage down"))
    }

    @Test
    fun `no link when Start is on, a run is going or Clash Farmer is not up`() {
        assertNull(fix(emptyMap(), reason = null))
        assertNull(fix(emptyMap(), busy = true))
        assertNull(fix(emptyMap(), connected = false))
    }

    @Test
    fun `the phone's not-connected reason reads as the PC's`() {
        assertEquals(NOT_RUNNING, StartFix.plainReason("Turn on the accessibility service in Setup"))
        assertEquals("Main is not calibrated", StartFix.plainReason("Main is not calibrated"))
        assertNull(StartFix.plainReason(null))
    }

    @Test
    fun `one account sees no Both preset`() {
        val both = RunPlan(listOf(main.id, second.id), Length.Attacks(20), walls = false, name = "Both accounts")
        val one = farm.copy(name = "Farm")
        assertEquals(listOf(one), shownPresets(listOf(one, both), accountCount = 1))
        assertEquals(listOf(one), shownPresets(listOf(one, both), accountCount = 0))
        assertEquals(listOf(one, both), shownPresets(listOf(one, both), accountCount = 2))
    }

    @Test
    fun `Home's chip says whether Clash is open, in Setup's tone`() {
        val window = ClashWindow(1, 2, "Clash of Clans", false, 1600, 900, foreground = false, monitorDpi = 96)
        assertEquals("Looking for Clash" to Tone.NEUTRAL, gameChip(WindowStatus()))
        assertEquals("Clash not open" to Tone.WARN, gameChip(WindowStatus(checked = true)))
        assertEquals("Clash closed" to Tone.BAD, gameChip(WindowStatus(checked = true, window = window.copy(visible = false))))
        assertEquals("Clash minimized" to Tone.OK, gameChip(WindowStatus(checked = true, window = window.copy(iconic = true))))
        assertEquals("Clash open" to Tone.OK, gameChip(WindowStatus(checked = true, window = window)))
        assertEquals("Clash not found" to Tone.BAD, gameChip(WindowStatus(checked = true, error = "sidecar down")))
    }
}
