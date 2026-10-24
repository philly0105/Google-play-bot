package local.clashfarmer.core

import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.RunNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayCheckTest {

    /**
     * Blood's calibrated tray from D4: one card every 97 px, anchors at the card centres. The
     * event card in front moves every one of these one card right on the scout.
     */
    private fun bloodProfile(): PhoneProfile = PhoneProfile(
        resolutionWidth = 1600,
        resolutionHeight = 720,
        viewport = Viewport(0, 0, 1600, 720),
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
            "queen" to Region(473, 600, 540, 702),
            "warden" to Region(570, 600, 637, 702),
            "champion" to Region(667, 600, 733, 702),
            "rages" to Region(775, 600, 842, 702),
            "jumps" to Region(872, 600, 938, 702)
        )
    )

    /** Badge band readings keyed by the band's left edge; anything else reads nothing. */
    private fun reads(vararg byLeft: Pair<Int, List<String>>): (Region) -> List<String> {
        val map = byLeft.toMap()
        return { band -> map[band.left].orEmpty() }
    }

    /**
     * Blood's event scout of 7 October: x50 event card, x44 Valkyries, Minion Prince where the
     * rages were, x11 clipped. Modelled from logged reads, since no event tray was ever logged on
     * Blood: slot 1 is the one cut of four that read the pale x50 (Sweat's slot 2, 8 October; the
     * reader drops the cuts that read nothing), slot 2 is Blood's Valkyries read on its plain
     * tray (8 and 14 October). The x1/x11 mix at 969 is modelled too: spell cuts are not logged,
     * only the null baseline they gave while the band stopped at the card edge.
     */
    private val bloodEventScout = reads(
        269 to listOf("X50"),
        366 to listOf("ttX", "X44", "X44", "X44"),
        969 to listOf("x1", "x11", "x1", "x11")
    )

    private val recalibrateAdvice = "Recalibrate this account's tray in Calibrate, then check again."
    private val missedEventAdvice =
        "If an event card is in the tray, do not recalibrate. The attack will read it the same way; report it instead."

    private fun TrayCheck.Report.slot(name: String) = slots.single { it.name == name }

    @Test
    fun theD4RagesAreaWithNoBadgeIsFlaggedNotASpell() {
        val report = TrayCheck.check(bloodProfile(), bloodEventScout) { roi -> if (roi.left == 872) 100.06 else 120.0 }

        assertEquals(AttackPlanner.TrayLayout.EVENT, report.layout)
        val rages = report.slot("rages")
        assertEquals(Region(872, 600, 939, 702), rages.roi)
        assertNull(rages.badge)
        assertEquals(TrayCheck.Level.BAD, rages.verdict.level)
        assertEquals("Rages: no count badge, so this is not a spell card (a hero or siege card has no badge)", rages.verdict.text)
        assertTrue(rages.wrong)
    }

    @Test
    fun valkyriesAtX44LookRight() {
        val report = TrayCheck.check(bloodProfile(), bloodEventScout) { 120.0 }

        val valkyries = report.slot("valkyries")
        assertEquals(Region(366, 600, 432, 702), valkyries.roi)
        assertEquals(44, valkyries.badge)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Valkyries: x44, looks right"), valkyries.verdict)
        assertNull(valkyries.tapNote)
        assertEquals(TrayCheck.Level.OK, report.slot("event").verdict.level)
        assertEquals(50, report.slot("event").badge)
    }

    @Test
    fun bloodsEventScoutHasOneWrongSlotAndOneUnclearOne() {
        val report = TrayCheck.check(bloodProfile(), bloodEventScout) { 120.0 }

        assertEquals(listOf("event", "valkyries", "king", "queen", "warden", "champion", "rages", "jumps"),
            report.slots.map { it.name })
        val jumps = report.slot("jumps")
        assertEquals(Region(969, 600, 1035, 702), jumps.roi)
        assertEquals(TrayCheck.Level.WARN, jumps.verdict.level)
        assertTrue(jumps.verdict.text.contains("did not read clearly"))
        assertEquals(listOf("x1", "x11", "x1", "x11"), jumps.raw)
        assertEquals(1, report.wrong)
        assertEquals(1, report.unsure)
        assertEquals(TrayCheck.Level.BAD, report.level)
        assertEquals("1 of 8 slots look wrong", report.headline)
        assertTrue(report.layoutLine.contains("one card to the right"))
        // The event was found, so only the Calibrate sentence applies.
        assertEquals(recalibrateAdvice, report.advice)
    }

    @Test
    fun bloodsWholeX11ThroughTheWiderBandLeavesOnlyTheRagesWrong() {
        // Modelled: with the band running to 1043, the x11 ending at about 1040 reads whole.
        val wider = reads(
            269 to listOf("X50"),
            366 to listOf("ttX", "X44", "X44", "X44"),
            969 to listOf("x11", "x11", "x11", "x11")
        )
        val report = TrayCheck.check(bloodProfile(), wider) { 120.0 }

        assertEquals(11, report.slot("jumps").badge)
        assertEquals(TrayCheck.Level.OK, report.slot("jumps").verdict.level)
        assertEquals(listOf("rages"), report.slots.filter { it.wrong }.map { it.name })
        assertEquals(0, report.unsure)
    }

    @Test
    fun aGreyCardIsEmptyOrAlreadyUsed() {
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Rages: grey card, empty or already used"),
            TrayCheck.verdict("rages", null, 30.0, isSpellSlot = true))
        assertEquals(TrayCheck.Level.WARN, TrayCheck.verdict("valkyries", null, 60.0, isSpellSlot = false).level)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Jumps: x0, empty or already used"),
            TrayCheck.verdict("jumps", 0, 140.0, isSpellSlot = true))

        val report = TrayCheck.check(bloodProfile(), bloodEventScout) { roi -> if (roi.left == 872) 20.0 else 120.0 }
        assertEquals(TrayCheck.Level.WARN, report.slot("rages").verdict.level)
        assertEquals(0, report.wrong)
        assertEquals("2 of 8 slots need a look", report.headline)
    }

    @Test
    fun aFrameThatIsNotTheScoutScreenIsRefusedInPlainWords() {
        val profile = bloodProfile()
        val home = TrayCheck.frameProblem(ScreenState.HOME, 1600, 720, profile)
        assertNotNull(home)
        assertTrue(home!!.contains("not a scout screen"))
        assertTrue(home.contains("the village"))
        assertTrue(home.contains("Find a match"))
        assertTrue(TrayCheck.frameProblem(ScreenState.UNKNOWN, 1600, 720, profile)!!.contains("not a scout screen"))

        val turning = TrayCheck.frameProblem(ScreenState.SCOUT, 720, 1600, profile)
        assertTrue(turning!!.contains("not a scout screen"))
        assertTrue(turning.contains("720x1600"))

        assertNull(TrayCheck.frameProblem(ScreenState.SCOUT, 1600, 720, profile))
    }

    @Test
    fun heroCardsHaveNoBadge() {
        // No badge cannot tell a hero from a siege card, so the sentence does not claim it.
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "King: no count badge: a hero card, or a siege card; check the picture"),
            TrayCheck.verdict("king", null, 120.0, isSpellSlot = false))
        val badged = TrayCheck.verdict("queen", 11, 120.0, isSpellSlot = false)
        assertEquals(TrayCheck.Level.BAD, badged.level)
        assertTrue(badged.text.contains("not a hero card"))
    }

    @Test
    fun anyBadgeOnAHeroSlotIsRedHoweverUnclear() {
        // More badge evidence must not give a milder colour: x0 and a mixed read are red like x1.
        assertEquals(TrayCheck.Level.BAD, TrayCheck.verdict("king", 1, 120.0, isSpellSlot = false).level)
        assertEquals(TrayCheck.Level.BAD, TrayCheck.verdict("king", 0, 120.0, isSpellSlot = false).level)
        val mixed = TrayCheck.slot(bloodProfile(), "king", Region(376, 600, 443, 702), listOf("x1", "x11", "x1", "x11"), 120.0)
        assertEquals(TrayCheck.Level.BAD, mixed.verdict.level)
        assertEquals("King: shows a count badge, so this is not a hero card (a hero card has no count badge)", mixed.verdict.text)
    }

    @Test
    fun aRealOneSpellCardReadsAsASpellNotAsNoBadge() {
        // badgeCount ignores counts under x5, so spell slots are counted from x0 up.
        val slot = TrayCheck.slot(bloodProfile(), "jumps", Region(872, 600, 938, 702), listOf("x1", "x1", "", "x1"), 120.0)
        assertEquals(1, slot.badge)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Jumps: x1, looks right"), slot.verdict)
    }

    @Test
    fun sweatsClippedReadsAsLoggedOn14October() {
        // Logged on Sweat's 14 October battles, with the band cut at the card edge: slot 1
        // "x4C,X4C,x4C,x4C" on 4 battles and "x4C,X4C,x4C,40" on 9 (2 more read nothing), slot 2
        // "(-" over the King. Modelled to match the logged spell baselines, whose cuts are not
        // logged: rages read 5, so "x5" on three cuts; jumps read null, so nothing (the x1's "1"
        // was cut to a 2 px sliver).
        for (valkyries in listOf(listOf("x4C", "X4C", "x4C", "x4C"), listOf("x4C", "X4C", "x4C", "40"))) {
            val clipped = reads(
                269 to valkyries,
                366 to listOf("(-"),
                376 to listOf("(-"),
                775 to listOf("x5", "x5", "x5")
            )
            val report = TrayCheck.check(bloodProfile(), clipped) { 120.0 }

            assertEquals(AttackPlanner.TrayLayout.UNKNOWN, report.layout)
            assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Valkyries: has a count badge, looks right"),
                report.slot("valkyries").verdict)
            assertEquals(TrayCheck.Level.OK, report.slot("king").verdict.level)
            assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Rages: x5, looks right"), report.slot("rages").verdict)
            // The clipped jump is why the band had to grow: the verdict cannot tell it from no badge.
            assertEquals(TrayCheck.Level.BAD, report.slot("jumps").verdict.level)
            assertFalse(report.blank)
        }
    }

    @Test
    fun sweatsScoutThroughTheWiderBandIsAllGreen() {
        // Modelled: the same tray with the band 8 px past each card, so the x40 and the x1 read whole.
        val wider = reads(
            269 to listOf("x40", "x40", "x40", "x40"),
            366 to listOf("(-"),
            376 to listOf("(-"),
            775 to listOf("x5", "x5", "x5"),
            872 to listOf("x1", "x1", "x1", "x1")
        )
        val report = TrayCheck.check(bloodProfile(), wider) { 120.0 }

        assertEquals(AttackPlanner.TrayLayout.PLAIN, report.layout)
        assertEquals(Region(775, 600, 842, 702), report.slot("rages").roi)
        assertFalse(report.slots.any { it.name == "event" })
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Valkyries: x40, looks right"), report.slot("valkyries").verdict)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "Jumps: x1, looks right"), report.slot("jumps").verdict)
        assertEquals(0, report.wrong)
        assertEquals(TrayCheck.Level.OK, report.level)
        assertEquals("All 7 slots look right", report.headline)
        assertNull(report.advice)
        assertEquals("The bot found no event card, so card areas are read where they were calibrated.", report.layoutLine)
    }

    @Test
    fun theCheckReadsEachBadgeThroughTheWiderBandAndEachCardThroughItsArea() {
        val bands = mutableListOf<Region>()
        val areas = mutableListOf<Region>()
        TrayCheck.check(bloodProfile(), { band -> bands += band; emptyList() }) { roi -> areas += roi; 120.0 }

        assertTrue(Region(872, 595, 946, 620) in bands)
        assertTrue(Region(269, 595, 343, 620) in bands)
        assertTrue(Region(872, 600, 938, 702) in areas)
        assertEquals(Region(872, 595, 946, 702), TrayCheck.pictureArea(Region(872, 600, 938, 702)))
    }

    @Test
    fun bloodsPlainTrayFlagsTheRagesAndCannotTellTheSiegeCardFromAHero() {
        // Logged on 14 October: slot 1 "trtX,X44,X44,X44", slot 2 nothing ("L,L" on two battles).
        // The King area covers the siege card, whose band read "L" on 8 October; its saturation is about 112. The
        // rages area covers the Minion Prince (105, baseline null). The x11 at 872 is modelled
        // whole through the wider band.
        val plain = reads(
            269 to listOf("trtX", "X44", "X44", "X44"),
            376 to listOf("L"),
            872 to listOf("x11", "x11", "x11", "x11")
        )
        val report = TrayCheck.check(bloodProfile(), plain) { roi ->
            when (roi.left) {
                376 -> 112.0
                775 -> 105.2
                else -> 120.0
            }
        }

        assertEquals(AttackPlanner.TrayLayout.PLAIN, report.layout)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.OK, "King: no count badge: a hero card, or a siege card; check the picture"),
            report.slot("king").verdict)
        assertEquals(TrayCheck.Level.BAD, report.slot("rages").verdict.level)
        assertEquals("1 of 7 slots look wrong", report.headline)
        assertEquals("$recalibrateAdvice $missedEventAdvice", report.advice)
        assertFalse(report.blank)
    }

    @Test
    fun aFrameOnWhichNoBadgeReadsIsAmberNotRed() {
        // 14 October, 08:36-08:47: every band read blank on both accounts at normal saturations.
        val report = TrayCheck.check(bloodProfile(), { listOf("") }) { 100.0 }

        assertTrue(report.blank)
        assertEquals(TrayCheck.Level.WARN, report.level)
        assertEquals("No count badge read at all; the picture may be dim or mid-change. Take the check again.", report.headline)
        assertEquals(0, report.wrong)
        assertNull(report.advice)
        assertTrue(report.slots.none { it.verdict.level == TrayCheck.Level.BAD })
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Valkyries: no count badge read on this picture"),
            report.slot("valkyries").verdict)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Jumps: no count badge read on this picture"),
            report.slot("jumps").verdict)
    }

    @Test
    fun aMisreadBadgeOnAHeroSlotIsNotAHero() {
        val slot = TrayCheck.slot(bloodProfile(), "king", Region(376, 600, 443, 702), listOf("x4C", "x4C"), 120.0)
        assertEquals(TrayCheck.Level.BAD, slot.verdict.level)
        assertEquals("King: shows a count badge, so this is not a hero card (a hero card has no count badge)", slot.verdict.text)
    }

    @Test
    fun aSiegeCardsCleanX1IsNoTroopBadge() {
        val slot = TrayCheck.slot(bloodProfile(), "valkyries", Region(269, 600, 335, 702), listOf("x1", "x1"), 120.0)
        assertEquals(TrayCheck.Level.BAD, slot.verdict.level)
        assertTrue(slot.verdict.text.contains("not a troop card"))
    }

    @Test
    fun anEventCardWhoseBadgeDoesNotReadIsAmberNotRed() {
        // The full x50 event card measured 58.2 on Blood in October, under the grey line.
        val slot = TrayCheck.slot(bloodProfile(), "event", Region(269, 600, 335, 702), listOf("", "", "", ""), 58.2)
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Event: the badge on this pale card did not read, so check the picture"),
            slot.verdict)
        assertFalse(slot.wrong)
        // A misread badge on it is still a badge, not a grey card.
        assertEquals(TrayCheck.Level.OK, TrayCheck.slot(bloodProfile(), "event", Region(269, 600, 335, 702), listOf("x5C"), 54.1).verdict.level)
        // Cuts that read counts which disagree did read, so the badge is unclear rather than unread.
        assertEquals(TrayCheck.Verdict(TrayCheck.Level.WARN, "Event: the count badge did not read clearly, so check the picture"),
            TrayCheck.slot(bloodProfile(), "event", Region(269, 600, 335, 702), listOf("x50", "x5"), 58.2).verdict)
    }

    @Test
    fun aTrayWithoutTheHeroesToMeasureItIsReadUnshifted() {
        val profile = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors - "queen") }
        val report = TrayCheck.check(profile, bloodEventScout) { 120.0 }

        assertEquals(AttackPlanner.TrayLayout.UNKNOWN, report.layout)
        assertEquals(Region(775, 600, 842, 702), report.slot("rages").roi)
        // Nothing was read for the layout, so the line does not say the badges did not read.
        assertTrue(report.layoutLine.contains("cannot look for an event card"))
    }

    @Test
    fun anEventTrayThatCannotBeShiftedSaysSo() {
        // A Valkyries card area with no Valkyries tap: the layout reads, but the shift fails.
        val profile = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors - "valkyries") }
        val report = TrayCheck.check(profile, bloodEventScout) { 120.0 }

        assertEquals(AttackPlanner.TrayLayout.EVENT, report.layout)
        assertEquals(Region(775, 600, 842, 702), report.slot("rages").roi)
        assertTrue(report.layoutLine.contains("could not be moved one card right"))
        assertFalse(report.layoutLine.contains("every card area is read one card to the right"))
    }

    @Test
    fun aSavedTapOutsideItsCardAreaIsReported() {
        // Calibration moved the rages tap one card right but left the card area where it was.
        val profile = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors + ("rages" to Point(905, 650))) }
        val slot = TrayCheck.slot(profile, "rages", Region(775, 600, 842, 702), listOf("x2", "x2", "x2", "x2"), 120.0)

        assertEquals(TrayCheck.Level.OK, slot.verdict.level)
        assertTrue(slot.wrong)
        assertTrue(slot.tapNote!!.contains("outside this card area"))
        assertTrue(slot.tapNote!!.endsWith(
            "Tap the middle of the right card again in Calibrate; the card area moves with the tap."))
        assertNull(TrayCheck.slot(bloodProfile(), "rages", Region(775, 600, 842, 702), listOf("x2"), 120.0).tapNote)
        // A tap above or below the card misses it as surely as one beside it.
        val low = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors + ("rages" to Point(808, 710))) }
        assertNotNull(TrayCheck.slot(low, "rages", Region(775, 600, 842, 702), listOf("x2"), 120.0).tapNote)
    }

    @Test
    fun aTrayWrongOnlyThroughATapNoteSaysRecalibrate() {
        // Every card reads right, but the rages tap sits on the jumps card. Tapping the rages card
        // again in Calibrate moves its tap and area together, which clears it.
        val profile = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors + ("rages" to Point(905, 650))) }
        val wider = reads(
            269 to listOf("x40", "x40", "x40", "x40"),
            366 to listOf("(-"),
            376 to listOf("(-"),
            775 to listOf("x5", "x5", "x5"),
            872 to listOf("x1", "x1", "x1", "x1")
        )
        val report = TrayCheck.check(profile, wider) { 120.0 }

        assertEquals(AttackPlanner.TrayLayout.PLAIN, report.layout)
        assertTrue(report.slots.none { it.verdict.level == TrayCheck.Level.BAD })
        assertEquals("1 of 7 slots look wrong", report.headline)
        assertEquals("$recalibrateAdvice $missedEventAdvice", report.advice)

        // A wrong card beside the tap note gets the same advice: Calibrate moves the card area too.
        val ragesUnread = reads(269 to listOf("x40", "x40", "x40", "x40"), 366 to listOf("(-"), 376 to listOf("(-"))
        assertEquals("$recalibrateAdvice $missedEventAdvice", TrayCheck.check(profile, ragesUnread) { 120.0 }.advice)
    }

    @Test
    fun anAccountWithoutCardAreasIsRefused() {
        assertTrue(TrayCheck.refusal(null)!!.contains("not calibrated"))
        assertTrue(TrayCheck.refusal(bloodProfile().copy(troopRois = emptyMap()))!!.contains("nothing to check"))
        assertNull(TrayCheck.refusal(bloodProfile()))
    }

    /**
     * Blood's scout of 14 October with its new army (accept/56-scout.png): a x10 troop where the
     * Valkyries were calibrated, and Giant x1, Yeti x1 and two x2 cards where the King, Queen,
     * Warden and Champion were. Slots 1 and 2 as logged on all 26 attacks of the two-hour run;
     * the hero bands are modelled, since no run read them.
     */
    private val bloodNewArmyScout = reads(
        269 to listOf("x10"),
        366 to listOf("X", "X"),
        376 to listOf("x1", "x1", "x1"),
        473 to listOf("x1", "X1", "x1", "x1"),
        570 to listOf("x2", "x2", "x2", "x2"),
        667 to listOf("x2", "x2", "x2", "x2")
    )

    /** The same scout should the bands lose the Giant's and the Yeti's thin 1, as slot 2's band lost it. */
    private val bloodNewArmyThin = reads(
        269 to listOf("x10"), 366 to listOf("X", "X"), 376 to listOf("X", "X"), 473 to listOf("X"),
        570 to listOf("x2", "x2", "x2", "x2"), 667 to listOf("x2", "x2")
    )

    /** The hero slots judged as an attack judges them: slots 1 and 2 pick the tray it deploys. */
    private fun attackHeroes(profile: PhoneProfile, read: (Region) -> List<String>): TrayCheck.Report {
        val bands = AttackPlanner.trayBadgeRegions(profile)!!
        val layout = AttackPlanner.trayLayout(read(bands[0]), read(bands[1]))
        val tray = AttackPlanner.forTray(profile, layout, eventMode = false)
        return TrayCheck.heroSlots(tray.profile, layout, read) { 120.0 }
    }

    /** The same slots one card right, as an attack judges them when a tray not read as an event tray shows a count. */
    private fun heroesOneCardRight(profile: PhoneProfile, read: (Region) -> List<String>): TrayCheck.Report {
        val bands = AttackPlanner.trayBadgeRegions(profile)!!
        val layout = AttackPlanner.trayLayout(read(bands[0]), read(bands[1]))
        return TrayCheck.heroSlots(AttackPlanner.withEventCard(profile), layout, read) { 120.0 }
    }

    @Test
    fun bloodsNewArmyIsRefusedAtItsFirstCountedHeroSlot() {
        val report = attackHeroes(bloodProfile(), bloodNewArmyScout)

        assertEquals(AttackPlanner.TrayLayout.PLAIN, report.layout)
        assertEquals(listOf("king", "queen", "warden", "champion"), report.slots.map { it.name })
        assertEquals(4, report.wrong)
        val wrong = TrayCheck.wrongHero(report)!!
        assertEquals("king", wrong.name)
        assertEquals(1, wrong.badge)
        assertEquals(listOf("x1", "x1", "x1"), wrong.raw)
        assertEquals("Blood's army no longer matches its tray setup: the King's card shows a troop count. " +
            "Check the tray in Setup, then recalibrate it.", TrayCheck.trayMismatch("Blood", wrong))
        // Should the band lose the Giant's and the Yeti's thin 1, the x2 cards still show counts.
        assertEquals("warden", TrayCheck.wrongHero(attackHeroes(bloodProfile(), bloodNewArmyThin))!!.name)
    }

    @Test
    fun anEventTrayWhoseEventCardDidNotReadGoesAhead() {
        // Blood's event tray of 7 October with its pale event card reading as junk and the
        // Valkyries on one cut: the layout is unclear, the attack deploys plain, and the King's
        // slot sits on the Valkyries' x44. One card right the heroes show no count.
        val missedEvent = reads(269 to listOf("trt"), 366 to listOf("x44"), 376 to listOf("x44", "x44"))
        val plain = attackHeroes(bloodProfile(), missedEvent)
        assertEquals(AttackPlanner.TrayLayout.AMBIGUOUS, plain.layout)
        assertEquals("king", TrayCheck.wrongHero(plain)!!.name)
        assertNull(TrayCheck.wrongHero(plain, heroesOneCardRight(bloodProfile(), missedEvent)))

        // Blood's new army shows counts one card right too, so it is still refused, at the slot
        // the attack would have deployed over.
        val changed = TrayCheck.wrongHero(attackHeroes(bloodProfile(), bloodNewArmyScout),
            heroesOneCardRight(bloodProfile(), bloodNewArmyScout))
        assertEquals("king", changed!!.name)
        val thin = TrayCheck.wrongHero(attackHeroes(bloodProfile(), bloodNewArmyThin),
            heroesOneCardRight(bloodProfile(), bloodNewArmyThin))
        assertEquals("warden", thin!!.name)
    }

    @Test
    fun aTrayWhoseHeroSlotsShowNoCountGoesAhead() {
        // Sweat's scout (clash-18647/08-scout.png): slots 1 and 2 as sweat3-log has them, and each
        // hero card's top reading as the King's did through slot 2.
        val sweat = reads(269 to listOf("x4C", "X4C", "x4C", "40"), 366 to listOf("(-"), 376 to listOf("(-"),
            473 to listOf("(-"), 570 to listOf("(-"), 667 to listOf("(-"))
        val sweatHeroes = attackHeroes(bloodProfile(), sweat)
        assertNull(TrayCheck.wrongHero(sweatHeroes))
        assertTrue(sweatHeroes.slots.all { it.verdict.level == TrayCheck.Level.OK })
        // Blood's morning tray (accept/29, 30 and 36): Valkyries x44, its siege card on the King's
        // slot (its band reads "L") and the real heroes on the next three, as blood6-log has slots 1 and 2.
        val bloodMorning = reads(269 to listOf("trtX", "X44", "X44", "X44"), 366 to listOf("L", "L"), 376 to listOf("L"))
        assertNull(TrayCheck.wrongHero(attackHeroes(bloodProfile(), bloodMorning)))
        // A frame on which nothing reads deploys as before.
        val nothing = attackHeroes(bloodProfile()) { emptyList() }
        assertNull(TrayCheck.wrongHero(nothing))
        assertTrue(nothing.blank)
    }

    @Test
    fun anEventTrayIsJudgedWhereTheAttackDeploysIt() {
        // Every card one card right: the King's card is read at 473, not over the Valkyries at 376.
        val report = attackHeroes(bloodProfile(), reads(
            269 to listOf("X50"), 366 to listOf("ttX", "X44", "X44", "X44"), 376 to listOf("x44", "x44")))

        assertEquals(AttackPlanner.TrayLayout.EVENT, report.layout)
        assertEquals(Region(473, 600, 540, 702), report.slot("king").roi)
        assertNull(TrayCheck.wrongHero(report))
    }

    @Test
    fun theTrayStopsSayWhatToDoAndAreNotRetried() {
        val champion = TrayCheck.slot(bloodProfile(), "champion", Region(667, 600, 733, 702), listOf("x2"), 120.0)
        assertEquals("This account's army no longer matches its tray setup: the Champion's card shows a troop count. " +
            "Check the tray in Setup, then recalibrate it.", TrayCheck.trayMismatch(null, champion))
        assertTrue(TrayCheck.trayMismatch(" ", champion).startsWith("This account's "))
        assertEquals("Sweat's heroes did not leave the tray on 2 attacks in a row, so the tray setup may be wrong. " +
            "Check the tray in Setup, then recalibrate it.", TrayCheck.heroesStayed("Sweat", 2))
        val reasons = listOf(
            TrayCheck.trayMismatch("Blood", champion),
            TrayCheck.trayMismatch("Blood", champion) + " End Battle was not found, so the scout was left on screen.",
            TrayCheck.heroesStayed("Blood", 2)
        )
        for (reason in reasons) {
            // A restart would only meet the same tray again.
            assertFalse(reason, RecoveryPolicy.isRecoverable(reason))
            assertEquals(reason, RunNotice.Kind.NEEDS_YOU, RunNotice.stopped("Blood", reason).kind)
            assertFalse(reason, reason.contains('_'))
        }
    }

    @Test
    fun everySentenceIsPlainWords() {
        assertEquals("Troop 2", TrayCheck.label("troop_2"))
        val texts = mutableListOf<String>()
        for (name in listOf("troop_1", "troop_2", "flame_flinger", "king", "rages", "valkyries", "event")) {
            for (badge in listOf(null, 0, 3, 44)) {
                for (flags in listOf(false to false, true to false, false to true)) {
                    for (saturation in listOf(20.0, 120.0)) {
                        texts += TrayCheck.verdict(name, badge, saturation, TrayCheck.isSpellSlot(name), flags.first, flags.second).text
                    }
                }
            }
        }
        val report = TrayCheck.check(bloodProfile(), bloodEventScout) { 120.0 }
        texts += report.headline
        texts += report.layoutLine
        texts += listOfNotNull(report.advice)
        texts += report.slots.mapNotNull { it.tapNote }
        val blank = TrayCheck.check(bloodProfile(), { listOf("") }) { 100.0 }
        texts += blank.headline
        texts += blank.slots.map { it.verdict.text }
        val unmeasured = bloodProfile().let { p -> p.copy(troopAnchors = p.troopAnchors - "queen") }
        texts += TrayCheck.check(unmeasured, bloodEventScout) { 120.0 }.layoutLine
        texts += ScreenState.values().mapNotNull { TrayCheck.frameProblem(it, 1600, 720, bloodProfile()) }
        texts += listOfNotNull(TrayCheck.refusal(null), TrayCheck.refusal(bloodProfile().copy(troopRois = emptyMap())))
        for (layout in AttackPlanner.TrayLayout.values()) {
            val plain = TrayCheck.Report(layout, report.slots)
            texts += plain.layoutLine
            texts += listOfNotNull(plain.advice)
        }

        assertTrue(texts.isNotEmpty())
        texts.forEach { assertFalse("underscore in: $it", it.contains('_')) }
    }
}
