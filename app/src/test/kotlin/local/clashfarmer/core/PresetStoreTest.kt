package local.clashfarmer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "presets.json")

    private fun preset(name: String, attacks: Int = 10) =
        RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(attacks), walls = false, name = name)

    @Test
    fun theFirstReadSeedsTheThreePresets() {
        val store = PresetStore(folder.root)
        assertEquals(listOf("Farm 40 + walls", "2 h + walls", "Both accounts"), store.presets().map { it.name })
        assertTrue(file.exists())
        assertEquals(PresetStore.SEEDS, PresetStore(folder.root).presets())
        assertNull(store.last())
    }

    @Test
    fun sixIsTheMost() {
        val store = PresetStore(folder.root)
        assertTrue(store.save(preset("A")))
        assertTrue(store.save(preset("B")))
        assertTrue(store.save(preset("C")))
        assertEquals(PresetStore.MAX_PRESETS, store.presets().size)
        assertFalse(store.save(preset("D")))
        assertEquals(PresetStore.MAX_PRESETS, PresetStore(folder.root).presets().size)
        // Saving under a name already taken replaces it, full or not.
        assertTrue(store.save(preset("B", attacks = 25)))
        assertEquals(Length.Attacks(25), store.presets().first { it.name == "B" }.length)
        assertEquals(PresetStore.MAX_PRESETS, store.presets().size)
    }

    @Test
    fun aCorruptFileReadsAsTheSeeds() {
        file.writeText("{ not json")
        val store = PresetStore(folder.root)
        assertEquals(PresetStore.SEEDS, store.presets())
        assertNull(store.last())
        file.writeText("""{"presets":[{"accounts":[],"length":{"kind":"attacks","n":3},"walls":false}]}""")
        assertEquals(PresetStore.SEEDS, store.presets())
    }

    @Test
    fun aPresetNeedsAName() {
        val store = PresetStore(folder.root)
        assertFalse(store.save(preset("  ")))
        assertFalse(store.save(preset("x").copy(name = null)))
        assertTrue(store.save(preset(" Night ")))
        assertEquals("Night", store.presets().last().name)
    }

    @Test
    fun renameAndDelete() {
        val store = PresetStore(folder.root)
        assertTrue(store.rename("2 h + walls", "Two hours"))
        assertFalse(store.rename("Two hours", "Both accounts"))
        assertFalse(store.rename("missing", "x"))
        assertTrue(store.delete("Both accounts"))
        assertFalse(store.delete("Both accounts"))
        assertEquals(listOf("Farm 40 + walls", "Two hours"), PresetStore(folder.root).presets().map { it.name })
    }

    @Test
    fun aFailedWriteIsFalseNotAThrow() {
        // A plain file where the folder should be: every write fails.
        val blocked = folder.newFile("not-a-folder")
        val store = PresetStore(blocked)
        val plan = RunPlan(listOf(RunPlan.SELECTED), Length.Attacks(5), walls = false)
        assertEquals(PresetStore.SEEDS, store.presets())
        assertFalse(store.setLast(plan))
        assertFalse(store.save(preset("A")))
        assertFalse(store.rename("2 h + walls", "Two hours"))
        assertFalse(store.delete("Both accounts"))
        assertNull(store.last())
    }

    @Test
    fun theLastPlanSurvivesPresetChanges() {
        val store = PresetStore(folder.root)
        val last = RunPlan(listOf(RunPlan.SELECTED), Length.Minutes(60), walls = true)
        store.setLast(last)
        store.save(preset("A"))
        store.delete("A")
        assertEquals(last, PresetStore(folder.root).last())
    }
}
