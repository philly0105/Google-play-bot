package local.clashfarmer.core

import local.clashfarmer.recognition.PhoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AttackPlannerTest {

    private object MinimumRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun createValidProfile(): PhoneProfile {
        return PhoneProfile(
            resolutionWidth = 1600,
            resolutionHeight = 720,
            viewport = Viewport(0, 0, 1600, 720),
            stateAnchors = mapOf(
                "attack" to Point(100, 600),
                "find_match" to Point(300, 500),
                "return_home" to Point(800, 620),
                "hero_drop" to Point(800, 360)
            ),
            troopAnchors = mapOf(
                "valkyries" to Point(150, 650),
                "rages" to Point(250, 650),
                "jumps" to Point(350, 650),
                "king" to Point(450, 650),
                "queen" to Point(550, 650),
                "warden" to Point(650, 650),
                "champion" to Point(750, 650)
            ),
            perimeterSegments = listOf(
                Pair(Point(200, 100), Point(1400, 100)),
                Pair(Point(1400, 100), Point(1400, 600)),
                Pair(Point(1400, 600), Point(200, 600)),
                Pair(Point(200, 600), Point(200, 100))
            ),
            isVerified = false
        )
    }

    @Test
    fun testDeploymentPlanGenerationAndBounds() {
        val profile = createValidProfile()
        val rng = Random(42)
        val plan = AttackPlanner.buildDeployment(profile, rng)

        val valkGroup = plan.first { it.name == "valkyries" }
        assertEquals(40, valkGroup.drops.size)
        assertEquals(profile.troopAnchors["valkyries"], valkGroup.selectPoint)

        val rageGroup = plan.first { it.name == "rages" }
        assertEquals(5, rageGroup.drops.size)
        assertEquals(profile.troopAnchors["rages"], rageGroup.selectPoint)

        val jumpGroup = plan.first { it.name == "jumps" }
        assertEquals(1, jumpGroup.drops.size)

        val heroNames = listOf("king", "queen", "warden", "champion")
        for (hName in heroNames) {
            val hGroup = plan.first { it.name == hName }
            // One point apiece was one point too few: an obstacle standing on it refused the
            // drop and the retries went back to the same refused pixel. Two fallbacks follow,
            // stepped along the perimeter nearest the calibrated point.
            assertEquals(3, hGroup.drops.size)
            // Near the calibrated hero point rather than on it: each attack nudges every drop so
            // that no two attacks place anything identically.
            val drop = hGroup.drops[0]
            assertTrue("$hName at $drop", kotlin.math.abs(drop.x - 800) <= 28)
            assertTrue("$hName at $drop", kotlin.math.abs(drop.y - 360) <= 28)
            // Far enough apart that one rock cannot cover two of them.
            for (other in hGroup.drops.drop(1)) {
                val gap = kotlin.math.hypot(
                    (other.x - drop.x).toDouble(), (other.y - drop.y).toDouble()
                )
                assertTrue("$hName fallback $other only $gap from $drop", gap >= 14.0)
            }
            assertTrue(hGroup.isHero)
        }

        // The heroes are one phase, so their four cards are tapped one after another with
        // nothing read back in between and they land together.
        val phases = AttackPlanner.phases(plan)
        val heroPhase = phases.single { it.size > 1 }
        assertEquals(heroNames, heroPhase.map { it.name })
        assertEquals(plan.size - heroNames.size + 1, phases.size)

        // Verify every single drop point across all groups is strictly within viewport
        for (group in plan) {
            for (drop in group.drops) {
                assertTrue(
                    "Drop point $drop in group ${group.name} must be within viewport ${profile.viewport}",
                    profile.viewport.contains(drop)
                )
            }
        }
    }

    @Test
    fun rageSpellsDeployBeforeValkyries() {
        val plan = AttackPlanner.buildDeployment(createValidProfile(), Random(42))

        assertTrue(
            "Rage spells must be placed before Valkyries",
            plan.indexOfFirst { it.name == "rages" } < plan.indexOfFirst { it.name == "valkyries" }
        )
    }

    @Test
    fun valkyriesAreSpreadEvenlyAlongTheWholeOutline() {
        val profile = createValidProfile()
        val drops = AttackPlanner.buildDeployment(profile, MinimumRandom)
            .first { it.name == "valkyries" }
            .drops

        assertEquals(40, drops.size)
        assertSpreadEvenly("Valkyries", drops, profile)
    }

    @Test
    fun testMissingPerimeterThrowsPreciseStopException() {
        val badProfile = createValidProfile().copy(perimeterSegments = emptyList(), deploymentPoints = emptyList())
        var caught = false
        try {
            AttackPlanner.buildDeployment(badProfile)
        } catch (e: StopException) {
            caught = true
            assertTrue(e.message!!.contains("Four calibrated perimeter segments are required"))
        }
        assertTrue("Expected StopException for missing perimeter", caught)
    }

    @Test
    fun testMissingValkyrieAnchorThrowsPreciseStopException() {
        val badProfile = createValidProfile().copy(troopAnchors = mapOf("rages" to Point(250, 650)))
        var caught = false
        try {
            AttackPlanner.buildDeployment(badProfile)
        } catch (e: StopException) {
            caught = true
            assertTrue(e.message!!.contains("Missing calibrated Valkyries troop card select anchor"))
        }
        assertTrue("Expected StopException for missing Valkyries anchor", caught)
    }

    @Test
    fun noTwoAttacksPlaceAnythingIdentically() {
        // Every group moved between attacks before this except the ones that mattered: the four
        // heroes went down on one identical pixel, the jump dead centre, and the siege on the
        // same corner, attack after attack.
        val profile = createValidProfile()
        val first = AttackPlanner.buildDeployment(profile, Random(1))
        val second = AttackPlanner.buildDeployment(profile, Random(2))

        for (name in listOf("valkyries", "rages", "jumps", "king", "queen", "warden", "champion")) {
            val a = first.first { it.name == name }.drops
            val b = second.first { it.name == name }.drops
            assertNotEquals("$name placed identically on both attacks", a, b)
        }
    }

    @Test
    fun theFourHeroesDoNotStackOnOnePixel() {
        val plan = AttackPlanner.buildDeployment(createValidProfile(), Random(7))
        val heroPoints = listOf("king", "queen", "warden", "champion")
            .map { name -> plan.first { it.name == name }.drops.first() }
        assertEquals(4, heroPoints.toSet().size)
    }

    @Test
    fun aNudgeNeverLeavesTheViewport() {
        // The hero point sits on the viewport's own edge, where a nudge outward would put a tap
        // off the screen and stop the run.
        val base = createValidProfile()
        val profile = base.copy(
            stateAnchors = base.stateAnchors + ("hero_drop" to Point(1599, 0)),
            heroDrop = Point(1599, 0)
        )
        for (seed in 1..40) {
            val plan = AttackPlanner.buildDeployment(profile, Random(seed))
            for (group in plan) {
                for (drop in group.drops) {
                    assertTrue("$drop outside ${profile.viewport}", profile.viewport.contains(drop))
                }
            }
        }
    }

    /** The tray as calibrated on the phone before the troop events: troops, heroes, then spells. */
    private fun phoneTrayProfile(): PhoneProfile = createValidProfile().copy(
        troopAnchors = mapOf(
            "valkyries" to Point(302, 650),
            "king" to Point(409, 650),
            "queen" to Point(506, 650),
            "warden" to Point(603, 650),
            "champion" to Point(700, 650),
            "rages" to Point(808, 650),
            "jumps" to Point(905, 650)
        ),
        troopRois = mapOf(
            "valkyries" to Region(269, 600, 335, 702),
            "king" to Region(376, 600, 443, 702),
            "rages" to Region(775, 600, 842, 702)
        )
    )

    @Test
    fun theEventCardTakesTheFirstSlotAndPushesEveryOtherCardOneRight() {
        // Measured on Sweat's scout tray on 24 September: Kane x40 first, everything else one
        // card further right than it was calibrated.
        val shifted = AttackPlanner.withEventCard(phoneTrayProfile())

        assertEquals(Point(302, 650), shifted.troopAnchors["event"])
        assertEquals(Point(399, 650), shifted.troopAnchors["valkyries"])
        assertEquals(Point(506, 650), shifted.troopAnchors["king"])
        assertEquals(Point(797, 650), shifted.troopAnchors["champion"])
        assertEquals(Point(905, 650), shifted.troopAnchors["rages"])
        assertEquals(Point(1002, 650), shifted.troopAnchors["jumps"])
        assertEquals(Region(269, 600, 335, 702), shifted.troopRois["event"])
        assertEquals(Region(366, 600, 432, 702), shifted.troopRois["valkyries"])
        assertEquals(Region(872, 600, 939, 702), shifted.troopRois["rages"])
    }

    @Test
    fun fiftyEventTroopsAreSpreadLikeTheValkyries() {
        val profile = AttackPlanner.withEventCard(phoneTrayProfile())
        val plan = AttackPlanner.buildDeployment(profile, MinimumRandom, withEventTroops = true)
        val event = plan.first { it.name == "event" }

        assertEquals(profile.troopAnchors["event"], event.selectPoint)
        assertEquals(profile.troopRois["event"], event.cardRoi)
        assertEquals(50, event.drops.size)
        assertSpreadEvenly("Event troops", event.drops, profile)
        // They follow the Valkyries onto the field, after the rages.
        assertEquals(plan.indexOfFirst { it.name == "valkyries" } + 1, plan.indexOfFirst { it.name == "event" })
    }

    @Test
    fun theOrdinaryAttackPlacesNoEventTroops() {
        val plan = AttackPlanner.buildDeployment(phoneTrayProfile(), Random(3))
        assertTrue(plan.none { it.name == "event" })
    }

    @Test
    fun theBadgeBandsAreTheTopOfTheFirstTwoCardSlots() {
        // Blood's event scout on 7 October: "x50" at x 282-328 and "x44" at x 380-425, y 597-617.
        // Each band runs 8 px past its card for the last digit.
        assertEquals(
            listOf(Region(269, 595, 343, 620), Region(366, 595, 440, 620)),
            AttackPlanner.trayBadgeRegions(phoneTrayProfile())
        )
    }

    @Test
    fun noBadgeBandsWithNothingToMeasureTheTray() {
        // No hero and no jump spell: on the phone no two cards give a card's width.
        val profile = phoneTrayProfile().let {
            it.copy(troopAnchors = it.troopAnchors - listOf("king", "queen", "warden", "champion", "jumps"))
        }
        assertNull(AttackPlanner.trayBadgeRegions(profile))
        // The stop says what is missing without naming a hero the account may not have.
        val stop = runCatching { AttackPlanner.withEventCard(profile) }.exceptionOrNull()
        assertTrue(stop is StopException)
        assertEquals("The calibration has no two cards side by side to measure the event tray's card width from", stop!!.message)
    }

    @Test
    fun theCardWidthIsMeasuredFromTheCardsTheArmyHas() {
        fun secondBand(profile: PhoneProfile) = AttackPlanner.trayBadgeRegions(profile)?.get(1)?.left
        val tray = phoneTrayProfile()
        // An account without the Queen has no card for her, so its Warden and Champion sit one card left.
        val noQueen = tray.copy(troopAnchors = tray.troopAnchors - "queen" + ("warden" to Point(506, 650)) + ("champion" to Point(603, 650)))
        assertEquals(366, secondBand(noQueen))
        assertEquals(Point(506, 650), AttackPlanner.withEventCard(noQueen).troopAnchors["king"])
        // Without the King, the Queen and the Warden measure it.
        assertEquals(366, secondBand(tray.copy(troopAnchors = tray.troopAnchors - "king")))
        // A hero card left without a tap leaves a double gap; the heroes side by side still measure one card.
        assertEquals(366, secondBand(tray.copy(troopAnchors = tray.troopAnchors - "queen")))
        // One hero alone measures nothing, so the rage and jump cards, neighbours in the tray, do.
        assertEquals(366, secondBand(tray.copy(troopAnchors = tray.troopAnchors - listOf("queen", "warden", "champion"))))
    }

    @Test
    fun aBadgeCountIsTheAgreedCutOrTheOnlyCleanOne() {
        assertEquals(50, AttackPlanner.badgeCount(listOf("x50", "xSO", "X5O", "x44")))
        assertEquals(44, AttackPlanner.badgeCount(listOf("x44", "x44")))
        // 8 October on Sweat: "x4C,x4C,x40,x40" must not tie as 4 against 40.
        assertEquals(40, AttackPlanner.badgeCount(listOf("x4C", "x4C", "x40", "x40")))
        // 07:28 and 07:36 on 8 October: the pale event card read on one cut only.
        assertEquals(50, AttackPlanner.badgeCount(listOf("x50")))
        assertNull(AttackPlanner.badgeCount(listOf("x50", "x44")))
        assertNull(AttackPlanner.badgeCount(emptyList()))
    }

    @Test
    fun aSpellBadgeBandIsTheTopOfItsOwnCard() {
        // Blood's scout of 7 October: the spell badges "x11" and "x1" sit at y 597-617 like the troops'.
        assertEquals(Region(958, 595, 1054, 620), AttackPlanner.badgeBand(Region(958, 600, 1046, 702)))
    }

    @Test
    fun theBadgeBandReachesTheLastDigitPastTheCardEdge() {
        // Measured on Sweat's 18.647 scout: the x40 ends at 338, the x5 at 845, the x1 at 941,
        // each past its card's right edge; Blood's event-tray x11 at about 1040 against 1035.
        val valkyries = AttackPlanner.badgeBand(Region(269, 600, 335, 702))
        assertTrue(valkyries.right >= 343)
        assertTrue(AttackPlanner.badgeBand(Region(775, 600, 842, 702)).right >= 845)
        assertTrue(AttackPlanner.badgeBand(Region(872, 600, 938, 702)).right >= 941)
        assertTrue(AttackPlanner.badgeBand(Region(969, 600, 1035, 702)).right >= 1040)
        // Only the band grows: it starts where the card does and stops short of the next card.
        assertEquals(269, valkyries.left)
        assertTrue(AttackPlanner.badgeBand(Region(775, 600, 842, 702)).right < 872)
    }

    @Test
    fun sweatsWholeX40GivesTheSameValkyrieDropsAsTheClippedRead() {
        // Before the wider band Sweat's 14 October battles read "x4C,X4C,x4C,x4C" (4 battles) or
        // "x4C,X4C,x4C,40" (9) over "(-", an unread layout that deploys plain with the fixed 40.
        // Modelled: the whole badge reads x40 on every cut, which is the plain tray, and still 40 drops.
        val whole = listOf("x40", "x40", "x40", "x40")
        for (clipped in listOf(listOf("x4C", "X4C", "x4C", "x4C"), listOf("x4C", "X4C", "x4C", "40"))) {
            assertEquals(AttackPlanner.TrayLayout.UNKNOWN, AttackPlanner.trayLayout(clipped, listOf("(-")))
            assertEquals(40, AttackPlanner.dropCounts(AttackPlanner.TrayLayout.UNKNOWN, clipped, listOf("(-")).valkyries)
        }
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(whole, listOf("(-")))
        assertEquals(40, AttackPlanner.dropCounts(AttackPlanner.TrayLayout.PLAIN, whole, listOf("(-")).valkyries)
        assertEquals("plain", AttackPlanner.forTray(phoneTrayProfile(), AttackPlanner.TrayLayout.UNKNOWN, false).decision)
        assertEquals("plain", AttackPlanner.forTray(phoneTrayProfile(), AttackPlanner.TrayLayout.PLAIN, false).decision)
    }

    @Test
    fun aSpellBadgeCountsDownToZero() {
        // Spell stocks are small: Blood's clan-castle spells show "x1", an emptied card "x0".
        assertEquals(1, AttackPlanner.spellBadgeCount(listOf("x1", "xl", "X1")))
        assertEquals(0, AttackPlanner.spellBadgeCount(listOf("x0", "xO")))
        assertEquals(11, AttackPlanner.spellBadgeCount(listOf("x11", "x11", "x1")))
        assertEquals(10, AttackPlanner.spellBadgeCount(listOf("x10")))
        assertNull(AttackPlanner.spellBadgeCount(listOf("x11", "x10")))
        assertNull(AttackPlanner.spellBadgeCount(listOf("100", "100")))
        assertNull(AttackPlanner.spellBadgeCount(emptyList()))
    }

    @Test
    fun aNumberWithoutItsXIsNotABadge() {
        // A hero's level and a siege's count sit elsewhere on the card; a bare number is not xNN.
        assertNull(AttackPlanner.badgeCount(listOf("100", "100", "!")))
    }

    private val sweatSlot1 = listOf("x4C", "x4C", "x40", "x40")
    private val bloodSlot1 = listOf("ttX", "X44", "X44", "X44")

    @Test
    fun aTroopCardDropsItsBadgeCountWhenTwoCutsAgree() {
        // 8 October: Blood's tray holds 44 Valkyries; the fixed 40 needed a retry for the other four.
        assertEquals(44, AttackPlanner.troopDropCount(bloodSlot1, 40))
        assertEquals(40, AttackPlanner.troopDropCount(sweatSlot1, 40))
        assertEquals(30, AttackPlanner.troopDropCount(listOf("x30", "x30", "x44"), 40))
    }

    @Test
    fun anUnreliableBadgeFallsBackToTheFixedCount() {
        // The pale event card reads on one cut only: not enough to drop by.
        assertEquals(50, AttackPlanner.troopDropCount(listOf("x40"), 50))
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x44", "x44", "x40", "x40"), 40))
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x1", "x1"), 40))
        assertEquals(40, AttackPlanner.troopDropCount(listOf("L"), 40))
        assertEquals(40, AttackPlanner.troopDropCount(emptyList(), 40))
    }

    @Test
    fun aLoneCountUnderTheFixedOneIsTakenOnThePlainTray() {
        // Blood's two-hour run of 14 October: slot 1 read "x10" on one cut and nothing else, every
        // attack, and forty Valkyrie taps went on a card that held ten.
        assertEquals(10, AttackPlanner.troopDropCount(listOf("x10"), 40, loneLow = true))
        assertEquals(AttackPlanner.DropCounts(10, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.PLAIN, listOf("x10"), listOf("X", "X")))
        assertEquals(10, AttackPlanner.dropCounts(AttackPlanner.TrayLayout.AMBIGUOUS, listOf("x10"), listOf("x44", "ttX")).valkyries)
        // A lone count over the fixed one is still not enough, nor are two that disagree.
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x48"), 40, loneLow = true))
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x10", "x30"), 40, loneLow = true))
        assertEquals(40, AttackPlanner.dropCounts(AttackPlanner.TrayLayout.PLAIN, listOf("x48"), emptyList()).valkyries)
        // Under the badge floor it is no troop count at all.
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x4"), 40, loneLow = true))
        // Without the flag nothing changed, and the event layout does not pass it: a lone x10 in
        // slot 2 still drops the fixed 40 Valkyries, and the pale event card's lone x40 its 50.
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x10"), 40))
        assertEquals(AttackPlanner.DropCounts(40, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.EVENT, listOf("x50", "x50"), listOf("x10")))
        assertEquals(AttackPlanner.DropCounts(44, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.EVENT, listOf("x40"), listOf("x44", "x44")))
    }

    @Test
    fun aMisreadHighBadgeIsClampedToFiveAboveTheFixedCount() {
        assertEquals(45, AttackPlanner.troopDropCount(listOf("x440", "x440"), 40))
        assertEquals(55, AttackPlanner.troopDropCount(listOf("x400", "x400"), 50))
    }

    @Test
    fun aCountAboveTheFixedOneNeedsEveryCleanCutBehindIt() {
        // A 0/8 confusion on two cuts of an x40 card must not spill eight taps.
        assertEquals(40, AttackPlanner.troopDropCount(listOf("x48", "x48", "x40"), 40))
    }

    @Test
    fun eachTroopGroupDropsTheCountOfTheSlotItTaps() {
        assertEquals(AttackPlanner.DropCounts(44, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.PLAIN, bloodSlot1, listOf("L")))
        // The event tray: event card in slot 1, Valkyries in slot 2.
        assertEquals(AttackPlanner.DropCounts(44, 40),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.EVENT, listOf("x40", "x40"), listOf("x44", "x44")))
        // Sweat at 07:28 on 8 October, cards swapped: Valkyries x40 in slot 1, the event x50 in
        // slot 2 on one cut. Each group drops its slot's count, or the fallback for a lone cut.
        assertEquals(AttackPlanner.DropCounts(40, 40),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.EVENT, sweatSlot1, listOf("x50")))
        // An ambiguous tray deploys plain, so the Valkyries tap slot 1.
        assertEquals(AttackPlanner.DropCounts(40, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.AMBIGUOUS, sweatSlot1, listOf("x50", "ttX")))
        assertEquals(AttackPlanner.DropCounts(40, 50),
            AttackPlanner.dropCounts(AttackPlanner.TrayLayout.UNKNOWN, emptyList(), emptyList()))
    }

    @Test
    fun thePlanDropsTheCountsItIsGiven() {
        val profile = AttackPlanner.withEventCard(phoneTrayProfile())
        val plan = AttackPlanner.buildDeployment(profile, Random(5), withEventTroops = true,
            troopDrops = AttackPlanner.DropCounts(valkyries = 44, event = 30))
        assertEquals(44, plan.first { it.name == "valkyries" }.drops.size)
        assertEquals(30, plan.first { it.name == "event" }.drops.size)
        // Spells and heroes are untouched.
        assertEquals(5, plan.first { it.name == "rages" }.drops.size)
        assertEquals(3, plan.first { it.name == "king" }.drops.size)
    }

    @Test
    fun aSingleCleanCutInSlotTwoIsTheEventCard() {
        // Sweat at 07:28 and 07:36 UTC on 8 October: the Totem Thrower card sat in slot 2 and only
        // one of four cuts read it; the other three came back empty.
        assertEquals(AttackPlanner.TrayLayout.EVENT, AttackPlanner.trayLayout(sweatSlot1, listOf("x50")))
    }

    @Test
    fun aBadgeInBothSlotsIsTheEventLayoutWhateverTheCounts() {
        // Sweat's Kane event on 24 September: Kane x40 beside Valkyries x40.
        assertEquals(AttackPlanner.TrayLayout.EVENT, AttackPlanner.trayLayout(listOf("x40", "x40"), listOf("x40", "x40")))
        assertEquals(AttackPlanner.TrayLayout.EVENT, AttackPlanner.trayLayout(listOf("x50", "x50"), listOf("x44", "x44")))
        // The game swapped the event and Valkyrie cards between scout and battle on 25 September.
        assertEquals(AttackPlanner.TrayLayout.EVENT, AttackPlanner.trayLayout(listOf("x44", "x44"), listOf("x50", "x50")))
        // Fifty Valkyries with nothing badged beside them are not an event card.
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(listOf("x50", "x50"), emptyList()))
    }

    @Test
    fun aBadgeInSlotOneOnlyIsThePlainLayout() {
        // 8 October after the event: Sweat x40 and Blood x44, nothing badged in the king's slot.
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(sweatSlot1, emptyList()))
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(listOf("ttX", "X44", "X44", "X44"), emptyList()))
        // A siege machine's x1 is not a troop count.
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(listOf("x40", "x40"), listOf("x1", "x1")))
        // Blood at 09:04 and 09:11 on 8 October: the siege card in slot 2 read "L".
        assertEquals(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.trayLayout(listOf("ttX", "X44", "X44", "X44"), listOf("L")))
    }

    @Test
    fun aPaleEventCardInSlotOneWithTheValkyriesBadgedInSlotTwoIsTheEventLayout() {
        // Blood on 7 October: the pale event card in slot 1, Valkyries x44 in slot 2.
        assertEquals(AttackPlanner.TrayLayout.EVENT, AttackPlanner.trayLayout(emptyList(), listOf("x44", "x44")))
    }

    @Test
    fun contradictoryEvidenceIsAmbiguousAndDeploysThePlainTray() {
        // One cut sees a badge in slot 2 while another reads text that is no badge at all.
        assertEquals(AttackPlanner.TrayLayout.AMBIGUOUS, AttackPlanner.trayLayout(sweatSlot1, listOf("x50", "ttX")))
        // Slot 1 reads text that is no badge, and slot 2 carries one.
        assertEquals(AttackPlanner.TrayLayout.AMBIGUOUS, AttackPlanner.trayLayout(listOf("ttX"), listOf("x44", "x44")))
        val tray = AttackPlanner.forTray(phoneTrayProfile(), AttackPlanner.TrayLayout.AMBIGUOUS, eventMode = false)
        assertEquals(phoneTrayProfile(), tray.profile)
        assertFalse(tray.withEventTroops)
    }

    @Test
    fun anUnreadTrayIsUnknown() {
        assertEquals(AttackPlanner.TrayLayout.UNKNOWN, AttackPlanner.trayLayout(emptyList(), emptyList()))
        assertEquals(AttackPlanner.TrayLayout.UNKNOWN, AttackPlanner.trayLayout(listOf("ttX"), emptyList()))
    }

    @Test
    fun aPlainRunShiftsPastADetectedEventCardWithoutPlacingIt() {
        val tray = AttackPlanner.forTray(phoneTrayProfile(), AttackPlanner.TrayLayout.EVENT, eventMode = false)
        assertEquals(Point(399, 650), tray.profile.troopAnchors["valkyries"])
        assertFalse(tray.withEventTroops)
        assertEquals("shift_only", tray.decision)
        val plan = AttackPlanner.buildDeployment(tray.profile, Random(3), tray.withEventTroops)
        assertTrue(plan.none { it.name == "event" })
    }

    @Test
    fun anEventRunPlacesTheEventCardWhenItIsThere() {
        val tray = AttackPlanner.forTray(phoneTrayProfile(), AttackPlanner.TrayLayout.EVENT, eventMode = true)
        assertEquals(Point(302, 650), tray.profile.troopAnchors["event"])
        assertTrue(tray.withEventTroops)
        assertEquals("event_card", tray.decision)
        val plan = AttackPlanner.buildDeployment(tray.profile, Random(3), tray.withEventTroops)
        assertTrue(plan.any { it.name == "event" })
    }

    @Test
    fun anEventRunAfterTheCardIsGoneDeploysThePlainTray() {
        for (layout in listOf(AttackPlanner.TrayLayout.PLAIN, AttackPlanner.TrayLayout.UNKNOWN, AttackPlanner.TrayLayout.AMBIGUOUS)) {
            val tray = AttackPlanner.forTray(phoneTrayProfile(), layout, eventMode = true)
            assertEquals(Point(302, 650), tray.profile.troopAnchors["valkyries"])
            assertFalse(tray.withEventTroops)
            assertEquals("event_fallback_plain", tray.decision)
        }
    }

    @Test
    fun aPlainRunWithNoEventCardIsUnchanged() {
        val profile = phoneTrayProfile()
        val tray = AttackPlanner.forTray(profile, AttackPlanner.TrayLayout.PLAIN, eventMode = false)
        assertEquals(profile, tray.profile)
        assertFalse(tray.withEventTroops)
        assertEquals("plain", tray.decision)
    }

    @Test
    fun theOptionalJumpIsCheckedOnlyAfterTheHeroesAreDown() {
        val plan = AttackPlanner.buildDeployment(createValidProfile(), Random(42))

        // Both spells carry the flag; only the Jump is optional, so only its card check may be
        // left until the heroes are on the field.
        assertTrue(plan.first { it.name == "rages" }.isSpell)
        assertTrue(plan.first { it.name == "jumps" }.let { it.isSpell && it.isOptional })
        assertEquals(listOf("jumps"), plan.filter { it.isOptional }.map { it.name })

        // The jump itself still drops where it always did, ahead of the heroes - it is only
        // the read on its card that waits.
        val phases = AttackPlanner.phases(plan)
        val jumpPhase = phases.indexOfFirst { phase -> phase.any { it.name == "jumps" } }
        val heroPhase = phases.indexOfFirst { phase -> phase.any { it.isHero } }
        assertTrue(jumpPhase in 0 until heroPhase)
    }

    /**
     * The drops cover the outline evenly: placed on the run of edges by the edge they sit
     * nearest, no gap between neighbours (the corners included) is wider than two steps.
     */
    private fun assertSpreadEvenly(name: String, drops: List<Point>, profile: PhoneProfile) {
        val segments = profile.perimeterSegments
        val lengths = segments.map { (a, b) -> Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        val total = lengths.sum()
        val along = drops.map { drop ->
            val edge = segments.indices.minBy { distanceSquaredToSegment(drop, segments[it]) }
            lengths.take(edge).sum() + projectedPosition(drop, segments[edge]).coerceIn(0.0, 1.0) * lengths[edge]
        }.sorted()
        val gaps = along.zipWithNext { a, b -> b - a } + (along.first() + total - along.last())
        val step = total / drops.size
        assertTrue("$name leave a gap of ${gaps.max().toInt()}px on a ${step.toInt()}px step", gaps.max() <= step * 2)
    }

    @Test
    fun moreAndLongerLinesShareTheFortyByLength() {
        // Sweat's lines from 29 September: a short strip at the top left, then long lines down
        // the lower left, along the lower right, up the right and across the top right.
        val profile = createValidProfile().copy(
            perimeterSegments = listOf(
                Pair(Point(195, 215), Point(160, 280)),
                Pair(Point(150, 315), Point(270, 440)),
                Pair(Point(270, 440), Point(450, 555)),
                Pair(Point(1110, 565), Point(1240, 510)),
                Pair(Point(1240, 510), Point(1340, 440)),
                Pair(Point(1340, 440), Point(1430, 290)),
                Pair(Point(1000, 10), Point(1150, 100)),
                Pair(Point(1150, 100), Point(1367, 262))
            )
        )
        for (seed in 1..20) {
            val plan = AttackPlanner.buildDeployment(profile, Random(seed))
            val drops = plan.first { it.name == "valkyries" }.drops
            assertEquals(40, drops.size)
            for (drop in drops) {
                val nearest = profile.perimeterSegments.minOf { distanceSquaredToSegment(drop, it) }
                assertTrue("$drop is off every line", nearest <= 26.0 * 26.0)
            }
            // Every long line gets its share: one drop per 34 pixels or so, less a couple lost
            // to a neighbour at each joint.
            val step = profile.perimeterSegments.sumOf { lengthOf(it) } / 40
            val byLine = drops.groupBy { drop ->
                profile.perimeterSegments.indices.minBy { distanceSquaredToSegment(drop, profile.perimeterSegments[it]) }
            }
            for ((index, line) in profile.perimeterSegments.withIndex()) {
                val share = lengthOf(line) / step
                if (share < 3) continue
                val got = byLine[index].orEmpty().size
                assertTrue("Line $index got $got of its ${share.toInt()}", got >= share - 2.5)
            }
            assertEquals(5, plan.first { it.name == "rages" }.drops.size)
        }
    }

    private fun lengthOf(segment: Pair<Point, Point>): Double =
        Math.hypot((segment.second.x - segment.first.x).toDouble(), (segment.second.y - segment.first.y).toDouble())

    private fun projectedPosition(point: Point, segment: Pair<Point, Point>): Double {
        val dx = (segment.second.x - segment.first.x).toDouble()
        val dy = (segment.second.y - segment.first.y).toDouble()
        val px = (point.x - segment.first.x).toDouble()
        val py = (point.y - segment.first.y).toDouble()
        return (px * dx + py * dy) / (dx * dx + dy * dy)
    }

    private fun distanceSquaredToSegment(point: Point, segment: Pair<Point, Point>): Double {
        val position = projectedPosition(point, segment).coerceIn(0.0, 1.0)
        val nearestX = segment.first.x + (segment.second.x - segment.first.x) * position
        val nearestY = segment.first.y + (segment.second.y - segment.first.y) * position
        val dx = point.x - nearestX
        val dy = point.y - nearestY
        return dx * dx + dy * dy
    }
}
