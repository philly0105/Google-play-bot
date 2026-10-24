package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {

    private val gold = Resource.GOLD
    private val elixir = Resource.ELIXIR
    private val none = emptyMap<Resource, Long>()

    private fun feedAll(d: StallDetector, vararg samples: Triple<Long, Int?, Map<Resource, Long>>): StallDecision? {
        var fired: StallDecision? = null
        for ((t, damage, loot) in samples) {
            d.feed(t, damage, loot)?.let { fired = it }
        }
        return fired
    }

    @Test fun aFlatDamagePlateauWithNoLootFiresDamageOnly() {
        val d = StallDetector()
        feedAll(d,
            Triple(15_000L, 40, none),
            Triple(25_000L, 40, none),
            Triple(35_000L, 40, none),
            Triple(44_999L, 40, none))
        val decision = d.feed(45_000L, 40, none)
        assertEquals(StallRule.DAMAGE_ONLY, decision?.rule)
        assertEquals(15_000L, decision?.windowStartMs)
        assertEquals(45_000L, decision?.windowEndMs)
    }

    @Test fun damageStillClimbingNeverFires() {
        val d = StallDetector()
        assertNull(feedAll(d,
            Triple(15_000L, 40, none),
            Triple(30_000L, 45, none),
            Triple(60_000L, 52, none),
            Triple(90_000L, 61, none),
            Triple(120_000L, 70, none)))
        assertNull(d.decision)
    }

    @Test fun lootStillFallingWithFlatDamageDoesNotFire() {
        val d = StallDetector()
        // Gold draining is progress even while the damage reading sits still, so neither the
        // progress rule nor the damage-only rule may call this a stall.
        assertNull(feedAll(d,
            Triple(15_000L, 50, mapOf(gold to 600_000L)),
            Triple(25_000L, 50, mapOf(gold to 500_000L)),
            Triple(35_000L, 50, mapOf(gold to 400_000L)),
            Triple(45_000L, 50, mapOf(gold to 300_000L)),
            Triple(55_000L, 50, mapOf(gold to 200_000L)),
            Triple(65_000L, 50, mapOf(gold to 100_000L))))
    }

    @Test fun samplesThatNeverReadDecideNothing() {
        val d = StallDetector()
        assertNull(feedAll(d,
            Triple(15_000L, null, none),
            Triple(30_000L, null, none),
            Triple(45_000L, null, none),
            Triple(60_000L, null, none)))
    }

    @Test fun aWindowWithoutReadableEndsDoesNotFire() {
        // The readable stretch is ten seconds; the unreadable samples around it are not quiet.
        val d = StallDetector()
        assertNull(feedAll(d,
            Triple(0L, null, none),
            Triple(10_000L, 40, none),
            Triple(20_000L, 40, none),
            Triple(30_000L, null, none),
            Triple(45_000L, null, none),
            Triple(60_000L, null, none)))

        // Same the other way round: quiet up front, then silence.
        val d2 = StallDetector()
        assertNull(feedAll(d2,
            Triple(15_000L, 40, none),
            Triple(25_000L, 40, none),
            Triple(35_000L, null, none),
            Triple(60_000L, null, none)))
    }

    @Test fun nothingFiresBeforeTheMinimumAfterDeploy() {
        // A ten-second window at ten seconds would decide; the battle is still too young.
        val d = StallDetector(stallMs = 10_000L, damageOnlyMs = 10_000L)
        assertNull(d.feed(0L, 40, none))
        assertNull(d.feed(10_000L, 40, none))
        assertEquals(StallRule.DAMAGE_ONLY, d.feed(20_000L, 40, none)?.rule)
    }

    @Test fun aStallIsReportedOncePerBattle() {
        val d = StallDetector()
        val first = feedAll(d,
            Triple(15_000L, 40, none),
            Triple(30_000L, 40, none),
            Triple(45_000L, 40, none))
        assertEquals(StallRule.DAMAGE_ONLY, first?.rule)
        // The battle goes on quiet after the report; no second decision is made of it.
        assertNull(d.feed(60_000L, 40, none))
        assertNull(d.feed(75_000L, 40, none))
        assertEquals(first, d.decision)
    }

    @Test fun flatLootAndDamageFireTheProgressRule() {
        val d = StallDetector()
        val decision = feedAll(d,
            Triple(15_000L, 40, mapOf(gold to 500_000L, elixir to 250_000L)),
            Triple(25_000L, 40, mapOf(gold to 500_000L, elixir to 250_000L)),
            Triple(35_000L, 40, mapOf(gold to 500_000L, elixir to 250_000L)),
            Triple(45_000L, 40, mapOf(gold to 500_000L, elixir to 250_000L)))
        assertEquals(StallRule.PROGRESS_STALLED, decision?.rule)
        assertEquals(15_000L, decision?.windowStartMs)
        assertEquals(45_000L, decision?.windowEndMs)
    }

    @Test fun aRecordedTrajectoryWithGapsStillReachesAVerdict() {
        // Reads like a battle's log looks: the damage comes and goes, the loot row reads only
        // when the cuts agree, and whole frames come back with nothing on them.
        val d = StallDetector()
        feedAll(d,
            Triple(15_000L, 42, mapOf(gold to 500_000L)),
            Triple(17_000L, null, none),
            Triple(20_000L, 42, mapOf(gold to 500_000L)),
            Triple(32_000L, 42, none),
            Triple(38_000L, null, none))
        val decision = d.feed(45_000L, 42, mapOf(gold to 500_000L))
        // The loot ends fifteen to forty-five seconds apart held at half a million.
        assertEquals(StallRule.PROGRESS_STALLED, decision?.rule)
        assertEquals(15_000L, decision?.windowStartMs)
        assertEquals(45_000L, decision?.windowEndMs)
        assertTrue(d.trajectory().contains("15s:42%:gold=500000"))
    }
}
