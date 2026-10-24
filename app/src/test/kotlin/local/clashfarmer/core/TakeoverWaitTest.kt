package local.clashfarmer.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TakeoverWaitTest {
    @Test fun backgroundsBeforeWaitingTenMinutes() = runTest {
        var backgroundAt = -1L
        val countdown = mutableListOf<Long>()
        waitBeforeVillageRetry(
            background = { backgroundAt = testScheduler.currentTime },
            awaitResume = {}, remaining = { countdown += it })
        assertEquals(0L, backgroundAt)
        assertEquals(600_000L, testScheduler.currentTime)
        assertEquals(600_000L, countdown.first())
        assertEquals(0L, countdown.last())
    }

    @Test fun cancellationNeverReturnsToTheGame() = runTest {
        var reopened = false
        val job = launch {
            waitBeforeVillageRetry({}, {}, {})
            reopened = true
        }
        advanceTimeBy(599_000)
        job.cancelAndJoin()
        assertFalse(reopened)
    }

    @Test fun pauseAtTheEndPreventsReturningUntilResume() = runTest {
        val resume = CompletableDeferred<Unit>()
        var reopened = false
        val job = launch {
            waitBeforeVillageRetry({}, {
                if (testScheduler.currentTime >= 599_000) resume.await()
            }, {})
            reopened = true
        }
        advanceTimeBy(620_000)
        runCurrent()
        assertFalse(reopened)
        resume.complete(Unit)
        job.join()
        assertTrue(reopened)
    }
}
