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
import local.clashfarmer.accounts.AccountStore
import local.clashfarmer.core.DeviceHealth
import local.clashfarmer.core.Point
import local.clashfarmer.core.StopException
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
import local.clashfarmer.recognition.PhoneProfile
import local.clashfarmer.service.FarmerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** Opens the window [opensAfterMs] after a launch. A resize reaches its size, and the watcher sees it 300 ms later. */
    private class FakeHost(private val scope: CoroutineScope) : ClashHost {
        override val window = MutableStateFlow(WindowStatus(checked = true))

        @Volatile var launches = 0
        var opensAfterMs = 600L
        val resizes = mutableListOf<SurfaceSize>()
        var battery: () -> PowerState = { PowerState(ac = true, batteryPercent = -1, saver = false) }
        /** The largest game area the monitor holds; a resize past it reaches this, as the sidecar's cap does. */
        var room: SurfaceSize? = null

        override suspend fun launch() {
            launches++
            scope.launch {
                delay(opensAfterMs)
                window.value = openWindow()
            }
        }

        override suspend fun resize(width: Int, height: Int): SurfaceSize {
            resizes += SurfaceSize(width, height)
            val reached = PcProfiles.fitInside(SurfaceSize(width, height), room)
            scope.launch {
                delay(300)
                window.value = openWindow(reached.width, reached.height)
            }
            return reached
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
    fun `a start with the window closed by hand stops and launches nothing`() = runBlocking {
        service.connectTransport()
        host.window.value = openWindow().let { it.copy(window = it.window?.copy(visible = false)) }
        val stop = runCatching { service.ensureClashForeground(timeoutMs = 10_000) }.exceptionOrNull()
        assertTrue(stop is StopException)
        assertEquals(FarmerService.CLASH_CLOSED, stop?.message)
        assertEquals(0, host.launches)
        assertEquals(0, surface.shots)
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

    @Test
    fun `a surface of the profile's aspect at another size is sized back to it`() = runBlocking {
        host.window.value = openWindow(1920, 1080)
        service.fitSurface(PhoneProfile.createDefault(1740, 978))
        assertEquals(listOf(SurfaceSize(1740, 978)), host.resizes)
        // The run guard reads the watcher, so the resize returns once the watcher has seen it.
        assertEquals(Viewport(0, 0, 1740, 978), service.currentDisplayViewport())
    }

    @Test
    fun `a surface of another aspect, or already at the profile's size, is left alone`() = runBlocking {
        host.window.value = openWindow(1860, 1162)
        service.fitSurface(PhoneProfile.createDefault(1740, 978))
        host.window.value = openWindow(1740, 978)
        service.fitSurface(PhoneProfile.createDefault(1740, 978))
        assertEquals(emptyList<SurfaceSize>(), host.resizes)
    }

    @Test
    fun `the preferred size is 1740x978 at its aspect and the surface's own at another`() {
        assertEquals(SurfaceSize(1740, 978), FarmerService.preferredSurface(SurfaceSize(1920, 1080)))
        assertEquals(SurfaceSize(1740, 978), FarmerService.preferredSurface(SurfaceSize(1280, 720)))
        assertEquals(SurfaceSize(1860, 1162), FarmerService.preferredSurface(SurfaceSize(1860, 1162)))
    }

    @Test
    fun `a capture sizes the surface to the preferred size first`() = runBlocking {
        service.connectTransport()
        host.window.value = openWindow(1280, 720)
        service.captureClashGame().bitmap.recycle()
        assertEquals(listOf(SurfaceSize(1740, 978)), host.resizes)
        assertEquals(Viewport(0, 0, 1740, 978), service.currentDisplayViewport())
    }

    @Test
    fun `a capture leaves a surface of another aspect at its own size`() = runBlocking {
        service.connectTransport()
        host.window.value = openWindow(1860, 1162)
        service.captureClashGame().bitmap.recycle()
        assertEquals(emptyList<SurfaceSize>(), host.resizes)
    }

    /** A verified calibration at 1740x978 with the Battle anchor, saved as Blood's. */
    private fun savedCalibration(): PhoneProfile {
        AccountStore(filesDir).ensureMigrated()
        val saved = PhoneProfile.createDefault(1740, 978)
            .copy(stateAnchors = mapOf("attack" to Point(96, 885)), isVerified = true)
        AccountStore(filesDir).saveProfile(AccountStore.BLOOD_ID, saved)
        return saved
    }

    @Test
    fun `the preferred size shrinks to what the monitor holds`() {
        assertEquals(SurfaceSize(1600, 899), FarmerService.preferredSurface(SurfaceSize(1280, 720), SurfaceSize(1600, 900)))
        assertEquals(SurfaceSize(1740, 978), FarmerService.preferredSurface(SurfaceSize(1280, 720), SurfaceSize(2560, 1400)))
        // Another aspect stays the surface's own size: nothing is resized at it.
        assertEquals(SurfaceSize(1860, 1162), FarmerService.preferredSurface(SurfaceSize(1860, 1162), SurfaceSize(1600, 900)))
    }

    @Test
    fun `a resize the monitor caps leaves the run on the size reached`() = runBlocking {
        val saved = savedCalibration()
        host.room = SurfaceSize(1600, 900)
        host.window.value = openWindow(1280, 720)
        service.fitSurface(saved)
        assertEquals(listOf(SurfaceSize(1740, 978)), host.resizes)
        assertEquals(Viewport(0, 0, 1600, 899), service.currentDisplayViewport())
        val run = service.runProfile(saved, AccountStore.BLOOD_ID)
        assertEquals(Viewport(0, 0, 1600, 899), run.viewport)
        // The same shape, so the calibration's verification holds.
        assertTrue(run.isVerified)
    }

    @Test
    fun `at the calibrated size the run uses the calibration itself`() = runBlocking {
        val saved = savedCalibration()
        host.window.value = openWindow(1740, 978)
        assertTrue(service.runProfile(saved, AccountStore.BLOOD_ID) === saved)
    }

    @Test
    fun `at 16 by 10 the run is moved and unverified until an attack works there`() {
        val saved = savedCalibration()
        host.window.value = openWindow(1860, 1162)
        val run = service.runProfile(saved, AccountStore.BLOOD_ID)
        assertEquals(Viewport(0, 0, 1860, 1162), run.viewport)
        assertEquals(Point(114, 1052), run.stateAnchors["attack"])
        assertFalse(run.isVerified)

        // The supervised attack's verification goes to surfaces.json; profile.json is not written.
        val profileFile = AccountStore(filesDir).profileFile(AccountStore.BLOOD_ID)
        val before = profileFile.readText()
        val earned = service.movedProfileUpdater(AccountStore.BLOOD_ID, SurfaceSize(1860, 1162))
            .invoke { current -> current.copy(isVerified = true) }
        assertTrue(earned.isVerified)
        assertEquals(before, profileFile.readText())
        assertEquals(setOf(1.6), SurfaceVerification.aspects(profileFile))
        assertTrue(service.runProfile(saved, AccountStore.BLOOD_ID).isVerified)
        // 1565x978 is the same shape.
        host.window.value = openWindow(1565, 978)
        assertTrue(service.runProfile(saved, AccountStore.BLOOD_ID).isVerified)
    }

    @Test
    fun `an attack at another shape earns nothing for a calibration never verified`() {
        AccountStore(filesDir).ensureMigrated()
        AccountStore(filesDir).saveProfile(AccountStore.BLOOD_ID, PhoneProfile.createDefault(1740, 978))
        service.movedProfileUpdater(AccountStore.BLOOD_ID, SurfaceSize(1860, 1162))
            .invoke { current -> current.copy(isVerified = true) }
        assertTrue(SurfaceVerification.aspects(AccountStore(filesDir).profileFile(AccountStore.BLOOD_ID)).isEmpty())
    }

    @Test
    fun `a game area too small to read is refused in words`() {
        val saved = savedCalibration()
        host.window.value = openWindow(1120, 700)
        val stop = assertThrows(StopException::class.java) { service.runProfile(saved, AccountStore.BLOOD_ID) }
        assertTrue(stop.message!!, stop.message!!.contains("too small"))
    }

    @Test
    fun `a start on any account but Blood is refused`() {
        service.connectTransport()
        val accounts = AccountStore(filesDir).ensureMigrated()
        val sweat = accounts.first { it.id == AccountStore.SWEAT_ID }
        val blood = accounts.first { it.id == AccountStore.BLOOD_ID }
        assertThrows(IllegalStateException::class.java) { service.startSwitchOnly(sweat) }
        assertThrows(IllegalStateException::class.java) { service.startSupervisedAccount(sweat) }
        assertThrows(IllegalStateException::class.java) { service.startCombinedRun(blood, sweat, 1, 1) }
        assertFalse(service.isBusy)
        service.startSwitchOnly(blood)
        assertTrue(service.isBusy)
    }
}
