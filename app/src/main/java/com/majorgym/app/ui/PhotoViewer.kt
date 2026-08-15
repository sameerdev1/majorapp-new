package com.majorgym.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen preview for a member's profile photo (previously tapping the
 * photo did nothing). Pinch to zoom, double-tap to zoom, drag while zoomed,
 * close button or tap the dark backdrop to dismiss. If there's no photo on
 * file, shows a premium placeholder instead of a blank/broken screen.
 *
 * Note: dismissing is via the close button or tapping the backdrop, not a
 * swipe gesture — combining swipe-to-dismiss with pinch/pan reliably needs
 * careful gesture arbitration that's risky to get right without on-device
 * testing, so it was left out rather than shipped half-working.
 */
@Composable
fun FullScreenPhotoViewer(photoPath: String?, memberName: String, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        visible = true
        alpha.animateTo(1f, animationSpec = tween(220))
    }

    fun close() {
        visible = false
    }

    LaunchedEffect(visible) {
        if (!visible) {
            alpha.animateTo(0f, animationSpec = tween(180))
            delay(20)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value }
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { close() }
        ) {
            val hasPhoto = photoPath != null && File(photoPath).exists()

            if (hasPhoto) {
                var scale by remember { mutableStateOf(1f) }
                // Section 22 (optional, only added because it's low-risk):
                // `offset` stays a plain, synchronously-updated state for
                // panning — identical zero-lag real-time feel to before, so
                // active pinch/pan is completely unaffected. `springProgress`
                // is only used for the brief, occasional moment the image
                // needs to ease back to center (zoom released back to 1x, or
                // double-tap reset) instead of snapping there instantly.
                var offset by remember { mutableStateOf(Offset.Zero) }
                var springStartOffset by remember { mutableStateOf(Offset.Zero) }
                val springProgress = remember { Animatable(1f) }
                val gestureScope = rememberCoroutineScope()

                fun springBackToCenter() {
                    if (offset == Offset.Zero) return
                    springStartOffset = offset
                    gestureScope.launch {
                        springProgress.snapTo(1f)
                        springProgress.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)
                        ) {
                            offset = springStartOffset * value
                        }
                    }
                }

                AsyncImage(
                    model = File(photoPath!!),
                    contentDescription = memberName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                if (newScale <= 1f) {
                                    springBackToCenter()
                                } else {
                                    offset += pan
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        springBackToCenter()
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                                onTap = { /* consume so it doesn't fall through to the backdrop dismiss */ }
                            )
                        }
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape)
                            .background(GymColors.Surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = GymColors.TextFaint, modifier = Modifier.size(72.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No photo on file", color = GymColors.TextMuted, fontSize = 13.sp)
                }
            }

            IconButton(
                onClick = { close() },
                modifier = Modifier.padding(16.dp).align(Alignment.TopEnd)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
