package com.shieldstream.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shieldstream.tv.domain.SeriesPlaybackContext
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.PlaybackMedia
import com.shieldstream.tv.model.WatchProgress
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.ProvidePivotScroll
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppColors

@Composable
internal fun EpisodePickerOverlay(
    context: SeriesPlaybackContext,
    currentMedia: PlaybackMedia?,
    selectedSeason: Int,
    episodes: List<Episode>,
    loading: Boolean,
    progressMap: Map<String, WatchProgress>,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (Episode) -> Unit,
) {
    val seasons = remember(context.seasons, selectedSeason) {
        val list = context.seasons.filter { it.episodeCount > 0 }.map { it.number }
        if (list.isNotEmpty()) list else listOf(selectedSeason)
    }
    val currentSeason = currentMedia?.season ?: selectedSeason
    val currentEpisode = currentMedia?.episode
    val activeRequester = remember { FocusRequester() }
    val episodeListState = rememberLazyListState()

    val targetIndex = remember(episodes, selectedSeason, currentSeason, currentEpisode) {
        if (selectedSeason == currentSeason && currentEpisode != null) {
            episodes.indexOfFirst { it.season == currentSeason && it.number == currentEpisode }.coerceAtLeast(0)
        } else {
            0
        }
    }

    LaunchedEffect(episodes, selectedSeason, loading) {
        if (!loading && episodes.isNotEmpty()) {
            if (targetIndex > 0) episodeListState.scrollToItem(targetIndex)
            withFrameNanos { }
            runCatching { activeRequester.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = 52.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 52.dp),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(22.dp)
                        .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = context.item.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = localizedStringResource(StringsR.string.player_episodes_season, selectedSeason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = localizedStringResource(StringsR.string.player_episode_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }

            if (seasons.size > 1) {
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(seasons, key = { it }) { season ->
                        PickerSeasonChip(
                            label = localizedStringResource(StringsR.string.details_season_number, season),
                            selected = season == selectedSeason,
                            onClick = { onSelectSeason(season) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            when {
                loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(206.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AppColors.Accent, modifier = Modifier.size(34.dp))
                }
                episodes.isEmpty() -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(206.dp)
                        .padding(horizontal = 52.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = localizedStringResource(StringsR.string.details_no_seasons_available),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                else -> ProvidePivotScroll(leading = 52.dp) {
                    LazyRow(
                        state = episodeListState,
                        contentPadding = PaddingValues(horizontal = 52.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(episodes.size, key = { idx -> "${episodes[idx].season}:${episodes[idx].number}" }) { idx ->
                            val ep = episodes[idx]
                            val playingNow = ep.season == currentSeason && ep.number == currentEpisode
                            val progress = progressMap[WatchProgress.episodeKey(context.item.id, ep.season, ep.number)]
                            PickerEpisodeCard(
                                episode = ep,
                                playingNow = playingNow,
                                progress = progress,
                                onClick = { onSelectEpisode(ep) },
                                modifier = if (idx == targetIndex) Modifier.focusRequester(activeRequester) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSeasonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(
                width = if (selected && !focused) 1.dp else 0.dp,
                color = if (selected && !focused) AppColors.Accent else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun PickerEpisodeCard(
    episode: Episode,
    playingNow: Boolean,
    progress: WatchProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .width(248.dp)
            .clip(shape)
            .background(if (focused) Color(0xFF232327) else Color(0xFF141416))
            .border(
                width = if (focused) 2.dp else if (playingNow) 1.5.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    playingNow -> AppColors.Accent
                    else -> Color.White.copy(alpha = 0.12f)
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF1B1C22)),
        ) {
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "${episode.number}",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (playingNow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.Accent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(
                        text = localizedStringResource(StringsR.string.player_playing_now),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            } else if (progress?.isWatched == true) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(5.dp),
                ) {
                    Icon(AppIcons.Check, contentDescription = null, tint = Color(0xFF46D369), modifier = Modifier.size(14.dp))
                }
            }
            val fraction = when {
                progress?.isWatched == true -> 1f
                progress?.isResumable == true -> progress.fraction
                else -> 0f
            }
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
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
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${episode.number}. ${episode.title.ifBlank { "第 ${episode.number} 集" }}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                episode.runtimeMinutes?.let { min ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${min}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
