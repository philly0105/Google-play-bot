package android.graphics

/** The android.graphics.Color statics the copied code calls, on colour ints (0xAARRGGBB). */
object Color {
    const val BLACK: Int = -0x1000000 // 0xFF000000
    const val WHITE: Int = -0x1 // 0xFFFFFFFF

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    /** Hue in [0, 360), saturation and value in [0, 1], by Skia's SkRGBToHSV. Alpha is ignored. */
    fun colorToHSV(color: Int, hsv: FloatArray) {
        if (hsv.size < 3) throw RuntimeException("3 components required for hsv")
        val r = red(color)
        val g = green(color)
        val b = blue(color)
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val v = max / 255f
        if (delta == 0) {
            hsv[0] = 0f
            hsv[1] = 0f
            hsv[2] = v
            return
        }
        var h = when (max) {
            r -> (g - b).toFloat() / delta
            g -> 2f + (b - r).toFloat() / delta
            else -> 4f + (r - g).toFloat() / delta
        }
        h *= 60
        if (h < 0) h += 360f
        hsv[0] = h
        hsv[1] = delta.toFloat() / max
        hsv[2] = v
    }
}
