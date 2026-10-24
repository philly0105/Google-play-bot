package local.clashfarmer.core

import local.clashfarmer.core.PopupGuard.Verdict
import local.clashfarmer.recognition.Observation
import local.clashfarmer.recognition.RecognizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGuardTest {

    private val screen = Viewport(0, 0, 1600, 720)

    /** A box of [text] centred on ([x], [y]), the way the reader reports one. */
    private fun box(text: String, x: Int, y: Int) = RecognizedBox(text, x - 60, y - 15, x + 60, y + 15)

    private fun inspect(vararg boxes: RecognizedBox) =
        PopupGuard.inspect(boxes.toList(), screen, boxes.joinToString(" ") { it.text })

    @Test fun theIdleDialogIsAServerDialog() {
        assertEquals(
            Verdict.ServerDialog("idle"),
            inspect(box("Anyone there?", 800, 300), box("Reload game", 790, 648))
        )
    }

    @Test fun aLostServerIsAServerDialog() {
        // The dialog that ended the unattended run at 06:07 on 22 September.
        assertEquals(
            Verdict.ServerDialog("connection_lost"),
            inspect(box("You have lost connection with the server", 800, 330), box("Try again", 790, 648))
        )
    }

    @Test fun theTakeoverIsLeftToItsOwnWait() {
        assertEquals(
            Verdict.None,
            inspect(
                box("Connection lost", 800, 250),
                box("Another device is connecting to this Village.", 800, 330),
                box("Reload", 790, 648)
            )
        )
    }

    @Test fun aPlayerNameAtTheTopIsNotADialog() {
        // 04:32 on 21 September: a scout frame whose defender is called "kogyi 23 + TRY again".
        assertEquals(Verdict.None, inspect(box("kogyi 23 + TRY again", 150, 40), box("Next", 1440, 495)))
        assertEquals(Verdict.None, inspect(box("Disconnected", 200, 30)))
        assertEquals(Verdict.None, inspect(box("Maintenance", 180, 35)))
        assertEquals(Verdict.None, inspect(box("Update available", 120, 30)))
    }

    @Test fun maintenanceIsRecognisedInTheDialog() {
        assertEquals(
            Verdict.Maintenance,
            inspect(box("Maintenance break", 800, 250), box("We'll be back soon!", 800, 340))
        )
    }

    @Test fun aForcedUpdateIsRecognisedAndNeverTapped() {
        val verdict = inspect(
            box("Update available", 800, 220),
            box("A new version of Clash of Clans is available", 800, 330),
            box("Update", 800, 600)
        )
        assertEquals(Verdict.UpdateRequired, verdict)
        assertEquals(
            Verdict.UpdateRequired,
            inspect(box("New version required", 800, 260), box("Update", 800, 600))
        )
    }

    @Test fun thePhonesOwnUpdateBannerIsWaitedOut() {
        // 17:05 on 2 October: the Samsung heads-up covered Return Home, which read as "URN".
        val banner = inspect(box("Software update ready to install.", 800, 40), box("URN", 800, 606))
        assertEquals(Verdict.Wait("samsung_update_banner"), banner)
        // Lower down, where a dialog would be, it is still not Clash asking for an update.
        val low = inspect(box("Software update ready to install.", 800, 200))
        assertFalse(low == Verdict.UpdateRequired)
    }

    @Test fun thePrivacyNoticeIsClosedWithOkNeverMoreInformation() {
        // audit-evidence/2026-09-22-privacy-dialog.png
        assertEquals(
            Verdict.Dismiss("privacy_notice", Point(1000, 600)),
            inspect(
                box("Privacy Information", 800, 160),
                box("Do not sell or share my personal information", 800, 350),
                box("More information", 600, 600),
                box("OK", 1000, 600)
            )
        )
    }

    @Test fun theLeaguePromotionIsClosedWithItsX() {
        // 05:27 on 21 September.
        assertEquals(
            Verdict.Dismiss("league_promotion", Point(1250, 110)),
            inspect(
                box("League Overview", 800, 120),
                box("You've been PROMOTED!", 800, 300),
                box("Titan", 800, 380),
                box("X", 1250, 110)
            )
        )
    }

    @Test fun anEventIntroIsClosedWithItsCloseWord() {
        assertEquals(
            Verdict.Dismiss("event_intro", Point(800, 620)),
            inspect(box("Portal Panic event", 800, 200), box("Close", 800, 620))
        )
    }

    @Test fun aRewardWindowIsClosedWithOkay() {
        assertEquals(
            Verdict.Dismiss("reward", Point(800, 620)),
            inspect(box("Raid Weekend Rewards", 800, 180), box("Okay", 800, 620))
        )
    }

    @Test fun aPopupThatSpendsGemsIsOnlyEverClosedByItsX() {
        val okayOnly = inspect(
            box("Special event offer", 800, 200),
            box("500 Gems", 800, 400),
            box("Okay", 800, 620)
        )
        assertEquals(Verdict.RefusedPremium("event_intro"), okayOnly)
        val withX = inspect(
            box("Special event offer", 800, 200),
            box("500 Gems", 800, 400),
            box("Okay", 800, 620),
            box("X", 1260, 120)
        )
        assertEquals(Verdict.Dismiss("event_intro", Point(1260, 120)), withX)
    }

    @Test fun aButtonWithANumberOrAPriceIsNeverTapped() {
        val verdict = inspect(box("Treasure Hunt event", 800, 200), box("Okay 50", 800, 620), box("$4.99", 1000, 620))
        assertFalse(verdict is Verdict.Dismiss)
    }

    /**
     * The gem store of 05:31 UTC on 15 October (failure-053116.png): its words as logged, placed as
     * the frame draws them, and its red X in the corner a popup's X is read in.
     */
    private val store = listOf(
        box("Treasure", 800, 45), box("Gems", 640, 110), box("Resources", 960, 110),
        box("Pocketful of Gems", 200, 230), box("80", 200, 420), box("$0.99", 200, 600),
        box("Pile of Gems", 500, 230), box("500", 500, 420), box("$4.99", 500, 600),
        box("Bag of Gems", 800, 230), box("1200", 800, 420), box("$9.99", 800, 600),
        box("Sack of Gems", 1100, 230), box("2500", 1100, 420), box("$19.99", 1100, 600),
        box("Box of Gems", 1400, 230), box("6500", 1400, 420), box("$49.99", 1400, 600),
        box("18 555 155", 1405, 40), box("22 110 583", 1408, 108), box("470 000", 1426, 173), box("1 878", 1441, 238),
        box("X", 1480, 50)
    )

    @Test fun theStoreIsAShopNotADismiss() {
        assertEquals(Verdict.Shop, inspect(*store.toTypedArray()))
        // Its prices alone, or its titles alone.
        assertEquals(Verdict.Shop, inspect(*store.filterNot { it.text.startsWith("$") }.toTypedArray()))
        assertEquals(Verdict.Shop, inspect(*store.filterNot { it.text.contains("Gems") }.toTypedArray()))
        // A known popup that sells for money is not closed by its X any more: Back leaves it.
        val offer = inspect(box("Special event offer", 800, 200), box("$4.99", 800, 400), box("X", 1260, 120))
        assertEquals(Verdict.Shop, offer)
        // A server or maintenance dialog with a price on it is still that dialog.
        assertEquals(
            Verdict.ServerDialog("connection_lost"),
            inspect(box("You have lost connection with the server", 800, 330), box("$4.99", 800, 420), box("Try again", 790, 648))
        )
        assertEquals(Verdict.Maintenance, inspect(box("Maintenance break", 800, 250), box("$4.99", 800, 420)))
    }

    @Test fun aKnownPopupWithNothingSafeToTapIsWaitedOn() {
        assertEquals(
            Verdict.Wait("reward"),
            inspect(box("Claim Reward", 800, 300), box("Claim", 800, 620))
        )
    }

    @Test fun anUnknownPopupFallsThrough() {
        assertEquals(Verdict.None, inspect(box("Builder Base news", 800, 200), box("Okay", 800, 620)))
        // A loading-screen tip; "attacking" is not a dialog word.
        assertEquals(Verdict.None, inspect(box("Tip: aggressive attacking wins loot", 800, 650)))
    }

    /** Blood, 09:33 UTC on 8 October: the dialog a wall confirmation opened with no builder free. */
    private val buildersBusy = listOf(
        RecognizedBox("All builders are", 640, 190, 960, 222),
        RecognizedBox("busy!", 745, 225, 850, 258),
        RecognizedBox("Complete the previous building and free up a", 540, 308, 1055, 335),
        RecognizedBox("builder?", 750, 337, 845, 362),
        RecognizedBox("212", 740, 430, 812, 475)
    )

    @Test fun theBuildersBusyDialogIsAGemPopupClosedOnlyByItsX() {
        val x = RecognizedBox("X", 1040, 195, 1082, 238)
        assertEquals(Verdict.Dismiss("builders_busy", x.center), inspect(*(buildersBusy + x).toTypedArray()))
        // Without its X read, the 212-gem button is the only thing on it: nothing is tapped.
        assertEquals(Verdict.RefusedPremium("builders_busy"), inspect(*buildersBusy.toTypedArray()))
        // An "Okay" read near the gem price is still never pressed.
        val okay = inspect(*(buildersBusy + box("Okay", 800, 620)).toTypedArray())
        assertEquals(Verdict.RefusedPremium("builders_busy"), okay)
    }

    @Test fun anXOutsideThePopupCornerIsNotAClose() {
        val verdict = inspect(box("League Overview", 800, 160), box("x", 300, 600))
        assertTrue(verdict is Verdict.Wait)
    }

    // The event intro of 8 October: a character on the left covers Attack!, a speech bubble at
    // x 370-705, y 405-530 holds one or three lines centred at x 538, and Shop stays readable.
    // audit-evidence/2026-10-08-identity-check-home.png and -event-intro-2.png .. -8.png.

    /** Sweat's village HUD as read at 08:05:04 UTC, everything but the bubble. */
    private val sweatHud = listOf(
        box("239", 90, 18), box("Sweat", 180, 18), box("Booso", 300, 20),
        box("0/1", 660, 40), box("O/6", 818, 40), box("NONe", 987, 40),
        box("Request", 797, 220), box("WAR", 790, 283), box("Upglrade", 1185, 300),
        box("18 775 984", 1405, 40), box("25 598 784", 1408, 108),
        box("184 853", 1428, 173), box("2 018", 1441, 238),
        box("SHOP", 1463, 679), box("9", 1514, 582)
    )

    /** The bubble's lines, top to bottom, the way the reader returns them: one box per line. */
    private fun bubble(vararg lines: String): List<RecognizedBox> =
        lines.mapIndexed { i, line -> box(line, 538, 450 + i * 19) }

    private fun decide(boxes: List<RecognizedBox>) = PopupGuard.decide(boxes, screen)

    @Test fun theFirstBubbleIsAdvancedByTappingIt() {
        // The real reading: run_stopped at 08:05:04 UTC, "Cannot verify account identity".
        val lines = bubble("Y-ouCH! THat daRN PoRtal", "PendaNt Has MasHed up", "OUR WORIds.")
        val verdict = decide(sweatHud + lines)
        assertEquals(
            Verdict.AdvanceDialogue("Y-ouCH! THat daRN PoRtal PendaNt Has MasHed up OUR WORIds.", Point(538, 469)),
            verdict
        )
        // The rule needs nothing the joined reading lacks: the whole logged text as one line.
        val joined = "Y-ouCH! THat daRN PoRtal PendaNt Has MasHed up OUR WORIds."
        assertTrue(decide(sweatHud + box(joined, 538, 469)) is Verdict.AdvanceDialogue)
    }

    @Test fun everyLaterBubbleIsAdvancedToo() {
        // Bubbles 2-8 as drawn; none reached the reader on the phone because a person tapped through.
        val bubbles = listOf(
            bubble("Sour Elixir portals are", "everywhere! It's going to", "take an army to clean this", "mess."),
            bubble("Wait, that's a good idea!", "You, Chief, time to mop up."),
            bubble("Collect Sour Elixir in", "battle and fill my Sour", "Elixir Fountain."),
            // "rewards" alone used to make this the reward popup, waited on and never tapped.
            bubble("You'll unlock rewards,", "temporary troops, and", "Rift Medals."),
            // "Shop" inside the bubble is not the village's Shop button.
            bubble("Spend Rift Medals in the", "Trader Shop on items..."),
            bubble("And even unlock the", "Portal Pendant", "Equipment for yourself."),
            bubble("That Sour Elixir isn't", "going to clean itself. Hop", "to it!")
        )
        for (lines in bubbles) {
            val verdict = decide(sweatHud + lines)
            assertTrue("$lines -> $verdict", verdict is Verdict.AdvanceDialogue)
            val tap = (verdict as Verdict.AdvanceDialogue).tap
            assertTrue("tap $tap outside the bubble", tap.x in 370..705 && tap.y in 405..530)
        }
    }

    @Test fun theIntroAfterAnAccountSwitchIsAdvanced() {
        // Blood at 08:11, audit-evidence/2026-10-08-blood-after-switch.png.
        val bloodHud = listOf(
            box("Request", 256, 251), box("6d 10H", 440, 122), box("20H 49M", 500, 165),
            box("1H 54M", 985, 40), box("23 429 258", 1405, 40), box("20 778 549", 1408, 108),
            box("470 000", 1422, 173), box("1 964", 1441, 238), box("SHOP", 1463, 679), box("3", 1514, 582)
        )
        val verdict = decide(bloodHud + bubble("Y-ouCH! THat daRN PoRtal", "PendaNt Has MasHed up", "OUR WORIds."))
        assertTrue(verdict is Verdict.AdvanceDialogue)
    }

    @Test fun theVillageWithItsAttackButtonIsNotADialogue() {
        // audit-evidence/2026-10-08-event-intro-9.png, the village once the intro was over: the
        // builder hut's NEW and Boost labels sit where the bubble was.
        val home = sweatHud + listOf(
            box("NEW", 395, 477), box("Boost", 296, 542), box("Attack!", 140, 680),
            box("23d 23h", 270, 686), box("6d 20H", 673, 628)
        )
        assertFalse(decide(home) is Verdict.AdvanceDialogue)
        // A clan chat line in the same place is a sentence, but Attack! is in its corner.
        val chat = sweatHud + box("Attack!", 140, 680) + box("Blood: anyone up for war tonight", 520, 470)
        assertFalse(decide(chat) is Verdict.AdvanceDialogue)
    }

    @Test fun aBuilderLabelWhereTheBubbleWasIsNotADialogue() {
        // Attack! hidden by something else: single-word labels are still not a bubble.
        val labels = sweatHud + listOf(box("NEW", 395, 477), box("Upgrade", 540, 460), box("1d 1H", 520, 500))
        assertFalse(decide(labels) is Verdict.AdvanceDialogue)
    }

    @Test fun aBubbleWithoutTheVillageBehindItIsNotThisDialogue() {
        // No Shop corner: the loading screen, a battle, or some panel. Not tapped.
        val verdict = decide(bubble("Y-ouCH! THat daRN PoRtal", "PendaNt Has MasHed up", "OUR WORIds."))
        assertFalse(verdict is Verdict.AdvanceDialogue)
    }

    @Test fun theAttackMenuInCooldownIsNotADialogue() {
        // 09:40:46 UTC on 23 September, audit-evidence/2026-09-23-third-40-menu-stop.png. The run
        // logged "Multiplayer Battle Attack available in: 195 Star Bonus Single PlayeR Next in: 1oh
        // 53m Rank 2 18719 Ranked Battle PRactice Attacks CoMpleted! 12/12 Time Left: 4d 19h SH 14M
        // 940 219 218 346 470 0O0 967 SHOP". The Ranked card's lines fall in the bubble band, Shop
        // reads, and the cooldown line stands where Attack! would not be read.
        val menu = listOf(
            box("Multiplayer Battle", 267, 460), box("Attack available in:", 268, 514), box("195", 268, 550),
            box("Star Bonus", 203, 675), box("Single PlayeR", 480, 56), box("Next in: 1oh 53m", 398, 675),
            box("Rank", 500, 198), box("2", 500, 224), box("18719", 620, 150),
            box("Ranked Battle", 618, 461), box("PRactice", 737, 56), box("Attacks CoMpleted!", 619, 512),
            box("12/12", 620, 555), box("Time Left:", 556, 596), box("4d 19h", 724, 596),
            box("SH 14M", 985, 40), box("940 219", 1428, 40), box("218 346", 1433, 108),
            box("470 0O0", 1426, 173), box("967", 1455, 238), box("SHOP", 1468, 679)
        )
        assertFalse(decide(menu) is Verdict.AdvanceDialogue)
        // The card's lines alone, with the words that name the menu read as nothing else would.
        val cardOnly = listOf(
            box("Ranked Battle", 618, 461), box("Attacks CoMpleted!", 619, 512), box("SHOP", 1468, 679)
        )
        assertFalse(decide(cardOnly) is Verdict.AdvanceDialogue)
    }

    @Test fun theAttackMenuWithJoinTournamentIsNotADialogue() {
        // audit-evidence/2026-09-23-exp-02-menu.png; the same menu was logged as "Battle FiNd a MatcH
        // 1500 Star Bonus Next in: 14m 27s Ractice RaNked Battle Join ToURNaMeNt Signup ends in: 23h
        // 47m SN 36M Request WAR DOB FulL 90 Upgrade 1136 946 400 000 2 159 SHOP". A tap between the
        // card's title and its button would sign the account into a tournament.
        val menu = listOf(
            box("Battle", 267, 460), box("FiNd a MatcH", 268, 514), box("1500", 250, 550),
            box("Star Bonus", 203, 675), box("Next in: 14m 27s", 395, 675), box("Ractice", 737, 56),
            box("RaNked Battle", 618, 461), box("Join ToURNaMeNt", 619, 531), box("Signup ends in:", 575, 597),
            box("23h 47m", 712, 597), box("SN 36M", 985, 40), box("Request", 960, 253), box("WAR DOB", 960, 313),
            box("FulL", 960, 645), box("90", 1213, 381), box("Upgrade", 1133, 540),
            box("1136 946", 1420, 40), box("400 000", 1425, 173), box("2 159", 1448, 238), box("SHOP", 1468, 679)
        )
        assertFalse(decide(menu) is Verdict.AdvanceDialogue)
        assertFalse(decide(listOf(box("RaNked Battle", 618, 461), box("Join ToURNaMeNt", 619, 531),
            box("SHOP", 1468, 679))) is Verdict.AdvanceDialogue)
    }

    @Test fun linesFarApartAreNotOneBubble() {
        // A bubble's lines are 19 px apart; two labels 70 px apart in the band are not a bubble.
        val spread = sweatHud + listOf(box("Gold Storage is nearly", 538, 430), box("full again Chief", 538, 500))
        assertFalse(decide(spread) is Verdict.AdvanceDialogue)
    }

    @Test fun aBubbleThatSellsIsNeverTapped() {
        val gems = decide(sweatHud + bubble("Get the Event Pass", "for 500 gems!"))
        assertEquals(Verdict.RefusedPremium("character_dialogue"), gems)
        val buy = decide(sweatHud + bubble("Buy my special", "bundle right now Chief!"))
        assertEquals(Verdict.RefusedPremium("character_dialogue"), buy)
        val price = decide(sweatHud + bubble("Only today, the Sour", "Elixir bundle is 4 99"))
        assertEquals(Verdict.RefusedPremium("character_dialogue"), price)
    }

    @Test fun thePrivacyDialogsConsentLineWhereABubbleSitsIsNotABubble() {
        // The audit puts the privacy dialog's consent line near (475, 467), inside the bubble's
        // place, with the village's Build readable behind it. No OCR of this dialog was logged:
        // the other places are invented. Its OK is pressed, never the consent line.
        assertEquals(
            Verdict.Dismiss("privacy_notice", Point(1000, 600)),
            inspect(
                box("Privacy Information", 800, 160),
                box("Do not sell or share my personal information", 475, 467),
                box("More information", 600, 600),
                box("OK", 1000, 600),
                box("Build", 1462, 685)
            )
        )
    }

    @Test fun theSwitchWaitTapsNoBubble() {
        val boxes = sweatHud + bubble("Y-ouCH! THat daRN PoRtal", "PendaNt Has MasHed up", "OUR WORIds.")
        val verdict = PopupGuard.inspect(boxes, screen, boxes.joinToString(" ") { it.text }, dialogue = false)
        assertEquals(Verdict.None, verdict)
    }

    @Test fun theQuitPromptIsTheVillageAndNoPopupToDismiss() {
        // Back on the village at 14:05 UTC on 8 October opened "Confirm Exit / Do you want to quit
        // the game?" (audit-evidence/overnight/LEDGER.md); these are WallPhaseTest's boxes for that
        // frame. Every reader takes it for the village and this guard knows no popup on it, so its
        // Okay is never a Dismiss. WallPhase.confirmHome presses its Cancel (WallPhaseTest).
        val boxes = listOf(
            RecognizedBox("259", 70, 15, 125, 42), RecognizedBox("29", 70, 48, 110, 72),
            RecognizedBox("!", 30, 560, 45, 590), RecognizedBox("Blood", 20, 95, 110, 120),
            RecognizedBox("Attack!", 60, 650, 210, 705), RecognizedBox("Collect", 230, 660, 330, 690),
            RecognizedBox("23d 17h", 1300, 120, 1400, 145), RecognizedBox("DOOst", 1300, 150, 1400, 175),
            RecognizedBox("0/6", 795, 26, 845, 56), RecognizedBox("4H 43M", 1420, 120, 1520, 145),
            RecognizedBox("CONFiRM Bxit", 690, 205, 910, 245),
            RecognizedBox("Do you want to quit the game?", 590, 300, 1010, 335),
            RecognizedBox("CaNcel", 605, 445, 715, 480),
            RecognizedBox("Full!", 1380, 20, 1440, 40), RecognizedBox("Full!", 1380, 70, 1440, 90),
            RecognizedBox("Full!", 1380, 120, 1440, 140), RecognizedBox("Okay", 895, 445, 985, 480),
            RecognizedBox("29 000 000", 1250, 20, 1370, 45), RecognizedBox("29 000 000", 1250, 70, 1370, 95),
            RecognizedBox("472585", 1250, 120, 1370, 145), RecognizedBox("1983", 1460, 180, 1530, 205),
            RecognizedBox("SHOP", 1480, 640, 1560, 680)
        )
        val raw = boxes.joinToString(" ") { it.text }
        assertEquals(ScreenState.HOME, local.clashfarmer.recognition.classifyScreen(boxes, screen))
        assertEquals(Verdict.None, PopupGuard.inspect(boxes, screen, raw))
        assertEquals(Verdict.None, PopupGuard.inspect(boxes, screen, raw, dialogue = false))
        assertFalse(Policy.showsShop(raw))
    }

    // --- The look before Back (UO-21 review, 15 October) ---------------------------------------

    private fun look(state: ScreenState, boxes: List<RecognizedBox>) =
        Observation(state, boxes, boxes.joinToString(" ") { it.text }, screen, 0L)

    /**
     * The store a start read at 04:11:38 UTC on 15 October, its words as logged; the log has no
     * boxes, so they are split into three placed here. The next frame, about 2 s later with no
     * input, was the village.
     */
    private val offers = listOf(
        box("INfo FRee Gift! PoweR PaNcakes x1 FREE 5d 3H 48M 355 Offers", 800, 200),
        box("SpideR Warden $9.99 17d 3H 48M 355", 800, 400),
        box("ZeRo to HeRD PRepaRe YoURSelf! Book of HeROes 3 BuildeR PotioN Re", 800, 550)
    )

    @Test fun backForTheStoreGoesOnlyWhileTheStoreIsStillUp() {
        assertEquals(Verdict.Shop, inspect(*offers.toTypedArray()))
        assertTrue(PopupGuard.stillShows(Verdict.Shop, look(ScreenState.UNKNOWN, offers)))
        assertTrue(PopupGuard.stillShows(Verdict.Shop, look(ScreenState.UNKNOWN, store)))
        // Gone to the village, where Back opens Clash's quit prompt.
        assertFalse(PopupGuard.stillShows(Verdict.Shop, look(ScreenState.HOME, sweatHud)))
        // Gone to a gem dialog with no price on it: that is not the store either.
        assertFalse(PopupGuard.stillShows(Verdict.Shop, look(ScreenState.UNKNOWN, buildersBusy)))
    }

    @Test fun backForAPricedOfferGoesOnlyWhileTheSameOfferIsUp() {
        val busy = Verdict.RefusedPremium("builders_busy")
        assertTrue(PopupGuard.stillShows(busy, look(ScreenState.UNKNOWN, buildersBusy)))
        // Or the store it became.
        assertTrue(PopupGuard.stillShows(busy, look(ScreenState.UNKNOWN, store)))
        // The village, with or without the offer's words read over it.
        assertFalse(PopupGuard.stillShows(busy, look(ScreenState.HOME, sweatHud)))
        assertFalse(PopupGuard.stillShows(busy, look(ScreenState.HOME, buildersBusy)))
        // Another offer, or the same one with its X now read, which the X closes instead.
        assertFalse(PopupGuard.stillShows(Verdict.RefusedPremium("another"), look(ScreenState.UNKNOWN, buildersBusy)))
        assertFalse(PopupGuard.stillShows(busy, look(ScreenState.UNKNOWN, buildersBusy + RecognizedBox("X", 1040, 195, 1082, 238))))
    }
}
