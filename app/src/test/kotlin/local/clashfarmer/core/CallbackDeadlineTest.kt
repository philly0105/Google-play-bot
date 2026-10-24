package local.clashfarmer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallbackDeadlineTest {

    @Test fun missingScreenshotCallbackTimesOutAndLateSuccessIsDisposed() = runTest {
        var deliver: ((String) -> Unit)? = null
        val disposed = mutableListOf<String>()
        val thrown = runCatching {
            awaitCallbackWithin<String>(5000L, "Screenshot capture", { disposed += it }) { d, _ -> deliver = d }
        }.exceptionOrNull()
        assertTrue(thrown is StopException)
        assertTrue(RecoveryPolicy.isRecoverable(thrown!!.message))
        deliver!!("late frame")
        assertEquals(listOf("late frame"), disposed)
    }

    @Test fun aScreenshotInsideTheDeadlineIsDelivered() = runTest {
        var deliver: ((String) -> Unit)? = null
        var disposed: String? = null
        val frame = async {
            awaitCallbackWithin<String>(5000L, "Screenshot capture", { disposed = it }) { d, _ -> deliver = d }
        }
        runCurrent()
        deliver!!("frame")
        assertEquals("frame", frame.await())
        assertNull(disposed)
    }

    @Test fun cancellationDuringTheWaitInterruptsBeforeTheDeadline() = runTest {
        var deliver: ((String) -> Unit)? = null
        val disposed = mutableListOf<String>()
        var thrown: Throwable? = null
        val job = launch {
            try {
                awaitCallbackWithin<String>(5000L, "Screenshot capture", { disposed += it }) { d, _ -> deliver = d }
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(thrown is CancellationException)
        deliver!!("late frame")
        assertEquals(listOf("late frame"), disposed)
    }

    @Test fun aFrameThatLosesTheRaceToCancellationIsDisposed() = runTest {
        var deliver: ((String) -> Unit)? = null
        val disposed = mutableListOf<String>()
        var received: String? = null
        val job = launch {
            received = awaitCallbackWithin<String>(5000L, "Screenshot capture", { disposed += it }) { d, _ -> deliver = d }
        }
        runCurrent()
        deliver!!("raced frame")
        job.cancelAndJoin()
        assertNull(received)
        assertEquals(listOf("raced frame"), disposed)
    }
}
