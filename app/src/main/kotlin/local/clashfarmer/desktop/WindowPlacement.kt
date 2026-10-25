package local.clashfarmer.desktop

import local.clashfarmer.core.AtomicFile
import org.json.JSONObject
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File

/**
 * Where the main window opens, and remembering where it was left. The window used to open at a
 * fixed 1100x760 wherever Windows put it. A 1080p laptop at 150% has about 688 of height to
 * spare under the taskbar, so the footer with Start run opened below the taskbar, and nothing
 * was remembered between starts. Units are the window's own (dp), which Java's screen bounds use
 * on a scaled display too.
 *
 * [initial] is pure, so its rules run in tests; [screens], [load] and [save] read and write the
 * PC's state.
 */
object WindowPlacement {
    const val FILE = "window.json"

    /** The size the window opens at when it fits: what every screen was laid out for. */
    const val DEFAULT_WIDTH = 1100
    const val DEFAULT_HEIGHT = 760

    /** The smallest the window may be dragged to: the 88 rail beside a form, and calibration's 340 sidebar beside the frame. */
    const val MIN_WIDTH = 860
    const val MIN_HEIGHT = 600

    /** Share of the work area a first window takes at most, so its edges show on a small screen. */
    private const val FIRST_SHARE = 0.92

    /** How much of a saved window must still be on a screen for its position to be kept. */
    private const val MIN_VISIBLE = 100

    /** A window's place: [x] and [y] null is centred on the main screen. */
    data class Placement(val x: Int?, val y: Int?, val width: Int, val height: Int, val maximized: Boolean = false)

    /** One monitor: its whole [bounds], and its [work] area without the taskbar. */
    data class Screen(val bounds: Rectangle, val work: Rectangle)

    /**
     * Where the window opens. [screens] lists the main screen first. With nothing [saved], it is
     * centred at the default size, shrunk to fit the main screen's work area. A saved position is
     * kept while at least 100x100 of the window still lies on a screen (a monitor unplugged since
     * leaves it centred instead), and moved so the whole window lies in that screen's work area.
     * A saved size is shrunk to fit that work area. Maximized is kept either way. With no screens
     * read there is nothing to fit to, so the window is centred at the saved or default size.
     */
    fun initial(saved: Placement?, screens: List<Screen>): Placement {
        val main = screens.firstOrNull()?.work
            ?: return saved?.copy(x = null, y = null) ?: Placement(null, null, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (saved == null) {
            return Placement(null, null,
                minOf(DEFAULT_WIDTH, (main.width * FIRST_SHARE).toInt()),
                minOf(DEFAULT_HEIGHT, (main.height * FIRST_SHARE).toInt()))
        }
        val home = if (saved.x != null && saved.y != null) {
            val rect = Rectangle(saved.x, saved.y, saved.width, saved.height)
            screens.firstOrNull { visible(rect, it.bounds) }
        } else {
            null
        }
        val work = home?.work ?: main
        val width = saved.width.coerceIn(minOf(MIN_WIDTH, work.width), work.width)
        val height = saved.height.coerceIn(minOf(MIN_HEIGHT, work.height), work.height)
        if (home == null) return Placement(null, null, width, height, saved.maximized)
        val x = saved.x!!.coerceIn(work.x, work.x + work.width - width)
        val y = saved.y!!.coerceIn(work.y, work.y + work.height - height)
        return Placement(x, y, width, height, saved.maximized)
    }

    /** The smallest size the window may take on a screen whose work area is [work]. */
    fun minimum(work: Rectangle?): Pair<Int, Int> =
        if (work == null) MIN_WIDTH to MIN_HEIGHT
        else minOf(MIN_WIDTH, work.width) to minOf(MIN_HEIGHT, work.height)

    private fun visible(window: Rectangle, screen: Rectangle): Boolean {
        val overlap = window.intersection(screen)
        return !overlap.isEmpty && overlap.width >= MIN_VISIBLE && overlap.height >= MIN_VISIBLE
    }

    /** This PC's monitors, the main one first; empty when Java can't read them. */
    fun screens(): List<Screen> = runCatching {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = env.defaultScreenDevice
        (listOf(main) + env.screenDevices.filter { it != main }).map { device ->
            val config = device.defaultConfiguration
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            Screen(bounds, Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom))
        }
    }.getOrDefault(emptyList())

    /** The placement saved in [filesDir], or null when there is none or it does not read. */
    fun load(filesDir: File): Placement? = runCatching {
        val json = JSONObject(File(filesDir, FILE).readText())
        Placement(
            x = if (json.has("x")) json.getInt("x") else null,
            y = if (json.has("y")) json.getInt("y") else null,
            width = json.getInt("width"),
            height = json.getInt("height"),
            maximized = json.optBoolean("maximized", false)
        ).takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()

    /** Saves [placement] in one step; a failure is ignored, since the next start then just centres the window. */
    fun save(filesDir: File, placement: Placement) {
        runCatching {
            val json = JSONObject().put("width", placement.width).put("height", placement.height).put("maximized", placement.maximized)
            placement.x?.let { json.put("x", it) }
            placement.y?.let { json.put("y", it) }
            filesDir.mkdirs()
            val tmp = File(filesDir, "$FILE.tmp")
            tmp.writeText(json.toString())
            AtomicFile.replace(tmp, File(filesDir, FILE))
        }
    }
}
