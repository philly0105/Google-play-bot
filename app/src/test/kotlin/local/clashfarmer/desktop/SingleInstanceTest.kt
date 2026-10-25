package local.clashfarmer.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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

    /**
     * The tests above hold the lock in this JVM, where a second tryLock throws and acquire treats
     * that as held. A real second start is another process, where tryLock returns null instead;
     * this runs [SingleInstanceHolder] in a second JVM to check that path end to end.
     */
    @Test
    fun `a start in another process is refused and asks that process to show itself`() {
        val dir = File(root, "files")
        val classpath = listOf(SingleInstance::class.java, SingleInstanceHolder::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct()
            .joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val holder = ProcessBuilder(java, "-cp", classpath, SingleInstanceHolder::class.java.name, dir.path)
            .redirectErrorStream(true)
            .start()
        try {
            val out = holder.inputStream.bufferedReader()
            assertTrue(out.awaitLine("held"))
            assertNull(SingleInstance.acquire(dir) {})
            assertTrue(SingleInstance.raiseRunning(dir))
            assertTrue(out.awaitLine("raised"))
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
            // Windows drops the lock with the process that held it.
            assertNotNull(acquire(dir))
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun `a port file that can't be written frees the lock`() {
        val dir = File(root, "files")
        File(dir, SingleInstance.PORT_FILE).mkdirs()
        val failure = runCatching { SingleInstance.acquire(dir) {} }.exceptionOrNull()
        assertTrue(failure is IOException && failure !is SingleInstance.LoopbackBlocked)
        FileChannel.open(File(dir, SingleInstance.LOCK_FILE).toPath(), StandardOpenOption.WRITE).use {
            assertNotNull(it.tryLock())
        }
    }

    @Test
    fun `a blocked loopback port is not blamed on the folder`() {
        val dir = File(root, "files")
        val blocked = SingleInstance.startupProblem(dir, SingleInstance.LoopbackBlocked(java.net.BindException("Permission denied")))
        assertTrue(blocked.message!!.startsWith("Clash Farmer couldn't open its connection on this PC (Permission denied)."))
        assertFalse(blocked.message!!.contains("free space"))
        val folder = SingleInstance.startupProblem(dir, IOException("Access is denied"))
        assertTrue(folder.message!!.startsWith("Clash Farmer can't save its settings in ${dir.path} (Access is denied)."))
    }

    /** Reads lines until [expected]; false at the end of the output. A JVM may print notices first. */
    private fun BufferedReader.awaitLine(expected: String): Boolean {
        while (true) {
            val line = readLine() ?: return false
            if (line.trim() == expected) return true
        }
    }
}

/**
 * The second process for SingleInstanceTest: takes the lock on the folder in args[0], says "held",
 * and ends once a later start asks it to show itself, or after 30 s if none does.
 */
object SingleInstanceHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val raised = CountDownLatch(1)
        val instance = SingleInstance.acquire(File(args[0])) { raised.countDown() }
        println(if (instance == null) "not held" else "held")
        System.out.flush()
        if (instance != null && raised.await(30, TimeUnit.SECONDS)) {
            println("raised")
            System.out.flush()
        }
        instance?.close()
    }
}
