package local.clashfarmer.core

import kotlin.math.roundToInt

/**
 * Where the phone's fixed screen positions lie on the PC's frames.
 *
 * The phone tree measured every fixed position on a 1600x720 phone. Google Play Games draws the
 * game at the aspect of the monitor it launched on and keeps that aspect through a resize: 1740x978
 * (16:9), and after a relaunch 1860x1162, resized to 1565x978 (16:10). A landscape frame from 1.3:1
 * up to, not including, 2:1 is the PC's and takes the PC values. The phone's tests use 1600x720,
 * 2400x1080, 800x360, 1550x720, 1500x620 and 1700x720, all 2.15:1 or wider, and a portrait
 * 720x1600, so they all keep the phone values. RunGuardTest's 1920x1080 counts as the PC's, which
 * changes nothing there: it only compares a frame's size with the expected one.
 *
 * Each PC value is in pixels of the 1740x978 frame it was measured on, with the edge Clash keeps it
 * at. Clash scales its UI with the frame's height, so on a frame of another size a value keeps its
 * distance from that edge times height / 978: [Anchor.LEFT] and [Anchor.RIGHT] for the HUD's
 * corners, [Anchor.CENTRE] for a panel drawn about the middle. Heights are from the top, scaled the
 * same way, which keeps the bottom row at its distance from the bottom as well. At 1740x978 every
 * value is itself. docs/PC-GEOMETRY.md lists each value with its anchor and its evidence.
 */
object PcGeometry {
    /** The frame the PC values were measured on. */
    const val WIDTH = 1740
    const val HEIGHT = 978

    /** The PC's aspects: width over height from this, up to but not including [MAX_ASPECT]. */
    private const val MIN_ASPECT = 1.3
    private const val MAX_ASPECT = 2.0

    /** The edge a position keeps its distance from when the frame's width changes. */
    enum class Anchor { LEFT, CENTRE, RIGHT }

    fun isPc(viewport: Viewport): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        val aspect = viewport.width.toDouble() / viewport.height
        return aspect >= MIN_ASPECT && aspect < MAX_ASPECT
    }

    fun <T> pick(viewport: Viewport, phone: T, pc: T): T = if (isPc(viewport)) pc else phone

    /** A length of [pixels] at 1740x978, on [viewport]. */
    fun length(viewport: Viewport, pixels: Int): Int = (pixels * viewport.height.toDouble() / HEIGHT).roundToInt()

    /** The x on [viewport] of [pixel], an x at 1740x978. */
    fun x(viewport: Viewport, pixel: Int, anchor: Anchor): Int = when (anchor) {
        Anchor.LEFT -> viewport.left + length(viewport, pixel)
        Anchor.CENTRE -> viewport.left + viewport.width / 2 + length(viewport, pixel - WIDTH / 2)
        Anchor.RIGHT -> viewport.right - length(viewport, WIDTH - pixel)
    }

    /** The y on [viewport] of [pixel], a y at 1740x978. */
    fun y(viewport: Viewport, pixel: Int): Int = viewport.top + length(viewport, pixel)

    fun point(viewport: Viewport, px: Int, py: Int, anchor: Anchor): Point = Point(x(viewport, px, anchor), y(viewport, py))

    fun region(viewport: Viewport, at: Region, anchor: Anchor): Region =
        Region(x(viewport, at.left, anchor), y(viewport, at.top), x(viewport, at.right, anchor), y(viewport, at.bottom))

    /** [x] of [pixel] as a fraction of [viewport]'s width, for the readers that compare fractions. */
    fun xFraction(viewport: Viewport, pixel: Int, anchor: Anchor): Double =
        (x(viewport, pixel, anchor) - viewport.left).toDouble() / viewport.width
}
