package com.shieldstream.tv.ui.component

import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.LocalLightweightUi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.strings.R
import com.shieldstream.tv.strings.localizedPluralResource
import com.shieldstream.tv.strings.localizedStringResource

val PosterWidth: Dp = 124.dp
private val CardShape = RoundedCornerShape(6.dp)

/** Escala al enfocar como en Bambi (animada, salvo con la interfaz ligera). */
@Composable
private fun focusScale(focused: Boolean, target: Float): Float =
    if (LocalLightweightUi.current) {
        if (focused) target else 1f
    } else {
        animateFloatAsState(
            if (focused) target else 1f,
            tween(120, easing = FastOutSlowInEasing),
            label = "posterScale",
        ).value
    }

/**
 * Póster 2:3 con foco estilo Netflix TV: escala + borde blanco. Sin ripple.
 * [onFocused] se usa para actualizar el fondo/hero de la pantalla. [newEpisodeCount] > 0 pinta la etiqueta de
 * capítulos nuevos. [caption] se mantiene por compatibilidad (Bambi no pone texto bajo los pósteres).
 */
@Composable
fun PosterCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    width: Dp = PosterWidth,
    onLongClick: (() -> Unit)? = null,
    newEpisodeCount: Int = 0,
    showYearOnFocus: Boolean = false,
    @Suppress("UNUSED_PARAMETER") caption: (@Composable ColumnScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, 1.06f)
    val context = LocalContext.current
    val posterRequest = remember(item.posterUrl, context) {
        item.posterUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(260, 390)
                .crossfade(false)
                .build()
        }
    }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .width(width)
            .aspectRatio(2f / 3f)
            // Bambi 1.0.6: el foco (clickable) va ANTES que la escala. Si la escala va delante, el rectángulo que
            // la tarjeta pide "traer a la vista" crece con la animación y la lista de filas da un "saltito".
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onLongClick = onLongClick, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .background(Color(0xFF232327))
            .then(if (focused) Modifier.border(2.dp, Color.White, CardShape) else Modifier),
    ) {
        if (posterRequest != null) {
            AsyncImage(
                model = posterRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = item.title,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        overlay()
        if (newEpisodeCount > 0) {
            StatusPill(
                text = localizedPluralResource(R.plurals.poster_new_episodes, newEpisodeCount, newEpisodeCount),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        } else {
            val ratingText = remember(item.rating) {
                item.rating?.takeIf { it > 0f }?.let { String.format(java.util.Locale.US, "%.1f", it) }
            }
            if (ratingText != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "★ $ratingText",
                        color = Color(0xFFFFC145),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        val focusYear = remember(item.year) { item.year?.toString() }
        if (showYearOnFocus && focusYear != null) {
            AnimatedVisibility(
                visible = focused,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(90)),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                Text(
                    text = focusYear,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Póster de catálogo (filas de Inicio, Mi lista…): en Bambi es el mismo póster sin texto debajo. */
@Composable
fun CatalogPosterCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    width: Dp = PosterWidth,
    onLongClick: (() -> Unit)? = null,
    newEpisodeCount: Int = 0,
) {
    PosterCard(
        item = item,
        onClick = onClick,
        modifier = modifier,
        onFocused = onFocused,
        width = width,
        onLongClick = onLongClick,
        newEpisodeCount = newEpisodeCount,
        showYearOnFocus = true,
    )
}

/** Degradado inferior con una o dos líneas de texto (y barra de progreso opcional), como "Seguir viendo". */
@Composable
private fun BoxScope.BottomLabel(label: String, fraction: Float?, barHeight: Dp = 3.dp) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(84.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black.copy(alpha = 0.75f),
                    1f to Color.Black.copy(alpha = 0.95f),
                ),
            ),
    )
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (fraction != null) {
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(AppColors.Accent),
                )
            }
        }
    }
}

/**
 * Tarjeta de "Seguir viendo": póster con la etiqueta (T1:E6 · 14 min) y la barra roja de progreso.
 * Mantener OK → [onLongClick] (opciones). [newEpisodeCount]: capítulos nuevos de la serie.
 */
@Composable
fun ContinueCard(
    item: CatalogItem,
    fraction: Float,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    newEpisodeCount: Int = 0,
) {
    var focused by remember { mutableStateOf(false) }
    val barHeight by animateDpAsState(if (focused) 5.dp else 3.dp, tween(120), label = "continueBar")
    PosterCard(
        item = item,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        onFocused = onFocused,
        onLongClick = onLongClick,
        newEpisodeCount = newEpisodeCount,
    ) {
        BottomLabel(label, fraction, barHeight = barHeight)
    }
}

/** Episodio recién emitido o por llegar: póster con la etiqueta roja arriba y el capítulo abajo. */
@Composable
fun NewEpisodeCard(
    item: CatalogItem,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    count: Int = 1,
    onLongClick: (() -> Unit)? = null,
) {
    PosterCard(
        item = item,
        onClick = onClick,
        modifier = modifier,
        onFocused = onFocused,
        onLongClick = onLongClick,
    ) {
        StatusPill(
            text = if (count == 0) {
                localizedStringResource(R.string.coming_soon)
            } else {
                localizedPluralResource(R.plurals.new_episode_count, count, count)
            },
            dot = count > 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )
        if (label.isNotEmpty()) BottomLabel(label, fraction = null)
    }
}

/** Póster con una etiqueta arriba (p. ej. "A continuación") y un texto abajo (capítulo / fecha). */
@Composable
fun LabeledPosterCard(
    item: CatalogItem,
    badge: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    badgeDot: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    PosterCard(
        item = item,
        onClick = onClick,
        modifier = modifier,
        onFocused = onFocused,
        onLongClick = onLongClick,
    ) {
        if (badge.isNotEmpty()) {
            StatusPill(
                text = badge,
                dot = badgeDot,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
        if (label.isNotEmpty()) BottomLabel(label, fraction = null)
    }
}

/**
 * Tarjeta "Top 10": número gigante con contorno gris + póster, como en Netflix.
 */
@Composable
fun Top10Card(
    rank: Int,
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    newEpisodeCount: Int = 0,
) {
    // Modified build: no big rank number - just the poster at normal size.
    Box(modifier = modifier.width(PosterWidth).height(PosterWidth * 1.5f)) {
        PosterCard(
            item = item,
            onClick = onClick,
            onFocused = onFocused,
            onLongClick = onLongClick,
            newEpisodeCount = newEpisodeCount,
            showYearOnFocus = true,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
