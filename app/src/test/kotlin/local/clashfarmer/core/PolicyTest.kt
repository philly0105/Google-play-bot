package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {

    @Test
    fun theTakeoverDialogIsRecognised() {
        // The OCR of the dialog at 06:14 on 25 September, over a battle.
        assertTrue(Policy.villageTakenElsewhere("ENd Battle XO Connection lost Battle ends in: 2M275 Another device is connecting to this Village. Reload"))
        assertTrue(Policy.villageTakenElsewhere("Connection lost Reload"))
        assertFalse(Policy.villageTakenElsewhere("Battle ends in: 2m Overall Damage 66%"))
        assertFalse(Policy.villageTakenElsewhere("Maintenance break! Try again later"))
    }

    @Test
    fun aPremiumSpendDialogIsRecognised() {
        val spends = listOf(
            "Not enough Elixir! Finish with gems? 120 Okay",
            "Special Offer $4.99 Buy",
            "Gold Pass Okay",
            "Do you really want to upgrade the selected Walls for 4000000 Gold? 200 gems",
            "Buy now for €1.99",
            "Purchase Okay"
        )
        for (text in spends) {
            assertTrue("expected premium spend: $text", Policy.spendsPremium(text))
        }

        val safe = listOf(
            "Do you really want to upgrade the selected Walls for 4000000 Gold? Okay Cancel",
            "Upgrade Wall to Level 18? 4,000,000 Okay",
            "Welcome back Chief! Okay",
            "Are you sure you want to surrender? Okay Cancel",
            "Password compass"
        )
        for (text in safe) {
            assertFalse("not a premium spend: $text", Policy.spendsPremium(text))
        }
    }

    /**
     * The gem store an Attack tap landed on at 05:31 UTC on 15 October, as logged in its
     * transition_timeout text and read off failure-053116.png (audit-evidence/shop/notes.md).
     */
    @Test
    fun showsShopReadsTheFifteenOctoberStore() {
        val cards = listOf(
            "Pocketful of Gems 80 $0.99", "Pile of Gems 500 $4.99", "Bag of Gems 1200 $9.99",
            "Sack of Gems 2500 $19.99", "Box of Gems 6500 $49.99"
        )
        for (card in cards) {
            assertTrue(card, Policy.showsShop(card))
            assertEquals(card, ScreenState.UNKNOWN, Policy.classify(card))
        }
        val store = "Treasure Gems Resources " + cards.joinToString(" ") + " 18 555 155 22 110 583 470 000 1 878"
        assertTrue(Policy.showsShop(store))
        assertEquals(ScreenState.UNKNOWN, Policy.classify(store))
        // Every currency sign dropped, or every title misread: still the store.
        assertTrue(Policy.showsShop(store.replace("$", "")))
        assertTrue(Policy.showsShop(store.replace("Gems", "Gerns")))
        assertTrue(Policy.showsShop(store.replace("$", "").replace("Gems", "Gerns")))
        // The village's buttons leaking in down its edges do not make it the village.
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Battle $store Build"))
    }

    /**
     * The quit prompt as read at 14:05 UTC on 8 October (WallPhaseTest's fixture), over a battle
     * as well as over the village, against the surrender confirmation whose Okay sits where its does.
     */
    @Test
    fun showsQuitPromptReadsClashsExitPromptAndNotTheSurrenderDialog() {
        val prompt = "CONFiRM Bxit Do you want to quit the game? CaNcel Okay"
        assertTrue(Policy.showsQuitPrompt(prompt))
        assertTrue(Policy.showsQuitPrompt("SURReNdeR Battle ends in: 1M 20S Overall damage: 41% $prompt"))
        assertFalse(Policy.showsQuitPrompt("Are you sure you want to surrender? Okay Cancel Battle ends in: 2M 10S"))
        assertFalse(Policy.showsQuitPrompt("Are you sure you want to surrender? Okay Cancel"))
        assertFalse(Policy.showsQuitPrompt("XtReMe Tactical 11 Available Loot: 706 006 602 739 SURReNdeR Battle ends in: IM 475"))
    }

    /**
     * UO-21 review, 15 October: "quit" anywhere on the frame made an opponent or clan named
     * "Never Quit" a quit prompt, and every early-end Okay over that battle was held.
     */
    @Test
    fun showsQuitPromptReadsThePromptsOwnWordsAndNotAClanNamedNeverQuit() {
        assertTrue(Policy.showsQuitPrompt("CONFiRM Bxit Do you want to quit the game? CaNcel Okay"))
        // The title or the question alone, as a frame that misses the other reads it.
        assertTrue(Policy.showsQuitPrompt("CONFiRM Bxit CaNcel Okay"))
        assertTrue(Policy.showsQuitPrompt("Confirm Exit"))
        assertTrue(Policy.showsQuitPrompt("Do you want to qu1t thegame? Cancel Okay"))
        for (name in listOf("Never Quit", "NEVER QUIT", "QuitNot", "Equity")) {
            assertFalse(name, Policy.showsQuitPrompt(
                "Are you sure you want to surrender? Okay Cancel $name Battle ends in: 2M 10S"))
            assertFalse(name, Policy.showsQuitPrompt("$name Available Loot: 706 006 602 739 SURReNdeR Battle ends in: IM 475"))
        }
    }

    @Test
    fun showsShopNeedsADecimalPriceOrAPackTitle() {
        for (text in listOf("$0.99", "£4.99", "0,99 €", "Buy now for €1.99", "Special Offer $4.99 Buy")) {
            assertTrue("a price: $text", Policy.showsShop(text))
        }
        val notPrices = listOf(
            "Upgrade Wall to Level 18? 4,000,000 Okay",
            "Do you really want to upgrade the selected Walls for 4000000 Gold? Okay Cancel",
            "18 555 155 22 110 583 470 000 1 878",
            "Buildings € HeRoes",
            "TReasuRe HUNt Find the hidden Treasure!",
            "Treasure Hunt event",
            "Boost 10",
            "1,000,000€",
            "12.345$"
        )
        for (text in notPrices) {
            assertFalse("not a shop: $text", Policy.showsShop(text))
        }
    }

    @Test
    fun testUnknownAndOverlayNeverBecomeHome() {
        assertEquals(ScreenState.HOME, Policy.classify("Attack! Shop"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Attack! Shop Connection lost Reload"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Buy gems Attack! Shop"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Something new"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Maintenance break! Try again later"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Another device is connecting to this village"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Out of sync with server"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Not enough Elixir! Purchase more"))
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Shield will expire. Are you sure?"))
    }

    @Test
    fun testEachAttackStageIsDistinct() {
        val cases = listOf(
            "Find a Match Ranked Battle Join Tournament" to ScreenState.MENU,
            "My Army Saved Recipes Attack!" to ScreenState.ARMY,
            "Battle starts in: 20s Available Loot Next" to ScreenState.SCOUT,
            "Battle ends in: 2m Overall Damage Surrender" to ScreenState.BATTLE,
            "Return Home Total damage" to ScreenState.RESULT,
            "Wall (Level 16) Upgrade Select Row" to ScreenState.WALL
        )
        for ((text, expected) in cases) {
            assertEquals("Failed for text: $text", expected, Policy.classify(text))
        }
    }

    /**
     * The attack menu has no Join Tournament button, and requiring one stalled a run.
     *
     * Read off the screen at 23:53 on 17 September, when a tap on Attack opened the menu and the
     * classifier called it HOME for the full fifteen seconds the transition was allowed: the
     * Ranked Battle card had reached its attack limit and a 4x Star Bonus banner sat along the
     * bottom, and between them no tournament was named anywhere on the screen. "Find a Match" is
     * the button the menu exists to offer and it was read correctly throughout.
     */
    @Test
    fun testMatchmakingIsNotTheResultScreen() {
        // Four readings of the same screen, taken while matchmaking ran on 18 September. The
        // Return Home button on it is the cancel-the-search button, and it is the one thing the
        // result screen is recognised by, so every search that outlasted a couple of seconds
        // ended the run. "Searching for opponents" is mangled differently every time; three
        // letters is the most of it the reader has ever kept. The last reading here is the one
        // that cost eight attacks at 15:10 - the reader turned the fourth letter into a D.
        val readings = listOf(
            "RetuRN HOMe SsaReiingFs oppoIeS ",
            "RetuRN HOMe SearaiNg foR OPPONeNis.a",
            "RetuRN HOMe SsaRe:tvgfoR OPpoNeis.a",
            "RetuRN HOMe SeaneiNg foR OPPONeNis.a",
            "RetuRN HOMe SeaReiiNg tOR OPPDNeNiS.sa "
        )
        for (reading in readings) {
            assertEquals(reading, ScreenState.SEARCHING, Policy.classify(reading))
        }
    }

    @Test
    fun testTheResultScreenIsStillTheResultScreen() {
        val readFromDevice = "S- UMUtbeY I KIZIL ORDU Total damage: 0% VictoRY RetuRN HOMe " +
            "19 28 993 O67 27 464 861 329 465 "
        assertEquals(ScreenState.RESULT, Policy.classify(readFromDevice))
    }

    @Test
    fun testAttackMenuIsRecognizedWithoutATournament() {
        val readFromDevice = "Multiplayer Battle FiNd a MatcH 4x Star Bonus boost! Single Plaver " +
            "Rank 2 1879 PRactice Ranked Battle 12/12 26 Attacks CoMpleted! Time Left: 3d 5h " +
            "16H 2 23m 3 614 688 6 222 773 142 019 408 SHOP "
        assertEquals(ScreenState.MENU, Policy.classify(readFromDevice))
    }

    @Test
    fun testAttackMenuIsRecognizedWhenMatchLosesItsLastLetters() {
        // Read on Sweat at 02:46 UTC on 25 September, every frame for fifteen seconds.
        val readFromDevice = "Multiplayer Battle 8 Single PlaveR FiNd a MateH 1500 i Star Bonus Next in: 2h 50m " +
            "PRactice RaNked Battile Join ToURNaMeNt Signup.ends in: 2h 13m 2iM 30s WAR Upgrade 90 " +
            "14 634 686 2 845 549 400 0O0 2259 SHOP 2 "
        assertEquals(ScreenState.MENU, Policy.classify(readFromDevice))
    }

    @Test
    fun testAmountParsingValidAndAmbiguous() {
        assertEquals(1_234_567L, Policy.amount("1 234 567"))
        assertEquals(1_234_567L, Policy.amount("1,234,567"))
        assertEquals(500_000L, Policy.amount("500000"))
        assertEquals(1_250_000L, Policy.amount("1 250 000"))
        assertEquals(0L, Policy.amount("0"))

        val ambiguous = listOf("1.2M", "123O", "-10", "12 34", "", "   ", "cost 100", "1,23", "abc", "100k")
        for (text in ambiguous) {
            var caught = false
            try {
                Policy.amount(text)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for ambiguous amount: '$text'", caught)
        }
    }

    @Test
    fun testReserveIsPreservedAndGemsAreRejected() {
        assertTrue(Policy.permittedUpgrade("gold", 2_500_000L, 1_500_000L, 1_000_000L))
        assertFalse(Policy.permittedUpgrade("elixir", 2_499_999L, 1_500_000L, 1_000_000L))
        assertTrue(Policy.permittedUpgrade("elixir", 1_000_000L, 500_000L, 500_000L))

        // Invalid currencies
        val invalidCurrencies = listOf("gems", "dark_elixir", "ore", "", "GOLD")
        for (cur in invalidCurrencies) {
            var caught = false
            try {
                Policy.permittedUpgrade(cur, 10_000_000L, 1L, 0L)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for currency: '$cur'", caught)
        }

        // Invalid amounts
        val invalidScenarios = listOf(
            Triple(2_000_000L, 0L, 1_000_000L),      // cost == 0
            Triple(2_000_000L, -100L, 1_000_000L),   // cost < 0
            Triple(-500L, 100L, 1_000_000L),         // balance < 0
            Triple(2_000_000L, 100L, -10L)           // reserve < 0
        )
        for ((bal, cost, res) in invalidScenarios) {
            var caught = false
            try {
                Policy.permittedUpgrade("gold", bal, cost, res)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for bal=$bal, cost=$cost, res=$res", caught)
        }
    }

    @Test
    fun testAttackCounterBoundsAndDeployResultCycle() {
        val counter = Policy.AttackCounter(40)
        for (i in 1..40) {
            counter.deploy()
            counter.result()
        }
        assertEquals(40, counter.completed)

        // Exceeded limit deploy
        var caught = false
        try {
            counter.deploy()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException when deploying after limit", caught)

        // Invalid limit bounds
        for (invalidLimit in listOf(0, -1, 101, 200)) {
            var limitCaught = false
            try {
                Policy.AttackCounter(invalidLimit)
            } catch (e: StopException) {
                limitCaught = true
            }
            assertTrue("Expected StopException for counter limit: $invalidLimit", limitCaught)
        }
    }

    @Test
    fun testAttackCounterRejectsDuplicateOrOutOfOrderResult() {
        val counter = Policy.AttackCounter(5)

        // Result without deployment
        var caught = false
        try {
            counter.result()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException on result before deploy", caught)

        // Normal deploy and result
        counter.deploy()
        counter.result()
        assertEquals(1, counter.completed)

        // Duplicate result
        caught = false
        try {
            counter.result()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException on duplicate result", caught)

        // Double deploy without result
        counter.deploy()
        caught = false
        try {
            counter.deploy()
        } catch (e: StopException) {
            caught = true
        }
        assertTrue("Expected StopException on double deploy", caught)
    }

    @Test
    fun testOverlayPrecedenceAndConflictingStateRejection() {
        // Explicit precedence: Wall panel over Home screen
        val wallOverHome = "Attack! Shop Wall (Level 16) Upgrade Select Row"
        assertEquals(ScreenState.WALL, Policy.classify(wallOverHome))

        // The reward banner is drawn over the battle and over a won battle alike
        assertEquals(
            ScreenState.REWARD,
            Policy.classify("Battle ends in: 2m Overall Damage Pick a Reward! Gold 331 720 Elixir")
        )
        assertEquals(
            ScreenState.REWARD,
            Policy.classify("Pick a Reward! Return Home Total damage 100%")
        )

        // A tall phone leaves the village visible beside every panel, so HOME leaks in
        assertEquals(ScreenState.ARMY, Policy.classify("My Army Saved Recipes Attack! Shop"))

        // The reader mangles "Attack!" on this screen more often than not
        assertEquals(ScreenState.ARMY, Policy.classify("Collect 238 MY ARMY Saved Recipes AMack! BHOP"))
        assertEquals(
            ScreenState.ARMY,
            Policy.classify(
                "238 Attad 83 4/4 90 MY ARMY 61 39 aved Recipes 320/320 X5 1/11 " +
                    "PA O/50 P0/3 0/2 Tap to edit Boost ARMY Cookbook Boost HeRDes Ao/3 Atack! PHOP"
            )
        )
        assertEquals(ScreenState.MENU, Policy.classify("Find a Match Join Tournament Attack! Shop"))

        // Conflicting states without precedence -> UNKNOWN
        val scoutAndBattle = "Battle starts in: 20s Available Loot Next Battle ends in: 2m Overall Damage"
        assertEquals(ScreenState.UNKNOWN, Policy.classify(scoutAndBattle))

        val menuAndResult = "Find a Match Join Tournament Return Home"
        assertEquals(ScreenState.UNKNOWN, Policy.classify(menuAndResult))
    }

    @Test
    fun testValidateLimits() {
        Policy.validateLimits(1, 15)
        Policy.validateLimits(100, 120)

        for (badAttacks in listOf(0, -1, 101)) {
            var caught = false
            try {
                Policy.validateLimits(badAttacks, 60)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for bad attacks: $badAttacks", caught)
        }

        for (badMinutes in listOf(0, -5, 121)) {
            var caught = false
            try {
                Policy.validateLimits(10, badMinutes)
            } catch (e: StopException) {
                caught = true
            }
            assertTrue("Expected StopException for bad minutes: $badMinutes", caught)
        }
    }

    @Test
    fun aBattleIsReadEvenWhenOverallLosesAnL() {
        // The reading that stopped a session at 15:06 on 17 September, transcribed whole.
        val raw = "HOM HOM 7 PHOENIX RISE!!! Available Loot: 1257 372 618 106 4 981 SURReNdeR " +
            "10 X40 83 90 61 140 39 Battle ends in: IM S25 PHOENIXRISE!!I XO 140 XO 642 878 " +
            "1455 184 400 0O0 OveRalI Damage 0% "
        assertEquals(ScreenState.BATTLE, Policy.classify(raw))
    }

    @Test
    fun aBattleIsReadWhenItsCountdownIsMangled() {
        // Battle frames logged as unrecognised on 25-28 September. The countdown came back
        // joined, misspelt or missing on 800 frames while the damage beside it was still there.
        val frames = listOf(
            "cikiRYS WodMaNs Available Loot: 819 480 735 712 10 719 ENd Battle 10 XO 83 90 61 39 " +
                "Battleends in: IM 385 XO 11 887 55 9061778 404 346 OveRalI Damage 76% ",
            "20 CRZ Available Loot: 10 042 70 908 306 ENd Battle 10 XO 83 |90 61 39 Batčle ends in: " +
                "IM 425 XO 10 977 279 8 602 670( 400 0D0 OveRalI Damage 87% ",
            "JHeN claN of WaR Available Loot: 8 20243 ENd Battle (10 X4 83 90 39 XO XO 25 000 000 " +
                "26 000 000 400 000 OveRal Damage 78% ",
            "SCYTH DgMR. WALTER V. Available Loot: 1456 730 1218 666 4751 SURReNdeR 10 X2 14 X40 83 " +
                "90 61 39 11915 394 9 894 994 400 00 OveRall Damage 49% ",
            "24 BBS0*LAANK Available Loot: 9821 ENd Battle XO 10 XO 83 90 61 Battle ends in: 2M 195 " +
                "A39 XO XO 21 109 027 16 103355 400 000 OveRall Damagge 88% "
        )
        for (text in frames) {
            assertEquals("Failed for text: $text", ScreenState.BATTLE, Policy.classify(text))
        }
    }

    @Test
    fun aBattleIsReadWhenItsDamageIsMangled() {
        // Blood's two-hour run of 14 October, line 696: "Damage" lost its m, so no battle rule held.
        val raw = "#BURHaN 30 IT GUYS 2 Available Loot: 911 117 1495 154 11100 SURReNdeR X5 12 X2 XO XO XO 13 " +
            "XO XO Battle ends in: IM 245 XO 19 X2 13 4 100 20 360 184 24 382 586 214 127 OveRall Daage 29% 79 11 "
        assertEquals(ScreenState.BATTLE, Policy.classify(raw))
    }

    @Test
    fun theCountdownAndSurrenderRuleLeavesOtherScreensAlone() {
        // Sweat's scout and both result screens of 14 October, and the takeover dialog over a battle.
        val scout = "Available Loot: 1596 6S7 I588 988 1 s86 ENd Battle BoDst ARMY 11 X40 83 Boost HeROes 66 39 " +
            "Battle starts in: X5 46 7 157 404 7008 i00 249 035 Next 1500"
        val claim = "Ioub R0 WAR DARK SoUR ElixiR 200 X40 11 83 10 66 Total darmage: S6% VictoRY 10 You go: 703 " +
            "317 S91 070 2594 Troops expended: 61 ClaiM RewaRd 39 X 6 86% BoNUS: x +232200 +232200 +1548 10 849 025 1 746 "
        val returnHome = "( KiNg DRagoN) GREAT FURY 2.0 100 5 100 Total damage: 70% VictoRY Yougot: 1577 465 13 384 " +
            "578 8 603 Troops expended 78 13 RetuRN HOMe 50 28- 100% BONUS: 370000 370000 +2750 29 000 O00 29 000 000 780 0"
        val takeover = "XtReMe Tactical 11 Available Loot: 706 006 602 739 10 238 SURReNdeR X5 12 X2 XO 12 Connection " +
            "lost Battle ends in: IM 475 Another device is connecting to this Village. Reload 100 100 22 109 836 ( 2463"
        assertEquals(ScreenState.SCOUT, Policy.classify(scout))
        assertEquals(ScreenState.RESULT, Policy.classify(claim))
        assertEquals(ScreenState.RESULT, Policy.classify(returnHome))
        assertEquals(ScreenState.UNKNOWN, Policy.classify(takeover))
        assertTrue(Policy.villageTakenElsewhere(takeover))
        // The surrender dialog itself is left to its Okay.
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Are you sure you want to surrender? Okay Cancel Battle ends in: 2M 10S"))
    }

    @Test
    fun anOpponentCalledNextIsStillABattle() {
        // The run that stopped at 22:54 on 26 September: the opponent's name supplied the scout
        // screen's "next", and a battle in plain sight read as two screens at once.
        val raw = "NextLevell2i122 e0 Aguilas Rojas Available Loot: 956 598 316 598 649 ENd Battle 10 XO " +
            "83 90 61 39 Battle ends in: IM 575 guilas ROJa XO XO 14 037 32 15 090 S68 400 000 " +
            "OveRall Damage G9% "
        assertEquals(ScreenState.BATTLE, Policy.classify(raw))
    }

    @Test
    fun aScoutScreenWithEndBattleIsStillAScout() {
        val raw = "Available Loot: 215 604 143 254 ENd Battle Next Battle starts in: 30s 10 X40 83 90 61 39"
        assertEquals(ScreenState.SCOUT, Policy.classify(raw))
    }

    @Test
    fun aScoutScreenIsNotABattle() {
        // Both screens carry the loot panel. The countdown is the difference, and it must stay
        // the difference: a scout screen classified as a battle would never get its troops.
        val raw = "Available Loot: 215 604 143 254 Next Battle starts in: 30s 10 X40 83 90 61 39"
        assertEquals(ScreenState.SCOUT, Policy.classify(raw))
    }

    /**
     * The bulk wall panel, exactly as the reader returned it at 23:07 on 17 September. It has no
     * Select Row button - Upgrade More replaces that panel with this one - so a WALL rule that
     * demanded "select row" called it HOME, and the phase abandoned a panel it had just opened.
     */
    @Test
    fun theBulkWallPanelIsAWallPanel() {
        val text = "238 20 Sweat Attack! 13d8h l Remove Wall Wall (Leveli5)x 1 AddWal Add Wall " +
            "IH33M WAR 008 QoOO 000 O UPgRade QOOO 00D UPgRade 10 4500 000 4 000 000 400 000 " +
            "2109 SHOp C"
        assertEquals(ScreenState.WALL, Policy.classify(text))
    }

    @Test
    fun theSingleWallPanelIsStillAWallPanel() {
        val text = "238 20 Sweat Attack! INfo Wall (Level 15) Select Row UpgRade MORe " +
            "4000 000 UPgRade 4000 000 UP9Rade 4500 000 SHOp"
        assertEquals(ScreenState.WALL, Policy.classify(text))
    }

    @Test
    fun attackOnlyPopupIsNotHome() {
        assertEquals(
            ScreenState.UNKNOWN,
            Policy.classify("Loading Building good-defeNses isjust as impoRtaNt as aggRessive attackiNg CLASH of CLANS")
        )
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Attacks Completed! 4x Star Bonus"))
        // The fallback the rule exists for: the Attack button without Shop read beside it.
        assertEquals(ScreenState.HOME, Policy.classify("258 Blood, Attack! Collect"))
    }

    @Test
    fun whereTheWordsSitDecidesBetweenMatchmakingAndResult() {
        // Whole-screen text cannot tell these apart: a defender's name supplied "opp" to a victory.
        val victory = "Total damage: 62% VictoRY You got: OPPortunist RetuRN HOMe"
        assertEquals(ScreenState.SEARCHING, Policy.classify(victory))
        assertEquals(
            ScreenState.RESULT,
            Policy.classify(victory, ScreenEvidence(resultAnchor = true, resultReturnButton = true))
        )
        // Matchmaking whose title lost its "opp" still has its Return Home in the corner.
        assertEquals(
            ScreenState.SEARCHING,
            Policy.classify("RetuRN HOMe toR DPPDNeRiSoe", ScreenEvidence(searchReturnButton = true))
        )
        // Return Home with nothing to say which screen it is on is neither.
        assertEquals(ScreenState.UNKNOWN, Policy.classify("RetuRN HOMe", ScreenEvidence()))
        // The village needs its Attack! button where the village draws it.
        assertEquals(ScreenState.UNKNOWN, Policy.classify("Attack! Shop", ScreenEvidence()))
        assertEquals(ScreenState.HOME, Policy.classify("Attack! Shop", ScreenEvidence(homeAttackButton = true)))
    }

    @Test
    fun unableToAttackIsNotAResultScreen() {
        // Read twice on 23 September after an account takeover, over matchmaking's Return Home.
        assertEquals(
            ScreenState.UNKNOWN,
            Policy.classify("RetuRN HOMe UNable tD ariadt Vilage! Tav agatu lateR ")
        )
    }

    @Test
    fun onlyTheSupervisedOneAttackRunSkipsEarnedVerification() {
        assertTrue(Policy.mayStartUnverified(supervised = true, attacks = 1, wallsOnly = false))
        assertFalse("Long attacks-only runs are unwatched", Policy.mayStartUnverified(supervised = true, attacks = 40, wallsOnly = false))
        assertFalse(Policy.mayStartUnverified(supervised = false, attacks = 1, wallsOnly = false))
        assertFalse(Policy.mayStartUnverified(supervised = false, attacks = 15, wallsOnly = false))
        // The walls-only phase buys nothing a deployment calibration could vouch for either way.
        assertTrue(Policy.mayStartUnverified(supervised = true, attacks = 0, wallsOnly = true))
    }

    @Test
    fun aRejectedStartSaysHowToEarnVerification() {
        val message = Policy.verificationRequired("Blood")
        assertTrue(message.contains("Blood"))
        assertTrue(message.contains("Supervised attack"))
    }

    @Test
    fun theTimedRunTurnsWallsOnAtNinetyPercent() {
        val off = local.clashfarmer.accounts.AccountSettings(wallsEnabled = false, triggerPercent = 95, resumePercent = 92)
        val timed = Policy.timedRunSettings(off)
        assertTrue(timed.wallsEnabled)
        assertEquals(90, timed.triggerPercent)
        assertEquals(0, timed.resumePercent)
        assertEquals(off.earlyEnd, timed.earlyEnd)
        assertEquals(0, Policy.timedRunSettings(local.clashfarmer.accounts.AccountSettings(resumePercent = 75)).resumePercent)
        Policy.validateLimits(Policy.TIMED_RUN_ATTACKS, Policy.TIMED_RUN_MINUTES)
    }

    @Test
    fun farmingRunsBuyEveryWallTheBalanceCovers() {
        val sweat = local.clashfarmer.accounts.AccountSettings(
            wallsEnabled = true, triggerPercent = 90, resumePercent = 75
        )
        val farming = Policy.farmingRunSettings(sweat)
        assertEquals(0, farming.resumePercent)
        assertEquals(90, farming.triggerPercent)
        assertTrue(farming.wallsEnabled)
        assertFalse(Policy.farmingRunSettings(sweat.copy(wallsEnabled = false)).wallsEnabled)
    }

    @Test
    fun theEventRunNeverEndsABattleEarly() {
        val sweat = local.clashfarmer.accounts.AccountSettings(
            wallsEnabled = true, triggerPercent = 90, resumePercent = 75,
            earlyEnd = EarlyEndRules(enabled = true, damageMin = 65, damageMax = 70)
        )
        val event = Policy.eventRunSettings(sweat)
        assertFalse(event.earlyEnd.enabled)
        assertEquals(0, event.resumePercent)
        assertTrue(event.wallsEnabled)
    }
}
