package local.clashfarmer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HeroAbilitiesTest {

    private fun hero(name: String, x: Int) =
        DeploymentGroup(name, Point(x, 650), listOf(Point(800, 360)), isHero = true)

    private val heroes = listOf(hero("king", 400), hero("queen", 480), hero("warden", 560), hero("champion", 640))

    /** A clock that only moves when the code under test waits. */
    private class FakeClock(var now: Long = 0L) {
        val events = mutableListOf<String>()
        val taps = mutableListOf<Triple<String, Point, Long>>()
        val tapTimes = mutableMapOf<String, Long>()

        suspend fun fire(abilities: HeroAbilities) = abilities.fire(
            now = { now },
            wait = { ms -> assertTrue("never waits a negative time", ms >= 0); now += ms },
            clearBanner = { events += "banner" },
            tap = { name, point, delayMs ->
                events += name
                taps += Triple(name, point, delayMs)
                tapTimes[name] = now
            }
        )
    }

    @Test
    fun fourHeroesGetExactlyOneAbilityTapEachAfterTheDelay() = runBlocking {
        val abilities = HeroAbilities()
        val clock = FakeClock()
        val doneAt = mutableMapOf<String, Long>()
        for (group in heroes) {
            clock.now += 850
            abilities.dropped(group, clock.now)
            doneAt[group.name] = clock.now
        }

        clock.fire(abilities)
        // Asked again - a later phase, a second call by mistake - nothing more is tapped.
        clock.fire(abilities)

        assertEquals(listOf("banner", "king", "queen", "warden", "champion"), clock.events)
        assertEquals(heroes.map { it.selectPoint }, clock.taps.map { it.second })
        for (group in heroes) {
            val waited = clock.tapTimes.getValue(group.name) - doneAt.getValue(group.name)
            assertTrue("${group.name} waited $waited ms", waited >= HeroAbilities.HERO_ABILITY_DELAY_MS)
        }
        for ((_, _, delayMs) in clock.taps) {
            assertTrue(delayMs >= HeroAbilities.HERO_ABILITY_DELAY_MS)
        }
    }

    @Test
    fun aHeroWhoseDropWasNeverSentGetsNoAbilityTap() = runBlocking {
        val abilities = HeroAbilities()
        val clock = FakeClock()
        // The warden's and champion's drops never went out (the run stopped mid-phase).
        abilities.dropped(heroes[0], 100)
        abilities.dropped(heroes[1], 900)

        clock.fire(abilities)

        assertEquals(listOf("king", "queen"), clock.taps.map { it.first })
    }

    @Test
    fun aDropSentTwiceStillGetsOneTapTimedFromTheFirst() = runBlocking {
        val abilities = HeroAbilities()
        val clock = FakeClock()
        abilities.dropped(heroes[0], 100)
        abilities.dropped(heroes[0], 2_000)

        clock.fire(abilities)

        assertEquals(listOf("king"), clock.taps.map { it.first })
        assertEquals(100 + HeroAbilities.HERO_ABILITY_DELAY_MS, clock.tapTimes.getValue("king"))
    }

    @Test
    fun troopsAndSpellsAreNotHeroes() = runBlocking {
        val abilities = HeroAbilities()
        val clock = FakeClock()
        abilities.dropped(DeploymentGroup("valkyries", Point(150, 650), listOf(Point(200, 100))), 0)
        abilities.dropped(DeploymentGroup("rages", Point(250, 650), listOf(Point(800, 360)), isSpell = true), 0)

        clock.fire(abilities)

        assertEquals("no banner check and no tap", emptyList<String>(), clock.events)
    }

    @Test
    fun aTapThatFailedIsNotTriedAgain() = runBlocking {
        val abilities = HeroAbilities()
        heroes.forEachIndexed { i, group -> abilities.dropped(group, i * 850L) }
        val tapped = mutableListOf<String>()
        var now = 0L
        try {
            abilities.fire({ now }, { now += it }, {}, { name, _, _ ->
                tapped += name
                if (name == "queen") throw IllegalStateException("gesture cancelled")
            })
            fail("the failed tap should propagate")
        } catch (e: IllegalStateException) {
            // expected
        }
        abilities.fire({ now }, { now += it }, {}, { name, _, _ -> tapped += name })

        assertEquals(listOf("king", "queen", "warden", "champion"), tapped)
    }

    @Test
    fun theDelayKeepsTheOldRetryTiming() {
        // The removed retry re-tapped each hero's card about 4 s after that hero's own drops
        // (three later heroes' drops, a 400 ms settle and a ~1.1 s read). Keep it comparable.
        assertTrue(HeroAbilities.HERO_ABILITY_DELAY_MS in 3_000L..5_000L)
    }
}
