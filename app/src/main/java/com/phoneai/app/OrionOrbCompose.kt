package com.phoneai.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The shared animated silver point-cloud orb for Compose screens. */
@Composable
fun OrionOrb(modifier: Modifier, level: Float = 0f, busy: Boolean = false) {
    val painter = remember { OrionOrbPainter() }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { t = (it - start) / 1_000_000_000f }
        }
    }
    Canvas(modifier.semantics { contentDescription = "Orion" }) {
        val frame = t
        drawIntoCanvas { c -> painter.draw(c.nativeCanvas, size.width / 2f, size.height / 2f, minOf(size.width, size.height) * .36f, frame, level, busy) }
    }
}
