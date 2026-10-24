package local.clashfarmer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.Launch
import local.clashfarmer.core.PresetStore
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.ui.calibration.CalibrationModel
import local.clashfarmer.desktop.ui.calibration.CalibrationScreen
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerDesktopTheme
import local.clashfarmer.ui.DashboardLoader
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.Tab
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities

/**
 * Every tab, the account editor and calibration compose and draw at the window's size, so a
 * layout that fails only once measured, such as a list with no height bound, fails here. Each
 * frame is kept in app/build/ui-renders for a look.
 */
class DashboardRenderTest {
    private val filesDir = Files.createTempDirectory("dashboard-render").toFile()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val host = object : DashboardHost {
        override val scope: CoroutineScope get() = uiScope
        override val filesDir: File get() = this@DashboardRenderTest.filesDir
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
        uiScope.cancel()
        filesDir.deleteRecursively()
    }

    @Test
    fun `the PC lists and starts only Blood`() {
        val store = AccountStore(filesDir)
        store.ensureMigrated()
        store.select(AccountStore.BLOOD_ID)
        val loaded = pcAccounts(DashboardLoader(filesDir, store).accounts())
        assertEquals(listOf(AccountStore.BLOOD_ID), loaded.accounts.map { it.id })
        assertEquals(setOf(AccountStore.BLOOD_ID), loaded.profiles.keys)
        assertEquals(2, store.load().size)
        // The built-in "Both accounts" preset finds no second account and is refused, not run.
        val both = PresetStore(filesDir).presets().first { it.isBoth }
        assertEquals(Launch.Refused("Both needs two accounts"),
            both.launch(loaded.accounts, System.currentTimeMillis(), loaded.selectedId))
    }

    @Test
    fun `every tab, the account editor and calibration draw at the window's size`() {
        val store = AccountStore(filesDir)
        val accounts = store.ensureMigrated()
        store.select(AccountStore.BLOOD_ID)
        val tick = DashboardLoader(filesDir, store).tick()
        // As FarmerController binds it: the service is up from the app's start, and only Blood shows.
        val shown = pcAccounts(tick.accounts)
        val state = DashboardState(
            snapshot = tick.snapshot,
            runContext = tick.runContext,
            connected = true,
            busy = false,
            paused = false,
            accounts = shown.accounts,
            selectedId = shown.selectedId,
            profiles = shown.profiles,
            profileError = shown.profileError,
            events = tick.events,
            frame = null
        )
        lateinit var dashboard: FarmerDashboard
        onUiThread {
            dashboard = FarmerDashboard(host)
            dashboard.bind(state)
        }
        Tab.entries.forEachIndexed { index, tab ->
            onUiThread { dashboard.selectTab(index) }
            render("tab-${tab.name.lowercase()}") { dashboard.screens[index].Content() }
        }
        val blood = accounts.first { it.id == AccountStore.BLOOD_ID }
        render("account-editor") { AccountEditorSheet(AccountEditorModel(host, blood), busy = false, onClose = {}) }
        lateinit var calibration: CalibrationModel
        onUiThread { calibration = CalibrationModel(host, AccountStore.BLOOD_ID, onBack = {}) }
        render("calibration") { CalibrationScreen(calibration) }
        onUiThread { calibration.close() }
    }

    /**
     * Draws [content] in the window's theme at 1100 by 760, twice with a pause between, so loads a
     * screen starts when shown have landed by the second frame, which is saved.
     */
    private fun render(name: String, content: @Composable () -> Unit) {
        lateinit var scene: ImageComposeScene
        onUiThread {
            scene = ImageComposeScene(1100, 760, Density(1f)) {
                FarmerDesktopTheme {
                    Box(Modifier.fillMaxSize().background(FarmerColors.background)) { content() }
                }
            }
            scene.render()
        }
        Thread.sleep(400)
        onUiThread {
            val image = scene.render(400_000_000L)
            val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)?.bytes) { "$name did not encode" }
            scene.close()
            File("build/ui-renders").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
        }
    }

    /** Runs [block] on the Swing thread, the UI thread here as in the app, and rethrows what it threw. */
    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw AssertionError("Failed on the UI thread: $it", it) }
    }
}
