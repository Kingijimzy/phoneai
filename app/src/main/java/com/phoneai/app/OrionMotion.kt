package com.phoneai.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Motion helpers shared by the chat: items glide in, controls squish when pressed. */

/** Fades and slides a message up the first time it appears, with a soft spring. */
fun Modifier.orionEnter(distancePx: Float = 36f): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) }
    graphicsLayer {
        val p = progress.value
        alpha = (p * 1.4f).coerceIn(0f, 1f)
        translationY = (1f - p) * distancePx
        val s = 0.96f + 0.04f * p
        scaleX = s; scaleY = s
    }
}

/** Shrinks slightly while a finger is down. Observes touches without consuming them, so clicks still work. */
fun Modifier.pressScale(pressed: Float = 0.92f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(down) { scale.animateTo(if (down) pressed else 1f, tween(if (down) 90 else 180)) }
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                down = false
            }
        }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
