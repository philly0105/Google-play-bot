package local.clashfarmer.history

import local.clashfarmer.core.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BattleHistoryStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("battle-history").toFile()

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    @Test fun savingSameBattleTwiceDoesNotDuplicateIt() {
        val store = BattleHistoryStore(tempDir())
        val record = BattleRecord(id(1), "session-1", "account-1", 1_000)
        store.save(record)
        store.save(record.copy(endedAtMs = 2_000, outcome = BattleRecord.COMPLETED))
        assertEquals(1, store.recent().size)
        assertEquals(2_000L, store.recent().single().endedAtMs)
    }

    @Test fun everyFieldSurvivesTheDisk() {
        val store = BattleHistoryStore(tempDir())
        val record = BattleRecord(
            id(1), "session-1", "account-1", 1_000, endedAtMs = 90_000,
            availableLoot = mapOf(Resource.GOLD to 736_773L, Resource.ELIXIR to 797_129L, Resource.DARK_ELIXIR to 7_300L),
            earnedLoot = mapOf(Resource.GOLD to 398_073L, Resource.DARK_ELIXIR to 0L),
            stars = 1, damagePercent = 62, skippedOpponents = 2, searchGold = 3_000,
            outcome = BattleRecord.COMPLETED, reason = "result_read"
        )
        store.save(record)
        assertEquals(record, store.recent().single())
    }

    @Test fun unknownValuesStayUnknown() {
        val store = BattleHistoryStore(tempDir())
        store.save(BattleRecord(id(1), "s", "a", 1_000))
        val back = store.recent().single()
        assertNull(back.stars)
        assertNull(back.damagePercent)
        assertNull(back.endedAtMs)
        assertEquals(emptyMap<Resource, Long>(), back.earnedLoot)
    }

    @Test fun aFailedReplacementKeepsTheOriginal() {
        val dir = tempDir()
        BattleHistoryStore(dir).save(BattleRecord(id(1), "s", "a", 1_000))
        val failing = BattleHistoryStore(dir, replace = { _, _ -> throw IOException("disk full") })
        assertThrows(IOException::class.java) {
            failing.save(BattleRecord(id(1), "s", "a", 1_000, outcome = BattleRecord.COMPLETED))
        }
        assertEquals(BattleRecord.IN_PROGRESS, BattleHistoryStore(dir).recent().single().outcome)
        // No temporary file is left behind to be read as a battle.
        assertEquals(listOf("${id(1)}.json"), dir.list()!!.toList())
    }

    @Test fun aCorruptFileIsReportedAndTheRestStayVisible() {
        val dir = tempDir()
        val store = BattleHistoryStore(dir)
        store.save(BattleRecord(id(1), "s", "a", 1_000))
        File(dir, "${id(2)}.json").writeText("{not json")
        File(dir, "${id(3)}.json").writeText("""{"id":"${id(3)}","outcome":"victory"}""")
        val loaded = store.load()
        assertEquals(listOf(id(1)), loaded.records.map { it.id })
        assertEquals(setOf("${id(2)}.json", "${id(3)}.json"), loaded.corrupt.toSet())
    }

    @Test fun aRecordFiledUnderAnotherIdIsCorrupt() {
        val dir = tempDir()
        val store = BattleHistoryStore(dir)
        store.save(BattleRecord(id(1), "s", "a", 1_000))
        File(dir, "${id(1)}.json").copyTo(File(dir, "${id(2)}.json"))
        assertEquals(listOf("${id(2)}.json"), store.load().corrupt)
        assertEquals(1, store.recent().size)
    }

    @Test fun anIdThatIsNotAUuidIsRefused() {
        val store = BattleHistoryStore(tempDir())
        assertThrows(IllegalArgumentException::class.java) {
            store.save(BattleRecord("../escape", "s", "a", 1_000))
        }
    }

    @Test fun onlyTheNewestThousandAreKept() {
        val dir = tempDir()
        val store = BattleHistoryStore(dir)
        for (n in 1..1_001) store.save(BattleRecord(id(n), "s", "a", n.toLong()))
        val kept = store.load().records
        assertEquals(1_000, kept.size)
        assertTrue(kept.none { it.id == id(1) })
        assertEquals(1_000, dir.list()!!.size)
    }

    @Test fun recentIsNewestFirstAndFiltersByAccount() {
        val store = BattleHistoryStore(tempDir())
        store.save(BattleRecord(id(1), "s", "sweat", 1_000))
        store.save(BattleRecord(id(2), "s", "blood", 2_000))
        store.save(BattleRecord(id(3), "s", "sweat", 3_000))
        assertEquals(listOf(id(3), id(2), id(1)), store.recent().map { it.id })
        assertEquals(listOf(id(3), id(1)), store.recent(accountId = "sweat").map { it.id })
        assertEquals(listOf(id(3)), store.recent(limit = 1).map { it.id })
    }

    @Test fun battlesLeftOpenByADeadProcessAreInterruptedNotFinished() {
        val dir = tempDir()
        BattleHistoryStore(dir).save(BattleRecord(id(1), "s", "a", 1_000))
        BattleHistoryStore(dir).save(BattleRecord(id(2), "s", "a", 500, endedAtMs = 900, outcome = BattleRecord.COMPLETED))
        val reopened = BattleHistoryStore(dir)
        assertEquals(1, reopened.interruptUnfinished("process_restart"))
        val battle = reopened.recent().first { it.id == id(1) }
        assertEquals(BattleRecord.INTERRUPTED, battle.outcome)
        assertEquals("process_restart", battle.reason)
        // Nothing about the result is invented, not even when it ended.
        assertNull(battle.endedAtMs)
        assertEquals(emptyMap<Resource, Long>(), battle.earnedLoot)
        assertEquals(BattleRecord.COMPLETED, reopened.recent().first { it.id == id(2) }.outcome)
    }
}
