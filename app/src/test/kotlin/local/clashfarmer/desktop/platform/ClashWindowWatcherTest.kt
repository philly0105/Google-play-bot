package local.clashfarmer.desktop.platform

import kotlinx.coroutines.runBlocking
import local.clashfarmer.desktop.sidecar.ClashWindow
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
}
