package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.desktop.ui.theme.farmerStyle
import local.clashfarmer.desktop.ui.theme.ink
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.Tone
import local.clashfarmer.ui.components.ValueFit

/** What one tile shows; see [StatTile]. */
data class Tile(
    val value: String,
    val label: String,
    val chip: String? = null,
    val chipTone: Tone? = null,
    val valueTone: Tone? = null
)

/**
 * A big number over a small label, with an optional chip. [Tile.valueTone] colours the number
 * (null keeps it white). Long values start smaller and shrink further to fit the tile's width,
 * so "13.6M/h" fits whole.
 */
@Composable
fun StatTile(tile: Tile, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape((FarmerTheme.CARD_RADIUS - 4).dp)
    val value = tile.value
    val cap = when {
        value.length <= 4 -> FarmerTheme.DISPLAY
        value.length <= 5 -> 28f
        value.length <= 7 -> 22f
        else -> 18f
    }
    val measurer = rememberTextMeasurer()
    var room by remember { mutableStateOf(0) }
    val sp = remember(value, cap, room) {
        if (room <= 0) cap
        else ValueFit.sp(cap, room.toFloat()) { size -> measurer.measure(value, farmerStyle(size, bold = true)).size.width.toFloat() }
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(FarmerColors.surface)
            .border(1.dp, FarmerColors.line, shape)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Box(Modifier.fillMaxWidth().onSizeChanged { room = it.width }) {
            Label(value, size = sp, color = tile.valueTone?.ink ?: FarmerColors.text, bold = true, maxLines = 1)
        }
        Label(tile.label, Modifier.padding(top = 6.dp), size = FarmerTheme.SMALL, color = FarmerColors.muted, maxLines = 2)
        if (tile.chip != null) Chip(tile.chip, tile.chipTone ?: Tone.NEUTRAL, Modifier.padding(top = 8.dp))
    }
}

/** A row of up to three equal tiles with 8 dp gaps, the same height, 12 dp below. */
@Composable
fun StatTileRow(tiles: List<Tile>, modifier: Modifier = Modifier) {
    require(tiles.size in 1..3) { "A tile row holds one to three tiles" }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp)) {
        tiles.forEachIndexed { i, tile ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            StatTile(tile, Modifier.weight(1f).fillMaxHeight())
        }
    }
}
