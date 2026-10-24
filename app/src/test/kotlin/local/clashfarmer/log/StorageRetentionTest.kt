package local.clashfarmer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRetentionTest {
    @get:Rule val folder = TemporaryFolder()

    // 2026-10-08T12:00:00Z
    private val now = 1_791_460_800_000L

    private fun entry(name: String, modified: Long, size: Long = 10) = StoredFile(name, modified, size)

    @Test fun keepsTheNewestCapturesByModifiedTime() {
        val files = (1..38).map { entry("inspect-2026100${it % 9}-$it.png", modified = it * 1000L) }
        val deleted = StorageRetention.capturesToDelete(files, keep = 20)
        assertEquals(18, deleted.size)
        assertEquals((1..18).map { it * 1000L }.toSet(), deleted.map { it.modified }.toSet())
    }

    @Test fun wallBulkStoppedCapturesCountTowardTheSameLimit() {
        val files = listOf(
            entry("wall-bulk-stopped-1.png", 1),
            entry("inspect-2.png", 2),
            entry("wall-bulk-stopped-3.png", 3)
        )
        assertEquals(listOf("wall-bulk-stopped-1.png"), StorageRetention.capturesToDelete(files, keep = 2).map { it.name })
    }

    @Test fun trayCapturesCountTowardTheSameLimit() {
        val files = listOf(
            entry("tray-20261014-100000.png", 1),
            entry("inspect-20261014-100001.png", 2),
            entry("tray-20261014-100002.png", 3)
        )
        assertEquals(listOf("tray-20261014-100000.png"),
            StorageRetention.capturesToDelete(files, keep = 2).map { it.name })
    }

    @Test fun pruneKeepsTheNewestTwentyTrayCaptures() {
        val captures = File(folder.root, "captures").apply { mkdirs() }
        for (i in 1..25) File(captures, "tray-$i.png").apply { writeBytes(ByteArray(10)); setLastModified(i * 1000L) }
        val result = StorageRetention.prune(folder.root, nowMillis = now)
        assertEquals(5, result.capturesDeleted)
        assertEquals(20, result.capturesKept)
        (1..5).forEach { assertFalse(File(captures, "tray-$it.png").exists()) }
        (6..25).forEach { assertTrue(File(captures, "tray-$it.png").exists()) }
    }

    @Test fun capturesNeverSelectsCalibrationOrUnknownFiles() {
        val files = listOf(
            entry("frozen.png", 1),
            entry("wall_camera.png", 2),
            entry("sweat.png", 3),
            entry("inspect-old.txt", 4),
            entry("inspect-a.png", 5),
            entry("inspect-b.png", 6)
        )
        assertEquals(listOf("inspect-a.png"), StorageRetention.capturesToDelete(files, keep = 1).map { it.name })
    }

    @Test fun failureSnapshotsKeepTheirOwnNewestTen() {
        val failures = (1..14).map { entry("failure-run_stopped-$it.png", modified = 100_000L + it) }
        val inspects = (1..30).map { entry("inspect-$it.png", modified = 200_000L + it) }
        val deleted = StorageRetention.capturesToDelete(failures + inspects).map { it.name }.toSet()
        // Newer inspect captures do not push failure snapshots out; each pool is pruned alone.
        assertEquals((1..4).map { "failure-run_stopped-$it.png" }.toSet() + (1..10).map { "inspect-$it.png" }.toSet(), deleted)
    }

    @Test fun pruneKeepsTenFailureSnapshots() {
        val captures = File(folder.root, "captures").apply { mkdirs() }
        for (i in 1..12) File(captures, "failure-auto_restart-$i.png").apply { writeBytes(ByteArray(10)); setLastModified(i * 1000L) }
        val result = StorageRetention.prune(folder.root, nowMillis = now)
        assertEquals(2, result.capturesDeleted)
        assertEquals(10, result.capturesKept)
        assertFalse(File(captures, "failure-auto_restart-1.png").exists())
        assertTrue(File(captures, "failure-auto_restart-12.png").exists())
    }

    @Test fun fewerCapturesThanTheLimitDeletesNothing() {
        assertTrue(StorageRetention.capturesToDelete(listOf(entry("inspect-a.png", 1)), keep = 20).isEmpty())
    }

    @Test fun logsOlderThanThirtyDaysByNameAreDeleted() {
        val files = listOf(
            entry("clashfarmer-20260907.jsonl", 0), // 31 days before 2026-10-08
            entry("clashfarmer-20260908.jsonl", 0), // 30 days: kept
            entry("clashfarmer-20261008.jsonl", 0),
            entry("clashfarmer-20250101.jsonl", now) // fresh mtime does not save an old name
        )
        val deleted = StorageRetention.logsToDelete(files, nowMillis = now, keepDays = 30).map { it.name }.toSet()
        assertEquals(setOf("clashfarmer-20260907.jsonl", "clashfarmer-20250101.jsonl"), deleted)
    }

    @Test fun theNewestLogIsKeptEvenWhenOld() {
        val files = listOf(entry("clashfarmer-20250101.jsonl", 0), entry("clashfarmer-20250102.jsonl", 0))
        assertEquals(listOf("clashfarmer-20250101.jsonl"),
            StorageRetention.logsToDelete(files, nowMillis = now, keepDays = 30).map { it.name })
    }

    @Test fun logsNeverSelectsFilesThatAreNotDatedLogs() {
        val files = listOf(
            entry("clashfarmer-20250101.jsonl", 0),
            entry("clashfarmer-2025.jsonl", 0),
            entry("notes.txt", 0),
            entry("clashfarmer-20261008.jsonl", 0)
        )
        assertEquals(listOf("clashfarmer-20250101.jsonl"),
            StorageRetention.logsToDelete(files, nowMillis = now, keepDays = 30).map { it.name })
    }

    @Test fun pruneDeletesOnlyFromCapturesAndLogsAndReportsBytes() {
        val root = folder.root
        val captures = File(root, "captures").apply { mkdirs() }
        val logs = File(root, "logs").apply { mkdirs() }
        for (i in 1..25) File(captures, "inspect-$i.png").apply { writeBytes(ByteArray(100)); setLastModified(i * 1000L) }
        File(captures, "frozen.png").writeBytes(ByteArray(7))
        File(logs, "clashfarmer-20250101.jsonl").writeBytes(ByteArray(50))
        File(logs, "clashfarmer-20261008.jsonl").writeBytes(ByteArray(50))
        val untouched = listOf(
            File(root, "accounts/abc/frozen.png"),
            File(root, "accounts/abc/wall_camera.png"),
            File(root, "identity/sweat.png"),
            File(root, "history/battles/inspect-1.png"),
            File(root, "frozen.png")
        ).onEach { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(3)); it.setLastModified(1) }

        val result = StorageRetention.prune(root, nowMillis = now)

        assertEquals(5, result.capturesDeleted)
        assertEquals(1, result.logsDeleted)
        assertEquals(5 * 100L + 50L, result.bytesFreed)
        assertEquals(20, captures.listFiles()!!.count { it.name.startsWith("inspect-") })
        assertFalse(File(captures, "inspect-1.png").exists())
        assertTrue(File(captures, "inspect-25.png").exists())
        assertTrue(File(captures, "frozen.png").exists())
        assertFalse(File(logs, "clashfarmer-20250101.jsonl").exists())
        assertTrue(File(logs, "clashfarmer-20261008.jsonl").exists())
        untouched.forEach { assertTrue(it.path, it.exists()) }
    }

    @Test fun pruneToleratesMissingDirectories() {
        val result = StorageRetention.prune(folder.root, nowMillis = now)
        assertEquals(0, result.capturesDeleted)
        assertEquals(0, result.logsDeleted)
        assertEquals(0L, result.bytesFreed)
    }
}
