package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CardRetryTest {

    private fun spell(name: String = "jumps") =
        DeploymentGroup(name, Point(350, 650), listOf(Point(800, 360)), isSpell = true)

    private fun troop(name: String = "valkyries") =
        DeploymentGroup(name, Point(150, 650), listOf(Point(200, 100)))

    private fun hero(name: String = "king") =
        DeploymentGroup(name, Point(450, 650), listOf(Point(800, 360)), isHero = true)

    private fun sat(value: Double) = CardReading(value)

    @Test
    fun aSpellCardStuckOnOneReadingGetsNoRetry() {
        // The Jump card read about 120.98 battle after battle, spent or not: a reading that
        // does not move across the drops is no evidence of stock left.
        assertFalse(CardRetry.allowsRetry(spell(), baseline = 120.98, after = 120.98, attempt = 1))
        assertFalse(CardRetry.allowsRetry(spell(), baseline = 120.98, after = 124.0, attempt = 1))
    }

    @Test
    fun aSpellCardWithoutABaselineGetsNoRetry() {
        // No pre-drop reading means nothing the card reports can be checked against.
        assertFalse(CardRetry.allowsRetry(spell(), baseline = null, after = 150.0, attempt = 1))
    }

    @Test
    fun aSpellCardThatMovedGetsOneRetryAndNeverASecond() {
        val card = spell("rages")
        // Down or up, a reading that moved across the drops is a card that heard them.
        assertTrue(CardRetry.allowsRetry(card, baseline = 150.0, after = 90.0, attempt = 1))
        assertTrue(CardRetry.allowsRetry(card, baseline = 90.0, after = 150.0, attempt = 1))
        for (attempt in 2..CardRetry.MAX_ATTEMPTS) {
            assertFalse(CardRetry.allowsRetry(card, baseline = 150.0, after = 90.0, attempt = attempt))
        }
    }

    @Test
    fun troopsKeepTheWholeAttemptBudget() {
        for (attempt in 1 until CardRetry.MAX_ATTEMPTS) {
            assertTrue(CardRetry.allowsRetry(troop(), baseline = null, after = 150.0, attempt = attempt))
        }
        assertFalse(CardRetry.allowsRetry(troop(), baseline = null, after = 150.0, attempt = CardRetry.MAX_ATTEMPTS))
    }

    @Test
    fun aHeroCardIsNeverRetried() {
        // Once a hero is out its card stays coloured while the ability is ready, and a tap on it
        // fires the ability. A coloured hero card is no evidence the hero is still in the tray.
        for (attempt in 1..CardRetry.MAX_ATTEMPTS) {
            assertFalse(CardRetry.allowsRetry(hero(), baseline = null, after = 150.0, attempt = attempt))
            assertFalse(CardRetry.allowsRetry(hero(), baseline = 150.0, after = 150.0, attempt = attempt))
        }
    }

    @Test
    fun heroCardsAreNeitherReadNorRetriedNorGivenUp() {
        // 8 October, Sweat 10:18: all four heroes landed and their cards read 100-137, because a
        // landed hero's card is the ability button. That reading proves nothing either way.
        val heroes = listOf("king", "queen", "warden", "champion").map { hero(it) }
        val valkyries = troop()
        val retried = mutableListOf<DeploymentGroup>()
        val read = mutableListOf<String>()
        val givenUp = mutableListOf<String>()

        val looked = CardRetry.verify(
            groups = heroes + valkyries,
            baselines = emptyMap(),
            read = { pending -> read += pending.map { it.name }; pending.associate { it.name to sat(150.0) } },
            giveUp = { group, _, _ -> givenUp += group.name },
            retry = { pending -> retried += pending }
        )

        assertTrue(looked)
        assertTrue("no hero card is read", read.none { it in heroes.map { h -> h.name } })
        assertTrue("no hero card is tapped again", retried.none { it.isHero })
        assertEquals(List(CardRetry.MAX_ATTEMPTS - 1) { valkyries }, retried)
        assertEquals(listOf("valkyries"), givenUp)
    }

    @Test
    fun aHeroOnlyPhaseLooksAtNothing() {
        // Production deploys the four heroes as a phase of their own: no frame, no log, no tap.
        val heroes = listOf("king", "queen", "warden", "champion").map { hero(it) }

        val looked = CardRetry.verify(heroes, emptyMap(), { error("hero card read: $it") },
            { group, _, _ -> fail("hero card given up: ${group.name}") }, { fail("hero card retried: $it") })

        assertFalse(looked)
    }

    @Test
    fun aTroopCardThatGreysAfterOneRetryStopsThere() {
        val valkyries = troop()
        val retried = mutableListOf<DeploymentGroup>()
        var reads = 0

        CardRetry.verify(
            groups = listOf(valkyries),
            baselines = emptyMap(),
            read = { pending -> reads++; pending.associate { it.name to sat(if (reads == 1) 150.0 else 10.0) } },
            giveUp = { _, _, _ -> },
            retry = { pending -> retried += pending }
        )

        assertEquals(listOf(valkyries), retried)
        assertEquals(2, reads)
    }

    @Test
    fun aSpellThatMovedIsRetriedOnceAndGreysAfterIt() {
        val rages = spell("rages")
        val retried = mutableListOf<DeploymentGroup>()
        var reads = 0

        CardRetry.verify(listOf(rages), mapOf("rages" to sat(150.0)),
            { reads++; mapOf("rages" to sat(if (reads == 1) 90.0 else 20.0)) },
            { _, _, _ -> fail("a spell that greyed is not given up") }, { retried += it })

        assertEquals(listOf(rages), retried)
        assertEquals(2, reads)
    }

    @Test
    fun aSpellThatMovedButStaysStockedIsGivenUpAfterOneRetry() {
        val rages = spell("rages")
        val retried = mutableListOf<DeploymentGroup>()
        val givenUp = mutableListOf<Triple<String, Double, Int>>()

        CardRetry.verify(listOf(rages), mapOf("rages" to sat(150.0)), { mapOf("rages" to sat(90.0)) },
            { group, reading, attempt -> givenUp += Triple(group.name, reading.saturation, attempt) }, { retried += it })

        assertEquals(listOf(rages), retried)
        assertEquals(listOf(Triple("rages", 90.0, 2)), givenUp)
    }

    @Test
    fun aSpellStuckOnOneReadingIsGivenUpWithoutARetry() {
        val givenUp = mutableListOf<Triple<String, Double, Int>>()

        CardRetry.verify(listOf(spell()), mapOf("jumps" to sat(120.98)), { mapOf("jumps" to sat(120.98)) },
            { group, reading, attempt -> givenUp += Triple(group.name, reading.saturation, attempt) },
            { fail("a stuck spell reading is no evidence of stock") })

        assertEquals(listOf(Triple("jumps", 120.98, 1)), givenUp)
    }

    @Test
    fun aSpellWhoseBadgeCountedDownIsSpentWhileItsCardStaysColoured() {
        // Blood, 8 October: the jump card read 118.05 before and 115.01 after, every battle. Its
        // card carries x11 and one cast leaves ten, so it never greys. The badge going down does.
        val spent = mutableListOf<Pair<String, CardReading>>()

        val looked = CardRetry.verify(listOf(spell()), mapOf("jumps" to CardReading(118.05, badge = 11)),
            { mapOf("jumps" to CardReading(115.01, badge = 10)) },
            { group, _, _ -> fail("a spell whose badge dropped is not unverified: ${group.name}") },
            { fail("a spell whose badge dropped is not retried") },
            { group, reading -> spent += group.name to reading })

        assertTrue(looked)
        assertEquals(listOf("jumps" to CardReading(115.01, badge = 10)), spent)
    }

    @Test
    fun aSpellWhoseBadgeDidNotMoveIsStillUnverified() {
        val givenUp = mutableListOf<CardReading>()

        CardRetry.verify(listOf(spell()), mapOf("jumps" to CardReading(118.05, badge = 11)),
            { mapOf("jumps" to CardReading(115.01, badge = 11)) },
            { _, reading, _ -> givenUp += reading }, { fail("a still reading earns no retry") })

        assertEquals(listOf(CardReading(115.01, badge = 11)), givenUp)
    }

    @Test
    fun aBadgeThatCountedDownWinsOverASaturationMoveThatWouldRetry() {
        // 150 -> 130 moves 20, enough for the saturation rule to retry; the badge says it was cast.
        val spent = mutableListOf<String>()

        CardRetry.verify(listOf(spell("rages")), mapOf("rages" to CardReading(150.0, badge = 11)),
            { mapOf("rages" to CardReading(130.0, badge = 6)) },
            { _, _, _ -> fail("a spell whose badge counted down is not unverified") },
            { fail("badge counted down: no retry") },
            { group, _ -> spent += group.name })

        assertEquals(listOf("rages"), spent)
    }

    @Test
    fun aBadgeReadHigherFallsBackToTheSaturationRule() {
        // A misread 11 -> 12 proves nothing; 150 -> 90 still earns the one retry it always did.
        val rages = spell("rages")
        val retried = mutableListOf<DeploymentGroup>()
        val givenUp = mutableListOf<Int>()

        CardRetry.verify(listOf(rages), mapOf("rages" to CardReading(150.0, badge = 11)),
            { mapOf("rages" to CardReading(90.0, badge = 12)) },
            { _, _, attempt -> givenUp += attempt }, { retried += it })

        assertEquals(listOf(rages), retried)
        assertEquals(listOf(2), givenUp)
    }

    @Test
    fun aBaselineBadgeTheWiderBandNowReadsNeverAddsARetry() {
        // The badge band now runs past the card edge, so Sweat's jump baseline, null on every
        // logged battle, should read 1, and Blood's clipped event-tray x11 should read 11 (both
        // modelled). A baseline badge can only take a card out of the retry, never put one in,
        // whatever the read after the drops gives: unread, lower, equal or misread higher.
        fun retries(baselineBadge: Int?, after: CardReading): Int {
            var count = 0
            CardRetry.verify(listOf(spell()), mapOf("jumps" to CardReading(101.4, baselineBadge)),
                { mapOf("jumps" to after) }, { _, _, _ -> }, { count++ })
            return count
        }
        for (saturation in listOf(101.4, 85.0, 120.0, 20.0)) {
            for (afterBadge in listOf(null, 0, 1, 10, 11, 12)) {
                val after = CardReading(saturation, afterBadge)
                for (baselineBadge in listOf(1, 11)) {
                    assertTrue("baseline x$baselineBadge, after $after", retries(baselineBadge, after) <= retries(null, after))
                }
            }
        }
        // Still the one spell retry at most, with or without a badge.
        assertEquals(1, retries(null, CardReading(85.0)))
        assertEquals(1, retries(11, CardReading(85.0, badge = 12)))
        assertEquals(0, retries(1, CardReading(85.0, badge = 0)))
    }

    @Test
    fun aSpellBadgeIsReadAfterTheDropsOnlyWhenTheBaselineFoundOne() {
        // Blood's rages ROI sits on a card with no badge: four OCR passes per read for nothing.
        assertTrue(CardRetry.readsBadge(spell(), CardReading(118.0, badge = 11)))
        assertFalse(CardRetry.readsBadge(spell(), CardReading(105.0, badge = null)))
        assertFalse(CardRetry.readsBadge(spell(), null))
        assertFalse(CardRetry.readsBadge(troop(), CardReading(150.0, badge = 44)))
        assertFalse(CardRetry.readsBadge(hero(), CardReading(150.0, badge = 1)))
    }

    @Test
    fun anUnreadBadgeOnEitherSideProvesNothing() {
        assertFalse(CardRetry.spent(spell(), CardReading(115.0, badge = 10), CardReading(118.0, badge = null)))
        assertFalse(CardRetry.spent(spell(), CardReading(115.0, badge = null), CardReading(118.0, badge = 11)))
        assertFalse(CardRetry.spent(spell(), CardReading(115.0, badge = 10), null))
    }

    @Test
    fun aGreyedCardIsSpentWhateverItsBadge() {
        assertTrue(CardRetry.spent(spell(), CardReading(20.0, badge = null), null))
        assertTrue(CardRetry.spent(troop(), CardReading(20.0), null))
    }

    @Test
    fun onlyASpellIsJudgedByItsBadge() {
        // A troop card is dropped out whole; a lower troop badge is a partial drop, not spent.
        assertFalse(CardRetry.spent(troop(), CardReading(150.0, badge = 4), CardReading(150.0, badge = 44)))
    }

    @Test
    fun spentCardsAreReported() {
        val spent = mutableListOf<String>()
        CardRetry.verify(listOf(troop(), spell("rages")), mapOf("rages" to sat(150.0)),
            { pending -> pending.associate { it.name to sat(10.0) } }, { _, _, _ -> fail("all spent") },
            { fail("all spent") }, { group, _ -> spent += group.name })
        assertEquals(listOf("valkyries", "rages"), spent)
    }

    @Test
    fun nothingToCheckLooksAtNothing() {
        assertFalse(CardRetry.verify(emptyList(), emptyMap(), { error("no read") }, { _, _, _ -> }, { error("no retry") }))
    }
}
