package local.clashfarmer.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    private val root: File = Files.createTempDirectory("single-instance").toFile()
    private val held = mutableListOf<SingleInstance>()

    @After
    fun cleanUp() {
        held.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun acquire(dir: File, onRaise: () -> Unit = {}): SingleInstance? =
        SingleInstance.acquire(dir, onRaise)?.also { held += it }

    @Test
    fun `a second start on the same files folder is refused`() {
        val dir = File(root, "files")
        assertNotNull(acquire(dir))
        assertNull(acquire(dir))
    }

    @Test
    fun `separate files folders each get their own app`() {
        assertNotNull(acquire(File(root, "owner")))
        assertNotNull(acquire(File(root, "friend")))
    }

    @Test
    fun `the lock is free again once the holder closes`() {
        val dir = File(root, "files")
        val first = acquire(dir)!!
        first.close()
        held -= first
        assertNotNull(acquire(dir))
    }

    @Test
    fun `the holder writes its loopback port for a later start`() {
        val dir = File(root, "files")
        val first = acquire(dir)!!
        assertEquals(first.port.toString(), File(dir, SingleInstance.PORT_FILE).readText())
    }

    @Test
    fun `a later start asks the holder to show its window`() {
        val dir = File(root, "files")
        val raised = CountDownLatch(1)
        assertNotNull(acquire(dir) { raised.countDown() })
        assertNull(acquire(dir))
        assertTrue(SingleInstance.raiseRunning(dir))
        assertTrue(raised.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `asking with nobody listening gives up`() {
        val dir = File(root, "files").apply { mkdirs() }
        assertFalse(SingleInstance.raiseRunning(dir, waitMs = 300))
    }

    @Test
    fun `a missing files folder is created`() {
        val dir = File(root, "a/b/files")
        assertNotNull(acquire(dir))
        assertTrue(File(dir, SingleInstance.LOCK_FILE).isFile)
    }
}
