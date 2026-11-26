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
                val raw = line.text.trim().replace(Regex("\\s+"), " ")
                if (raw.isBlank()) continue
                // A long line (a URL, a full sentence, a paragraph) used to be dropped entirely
                // here instead of clipped, so anything on screen past 100 characters was simply
                // invisible to Orion - not shortened, just gone, with nothing standing in for it.
                // Clip it instead, the same way every other long value in this app is clipped
                // (OrionVoice.action, tool receipts, etc), so Orion still sees that the text is
                // there and roughly what it says, rather than perceiving nothing at all.
                val text = if (raw.length > 100) raw.take(99).trimEnd() + "…" else raw
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
