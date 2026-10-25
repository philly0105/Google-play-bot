package local.clashfarmer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.Launch
import local.clashfarmer.core.Point
import local.clashfarmer.core.PresetStore
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.ui.calibration.CalibrationModel
import local.clashfarmer.desktop.ui.calibration.CalibrationScreen
import local.clashfarmer.desktop.ui.screens.shownPresets
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerDesktopTheme
import local.clashfarmer.ui.DashboardLoader
import local.clashfarmer.ui.DashboardState
import local.clashfarmer.ui.Tab
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
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
        // The built-in "Both accounts" preset finds no second account and is refused, not run, and
        // Run does not offer it.
        val presets = PresetStore(filesDir).presets()
        val both = presets.first { it.isBoth }
        assertEquals(Launch.Refused("Both needs two accounts"),
            both.launch(loaded.accounts, System.currentTimeMillis(), loaded.selectedId))
        assertEquals(presets.filterNot { it.isBoth }, shownPresets(presets, loaded.accounts.size))
    }

    @Test
    fun `every tab, the account editor and calibration draw at the window's size`() {
        val store = AccountStore(filesDir)
        val accounts = store.ensureMigrated()
        val state = pcState(store)
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

        // With a saved screenshot, a clicked point and the loupe over it.
        val frozen = File(store.calibrationDir(AccountStore.BLOOD_ID).apply { mkdirs() }, "frozen.png")
        ImageIO.write(BufferedImage(1740, 978, BufferedImage.TYPE_INT_RGB), "png", frozen)
        onUiThread { calibration = CalibrationModel(host, AccountStore.BLOOD_ID, onBack = {}) }
        Thread.sleep(400)
        onUiThread { calibration.click(Point(870, 489)) }
        render("calibration-frame") { CalibrationScreen(calibration) }
        onUiThread { calibration.close() }
    }

    /**
     * The whole window body, rail and footers included: at 100%, at 150% in the 1280 by 650 dp a
     * 1080p screen's work area leaves, and with a run going, where the run strip tops every tab
     * but Home.
     */
    @Test
    fun `the window draws whole at 100 and 150 percent and with a run going`() {
        val store = AccountStore(filesDir)
        store.ensureMigrated()
        val idle = pcState(store)
        lateinit var dashboard: FarmerDashboard
        onUiThread {
            dashboard = FarmerDashboard(host)
            dashboard.bind(idle)
        }
        render("shell-home") { Dashboard(dashboard, host) }
        onUiThread { dashboard.selectTab(Tab.RUN.ordinal) }
        render("shell-run-150", 1280, 650, 1.5f) { Dashboard(dashboard, host) }
        onUiThread {
            dashboard.bind(idle.copy(busy = true))
            dashboard.selectTab(Tab.STATS.ordinal)
        }
        render("shell-stats-busy") { Dashboard(dashboard, host) }
        onUiThread { dashboard.selectTab(Tab.HOME.ordinal) }
        render("shell-home-busy-150", 1280, 650, 1.5f) { Dashboard(dashboard, host) }
    }

    @Test
    fun `Ctrl with a digit or Tab picks a tab`() {
        val count = Tab.entries.size
        assertEquals(0, tabForKey(Key.One, ctrl = true, shift = false, selected = 3, count = count))
        assertEquals(4, tabForKey(Key.Five, ctrl = true, shift = false, selected = 0, count = count))
        assertEquals(null, tabForKey(Key.Six, ctrl = true, shift = false, selected = 0, count = count))
        assertEquals(null, tabForKey(Key.One, ctrl = false, shift = false, selected = 0, count = count))
        assertEquals(1, tabForKey(Key.Tab, ctrl = true, shift = false, selected = 0, count = count))
        assertEquals(0, tabForKey(Key.Tab, ctrl = true, shift = false, selected = count - 1, count = count))
        assertEquals(count - 1, tabForKey(Key.Tab, ctrl = true, shift = true, selected = 0, count = count))
        assertEquals(null, tabForKey(Key.Tab, ctrl = false, shift = false, selected = 0, count = count))
    }

    /**
     * A tab change drops the old page and the control in it that had focus. The tab keys must
     * still work after that, as they do after a page link such as "Open Setup >" changes the tab.
     */
    // The key event factory is marked internal; Compose's version is pinned, so a test may use it.
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun `the tab keys still work after the page that had focus is dropped`() {
        val store = AccountStore(filesDir)
        store.ensureMigrated()
        lateinit var dashboard: FarmerDashboard
        lateinit var scene: ImageComposeScene
        onUiThread {
            dashboard = FarmerDashboard(host)
            dashboard.bind(pcState(store))
            scene = ImageComposeScene(1100, 760, Density(1f)) {
                FarmerDesktopTheme { Dashboard(dashboard, host) }
            }
        }
        var frame = 0L
        fun frames() = repeat(3) { scene.render(++frame * 16_000_000L) }
        fun press(key: Key, ctrl: Boolean = false) = onUiThread {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
            frames()
        }
        try {
            onUiThread { frames() }
            // Past the rail's five tabs into Home's page, so a control there holds focus.
            repeat(7) { press(Key.Tab) }
            press(Key.Two, ctrl = true)
            assertEquals(Tab.RUN.ordinal, dashboard.selectedTab)
            press(Key.One, ctrl = true)
            assertEquals(Tab.HOME.ordinal, dashboard.selectedTab)
        } finally {
            onUiThread { scene.close() }
        }
    }

    /** The state as FarmerController binds it: the service is up from the app's start, and only Blood shows. */
    private fun pcState(store: AccountStore): DashboardState {
        store.select(AccountStore.BLOOD_ID)
        val tick = DashboardLoader(filesDir, store).tick()
        val shown = pcAccounts(tick.accounts)
        return DashboardState(
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
    }

    /**
     * Draws [content] in the window's theme at [width] by [height] dp and [scale], twice with a
     * pause between, so loads a screen starts when shown have landed by the second frame, which
     * is saved.
     */
    private fun render(name: String, width: Int = 1100, height: Int = 760, scale: Float = 1f, content: @Composable () -> Unit) {
        lateinit var scene: ImageComposeScene
        onUiThread {
            scene = ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
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
