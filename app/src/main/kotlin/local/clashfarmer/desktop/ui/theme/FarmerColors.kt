package local.clashfarmer.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import local.clashfarmer.core.Resource
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone

/**
 * FarmerTheme's colour tokens (spec 1.1) as Compose colours. The ARGB ints in the shared
 * FarmerTheme stay the one source; this only converts them.
 */
object FarmerColors {
    val background = Color(FarmerTheme.background)
    val surface = Color(FarmerTheme.surface)
    val surfaceStrong = Color(FarmerTheme.surfaceStrong)
    val line = Color(FarmerTheme.line)
    val inset = Color(FarmerTheme.inset)
    val chrome = Color(FarmerTheme.chrome)
    val navBar = Color(FarmerTheme.navBar)

    val text = Color(FarmerTheme.text)
    val muted = Color(FarmerTheme.muted)
    val faint = Color(FarmerTheme.faint)

    val gold = Color(FarmerTheme.gold)
    val elixir = Color(FarmerTheme.elixir)
    val dark = Color(FarmerTheme.dark)

    val ok = Color(FarmerTheme.ok)
    val warn = Color(FarmerTheme.warn)
    val bad = Color(FarmerTheme.bad)
    val idle = Color(FarmerTheme.idle)

    /** [color] with its alpha replaced by [alpha] (0..255), as FarmerTheme.withAlpha does. */
    fun withAlpha(color: Color, alpha: Int): Color = color.copy(alpha = alpha.coerceIn(0, 255) / 255f)

    /** A resource's own colour: gold, pink elixir, violet dark elixir. */
    fun of(resource: Resource): Color = when (resource) {
        Resource.GOLD -> gold
        Resource.ELIXIR -> elixir
        Resource.DARK_ELIXIR -> dark
    }
}

/** The ink for text, dots and bars in this tone. */
val Tone.ink: Color get() = Color(color)

/** A tinted fill for chips and attention cards. */
val Tone.fillColor: Color get() = Color(fill)

/** A border one step stronger than [fillColor]. */
val Tone.edgeColor: Color get() = Color(edge)
