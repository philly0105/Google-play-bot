package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WallPurchaseStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "11111111-2222-3333-4444-555555555555"
    private val other = "66666666-7777-8888-9999-000000000000"

    private fun pending(id: String = account) = PendingWallPurchase(
        accountId = id, currency = Resource.GOLD, count = 12, total = 48_000_000L,
        expectedBalance = 51_000_000L, startingBalance = 99_000_000L, atMs = 1_234L
    )

    private fun file(id: String = account) = File(folder.root, "accounts/$id/wall-pending.json")

    @Test
    fun aNewStoreHasNothingPending() {
        val store = WallPurchaseStore(folder.root)
        assertFalse(store.isPending(account))
        assertNull(store.pending(account))
    }

    @Test
    fun aMarkerRoundTripsEveryField() {
        WallPurchaseStore(folder.root).markPending(pending())
        assertEquals(pending(), WallPurchaseStore(folder.root).pending(account))
    }

    @Test
    fun aMarkerSurvivesProcessRecreation() {
        WallPurchaseStore(folder.root).markPending(pending())
        assertTrue(WallPurchaseStore(folder.root).isPending(account))
    }

    @Test
    fun markersAreKeptPerAccount() {
        val store = WallPurchaseStore(folder.root)
        store.markPending(pending())
        assertTrue(store.isPending(account))
        assertFalse(store.isPending(other))
    }

    @Test
    fun theMarkerIsWrittenThroughATempFileNothingIsLeftBehind() {
        WallPurchaseStore(folder.root).markPending(pending())
        val names = file().parentFile!!.list()!!.toList()
        assertEquals(listOf("wall-pending.json"), names)
    }

    @Test
    fun aCorruptMarkerStillBlocks() {
        file().parentFile!!.mkdirs()
        file().writeText("{ not json")
        val store = WallPurchaseStore(folder.root)
        assertTrue(store.isPending(account))
        assertNull(store.pending(account))
    }

    @Test
    fun acknowledgeClearsTheMarkerAndReportsIt() {
        val store = WallPurchaseStore(folder.root)
        store.markPending(pending())
        assertTrue(store.acknowledge(account))
        assertFalse(store.isPending(account))
        assertFalse(store.acknowledge(account))
    }

    @Test
    fun acknowledgeClearsACorruptMarkerToo() {
        file().parentFile!!.mkdirs()
        file().writeText("garbage")
        assertTrue(WallPurchaseStore(folder.root).acknowledge(account))
        assertFalse(WallPurchaseStore(folder.root).isPending(account))
    }

    @Test
    fun clearProvenRemovesOnlyTheMarkerThePhaseWrote() {
        val store = WallPurchaseStore(folder.root)
        store.markPending(pending())
        assertFalse(store.clearProven(pending().copy(total = 1L)))
        assertTrue(store.isPending(account))
        assertTrue(store.clearProven(pending()))
        assertFalse(store.isPending(account))
    }

    @Test
    fun clearProvenLeavesAnotherAccountsMarker() {
        val store = WallPurchaseStore(folder.root)
        store.markPending(pending(other))
        assertFalse(store.clearProven(pending()))
        assertTrue(store.isPending(other))
    }

    @Test
    fun aSecondMarkerForTheSameAccountIsRefused() {
        val store = WallPurchaseStore(folder.root)
        store.markPending(pending())
        try {
            store.markPending(pending().copy(count = 3))
            fail("a second marker must not overwrite the first")
        } catch (_: IllegalStateException) {
        }
        assertEquals(12, store.pending(account)!!.count)
    }

    @Test
    fun anAccountIdThatIsNotAUuidIsRefused() {
        val store = WallPurchaseStore(folder.root)
        for (bad in listOf("../evil", "", "abc", "$account/../x")) {
            try {
                store.isPending(bad)
                fail("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aMarkerThatCannotBeWrittenThrowsSoNothingIsSent() {
        // A file where the accounts directory should be makes the parent unwritable.
        File(folder.root, "accounts").writeText("in the way")
        try {
            WallPurchaseStore(folder.root).markPending(pending())
            fail("markPending must throw when the marker was not persisted")
        } catch (_: java.io.IOException) {
        }
        assertNotNull(folder.root)
    }
}
