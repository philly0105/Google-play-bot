package local.clashfarmer.core

/** Which wall of which row the old lattice walk was up to; kept, like [WallRows], for round-trip. */
data class WallCursor(val row: Int, val index: Int)

/**
 * One step of the wall lattice, in pixels.
 *
 * Fractional because a wall is 12.9 pixels along a row and 9.68 up it, not 13 and 10. Rounding
 * the step rather than the position costs a third of a pixel each time, which is nothing for one
 * wall and twelve pixels - most of a wall - by the far end of a row of forty.
 */
data class Step(val dx: Double, val dy: Double)

/**
 * The block of walls as a lattice: an origin, two steps and a count. Walls are no longer found
 * by tapping it; it is kept only as [local.clashfarmer.recognition.PhoneProfile.wallRows] so
 * older profile.json files round-trip.
 */
data class WallRows(
    val origin: Point,
    val alongStep: Step,
    val rowStep: Step,
    val rows: Int,
    val wallsPerRow: Int
)
