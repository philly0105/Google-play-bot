package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuilderStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "11111111-2222-3333-4444-555555555555"
    private val other = "66666666-7777-8888-9999-000000000000"

    @Test
    fun aReadingIsSavedBesideTheAccount() {
        val store = BuilderStore(folder.root)
        assertTrue(store.record(account, BuilderCount(2, 6), 1_000L))
        assertTrue(File(folder.root, "accounts/$account/builders.json").exists())
        assertEquals(BuilderReading(2, 6, 1_000L), BuilderStore(folder.root).latest(account))
        assertNull(store.latest(other))
    }

    @Test
    fun aNullReadKeepsTheOldValue() {
        val store = BuilderStore(folder.root)
        store.record(account, BuilderCount(0, 6), 1_000L)
        assertFalse(store.record(account, null, 5_000L))
        assertEquals(BuilderReading(0, 6, 1_000L), store.latest(account))
        // Nothing at all is written for a first read that fails.
        assertFalse(store.record(other, null, 5_000L))
        assertFalse(File(folder.root, "accounts/$other").exists())
    }

    @Test
    fun theValuesAgeIsReported() {
        val reading = BuilderReading(1, 6, 60_000L)
        assertEquals(240_000L, reading.ageMs(300_000L))
        assertEquals(0L, reading.ageMs(10_000L))
        val store = BuilderStore(folder.root)
        store.record(account, BuilderCount(1, 6), 60_000L)
        assertEquals(240_000L, store.latest(account)!!.ageMs(300_000L))
    }

    @Test
    fun aBadIdOrFileIsNothing() {
        val store = BuilderStore(folder.root)
        assertFalse(store.record("../escape", BuilderCount(1, 6), 1L))
        assertNull(store.latest("../escape"))
        val f = File(folder.root, "accounts/$account/builders.json")
        f.parentFile!!.mkdirs()
        f.writeText("{ broken")
        assertNull(store.latest(account))
        f.writeText("""{"free":7,"total":6,"at":1}""")
        assertNull(store.latest(account))
    }
}
