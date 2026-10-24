package local.clashfarmer.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AttackPacingTest {
    @Test fun waitsAreShortOrABreakAndBothKindsHappen() {
        val rng = Random(7)
        val waits = List(4000) { AttackPacing.nextWaitMs(rng) }
        val short = AttackPacing.SHORT_MIN_MS..AttackPacing.SHORT_MAX_MS
        val long = AttackPacing.BREAK_MIN_MS..AttackPacing.BREAK_MAX_MS
        assertTrue(waits.all { it in short || it in long })
        val breaks = waits.count { it in long }
        // One in eight of 4000 is 500.
        assertTrue("breaks: $breaks", breaks in 400..600)
        assertTrue(waits.distinct().size > 1000)
    }

    @Test fun dropsAreUnderTenTapsASecondAndUneven() {
        val rng = Random(11)
        val gaps = List(2000) { AttackPacing.dropGapMs(rng) }
        assertTrue(gaps.all { it in 70L..160L })
        assertTrue(gaps.distinct().size > 50)
        val selects = List(2000) { AttackPacing.selectWaitMs(rng) }
        assertTrue(selects.all { it in 150L..350L })
    }
}
