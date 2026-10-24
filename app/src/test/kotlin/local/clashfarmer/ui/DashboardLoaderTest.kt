package local.clashfarmer.ui

import local.clashfarmer.core.BuilderCount
import local.clashfarmer.core.BuilderReading
import local.clashfarmer.core.BuilderStore
import local.clashfarmer.core.Length
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.Resource
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.StorageReading
import local.clashfarmer.core.StorageStore
import local.clashfarmer.history.BattleHistoryStore
import local.clashfarmer.history.BattleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DashboardLoaderTest {

    private val blood = "66666666-7777-8888-9999-000000000000"
    private val sweat = "11111111-2222-3333-4444-555555555555"

    @Test
    fun aFreshLoaderReadsWhatTheRunSavedForTheShownAccount() {
        // As after an update: a new process, and Home's loader reads only files.
        val root = Files.createTempDirectory("dashboard-loader").toFile()
        val storage = StorageReading(1_000L, mapOf(Resource.GOLD to 27_800_000L, Resource.ELIXIR to 27_700_000L))
        StorageStore(root).record(blood, storage)
        StorageStore(root).record(sweat, StorageReading(2_000L, mapOf(Resource.GOLD to 1L)))
        BuilderStore(root).record(blood, BuilderCount(1, 6), 3_000L)
        val battle = BattleRecord("00000000-0000-4000-8000-000000000001", "session-1", blood, 4_000L,
            endedAtMs = 5_000L, outcome = BattleRecord.COMPLETED)
        BattleHistoryStore(File(root, "history/battles")).save(battle)
        val plan = RunPlan(listOf(blood), Length.Attacks(4), walls = false)
        PresetStore(root).setLast(plan)

        val loader = DashboardLoader(root)
        val home = loader.home(blood, listOf(blood, sweat))
        assertEquals(blood, home.accountId)
        assertEquals(storage, home.storage)
        assertEquals(BuilderReading(1, 6, 3_000L), home.builders)
        assertEquals(plan, home.last)
        assertTrue(home.markers.isEmpty())
        assertEquals(listOf(battle), loader.battles())
        assertEquals(1L, loader.home(sweat, listOf(blood, sweat)).storage?.values?.get(Resource.GOLD))
    }

    @Test
    fun nothingSavedReadsAsNothing() {
        val loader = DashboardLoader(Files.createTempDirectory("dashboard-loader").toFile())
        val home = loader.home(blood, listOf(blood))
        assertNull(home.storage)
        assertNull(home.builders)
        assertNull(home.last)
        assertTrue(loader.battles().isEmpty())
    }
}
