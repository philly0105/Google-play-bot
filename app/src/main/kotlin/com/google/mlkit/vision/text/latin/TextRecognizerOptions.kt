package com.google.mlkit.vision.text.latin

/** The options ScreenReader asks for. They choose nothing here: the OcrBackend decides the model. */
class TextRecognizerOptions private constructor() {
    companion object {
        val DEFAULT_OPTIONS = TextRecognizerOptions()
    }
}
