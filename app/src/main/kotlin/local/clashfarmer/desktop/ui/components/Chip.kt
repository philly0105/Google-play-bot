package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.edgeColor
import local.clashfarmer.desktop.ui.theme.fillColor
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone

/** A rounded status label: tinted fill, coloured word, optional leading dot. */
@Composable
fun Chip(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier, dot: Boolean = false) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 26.dp)
            .clip(shape)
            .background(tone.fillColor)
            .border(1.dp, tone.edgeColor, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Dot(tone.ink, 7.dp)
            Spacer(Modifier.width(6.dp))
        }
        Label(text, size = FarmerTheme.LABEL, color = tone.ink, medium = true, maxLines = 1)
    }
}

/** A filled circle, for status dots beside names and in the run strip. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
