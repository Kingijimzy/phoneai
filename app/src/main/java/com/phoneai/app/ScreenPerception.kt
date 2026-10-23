package com.phoneai.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device screen OCR. Results are transient and are never persisted by this class.
 * Callers must provide rectangles that must not be read (for example password fields).
 */
internal object ScreenPerception {
    data class OcrItem(val text: String, val bounds: Rect)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognize(bitmap: Bitmap, excluded: List<Rect> = emptyList()): List<OcrItem> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(image), 4, TimeUnit.SECONDS)
        val out = ArrayList<OcrItem>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim().replace(Regex("\\s+"), " ")
                if (text.isBlank() || text.length > 100) continue
                val box = line.boundingBox ?: continue
                if (box.width() <= 0 || box.height() <= 0) continue
                // Never surface OCR text from a protected rectangle.
                if (excluded.any { Rect.intersects(it, box) }) continue
                out.add(OcrItem(text, Rect(box)))
                if (out.size >= 60) return out
            }
        }
        return out
    }
}
