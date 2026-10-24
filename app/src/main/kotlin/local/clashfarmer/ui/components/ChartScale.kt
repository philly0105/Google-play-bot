package local.clashfarmer.ui.components

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The pure axis and bar maths behind [BarChartView] and [HBarView]. No Android types. */
object ChartScale {
    private val steps = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

    /**
     * The smallest "round" number (1, 2, 2.5 or 5 times a power of ten) at or above [max].
     * Zero, negative or non-finite data scale to 1 so an empty chart still has an axis.
     */
    fun niceMax(max: Double): Double {
        if (!max.isFinite() || max <= 0.0) return 1.0
        val power = 10.0.pow(floor(log10(max)))
        val fraction = max / power
        val step = steps.firstOrNull { it >= fraction - 1e-9 } ?: 10.0
        return step * power
    }

    /** [niceMax] of the largest value; nulls and negatives count as zero. */
    fun top(values: List<Double?>): Double = niceMax(values.maxOfOrNull { it ?: 0.0 } ?: 0.0)

    /** Bar height in pixels for [value] on a plot [height] tall whose top is [top]. */
    fun height(value: Double?, top: Double, height: Float): Float {
        if (value == null || !value.isFinite() || value <= 0.0 || top <= 0.0) return 0f
        return (value / top * height).toFloat().coerceIn(0f, height)
    }

    /** One horizontal slot: [left, right) in pixels. */
    data class Span(val left: Float, val right: Float) {
        val width: Float get() = right - left
        val center: Float get() = (left + right) / 2f
    }

    /**
     * Splits [width] into [count] equal slots and centres a bar in each, [gap] of the slot left
     * empty, never wider than [maxBar]. A single bar is centred, not stretched across the chart.
     */
    fun slots(count: Int, width: Float, maxBar: Float, gap: Float = 0.3f): List<Span> {
        if (count <= 0 || width <= 0f) return emptyList()
        val slot = width / count
        val bar = min(slot * (1f - gap.coerceIn(0f, 0.9f)), maxBar).coerceAtLeast(1f)
        return List(count) { i ->
            val center = slot * i + slot / 2f
            Span(center - bar / 2f, center + bar / 2f)
        }
    }

    /** Splits one slot between [series] side-by-side bars with a 2 px gap between them. */
    fun split(span: Span, series: Int, innerGap: Float = 2f): List<Span> {
        if (series <= 1) return listOf(span)
        val each = max(1f, (span.width - innerGap * (series - 1)) / series)
        return List(series) { i ->
            val left = span.left + i * (each + innerGap)
            Span(left, left + each)
        }
    }

    /** Label every Nth x position so at most [maxLabels] fit. Always at least 1. */
    fun labelEvery(count: Int, maxLabels: Int): Int {
        if (count <= 0 || maxLabels <= 0) return 1
        return max(1, ceil(count.toDouble() / maxLabels).toInt())
    }

    /** Index of the tallest bar, the only one that gets a value label; null when every bar is empty. */
    fun tallest(values: List<Double?>): Int? {
        var best: Int? = null
        values.forEachIndexed { i, v ->
            if (v != null && v > 0.0 && (best == null || v > (values[best!!] ?: 0.0))) best = i
        }
        return best
    }

    /** Fill fraction of a horizontal bar against the largest value shown. */
    fun fraction(value: Double?, largest: Double): Float {
        if (value == null || !value.isFinite() || value <= 0.0 || largest <= 0.0) return 0f
        return (value / largest).toFloat().coerceIn(0f, 1f)
    }
}
