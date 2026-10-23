package com.phoneai.app

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, on-device visual target fallback for screens whose controls are custom-drawn.
 *
 * This is deliberately a heuristic region detector, not a trained YOLO model. It finds
 * high-contrast horizontal/vertical boundaries, pairs them into plausible control/card
 * rectangles, and returns transient bounds only. OCR/accessibility labels take precedence.
 * A real trained detector can replace this implementation without changing the target API.
 */
internal object VisualElementDetector {
    data class Region(val bounds: Rect, val confidence: Float)

    fun detect(bitmap: Bitmap, excluded: List<Rect> = emptyList(), maxResults: Int = 12): List<Region> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 80 || h < 120) return emptyList()

        // Work on a tiny grayscale image to keep CPU and memory use low.
        val sw = min(270, w)
        val scale = w.toFloat() / sw
        val sh = max(1, (h / scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val gray = IntArray(pixels.size) { i ->
                val p = pixels[i]
                (android.graphics.Color.red(p) * 30 +
                    android.graphics.Color.green(p) * 59 +
                    android.graphics.Color.blue(p) * 11) / 100
            }
            val horizontal = ArrayList<Int>()
            for (y in 2 until sh - 2) {
                var edges = 0
                for (x in 2 until sw - 2 step 2) {
                    if (abs(gray[y * sw + x] - gray[(y - 1) * sw + x]) >= 48) edges++
                }
                if (edges >= sw / 18) horizontal.add(y)
            }
            val vertical = ArrayList<Int>()
            for (x in 2 until sw - 2) {
                var edges = 0
                for (y in 2 until sh - 2 step 2) {
                    if (abs(gray[y * sw + x] - gray[y * sw + x - 1]) >= 48) edges++
                }
                if (edges >= sh / 24) vertical.add(x)
            }

            fun compact(lines: List<Int>, minGap: Int): List<Int> {
                if (lines.isEmpty()) return emptyList()
                val out = ArrayList<Int>()
                var sum = lines[0]
                var count = 1
                var last = lines[0]
                for (i in 1 until lines.size) {
                    val v = lines[i]
                    if (v - last <= minGap) {
                        sum += v
                        count++
                    } else {
                        out.add(sum / count)
                        sum = v
                        count = 1
                    }
                    last = v
                }
                out.add(sum / count)
                return out
            }

            val hs = compact(horizontal, 3)
            val vs = compact(vertical, 3)
            val candidates = ArrayList<Region>()
            for (topIndex in hs.indices) {
                val top = hs[topIndex]
                for (bottomIndex in topIndex + 1 until hs.size) {
                    val bottom = hs[bottomIndex]
                    val rh = bottom - top
                    if (rh < 24) continue
                    if (rh > sh * 0.38f) break
                    for (leftIndex in vs.indices) {
                        val left = vs[leftIndex]
                        val right = vs.drop(leftIndex + 1).firstOrNull { it - left >= 36 && it - left <= sw * 0.88f } ?: continue
                        val rw = right - left
                        val ratio = rw.toFloat() / rh
                        if (ratio !in 0.75f..8.0f) continue
                        // Require contrast inside the proposed region, reducing random edge matches.
                        val cx = (left + right) / 2
                        val cy = (top + bottom) / 2
                        val inset = max(2, min(rw, rh) / 6)
                        val inside = gray[cy * sw + cx]
                        val outside = gray[max(0, top - 2) * sw + cx]
                        val contrast = abs(inside - outside)
                        if (contrast < 18) continue

                        val box = Rect(
                            (left * scale).toInt().coerceIn(0, w - 1),
                            (top * scale).toInt().coerceIn(0, h - 1),
                            (right * scale).toInt().coerceIn(1, w),
                            (bottom * scale).toInt().coerceIn(1, h)
                        )
                        if (box.width() < w * 0.12f || box.height() < h * 0.025f) continue
                        if (excluded.any { Rect.intersects(it, box) }) continue
                        if (candidates.any { overlapRatio(it.bounds, box) > 0.72f }) continue
                        val confidence = (0.35f + contrast / 255f * 0.3f + min(0.25f, rw.toFloat() / sw * 0.25f)).coerceIn(0.35f, 0.82f)
                        candidates.add(Region(box, confidence))
                        if (candidates.size >= maxResults * 4) break
                    }
                    if (candidates.size >= maxResults * 4) break
                }
                if (candidates.size >= maxResults * 4) break
            }
            return candidates.sortedByDescending { it.confidence }.take(maxResults)
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val intersection = Rect(a)
        if (!intersection.intersect(b)) return 0f
        val smaller = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return intersection.width() * intersection.height().toFloat() / smaller
    }
}
