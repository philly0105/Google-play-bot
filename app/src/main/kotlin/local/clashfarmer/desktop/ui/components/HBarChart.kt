package local.clashfarmer.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.clashfarmer.desktop.ui.theme.FarmerColors
import local.clashfarmer.desktop.ui.theme.Label
import local.clashfarmer.ui.FarmerTheme
import local.clashfarmer.ui.components.ChartScale

/** One row of [HBarChart]: the phone's HBarView.Row with a Compose colour. */
data class HBarRow(val name: String, val value: Double?, val valueText: String, val color: Color, val note: String? = null)

/**
 * Horizontal comparison bars, one 40 dp row each: name, a bar scaled to the largest row, and the
 * value text (plus an optional faint note such as "31 h") at the end.
 */
@Composable
fun HBarChart(rows: List<HBarRow>, modifier: Modifier = Modifier) {
    val largest = rows.maxOfOrNull { it.value ?: 0.0 } ?: 0.0
    val notes = rows.any { it.note != null }
    Column(modifier.fillMaxWidth()) {
        if (rows.isEmpty()) Spacer(Modifier.height(40.dp))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(row.name, Modifier.width(64.dp).padding(end = 8.dp), size = FarmerTheme.SMALL, medium = true, maxLines = 1)
                val f = ChartScale.fraction(row.value, largest)
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(FarmerColors.surfaceStrong, cornerRadius = radius)
                    if (f > 0f) drawRoundRect(row.color, size = Size(size.width * f, size.height), cornerRadius = radius)
                }
                Spacer(Modifier.width(8.dp))
                Label(row.valueText, Modifier.width(68.dp), size = FarmerTheme.SMALL,
                    color = if (row.value == null) FarmerColors.faint else FarmerColors.text, bold = true, maxLines = 1,
                    align = TextAlign.End, tabular = true)
                if (notes) {
                    Label(row.note ?: "", Modifier.width(40.dp), size = FarmerTheme.LABEL, color = FarmerColors.faint, maxLines = 1,
                        align = TextAlign.End, tabular = true)
                }
            }
        }
    }
}
