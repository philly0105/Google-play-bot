package local.clashfarmer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStoreTest {

    @Test
    fun startsEmptyAndKeepsTheFirstFrame() {
        val store = FrameStore()
        assertNull(store.snapshot)
        assertTrue(store.offer(byteArrayOf(1, 2, 3), atMs = 1000L))
        assertEquals(1000L, store.snapshot!!.atMs)
        assertEquals(listOf<Byte>(1, 2, 3), store.snapshot!!.bytes.toList())
    }

    @Test
    fun aNewerFrameReplacesTheOlderOne() {
        val store = FrameStore()
        store.offer(byteArrayOf(1), atMs = 1000L)
        assertTrue(store.offer(byteArrayOf(2), atMs = 2000L))
        assertEquals(2000L, store.snapshot!!.atMs)
        assertEquals(listOf<Byte>(2), store.snapshot!!.bytes.toList())
    }

    @Test
    fun anOlderFrameNeverReplacesANewerOne() {
        // JPEG encoding runs off the capture thread, so an older encode can finish last.
        val store = FrameStore()
        store.offer(byteArrayOf(2), atMs = 2000L)
        assertFalse(store.offer(byteArrayOf(1), atMs = 1000L))
        assertFalse(store.offer(byteArrayOf(9), atMs = 2000L))
        assertEquals(listOf<Byte>(2), store.snapshot!!.bytes.toList())
    }

    @Test
    fun aFrameKeepsTheAccountItWasOfferedWithOrNone() {
        val store = FrameStore()
        // Captured during a switch, or anywhere outside a session: whose village it is is not known.
        store.offer(byteArrayOf(1), atMs = 1000L)
        assertNull(store.snapshot!!.accountId)
        store.offer(byteArrayOf(2), atMs = 2000L, accountId = "blood-id")
        assertEquals("blood-id", store.snapshot!!.accountId)
        // The next switch's frame drops the account with the bytes it replaces.
        store.offer(byteArrayOf(3), atMs = 3000L, accountId = null)
        assertNull(store.snapshot!!.accountId)
        // An older frame that finished encoding last brings back neither its bytes nor its account.
        assertFalse(store.offer(byteArrayOf(2), atMs = 2500L, accountId = "blood-id"))
        assertNull(store.snapshot!!.accountId)
    }

    @Test
    fun clearForgetsTheFrame() {
        val store = FrameStore()
        store.offer(byteArrayOf(1), atMs = 1000L)
        store.clear()
        assertNull(store.snapshot)
        assertTrue(store.offer(byteArrayOf(1), atMs = 500L))
    }
}
