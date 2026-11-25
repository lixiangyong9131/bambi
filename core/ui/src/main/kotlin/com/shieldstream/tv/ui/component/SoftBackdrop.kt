package com.shieldstream.tv.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

/** Modified build: higher decode size + stronger blur so the softened backdrop blends with the
 * sharp foreground (theme-colour extension) instead of showing blocky low-res banding. */
private const val SoftWidth = 960
private const val SoftHeight = 540

/**
 * Box-blur radius in decoded pixels (three passes). 4 was too soft on the Nokia; 3 keeps shapes
 * readable while still quieting detail behind the rows.
 */
private const val SoftRadius = 12

/**
 * Full-screen backdrop that is blurred once while loading, Muzei-style. The blur runs on Coil's
 * background decoder on a 320×180 bitmap; the result is an ordinary bitmap, so drawing and
 * scrolling cost nothing extra per frame. Avoid GPU `Modifier.blur` for this: it needs API 31 and
 * re-blurs a full-screen layer whenever that layer redraws.
 */
@Composable
fun SoftBackdropImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(SoftWidth, SoftHeight)
            .transformations(SoftenTransformation)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * Borderless TV hero stage combining an edge-extended [AmbientBackdropImage] fill with a right-side
 * un-bloated sharp artwork that smoothly feathers into the ambient backdrop and bottom black fade.
 */
@Composable
fun FeatheredBackdropStage(
    heroUrl: String?,
    ambientUrl: String?,
    stageHeight: Dp,
    modifier: Modifier = Modifier,
    heroWidthFraction: Float = 0.62f,
    leftScrimAlpha: Float = 0.74f,
    topPadding: Dp = 0.dp,
    verticalBias: Float = 0.12f,
    fadeBottomToBlack: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(stageHeight),
    ) {
        if (ambientUrl != null) {
            AmbientBackdropImage(
                url = ambientUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (heroUrl != null) {
            AsyncImage(
                model = heroUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, verticalBias),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topPadding)
                    .fillMaxWidth(heroWidthFraction)
                    .fillMaxHeight()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.30f to Color.White.copy(alpha = 0.85f),
                                0.48f to Color.White,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to if (topPadding > 0.dp) Color.Transparent else Color.White,
                                0.08f to Color.White,
                                0.56f to Color.White,
                                0.96f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        if (leftScrimAlpha > 0f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = leftScrimAlpha),
                        0.38f to Color.Black.copy(alpha = 0.36f),
                        0.72f to Color.Transparent,
                    ),
                ),
            )
        }
        if (fadeBottomToBlack) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.58f to Color.Transparent,
                        0.96f to Color.Black,
                        1f to Color.Black,
                    ),
                ),
            )
        }
    }
}

/**
 * Edge-extended ambient color wash decoded once on Coil's background thread from a tiny
 * [AmbientSize] bitmap: clamping box-blur smears the poster's edge colors smoothly outward
 * without per-frame GPU blur cost.
 */
@Composable
fun AmbientBackdropImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(AmbientWidth, AmbientHeight)
            .transformations(AmbientTransformation)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private const val AmbientWidth = 144
private const val AmbientHeight = 144
private const val AmbientRadius = 18

private object AmbientTransformation : Transformation {
    override val cacheKey: String = "ambient-$AmbientWidth-$AmbientRadius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scaled = Bitmap.createScaledBitmap(input, AmbientWidth, AmbientHeight, true)
        val pixels = IntArray(AmbientWidth * AmbientHeight)
        scaled.getPixels(pixels, 0, AmbientWidth, 0, 0, AmbientWidth, AmbientHeight)
        boxBlur(pixels, AmbientWidth, AmbientHeight, AmbientRadius, passes = 3)
        val output = Bitmap.createBitmap(AmbientWidth, AmbientHeight, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, AmbientWidth, 0, 0, AmbientWidth, AmbientHeight)
        return output
    }
}

private object SoftenTransformation : Transformation {
    override val cacheKey: String = "soften-$SoftWidth-$SoftRadius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = if (input.width > SoftWidth) {
            val height = (input.height * SoftWidth.toFloat() / input.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(input, SoftWidth, height, true)
        } else {
            input
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        boxBlur(pixels, width, height, SoftRadius)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}

/**
 * Approximates a Gaussian blur with [passes] horizontal and vertical box blurs. Edges clamp, so
 * the borders do not darken. Operates in place on ARGB pixels.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurLine(pixels, scratch, lines = height, length = width, lineStride = width, step = 1, radius = radius)
        blurLine(scratch, pixels, lines = width, length = height, lineStride = 1, step = width, radius = radius)
    }
}

private fun blurLine(src: IntArray, dst: IntArray, lines: Int, length: Int, lineStride: Int, step: Int, radius: Int) {
    val window = radius * 2 + 1
    val last = length - 1
    for (line in 0 until lines) {
        val base = line * lineStride
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = src[base + k.coerceIn(0, last) * step]
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0..last) {
            dst[base + i * step] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = src[base + (i - radius).coerceIn(0, last) * step]
            val enter = src[base + (i + radius + 1).coerceIn(0, last) * step]
            a += (enter ushr 24) - (out ushr 24)
            r += ((enter shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((enter shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (enter and 0xFF) - (out and 0xFF)
        }
    }
}
