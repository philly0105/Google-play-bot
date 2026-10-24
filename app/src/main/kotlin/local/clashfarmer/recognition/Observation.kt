package local.clashfarmer.recognition

import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.ScreenState
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport

data class RecognizedBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val center: Point get() = Point((left + right) / 2, (top + bottom) / 2)
    val region: Region get() = Region(left, top, right, bottom)
}

data class Observation(
    val state: ScreenState,
    val boxes: List<RecognizedBox>,
    val rawText: String,
    val viewport: Viewport,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun isFresh(maxAgeMs: Long = 5000): Boolean {
        return (System.currentTimeMillis() - timestampMs) <= maxAgeMs
    }

    fun assertFresh(maxAgeMs: Long = 5000) {
        if (!isFresh(maxAgeMs)) {
            val age = System.currentTimeMillis() - timestampMs
            throw StopException("Screen observation is stale ($age ms old, max allowed $maxAgeMs ms)")
        }
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.append("State: ").append(state.name).append("\n")
        sb.append("Viewport: [${viewport.left}, ${viewport.top}, ${viewport.right}, ${viewport.bottom}]\n")
        sb.append("Acquired At: ").append(timestampMs).append(" (Age: ").append(System.currentTimeMillis() - timestampMs).append("ms)\n")
        sb.append("Recognized Text (").append(boxes.size).append(" elements):\n")
        for (box in boxes.take(30)) {
            sb.append("  - \"").append(box.text).append("\" at (${box.center.x}, ${box.center.y})\n")
        }
        if (boxes.size > 30) {
            sb.append("  ... and ").append(boxes.size - 30).append(" more\n")
        }
        return sb.toString()
    }
}
