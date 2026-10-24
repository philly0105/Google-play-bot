package android.graphics

/** android.graphics.Rect: integer edges, with right and bottom exclusive. */
class Rect(
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top

    override fun equals(other: Any?): Boolean =
        other is Rect && left == other.left && top == other.top && right == other.right && bottom == other.bottom

    override fun hashCode(): Int = 31 * (31 * (31 * left + top) + right) + bottom

    override fun toString(): String = "Rect($left, $top - $right, $bottom)"
}
