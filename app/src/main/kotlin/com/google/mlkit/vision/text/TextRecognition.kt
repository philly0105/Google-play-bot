package com.google.mlkit.vision.text

import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import local.clashfarmer.desktop.ocr.OcrBackends
import local.clashfarmer.desktop.ocr.OcrLine
import local.clashfarmer.desktop.ocr.bgraOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object TextRecognition {
    /** A recognizer that reads through whatever OcrBackend is installed when an image comes in. */
    fun getClient(options: TextRecognizerOptions): TextRecognizer = BackendTextRecognizer()
}

/** One daemon thread for every recognizer, so images are read one at a time. */
private val ocrThread: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ocr").apply { isDaemon = true }
}

private class BackendTextRecognizer : TextRecognizer {
    @Volatile private var closed = false

    override fun process(image: InputImage): Task<Text> {
        val task = Task<Text>()
        val backend = OcrBackends.current
        when {
            closed -> task.setException(IllegalStateException("This detector is already closed!"))
            backend == null -> task.setException(
                IllegalStateException("No OCR backend installed; call OcrBackends.install before reading the screen")
            )
            else -> {
                val width = image.bitmap.width
                val height = image.bitmap.height
                // Copied now: the caller recycles the bitmap as soon as the task settles or its
                // coroutine is cancelled, which can be before the backend has read it.
                val bgra = bgraOf(image.bitmap)
                ocrThread.execute {
                    try {
                        task.setResult(textOf(backend.recognize(width, height, bgra)))
                    } catch (e: Exception) {
                        task.setException(e)
                    } catch (e: Throwable) {
                        // An Error (a TODO() in a backend, say) must still settle the task, or the
                        // read waits on it forever.
                        task.setException(RuntimeException("OCR backend failed: $e", e))
                    }
                }
            }
        }
        return task
    }

    override fun close() {
        closed = true
    }

    private fun textOf(lines: List<OcrLine>) = Text(
        lines.map { line ->
            val box = Rect(line.left, line.top, line.right, line.bottom)
            Text.TextBlock(line.text, box, listOf(Text.Line(line.text, box)))
        }
    )
}
