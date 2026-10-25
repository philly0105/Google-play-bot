package local.clashfarmer.desktop.platform

import kotlinx.coroutines.runBlocking
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.PcInfo
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.desktop.sidecar.SurfaceSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashWindowWatcherTest {

    private fun window(width: Int, height: Int, iconic: Boolean = false) =
        ClashWindow(1, 2, "Clash of Clans", iconic, width, height, foreground = false, monitorDpi = 96)

    @Test
    fun `a found window gives its surface size`() = runBlocking {
        val watcher = ClashWindowWatcher(find = { window(1600, 900) })
        assertFalse(watcher.status.value.checked)
        assertTrue(watcher.check())
        val status = watcher.status.value
        assertTrue(status.checked)
        assertEquals(SurfaceSize(1600, 900), status.surface)
        assertNull(status.error)
    }

    @Test
    fun `a minimized window keeps the last size`() = runBlocking {
        val replies = ArrayDeque(listOf(window(1600, 900), window(0, 0, iconic = true)))
        val watcher = ClashWindowWatcher(find = { replies.removeFirst() })
        watcher.check()
        watcher.check()
        assertTrue(watcher.status.value.window!!.iconic)
        assertEquals(SurfaceSize(1600, 900), watcher.status.value.surface)
    }

    @Test
    fun `no window clears the size`() = runBlocking {
        val replies = ArrayDeque(listOf(window(1600, 900), null))
        val watcher = ClashWindowWatcher(find = { replies.removeFirst() })
        watcher.check()
        watcher.check()
        assertNull(watcher.status.value.window)
        assertNull(watcher.status.value.surface)
    }

    @Test
    fun `a failed lookup reports why`() = runBlocking {
        val watcher = ClashWindowWatcher(find = { throw SidecarException("failed", "Can't start the sidecar") })
        assertFalse(watcher.check())
        assertEquals("Can't start the sidecar", watcher.status.value.error)
        assertNull(watcher.status.value.window)
    }

    private fun owned(pid: Long, startedMs: Long, visible: Boolean = true) =
        ClashWindow(1, 2, "Clash of Clans", false, 1740, 978, foreground = false, monitorDpi = 96, visible = visible,
            ownerPid = pid, ownerStartedMs = startedMs)

    private suspend fun watch(vararg replies: ClashWindow?): ClashWindowWatcher {
        val queue = ArrayDeque(replies.toList())
        val watcher = ClashWindowWatcher(find = { queue.removeFirst() })
        repeat(replies.size) { watcher.check() }
        return watcher
    }

    @Test
    fun `a hidden window shown again by the same process is flagged`() = runBlocking {
        val watcher = watch(owned(10, 100), owned(10, 100, visible = false), owned(10, 100))
        assertTrue(watcher.status.value.reopenedInRunningVm)
    }

    @Test
    fun `the flag stays through a missed lookup`() = runBlocking {
        val watcher = watch(owned(10, 100), owned(10, 100, visible = false), null, owned(10, 100), null)
        assertTrue(watcher.status.value.reopenedInRunningVm)
    }

    @Test
    fun `a new game whose window starts hidden and then shows is not flagged`() = runBlocking {
        assertFalse(watch(owned(10, 100, visible = false), owned(10, 100)).status.value.reopenedInRunningVm)
        assertFalse(watch(owned(10, 100, visible = false), null, owned(10, 100, visible = false), owned(10, 100))
            .status.value.reopenedInRunningVm)
        // The old process's hidden window, then a new one shown: the new process is not flagged either.
        assertFalse(watch(owned(10, 100), owned(10, 100, visible = false), owned(11, 200, visible = false), owned(11, 200))
            .status.value.reopenedInRunningVm)
    }

    @Test
    fun `a new game shown, hidden and shown again is flagged`() = runBlocking {
        val watcher = watch(owned(10, 100, visible = false), owned(10, 100), owned(10, 100, visible = false), owned(10, 100))
        assertTrue(watcher.status.value.reopenedInRunningVm)
    }

    @Test
    fun `a window seen once, or missed for a moment, is not flagged`() = runBlocking {
        assertFalse(watch(owned(10, 100)).status.value.reopenedInRunningVm)
        assertFalse(watch(owned(10, 100), null, owned(10, 100)).status.value.reopenedInRunningVm)
        assertFalse(watch(owned(10, 100), owned(10, 100).copy(iconic = true), owned(10, 100)).status.value.reopenedInRunningVm)
    }

    @Test
    fun `a new process clears the flag`() = runBlocking {
        val watcher = watch(owned(10, 100), owned(10, 100, visible = false), owned(10, 100), owned(11, 200))
        assertFalse(watcher.status.value.reopenedInRunningVm)
        // A reused process id with another start time is another process.
        val reused = watch(owned(10, 100), owned(10, 100, visible = false), owned(10, 100), null, owned(10, 300))
        assertFalse(reused.status.value.reopenedInRunningVm)
    }

    @Test
    fun `a window without its process, from an older sidecar, is never flagged`() = runBlocking {
        val bare = window(1740, 978)
        assertFalse(watch(bare, bare.copy(visible = false), bare).status.value.reopenedInRunningVm)
    }

    @Test
    fun `what the PC says is asked at the start and then every 30 seconds`() = runBlocking {
        var clock = 0L
        var asked = 0
        val answers = ArrayDeque(listOf(PcInfo(19045, true, true), PcInfo(22631, false, false)))
        val watcher = ClashWindowWatcher(
            find = { null },
            pcInfo = { asked++; answers.removeFirst() },
            now = { clock }
        )
        watcher.check()
        assertEquals(PcInfo(19045, true, true), watcher.status.value.pc)
        clock = 29_999
        watcher.check()
        assertEquals(1, asked)
        clock = 30_000
        watcher.check()
        assertEquals(2, asked)
        assertEquals(PcInfo(22631, false, false), watcher.status.value.pc)
    }

    @Test
    fun `a sidecar that can't say what the PC has keeps the last answer`() = runBlocking {
        var clock = 0L
        var fail = false
        val watcher = ClashWindowWatcher(
            find = { null },
            pcInfo = { if (fail) throw SidecarException("bad_request", "Unknown op 'pcInfo'") else PcInfo(22631, true, true) },
            now = { clock }
        )
        watcher.check()
        fail = true
        clock = 60_000
        assertTrue(watcher.check())
        assertEquals(PcInfo(22631, true, true), watcher.status.value.pc)
    }

    @Test
    fun `a failed start is shown until a window appears`() = runBlocking {
        val replies = ArrayDeque(listOf(null, window(1740, 978)))
        val watcher = ClashWindowWatcher(find = { replies.removeFirst() })
        watcher.launchResult("Google Play Games is not installed")
        assertEquals("Google Play Games is not installed", watcher.status.value.launchError)
        watcher.check()
        assertEquals("Google Play Games is not installed", watcher.status.value.launchError)
        watcher.check()
        assertNull(watcher.status.value.launchError)
    }
}
