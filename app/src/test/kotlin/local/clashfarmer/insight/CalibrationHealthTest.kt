package local.clashfarmer.insight

import local.clashfarmer.accounts.AccountRecord
import local.clashfarmer.accounts.AccountSettings
import local.clashfarmer.core.FarmingGoal
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.insight.LogFixtures.BLOOD
import local.clashfarmer.insight.LogFixtures.SWEAT
import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaped on D4 (audit-evidence/overnight/D4-blood-spell-unverified.md): Blood's tray regions were
 * recalibrated at a 97 px pitch, but its troop and hero taps were copied from Sweat's, so every
 * tap after the Valkyries landed one card to the left.
 */
class CalibrationHealthTest {
    private val sweat = AccountRecord(SWEAT, "Sweat", "Philly0105", "#8YQCYG2RU")
    private val blood = AccountRecord(BLOOD, "Blood", "Philly0105", "#Y2G29YJQ8")

    /** Sweat's taps: card centres 97 px apart along the tray. */
    private val sweatAnchors = mapOf(
        "valkyries" to Point(302, 650), "king" to Point(399, 650), "queen" to Point(496, 650),
        "warden" to Point(593, 650), "champion" to Point(690, 650), "rages" to Point(787, 650)
    )

    /** Blood's regions as D4 measured them: valkyries 269-335, king 376-443 and so on. */
    private val bloodRois = mapOf(
        "valkyries" to Region(269, 610, 335, 700), "king" to Region(376, 610, 443, 700),
        "queen" to Region(473, 610, 540, 700), "warden" to Region(570, 610, 637, 700),
        "champion" to Region(667, 610, 733, 700), "rages" to Region(775, 610, 842, 700)
    )

    private fun profile(anchors: Map<String, Point>, rois: Map<String, Region> = emptyMap(), verified: Boolean = true) =
        PhoneProfile.createDefault().copy(troopAnchors = anchors, troopRois = rois, isVerified = verified)

    private fun kinds(findings: List<Finding>) = findings.map { it.kind }.toSet()

    @Test
    fun bloodEqualToSweatOnFiveOfSixAnchorsGivesCopiedAnchors() {
        // Blood's rages tap was moved one card right; the other five are Sweat's, within 3 px.
        val bloodAnchors = sweatAnchors.mapValues { (k, p) -> if (k == "rages") Point(p.x + 97, p.y) else Point(p.x + 2, p.y - 1) }
        val result = CalibrationHealth.check(listOf(sweat, blood),
            mapOf(SWEAT to profile(sweatAnchors), BLOOD to profile(bloodAnchors, bloodRois)),
            savedAtMs = mapOf(SWEAT to 1_000L, BLOOD to 2_000L))

        val copied = result.getValue(BLOOD).single { it.kind == Finding.Kind.COPIED_ANCHORS }
        assertEquals(Tone.WARN, copied.tone)
        assertEquals(Finding.Area.TRAY, copied.area)
        assertEquals("Same taps as Sweat's (5 of 6)", copied.title)
        assertEquals(SWEAT, copied.otherAccountId)
        assertTrue(copied.detail, copied.detail.contains("Blood's calibration was saved later"))
        // Symmetric: Sweat shows it too.
        assertEquals("Same taps as Blood's (5 of 6)", result.getValue(SWEAT).single { it.kind == Finding.Kind.COPIED_ANCHORS }.title)
        assertTrue(copied.detail, copied.detail.contains("That is fine if both trays hold the same cards in the same order."))

        // The fingerprint is stable for the same taps and changes when a tap moves.
        assertEquals(CalibrationHealth.fingerprint(profile(bloodAnchors, bloodRois)), copied.fingerprint)
        val moved = bloodAnchors + ("king" to Point(500, 650))
        assertFalse(CalibrationHealth.fingerprint(profile(moved, bloodRois)) == copied.fingerprint)
    }

    @Test
    fun separatelyCalibratedTraysAreNotCopied() {
        val bloodAnchors = sweatAnchors.mapValues { (_, p) -> Point(p.x - 33, p.y) }
        val result = CalibrationHealth.check(listOf(sweat, blood),
            mapOf(SWEAT to profile(sweatAnchors), BLOOD to profile(bloodAnchors)))
        assertFalse(kinds(result.getValue(BLOOD)).contains(Finding.Kind.COPIED_ANCHORS))
        // Three of six matching, or fewer than four shared, says nothing.
        val half = sweatAnchors.entries.mapIndexed { i, (k, p) -> k to if (i < 3) p else Point(p.x + 50, p.y) }.toMap()
        val few = sweatAnchors.entries.take(3).associate { it.key to it.value }
        for (other in listOf(half, few)) {
            val r = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to profile(sweatAnchors), BLOOD to profile(other)))
            assertFalse(kinds(r.getValue(BLOOD)).contains(Finding.Kind.COPIED_ANCHORS))
        }
    }

    @Test
    fun copiedRegionsFoldIntoTheSameFinding() {
        val sweatRois = bloodRois
        val result = CalibrationHealth.check(listOf(sweat, blood),
            mapOf(SWEAT to profile(sweatAnchors, sweatRois), BLOOD to profile(sweatAnchors.mapValues { Point(it.value.x + 40, 650) }, bloodRois)))
        val copied = result.getValue(BLOOD).single { it.kind == Finding.Kind.COPIED_ANCHORS }
        // The taps differ, so the title names what matches.
        assertEquals("Same tray regions as Sweat's (6 of 6)", copied.title)
        assertTrue(copied.detail.contains("tray regions"))
    }

    @Test
    fun gapsOf97Against194GiveUnevenSpacing() {
        val anchors = mapOf(
            "valkyries" to Point(302, 650), "king" to Point(399, 650), "queen" to Point(496, 650),
            "warden" to Point(690, 650), "champion" to Point(787, 650), "rages" to Point(884, 650),
            // An alias of the first card: one tap, two names.
            "troop_1" to Point(304, 650)
        )
        val findings = CalibrationHealth.check(listOf(sweat), mapOf(SWEAT to profile(anchors))).getValue(SWEAT)
        val uneven = findings.single { it.kind == Finding.Kind.UNEVEN_SPACING }
        assertEquals(Tone.WARN, uneven.tone)
        assertEquals("The gap between queen and warden is 194 px against 97 px elsewhere. " +
            "A card slot may be missing, or a tap was saved on the wrong card.", uneven.detail)
        assertEquals("6 cards, spacing 97 px", CalibrationHealth.trayLine(profile(anchors)))

        val even = CalibrationHealth.check(listOf(sweat), mapOf(SWEAT to profile(sweatAnchors))).getValue(SWEAT)
        assertFalse(kinds(even).contains(Finding.Kind.UNEVEN_SPACING))
    }

    @Test
    fun onlyASiegeSlotBeforeTheKingMayBeEmpty() {
        val names = listOf("valkyries", "king", "queen", "warden", "champion", "rages", "jumps")
        fun uneven(xs: List<Int>, keys: List<String> = names) = CalibrationHealth.check(listOf(blood),
            mapOf(BLOOD to profile(keys.zip(xs) { n, x -> n to Point(x, 650) }.toMap())))
            .getValue(BLOOD).singleOrNull { it.kind == Finding.Kind.UNEVEN_SPACING }?.detail

        // Sweat's tray has no siege machine and no wide gap.
        assertNull(uneven(listOf(302, 409, 506, 603, 700, 808, 905)))
        // Blood's has one: Calibrate has no siege target, so Valkyries to King is two cards.
        assertNull(uneven(listOf(302, 506, 603, 700, 797, 905, 1002)))
        // The same double gap anywhere else is a missing card.
        assertEquals("The gap between king and queen is 194 px against 97 px elsewhere. " +
            "A card slot may be missing, or a tap was saved on the wrong card.", uneven(listOf(302, 399, 593, 690, 787, 884, 981)))
        // With two double gaps, the siege slot is allowed and the second is not.
        assertTrue(uneven(listOf(302, 506, 603, 797, 894, 991, 1088))!!.startsWith("The gap between queen and warden is 194 px"))
        // Two empty slots after the Valkyries are more than a siege machine.
        assertTrue(uneven(listOf(302, 593, 690, 787, 884, 981, 1078))!!.startsWith("The gap between valkyries and king is 291 px"))
        // Sweat's tray with the King never tapped: the empty slot is the King's, not a siege machine's.
        assertTrue(uneven(listOf(302, 506, 603, 700, 808, 905), names - "king")!!.startsWith("The gap between valkyries and queen is 204 px"))
    }

    @Test
    fun bloodRecalibratedAroundItsSiegeCardClearsTheTrayRow() {
        // Both phone profiles on 14 October: the September taps and the areas measured around them.
        val september = profile(
            mapOf("valkyries" to Point(302, 650), "king" to Point(409, 650), "queen" to Point(506, 650), "warden" to Point(603, 650),
                "champion" to Point(700, 650), "rages" to Point(808, 650), "jumps" to Point(905, 650)),
            mapOf("valkyries" to Region(269, 600, 335, 702), "king" to Region(376, 600, 443, 702),
                "queen" to Region(473, 600, 540, 702), "warden" to Region(570, 600, 637, 702),
                "champion" to Region(667, 600, 733, 702), "rages" to Region(775, 600, 842, 702),
                "jumps" to Region(872, 600, 938, 702)))
        fun tray(findings: List<Finding>) = findings.filter { it.area == Finding.Area.TRAY }

        // Before, D4: Blood holds Sweat's tray, so every tap after the Valkyries lands one card left.
        val before = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to september, BLOOD to september))
        assertEquals(listOf(Finding.Kind.COPIED_ANCHORS), tray(before.getValue(BLOOD)).map { it.kind })

        // Blood's tray: Valkyries, siege machine, King, Queen, Warden, a fourth hero, an x11 spell,
        // three x1 clan castle spells. Each card right of the siege machine is tapped again in
        // Calibrate, which moves its area too; the Valkyries' tap and area were already Blood's.
        val taps = mapOf("king" to 506, "queen" to 603, "warden" to 700, "champion" to 808, "rages" to 905, "jumps" to 1002)
        val after = taps.entries.fold(september) { p, (name, x) -> p.withTroopCard(name, Point(x, 650)) }
        val findings = CalibrationHealth.check(listOf(sweat, blood), mapOf(SWEAT to september, BLOOD to after))
        assertEquals(emptyList<Finding>(), tray(findings.getValue(BLOOD)))
        assertEquals(Tone.OK, CalibrationHealth.tone(findings.getValue(BLOOD), Finding.Area.TRAY))
        assertEquals(emptyList<Finding>(), tray(findings.getValue(SWEAT)))
        assertEquals("7 cards, spacing 97 px", CalibrationHealth.trayLine(after))
        // A recalibration still needs one supervised attack, on the Calibration row.
        assertTrue(kinds(findings.getValue(BLOOD)).contains(Finding.Kind.NOT_VERIFIED))
    }

    @Test
    fun missingAndUnverifiedProfiles() {
        val result = CalibrationHealth.check(listOf(sweat, blood),
            mapOf(SWEAT to profile(sweatAnchors, verified = false), BLOOD to null))
        val bloodFindings = result.getValue(BLOOD)
        assertEquals(Finding.Kind.NOT_CALIBRATED, bloodFindings.first().kind)
        assertEquals(Tone.BAD, bloodFindings.first().tone)
        val sweatFindings = result.getValue(SWEAT)
        assertEquals("Needs one supervised attack", sweatFindings.single { it.kind == Finding.Kind.NOT_VERIFIED }.title)
        // A default profile lacks the attack anchors and perimeter; validateForAttack's message is the detail.
        val attack = sweatFindings.single { it.kind == Finding.Kind.ATTACK_INCOMPLETE }
        assertTrue(attack.detail, attack.detail.startsWith("Attack workflow configuration incomplete. Missing:"))
        assertEquals("Not calibrated", CalibrationHealth.calibrationLine(null))
        assertEquals("Needs one supervised attack", CalibrationHealth.calibrationLine(profile(sweatAnchors, verified = false)))
        assertEquals("Verified on this phone", CalibrationHealth.calibrationLine(profile(sweatAnchors)))
    }

    @Test
    fun wallAndGoalProblems() {
        val walls = sweat.copy(settings = AccountSettings(wallsEnabled = true,
            capacities = mapOf(Resource.GOLD to 26_000_000, Resource.ELIXIR to 26_000_000),
            goal = FarmingGoal(mapOf(Resource.DARK_ELIXIR to 300_000L))))
        val findings = CalibrationHealth.check(listOf(walls), mapOf(SWEAT to profile(sweatAnchors))).getValue(SWEAT)
        val wallsIncomplete = findings.single { it.kind == Finding.Kind.WALLS_INCOMPLETE }
        assertTrue(wallsIncomplete.detail.startsWith("Wall workflow configuration incomplete"))
        val goal = findings.single { it.kind == Finding.Kind.GOAL_UNREACHABLE }
        assertTrue(goal.detail, goal.detail.startsWith("Goal for Dark elixir has no village balance region"))
        assertEquals(Tone.BAD, CalibrationHealth.tone(findings, Finding.Area.WALLS))
        assertEquals(Tone.WARN, CalibrationHealth.tone(findings, Finding.Area.GOAL))
        assertEquals("Capacities set, buys at 90%", CalibrationHealth.wallsLine(walls.settings))
        assertEquals("Off", CalibrationHealth.wallsLine(AccountSettings()))

        val noCapacity = sweat.copy(settings = AccountSettings())
        val setup = CalibrationHealth.check(listOf(noCapacity), mapOf(SWEAT to profile(sweatAnchors))).getValue(SWEAT)
            .single { it.kind == Finding.Kind.WALL_SETUP }
        assertEquals(Tone.WARN, setup.tone)
        assertEquals("Wall spending needs the gold capacity.", setup.detail)
    }
}
