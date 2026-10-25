package local.clashfarmer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashHandlerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private inner class Fixture(logFile: File = File(temp.root, "logs/desktop.log")) {
        var now = 0L
        val logged = mutableListOf<String>()
        val shown = mutableListOf<Pair<Boolean, String>>()
        val exits = mutableListOf<Int>()
        val handler = CrashHandler({ logged += it }, logFile, { now }, { fatal, text -> shown += fatal to text }, { exits += it })
    }

    @Test
    fun `a failure on the main thread is logged, shown and ends the app`() {
        val f = Fixture()
        f.handler.uncaughtException(Thread({}, "main"), IllegalStateException("boom"))
        assertTrue(f.logged.single().startsWith("Uncaught on main: java.lang.IllegalStateException: boom"))
        assertEquals(true, f.shown.single().first)
        assertTrue(f.shown.single().second.startsWith("Clash Farmer stopped because of an error. Start it again"))
        assertEquals(listOf(1), f.exits)
    }

    @Test
    fun `a failure on another thread leaves the app running and shows once every ten minutes`() {
        val f = Fixture()
        val worker = Thread({}, "DefaultDispatcher-worker-1")
        f.handler.uncaughtException(worker, RuntimeException("one"))
        f.now = CrashHandler.DIALOG_EVERY_MS - 1
        f.handler.uncaughtException(worker, RuntimeException("two"))
        f.now = CrashHandler.DIALOG_EVERY_MS
        f.handler.uncaughtException(worker, RuntimeException("three"))
        assertEquals(3, f.logged.size)
        assertEquals(listOf(false, false), f.shown.map { it.first })
        assertTrue(f.shown.first().second.startsWith("Something in Clash Farmer failed, but it keeps running."))
        assertTrue(f.exits.isEmpty())
    }

    @Test
    fun `a startup problem shows its own words and the log once there is one`() {
        val log = File(temp.root, "logs/desktop.log")
        val problem = StartupProblem("A file Clash Farmer needs is missing.")
        assertEquals("A file Clash Farmer needs is missing.", CrashHandler.text(problem, true, null))
        log.parentFile.mkdirs()
        log.writeText("x\n")
        val f = Fixture(log)
        f.handler.uncaughtException(Thread({}, "main"), problem)
        assertEquals("A file Clash Farmer needs is missing. Details are in ${log.path}.", f.shown.single().second)
    }

    @Test
    fun `without a log file the dialog does not point to one`() {
        val f = Fixture()
        f.handler.report(RuntimeException("x"), fatal = false, where = "Window failed")
        assertFalse(f.shown.single().second.contains("Details are in"))
        assertTrue(f.logged.single().startsWith("Window failed: java.lang.RuntimeException: x"))
    }
}
