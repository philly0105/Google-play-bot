package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.ui.Tab

/**
 * Line icons for the navigation, drawn on a 24-unit grid so no image assets are needed. [filled]
 * is the selected tab's variant.
 */
@Composable
fun TabIcon(tab: Tab, color: Color, filled: Boolean, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val unit = minOf(size.width, size.height) / 24f
        val stroke = Stroke(width = 1.9.dp.toPx() / unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({
            translate(left = (size.width - unit * 24f) / 2f, top = (size.height - unit * 24f) / 2f)
            scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero)
        }) {
            when (tab) {
                Tab.HOME -> {
                    val roof = Path().apply {
                        moveTo(3.5f, 11f); lineTo(12f, 3.8f); lineTo(20.5f, 11f)
                        moveTo(5.8f, 9.4f); lineTo(5.8f, 20f); lineTo(18.2f, 20f); lineTo(18.2f, 9.4f)
                    }
                    drawPath(roof, color, style = stroke)
                    drawRect(color, topLeft = Offset(10f, 14f), size = Size(4f, 6f), style = if (filled) Fill else stroke)
                }
                Tab.RUN -> {
                    val play = Path().apply {
                        moveTo(7.5f, 4.5f); lineTo(19f, 12f); lineTo(7.5f, 19.5f); close()
                    }
                    if (filled) drawPath(play, color, style = Fill)
                    drawPath(play, color, style = stroke)
                }
                Tab.STATS -> {
                    bar(4.5f, 13f, color, filled, stroke)
                    bar(10.5f, 6f, color, filled, stroke)
                    bar(16.5f, 10f, color, filled, stroke)
                    drawLine(color, Offset(3f, 20.5f), Offset(21f, 20.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
                Tab.ACTIVITY -> {
                    val pulse = Path().apply {
                        moveTo(2.5f, 12.5f); lineTo(7f, 12.5f); lineTo(9.5f, 6f)
                        lineTo(14f, 19f); lineTo(16.5f, 12.5f); lineTo(21.5f, 12.5f)
                    }
                    drawPath(pulse, color, style = stroke)
                }
                Tab.SETUP -> {
                    slider(7f, 8.5f, color, filled, stroke)
                    slider(12f, 15.5f, color, filled, stroke)
                    slider(17f, 6.5f, color, filled, stroke)
                }
            }
        }
    }
}

private fun DrawScope.bar(left: Float, top: Float, color: Color, filled: Boolean, stroke: Stroke) {
    drawRoundRect(color, topLeft = Offset(left, top), size = Size(3.4f, 20.5f - top), cornerRadius = CornerRadius(1f),
        style = if (filled) Fill else stroke)
}

private fun DrawScope.slider(y: Float, knob: Float, color: Color, filled: Boolean, stroke: Stroke) {
    drawLine(color, Offset(3.5f, y), Offset(20.5f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
    // The knob covers the line: solid in the selected tab, the navigation's own colour otherwise.
    drawCircle(if (filled) color else FarmerColors.navBar, radius = 2.2f, center = Offset(knob, y))
    drawCircle(color, radius = 2.2f, center = Offset(knob, y), style = stroke)
}
