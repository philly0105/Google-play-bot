package local.clashfarmer.ui.components

import local.clashfarmer.ui.FarmerTheme

/** Status tone. Shown with a word every time, never by colour alone (spec 1, principle 5). */
enum class Tone {
    OK, WARN, BAD, IDLE, NEUTRAL;

    /** The ink for text, dots and bars in this tone. */
    val color: Int
        get() = when (this) {
            OK -> FarmerTheme.ok
            WARN -> FarmerTheme.warn
            BAD -> FarmerTheme.bad
            IDLE -> FarmerTheme.idle
            NEUTRAL -> FarmerTheme.text
        }

    /** A tinted fill for chips and attention cards. */
    val fill: Int
        get() = if (this == NEUTRAL) FarmerTheme.surfaceStrong else FarmerTheme.withAlpha(color, 38)

    /** A border one step stronger than [fill]. */
    val edge: Int
        get() = if (this == NEUTRAL) FarmerTheme.line else FarmerTheme.withAlpha(color, 90)
}
