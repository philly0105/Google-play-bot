package local.clashfarmer.desktop

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.platform.ClashHost
import local.clashfarmer.desktop.platform.GameSurface
import local.clashfarmer.desktop.platform.GestureCallback
import local.clashfarmer.desktop.platform.GestureStroke
import local.clashfarmer.desktop.platform.ScreenshotCallback
import local.clashfarmer.desktop.platform.WindowStatus
import local.clashfarmer.desktop.sidecar.ClashWindow
import local.clashfarmer.desktop.sidecar.PowerState
import local.clashfarmer.desktop.sidecar.SidecarException
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.service.FarmerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private const val W = 64
private const val H = 36
private val BLACK = 0xFF000000.toInt()
private val GREY = 0xFF808080.toInt()

/** The Clash window as the watcher reports it; a minimized one keeps the surface's last size. */
private fun openWindow(width: Int = 1280, height: Int = 720, iconic: Boolean = false) = WindowStatus(
    checked = true,
    window = ClashWindow(1, 2, "Clash of Clans", iconic, if (iconic) 0 else width, if (iconic) 0 else height,
        foreground = false, monitorDpi = 120),
    surface = SurfaceSize(width, height)
)

class FarmerServicePcTest {

    /** Opens the window [opensAfterMs] after a launch. */
    private class FakeHost(private val scope: CoroutineScope) : ClashHost {
        override val window = MutableStateFlow(WindowStatus(checked = true))

        @Volatile var launches = 0
        var opensAfterMs = 600L
        var battery: () -> PowerState = { PowerState(ac = true, batteryPercent = -1, saver = false) }

        override suspend fun launch() {
            launches++
            scope.launch {
                delay(opensAfterMs)
                window.value = openWindow()
            }
        }

        override suspend fun power(): PowerState = battery()

        override suspend fun keepAwake(on: Boolean) {}
    }

    /** Answers each screenshot at once with the next of [frames], the last one repeating. */
    private class FakeSurface(private val host: FakeHost) : GameSurface {
        @Volatile var frames = listOf(GREY)
        @Volatile var shots = 0
        /** Screenshots asked for while no Clash window was open. */
        @Volatile var shotsWithoutWindow = 0

        override fun takeScreenshot(callback: ScreenshotCallback) {
            if (host.window.value.window == null) shotsWithoutWindow++
            val color = frames[minOf(shots, frames.size - 1)]
            shots++
            callback.onSuccess(Bitmap.createBitmap(IntArray(W * H) { color }, W, H, Bitmap.Config.ARGB_8888))
        }

        override fun dispatchGesture(strokes: List<GestureStroke>, callback: GestureCallback?): Boolean {
            callback?.onCompleted()
            return true
        }

        override fun back(): Boolean = true
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val host = FakeHost(scope)
    private val surface = FakeSurface(host)
    private val filesDir = Files.createTempDirectory("farmer-service-pc").toFile()
    private val service = FarmerService(filesDir, host, surface)

    @After
    fun stop() {
        service.serviceScope.cancel()
        scope.cancel()
        filesDir.deleteRecursively()
    }

    @Test
    fun `Clash is in front while its window exists, minimized or not`() {
        assertNull(service.getActiveForegroundPackage())
        host.window.value = openWindow()
        assertEquals(FarmerService.CLASH_PACKAGE, service.getActiveForegroundPackage())
        host.window.value = openWindow(iconic = true)
        assertEquals(FarmerService.CLASH_PACKAGE, service.getActiveForegroundPackage())
        host.window.value = WindowStatus(checked = true)
        assertNull(service.getActiveForegroundPackage())
    }

    @Test
    fun `the viewport is the game surface, kept while the window is minimized`() {
        assertNull(service.currentDisplayViewport())
        host.window.value = openWindow(1280, 720)
        assertEquals(Viewport(0, 0, 1280, 720), service.currentDisplayViewport())
        host.window.value = openWindow(1280, 720, iconic = true)
        assertEquals(Viewport(0, 0, 1280, 720), service.currentDisplayViewport())
    }

    @Test
    fun `the battery reads as device health, without a temperature`() = runBlocking {
        assertNull(service.readDeviceHealth())
        service.refreshDeviceHealth()
        assertEquals(DeviceHealth(levelPercent = null, charging = true, tempC = null), service.readDeviceHealth())
        host.battery = { PowerState(ac = false, batteryPercent = 42, saver = true) }
        service.refreshDeviceHealth()
        assertEquals(DeviceHealth(levelPercent = 42, charging = false, tempC = null), service.readDeviceHealth())
        host.battery = { throw SidecarException("failed", "Sidecar stopped reading") }
        service.refreshDeviceHealth()
        assertNull(service.readDeviceHealth())
    }

    @Test
    fun `with no Clash window a start launches Clash and returns once it draws`() = runBlocking {
        service.connectTransport()
        surface.frames = listOf(BLACK, GREY)
        service.ensureClashForeground(timeoutMs = 10_000)
        assertEquals(1, host.launches)
        assertEquals(2, surface.shots)
        assertEquals(0, surface.shotsWithoutWindow)
    }

    @Test
    fun `with the Clash window up a start does not launch it again`() = runBlocking {
        service.connectTransport()
        host.window.value = openWindow(iconic = true)
        service.ensureClashForeground(timeoutMs = 10_000)
        assertEquals(0, host.launches)
        assertEquals(1, surface.shots)
    }

    @Test
    fun `a start waits for a lookup that worked before it launches Clash`() = runBlocking {
        service.connectTransport()
        host.window.value = WindowStatus()
        scope.launch {
            delay(300)
            host.window.value = WindowStatus(checked = true, error = "Sidecar restarting")
            delay(300)
            host.window.value = openWindow()
        }
        service.ensureClashForeground(timeoutMs = 10_000)
        assertEquals(0, host.launches)
    }

    @Test
    fun `Home leaves Clash up, so the reopening after the takeover wait does not launch it`() = runBlocking {
        service.connectTransport()
        host.window.value = openWindow()
        assertTrue(service.performGlobalAction(GLOBAL_ACTION_HOME))
        service.ensureClashForeground(timeoutMs = 10_000)
        assertEquals(FarmerService.CLASH_PACKAGE, service.getActiveForegroundPackage())
        assertEquals(0, host.launches)
    }
}
