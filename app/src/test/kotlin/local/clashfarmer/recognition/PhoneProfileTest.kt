package local.clashfarmer.recognition

import local.clashfarmer.core.CollectionPolicy
import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.Step
import local.clashfarmer.core.WallCursor
import local.clashfarmer.core.WallRows
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneProfileTest {

    @Test
    fun testDefaultProfileIsNotVerified() {
        val profile = PhoneProfile.createDefault(1600, 720)
        assertFalse("New profile must never be marked verified", profile.isVerified)
        assertEquals(1600, profile.resolutionWidth)
        assertEquals(720, profile.resolutionHeight)
        assertEquals(0, profile.viewport.left)
        assertEquals(0, profile.viewport.top)
        assertEquals(1600, profile.viewport.right)
        assertEquals(720, profile.viewport.bottom)
    }

    @Test
    fun testJsonSerializationAndDeserialization() {
        val profile = PhoneProfile(
            resolutionWidth = 1600,
            resolutionHeight = 720,
            viewport = Viewport(0, 0, 1600, 720),
            stateAnchors = mapOf(
                "attack" to Point(100, 600),
                "find_match" to Point(300, 500),
                "army" to Point(500, 650),
                "return_home" to Point(800, 620),
                "village_neutral" to Point(150, 200)
            ),
            troopAnchors = mapOf(
                "valkyrie" to Point(200, 650),
                "rage" to Point(300, 650)
            ),
            deploymentPoints = listOf(
                Point(100, 100),
                Point(1500, 650)
            ),
            wallPoints = listOf(
                Point(800, 400)
            ),
            isVerified = false
        )

        val json = profile.toJson()
        val parsed = PhoneProfile.fromJson(json)

        assertEquals(profile.resolutionWidth, parsed.resolutionWidth)
        assertEquals(profile.resolutionHeight, parsed.resolutionHeight)
        assertEquals(profile.viewport, parsed.viewport)
        assertEquals(profile.stateAnchors.size, parsed.stateAnchors.size)
        assertEquals(profile.stateAnchors["attack"], parsed.stateAnchors["attack"])
        assertEquals(profile.troopAnchors["valkyrie"], parsed.troopAnchors["valkyrie"])
        assertEquals(profile.deploymentPoints.size, parsed.deploymentPoints.size)
        assertEquals(profile.wallPoints.size, parsed.wallPoints.size)
        assertFalse(parsed.isVerified)
    }

    @Test
    fun homeResourceRegionsComeFromTheWallBalanceRegionsWhenUnset() {
        // Profiles saved before goals read the village balances only for walls.
        val old = """
            {
              "resolutionWidth": 1600, "resolutionHeight": 720,
              "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720},
              "wallConfig": {"currencies": {}, "resourceRois": {
                "gold": {"left": 1300, "top": 20, "right": 1500, "bottom": 50},
                "elixir": {"left": 1300, "top": 80, "right": 1500, "bottom": 110}
              }}
            }
        """.trimIndent()
        val profile = PhoneProfile.fromJson(old)
        assertEquals(
            mapOf(Resource.GOLD to Region(1300, 20, 1500, 50), Resource.ELIXIR to Region(1300, 80, 1500, 110)),
            profile.homeResourceRois
        )
        // Dark elixir has no region until it is calibrated.
        assertNull(profile.homeResourceRois[Resource.DARK_ELIXIR])
        assertEquals(profile, PhoneProfile.fromJson(profile.toJson()))
    }

    @Test
    fun savedHomeResourceRegionsWinOverTheWallOnes() {
        val profile = PhoneProfile.createDefault().copy(
            homeResourceRois = mapOf(Resource.DARK_ELIXIR to Region(1300, 140, 1500, 170)),
            wallConfig = WallConfig(resourceRois = mapOf("gold" to Region(1300, 20, 1500, 50)))
        )
        assertEquals(profile.homeResourceRois, PhoneProfile.fromJson(profile.toJson()).homeResourceRois)
    }

    @Test
    fun scoutRegionsRoundTripAndStartEmpty() {
        assertEquals(emptyMap<Resource, Region>(), PhoneProfile.createDefault().scoutLootRois)
        val profile = PhoneProfile.createDefault().copy(
            scoutLootRois = mapOf(Resource.GOLD to Region(112, 95, 260, 125)),
            scoutCountdownRoi = Region(750, 36, 850, 70),
            scoutFeeRoi = Region(1406, 516, 1494, 546)
        )
        assertEquals(profile, PhoneProfile.fromJson(profile.toJson()))
        // A scout region is calibration, so changing one costs verification like any other.
        val verified = profile.copy(isVerified = true)
        val edited = verified.copy(scoutFeeRoi = Region(1400, 516, 1494, 546))
        assertFalse(edited.editsOnto(verified, verified).isVerified)
    }

    @Test
    fun anUnknownHomeResourceIsRejected() {
        val bad = """
            {
              "resolutionWidth": 1600, "resolutionHeight": 720,
              "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720},
              "homeResourceRois": {"gems": {"left": 1300, "top": 20, "right": 1500, "bottom": 50}}
            }
        """.trimIndent()
        try {
            PhoneProfile.fromJson(bad)
            fail("Expected an unknown resource to be rejected")
        } catch (_: StopException) {
        }
    }

    @Test
    fun testDesktopProfileImportIgnoresLiveVerified() {
        // Even if an external profile JSON claims live_verified = true,
        // PhoneProfile must refuse to mark the phone calibration verified.
        val fakeDesktopJson = """
            {
              "live_verified": true,
              "resolutionWidth": 1600,
              "resolutionHeight": 720,
              "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720},
              "stateAnchors": {
                "attack": {"x": 100, "y": 600}
              }
            }
        """.trimIndent()

        val profile = PhoneProfile.fromJson(fakeDesktopJson)
        assertFalse("Imported profile must ignore desktop verified status", profile.isVerified)
    }

    @Test
    fun testPointOutsideViewportFailsValidation() {
        val badProfile = PhoneProfile(
            resolutionWidth = 1600,
            resolutionHeight = 720,
            viewport = Viewport(50, 50, 1550, 670),
            stateAnchors = mapOf(
                "attack" to Point(10, 10) // Outside viewport!
            ),
            isVerified = false
        )

        var caught = false
        try {
            badProfile.validate()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException for point outside calibrated viewport", caught)
    }

    @Test
    fun testUpdatingCalibrationResetsVerification() {
        // Suppose a profile was tested
        val profile = PhoneProfile(
            resolutionWidth = 1600,
            resolutionHeight = 720,
            viewport = Viewport(0, 0, 1600, 720),
            stateAnchors = mapOf("attack" to Point(100, 600)),
            isVerified = false
        )

        val updated = profile.withAnchor("attack", Point(120, 600))
        assertFalse(updated.isVerified)
        assertEquals(Point(120, 600), updated.stateAnchors["attack"])
    }

    @Test
    fun testMalformedJsonThrowsStop() {
        val badJsons = listOf(
            "",
            "{}",
            "{invalid: json}",
            """{"resolutionWidth": -100}""",
            // Missing viewport
            """{"resolutionWidth": 1600, "resolutionHeight": 720}""",
            // Fraction in resolution
            """{"resolutionWidth": 1600.5, "resolutionHeight": 720, "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720}}""",
            // Fraction in point
            """{"resolutionWidth": 1600, "resolutionHeight": 720, "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720}, "stateAnchors": {"attack": {"x": 100.25, "y": 600}}}""",
            // Malformed point record (missing y)
            """{"resolutionWidth": 1600, "resolutionHeight": 720, "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720}, "stateAnchors": {"attack": {"x": 100}}}""",
            // Non-integer string in point
            """{"resolutionWidth": 1600, "resolutionHeight": 720, "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720}, "stateAnchors": {"attack": {"x": "abc", "y": 600}}}"""
        )

        for (bad in badJsons) {
            var caught = false
            try {
                PhoneProfile.fromJson(bad)
            } catch (e: Exception) {
                caught = true
            }
            assertTrue("Expected exception for malformed json: $bad", caught)
        }
    }

    @Test
    fun testWorkflowValidationRequiresCompleteFields() {
        val incomplete = PhoneProfile.createDefault(1600, 720)
        var caughtAttack = false
        try {
            incomplete.validateForAttack()
        } catch (e: StopException) {
            caughtAttack = true
            assertTrue(e.message!!.contains("Attack workflow configuration incomplete"))
        }
        assertTrue("Expected StopException for incomplete attack profile", caughtAttack)

        var caughtWalls = false
        try {
            incomplete.validateForWalls()
        } catch (e: StopException) {
            caughtWalls = true
            assertTrue(e.message!!.contains("Wall workflow configuration incomplete"))
        }
        assertTrue("Expected StopException for incomplete wall profile", caughtWalls)
    }

    @Test
    fun testWallValidationPassesWithUpgradeListRequirements() {
        val profile = PhoneProfile.createDefault(1600, 720).copy(
            stateAnchors = mapOf("village_neutral" to Point(100, 100)),
            homeResourceRois = mapOf(
                Resource.GOLD to Region(1300, 20, 1500, 50),
                Resource.ELIXIR to Region(1300, 80, 1500, 110)
            )
        )
        // Passes without wallConfig, wallPoints, or wallRows
        profile.validateForWalls()
    }

    @Test
    fun oldWallPointsRowsAndCursorStillLoadAndRoundTrip() {
        // As saved on the phone before walls were taken only from the builder's list. Nothing
        // reads these now; they must load, even off screen, and survive a later save.
        val old = """
            {
              "resolutionWidth": 1600, "resolutionHeight": 720,
              "viewport": {"left": 0, "top": 0, "right": 1600, "bottom": 720},
              "wallPoints": [{"x": 800, "y": 210}, {"x": 5000, "y": 10}],
              "wallRows": {
                "origin": {"x": 2000, "y": 520},
                "alongStep": {"dx": 12.9, "dy": -9.678},
                "rowStep": {"dx": 12.926, "dy": 9.695},
                "rows": 7, "wallsPerRow": 40
              },
              "wallCursor": {"row": 3, "index": 21},
              "isVerified": true
            }
        """.trimIndent()

        val parsed = PhoneProfile.fromJson(old)
        parsed.validate()

        assertEquals(listOf(Point(800, 210), Point(5000, 10)), parsed.wallPoints)
        assertEquals(WallRows(Point(2000, 520), Step(12.9, -9.678), Step(12.926, 9.695), 7, 40), parsed.wallRows)
        assertEquals(WallCursor(3, 21), parsed.wallCursor)
        assertTrue(parsed.isVerified)
        assertEquals(parsed, PhoneProfile.fromJson(parsed.toJson()))
    }

    @Test
    fun testAtomicSaveToFile() {
        val tempDir = java.nio.file.Files.createTempDirectory("profile_test").toFile()
        try {
            val targetFile = File(tempDir, "profile.json")
            val profile = PhoneProfile.createDefault(1600, 720)
            profile.saveToFile(targetFile)

            assertTrue("Profile file should exist after atomic save", targetFile.exists())
            val loaded = PhoneProfile.loadFromFile(targetFile)
            assertEquals(1600, loaded.resolutionWidth)
            assertEquals(720, loaded.resolutionHeight)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCollectionTargetsRoundTripAndLeaveOthersVerified() {
        val verified = PhoneProfile.createDefault(1600, 720).copy(isVerified = true)
        val targets = listOf(CollectionTarget("gold 1", Point(1217, 300)), CollectionTarget("elixir 1", Point(343, 300)))
        val edited = verified.withCollectionTargets(targets)
        assertEquals(targets, PhoneProfile.fromJson(edited.toJson()).collectionTargets)
        // Collection targets are not what the attack verified.
        assertTrue(edited.isVerified)
        assertEquals(emptyList<CollectionTarget>(), PhoneProfile.createDefault().collectionTargets)
    }

    /**
     * The tray measured by hand in September. Both phone profiles still held exactly this tray on
     * 14 October (audit-evidence/clash-18647/r4-profiles-now).
     */
    private fun liveProfile() = PhoneProfile.loadFromFile(File("../calibration/live-2026-09-17/phone-profile.json"))

    @Test
    fun aTroopCardTapAlsoMovesItsCardArea() {
        val live = liveProfile().copy(isVerified = true)
        // D4: Blood's rages tap moved one card right, off its fourth hero.
        val edited = live.withTroopCard("rages", Point(905, 650))
        assertEquals(Point(905, 650), edited.troopAnchors["rages"])
        assertEquals(Region(872, 600, 939, 702), edited.troopRois["rages"])
        assertFalse(edited.isVerified)
        // Every other card keeps its tap and its area.
        assertEquals(Region(376, 600, 443, 702), edited.troopRois["king"])
        assertEquals(live.troopAnchors - "rages", edited.troopAnchors - "rages")
        assertEquals(live.troopRois - "rages", edited.troopRois - "rages")
    }

    @Test
    fun everyMeasuredCardAreaRebuildsFromItsTap() {
        val live = liveProfile()
        assertEquals(7, live.troopAnchors.size)
        for ((name, tap) in live.troopAnchors) {
            val stored = live.troopRois.getValue(name)
            val rebuilt = live.withTroopCard(name, tap).troopRois.getValue(name)
            val edges = listOf(stored.left - rebuilt.left, stored.top - rebuilt.top, stored.right - rebuilt.right, stored.bottom - rebuilt.bottom)
            assertTrue("$name: rebuilt $rebuilt, measured $stored", edges.all { Math.abs(it) <= 1 })
        }
    }

    @Test
    fun aNewCardAreaTakesItsHeightFromTheValkyries() {
        val live = liveProfile()
        val noJumps = live.copy(troopRois = live.troopRois - "jumps")
        assertEquals(Region(977, 600, 1044, 702), noJumps.withTroopCard("jumps", Point(1010, 655)).troopRois["jumps"])
        // With no areas at all, a card's height around the tap, and never past the screen's edge.
        val empty = PhoneProfile.createDefault(1600, 720)
        assertEquals(Region(269, 600, 336, 702), empty.withTroopCard("valkyries", Point(302, 650)).troopRois["valkyries"])
        val edge = empty.withTroopCard("jumps", Point(1590, 700))
        assertEquals(Region(1557, 650, 1600, 720), edge.troopRois["jumps"])
        edge.validate()
    }

    @Test
    fun aTroopCardSurvivesSaveAndLoad() {
        val opened = liveProfile().copy(isVerified = true)
        val edited = opened.withTroopCard("rages", Point(905, 650))
        // Saved over the profile on disk, as Calibrate's Save does.
        val saved = edited.editsOnto(opened, opened)
        assertEquals(Region(872, 600, 939, 702), saved.troopRois["rages"])
        assertEquals(Point(905, 650), saved.troopAnchors["rages"])
        assertFalse(saved.isVerified)
        val loaded = PhoneProfile.fromJson(saved.toJson())
        assertEquals(saved.troopRois, loaded.troopRois)
        assertEquals(saved.troopAnchors, loaded.troopAnchors)
        assertFalse(loaded.isVerified)
    }

    @Test
    fun testCollectionTargetsOffScreenOrTooManyAreRejected() {
        val base = PhoneProfile.createDefault(1600, 720)
        for (bad in listOf(
            listOf(CollectionTarget("off", Point(1700, 300))),
            (0..CollectionPolicy.MAX_TARGETS).map { CollectionTarget("t$it", Point(800, 300)) }
        )) {
            try {
                PhoneProfile.fromJson(base.copy(collectionTargets = bad).toJson())
                fail("Expected ${bad.size} targets to be rejected")
            } catch (_: StopException) {
            }
        }
    }
}
