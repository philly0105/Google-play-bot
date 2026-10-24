package local.clashfarmer.ui

/**
 * The phone's design tokens (spec 1.1), moved here without its Android view factories. Tone and
 * AccountColor read them, and so will the desktop UI.
 */
object FarmerTheme {
    // Surfaces: dark green glass. Alpha colours composite over the window background.
    val background = 0xFF0F1710.toInt()
    val surface = 0x14FFFFFF                         // cards, 8% white
    val surfaceStrong = 0x1FFFFFFF                   // chips, outlined buttons, segmented track, 12% white
    val line = 0x24FFFFFF                            // hairlines, 14% white
    val inset = 0x5A000000                           // nested blocks, chart plot area, 35% black
    val chrome = 0x66000000                          // 40% black, over content
    val navBar = 0xFF0A110B.toInt()                  // tab bar and run strip, solid so content never shows through

    // Text.
    val text = 0xFFFFFFFF.toInt()
    val muted = 0xA6FFFFFF.toInt()                   // 65% white
    val faint = 0x66FFFFFF                           // 40% white

    // Resources only.
    val gold = 0xFFFFC53D.toInt()
    val elixir = 0xFFFF8AE0.toInt()
    val dark = 0xFFA58BFF.toInt()

    // Status, always paired with a word.
    val ok = 0xFF5EE29A.toInt()
    val warn = 0xFFFFB020.toInt()
    val bad = 0xFFE5463A.toInt()
    val idle = 0xFF8E8E93.toInt()

    // Account series in charts, by account (see AccountColor).
    val acctA = 0xFF5EC8FF.toInt()
    val acctB = 0xFFFF8A5C.toInt()

    // Older names, kept so screens and dialogs outside this package keep compiling unchanged.
    val raised = surfaceStrong
    val panelLine = line
    val green = ok
    val amber = warn
    val red = bad

    // Type scale, sp.
    const val DISPLAY = 32f      // numbers only
    const val HEADLINE = 28f     // a screen's state word
    const val TITLE = 20f
    const val BODY = 15f
    const val SMALL = 13f
    const val LABEL = 12f

    // Spacing, dp.
    const val GUTTER = 16
    const val TOUCH = 48

    // Radius, dp.
    const val CARD_RADIUS = 20
    const val BUTTON_RADIUS = 16
    const val CHIP_RADIUS = 999

    /** [color] with its alpha replaced by [alpha] (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
