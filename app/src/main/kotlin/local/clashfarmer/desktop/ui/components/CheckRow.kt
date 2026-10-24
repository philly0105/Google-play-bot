package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone

/**
 * One checklist line: title and two-line detail on the left, status chip on the right, and a fix
 * button underneath only while the chip is not OK. [fixEnabled] false greys the fix out but keeps
 * its place. [onClick], when set, opens the line's detail.
 */
@Composable
fun CheckRow(
    title: String,
    detail: String,
    chip: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    fixLabel: String? = null,
    onFix: (() -> Unit)? = null,
    fixEnabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.hoverClick(true, RoundedCornerShape(8.dp), onClick) else Modifier)
            .defaultMinSize(minHeight = 52.dp)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, size = FarmerTheme.BODY, bold = true, maxLines = 1)
                if (detail.isNotEmpty()) {
                    Label(detail, Modifier.padding(top = 4.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted, maxLines = 2)
                }
            }
            Spacer(Modifier.width(10.dp))
            Chip(chip, tone, dot = true)
        }
        if (tone != Tone.OK && fixLabel != null && onFix != null) {
            SecondaryButton(fixLabel, Modifier.padding(top = 8.dp), enabled = fixEnabled, compact = true, onClick = onFix)
        }
    }
}
