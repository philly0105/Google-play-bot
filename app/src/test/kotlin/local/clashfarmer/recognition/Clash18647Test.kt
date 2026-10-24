package local.clashfarmer.recognition

import kotlinx.coroutines.runBlocking
import local.clashfarmer.accounts.AccountIdentityParser
import local.clashfarmer.core.BuilderCount
import local.clashfarmer.core.Builders
import local.clashfarmer.core.Point
import local.clashfarmer.core.Policy
import local.clashfarmer.core.PopupGuard
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.SecondRead
import local.clashfarmer.core.UpgradeListDevice
import local.clashfarmer.core.UpgradeListFrame
import local.clashfarmer.core.UpgradeListNavigator
import local.clashfarmer.core.UpgradeListResult
import local.clashfarmer.core.Viewport
import local.clashfarmer.core.WallCandidate
import local.clashfarmer.core.WallPanel
import local.clashfarmer.core.secondRead
import local.clashfarmer.recognition.UpgradeListReader.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clash 18.647.2, installed on Sweat's phone at 04:57 UTC on 14 October. Every frame below is the
 * OCR an inspect logged on the phone that morning, box for box; the .ocr.txt files in audit-evidence/clash-18647.
 */
class Clash18647Test {

    private val screen = Viewport(0, 0, 1600, 720)

    /** An inspect's "text@left,top,right,bottom | ..." line as boxes. */
    private fun ocr(line: String): List<RecognizedBox> = line.split(" | ").map { item ->
        val at = item.lastIndexOf('@')
        val (l, t, r, b) = item.substring(at + 1).split(",").map(String::toInt)
        RecognizedBox(item.substring(0, at), l, t, r, b)
    }

    private fun text(boxes: List<RecognizedBox>) = boxes.joinToString(" ") { it.text }

    private val home = ocr("239@70,25,113,53 | New@149,118,184,129 | Ended@100,486,149,499 | Sweat Joost@181,56,285,83 | WAR 08@182,78,256,98 | New@170,350,208,364 | Battle@100,675,180,696 | New@200,526,235,537 | NEW@309,38,336,45 | Z324M@449,127,513,142 | Request@764,214,831,227 | WAR 008@779,272,824,286 | Upgrade@1151,295,1214,308 | 7 158 904@1378,24,1483,50 | 7 008 100@1373,74,1480,95 | 249 03S@1394,125,1479,144 | 1911@1440,179,1481,194 | SH 1OM@1415,223,1485,248 | FRee!@1445,379,1509,397 | 1/6 1/2@1411,540,1512,563 | Build@1432,674,1492,695")

    private val menu = ocr("XMultiplaveR 8 Single Plaver@97,42,560,71 | New@164,113,203,125 | Battle@213,444,330,472 | FiNd a MatcH@187,502,362,527 | 1500@227,533,287,567 | New@182,640,223,656 | Edit ARMY@171,670,286,691 | i Star Bonus@348,674,480,693 | PRactice@681,47,785,64 | RaNked Battle@493,446,754,477 | Join ToURNaMeNt@514,518,735,541 | Signup ends in:@528,589,642,608 | ld 23h@703,589,758,603 | Ends in: 3d 2h@961,120,1081,137 | TReasuRe HUNt@897,330,1143,352 | Destroy Enerny Buldings to@895,401,1149,419 | Find the hidden Treasure!@904,423,1136,442 | Easily view Battle Logs OR@900,508,1197,528 | edit YOUR aRMY fROM tHe@907,527,1191,544 | Battle MeNu.@973,545,1119,564 | 7158 904@1378,25,1486,49 | 7 008 100@1373,76,1482,95 | 249 035@1394,125,1479,144 | 1911@1440,177,1481,194 | SH 9M@1428,226,1482,249 | FRee!@1445,378,1509,398 | 1/6 1/@1416,541,1495,563")

    private val scout = ocr("Available Loot:@122,70,225,81 | 1596 6S7@115,90,224,113 | I588 988@115,124,223,154 | 1 s86@126,162,190,191 | ENd Battle@91,529,206,544 | BoDst ARMY@255,526,372,547 | 11@271,683,280,695 | X40@295,598,339,615 | 83@374,683,393,695 | Boost@492,518,555,536 | HeROes@483,536,561,554 | 66@470,683,489,695 | 39@660,680,683,695 | Battle starts in:@722,13,870,31 | X5@821,598,847,615 | 46@912,538,935,554 | 7 157 404@1381,25,1490,50 | 7008 i00@1377,76,1487,97 | 249 035@1395,128,1482,144 | Next@1419,481,1501,509 | 1500@1421,520,1484,540")

    private val battle = ocr("Available Loot:@119,66,228,85 | 1596 6S7@114,90,224,113 | I588 988@114,123,221,154 | 15 s86@114,162,188,191 | ENd Battle@91,529,206,544 | 11@271,683,279,692 | X40@295,598,339,615 | 83@374,683,393,695 | 66@470,683,489,695 | 39@664,683,683,695 | Battle ends in:@739,13,860,34 | 2M 325@740,38,861,66 | 47@749,565,768,578 | X5@821,598,847,615 | X1@922,598,942,614 | 7157 404@1386,25,1490,50 | 7 008 100@1374,76,1483,95 | 249 035@1395,128,1482,144 | OveRall Damage@1354,504,1509,520 | 100@1432,564,1478,584 | O%@1481,528,1524,551")

    private val wallPanel = ocr("239@70,23,114,52 | New@146,116,187,129 | Ended@100,486,149,499 | Sweat@181,58,251,76 | WAR 008@181,81,253,97 | New@173,353,208,364 | Battle@99,672,179,695 | 4d 14H@255,338,313,364 | 14@385,640,400,651 | CArmy Upgrades@483,260,624,281 | Wall@438,340,485,359 | Level 17@428,367,496,386 | INfo@570,586,619,605 | Wall (Level@680,463,874,500 | 7)@861,463,923,500 | Select@695,575,768,597 | ROW@710,599,758,615 | 7000 00o O@823,509,927,527 | UPgRade@822,585,922,609 | 7000 0o0@971,507,1052,525 | UPgRade@960,581,1058,609 | 7264 819(@1384,26,1497,52 | 7 008 100@1373,74,1480,95 | 249 035@1394,125,1479,144 | 1911@1440,179,1481,194 | 4H 57M@1413,226,1488,248 | LFRee!@1445,378,1508,397 | 1/6 1/ž@1411,539,1512,563 | Build@1432,674,1492,696")

    /** The builder list's top, 11-list-top: rows in progress, then Suggested upgrades. */
    private val listTop = ocr("New@168,339,215,379 | 2d2H@358,46,420,67 | 61@640,67,659,84 | 23H 12M@576,212,650,237 | Reques@766,53,819,71 | 14@783,132,803,145 | WAR O08@737,179,849,199 | 7157 404@944,25,1052,48 | n O08 100@878,72,1053,104 | 14H 12M@878,306,953,330 | 249 03S@956,120,1047,144 | 1911@1008,176,1049,197 | SH SM@996,226,1051,248 | BAvailable@978,291,1044,308 | Available@978,343,1044,355 | AiR DefeNse@1132,77,1259,97 | Buildings € HeRoes@1246,18,1425,39 | Hidden Tesla@1145,124,1264,145 | ARCHeR QUeeN@1150,176,1283,194 | WORkSHOP@1132,223,1238,243 | AiR DefeNse@1146,273,1258,291 | Suggested upgrades@1239,314,1427,329 | 1/6@1155,676,1182,690 | Barbarian KiNg@1190,349,1344,367 | CRaftiN9 5tatioN@1190,423,1359,442 | OHot Candle Hitpoints@1160,449,1333,464 | MORtaR X4@1203,497,1306,517 | Vi Roval CHaMPiON@1154,599,1350,618 | OtHeR upgrades@1260,563,1406,578 | 1H 17M@1463,80,1513,97 | 14H 12M@1451,127,1509,145 | 23M 17M@1452,175,1509,193 | 23M 19M@1454,224,1511,243 | 2d 2H@1469,274,1510,291 | Sd@1490,351,1509,363 | 195 000@1439,370,1515,390 | 3 500 O00@1408,446,1516,465 | 4d@1488,500,1509,512 | 7 000 000@1405,520,1516,538 | Sd@1490,600,1509,612 | 1/1@1482,676,1503,690")

    /**
     * Blood's list four scrolls down, read by an inspect at 07:31 UTC (r3-05): its Wall row among
     * the gold rows, no section header in view, and the icons' level badges "19" and "6".
     */
    private val bloodWallPage = ocr("Collect@215,160,257,170 | 8H IM@335,55,361,61 | Request@475,300,542,315 | H3DM@587,249,614,256 | Dius3H@633,271,666,280 | 9319 716@961,27,1053,52 | 4097405@939,76,1050,95 | 162 520@972,121,1049,147 | 1746@1000,173,1053,197 | ZH 54M@981,225,1055,249 | Available@978,343,1045,355 | 19@1134,66,1143,74 | 6@1140,276,1143,289 | 1/6@1155,676,1182,690 | Buildings € HeROes@1235,20,1425,37 | Wall x 23@1191,105,1278,119 | BOMb x6@1190,175,1278,191 | Seeking AiR MiNe x6@1191,248,1397,265 | SPRİNg TRaP X9@1191,319,1348,336 | GiaNt BoMb x 8@1191,391,1342,406 | Seeking Air MiNe@1191,462,1361,479 | AiR BoMb X7@1190,534,1317,548 | BoMb x2@1190,603,1276,618 | 1/2@1264,676,1291,690 | 8000 000@1409,124,1512,144 | 1d 14H ?@1461,171,1529,190 | 6 800 000@1405,196,1516,214 | 2d 9u@1470,244,1514,264 | S200 000@1408,267,1512,287 | 1d 14H@1465,315,1513,333 | 4800 O00@1408,338,1515,358 | 2d@1491,391,1509,403 | 4400 O00@1411,412,1514,430 | ld 14H@1462,457,1512,477 | 4 000 000@1404,482,1514,500 | 1d 14H ?@1461,530,1528,547 | 4 000 000@1416,553,1515,572 | 19H 10M à@1449,602,1532,618 | O/1@1478,676,1508,690")

    /** Pixels as Blood's list draws them: white digits, and a gold coin right of each price. */
    private fun bloodPixels(region: Region): IntArray =
        IntArray(maxOf(1, region.width * region.height)) { if (region.left >= 1500) 0xFFFFD21C.toInt() else 0xFFFFFFFF.toInt() }

    /**
     * Blood's village as each of its wall phases read it from 07:58 to 08:04 UTC on 14 October,
     * Battle read as "Batle"; none counted as the village and every phase ended unstarted.
     */
    private val bloodHomeBatle = ocr("259@70,28,108,54 | Ended@100,486,149,499 | Batle@99,671,178,696 | Blood@181,57,248,76 | WAR 008@182,84,253,97 | Request@186,239,257,258 | NAR O08@183,357,282,383 | 1d13H@455,29,538,57 | CATH56M@356,107,461,127 | 10@350,164,368,178 | BH:6M2@449,147,516,173 | 10 007 136@1375,26,1486,50 | 4779 978@1378,76,1482,94 | 172305@1384,124,1480,144 | R1746@1388,174,1480,195 | 7H 20M@1412,225,1487,250 | FRee!@1445,378,1509,397 | 1/6 O71@1410,540,1521,564 | Build@1432,674,1492,695")

    @Test fun theVillageWithBattleReadAsBatleIsHome() {
        assertTrue(screenEvidence(bloodHomeBatle, screen).homeAttackButton)
        assertEquals(ScreenState.HOME, classifyScreen(bloodHomeBatle, screen))
        // Not a character's bubble to be tapped through either.
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(bloodHomeBatle, screen, text(bloodHomeBatle)))
    }

    /** Blood's village after the reward chest at 08:40:33 UTC, Battle read as "Bale"; the checkpoint was skipped. */
    private val bloodHomeBale = ocr("259@70,25,114,54 | Ended@100,486,149,499 | Bale@99,672,178,696 | Blood@181,58,249,76 | WAR 008@181,82,253,96 | Collect@224,168,266,178 | bH SM@338,50,367,60 | 4393 34o@1374,25,1500,50 | 4914931@1382,73,1481,94 | 173 388@1401,125,1479,145 | 1746@1429,179,1481,194 | SH 44M@1409,227,1485,249 | FRee!@1445,378,1509,397 | 1/6 0/1@1410,539,1513,564 | Build@1432,674,1492,695")

    @Test fun theVillageWithBattleReadAsBaleIsHome() {
        assertTrue(screenEvidence(bloodHomeBale, screen).homeAttackButton)
        assertEquals(ScreenState.HOME, classifyScreen(bloodHomeBale, screen))
        assertEquals(ScreenState.HOME, Policy.classify(text(bloodHomeBale)))
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(bloodHomeBale, screen, text(bloodHomeBale)))
    }

    /** Sweat's village at 10:37:00 UTC on 14 October, Battle read as "Battie"; inspect called it UNKNOWN. */
    private val sweatHomeBattie = ocr("2397@70,25,117,53 | Ended@100,486,149,499 | Nlect@151,184,179,193 | Sweat@181,58,253,76 | WAR 008@182,80,260,97 | New@165,348,211,369 | Battie@99,672,178,697 | 3oost@250,73,289,87 | 2x@244,604,274,623 | 1BH_8M@463,127,496,136 | Request@765,216,818,227 | WAR 008 83@779,275,843,288 | 10 71D O88@1368,26,1483,49 | 1 384 603@1370,73,1480,95 | 297 112@1410,128,1480,143 | 1911@1440,179,1481,194 | 7H 58M@1410,227,1481,249 | LFRee!@1445,379,1509,397 | Build@1432,674,1492,696")

    @Test fun theVillageWithBattleReadAsBattieIsHome() {
        assertTrue(screenEvidence(sweatHomeBattie, screen).homeAttackButton)
        assertEquals(ScreenState.HOME, classifyScreen(sweatHomeBattie, screen))
        assertEquals(ScreenState.HOME, Policy.classify(text(sweatHomeBattie)))
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(sweatHomeBattie, screen, text(sweatHomeBattie)))
    }

    @Test fun theVillageWithBattleAndBuildIsHome() {
        assertTrue(screenEvidence(home, screen).homeAttackButton)
        assertEquals(ScreenState.HOME, classifyScreen(home, screen))
        assertEquals(ScreenState.HOME, Policy.classify(text(home)))
        assertTrue(AccountIdentityParser.isVillageHome(text(home)))
    }

    @Test fun aHyphenReadInsideBattleIsStillTheButton() {
        // 41-after-install-home: the first inspect after the install read nothing on this frame.
        val village = ocr("239@70,25,112,50 | Ended@100,486,149,499 | SWeat@181,57,254,78 | Bat-tle@99,671,176,696 | WAR 008@182,84,254,97 | New@173,353,208,364 | 2d-lH@367,49,429,71 | 14@385,640,400,651 | 22H 34M@577,218,655,237 | Request@753,55,828,75 | WAR O08@737,183,851,201 | 10.@722,257,742,271 | 39@932,41,953,58 | 13H 28M@874,309,955,331 | 83@1010,305,1030,321 | 10@1055,259,1075,273 | 1OH34M@1147,50,1233,71 | 7 264 819@1381,27,1485,50 | 7 008100@1383,76,1484,99 | 249 035@1388,123,1479,144 | 1911@1437,178,1485,196 | 4M 2IM@1416,224,1486,250 | LFRee!@1445,379,1509,397 | 1/6 1/2@1410,537,1511,564 | Build@1432,674,1492,696")
        assertEquals(ScreenState.HOME, classifyScreen(village, screen))
        assertTrue(AccountIdentityParser.isVillageHome(text(village)))
        assertEquals(BuilderCount(1, 6), Builders.read(village, screen))
    }

    @Test fun aResultThatPaysTheStarBonusIsAResultAndClaimRewardGoesHome() {
        // Words as logged at 06:18:24 UTC on 14 October; places measured on 50-result-claim.png.
        val result = ocr("Total damage:@755,82,850,98 | 72%@775,105,825,130 | VictoRY@735,195,868,225 | You got:@772,290,830,305 | 199 620@712,317,825,347 | 110 000@712,365,825,395 | 4 824@740,412,825,442 | Troops expended:@738,465,865,480 | 100% BONUS:@1057,317,1193,340 | +270000@1072,353,1163,375 | Claim RewaRd@743,605,895,630")
        assertEquals(ScreenState.RESULT, classifyScreen(result, screen))
        assertEquals(local.clashfarmer.core.Point(819, 617), returnHomeTarget(result, screen, local.clashfarmer.core.Point(800, 624)))
        // A Claim Reward without the loot lines, such as an event's, is not a result.
        assertFalse(Policy.classify("Event rewards Claim Reward") == ScreenState.RESULT)
        // Nor is its Claim Reward a chest's Continue.
        assertFalse(PopupGuard.inspect(result, screen, text(result)) is PopupGuard.Verdict.AdvanceDialogue)
    }

    @Test fun theStarBonusChestIsTappedOpenAndItsRewardContinuedButAPricedOneIsLeftAsTheStore() {
        // Places measured on 54-chest-tap1.png; the reward is 56-chest-continue's own OCR.
        val chest = ocr("Tap!@757,150,826,180 | Rare@745,527,835,555")
        val tap = PopupGuard.inspect(chest, screen, text(chest)) as PopupGuard.Verdict.AdvanceDialogue
        assertEquals(local.clashfarmer.core.Point(795, 390), tap.tap)
        val reward = ocr("ElixiR@768,184,851,214 | 1900 O00@742,486,895,520 | CONTiNUe@748,660,876,687")
        val next = PopupGuard.inspect(reward, screen, text(reward)) as PopupGuard.Verdict.AdvanceDialogue
        assertEquals(local.clashfarmer.core.Point(812, 673), next.tap)
        // A price on the frame is never continued past. Since the store tap of 15 October a
        // real-money price is the store's rule, ahead of the chest's: only Back answers it.
        val priced = reward + RecognizedBox("$4.99", 1300, 300, 1380, 330)
        assertEquals(PopupGuard.Verdict.Shop, PopupGuard.inspect(priced, screen, text(priced)))
        // A Tap! with no chest under it is nothing.
        val label = ocr("Tap!@757,150,826,180")
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(label, screen, text(label)))
    }

    @Test fun theChestIsNeverTappedOrContinuedPastAnythingThatSellsAndAPriceAfterItsNumberIsTheStore() {
        val reward = ocr("ElixiR@768,184,851,214 | 1900 O00@742,486,895,520 | CONTiNUe@748,660,876,687")
        val chest = ocr("Tap!@757,150,826,180 | Rare@745,527,835,555")
        // What a known popup is refused for, and what Policy.spendsPremium alone missed.
        for (sale in listOf("500 Gems", "500gems", "Buy", "Deal", "Special offer")) {
            for (frame in listOf(reward, chest)) {
                val sold = frame + RecognizedBox(sale, 1300, 300, 1380, 330)
                assertEquals(sale, PopupGuard.Verdict.RefusedPremium("reward_chest"), PopupGuard.inspect(sold, screen, text(sold)))
            }
        }
        // A real-money price, its sign after the number, is the store's rule since 15 October.
        for (frame in listOf(reward, chest)) {
            val sold = frame + RecognizedBox("4,99 €", 1300, 300, 1380, 330)
            assertEquals(PopupGuard.Verdict.Shop, PopupGuard.inspect(sold, screen, text(sold)))
        }
        // The village's buttons beside a Continue mean it is not the chest's, whatever else is read.
        for (button in listOf(RecognizedBox("Build", 1432, 674, 1492, 695), RecognizedBox("Battle", 100, 675, 180, 696))) {
            val village = reward + RecognizedBox("500 Gems", 1300, 300, 1380, 330) + button
            assertEquals(button.text, PopupGuard.Verdict.None, PopupGuard.inspect(village, screen, text(village)))
        }
        // A Continue with nothing on the pedestal is not the chest's either.
        val lone = ocr("CONTiNUe@748,660,876,687")
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(lone, screen, text(lone)))
        val labelled = ocr("ElixiR@768,184,851,214 | CONTiNUe@748,660,876,687")
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(labelled, screen, text(labelled)))
        // Nothing on the villages is a chest.
        for (village in listOf(home, bloodHomeBale, bloodHomeBatle)) {
            assertFalse(PopupGuard.inspect(village, screen, text(village)) is PopupGuard.Verdict.AdvanceDialogue)
        }
    }

    @Test fun theScreensThatSayBattleAreNotHome() {
        assertEquals(ScreenState.MENU, classifyScreen(menu, screen))
        assertEquals(ScreenState.SCOUT, classifyScreen(scout, screen))
        assertEquals(ScreenState.BATTLE, classifyScreen(battle, screen))
        // End Battle sits above the band the village's Battle button is read in.
        assertFalse(screenEvidence(scout, screen).homeAttackButton)
        assertFalse(AccountIdentityParser.isVillageHome(text(scout)))
        assertFalse(AccountIdentityParser.isVillageHome(text(menu)))
    }

    @Test fun theWallPanelOverTheVillageIsStillTheWallPanel() {
        assertEquals(ScreenState.WALL, classifyScreen(wallPanel, screen))
    }

    /**
     * The store rule of 15 October touches no frame read on the phone: none of these shows a shop,
     * so classify and PopupGuard answer each exactly as before. The list pages' "Buildings € HeRoes"
     * is the one currency sign ever read on a game screen.
     */
    @Test fun theStoreRuleLeavesEveryFixtureItsState() {
        val known = listOf(
            home to ScreenState.HOME, menu to ScreenState.MENU, scout to ScreenState.SCOUT,
            battle to ScreenState.BATTLE, wallPanel to ScreenState.WALL, bloodHomeBatle to ScreenState.HOME,
            bloodHomeBale to ScreenState.HOME, sweatHomeBattie to ScreenState.HOME
        )
        for ((frame, state) in known) {
            assertFalse(text(frame), Policy.showsShop(text(frame)))
            assertEquals(text(frame), state, classifyScreen(frame, screen))
        }
        val others = listOf(listTop, bloodWallPage, bloodWallAtTheBottom, bulkAtRest, bulkAtRestAlone,
            bulkAfterUpgradeMore, bulkAfterUpgradeMoreAlone,
            ocr("Total damage:@755,82,850,98 | 72%@775,105,825,130 | VictoRY@735,195,868,225 | You got:@772,290,830,305 | 199 620@712,317,825,347 | 110 000@712,365,825,395 | 4 824@740,412,825,442 | Troops expended:@738,465,865,480 | 100% BONUS:@1057,317,1193,340 | +270000@1072,353,1163,375 | Claim RewaRd@743,605,895,630"),
            ocr("Tap!@757,150,826,180 | Rare@745,527,835,555"),
            ocr("ElixiR@768,184,851,214 | 1900 O00@742,486,895,520 | CONTiNUe@748,660,876,687"))
        for (frame in others) {
            assertFalse(text(frame), Policy.showsShop(text(frame)))
            assertFalse(text(frame), PopupGuard.inspect(frame, screen, text(frame)) == PopupGuard.Verdict.Shop)
        }
    }

    /** RT-7: the one-wall panel of 25-wall-panel.png, with no Upgrade More and no Wall Ring button. */
    @Test fun theOneWallPanelHasTwoPurchaseButtonsAndNothingAtTheRing() {
        assertTrue(WallPanel.isPanel(wallPanel, screen))
        assertEquals(1, WallPanel.parseTitle(wallPanel, screen)?.count)
        assertEquals(null, WallPanel.upgradeMoreTap(wallPanel, screen))
        assertFalse(WallPanel.WallButton.UPGRADE_MORE in WallPanel.buttons(wallPanel, screen))

        val buttons = WallPanel.purchaseButtons(wallPanel, screen) { if (it.center.x < 950) "gold" else "elixir" }
        assertEquals(setOf("gold", "elixir"), buttons.keys)
        assertEquals(Point(872, 597), buttons.getValue("gold").tap)
        assertEquals(Point(1009, 595), buttons.getValue("elixir").tap)
        assertEquals(7_000_000L, buttons.getValue("gold").price)
        assertEquals(7_000_000L, buttons.getValue("elixir").price)
        assertTrue("nothing near the ring at x 1150: $buttons", buttons.values.none { it.tap.x >= 1100 })

        // Two prices both under one icon are one currency found twice, and neither is pressed.
        assertTrue(WallPanel.purchaseButtons(wallPanel, screen) { "elixir" }.isEmpty())
    }

    @Test fun theBuilderCounterIsReadAboveBuildAndNotTheGoblins() {
        assertEquals(BuilderCount(1, 6), Builders.read(home, screen))
        assertEquals(BuilderCount(1, 6), Builders.read(menu, screen))
        assertEquals(BuilderCount(1, 6), Builders.read(wallPanel, screen))
    }

    @Test fun aBuilderCounterThatLostItsFreeCountIsNotTheGoblins() {
        // Sweat's wall phase at 07:55:39 UTC read "/6 1/2" and took the goblin's 1/2.
        assertEquals(null, Builders.read(ocr("/6 1/2@1410,540,1512,563"), screen))
        assertEquals(BuilderCount(0, 6), Builders.read(ocr("0/6 1/2@1410,540,1512,563"), screen))
        // Blood's at 08:16:30, its goblin's counter "O/1".
        assertEquals(BuilderCount(1, 6), Builders.read(ocr("1/6 O/1@1410,540,1513,563"), screen))
    }

    @Test fun theProfileMenuIsKnownByItsTabs() {
        // Tabs read on Sweat's profile menu, 31-profile-default.png, over a village still showing.
        val profile = ocr("Clans@660,48,730,70 | Social@815,48,895,70 | Inbox@975,48,1053,70 | Settings@1125,48,1221,70 | Battle@100,675,180,696 | Build@1432,674,1492,695")
        assertTrue(AccountIdentityParser.isProfileMenu(text(profile)))
        assertFalse(AccountIdentityParser.isProfileMenu(text(home)))
    }

    @Test fun theVillageButtonsAreNotACharactersBubble() {
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(home, screen, text(home)))
        // The intro's character covers Battle and leaves Build readable.
        val intro = home.filterNot { it.text == "Battle" } + listOf(
            RecognizedBox("Chief, the portal pendant", 420, 420, 660, 440),
            RecognizedBox("has smashed up our worlds.", 420, 445, 660, 465)
        )
        assertTrue(PopupGuard.inspect(intro, screen, text(intro)) is PopupGuard.Verdict.AdvanceDialogue)
    }

    @Test fun theSupercellIdOverlaysLegalLinksAreNotABubble() {
        // 07:55:13 UTC, 1.8 s into the switch to Sweat: popup_dialogue_advanced where=switch at
        // (605, 502) on "PRİvacY Policy TeRMS Of seRvice" (r3-log-final.jsonl:692). Only the text and
        // the tap were logged, so the links are one box centred on the tap, over a village whose
        // Build is read and whose Battle is not.
        val behind = home.filterNot { it.text == "Battle" }
        val links = behind + RecognizedBox("PRİvacY Policy TeRMS Of seRvice", 490, 492, 720, 512)
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(links, screen, text(links)))
        // The same box saying something else is still a bubble: the words are what refuse it.
        val words = behind + RecognizedBox("Chief, the portal pendant broke", 490, 492, 720, 512)
        val bubble = PopupGuard.inspect(words, screen, text(words)) as PopupGuard.Verdict.AdvanceDialogue
        assertEquals(local.clashfarmer.core.Point(605, 502), bubble.tap)
        // And the switch's wait taps no bubble at all.
        assertEquals(PopupGuard.Verdict.None, PopupGuard.inspect(words, screen, text(words), dialogue = false))
    }

    @Test fun theBuilderPanelReadsAsTheListFromItsTop() {
        assertTrue(builderPanelShowing(listTop, screen))
        val reading = UpgradeListReader.read(listTop, screen) { region -> IntArray(maxOf(1, region.width * region.height)) { 0xFF203020.toInt() } }
        assertTrue(reading.hasListEvidence)
        assertTrue(reading.headerShowing)
        assertTrue(reading.atTop)
        assertFalse(reading.laboratoryShowing)
        // Sweat's walls are all at the top level, so its list has no Wall row.
        assertTrue(reading.rows.none { it.kind != Kind.OTHER })
        // A card's price, about 20 px under its name, is that card's.
        val king = reading.rows.single { it.name.contains("Barbarian") }
        assertEquals("195 000", king.priceText)
        val mortar = reading.rows.single { it.name.contains("MORtaR") }
        assertEquals("7 000 000", mortar.priceText)
        // "14H 12M" is a timer, not a price.
        val tesla = reading.rows.single { it.name.contains("Hidden Tesla") }
        assertEquals(null, tesla.priceText)
        assertTrue(UpgradeListReader.isCountdown("11H 15M"))
        assertTrue(UpgradeListReader.isCountdown("2d 2H"))
        // A lone capital unit is an abbreviated price, which a wall row must read as ambiguous.
        assertFalse(UpgradeListReader.isCountdown("5M"))
    }

    @Test fun bloodsWallRowIsAWallWithItsGoldPrice() {
        val reading = UpgradeListReader.read(bloodWallPage, screen, ::bloodPixels)
        assertTrue(reading.builderPanel)
        assertTrue(reading.hasListEvidence)
        assertFalse(reading.headerShowing)
        val wall = reading.rows.single { it.kind != Kind.OTHER }
        assertEquals(Kind.WALL, wall.kind)
        assertEquals(23, wall.count)
        assertEquals(UpgradeListReader.Amount.Exact(8_000_000), wall.amount)
        assertEquals("gold", wall.currency)
        assertEquals(true, wall.payable)
        assertTrue(wall.readable)
        assertEquals(local.clashfarmer.core.Point(1234, 112), wall.tap)
    }

    @Test fun anIconsLevelBadgeIsNeverACardsName() {
        val reading = UpgradeListReader.read(bloodWallPage, screen, ::bloodPixels)
        // "6" sits on Seeking Air Mine's icon, level with its price.
        assertEquals("S200 000", reading.rows.single { it.name.startsWith("Seeking AiR") }.priceText)
        assertTrue(reading.rows.none { it.name == "6" || it.name == "19" })
        // The same badge level with the Wall row's price took its name and lost the wall.
        val badged = bloodWallPage + RecognizedBox("19", 1134, 128, 1143, 140)
        val wall = UpgradeListReader.read(badged, screen, ::bloodPixels).rows.single { it.kind != Kind.OTHER }
        assertEquals(23, wall.count)
        assertEquals("gold", wall.currency)
    }

    @Test fun theListsTitleAloneIsEnoughToKnowItIsOpen() {
        // A frame with no header and none of its prices read, as a page caught mid-scroll can be.
        val bare = bloodWallPage.filterNot { it.left >= 1400 }
        val reading = UpgradeListReader.read(bare, screen, ::bloodPixels)
        assertFalse(reading.headerShowing)
        assertTrue(reading.builderPanel)
        assertTrue(reading.hasListEvidence)
    }

    /** Blood's list one page higher, its Wall row's name above the tabs and its price under them (r3-03). */
    private val bloodWallAtTheBottom = bloodWallPage.map {
        if (it.left >= 1118 && it.top in 61..625) it.copy(top = it.top + 499, bottom = it.bottom + 499) else it
    }

    @Test fun aWallCardWhosePriceIsUnderTheTabsIsCutOff() {
        val wall = UpgradeListReader.read(bloodWallAtTheBottom, screen, ::bloodPixels).rows.single { it.kind != Kind.OTHER }
        assertEquals(Kind.WALL, wall.kind)
        assertTrue(wall.clipped)
        // Half the price in view, its lower edge on the tabs, is no better.
        val half = bloodWallAtTheBottom.filterNot { it.text == "8000 000" } + RecognizedBox("8000 000", 1409, 612, 1512, 624)
        assertTrue(UpgradeListReader.read(half, screen, ::bloodPixels).rows.single { it.kind != Kind.OTHER }.clipped)
        // With its price clear of them it is read as before.
        assertFalse(UpgradeListReader.read(bloodWallPage, screen, ::bloodPixels).rows.single { it.kind != Kind.OTHER }.clipped)
    }

    @Test fun aMangledWallNameIsAWallToRereadNotAnotherCard() {
        fun wallOf(name: String) = UpgradeListReader.read(
            bloodWallPage.map { if (it.text == "Wall x 23") it.copy(text = name) else it }, screen, ::bloodPixels
        ).rows.firstOrNull { it.tap == Point(1234, 112) || it.name == name }
        assertEquals(23, wallOf("Wáll x 23")?.count)
        assertEquals(Kind.WALL, wallOf("Wáll x 23")?.kind)
        for (name in listOf("Wait x 23", "Wair x 23", "wa x 23")) {
            val row = wallOf(name)
            assertEquals(name, Kind.AMBIGUOUS_WALL, row?.kind)
            assertFalse(name, row!!.readable)
        }
        // Two words are another card.
        assertEquals(Kind.OTHER, wallOf("Wizard Tower x 5")?.kind)
    }

    /**
     * A phone showing the 18.647 list that does what Blood's did on 14 October (r3-11, r3-12): a
     * tap on the Wall row selects a wall and leaves the list open over its panel, and only the
     * list's X closes it. [pages] are the list top to bottom, a swipe moving one page, and the
     * Wall row is tapped on the last. On the first [untitled] frames after that tap the list's
     * title does not read.
     */
    private class PanelPhone(
        private val pages: List<List<RecognizedBox>>,
        private val wallRow: Region?,
        private val paint: (Region) -> IntArray,
        private var untitled: Int = 0
    ) : UpgradeListDevice {
        var listOpen = true
        var selected = false
        val taps = mutableListOf<Point>()
        private var page = 0
        private var time = 0L
        private val screen = Viewport(0, 0, 1600, 720)

        override suspend fun capture(): UpgradeListFrame {
            time += 1_100
            val shown = pages[page]
            val village = shown.filter { it.right < 1118 }
            val boxes = when {
                listOpen && selected -> (if (untitled-- > 0) shown.filterNot { it.text.startsWith("Buildings") } else shown) +
                    RecognizedBox("Wall (Level 18)", 436, 470, 690, 500)
                listOpen -> shown
                selected -> village + RecognizedBox("Wall (Level 18)", 680, 463, 920, 500)
                else -> village
            }
            return object : UpgradeListFrame {
                override val observation = Observation(ScreenState.UNKNOWN, boxes, boxes.joinToString(" ") { it.text }, screen)
                override fun pixels(region: Region) = paint(region)
            }
        }

        override suspend fun tap(point: Point) {
            time += 100
            taps += point
            if (listOpen && point == upgradeListClosePoint(screen)) listOpen = false
            else if (listOpen && wallRow != null && page == pages.lastIndex && point.x in wallRow.left..wallRow.right &&
                point.y in wallRow.top..wallRow.bottom) selected = true
        }

        override suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long) {
            time += durationMs + holdMs
            page = if (from.y > to.y) minOf(page + 1, pages.lastIndex) else maxOf(page - 1, 0)
        }

        override suspend fun wait(ms: Long) { time += ms }
        override fun now(): Long = time
        override suspend fun checkpoint(): Boolean = false
    }

    private val bloodWall = WallCandidate("Wall x 23", 23, 8_000_000, "gold")
    private val bloodWallRow = Region(1191, 105, 1278, 119)

    @Test fun bloodsWallRowIsTappedAndTheListClosedBeforeItsPanelCounts() = runBlocking {
        val phone = PanelPhone(listOf(bloodWallPage), bloodWallRow, ::bloodPixels)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 9_319_716L), emptySet())
        assertEquals(UpgradeListResult.Selected(bloodWall, 18), result)
        // The panel's title shows under the open list with no header in view; it is not taken
        // for the closed list until the X has been pressed.
        assertEquals(listOf(Point(1234, 112), upgradeListClosePoint(screen)), phone.taps)
        assertFalse(phone.listOpen)
    }

    @Test fun aFrameWhoseListTitleDidNotReadIsNotTheClosedList() = runBlocking {
        val phone = PanelPhone(listOf(bloodWallPage), bloodWallRow, ::bloodPixels, untitled = 1)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 9_319_716L), emptySet())
        assertEquals(UpgradeListResult.Selected(bloodWall, 18), result)
        assertEquals(listOf(Point(1234, 112), upgradeListClosePoint(screen)), phone.taps)
        assertFalse(phone.listOpen)
    }

    @Test fun twoFramesWhoseListTitleDidNotReadAreStillTheOpenList() = runBlocking {
        // Before, two such frames in a row were taken for the closed list: Selected, list open.
        val phone = PanelPhone(listOf(bloodWallPage), bloodWallRow, ::bloodPixels, untitled = 2)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 9_319_716L), emptySet())
        assertEquals(UpgradeListResult.Selected(bloodWall, 18), result)
        assertEquals(listOf(Point(1234, 112), upgradeListClosePoint(screen)), phone.taps)
        assertFalse(phone.listOpen)
    }

    @Test fun onlyTheListDrawsCardsInItsColumn() {
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(bloodWallPage, screen))
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(listTop, screen))
        // The Wall card alone, its price unread, and the prices alone, the Wall's name unread.
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(bloodWallPage.filterNot { it.left >= 1400 }, screen))
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(bloodWallPage.filterNot { it.text == "Wall x 23" }, screen))
        for (frame in listOf(wallPanel, home, bloodHomeBale, bloodHomeBatle, menu)) {
            assertFalse(text(frame), local.clashfarmer.core.upgradeListCardShowing(frame, screen))
        }
    }

    /**
     * UO-18 round 2: the builder counter's split half "071", read on ARMY frames, and a lone badge
     * "6" by the store button passed for list cards and cost the bulk panel its price pass. A
     * longer misread of the counters, "7161", is no card either, for where it sits.
     */
    @Test fun aLoneShortNumberOrTheCountersLineIsNoListCard() {
        for (stray in listOf(RecognizedBox("071", 1474, 543, 1513, 563), RecognizedBox("6", 1509, 574, 1521, 591),
            RecognizedBox("7161", 1466, 543, 1513, 563))) {
            for (panel in listOf(bulkAtRest, bulkAfterUpgradeMore)) {
                val frame = panel + stray
                assertFalse(text(frame), local.clashfarmer.core.upgradeListCardShowing(frame, screen))
                val reading = UpgradeListReader.read(frame, screen, ::bloodPixels)
                assertEquals(text(frame), SecondRead.PRICE_REGIONS, secondRead(reading, frame, screen))
            }
        }
        // The list's own cards still count: its smallest price, a dark elixir 195 000, alone, and
        // Blood's Wall page with its name unread, whose 4 000 000 on the counters' line no longer counts.
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(listOf(RecognizedBox("195 000", 1439, 370, 1515, 390)), screen))
        assertTrue(local.clashfarmer.core.upgradeListCardShowing(bloodWallPage.filterNot { it.text == "Wall x 23" }, screen))
    }

    @Test fun aWallRowWithItsPriceUnderTheTabsIsTakenOneScrollLater() = runBlocking {
        val phone = PanelPhone(listOf(bloodWallAtTheBottom, bloodWallPage), bloodWallRow, ::bloodPixels)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 9_319_716L), emptySet())
        assertEquals(UpgradeListResult.Selected(bloodWall, 18), result)
        assertEquals(listOf(Point(1234, 112), upgradeListClosePoint(screen)), phone.taps)
    }

    @Test fun aWallBeyondTheBalanceIsNotTapped() = runBlocking {
        val phone = PanelPhone(listOf(bloodWallPage), bloodWallRow, ::bloodPixels)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 7_999_999L), emptySet())
        assertEquals(UpgradeListResult.Empty(2, 1), result)
        assertTrue(phone.taps.isEmpty())
    }

    @Test fun sweatsListWithNoWallRowEndsEmptyWithNoWallSeenAndNoTap() = runBlocking {
        val dark = { region: Region -> IntArray(maxOf(1, region.width * region.height)) { 0xFF203020.toInt() } }
        val phone = PanelPhone(listOf(listTop), null, dark)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 20_000_000L), emptySet())
        assertEquals(UpgradeListResult.Empty(2, 0), result)
        assertTrue(phone.taps.isEmpty())
    }

    /**
     * Blood's bulk panel at rest at 02:15 UTC on 15 October, as a frame's first read would have it:
     * the two prices one box from the gold price to the elixir drop, and the title with its "x1"
     * out to x 962. The lefts are the ones wall_bulk_reread logged (UO-18-wall-history.jsonl,
     * 560-566); the rest is 25-wall-panel's, whose labels and prices start where these do. The
     * list's crop of this frame read the elixir price as "80" at 968, cut at x 985.
     */
    private val bulkAtRest = ocr("11h 50m@60,84,124,100 | ReMOVe@408,576,468,594 | Wall@432,596,470,614 | Add Wall@552,586,632,612 | Add Wall@679,586,759,612 | Wall (Level 18)x1@681,463,962,500 | 8O0D O0D ON 8opD O00@826,507,1052,527 | UpgRade@822,585,922,609 | UpgRade@960,581,1058,609 | UPgRade@1103,583,1199,609 | 8@1141,507,1153,527 | 1/6 o/1@1414,540,1513,563")

    /** Each price of [bulkAtRest] as a read of its own region gives it; "8000 000@967" read so at 02:15:11. */
    private val bulkAtRestAlone = ocr("B000 000 O@826,509,927,527 | 8000 000@967,507,1052,525 | 8@1141,507,1153,527")

    /**
     * The same panel after Upgrade More at 03:14 UTC: the labels and prices 69 px further left, and
     * the first read running both prices on past the elixir drop. The list's crop read them as one
     * box ending at about 985, "B000 00DC 8000 O00", and the count was refused.
     */
    private val bulkAfterUpgradeMore = ocr("11h 50m@60,84,124,100 | ReMOVe@477,576,537,594 | Wall@498,596,536,614 | Add Wall@622,586,702,612 | Waii (Leveri8)x 1@677,463,958,500 | B000 00DC 8000 O00 O@757,507,1000,527 | UpgRade@753,585,849,609 | UpgRade@892,581,988,609 | UPgRade@1033,583,1129,609")

    private val bulkAfterUpgradeMoreAlone = ocr("B000 000 O@757,509,858,527 | 8000 000@898,507,983,525")

    private fun inside(region: Region, box: RecognizedBox) =
        box.left >= region.left && box.right <= region.right && box.top >= region.top && box.bottom <= region.bottom

    /** What reading each of [frame]'s price regions gives: the boxes of [alone] that lie inside it. */
    private fun regionReads(frame: List<RecognizedBox>, alone: List<RecognizedBox>): List<Pair<Region, List<RecognizedBox>>> =
        WallPanel.priceRegions(frame, screen).map { region -> region to alone.filter { inside(region, it) } }

    /** The coin right of a gold price at x [coin] and the drop right of an elixir one at [drop]. */
    private fun icons(coin: Int, drop: Int): (RecognizedBox) -> String? = { box ->
        val reach = box.left..box.right + 20
        when {
            coin in reach && drop !in reach -> "gold"
            drop in reach && coin !in reach -> "elixir"
            else -> null
        }
    }

    @Test fun aBulkWallPanelHasItsPricesReadOneLabelAtATimeAndNotTheListsCrop() {
        fun check(frame: List<RecognizedBox>, alone: List<RecognizedBox>, coin: Int, drop: Int, gold: Point, elixir: Point) {
            val name = text(frame)
            val reading = UpgradeListReader.read(frame, screen, ::bloodPixels)
            // The title and the run-on prices end flush right inside the old popup: the list's
            // test alone takes the frame for the list. Its crop ends inside the elixir price at
            // rest, and after Upgrade More just past it, short of the drop that names elixir.
            assertTrue(name, reading.hasListEvidence)
            val elixirPrice = alone[1]
            val cropEnd = upgradePopupRegion(screen, frame).right
            if (frame === bulkAtRest) assertTrue(name, cropEnd in elixirPrice.left until elixirPrice.right)
            else assertTrue(name, cropEnd in elixirPrice.right until drop)
            val icon = icons(coin, drop)
            // Read once and decided once: the wall panel, so its price pass runs and the crop does not.
            assertEquals(name, SecondRead.PRICE_REGIONS, secondRead(reading, frame, screen))
            val regions = WallPanel.priceRegions(frame, screen)
            assertTrue(name, alone.all { box -> regions.any { inside(it, box) } })
            val read = WallPanel.withPriceReads(frame, regionReads(frame, alone))
            val buttons = WallPanel.purchaseButtons(read, screen, icon)
            assertEquals(name, setOf("gold", "elixir"), buttons.keys)
            assertEquals(name, gold, buttons.getValue("gold").tap)
            assertEquals(name, elixir, buttons.getValue("elixir").tap)
            assertEquals(name, 8_000_000L, buttons.getValue("gold").price)
            assertEquals(name, 8_000_000L, buttons.getValue("elixir").price)
            assertTrue(name, buttons.values.none { it.tap.x >= 1100 })
            // The title sits above the regions and is kept as the first read had it.
            assertTrue(name, frame.filter { it.top == 463 }.all { it in read })
        }
        check(bulkAtRest, bulkAtRestAlone, coin = 935, drop = 1062, gold = Point(872, 597), elixir = Point(1009, 595))
        check(bulkAfterUpgradeMore, bulkAfterUpgradeMoreAlone, coin = 866, drop = 993, gold = Point(801, 597), elixir = Point(940, 595))
    }

    @Test fun aGoldLabelRunOnFromAddWallHasItsRegionOnTheWordUpgrade() {
        // The +1 Add Wall and the gold Upgrade as one box, as on Sweat on 5 October: its middle
        // is x 800, and a region around that cut the gold price, 826 to 927.
        val merged = bulkAtRest.filterNot { it.left == 679 || it.left == 822 } +
            RecognizedBox("Add Wall UpgRade", 679, 585, 922, 612)
        val regions = WallPanel.priceRegions(merged, screen)
        assertTrue(text(merged), bulkAtRestAlone.take(2).all { box -> regions.any { inside(it, box) } })
        val buttons = WallPanel.purchaseButtons(WallPanel.withPriceReads(merged, regionReads(merged, bulkAtRestAlone)), screen, icons(935, 1062))
        assertEquals(setOf("gold", "elixir"), buttons.keys)
        assertEquals(8_000_000L, buttons.getValue("gold").price)
        assertTrue("${buttons.getValue("gold").tap}", buttons.getValue("gold").tap.x in 822..922)
    }

    @Test fun aFrameNoneOfWhoseRegionsReadAPriceKeepsItsFirstReadForTheCut() {
        // Every region of the 03:14 frame reads short of a price; the first read's box of both
        // prices is kept, and cut between the gold and elixir labels.
        val reads = WallPanel.priceRegions(bulkAfterUpgradeMore, screen).map { region ->
            region to listOf(RecognizedBox("80", region.left + 30, 507, region.left + 60, 525))
        }
        val read = WallPanel.withPriceReads(bulkAfterUpgradeMore, reads)
        assertEquals(bulkAfterUpgradeMore, read)
        val buttons = WallPanel.purchaseButtons(read, screen, icons(866, 993))
        assertEquals(setOf("gold", "elixir"), buttons.keys)
        assertEquals(Point(801, 597), buttons.getValue("gold").tap)
        assertEquals(Point(940, 595), buttons.getValue("elixir").tap)
        assertEquals(8_000_000L, buttons.getValue("gold").price)
        assertEquals(8_000_000L, buttons.getValue("elixir").price)
    }

    @Test fun aFrameWhoseGoldRegionAloneReadsAPriceHasGoldOnceAndNoElixir() {
        // The gold region reads its price whole and the elixir one reads "80": the first read's
        // box of both prices reaches the gold region and goes, so gold is not found twice.
        val reads = WallPanel.priceRegions(bulkAfterUpgradeMore, screen).map { region ->
            region to when {
                inside(region, bulkAfterUpgradeMoreAlone[0]) -> listOf(bulkAfterUpgradeMoreAlone[0])
                region.contains(bulkAfterUpgradeMoreAlone[1].center) -> listOf(RecognizedBox("80", 898, 507, 930, 525))
                else -> emptyList()
            }
        }
        val read = WallPanel.withPriceReads(bulkAfterUpgradeMore, reads)
        assertTrue(read.none { it.text.startsWith("B000 00DC") || it.text == "80" })
        val buttons = WallPanel.purchaseButtons(read, screen, icons(866, 993))
        assertEquals(setOf("gold"), buttons.keys)
        assertEquals(Point(801, 597), buttons.getValue("gold").tap)
        assertEquals(8_000_000L, buttons.getValue("gold").price)
    }

    /**
     * UO-18 round 2: an 8-digit gold total read whole on the first pass, its coin taking it 2 px
     * into the elixir region at rest and 1 px after Upgrade More, with the gold region reading no
     * price and the elixir one reading its own. The gold box is kept, not dropped for the reach.
     */
    @Test fun anEightDigitGoldTotalReachingIntoTheElixirRegionKeepsItsFirstRead() {
        fun check(panel: List<RecognizedBox>, merged: String, past: Int, coin: Int, drop: Int) {
            val elixirRegion = WallPanel.priceRegions(panel, screen).sortedBy { it.left }[1]
            val first = panel.single { it.text == merged }
            val gold = RecognizedBox("10 000 000 O", first.left, 509, elixirRegion.left + past, 527)
            val elixir = RecognizedBox("10 000 000", elixirRegion.left + 30, 507, drop - 10, 525)
            val frame = panel.filterNot { it === first } + gold + elixir
            val name = text(frame)
            val reads = WallPanel.priceRegions(frame, screen).map { region ->
                region to if (region == elixirRegion) listOf(elixir) else listOf(RecognizedBox("80", region.left + 30, 507, region.left + 60, 525))
            }
            val read = WallPanel.withPriceReads(frame, reads)
            assertTrue(name, gold in read)
            val buttons = WallPanel.purchaseButtons(read, screen, icons(coin, drop))
            assertEquals(name, setOf("gold", "elixir"), buttons.keys)
            assertEquals(name, 10_000_000L, buttons.getValue("gold").price)
            assertEquals(name, 10_000_000L, buttons.getValue("elixir").price)
        }
        check(bulkAtRest, "8O0D O0D ON 8opD O00", past = 2, coin = 935, drop = 1062)
        check(bulkAfterUpgradeMore, "B000 00DC 8000 O00 O", past = 1, coin = 866, drop = 993)
        // A box that reaches well into a priced region still gives way, as the run-on read does.
        val runOn = bulkAfterUpgradeMore.single { it.text == "B000 00DC 8000 O00 O" }
        val elixirRegion = WallPanel.priceRegions(bulkAfterUpgradeMore, screen).sortedBy { it.left }[1]
        val reads = WallPanel.priceRegions(bulkAfterUpgradeMore, screen).map { region ->
            region to if (region == elixirRegion) listOf(bulkAfterUpgradeMoreAlone[1]) else emptyList()
        }
        assertFalse(runOn in WallPanel.withPriceReads(bulkAfterUpgradeMore, reads))
    }

    @Test fun aListFrameStillHasItsCropReadAndNoPricePass() {
        fun isList(frame: List<RecognizedBox>, pixels: (Region) -> IntArray) {
            val reading = UpgradeListReader.read(frame, screen, pixels)
            assertTrue(text(frame), reading.hasListEvidence)
            assertEquals(text(frame), SecondRead.LIST_CROP, secondRead(reading, frame, screen))
        }
        // The 18.647 list at its top, on Blood's Wall page, and that page over the wall panel a
        // Wall row's tap left under it, the panel's title, prices and labels showing beside it.
        isList(listTop, ::bloodPixels)
        isList(bloodWallPage, ::bloodPixels)
        isList(bloodWallPage + wallPanel.filter { it.top in 440..620 && it.right < 1118 }, ::bloodPixels)
        // The old popup's list, with and without a header in view.
        val sweat = UpgradeListFixture.sweat()
        for (offset in listOf(0, 120, 240, 360)) {
            val frame = UpgradeListFixture.Frame(sweat, offset)
            isList(frame.observation.boxes, frame::pixels)
        }
    }

    @Test fun aDialogOverTheWallPanelHasNoPricePass() {
        // The confirmation's Okay, and a frame where a reread restored it: the dialog's own reads run instead.
        for (okay in listOf("Okay", "OK")) {
            val dialog = bulkAtRest + RecognizedBox(okay, 736, 470, 864, 500)
            val reading = UpgradeListReader.read(dialog, screen, ::bloodPixels)
            assertEquals(okay, SecondRead.LIST_CROP, secondRead(reading, dialog, screen))
        }
        // The one-wall panel with nothing over it keeps the pass it had.
        val single = UpgradeListReader.read(wallPanel, screen, ::bloodPixels)
        assertFalse(single.hasListEvidence)
        assertEquals(SecondRead.PRICE_REGIONS, secondRead(single, wallPanel, screen))
    }

    /**
     * Blood's list as one column of 18.647 cards scrolled under the panel's window, y 60 to 625:
     * "Suggested upgrades" at the top, then [cards] (name, price) 63 px apart, nine or so to a
     * page, each a name at x 1191 with its price 19 px lower and flush right at 1512 and, for all
     * but a Wall, the time to upgrade level with the name. A card with no price shows only its
     * time, "8H", "6H" or "4H". A held swipe moves the column 250 px, and only boxes wholly inside
     * the window are read. A tap on the Wall's name selects it and leaves the list open over its
     * panel until the list's X is pressed, as PanelPhone's does.
     */
    private class CardPhone(private val cards: List<Pair<String, String?>>) : UpgradeListDevice {
        var offset = 0
        var listOpen = true
        var selected = false
        var swipes = 0
        val taps = mutableListOf<Point>()
        private var time = 0L
        private val screen = Viewport(0, 0, 1600, 720)
        private val maxOffset = FIRST_CARD + PITCH * cards.size - 625

        private fun nameBox(index: Int): RecognizedBox {
            val top = FIRST_CARD + PITCH * index - offset
            return RecognizedBox(cards[index].first, 1191, top, 1191 + 9 * cards[index].first.length, top + 15)
        }

        override suspend fun capture(): UpgradeListFrame {
            time += 1_100
            val shown = mutableListOf<RecognizedBox>()
            fun add(box: RecognizedBox) { if (box.top >= 60 && box.bottom <= 625) shown += box }
            add(RecognizedBox("Suggested upgrades", 1239, 66 - offset, 1427, 81 - offset))
            cards.forEachIndexed { i, (name, price) ->
                val box = nameBox(i)
                add(box)
                if (price == null) {
                    add(RecognizedBox("${8 - 2 * (i / 3 % 3)}H", 1485, box.top - 2, 1513, box.top + 15))
                    return@forEachIndexed
                }
                add(RecognizedBox(price, 1409, box.top + 19, 1512, box.top + 39))
                if (!name.startsWith("Wall")) add(RecognizedBox("1d 14H", 1461, box.top - 2, 1513, box.top + 15))
            }
            val boxes = when {
                listOpen && selected -> listOf(RecognizedBox("Buildings € HeROes", 1235, 20, 1425, 37)) + shown +
                    RecognizedBox("Wall (Level 18)", 436, 470, 690, 500)
                listOpen -> listOf(RecognizedBox("Buildings € HeROes", 1235, 20, 1425, 37)) + shown
                selected -> listOf(RecognizedBox("Wall (Level 18)", 680, 463, 920, 500))
                else -> emptyList()
            }
            return object : UpgradeListFrame {
                override val observation = Observation(ScreenState.UNKNOWN, boxes, boxes.joinToString(" ") { it.text }, screen)
                override fun pixels(region: Region) =
                    IntArray(maxOf(1, region.width * region.height)) { if (region.left >= 1500) 0xFFFFD21C.toInt() else 0xFFFFFFFF.toInt() }
            }
        }

        override suspend fun tap(point: Point) {
            time += 100
            taps += point
            if (listOpen && point == upgradeListClosePoint(screen)) {
                listOpen = false
                return
            }
            val wall = cards.indexOfFirst { it.first.startsWith("Wall") }
            val box = nameBox(wall)
            if (listOpen && point.x in box.left..box.right && point.y in box.top - 3..box.bottom + 3) selected = true
        }

        override suspend fun swipe(from: Point, to: Point, durationMs: Long, holdMs: Long) {
            time += durationMs + holdMs
            swipes++
            offset = (offset + if (from.y > to.y) MOVE else -MOVE).coerceIn(0, maxOffset)
        }

        override suspend fun wait(ms: Long) { time += ms }
        override fun now(): Long = time
        override suspend fun checkpoint(): Boolean = false

        companion object {
            const val FIRST_CARD = 106
            const val PITCH = 63
            const val MOVE = 250
        }
    }

    /**
     * Fifty-nine cards, 3 198 px to scroll against Blood's 3 215: every third a Crafting Station
     * with no price, read as "CRafting 5tation 8H", "6H" or "4H" as Sweat's list read them at
     * 07:55 on 14 October, the rest each at its own price, and the Wall last.
     */
    private val bloodLengthCards: List<Pair<String, String?>> = (0 until 58).map { i ->
        val names = listOf("Cake-A-Pult", "Hot Candle", "DaRk ElixiR StoRage", "LONgSHot", "SMasHeR", "AiR DefeNse", "MORtaR X4")
        if (i % 3 == 0) "CRafting 5tation" to null
        else names[i % names.size] to UpgradeListFixture.spaced(100_000L + 37_000L * i)
    } + ("Wall x 23" to "8 000 000")

    @Test fun aListAsLongAsBloodsAtTheCardsOwnSpacingIsReadToItsWallAtTheBottom() = runBlocking {
        val phone = CardPhone(bloodLengthCards)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 9_319_716L), emptySet())
        assertEquals(UpgradeListResult.Selected(bloodWall, 18), result)
        // Twelve held swipes of 250 px and a thirteenth to the end at 3 198; none spent on the top.
        assertEquals(13, phone.swipes)
        assertEquals(2, phone.taps.size)
        assertEquals(upgradeListClosePoint(screen), phone.taps[1])
        assertTrue(phone.selected)
        assertFalse(phone.listOpen)
    }

    @Test fun aListAsLongAsBloodsAtTheCardsOwnSpacingEndsEmptyWithinTheBudget() = runBlocking {
        val phone = CardPhone(bloodLengthCards)
        val result = UpgradeListNavigator(phone, screen).findAndSelectWall(mapOf("gold" to 7_999_999L), emptySet())
        // Thirteen swipes to the end, two that move nothing: five to spare of twenty.
        assertEquals(UpgradeListResult.Empty(15, 1), result)
        assertTrue(phone.taps.isEmpty())
    }
}
