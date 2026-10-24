package com.google.mlkit.vision.text

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage

interface TextRecognizer {
    fun process(image: InputImage): Task<Text>

    fun close()
}
