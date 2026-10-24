package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors

/** The phone BarView: a thin rounded bar, track at 15% white, fill in [color] for [fraction] of the width. */
@Composable
fun FillBar(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(Color(0x26FFFFFF), cornerRadius = radius)
        val f = fraction?.coerceIn(0f, 1f) ?: return@Canvas
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = radius)
    }
}

/**
 * The phone RingsView: two concentric rings, gold storage outside, elixir inside. A null fill
 * draws the track alone. The phone defined it but no screen used it; it is here for parity.
 */
@Composable
fun Rings(gold: Float?, elixir: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 11.dp.toPx()
        val gap = 4.dp.toPx()
        val side = minOf(size.width, size.height)
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        ring(origin, side, 0f, stroke, FarmerColors.gold, gold)
        ring(origin, side, stroke + gap, stroke, FarmerColors.elixir, elixir)
    }
}

private fun DrawScope.ring(origin: Offset, side: Float, inset: Float, stroke: Float, color: Color, fill: Float?) {
    val edge = inset + stroke / 2f
    val topLeft = Offset(origin.x + edge, origin.y + edge)
    val oval = Size(side - 2f * edge, side - 2f * edge)
    val style = Stroke(width = stroke, cap = StrokeCap.Round)
    drawArc(FarmerColors.withAlpha(color, 60), 0f, 360f, useCenter = false, topLeft = topLeft, size = oval, style = style)
    val sweep = (fill ?: return).coerceIn(0f, 1f) * 360f
    if (sweep <= 0f) return
    drawArc(color, -90f, sweep, useCenter = false, topLeft = topLeft, size = oval, style = style)
}
