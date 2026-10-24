package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.FarmerSize
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme

/**
 * Two to four mutually exclusive options on one track. A disabled option stays visible, faint,
 * and ignores clicks. As on the phone, clicking the selected option calls [onPick] again.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    onPick: (Int) -> Unit
) {
    require(options.size in 2..4) { "Segmented takes 2 to 4 options" }
    val track = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(FarmerSize.CONTROL)
            .clip(track)
            .background(FarmerColors.surfaceStrong)
            .border(1.dp, FarmerColors.line, track)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val usable = enabled(index)
            val segment = RoundedCornerShape(11.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(segment)
                    .then(if (on) Modifier.background(FarmerColors.gold) else Modifier)
                    .hoverClick(usable, segment) { onPick(index) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Label(
                    option,
                    size = FarmerTheme.SMALL,
                    color = when {
                        on -> FarmerColors.background
                        !usable -> FarmerColors.faint
                        else -> FarmerColors.muted
                    },
                    medium = true,
                    maxLines = 1,
                    align = TextAlign.Center
                )
            }
        }
    }
}
