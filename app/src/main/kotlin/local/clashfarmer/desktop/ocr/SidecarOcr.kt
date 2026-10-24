package local.clashfarmer.desktop.ocr

import kotlinx.coroutines.runBlocking
import local.clashfarmer.desktop.sidecar.Sidecar

/** [OcrBackend] through the sidecar's PaddleOCR. The ML Kit shim's thread waits for each answer. */
class SidecarOcr(private val sidecar: Sidecar) : OcrBackend {
    override fun recognize(width: Int, height: Int, bgra: ByteArray): List<OcrLine> =
        runBlocking { sidecar.ocr(width, height, bgra) }.map { OcrLine(it.text, it.left, it.top, it.right, it.bottom) }
}
