package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme
import java.util.Locale

/**
 * An uppercase section label ("STORAGE") with an optional right-hand note ("read 07:40") or a
 * small gold text action ("Show") when [onAction] is set. [top] is the space above it: 0 as a
 * card's first line, whose padding already gives that space.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    actionEnabled: Boolean = true,
    top: Dp = 8.dp,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = top, bottom = 4.dp).defaultMinSize(minHeight = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(title.uppercase(Locale.ROOT), Modifier.weight(1f), size = FarmerTheme.LABEL, color = FarmerColors.muted,
            medium = true, letterSpacing = 0.08f)
        if (action != null) {
            if (onAction != null) {
                Label(
                    action,
                    Modifier
                        .hoverClick(actionEnabled, RoundedCornerShape(8.dp), onAction)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    size = FarmerTheme.SMALL,
                    color = if (actionEnabled) FarmerColors.gold else FarmerColors.faint,
                    medium = true,
                    maxLines = 1
                )
            } else {
                Label(action, size = FarmerTheme.SMALL, color = FarmerColors.muted, medium = true, maxLines = 1)
            }
        }
    }
}
