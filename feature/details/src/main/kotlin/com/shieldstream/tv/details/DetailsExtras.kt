package com.shieldstream.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.ui.component.PosterCard
import com.shieldstream.tv.ui.component.ProvidePivotScroll
import kotlin.math.abs

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = modifier.padding(start = 56.dp, bottom = 4.dp),
    )
}

/**
 * Consumes Left/Right D-pad presses at the ends of a horizontal row so focus stays on that row
 * instead of jumping to unrelated widgets above or below.
 */
internal fun Modifier.horizontalRowBoundary(
    stopLeft: Boolean,
    stopRight: Boolean,
): Modifier = if (!stopLeft && !stopRight) {
    this
} else {
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionLeft -> stopLeft
            Key.DirectionRight -> stopRight
            else -> false
        }
    }
}

/**
 * Tracks wrapped items inside a [FlowRow] so Left/Right stays strictly within the active visual
 * row and stops at the row edges without wrapping or jumping to another section below.
 */
internal class FlowRowFocusBoundary {
    private class Entry(
        val requester: FocusRequester,
        var bounds: Rect? = null,
    )

    private val entries = LinkedHashMap<Any, Entry>()

    fun register(key: Any, requester: FocusRequester) {
        entries[key] = Entry(requester)
    }

    fun unregister(key: Any, requester: FocusRequester) {
        val existing = entries[key]
        if (existing?.requester === requester) {
            entries.remove(key)
        }
    }

    fun updateBounds(key: Any, bounds: Rect) {
        entries[key]?.bounds = bounds
    }

    fun moveHorizontal(currentKey: Any, right: Boolean): Boolean {
        val currentBounds = entries[currentKey]?.bounds ?: return true
        val rowTolerance = currentBounds.height * 0.5f
        val candidate = entries.entries
            .asSequence()
            .filter { (k, _) -> k != currentKey }
            .mapNotNull { (_, entry) -> entry.bounds?.let { entry to it } }
            .filter { (_, bounds) -> abs(bounds.center.y - currentBounds.center.y) < rowTolerance }
            .filter { (_, bounds) ->
                if (right) bounds.center.x > currentBounds.center.x + currentBounds.width * 0.4f
                else bounds.center.x < currentBounds.center.x - currentBounds.width * 0.4f
            }
            .minByOrNull { (_, bounds) ->
                if (right) bounds.center.x else -bounds.center.x
            }
            ?.first
        if (candidate != null) {
            runCatching { candidate.requester.requestFocus() }
        }
        return true
    }
}

@Composable
internal fun Modifier.flowRowFocusItem(
    boundary: FlowRowFocusBoundary,
    itemKey: Any,
): Modifier {
    val requester = remember(itemKey) { FocusRequester() }
    DisposableEffect(boundary, itemKey) {
        boundary.register(itemKey, requester)
        onDispose { boundary.unregister(itemKey, requester) }
    }
    return this
        .focusRequester(requester)
        .onGloballyPositioned { coords ->
            boundary.updateBounds(itemKey, coords.boundsInRoot())
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> boundary.moveHorizontal(itemKey, right = false)
                Key.DirectionRight -> boundary.moveHorizontal(itemKey, right = true)
                else -> false
            }
        }
}

/** Fila de carátulas (saga, filmografía). */
@Composable
internal fun PosterStrip(
    items: List<CatalogItem>,
    onItem: (CatalogItem) -> Unit,
    highlightId: String? = null,
    modifier: Modifier = Modifier,
) {
    ProvidePivotScroll(leading = 56.dp) {
        LazyRow(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                Column {
                    PosterCard(
                        item = item,
                        onClick = { onItem(item) },
                        modifier = Modifier.horizontalRowBoundary(
                            stopLeft = index == 0,
                            stopRight = index == items.lastIndex,
                        ),
                    )
                    if (item.id == highlightId) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .width(36.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                }
            }
        }
    }
}

internal fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
