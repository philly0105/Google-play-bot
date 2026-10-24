package local.clashfarmer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunnerLifecycleTest {

    private class CountingReader : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
        }
    }

    @Test
    fun startupFailureClosesTheReaderOnce() = runBlocking {
        val reader = CountingReader()
        var worked = false
        try {
            runWithReader({ reader }, begin = { throw StopException("Clash never came to the front") }) { _, _ ->
                worked = true
            }
            fail("Expected the startup failure to propagate")
        } catch (e: StopException) {
            assertEquals("Clash never came to the front", e.message)
        }
        assertEquals(1, reader.closes)
        assertTrue("Nothing runs after a failed start", !worked)
    }

    @Test
    fun cancellationPropagatesAndClosesTheReaderOnce() = runBlocking {
        val reader = CountingReader()
        try {
            runWithReader({ reader }, begin = { 7L }) { _, _ -> throw CancellationException("Stopped from dashboard") }
            fail("Expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("Stopped from dashboard", e.message)
        }
        assertEquals(1, reader.closes)
    }

    @Test
    fun theRunGetsItsTokenAndTheReaderIsClosedAfterwards() = runBlocking {
        val reader = CountingReader()
        val completed = runWithReader({ reader }, begin = { 42L }) { r, token ->
            assertEquals(0, r.closes)
            assertEquals(42L, token)
            3
        }
        assertEquals(3, completed)
        assertEquals(1, reader.closes)
    }

    @Test
    fun aReaderThatCannotOpenHasNothingToClose() = runBlocking {
        var began = false
        try {
            runWithReader<CountingReader, Unit>({ throw IllegalStateException("no recogniser") }, begin = {
                began = true
                1L
            }) { _, _ -> }
            fail("Expected the open failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("no recogniser", e.message)
        }
        assertTrue(!began)
    }
}
