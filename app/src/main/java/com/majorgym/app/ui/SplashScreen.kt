package com.majorgym.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shown once, the instant the app opens: a dumbbell falls from off the top
 * of the screen, lands in the middle, and settles with two small bounces —
 * exactly 3 seconds total — then hands off to the Dashboard automatically.
 * See MainActivity for where this is shown before the normal app content.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Vertical offset in dp, negative = above its resting position.
    val offsetY = remember { Animatable(-600f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        offsetY.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 3000
                -600f at 0 using LinearEasing
                0f at 900 using FastOutLinearInEasing      // fast fall, hits center
                -70f at 1300 using LinearOutSlowInEasing   // first bounce up
                0f at 1650 using FastOutLinearInEasing
                -25f at 1950 using LinearOutSlowInEasing   // smaller second bounce
                0f at 2200 using FastOutLinearInEasing
                0f at 3000                                 // settled, holds here
            }
        )
    }

    // Wordmark fades in right as the dumbbell settles, so the 3 seconds
    // doesn't feel empty.
    LaunchedEffect(Unit) {
        delay(1650)
        textAlpha.animateTo(1f, animationSpec = tween(durationMillis = 500))
    }

    // Hands off to the Dashboard at exactly the 3-second mark.
    LaunchedEffect(Unit) {
        delay(3000)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(GymColors.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = GymColors.Accent,
                modifier = Modifier
                    .size(72.dp)
                    .offset(y = offsetY.value.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "MAJOR GYM",
                color = GymColors.Text.copy(alpha = textAlpha.value),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
    }
}
