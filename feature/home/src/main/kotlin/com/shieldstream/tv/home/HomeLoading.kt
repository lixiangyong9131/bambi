package com.shieldstream.tv.home

import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.AppShapes
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shieldstream.tv.ui.component.PosterWidth
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.theme.LocalLightweightUi
import kotlinx.coroutines.delay

// Inicio → pantallas de carga y de error.

internal val SkeletonColor = Color(0xFF1A1D28)
private val SkeletonTitleColor = Color(0xFF232736)
private val SkeletonCardShape = RoundedCornerShape(6.dp)

@Composable
internal fun delayedSkeletonAlpha(): Float {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(if (visible) 1f else 0.65f, tween(180), label = "skeleton")
    return alpha
}

@Composable
internal fun skeletonPulse(): Float {
    if (LocalLightweightUi.current) return 0.8f
    val transition = rememberInfiniteTransition(label = "skeletonPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "skeletonPulseAlpha",
    )
    return pulse
}

/**
 * Matches [RowsBrowser] geometry: top hero-info block (172dp) + poster rows below + bottom fade,
 * so switching tabs shows aligned placeholders in place of the old full-screen banner skeleton.
 */
@Composable
internal fun HomeSkeleton(
    hasHeader: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val alpha = delayedSkeletonAlpha() * skeletonPulse()
    Box(modifier.background(RowBackground)) {
        // Subtle ambient glow in the upper-right backdrop stage.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.56f)
                .height(348.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1B2030), Color.Black),
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .padding(start = ContentStart, top = 8.dp, end = 320.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (hasHeader) {
                    Box(Modifier.width(140.dp).height(24.dp).background(SkeletonTitleColor, AppShapes.Badge))
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.width(260.dp).height(30.dp).background(SkeletonTitleColor, AppShapes.Badge))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(170.dp).height(14.dp).background(SkeletonColor, AppShapes.Badge))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(420.dp).height(14.dp).background(SkeletonColor, AppShapes.Badge))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(300.dp).height(14.dp).background(SkeletonColor, AppShapes.Badge))
            }
            Column(Modifier.fillMaxWidth().weight(1f)) {
                repeat(2) { rowIdx ->
                    Box(
                        Modifier
                            .padding(start = ContentStart, top = 4.dp)
                            .width(if (rowIdx == 0) 140.dp else 170.dp)
                            .height(20.dp)
                            .background(SkeletonTitleColor, AppShapes.Badge),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = ContentStart, vertical = 14.dp),
                    ) {
                        repeat(7) {
                            Box(
                                Modifier
                                    .width(PosterWidth)
                                    .aspectRatio(2f / 3f)
                                    .background(SkeletonColor, SkeletonCardShape),
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
    }
}

@Composable
internal fun HomeError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val retryRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { retryRequester.requestFocus() }
    }
    Column(
        modifier.background(AppColors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        TvButton(text = localizedStringResource(StringsR.string.home_retry), onClick = onRetry, modifier = Modifier.focusRequester(retryRequester))
    }
}
