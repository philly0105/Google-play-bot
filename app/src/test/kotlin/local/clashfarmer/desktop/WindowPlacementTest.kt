package local.clashfarmer.desktop

import local.clashfarmer.desktop.WindowPlacement.Placement
import local.clashfarmer.desktop.WindowPlacement.Screen
import local.clashfarmer.service.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Rectangle
import java.io.File

class WindowPlacementTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A 1920x1080 monitor at 100%, with a 48-tall taskbar. */
    private val fullHd = Screen(Rectangle(0, 0, 1920, 1080), Rectangle(0, 0, 1920, 1032))

    /** A 1080p laptop at 150%: 1280x720 in the window's units, 688 of it above the taskbar. */
    private val scaledLaptop = Screen(Rectangle(0, 0, 1280, 720), Rectangle(0, 0, 1280, 688))

    /** A second monitor to the right of [fullHd]. */
    private val right = Screen(Rectangle(1920, 0, 1920, 1080), Rectangle(1920, 0, 1920, 1040))

    @Test
    fun `a first start centres the default size where it fits`() {
        assertEquals(Placement(null, null, 1100, 760), WindowPlacement.initial(null, listOf(fullHd)))
    }

    @Test
    fun `a first start on a small scaled screen leaves the footer above the taskbar`() {
        val start = WindowPlacement.initial(null, listOf(scaledLaptop))
        assertEquals(Placement(null, null, 1100, 632), start)
    }

    @Test
    fun `a first start with no screens read opens at the default size`() {
        assertEquals(Placement(null, null, 1100, 760), WindowPlacement.initial(null, emptyList()))
        assertEquals(Placement(null, null, 1200, 800, maximized = true),
            WindowPlacement.initial(Placement(50, 50, 1200, 800, maximized = true), emptyList()))
    }

    @Test
    fun `a saved place that still fits is kept, maximized too`() {
        val saved = Placement(200, 120, 1200, 800, maximized = true)
        assertEquals(saved, WindowPlacement.initial(saved, listOf(fullHd)))
    }

    @Test
    fun `a window saved on a second monitor reopens there`() {
        val saved = Placement(2100, 100, 1100, 760)
        assertEquals(saved, WindowPlacement.initial(saved, listOf(fullHd, right)))
    }

    @Test
    fun `a window left on a monitor since unplugged is centred on the main one`() {
        val saved = Placement(2100, 100, 1100, 760)
        assertEquals(Placement(null, null, 1100, 760), WindowPlacement.initial(saved, listOf(fullHd)))
    }

    @Test
    fun `a window mostly off the screen edge is pulled back inside the work area`() {
        val saved = Placement(1700, 600, 1100, 760)
        assertEquals(Placement(820, 272, 1100, 760), WindowPlacement.initial(saved, listOf(fullHd)))
    }

    @Test
    fun `a tall saved window is shrunk to the work area of a smaller screen`() {
        val saved = Placement(0, 0, 1100, 760)
        assertEquals(Placement(0, 0, 1100, 688), WindowPlacement.initial(saved, listOf(scaledLaptop)))
    }

    @Test
    fun `the smallest window allowed never outgrows the screen`() {
        assertEquals(860 to 600, WindowPlacement.minimum(fullHd.work))
        assertEquals(800 to 560, WindowPlacement.minimum(Rectangle(0, 0, 800, 560)))
        assertEquals(860 to 600, WindowPlacement.minimum(null))
    }

    @Test
    fun `a saved place reads back, and a missing or broken file reads as none`() {
        assertNull(WindowPlacement.load(folder.root))
        WindowPlacement.save(folder.root, Placement(10, -5, 1000, 700, maximized = true))
        assertEquals(Placement(10, -5, 1000, 700, maximized = true), WindowPlacement.load(folder.root))
        WindowPlacement.save(folder.root, Placement(null, null, 1000, 700))
        assertEquals(Placement(null, null, 1000, 700), WindowPlacement.load(folder.root))
        File(folder.root, WindowPlacement.FILE).writeText("{not json")
        assertNull(WindowPlacement.load(folder.root))
        File(folder.root, WindowPlacement.FILE).writeText("""{"width":0,"height":700}""")
        assertNull(WindowPlacement.load(folder.root))
    }

    @Test
    fun `the title shows a live run's phase and nothing when idle`() {
        assertEquals("Clash Farmer", windowTitle(null))
        val live = LiveStatus(LiveStatus.State.RUNNING, "12/40", "12 of 40", "Attacking", 0.3f, false, "30%")
        assertEquals("Clash Farmer - Attacking", windowTitle(live))
        assertEquals("Clash Farmer", windowTitle(live.copy(phase = " ")))
        assertEquals("Clash Farmer - " + "x".repeat(80), windowTitle(live.copy(phase = "x".repeat(200))))
    }
}
