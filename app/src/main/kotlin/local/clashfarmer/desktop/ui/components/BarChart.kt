package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.farmerStyle
import local.clashfarmer.ui.components.ChartScale

/** One bar of a [ChartGroup]; a null value draws nothing. */
data class ChartBar(val value: Double?, val color: Color)

/** One x position of [BarChart]: one or two bars (for example one per account) and its label. */
data class ChartGroup(val label: String, val bars: List<ChartBar>)

/**
 * Vertical bars on a Canvas, one or two per x position. The y axis is implicit: the scale's top
 * is printed top-left, faint guide lines sit at half and full, and only the tallest bar carries
 * a value label. X labels are thinned to every Nth. [format] formats the max label and the
 * tallest bar's value; [emptyMessage] shows in place of bars when there is nothing to draw.
 * [onBarClick], when set, gets the group index of a click.
 */
@Composable
fun BarChart(
    groups: List<ChartGroup>,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { ResourceFormat.short(it.toLong()) },
    emptyMessage: String = "No data yet",
    onBarClick: ((Int) -> Unit)? = null
) {
    val measurer = rememberTextMeasurer()
    val top = remember(groups) { ChartScale.top(groups.flatMap { g -> g.bars.map { it.value } }) }
    val click by rememberUpdatedState(onBarClick)
    val count by rememberUpdatedState(groups.size)
    val axisStyle = farmerStyle(11f, FarmerColors.muted)
    val valueStyle = farmerStyle(11f, FarmerColors.text, medium = true)
    val labelStyle = farmerStyle(10f, FarmerColors.faint)
    val emptyStyle = farmerStyle(13f, FarmerColors.faint)
    val tapModifier = if (onBarClick == null) Modifier else Modifier
        .pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(Unit) {
            detectTapGestures { at ->
                val n = count
                if (n == 0) return@detectTapGestures
                val slotWidth = (size.width - 8.dp.toPx()) / n
                val index = ((at.x - 4.dp.toPx()) / slotWidth).toInt()
                if (index in 0 until n) click?.invoke(index)
            }
        }
    Canvas(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 180.dp)
            .semantics { contentDescription = "Bar chart" }
            .then(tapModifier)
    ) {
        val pad = 4.dp.toPx()
        val headroom = 30.dp.toPx()  // max label row and the tallest bar's value
        val footer = 20.dp.toPx()    // x labels
        val plot = Rect(pad, headroom, size.width - pad, size.height - footer)
        val guide = 1.dp.toPx()

        drawText(measurer.measure(format(top), axisStyle), topLeft = Offset(pad, 0f))
        drawLine(FarmerColors.line, Offset(plot.left, plot.top), Offset(plot.right, plot.top), guide)
        drawLine(FarmerColors.line, Offset(plot.left, plot.center.y), Offset(plot.right, plot.center.y), guide)
        drawLine(FarmerColors.line, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), guide)

        val values = groups.flatMap { g -> g.bars.map { it.value } }
        if (values.none { (it ?: 0.0) > 0.0 }) {
            val empty = measurer.measure(emptyMessage, emptyStyle)
            drawText(empty, topLeft = Offset(plot.center.x - empty.size.width / 2f,
                plot.center.y - 6.dp.toPx() - empty.firstBaseline))
        }
        val tallest = ChartScale.tallest(values)
        val slots = ChartScale.slots(groups.size, plot.width, 28.dp.toPx(), gap = 0.28f)
        if (slots.size != groups.size) return@Canvas
        val every = ChartScale.labelEvery(groups.size, (plot.width / 44.dp.toPx()).toInt())
        val radius = 3.dp.toPx()
        var flat = 0
        groups.forEachIndexed { gi, group ->
            val slot = slots[gi]
            ChartScale.split(slot, group.bars.size).forEachIndexed { bi, span ->
                val item = group.bars[bi]
                val h = ChartScale.height(item.value, top, plot.height)
                if (h > 0f) {
                    val left = plot.left + span.left
                    drawRoundRect(item.color, topLeft = Offset(left, plot.bottom - h), size = Size(span.width, h),
                        cornerRadius = CornerRadius(radius))
                    // Square off the base so bars sit on the axis.
                    if (h > radius) drawRect(item.color, topLeft = Offset(left, plot.bottom - radius), size = Size(span.width, radius))
                }
                if (flat == tallest && item.value != null) {
                    val text = measurer.measure(format(item.value), valueStyle)
                    drawText(text, topLeft = Offset(plot.left + span.center - text.size.width / 2f,
                        plot.bottom - h - 5.dp.toPx() - text.firstBaseline))
                }
                flat++
            }
            if (gi % every == 0 && group.label.isNotEmpty()) {
                val text = measurer.measure(group.label, labelStyle)
                drawText(text, topLeft = Offset(plot.left + slot.center - text.size.width / 2f,
                    size.height - 5.dp.toPx() - text.firstBaseline))
            }
        }
    }
}
