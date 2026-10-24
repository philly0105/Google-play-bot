package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme

/**
 * Title, one-line subtitle and a switch; the whole row toggles. A disabled row shows why as its
 * subtitle, in amber, instead of the normal subtitle: a non-null [disabledReason] disables it.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    disabledReason: String? = null,
    onToggle: (Boolean) -> Unit
) {
    val enabled = disabledReason == null
    val shown = disabledReason ?: subtitle
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverClick(enabled, RoundedCornerShape(8.dp)) { onToggle(!checked) }
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Label(title, size = FarmerTheme.BODY, color = if (enabled) FarmerColors.text else FarmerColors.muted, bold = true)
            if (shown.isNotEmpty()) {
                Label(shown, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL,
                    color = if (enabled) FarmerColors.muted else FarmerColors.warn)
            }
        }
        Switch(checked, enabled)
    }
}

/** The phone Switch's look: a gold thumb on a gold-tinted track when on, grey on glass when off. */
@Composable
private fun Switch(checked: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (checked) FarmerColors.withAlpha(FarmerColors.gold, 110) else FarmerColors.surfaceStrong)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (checked) FarmerColors.gold else FarmerColors.idle))
    }
}
