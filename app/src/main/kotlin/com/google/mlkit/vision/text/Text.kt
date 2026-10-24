package com.google.mlkit.vision.text

import android.graphics.Rect

/**
 * A recognition result. ML Kit groups lines into blocks; an OcrBackend returns lines, so each
 * line here is a block of its own. [text] is the blocks joined by newlines, as ML Kit joins them.
 */
class Text(val textBlocks: List<TextBlock>) {
    val text: String = textBlocks.joinToString("\n") { it.text }

    class TextBlock(val text: String, val boundingBox: Rect?, val lines: List<Line>)

    class Line(val text: String, val boundingBox: Rect?)
}
