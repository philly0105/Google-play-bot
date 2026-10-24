package local.clashfarmer.desktop

import local.clashfarmer.recognition.BattleDamage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
