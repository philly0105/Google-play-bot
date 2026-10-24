package local.clashfarmer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lines are timestamped and the file starts over past its limit`() {
        val file = File(temp.root, "logs/desktop.log")
        val log = DesktopLog(file, maxBytes = 60)
        log.write("Sidecar 1 ready")
        log.write("Sidecar 1 exited with code 0")
        assertTrue(file.readText().matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} Sidecar 1 ready\n.*\n""")))

        log.write("Sidecar 2 ready")
        assertEquals(2, File(file.path + ".1").readLines().size)
        assertTrue(file.readText().endsWith(" Sidecar 2 ready\n"))
    }
}
