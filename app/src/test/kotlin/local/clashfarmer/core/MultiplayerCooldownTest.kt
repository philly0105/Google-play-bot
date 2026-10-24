package local.clashfarmer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerCooldownTest {
    @Test
    fun `cooldown lines read on Blood are recognised`() {
        listOf(
            "Multiplaver Battle Attack available in: IM S15 i Star Bonus",
            "Multiplaver Battle Atack available in: IM 13s i Star Bonus",
            "Multiplaver Battle Attack available in: 345 i Star Bonus",
            "Attack avaiiable in: 34s"
        ).forEach { assertTrue(it, MultiplayerCooldown.showing(it)) }
    }

    @Test
    fun `the normal attack menu is not a cooldown`() {
        assertFalse(MultiplayerCooldown.showing(
            "Multiplaver Battle S SiNgle PlayeR FiNd a MatcH 1700 i Star Bonus Next in: 4h llm Rank 79"
        ))
    }
}
