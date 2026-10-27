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

/** Local semantic matching for visible UI targets; keeps model-generated coordinates out of the path. */
internal object ScreenTargetMatcher {
    fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun aliases(request: String): List<String> {
        val q = normalize(request)
        val common = when {
            Regex("\\b(search|find)\\b").containsMatchIn(q) -> listOf("search", "search apps and games", "search apps games", "search google play", "search in apps", "search button")
            Regex("\\b(back|go back)\\b").containsMatchIn(q) -> listOf("back", "navigate up", "up")
            Regex("\\b(close|dismiss)\\b").containsMatchIn(q) -> listOf("close", "dismiss", "cancel")
            else -> emptyList()
        }
        return (listOf(q) + common).filter { it.isNotBlank() }.distinct()
    }

    fun score(request: String, visibleLabel: String, clickable: Boolean): Int {
        val label = normalize(visibleLabel)
        if (label.isBlank()) return 0
        val aliases = aliases(request)
        val best = aliases.maxOfOrNull { q ->
            when {
                label == q -> 100
                label.startsWith(q) || q.startsWith(label) -> 78
                label.contains(q) || q.contains(label) -> 62
                else -> {
                    val words = q.split(' ').filter { it.length > 1 }.toSet()
                    val matched = words.count { label.split(' ').contains(it) }
                    if (words.isNotEmpty() && matched > 0) (matched * 50 / words.size) else 0
                }
            }
        } ?: 0
        return best + if (clickable) 12 else 0
    }
}
