package com.shieldstream.tv.ui.component

import androidx.annotation.StringRes
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.ui.theme.AppColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val RailCollapsedWidth: Dp = 64.dp
private val RailExpandedWidth: Dp = 216.dp

enum class RailDestination(val icon: ImageVector) {
    SEARCH(AppIcons.Search),
    HOME(AppIcons.Home),
    TV(AppIcons.Tv),
    MOVIES(AppIcons.Movie),
    ANIME(AppIcons.Anime),
    SETTINGS(AppIcons.Settings),
}

@get:StringRes
private val RailDestination.labelResource: Int
    get() = when (this) {
        RailDestination.SEARCH -> StringsR.string.common_search
        RailDestination.HOME -> StringsR.string.common_home
        RailDestination.TV -> StringsR.string.common_tv_shows
        RailDestination.MOVIES -> StringsR.string.common_movies
        RailDestination.ANIME -> StringsR.string.common_anime
        RailDestination.SETTINGS -> StringsR.string.common_settings
    }

@Composable
fun NavRail(
    selected: RailDestination,
    onSelect: (RailDestination) -> Unit,
    selectedRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") peekExpanded: Boolean = false,
    onRightExit: (() -> Unit)? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val scrim = Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.96f),
        0.78f to Color.Black.copy(alpha = 0.88f),
        1f to Color.Transparent,
    )
    val mainDestinations = remember { RailDestination.entries.filter { it != RailDestination.SETTINGS } }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(RailExpandedWidth)
            .background(scrim)
            .onFocusChanged {
                hasFocus = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .onPreviewKeyEvent { event ->
                if (hasFocus && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && onRightExit != null) {
                    onRightExit()
                    true
                } else {
                    false
                }
            }
            .focusGroup(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.weight(1f))
            mainDestinations.forEach { dest ->
                RailItem(
                    destination = dest,
                    selected = dest == selected,
                    expanded = true,
                    onClick = { onSelect(dest) },
                    modifier = if (dest == selected) Modifier.focusRequester(selectedRequester) else Modifier,
                )
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.weight(1f))
            RailItem(
                destination = RailDestination.SETTINGS,
                selected = selected == RailDestination.SETTINGS,
                expanded = true,
                onClick = { onSelect(RailDestination.SETTINGS) },
                modifier = if (selected == RailDestination.SETTINGS) Modifier.focusRequester(selectedRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: RailDestination,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val label = localizedStringResource(destination.labelResource)
    val tint = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.55f)
    }
    val rowBg = when {
        focused -> Color.White
        selected && expanded -> Color.White.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected && !focused) AppColors.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(7.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(destination.icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = label,
                    color = tint,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
