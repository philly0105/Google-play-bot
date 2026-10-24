package com.google.mlkit.vision.common

import android.graphics.Bitmap

/** An image for a recognizer. Only unrotated bitmaps, the one form the copied code makes. */
class InputImage private constructor(internal val bitmap: Bitmap) {
    companion object {
        fun fromBitmap(bitmap: Bitmap, rotationDegrees: Int): InputImage {
            require(rotationDegrees == 0) { "Only unrotated images are supported, not $rotationDegrees degrees" }
            return InputImage(bitmap)
        }
    }
}
