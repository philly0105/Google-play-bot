package local.clashfarmer.core

import org.junit.Assert.*
import org.junit.Test

class HomeBalanceAgreementTest {
    private val capacities = mapOf(Resource.GOLD to 25L)
    private fun samples(a: Long, b: Long, secondTime: Long = 2000L) = listOf(
        ResourceSample(1000L, mapOf(Resource.GOLD to a)),
        ResourceSample(secondTime, mapOf(Resource.GOLD to b))
    )
    @Test fun requiresTwoDistinctFramesWithEqualPlausibleBalances() {
        assertEquals(mapOf(Resource.GOLD to 20L), agreedHomeBalances(samples(20, 20), capacities))
        assertTrue(agreedHomeBalances(samples(19, 20), capacities).isEmpty())
        assertTrue(agreedHomeBalances(samples(-1, -1), capacities).isEmpty())
        assertTrue(agreedHomeBalances(samples(26, 26), capacities).isEmpty())
        assertTrue(agreedHomeBalances(samples(20, 20, 1000L), capacities).isEmpty())
        assertTrue(agreedHomeBalances(samples(20, 20).take(1), capacities).isEmpty())
    }
}
