package local.clashfarmer.desktop

import local.clashfarmer.core.AtomicFile
import local.clashfarmer.core.BuilderCount
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.BuilderStore
import local.clashfarmer.core.Length
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StorageReading
import local.clashfarmer.core.StorageStore
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.PendingStart
import local.clashfarmer.service.PendingStartStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Desktop: each store saved twice keeps the second save and leaves no temp file. On Windows
 * File.renameTo never replaces a file, so before [AtomicFile] every second save went through
 * delete-then-rename.
 */
class AtomicStoresTest {
    private val root = Files.createTempDirectory("atomic-stores").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun leftovers() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()

    @Test
    fun `replace moves the temp file over the old one`() {
        val target = File(root, "a.json").apply { writeText("old") }
        val tmp = File(root, "a.json.tmp").apply { writeText("new") }
        AtomicFile.replace(tmp, target)
        assertEquals("new", target.readText())
        assertTrue(!tmp.exists())
    }

    @Test
    fun `presets saved twice keep the second`() {
        val store = PresetStore(root)
        assertTrue(store.setLast(RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(4), walls = false)))
        assertTrue(store.setLast(RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(9), walls = true)))
        assertEquals(Length.Attacks(9), PresetStore(root).last()?.length)
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test
    fun `storage saved twice keeps the second`() {
        val store = StorageStore(root)
        store.record("a", StorageReading(1L, mapOf(Resource.GOLD to 100L)))
        store.record("a", StorageReading(2L, mapOf(Resource.GOLD to 200L)))
        assertEquals(StorageReading(2L, mapOf(Resource.GOLD to 200L)), StorageStore(root).latest("a"))
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test
    fun `builders saved twice keep the second`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val store = BuilderStore(root)
        assertTrue(store.record(id, BuilderCount(1, 5), 1L))
        assertTrue(store.record(id, BuilderCount(3, 6), 2L))
        assertEquals(BuilderReading(3, 6, 2L), BuilderStore(root).latest(id))
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test
    fun `a start for later saved twice keeps the second`() {
        val store = PendingStartStore(root)
        val plan = RunPlan(listOf("blood"), Length.Attacks(4), walls = false)
        assertTrue(store.write(PendingStart(plan, 1_000L, 1L, "First")))
        assertTrue(store.write(PendingStart(plan, 2_000L, 2L, "Second")))
        assertEquals(2_000L, PendingStartStore(root).read()?.atMs)
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test
    fun `a profile saved twice keeps the second`() {
        val file = File(root, "profiles/profile.json")
        PhoneProfile.createDefault(1600, 720).saveToFile(file)
        PhoneProfile.createDefault(1740, 978).saveToFile(file)
        assertEquals(1740, PhoneProfile.loadFromFile(file).resolutionWidth)
        assertEquals(emptyList<File>(), leftovers())
    }
}
