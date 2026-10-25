package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.clashfarmer.core.Resource
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone
import java.util.Locale
import local.clashfarmer.ui.components.ResourceBar as SharedResourceBar

/** One storage line: name, "29.0M / 29.0M", a bar in the resource's colour, and FULL at 98% or more. */
@Composable
fun ResourceBar(resource: Resource, value: Long?, capacity: Long?, modifier: Modifier = Modifier) {
    val fraction = ResourceFormat.fraction(value, capacity)
    // A null value reads "Not read", in faint; never shown as 0.
    val amount = when {
        value == null -> "Not read"
        capacity == null -> ResourceFormat.short(value)
        else -> "${ResourceFormat.short(value)} / ${ResourceFormat.short(capacity)}"
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(ResourceFormat.nameOf(resource), Modifier.width(64.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted,
                medium = true, maxLines = 1)
            Label(amount, Modifier.weight(1f), size = FarmerTheme.BODY, color = if (value == null) FarmerColors.faint else FarmerColors.text,
                bold = true, maxLines = 1)
            if (fraction != null && fraction >= SharedResourceBar.FULL_AT) Chip("FULL", Tone.WARN)
        }
        FillBar(fraction, FarmerColors.of(resource), Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp))
    }
}

/** The pure parts of the phone's ResourceBar companion, which the charts and screens share. */
object ResourceFormat {
    fun fraction(value: Long?, capacity: Long?): Float? =
        if (value == null || capacity == null || capacity <= 0) null else (value.toFloat() / capacity).coerceIn(0f, 1f)

    fun nameOf(resource: Resource) = when (resource) {
        Resource.GOLD -> "Gold"
        Resource.ELIXIR -> "Elixir"
        Resource.DARK_ELIXIR -> "Dark"
    }

    /** 29.0M, 412k, 950. */
    fun short(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 10_000 -> "${value / 1000}k"
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1000.0)
        else -> value.toString()
    }
}
