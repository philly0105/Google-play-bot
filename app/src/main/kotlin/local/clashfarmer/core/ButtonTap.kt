package local.clashfarmer.core

import kotlin.random.Random

/** Button offsets only; deployment and village geometry use exact taps. */
fun buttonTapPoint(point: Point, width: Int, height: Int, random: Random = Random.Default): Point {
    require(point.x in 0 until width && point.y in 0 until height) { "Button target is outside the display" }
    return Point(
        (point.x + random.nextInt(-3, 4)).coerceIn(0, width - 1),
        (point.y + random.nextInt(-3, 4)).coerceIn(0, height - 1)
    )
}
