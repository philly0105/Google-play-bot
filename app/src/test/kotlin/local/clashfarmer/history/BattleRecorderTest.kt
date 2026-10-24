package local.clashfarmer.history

import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class BattleRecorderTest {

    private val store = BattleHistoryStore(Files.createTempDirectory("battle-history").toFile())
    private var now = 1_000L
    private val recorder = BattleRecorder(
        store, sessionId = "session-1", accountId = "sweat", clock = { now },
        newId = { "00000000-0000-4000-8000-000000000001" }
    )
    private val offered = mapOf(Resource.GOLD to 736_773L, Resource.ELIXIR to 797_129L)

    @Test fun theBattleIsOnDiskBeforeAnythingIsDeployed() {
        recorder.start(offered, skips = 2, searchGold = 3_000)
        val saved = store.recent().single()
        assertEquals(BattleRecord.IN_PROGRESS, saved.outcome)
        assertEquals(offered, saved.availableLoot)
        assertEquals(2, saved.skippedOpponents)
        assertEquals(3_000L, saved.searchGold)
        assertEquals("session-1", saved.sessionId)
        assertEquals("sweat", saved.accountId)
    }

    @Test fun aResultIsRecordedOnceHoweverOftenItIsSeen() {
        recorder.start(offered, 0, 0)
        recorder.result(mapOf(Resource.GOLD to 398_073L), damagePercent = 62, stars = null)
        // The same screen read again, part of it lost this time.
        recorder.result(mapOf(Resource.GOLD to 98_073L, Resource.ELIXIR to 610_474L), damagePercent = null, stars = null)
        now = 90_000
        recorder.finish("result_read")
        recorder.finish("result_read")
        val battle = store.recent().single()
        assertEquals(mapOf(Resource.GOLD to 398_073L, Resource.ELIXIR to 610_474L), battle.earnedLoot)
        assertEquals(62, battle.damagePercent)
        assertEquals(90_000L, battle.endedAtMs)
        assertEquals(BattleRecord.COMPLETED, battle.outcome)
    }

    @Test fun availableLootIsNeverTakenForEarnedLoot() {
        recorder.start(offered, 0, 0)
        now = 90_000
        recorder.finish("result_not_seen")
        val battle = store.recent().single()
        assertEquals(offered, battle.availableLoot)
        assertEquals(emptyMap<Resource, Long>(), battle.earnedLoot)
        assertNull(battle.stars)
        assertNull(battle.damagePercent)
        assertEquals("result_not_seen", battle.reason)
    }

    @Test fun aBattleTheRunLostIsInterruptedWithItsResultUnknown() {
        recorder.start(offered, 0, 0)
        now = 20_000
        recorder.interrupt("Stopped from floating panel")
        val battle = store.recent().single()
        assertEquals(BattleRecord.INTERRUPTED, battle.outcome)
        assertEquals("Stopped from floating panel", battle.reason)
        assertEquals(emptyMap<Resource, Long>(), battle.earnedLoot)
        // An interruption after the battle was finished changes nothing.
        recorder.start(offered, 0, 0)
        recorder.finish("result_read")
        recorder.interrupt("later stop")
        assertEquals(BattleRecord.COMPLETED, store.recent().single().outcome)
    }

    @Test fun aBattleAcrossMidnightIsOneRecord() {
        now = 1_790_294_340_000 // 23:59 UTC
        recorder.start(offered, 0, 0)
        now = 1_790_294_460_000 // 00:01 UTC the next day
        recorder.finish("result_read")
        val battle = store.recent().single()
        assertEquals(1_790_294_340_000, battle.startedAtMs)
        assertEquals(1_790_294_460_000, battle.endedAtMs)
    }

    @Test fun nothingIsRecordedWithoutABattle() {
        recorder.result(mapOf(Resource.GOLD to 1L), 50, null)
        recorder.finish("result_read")
        recorder.interrupt("stop")
        assertEquals(emptyList<BattleRecord>(), store.recent())
    }
}
