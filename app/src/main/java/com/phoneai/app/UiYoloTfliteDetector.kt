package com.phoneai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Optional on-device YOLOv8 UI detector. It is called only when accessibility + OCR are sparse.
 * The model is bundled in assets/models and no screenshots leave the device.
 */
internal object UiYoloTfliteDetector {
    data class Region(val bounds: Rect, val label: String, val confidence: Float)

    private const val ASSET = "models/android_ui_detection_yolov8.tflite"
    private const val INPUT = 640
    private const val CONFIDENCE = 0.36f
    private const val IOU = 0.45f
    private val labels = listOf(
        "BackgroundImage", "Bottom_Navigation", "Card", "CheckBox", "Checkbox",
        "CheckedTextView", "Drawer", "EditText", "Icon", "Image", "Map", "Modal",
        "Multi_Tab", "PageIndicator", "Remember", "Spinner", "Switch", "Text",
        "TextButton", "Toolbar", "UpperTaskBar"
    )

    @Volatile private var attempted = false
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var unavailable = false

    @Synchronized
    private fun load(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (attempted && unavailable) return null
        attempted = true
        return try {
            val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            Interpreter(model, Interpreter.Options().setNumThreads(2)).also { interpreter = it }
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    @Synchronized
    fun detect(context: Context, bitmap: Bitmap, excluded: List<Rect>, maxResults: Int = 10): List<Region>? {
        val model = load(context) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val originalW = bitmap.width
            val originalH = bitmap.height
            val scale = min(INPUT.toFloat() / originalW, INPUT.toFloat() / originalH)
            val resizedW = max(1, (originalW * scale).toInt())
            val resizedH = max(1, (originalH * scale).toInt())
            val padX = (INPUT - resizedW) / 2
            val padY = (INPUT - resizedH) / 2
            val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
            val input = ByteBuffer.allocateDirect(1 * INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
            try {
                val row = IntArray(resizedW)
                for (y in 0 until INPUT) {
                    val sy = y - padY
                    val validY = sy in 0 until resizedH
                    if (validY) resized.getPixels(row, 0, resizedW, 0, sy, resizedW, 1)
                    for (x in 0 until INPUT) {
                        val sx = x - padX
                        val p = if (validY && sx in 0 until resizedW) row[sx] else 0xFF727272.toInt()
                        input.putFloat(Color.red(p) / 255f)
                        input.putFloat(Color.green(p) / 255f)
                        input.putFloat(Color.blue(p) / 255f)
                    }
                }
            } finally {
                if (resized !== bitmap && !resized.isRecycled) resized.recycle()
            }
            input.rewind()

            val shape = model.getOutputTensor(0).shape()
            if (shape.size != 3 || shape[0] != 1) return null
            val output = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
            model.run(input, output)
            val channelsFirst = shape[1] == labels.size + 4
            val channelCount = if (channelsFirst) shape[1] else shape[2]
            val anchors = if (channelsFirst) shape[2] else shape[1]
            if (channelCount < labels.size + 4) return null

            val candidates = ArrayList<Region>()
            for (i in 0 until anchors) {
                var bestClass = -1
                var bestScore = 0f
                for (c in 0 until labels.size) {
                    val score = if (channelsFirst) output[0][c + 4][i] else output[0][i][c + 4]
                    if (score > bestScore) { bestScore = score; bestClass = c }
                }
                if (bestClass < 0 || bestScore < CONFIDENCE) continue
                val cx = value(output, channelsFirst, i, 0)
                val cy = value(output, channelsFirst, i, 1)
                val bw = value(output, channelsFirst, i, 2)
                val bh = value(output, channelsFirst, i, 3)
                val left = (((cx - bw / 2f) - padX) / scale).toInt().coerceIn(0, originalW)
                val top = (((cy - bh / 2f) - padY) / scale).toInt().coerceIn(0, originalH)
                val right = (((cx + bw / 2f) - padX) / scale).toInt().coerceIn(0, originalW)
                val bottom = (((cy + bh / 2f) - padY) / scale).toInt().coerceIn(0, originalH)
                val box = Rect(left, top, right, bottom)
                if (box.width() < originalW * 0.025f || box.height() < originalH * 0.012f) continue
                // A small intersection is expected when a button contains OCR/accessibility text.
                // Suppress only detections substantially covered by an existing target.
                if (excluded.any { overlapRatio(it, box) > 0.62f }) continue
                candidates.add(Region(box, labels[bestClass], bestScore))
            }

            val selected = ArrayList<Region>()
            for (candidate in candidates.sortedByDescending { it.confidence }) {
                if (selected.none { it.label == candidate.label && iou(it.bounds, candidate.bounds) > IOU }) {
                    selected.add(candidate)
                    if (selected.size >= maxResults) break
                }
            }
            Log.i("OrionUiDetector", "detect_ms=${SystemClock.elapsedRealtime() - startedAt} targets=${selected.size}")
            selected
        } catch (error: Exception) {
            Log.w("OrionUiDetector", "TFLite inference failed; heuristic fallback will be used", error)
            null
        }
    }

    private fun value(output: Array<Array<FloatArray>>, channelsFirst: Boolean, anchor: Int, channel: Int): Float =
        if (channelsFirst) output[0][channel][anchor] else output[0][anchor][channel]

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val intersection = Rect(a)
        if (!intersection.intersect(b)) return 0f
        val smallerArea = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return intersection.width() * intersection.height().toFloat() / smallerArea
    }

    private fun iou(a: Rect, b: Rect): Float {
        val intersection = Rect(a)
        if (!intersection.intersect(b)) return 0f
        val intersectionArea = intersection.width().toFloat() * intersection.height()
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - intersectionArea
        return if (union <= 0f) 0f else intersectionArea / union
    }
}
