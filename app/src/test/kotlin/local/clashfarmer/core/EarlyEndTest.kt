package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarlyEndTest {

    private val gold = Resource.GOLD
    private val elixir = Resource.ELIXIR

    @Test fun offByDefaultWhateverTheBattleShows() {
        assertNull(earlyEndReason(EarlyEndRules(), 170_000L, 1, emptyMap()))
        assertNull(earlyEndReason(EarlyEndRules(afterDeploySeconds = 10), 170_000L, 1, emptyMap()))
    }

    @Test fun enabledWithNoTriggerWaitsTheBattleOut() {
        val rules = EarlyEndRules(enabled = true)
        assertEquals(false, rules.anyTrigger)
        assertNull(earlyEndReason(rules, 170_000L, 1, mapOf(gold to 0L)))
    }

    @Test fun secondsAfterDeploy() {
        val rules = EarlyEndRules(enabled = true, afterDeploySeconds = 60)
        assertNull(earlyEndReason(rules, 59_999L, null, emptyMap()))
        assertEquals(EarlyEndReason.AFTER_DEPLOY, earlyEndReason(rules, 60_000L, null, emptyMap()))
    }

    @Test fun battleClockAtOrBelowTheSetting() {
        val rules = EarlyEndRules(enabled = true, battleSecondsLeft = 90)
        assertNull(earlyEndReason(rules, 5_000L, 91, emptyMap()))
        assertEquals(EarlyEndReason.BATTLE_TIMER, earlyEndReason(rules, 5_000L, 90, emptyMap()))
    }

    @Test fun anUnreadClockNeverEndsABattle() {
        val rules = EarlyEndRules(enabled = true, battleSecondsLeft = 90)
        assertNull(earlyEndReason(rules, 170_000L, null, emptyMap()))
    }

    @Test fun lootLeftNeedsEveryListedResourceReadAndBelow() {
        val rules = EarlyEndRules(enabled = true, lootLeftBelow = mapOf(gold to 50_000L, elixir to 50_000L))
        assertEquals(EarlyEndReason.LOOT_LEFT,
            earlyEndReason(rules, 20_000L, null, mapOf(gold to 49_999L, elixir to 10_000L)))
        // One resource still worth taking keeps the battle going.
        assertNull(earlyEndReason(rules, 20_000L, null, mapOf(gold to 49_999L, elixir to 50_000L)))
        // An unread row is unknown, not zero.
        assertNull(earlyEndReason(rules, 20_000L, null, mapOf(gold to 0L)))
    }

    /**
     * 22 September 00:41:54: 7% read as "79%" with the sign taken for a 9, and 8% as "89%" on four
     * frames over 24 s. A lone frame like that at the start of a battle must not surrender it.
     */
    @Test fun aLoneSignAsNineFrameAtTheStartDoesNotSurrender() {
        val rules = EarlyEndRules(enabled = true, atDamage = true)
        val proof = DamageProof()
        assertNull(earlyEndReason(rules, 3_000L, null, emptyMap(), proof.admit(79), damageTarget = 70))
        assertNull(earlyEndReason(rules, 5_000L, null, emptyMap(), proof.admit(89), damageTarget = 70))
        assertNull(proof.admit(99))
        assertNull(proof.admit(9))
        assertNull(proof.admit(null))
        // A two-digit value not ending in 9 proves the damage is past 9; after that X9 is real.
        assertEquals(72, proof.admit(72))
        assertEquals(EarlyEndReason.DAMAGE,
            earlyEndReason(rules, 9_000L, null, emptyMap(), proof.admit(79), damageTarget = 70))
        // The next battle starts unproven.
        proof.reset()
        assertNull(proof.admit(79))
        assertEquals(80, DamageProof().admit(80))
        assertEquals(100, DamageProof().admit(100))
    }

    @Test fun damageAtOrAboveThisBattlesTarget() {
        val rules = EarlyEndRules(enabled = true, atDamage = true)
        assertEquals(true, rules.anyTrigger)
        assertNull(earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 52, damageTarget = 53))
        assertEquals(EarlyEndReason.DAMAGE,
            earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 53, damageTarget = 53))
        assertEquals(EarlyEndReason.DAMAGE,
            earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 61, damageTarget = 53))
        // An unread damage is unknown, not zero, and the setting off ignores it.
        assertNull(earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = null, damageTarget = 50))
        assertNull(earlyEndReason(EarlyEndRules(enabled = true), 20_000L, null, emptyMap(), 99, 50))
    }

    @Test fun eachBattlesTargetIsInTheRange() {
        assertEquals(setOf(70), (1..50).map { pickDamageTarget(EarlyEndRules()) }.toSet())
        val rules = EarlyEndRules(damageMin = 72, damageMax = 75)
        assertEquals((72..75).toSet(), (1..300).map { pickDamageTarget(rules) }.toSet())
        assertEquals(setOf(80), (1..20).map { pickDamageTarget(EarlyEndRules(damageMin = 80, damageMax = 80)) }.toSet())
    }

    @Test fun theDefaultSurrendersOnlyOnceTheLeagueBonusIsFull() {
        // Bonus percent = min(100, damage + 30), so 70% damage is the first full bonus.
        assertEquals(70, LEAGUE_BONUS_DAMAGE_FLOOR)
        assertEquals(70, DEFAULT_DAMAGE_MIN)
        assertEquals(70, EarlyEndRules().damageMin)
    }

    @Test fun aSavedRangeBelowTheFloorIsLiftedAndLogged() {
        val warnings = mutableListOf<Triple<Int, Int, Int>>()
        val rules = EarlyEndRules(enabled = true, atDamage = true, damageMin = 50, damageMax = 55)
        val targets = (1..50).map { pickDamageTarget(rules) { min, max, t -> warnings += Triple(min, max, t) } }
        assertEquals(setOf(70), targets.toSet())
        assertEquals(50, warnings.size)
        assertEquals(Triple(50, 55, 70), warnings.first())
    }

    @Test fun aRangeAcrossTheFloorKeepsTheOperatorsUpperBound() {
        val warnings = mutableListOf<Int>()
        val rules = EarlyEndRules(enabled = true, atDamage = true, damageMin = 60, damageMax = 75)
        val targets = (1..400).map { pickDamageTarget(rules) { _, _, t -> warnings += t } }.toSet()
        assertEquals((70..75).toSet(), targets)
        assertEquals(400, warnings.size)
    }

    @Test fun noWarningAtOrAboveTheFloorOrWithTheDamageTriggerOff() {
        val warnings = mutableListOf<Int>()
        pickDamageTarget(EarlyEndRules(enabled = true, atDamage = true)) { _, _, t -> warnings += t }
        // A low saved range that is not in use still targets the floor, but says nothing.
        assertEquals(70, pickDamageTarget(EarlyEndRules(damageMin = 50, damageMax = 55)) { _, _, t -> warnings += t })
        assertEquals(70, pickDamageTarget(EarlyEndRules(enabled = true, damageMin = 50, damageMax = 55)) { _, _, t -> warnings += t })
        assertEquals(emptyList<Int>(), warnings)
    }

    @Test fun theDefaultDamageTargetRespectsTheFloor() {
        val rules = EarlyEndRules(enabled = true, atDamage = true, damageMin = 50, damageMax = 55)
        assertNull(earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 69))
        assertEquals(EarlyEndReason.DAMAGE, earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 70))
    }

    @Test fun lootLeftWaitsForThePanelToCountDown() {
        val rules = EarlyEndRules(enabled = true, lootLeftBelow = mapOf(gold to 50_000L))
        assertNull(earlyEndReason(rules, MIN_MS_BEFORE_LOOT_END - 1, null, mapOf(gold to 0L)))
        assertEquals(EarlyEndReason.LOOT_LEFT, earlyEndReason(rules, MIN_MS_BEFORE_LOOT_END, null, mapOf(gold to 0L)))
    }

    @Test fun aStallNeedsTheSwitchAndAFiredDetector() {
        val rules = EarlyEndRules(enabled = true, onStall = true)
        assertEquals(true, rules.anyTrigger)
        assertEquals(EarlyEndReason.STALL,
            earlyEndReason(rules, 20_000L, null, emptyMap(), stalled = true))
        // A detector that has not decided decides nothing, switch or not.
        assertNull(earlyEndReason(rules, 20_000L, null, emptyMap(), stalled = false))
    }

    @Test fun aFiredDetectorWithTheSwitchOffDecidesNothing() {
        val rules = EarlyEndRules(enabled = true)
        assertEquals(false, rules.anyTrigger)
        assertNull(earlyEndReason(rules, 20_000L, null, emptyMap(), stalled = true))
    }

    @Test fun aStallRespectsTheMasterSwitch() {
        assertNull(earlyEndReason(EarlyEndRules(onStall = true), 170_000L, null, emptyMap(), stalled = true))
    }

    @Test fun theDamageTriggerStillOutranksAStall() {
        val rules = EarlyEndRules(enabled = true, onStall = true, atDamage = true)
        assertEquals(EarlyEndReason.DAMAGE,
            earlyEndReason(rules, 20_000L, null, emptyMap(), damagePercent = 70, damageTarget = 65, stalled = true))
    }
}
