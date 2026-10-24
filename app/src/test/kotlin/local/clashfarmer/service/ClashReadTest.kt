package local.clashfarmer.service

import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.RunGuard
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The start's wait for the window read to name Clash. At 21:56 UTC on 14 October the read still
 * said local.clashfarmer 0.6 s into the account check, after the helper had gone, and the run
 * guard spent an auto-restart on it.
 */
class ClashReadTest {
    private val clash = FarmerService.CLASH_PACKAGE
    private val own = "local.clashfarmer"

    private class Clock {
        var ms = 0L
        val pauses = mutableListOf<Long>()
    }

    private fun await(reads: List<String?>, boundMs: Long = 10_000L): Pair<String?, Clock> {
        val clock = Clock()
        var i = 0
        val pkg = runBlocking {
            awaitClashRead({ reads[minOf(i++, reads.lastIndex)] }, { clock.ms }, { clock.pauses += it; clock.ms += it }, boundMs)
        }
        return pkg to clock
    }

    @Test
    fun theHelpersLingeringReadIsWaitedOut() {
        val (pkg, clock) = await(listOf(own, own, clash))
        assertEquals(clash, pkg)
        assertEquals(listOf(250L, 250L), clock.pauses)
    }

    @Test
    fun clashAlreadyReadCostsNothing() {
        val (pkg, clock) = await(listOf(clash))
        assertEquals(clash, pkg)
        assertTrue(clock.pauses.isEmpty())
    }

    @Test
    fun aReadThatNeverNamesClashGivesUpAtTheBound() {
        val (pkg, clock) = await(listOf(own), boundMs = 10_000L)
        assertEquals(own, pkg)
        assertEquals(10_000L, clock.ms)
        val (none, spent) = await(listOf(null), boundMs = 0L)
        assertEquals(null, none)
        assertTrue(spent.pauses.isEmpty())
    }

    /** Why the wait is there: the foreground check lets this read through, the guard does not. */
    @Test
    fun theGuardTakesNothingButClash() {
        val guard = RunGuard(clock = { 0L })
        val viewport = Viewport(0, 0, 1600, 720)
        val token = guard.startRun(60_000L, viewport, 1600, 720)
        try {
            guard.checkCanAct(token, own, true, viewport)
            fail("the helper's own package must stop the run")
        } catch (e: StopException) {
            assertEquals("Clash is not in foreground (detected: $own)", e.message)
        }
        val next = guard.startRun(60_000L, viewport, 1600, 720)
        guard.checkCanAct(next, clash, true, viewport)
    }
}
