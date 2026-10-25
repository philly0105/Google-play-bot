package local.clashfarmer.desktop

import local.clashfarmer.recognition.BattleDamage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battle's damage as the PC's recogniser orders it, with the battle button's label between the
 * word and the value. Each reading is a stall_damage_unreadable line of Blood's supervised attack of
 * 24 October (audit-evidence/live/20261024-163029-supervised/events.jsonl), in order.
 */
class PcBattleDamageTest {

    @Test
    fun `the button's label between the word and the value is skipped`() {
        val readings = listOf(
            " Loot: . 8 368 787 1 013 059 095 676 154 465 s 112 OveRall Damage SURReNdeR 40% " to 40,
            "e Loot: 8 568 342 813 504 895 121 155 462 4 115 Overall Damage SURReNdeR 47% XO " to 47,
            "Loot: 48 848 839 813 504 615 624 155 462 4 115 OveRall Damage End Battle 52% XO " to 52,
            "t: 48 848 839 555 673 615 624 155 462 4 115 Overall Damage End Battle 58% XO 80 " to 58,
            " Loot: 8 924 038 551 366 540 425 157 194 2 383 Overall Damage End Battle 65% XO " to 65,
            "ble Loot: 8 924 038 524 276 540 425 157 194 2 383 OveRall Damage End Battle 68% " to 68,
            "ble Loot: 8 947 477 524 276 513 110 157 194 2 383 OveRall Damage End Battle 71% " to 71,
            "ble Loot: 8 951 353 524 276 513 110 157 194 2 383 OveRall Damage End Battle 72% " to 72,
            "e Loot: 8 951 353 524 276 513 110 157 194 2 383 OveRall Damage End Battle 72% 8 " to 72
        )
        for ((text, percent) in readings) assertEquals(text, percent, BattleDamage.overallPercent(text))
    }

    @Test
    fun `the value after the label keeps the phone's rules`() {
        assertEquals(52, BattleDamage.overallPercent("OveRall Damage End Battle S2% "))
        assertEquals(34, BattleDamage.overallPercent("OveRall Damage End Battle 100 34% 100 78"))
        assertNull(BattleDamage.overallPercent("OveRall Damage End Battle 532% "))
        assertNull(BattleDamage.overallPercent("OveRall Damage End Battle 68 "))
        // Two words between are not one label.
        assertNull(BattleDamage.overallPercent("OveRall Damage Surrender Surrender 40% "))
    }

    /**
     * The stars beside the value read as a lone "e", "d" or "*". Each reading is the tail of a
     * stall_damage_unreadable line of Blood's unattended run of 25 October, in order; every one of
     * them went unread then, twelve in a row in one battle that ended at 93%.
     */
    @Test
    fun `a lone glyph beside the label or the value is skipped`() {
        val readings = listOf(
            "4 252 9 OveRall Damage SURReNdeR e 48% XO XO XO 80 R " to 48,
            "238 888 e Overall Damage End Battle e 99% x0 XO 80 12 R " to 99,
            "4 324 * e Overall Damage SURReNdeR e 44% XO 80 12 R " to 44,
            "754 1543 Overall Damage d End Battle 69% MM XO e 80 12 R " to 69,
            "754 1543 e de Overall Damage d End Battle 75% XO 0 " to 75,
            "329 207 90 Overall Damage d End Battle 81% XO 80 " to 81,
            "329 207 90 e Overall Damage d End Battle 83% 80 " to 83,
            "329 207 90 e Overall Damage d End Battle * 87% 4 " to 87,
            "329 207 90 Overall Damage d End Battle * 88% 4 XO xO " to 88,
            "329 207 90 e Overall Damage d End Battle 88% XO xO " to 88,
            "329 207 90 e Overall Damage d End Battle 90% 4 " to 90,
            "329 264 e Overall Damage d End Battle * 92% 4 " to 92,
            "329 297 e Overall Damage d End Battle * 93% 4 XO XO " to 93,
            "329 297 Overall Damage End Battle * 93% 4 XO XO " to 93,
            "341 465 1 665 Overall Damage End Battle * 65% " to 65,
            "783 2 973 e Overall Damage End Battle * 79% XO " to 79,
            "783 2 973 * Overall Damage End Battle * 83% " to 83,
            "411781 1 683 Overall Damage End Battle * 49% x0 " to 49
        )
        for ((text, percent) in readings) assertEquals(text, percent, BattleDamage.overallPercent(text))
        // Two strays on each side still read; a stray before the hero level's skip as well.
        assertEquals(57, BattleDamage.overallPercent("Overall Damage e d End Battle * e 57% "))
        assertEquals(34, BattleDamage.overallPercent("Overall Damage End Battle * 100 34% "))
    }

    @Test
    fun `a stray that could be a digit or the sign is not skipped`() {
        // o and B can be a split value's first digit, and three strays are not stars.
        assertNull(BattleDamage.overallPercent("Overall Damage o 48% "))
        assertNull(BattleDamage.overallPercent("Overall Damage B 8% "))
        assertNull(BattleDamage.overallPercent("Overall Damage End Battle 5 % "))
        assertNull(BattleDamage.overallPercent("Overall Damage End Battle e * d 48% "))
        // The result screen's damage is not the battle's.
        assertNull(BattleDamage.overallPercent("31 000 000 11 071 869 Total damage: 0% 174 833 VictORY End Battle "))
    }

    @Test
    fun `the result screen sweeping in is told apart from a miss`() {
        assertTrue(BattleDamage.resultWipe("31 000 000 11 071 869 Total damage: 0% 174 833 VictORY End Battle "))
        assertTrue(BattleDamage.resultWipe("31 000 000 12 730 631 Total damage: 0% 189 791 VictORY"))
        assertFalse(BattleDamage.resultWipe("Overall Damage End Battle 68% "))
        assertFalse(BattleDamage.resultWipe("Overall Damage d End Battle * 87% 4 "))
    }
}
