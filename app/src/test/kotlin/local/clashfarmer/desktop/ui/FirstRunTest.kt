package local.clashfarmer.desktop.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirstRunTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the dialog shows until it is confirmed, and only once after`() {
        assertTrue(FirstRun.needed(folder.root))
        FirstRun.confirm(folder.root, nowMs = 1234L)
        assertFalse(FirstRun.needed(folder.root))
        assertEquals(1234L, JSONObject(File(folder.root, FirstRun.FILE).readText()).getLong("confirmedAtMs"))
        assertFalse(File(folder.root, "${FirstRun.FILE}.tmp").exists())
    }

    @Test
    fun `a data folder that does not exist yet gets the flag`() {
        val fresh = File(folder.root, "files")
        assertTrue(FirstRun.needed(fresh))
        FirstRun.confirm(fresh)
        assertFalse(FirstRun.needed(fresh))
    }

    @Test
    fun `the message says the risk first, then the three steps in order`() {
        assertTrue(FirstRun.MESSAGE.startsWith(FirstRun.BAN_WARNING))
        assertTrue("1. Open Clash of Clans in Google Play Games." in FirstRun.MESSAGE)
        assertTrue("2. Calibrate your account in Setup." in FirstRun.MESSAGE)
        assertTrue("3. Run one supervised attack" in FirstRun.MESSAGE)
        assertTrue("ban" in FirstRun.BAN_WARNING)
        listOf("phone", "Android").forEach { assertFalse(it in FirstRun.MESSAGE) }
    }
}
