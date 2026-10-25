package local.clashfarmer.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.ui.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Close and Escape on the account editor ask before they drop changes, and only then. */
class AccountEditorCloseTest {
    private val filesDir = Files.createTempDirectory("editor-close").toFile()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val host = object : DashboardHost {
        override val scope: CoroutineScope get() = testScope
        override val filesDir: File get() = this@AccountEditorCloseTest.filesDir
        override val windowStatus = MutableStateFlow(WindowStatus(checked = true))
        override fun act(action: String) {}
        override fun refresh() {}
        override fun openTab(tab: Tab) {}
        override fun openCalibration(accountId: String?) {}
        override fun inspection(block: suspend () -> Unit): Boolean = false
        override fun toast(text: String, long: Boolean) {}
    }

    @After
    fun stop() {
        testScope.cancel()
        filesDir.deleteRecursively()
    }

    private fun editor(): AccountEditorModel {
        val blood = AccountStore(filesDir).ensureMigrated().first { it.id == AccountStore.BLOOD_ID }
        return AccountEditorModel(host, blood)
    }

    @Test
    fun `an untouched sheet closes at once`() {
        val model = editor()
        var closed = 0
        assertFalse(model.dirty)
        model.requestClose { closed++ }
        assertEquals(1, closed)
        assertFalse(model.closeQuestion)
    }

    @Test
    fun `a changed box or switch asks first, and Keep editing keeps the change`() {
        val model = editor()
        var closed = 0
        model.type("attacks", model.text("attacks") + "1")
        assertTrue(model.dirty)
        model.requestClose { closed++ }
        assertEquals(0, closed)
        assertTrue(model.closeQuestion)
        model.keepEditing()
        assertFalse(model.closeQuestion)
        assertTrue(model.dirty)

        val switched = editor()
        switched.walls = !switched.walls
        switched.requestClose { closed++ }
        assertEquals(0, closed)
        assertTrue(switched.closeQuestion)
    }

    @Test
    fun `a change put back is no change`() {
        val model = editor()
        val before = model.text("attacks")
        model.type("attacks", before + "1")
        model.type("attacks", before)
        assertFalse(model.dirty)
    }
}
