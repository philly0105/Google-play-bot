package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.Point
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.WallCursor
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.insight.CalibrationHealth
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.recognition.WallConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The standard positions a new account starts calibration from, and Setup's pointer to them. */
class StarterProfileTest {

    @Test
    fun `the standard positions hold the game's points and nothing of the owner's village`() {
        val starter = StarterProfile.load()
        assertEquals(1740 to 978, starter.resolutionWidth to starter.resolutionHeight)
        assertEquals(Viewport(0, 0, 1740, 978), starter.viewport)
        assertFalse(starter.isVerified)
        assertTrue(starter.collectionTargets.isEmpty())
        assertTrue(starter.wallPoints.isEmpty())
        assertTrue(starter.deploymentPoints.isEmpty())
        // The grass the bot clicks to close windows is the village's own, so it is left to set.
        assertNull(starter.stateAnchors["village_neutral"])
        // Everything an attack needs is there, so a friend who only checks the cards can attack.
        starter.validateForAttack()
        CALIBRATION_TARGETS.filter { it.required }.forEach { assertTrue(it.name, isSet(starter, it.name)) }
    }

    @Test
    fun `at the same size the standard positions keep what belongs to the account's village`() {
        val starter = StarterProfile.load()
        val collectors = listOf(CollectionTarget("collector 1", Point(800, 400)))
        val current = PhoneProfile.createDefault(1740, 978).copy(
            stateAnchors = mapOf("village_neutral" to Point(900, 500), "attack" to Point(1, 1)),
            collectionTargets = collectors,
            wallCursor = WallCursor(2, 3),
            isVerified = true
        )
        val next = StarterProfile.onto(starter, current, SurfaceSize(1740, 978))
        assertFalse(next.isVerified)
        assertEquals(collectors, next.collectionTargets)
        assertEquals(WallCursor(2, 3), next.wallCursor)
        assertEquals(Point(900, 500), next.stateAnchors["village_neutral"])
        // The game's own buttons come from the standard positions.
        assertEquals(starter.stateAnchors["attack"], next.stateAnchors["attack"])
        assertEquals(starter.troopAnchors, next.troopAnchors)
        assertEquals(starter.perimeterSegments, next.perimeterSegments)
    }

    @Test
    fun `at another size the account's own points move with the standard positions, not vanish`() {
        val starter = StarterProfile.load()
        val walls = WallConfig(bulkButton = Point(870, 800), removeButton = Point(700, 800))
        // A saved 1740x978 calibration under a frame captured at 1920x1080.
        val current = PhoneProfile.createDefault(1740, 978).copy(
            stateAnchors = mapOf("village_neutral" to Point(900, 500)),
            wallConfig = walls,
            wallCursor = WallCursor(2, 3),
            collectionTargets = listOf(CollectionTarget("collector 1", Point(800, 400))),
            isVerified = true
        )
        val size = SurfaceSize(1920, 1080)
        val next = StarterProfile.onto(starter, current, size)
        val own = PcProfiles.derive(current, size, emptySet())
        assertEquals(1920 to 1080, next.resolutionWidth to next.resolutionHeight)
        assertFalse(next.isVerified)
        assertEquals(own.stateAnchors["village_neutral"], next.stateAnchors["village_neutral"])
        assertNotEquals(Point(900, 500), next.stateAnchors["village_neutral"])
        assertNotNull(next.wallConfig)
        assertEquals(own.wallConfig, next.wallConfig)
        assertEquals(WallCursor(2, 3), next.wallCursor)
        // Collectors belong to the camera reference at the old size, so they go, as on a capture.
        assertTrue(next.collectionTargets.isEmpty())
        assertEquals(PcProfiles.derive(starter, size, emptySet()).troopAnchors, next.troopAnchors)
        next.validate()
    }

    @Test
    fun `on a 16 by 10 game area the standard positions move as a run moves them`() {
        val starter = StarterProfile.load()
        val next = StarterProfile.onto(starter, PhoneProfile.createDefault(), SurfaceSize(1860, 1162))
        assertEquals(1860 to 1162, next.resolutionWidth to next.resolutionHeight)
        assertEquals(Viewport(0, 0, 1860, 1162), next.viewport)
        assertFalse(next.isVerified)
        assertNotEquals(starter.stateAnchors["attack"], next.stateAnchors["attack"])
        next.validateForAttack()
    }

    @Test(expected = StopException::class)
    fun `a game area the bot can't work at is refused`() {
        StarterProfile.onto(StarterProfile.load(), PhoneProfile.createDefault(), SurfaceSize(1000, 1000))
    }

    @Test
    fun `the size is the frame's, else the edited profile's when workable, else the standard one`() {
        val starter = StarterProfile.load()
        val tall = PhoneProfile.createDefault(1860, 1162)
        assertEquals(SurfaceSize(1920, 1080), StarterProfile.sizeFor(SurfaceSize(1920, 1080), tall, starter))
        assertEquals(SurfaceSize(1860, 1162), StarterProfile.sizeFor(null, tall, starter))
        // A new account before its first capture is at the phone's 1600x720, which no PC has.
        assertEquals(SurfaceSize(1740, 978), StarterProfile.sizeFor(null, PhoneProfile.createDefault(), starter))
    }

    @Test
    fun `Setup points an account with no calibration at the standard positions`() {
        val friend = AccountRecord("friend", "Friend", "", "")
        val findings = CalibrationHealth.check(listOf(friend), mapOf("friend" to null)).getValue("friend")
        val lines = local.clashfarmer.ui.screens.SetupChecklist.account(friend, null, findings, null, null, 0L)
        val calibration = lines.single { it.key == "friend:calibration" }
        assertEquals(StarterProfile.SETUP_HINT, StarterProfile.setupLine(calibration).detail)
        // Every other row, and a calibrated account's row, is left as it was.
        lines.filter { it !== calibration }.forEach { assertSame(it, StarterProfile.setupLine(it)) }
        val loadFailed = local.clashfarmer.ui.screens.SetupChecklist.account(friend, null, emptyList(), "Bad file", null, 0L)
            .single { it.key == "friend:calibration" }
        assertSame(loadFailed, StarterProfile.setupLine(loadFailed))
    }
}
