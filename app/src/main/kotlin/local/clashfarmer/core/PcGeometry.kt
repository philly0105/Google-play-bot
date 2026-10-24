package local.clashfarmer.core

import kotlin.math.abs

/**
 * Chooses between the phone's screen positions and the PC's.
 *
 * The phone tree measured every fixed position on a 1600x720 phone. Google Play Games draws the
 * game 16:9 (1740x978 on this PC), and Clash lays a 16:9 screen out differently: the HUD scales
 * with the height and keeps to the corners, and the panels have their own scales. A frame whose
 * aspect is within 2% of 16:9 takes the PC value; every other frame keeps the phone's.
 *
 * The phone's tests use 1600x720, 2400x1080, 800x360, 1550x720, 1500x620, 1700x720 and a portrait
 * 720x1600, so they all keep the phone values. Their one 16:9 viewport, RunGuardTest's 1920x1080,
 * is a mismatch case that reaches no screen position. The PC values were measured at 1740x978;
 * each file scales them by the same viewport fraction it uses for the phone's. docs/PC-GEOMETRY.md
 * lists every value and its evidence.
 */
object PcGeometry {
    private const val SIXTEEN_NINE = 16.0 / 9.0
    private const val TOLERANCE = 0.02

    fun isPc(viewport: Viewport): Boolean {
        if (viewport.width <= 0 || viewport.height <= 0) return false
        return abs(viewport.width.toDouble() / viewport.height / SIXTEEN_NINE - 1) <= TOLERANCE
    }

    fun <T> pick(viewport: Viewport, phone: T, pc: T): T = if (isPc(viewport)) pc else phone
}
