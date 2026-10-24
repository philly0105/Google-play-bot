package local.clashfarmer.recognition

import local.clashfarmer.core.DamageProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Readings copied from the battle frames logged from 19 to 29 September. */
class BattleDamageTest {

    private fun damage(tail: String) =
        BattleDamage.overallPercent("akela Available Loot: ENd Battle Battleends in: IM 325 39 X 3 821964 $tail")

    @Test fun cleanReadings() {
        assertEquals(92, damage("OveRall Damage 92% "))
        assertEquals(49, damage("OveRall DaMage 49%"))
        assertEquals(80, damage("OveRall Damagge 80%"))
        assertEquals(65, damage("OveRall Damage. 65%"))
        assertEquals(70, damage("OveRallDaMage 70%"))
        assertEquals(66, damage("OveRall Damage G6% I||"))
    }

    @Test fun lettersTheRecogniserPutsForDigits() {
        assertEquals(52, damage("OveRall Damage S2%"))
        assertEquals(50, damage("OveRall Damage SO%"))
        assertEquals(60, damage("OveRall Damage G0%"))
        assertEquals(100, damage("OveRall Damage iOO%"))
    }

    /**
     * Spellings of "Overall" from the battle frames logged 18 to 24 September; 273 of 1358 frames
     * lost their damage to the word alone. Every value below matches its neighbouring frames.
     */
    @Test fun theWordOverallAsThePhoneSpellsIt() {
        assertEquals(66, damage("OveRalI Damage 66% "))
        assertEquals(73, damage("Overal Damage 73% "))
        assertEquals(54, damage("OveRal Damage 54% "))
        assertEquals(57, damage("OveRallI Damage S7% "))
        assertEquals(84, damage("OveRaIl Damage 84% "))
        assertEquals(49, damage("OveRaII Damage 49% "))
        assertEquals(79, damage("OveRalW Damage 79% "))
        assertEquals(79, damage("OveRalT Damage 79% "))
        assertEquals(49, damage("OveRalIl DaMage 49% "))
        assertEquals(63, damage("OveRalI, Damage 63% "))
        assertEquals(100, damage("OveRalIDamage 100% "))
        assertEquals(76, damage("OVeRalI Damage 76% "))
        assertEquals(51, damage("OveRalIl Daage S1% "))
        assertEquals(23, damage("OveRalI Damage 23% 10"))
        assertEquals(12, damage("OveRalI Damage 12% 1002 77 "))
    }

    @Test fun aMisspelledWordDoesNotLetAnUnsafeNumberThrough() {
        // The sign read as a digit. Other HUD numbers between the word and the value were here
        // too; since the badge skip they are pinned in aHeroLevelBadgeBeforeTheValueIsSkipped.
        assertNull(damage("OveRalI Damage 7076 "))
        assertNull(damage("Overall DáMage \$39% "))
        // A base called "OVER POWERED" is not the damage line.
        assertNull(BattleDamage.overallPercent(
            "DARK SAVIOUR 23 OVER POWERED Available Loot: 395 835 374 294 2535 SURReNdeR XO 100 100 77 50"))
        assertNull(BattleDamage.overallPercent("CLOVER DEN 45% Available Loot: 395 835"))
    }

    @Test fun readingsWithAStrayDigitOrNoSignAreUnread() {
        assertNull(damage("OveRall Damage 783"))
        assertNull(damage("OveRall Damage 9896"))
        assertNull(damage("OveRall Damage"))
        assertNull(BattleDamage.overallPercent("Total damage: VictoRY You got: 20 100%"))
        // 14 October, r4-log-run3.jsonl:377: 53% with the sign read as "2%".
        assertNull(BattleDamage.overallPercent("2M 185A LIGA DA JU STiCA XO XO 9653 197J 10 680 S51 273 417 Overall Damage 532% "))
        // The sign read as a 6 with no "%": 52% on overnight/w4-proof-blood.jsonl:139, 48% on r4-log-run3.jsonl:1888.
        assertNull(BattleDamage.overallPercent("s in: 2M 335 AwesoMe! XI 12 5 28 096 860 27 965 741( 472 58S OveRalI Damage S26 "))
        assertNull(BattleDamage.overallPercent("0 78 Battle ends in: 2M 245 5 X1 18 003 807 5739 360 267 344 OveRall Damage 486 "))
        // Three digits with a leading zero, or a stray digit other than a closing 9.
        assertNull(damage("OveRall Damage 059%"))
        assertNull(damage("OveRall Damage 712%"))
    }

    /**
     * The recogniser reads the sign after two digits as "9%". Each tail below is the end of a
     * stall_damage_unreadable line, and each value lies within the reads either side of it in the
     * same battle.
     */
    @Test fun theSignReadAsANineAfterTwoDigitsIsDropped() {
        // r4-log-run3.jsonl:1483, one of fourteen such frames between reads of 52; the battle ended at 52%.
        assertEquals(52, BattleDamage.overallPercent("s in: 2M 105 TeHRaNwansioRS 5 X1 1511 66 2 585 665 246 21I0 OveRalI Damage S29% "))
        // r4-log-run3.jsonl:380 (reads 47 and 60 around it) and :1052 (54 and 60).
        assertEquals(58, BattleDamage.overallPercent("n: ZM125 LuGA DA JUSTICA XO XO 9 672 008 10 680 551 273 417 OveRall Damage S89% "))
        assertEquals(58, BattleDamage.overallPercent("attle ends in: 2M285 TA 11 X10 5 4 739 13 S 168 738 178 140 OveRalI Damage S89% "))
        // r4-log-run3.jsonl:328 (57 and 62), :968 (42 and 43), :1766 (10 and 16).
        assertEquals(59, BattleDamage.overallPercent("3 66 61 39 Battle ends in: 2M265 8489 702 9775 469 256 367 OveRalIl Damage 599% "))
        assertEquals(42, BattleDamage.overallPercent("8 Battle ends in: 2M 195 33 X10 5 2558 274 4898 O1S 173 388 OveRall Damage 429% "))
        assertEquals(12, BattleDamage.overallPercent(" 78 Battle ends in: 2M 36s 1 5 16 419 756 3 087 253 253 298 OveRall Damage 129% "))
        // overnight/w4-proof-blood.jsonl:143 (58 and 61).
        assertEquals(59, BattleDamage.overallPercent("Battle ends in: 2M 235 XI 12 5 28 096 860 27 965741 472 585 OveRall Damage S99% "))
        // The same shape; no log line has this one.
        assertEquals(87, damage("OveRall Damage 879%"))
        assertEquals(52, damage("OveRall Damage S29%"))
    }

    /** The recogniser reads a five as "$" as well as "S". */
    @Test fun aFiveReadAsADollarIsAFive() {
        // r4-log-run3.jsonl:2150 (reads 53 and 54 around it) and :2235 (52 and 54; the battle ended at 54%).
        assertEquals(53, BattleDamage.overallPercent("RIS 78 Battle ends in: 2M 13s 5 X 18 985 O1 6153 648 274 291 OveRall Damage \$3% "))
        assertEquals(53, BattleDamage.overallPercent("ends in: IM 555 11 UPRRClat 5 XI 20 232 891 7353 004 279 435 OveRall Damage \$3% "))
        // overnight/w4-proof-blood.jsonl:234 (52 and 54).
        assertEquals(53, BattleDamage.overallPercent("attle ends in: 2M 1S XIO 12 5 29 000 o00( 28 632 442 472 58S OveRalI Damage \$3% "))
        // overnight/log-snapshot.jsonl:2719 (56 and 63): a five read as "$" before a real 9, so 59.
        assertEquals(59, BattleDamage.overallPercent("9 Battle ends in: 2M 225S XO XO 26 000 O00 26 D00 O00 277 174 OveRal Damage \$9% "))
        assertEquals(53, damage("OveRall Damagge \$3%"))
    }

    /**
     * Since Clash 18.647 a hero card's level badge stands between the word and the value. The
     * first four are stall_damage_unreadable lines of a one-attack Blood run on 14 October
     * (accept/day-log-2157.jsonl:3265, 3284, 3257, 3254), whose screens showed 34%, 34%, 33% and 29%.
     */
    @Test fun aHeroLevelBadgeBeforeTheValueIsSkipped() {
        assertEquals(34, BattleDamage.overallPercent(" X2 X1 19 X2 13 X 4 18 854 633 22 723 270 199 232 OveRall Damage 100 34% 100 78"))
        assertEquals(34, BattleDamage.overallPercent("X1 19 I2 X2 13 X 4 18 854 633 22 723 270 199 232 OveRallI Damage 100 34% 100 78"))
        assertEquals(33, BattleDamage.overallPercent("5 13 X2 X 19 13 X 4 18 854 633 22 723 174 199 232 OveRall Damage 100 33% 100 78"))
        assertEquals(29, BattleDamage.overallPercent("365 13 X2 X 19 13 X 4 18 854 633 22422397 199 232 OveRall Damage 100 29% 100 78"))
        assertEquals(100, damage("OveRall Damage 100% 100 78"))
        assertEquals(100, damage("OveRall Damage 100 100% "))
        // The value after the badge is held to the same rules as one straight after the word.
        assertEquals(52, damage("OveRall Damage 100 S29% 100 78"))
        assertNull(damage("OveRall Damage 100 532% 100 78"))
        // 2026-09-22-log-final.jsonl:6893, the same shape a month earlier: 22% read right. And at
        // :7249, 8% with its sign read as a 9 on four frames over 24 s. That read now comes back
        // as 89, as "Damage 89%" always has; DamageProof keeps it from early end (below).
        assertEquals(22, damage("OveRalI Damage 100 22% 75 "))
        assertEquals(89, damage("OveRalI Damage 100 89% 75 "))
    }

    /** Only a lone run of two or three plain digits is a badge; anything else stays unread. */
    @Test fun anyOtherTokenBeforeTheValueIsNotSkipped() {
        // One digit may be the front of a split "52%".
        assertNull(damage("OveRall Damage 5 2% "))
        // OCR letters: the token may be the value itself.
        assertNull(damage("OveRall Damage 1O0 34% 100 78"))
        assertNull(damage("OveRall Damage I00 34% 100 78"))
        // Four digits are not a hero level, and a badge needs a space on both sides.
        assertNull(damage("OveRall Damage 1000 34% 100 78"))
        assertNull(damage("OveRall Damage100 34% 100 78"))
        assertNull(damage("OveRall Damage 10034% 100 78"))
        // Two tokens: run2h-log.jsonl logged 98 frames of this shape; one is skipped, not two.
        assertNull(damage("OveRall Damage 79 11 16% 4 "))
        // A number without its sign, behind a badge or not.
        assertNull(damage("OveRall Damage 446"))
        assertNull(damage("OveRall Damage 100 34"))
        assertNull(damage("OveRall Damage 100 34 100 78"))
    }

    /**
     * The 22 September battle above, frame by frame through early end's proof: 7% read as 79, then
     * 8, then 8% read as 89 four times. None of it reaches early end.
     */
    @Test fun aSignReadAsANineBehindABadgeStillWaitsForProof() {
        val proof = DamageProof()
        listOf("100 79% 75", "100 8% 75", "100 89% 75", "100 89% 75", "100 89% 75", "100 89% 75").forEach {
            assertNull(it, proof.admit(damage("OveRalI Damage $it ")))
        }
        assertEquals(12, proof.admit(damage("OveRalI Damage 100 12% 75 ")))
    }
}
