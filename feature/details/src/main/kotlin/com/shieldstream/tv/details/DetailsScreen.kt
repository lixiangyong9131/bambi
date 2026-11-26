package com.shieldstream.tv.details

import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.domain.TraktRatingTarget
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.shieldstream.tv.model.CatalogDetails
import com.shieldstream.tv.model.DoubanRatingInfo
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.ui.component.downloadIndicatorState
import com.shieldstream.tv.ui.component.busy
import com.shieldstream.tv.ui.component.DownloadIndicator
import com.shieldstream.tv.strings.localizedCode
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.WatchProgress
import com.shieldstream.tv.ui.component.AmbientBackdropImage
import com.shieldstream.tv.ui.component.FeatheredBackdropStage
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.ProvideMinimalScroll
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.formatEpisodeDate
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.tvClickable

internal val Green = Color(0xFF46D369)

internal val Grey = Color(0xFFB3B3B3)

internal val WatchedCountColor = Color.White.copy(alpha = 0.75f)

/** Cached bolt on a focused (white) row: a darker green keeps contrast. */
internal val CachedFocused = Color(0xFF0A8F3C)

internal enum class DetailsBackAction { DISMISS_ACTIONS, DISMISS_SOURCES, EXIT_DETAILS }

internal fun detailsBackAction(actionsOpen: Boolean, panelOpen: Boolean): DetailsBackAction = when {
    actionsOpen -> DetailsBackAction.DISMISS_ACTIONS
    panelOpen -> DetailsBackAction.DISMISS_SOURCES
    else -> DetailsBackAction.EXIT_DETAILS
}

internal fun detailsSourcesVisible(panelOpen: Boolean, actionsOpen: Boolean): Boolean = panelOpen && !actionsOpen

internal fun detailsMoreVisible(pendingPlay: Boolean): Boolean = !pendingPlay

internal fun upcomingEpisodeDate(airDate: String?, today: String): String? =
    airDate?.takeIf { it.isNotBlank() && it > today }

@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onOpenPerson: (Int) -> Unit = {},
    onOpenItem: (com.shieldstream.tv.model.CatalogItem) -> Unit = {},
    onOpenTrailer: (List<String>) -> Unit = {},
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    DetailsEvent.OpenPlayer -> onPlay()
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            DetailsUiState.Loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            is DetailsUiState.Error -> Text(
                text = s.message,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            is DetailsUiState.Content -> DetailsContent(s, viewModel, onBack, onOpenPerson, onOpenItem, onOpenTrailer)
        }
    }
}

@Composable
private fun DetailsContent(
    state: DetailsUiState.Content,
    viewModel: DetailsViewModel,
    onBack: () -> Unit,
    onOpenPerson: (Int) -> Unit,
    onOpenItem: (com.shieldstream.tv.model.CatalogItem) -> Unit,
    onOpenTrailer: (List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadList by viewModel.downloadList.collectAsStateWithLifecycle()
    val plannerProgress by viewModel.plannerProgress.collectAsStateWithLifecycle()
    val skipConfig by viewModel.skipConfig.collectAsStateWithLifecycle()
    // El episodio sobre el que se estaba (enfocado), no solo el último abierto: "Más" actúa sobre ese.
    var focusedEpisode by remember { mutableStateOf<Episode?>(null) }
    val menuState = remember(state, focusedEpisode) {
        val focused = focusedEpisode?.takeIf { f -> state.isSeries && f.season == state.selectedSeason }
        if (focused != null) state.copy(selectedEpisode = focused) else state
    }
    var panelOpen by remember { mutableStateOf(false) }
    // La fuente elegida a mano falló (sin cambio automático): se vuelve con la lista abierta para elegir otra.
    val reopenSources by viewModel.reopenSources.collectAsStateWithLifecycle()
    LaunchedEffect(reopenSources) {
        if (reopenSources) {
            panelOpen = true
            viewModel.sourcesReopened()
        }
    }
    var actionsOpen by remember { mutableStateOf(false) }
    var skipDialogOpen by remember { mutableStateOf(false) }
    var ratingTarget by remember { mutableStateOf<TraktRatingTarget?>(null) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    BackHandler(enabled = skipDialogOpen || actionsOpen || episodeMenu != null || !panelOpen) {
        when {
            skipDialogOpen -> skipDialogOpen = false
            ratingTarget != null -> ratingTarget = null
            episodeMenu != null -> episodeMenu = null
            else -> when (detailsBackAction(actionsOpen, panelOpen)) {
                DetailsBackAction.DISMISS_ACTIONS -> actionsOpen = false
                DetailsBackAction.DISMISS_SOURCES -> panelOpen = false
                DetailsBackAction.EXIT_DETAILS -> onBack()
            }
        }
    }

    val playRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playRequester.requestFocus() }
    }
    LaunchedEffect(panelOpen, actionsOpen, episodeMenu, skipDialogOpen) {
        if (!panelOpen && !actionsOpen && episodeMenu == null && !skipDialogOpen) {
            withFrameNanos { }
            runCatching { playRequester.requestFocus() }
        }
    }

    val item = state.details.item

    Box(Modifier.fillMaxSize().onBackKey(enabled = panelOpen && !actionsOpen) { panelOpen = false }) {
        // Modified build: edge-extended ambient backdrop fills the entire Details viewport (top and
        // bottom) so lower sections share the same color wash, while the lowered right cover artwork
        // feathers cleanly into it without zooming or hard seams.
        val artworkUrl = item.posterUrl ?: item.backdropUrl
        if (artworkUrl != null) {
            AmbientBackdropImage(
                url = artworkUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }
        FeatheredBackdropStage(
            heroUrl = artworkUrl,
            ambientUrl = null,
            stageHeight = 520.dp,
            heroWidthFraction = 0.43f,
            leftScrimAlpha = 0f,
            topPadding = 16.dp,
            endPadding = 72.dp,
            verticalBias = -0.05f,
            fadeBottomToBlack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.78f),
                    0.32f to Color.Black.copy(alpha = 0.32f),
                    0.56f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.14f),
                    0.52f to Color.Black.copy(alpha = 0.44f),
                    1f to Color.Black.copy(alpha = 0.68f),
                ),
            ),
        )

        val pageScroll = rememberScrollState()
        val pageScope = rememberCoroutineScope()
        ProvideMinimalScroll(margin = 72.dp) {
        Column(
            Modifier
                .zIndex(1f)
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(top = 44.dp, bottom = 72.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                InfoBlock(
                    details = state.details,
                    state = state,
                    skipConfig = skipConfig,
                    interactive = !panelOpen && !actionsOpen && episodeMenu == null,
                    playRequester = playRequester,
                    onPlay = { viewModel.play() },
                    onPlaySource = { source -> viewModel.playSource(source) },
                    onPlayFromStart = { viewModel.play(fromStart = true) },
                    onToggleMyList = { viewModel.toggleMyList() },
                    onOpenActions = { actionsOpen = true },
                    onTrailer = state.details.trailerKey?.let { first -> { onOpenTrailer(state.details.trailerKeys.ifEmpty { listOf(first) }) } },
                    // Al volver a los botones desde abajo, la página sube del todo: si no, la sinopsis quedaba cortada.
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.hasFocus && pageScroll.value > 0) pageScope.launch { pageScroll.scrollToTopFirmly() } },
                )
                DoubanRatingCard(
                    rating = state.details.doubanRating,
                    fallbackScore = item.rating,
                    loading = state.detailsLoading,
                    modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                )
            }

            if (state.isSeries) {
                Spacer(Modifier.height(28.dp))
                SeasonsAndEpisodes(
                    state = state,
                    interactive = !panelOpen && !actionsOpen && episodeMenu == null,
                    onSeason = { viewModel.selectSeason(it) },
                    onEpisode = { viewModel.playEpisode(it) },
                    onEpisodeLongPress = { episodeMenu = it },
                    onEpisodeFocused = { focusedEpisode = it },
                )
            }

            val interactive = !panelOpen && !actionsOpen && episodeMenu == null
            state.collection?.let { saga ->
                Spacer(Modifier.height(28.dp))
                SectionTitle(localizedStringResource(StringsR.string.details_collection, saga.name))
                PosterStrip(
                    items = saga.parts,
                    highlightId = item.id,
                    onItem = { if (interactive && it.id != item.id) onOpenItem(it) },
                )
            }
            val people = if (state.showCast) state.details.directors + state.details.cast else emptyList()
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                val shown = people.distinctBy { it.id }
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionTitle(localizedStringResource(StringsR.string.details_cast))
                    Text(
                        text = shown.size.toString(),
                        color = Grey,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    )
                }
                PeopleRow(
                    people = shown,
                    enabled = interactive,
                    onPerson = { onOpenPerson(it.id) },
                    crewIds = state.details.directors.map { it.id }.toSet(),
                    crewLabel = localizedStringResource(
                        if (state.isSeries) StringsR.string.details_crew_creator else StringsR.string.details_crew_director,
                    ),
                )
            }
        }
        }

        val indicator = downloadIndicatorState(
            downloadList,
            preparing = plannerProgress?.let { "${it.done + 1}/${it.total}" },
        )
        if (indicator != null && indicator.busy && !panelOpen && !actionsOpen) {
            DownloadIndicator(
                state = indicator,
                onClick = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp).zIndex(1f),
            )
        }

        Box(Modifier.fillMaxSize().zIndex(10f)) {
            AnimatedVisibility(
                visible = detailsSourcesVisible(panelOpen, actionsOpen),
                enter = fadeIn() + slideInHorizontally { it / 3 },
                exit = fadeOut() + slideOutHorizontally { it / 3 },
            ) {
                SourcesPanel(
                    state = state,
                    onPick = { source ->
                        panelOpen = false
                        viewModel.playSource(source)
                    },
                    isBlocked = viewModel::isBlocked,
                    onToggleSpanishOnly = viewModel::toggleSpanishOnly,
                    onMarkBroken = viewModel::markSourceBroken,
                    autoPick = remember(state.sources, state.selectedEpisode, state.sourceExclusions, state.brokenSources, state.spanishOnly) {
                        viewModel.autoPlaySource()
                    },
                )
            }
            if (actionsOpen) {
                val target = ratingTarget
                when {
                    target != null -> RatingPickerDialog(
                        state = state,
                        target = target,
                        ratings = state.ratings,
                        onSave = { viewModel.setRating(target, it) },
                        onRetry = viewModel::loadRatings,
                        onDismiss = { ratingTarget = null },
                    )
                    else -> DetailsActions(
                        state = menuState,
                        onToggleSeasonWatched = {
                            viewModel.toggleSeasonWatched()
                            actionsOpen = false
                        },
                        onToggleMovieWatched = {
                            viewModel.toggleMovieWatched()
                            actionsOpen = false
                        },
                        onToggleEpisodeWatched = { episode ->
                            viewModel.toggleEpisodeWatched(episode)
                            actionsOpen = false
                        },
                        onToggleMyList = {
                            viewModel.toggleMyList()
                            actionsOpen = false
                        },
                        onToggleFollowing = {
                            viewModel.toggleFollowing()
                            actionsOpen = false
                        },
                        onRate = {
                            ratingTarget = it
                            viewModel.loadRatings()
                        },
                        onRandomEpisode = {
                            actionsOpen = false
                            viewModel.playRandomEpisode()
                        },
                        skipSummary = if (skipConfig.enabled && (skipConfig.introSec > 0 || skipConfig.outroSec > 0)) {
                            "${skipConfig.introSec}s / ${skipConfig.outroSec}s"
                        } else {
                            localizedStringResource(StringsR.string.details_skip_off)
                        },
                        onOpenSkipSettings = {
                            actionsOpen = false
                            skipDialogOpen = true
                        },
                        downloads = downloadList,
                        onDownload = { episodes, quality ->
                            actionsOpen = false
                            viewModel.download(episodes, quality)
                        },
                        onDownloadAction = { d ->
                            actionsOpen = false
                            when {
                                d.isDone -> viewModel.deleteDownload(d.id)
                                d.isActive -> viewModel.pauseDownload(d.id)
                                else -> viewModel.resumeDownload(d.id)
                            }
                        },
                        onDismiss = { actionsOpen = false },
                    )
                }
            }

            episodeMenu?.let { episode ->
                val episodeDownload = downloadList.firstOrNull {
                    it.id == WatchProgress.episodeKey(state.details.item.id, episode.season, episode.number)
                }
                EpisodeActions(
                    state = state,
                    episode = episode,
                    download = episodeDownload,
                    onDownload = {
                        episodeMenu = null
                        if (episodeDownload?.isDone == true) viewModel.deleteDownload(episodeDownload.id) else viewModel.download(listOf(episode))
                    },
                    onPlay = {
                        episodeMenu = null
                        viewModel.playEpisode(episode)
                    },
                    onToggleWatched = {
                        viewModel.toggleEpisodeWatched(episode)
                        episodeMenu = null
                    },
                    onDismiss = { episodeMenu = null },
                )
            }
        }

        if (skipDialogOpen) {
            SkipSettingsDialog(
                enabled = skipConfig.enabled,
                introSec = skipConfig.introSec,
                outroSec = skipConfig.outroSec,
                onConfig = viewModel::saveSkipConfig,
                onDismiss = { skipDialogOpen = false },
            )
        }
    }

    state.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3500)
            viewModel.consumeMessage()
        }
    }
}

@Composable
private fun InfoBlock(
    details: CatalogDetails,
    state: DetailsUiState.Content,
    skipConfig: com.shieldstream.tv.domain.ManualSkipRepository.Config,
    interactive: Boolean,
    playRequester: FocusRequester,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleMyList: () -> Unit,
    onOpenActions: () -> Unit,
    onTrailer: (() -> Unit)?,
    onPlaySource: (com.shieldstream.tv.model.StreamSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = details.item
    val skeletonPulse = rememberDetailsSkeletonPulse()
    val skeletonPrimary = Color.White.copy(alpha = 0.24f * skeletonPulse)
    val skeletonSecondary = Color.White.copy(alpha = 0.16f * skeletonPulse)
    Column(modifier.widthIn(max = 580.dp)) {
        if (details.logoUrl != null) {
            AsyncImage(
                model = details.logoUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.heightIn(max = 110.dp).widthIn(max = 420.dp),
            )
        } else {
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))

        MetaRow(details, state, skipConfig)
        val tags = buildList {
            details.genres.take(4).forEach { add(it) }
            item.year?.let { add(it.toString()) }
        }
        val synopsis = item.overview.ifBlank { details.tagline.orEmpty() }
        // Reserve a stable minimum height for the metadata block so buttons and episodes below
        // do not jump downward when tags/cast/synopsis finish loading from Douban & CMS.
        Column(Modifier.heightIn(min = 148.dp)) {
            if (state.detailsLoading && tags.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(56.dp, 48.dp, 52.dp).forEach { w ->
                        Box(
                            Modifier
                                .width(w)
                                .height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(skeletonPrimary),
                        )
                    }
                }
            } else {
                val shownTags = if (tags.isNotEmpty()) tags else listOf(
                    if (item.type == com.shieldstream.tv.model.MediaType.TV) "剧集" else "电影",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shownTags.forEach { tag ->
                        Text(
                            text = tag,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (state.detailsLoading && details.directors.isEmpty() && details.cast.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(240.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skeletonSecondary),
                )
            } else {
                if (details.directors.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = localizedStringResource(
                            if (item.type == com.shieldstream.tv.model.MediaType.TV) StringsR.string.details_created_by else StringsR.string.details_directed_by,
                            details.directors.take(2).joinToString(", ") { it.name },
                        ),
                        color = Grey,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Modified build: show main cast near the top, next to genres/runtime.
                if (details.cast.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = localizedStringResource(
                            StringsR.string.details_starring,
                            details.cast.take(3).joinToString(", ") { it.name },
                        ),
                        color = Grey,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            details.nextEpisode?.let { next ->
                val nextCode = next.localizedCode()
                val release = remember(next, nextCode) {
                    listOf(nextCode, next.title, formatEpisodeDate(next.airDate, includeYear = true))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localizedStringResource(StringsR.string.details_coming_soon, release),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Modified build: synopsis, two lines by default, focusable expand/collapse button.
            if (synopsis.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                var expanded by remember { mutableStateOf(false) }
                var synopsisFocused by remember { mutableStateOf(false) }
                Text(
                    text = synopsis,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (expanded) "▲ 收起" else "▼ 展开",
                    color = if (synopsisFocused) Color.Black else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (synopsisFocused) Color.White else Color.White.copy(alpha = 0.12f))
                        .onFocusChanged { synopsisFocused = it.isFocused }
                        .tvClickable(enabled = interactive) { expanded = !expanded }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else if (state.detailsLoading) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.width(440.dp).height(15.dp).clip(RoundedCornerShape(4.dp)).background(skeletonPrimary))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(310.dp).height(15.dp).clip(RoundedCornerShape(4.dp)).background(skeletonSecondary))
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无剧情简介",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        val sourcesReady = state.sources as? SourcesState.Ready
        val eligibleCount = remember(sourcesReady, state.sourceExclusions, state.brokenSources, state.spanishOnly) {
            if (sourcesReady != null) state.eligibleSources(sourcesReady.sources).size else 0
        }
        val sourcesLoading = state.sources is SourcesState.Idle ||
            state.sources is SourcesState.Loading ||
            (sourcesReady != null && eligibleCount == 0 && sourcesReady.pending.isNotEmpty())
        val noSourcesTerminal = (sourcesReady != null && eligibleCount == 0 && sourcesReady.pending.isEmpty()) ||
            state.sources is SourcesState.Error

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
        ) {
            val progress = state.mainProgress
            val resumable = progress?.isResumable == true
            val episodeCode = state.selectedEpisode?.takeIf { state.isSeries }?.let { " ${it.localizedCode()}" }.orEmpty()
            val playLabel = when {
                state.pendingPlay -> localizedStringResource(StringsR.string.details_preparing)
                sourcesLoading -> localizedStringResource(StringsR.string.details_finding_sources)
                noSourcesTerminal -> localizedStringResource(StringsR.string.details_no_sources)
                resumable -> localizedStringResource(StringsR.string.details_resume_episode, episodeCode)
                else -> localizedStringResource(StringsR.string.details_play_episode, episodeCode)
            }
            TvButton(
                text = playLabel,
                icon = AppIcons.Play,
                loading = state.pendingPlay || sourcesLoading,
                onClick = onPlay,
                enabled = interactive,
                modifier = Modifier.focusRequester(playRequester),
            )
            TvButton(
                text = localizedStringResource(
                    if (state.inMyList) StringsR.string.details_in_my_list else StringsR.string.details_my_list,
                ),
                icon = if (state.inMyList) AppIcons.Check else AppIcons.Add,
                onClick = onToggleMyList,
                enabled = interactive,
            )
            if (resumable && !state.pendingPlay) {
                TvButton(
                    text = localizedStringResource(StringsR.string.details_from_the_beginning),
                    onClick = onPlayFromStart,
                    enabled = interactive,
                )
            }
            if (onTrailer != null) {
                TvButton(
                    text = localizedStringResource(StringsR.string.details_trailer),
                    icon = AppIcons.Movie,
                    onClick = onTrailer,
                    enabled = interactive,
                )
            }
            if (detailsMoreVisible(state.pendingPlay)) {
                TvButton(
                    text = localizedStringResource(StringsR.string.details_more),
                    onClick = onOpenActions,
                    enabled = interactive,
                )
            }
        }
        // Modified build: probed sources shown right under the play buttons, one card per
        // completed probe. Always show either probed cards, pulsing skeletons while checking,
        // or a clear fallback message if nothing could be probed.
        val probedSources = sourcesReady?.sources.orEmpty()
            .filter { state.sourceProbes.containsKey(it.id) }
        val probingInFlight = sourcesLoading ||
            (sourcesReady != null && !state.probingDone && probedSources.isEmpty() && eligibleCount > 0)
        Column(Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    probedSources.isNotEmpty() ->
                        "已检测片源 · 点击直接播放" + if (!state.probingDone) " · 检测中…" else ""
                    probingInFlight -> "正在检测可用片源…"
                    else -> "暂无可用片源"
                },
                color = Grey,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            when {
                probedSources.isNotEmpty() -> androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(probedSources.size) { i ->
                        val src = probedSources[i]
                        val probe = state.sourceProbes[src.id]
                        ProbeCard(
                            label = src.title,
                            detail = probe?.summary.orEmpty(),
                            ok = probe?.ok == true,
                            onClick = { onPlaySource(src) },
                        )
                    }
                }
                probingInFlight -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        Column(
                            Modifier
                                .width(190.dp)
                                .height(58.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(skeletonSecondary)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.width(96.dp).height(12.dp).clip(RoundedCornerShape(3.dp)).background(skeletonPrimary))
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width(120.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.12f * skeletonPulse)))
                        }
                    }
                }
                else -> Text(
                    text = "暂无可用播放线路，可点击「播放」重试",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        state.mainProgress?.takeIf { it.isResumable }?.let { p ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WatchBar(fraction = p.fraction, modifier = Modifier.width(240.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = buildString {
                        state.selectedEpisode?.takeIf { state.isSeries }?.let { append(it.localizedCode()).append(" · ") }
                        val left = formatRuntime(((p.remainingMs + 59_999) / 60_000).toInt())
                        append(localizedStringResource(StringsR.string.details_remaining, left))
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
            SourcesStatus(state)
        }
    }
}

@Composable
private fun DoubanRatingCard(
    rating: DoubanRatingInfo?,
    fallbackScore: Float?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val shownRating = rating ?: fallbackScore?.takeIf { it > 0f }?.let { score ->
        DoubanRatingInfo(score = score, starCount = score / 2f, voteCount = 0)
    }
    if (shownRating == null) return

    val cardShape = RoundedCornerShape(12.dp)
    val cardBg = Color(0xFF101218).copy(alpha = 0.78f)
    val borderCol = Color.White.copy(alpha = 0.14f)
    val starGold = Color(0xFFFFAC2D)
    val starDim = Color.White.copy(alpha = 0.22f)
    val barGold = Color(0xFFF6C26B)
    val pulse = rememberDetailsSkeletonPulse()
    val skelLight = Color.White.copy(alpha = 0.22f * pulse)
    val skelMuted = Color.White.copy(alpha = 0.14f * pulse)

    Column(
        modifier = modifier
            .width(236.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(1.dp, borderCol, cardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "豆瓣评分",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (loading && rating == null) {
            Row(Modifier.height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                if ((fallbackScore ?: 0f) > 0f) {
                    Text(
                        text = String.format(Lang.locale, "%.1f", fallbackScore),
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(84.dp).height(12.dp).clip(RoundedCornerShape(3.dp)).background(skelLight))
                    Box(Modifier.width(72.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(skelMuted))
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.55f, 0.80f, 0.45f, 0.25f, 0.15f).forEach { w ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(16.dp),
                    ) {
                        Box(Modifier.width(20.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(skelMuted))
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(7.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.08f * pulse)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(w)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(skelLight),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.width(36.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(skelMuted))
                    }
                }
            }
        } else {
            val active = shownRating
            Row(Modifier.height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Lang.locale, "%.1f", active.score),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    val stars = (active.starCount.takeIf { it > 0f } ?: (active.score / 2f)).coerceIn(0f, 5f)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (index in 1..5) {
                            val fill = when {
                                stars >= index -> 1f
                                stars >= index - 0.5f -> 0.5f
                                else -> 0f
                            }
                            RatingStarIcon(fillFraction = fill, filledColor = starGold, emptyColor = starDim)
                        }
                    }
                    if (active.voteCount > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${active.voteCount}人评价",
                            color = Color(0xFF9FB4CC),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (active.starDistribution.size == 5) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (star in 5 downTo 1) {
                        val ratio = active.starDistribution[star - 1].coerceIn(0f, 1f)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(16.dp),
                        ) {
                            Text(
                                text = "${star}星",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                modifier = Modifier.width(24.dp),
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.10f)),
                            ) {
                                if (ratio > 0f) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(ratio.coerceAtLeast(0.02f))
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(barGold),
                                    )
                                }
                            }
                            Text(
                                text = String.format(Lang.locale, "%.1f%%", ratio * 100f),
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(42.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingStarIcon(
    fillFraction: Float,
    filledColor: Color,
    emptyColor: Color,
) {
    Canvas(modifier = Modifier.size(13.dp)) {
        val starPath = Path().apply {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outer = size.minDimension / 2f
            val inner = outer * 0.42f
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val angle = (-PI / 2.0) + i * (PI / 5.0)
                val x = cx + (r * cos(angle)).toFloat()
                val y = cy + (r * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path = starPath, color = emptyColor)
        if (fillFraction > 0f) {
            clipRect(right = size.width * fillFraction.coerceIn(0f, 1f)) {
                drawPath(path = starPath, color = filledColor)
            }
        }
    }
}

@Composable
private fun rememberDetailsSkeletonPulse(): Float {
    val transition = rememberInfiniteTransition(label = "detailsSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000), RepeatMode.Reverse),
        label = "detailsSkeletonPulse",
    )
    return pulse
}

@Composable
private fun MetaRow(
    details: CatalogDetails,
    state: DetailsUiState.Content,
    skipConfig: com.shieldstream.tv.domain.ManualSkipRepository.Config,
) {
    val item = details.item
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
        item.rating?.takeIf { it > 0f }?.let {
            Text(
                text = localizedStringResource(StringsR.string.details_rating, it),
                color = Green,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(14.dp))
        }
        item.year?.let {
            Text(it.toString(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(14.dp))
        }
        val duration = when {
            state.isSeries && details.seasons.isNotEmpty() ->
                if (details.seasons.size == 1) localizedStringResource(StringsR.string.details_1_season) else localizedStringResource(StringsR.string.details_season_count, details.seasons.size)
            else -> details.runtimeMinutes?.let { formatRuntime(it) }
        }
        duration?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(14.dp))
        }
        (state.sources as? SourcesState.Ready)?.sources?.firstOrNull {
            !state.sourceExclusions.excludes(it) && (!state.spanishOnly || it.advertisesSpanishAudio)
        }?.let { best ->
            Badge(best.quality.label)
            if (best.hdrType != HdrType.SDR) {
                Spacer(Modifier.width(8.dp))
                Badge(best.hdrType.label)
            }
        }
        if (skipConfig.active) {
            val skipLabel = buildString {
                if (skipConfig.introSec > 0) append("片头 ").append(skipConfig.introSec).append('s')
                if (skipConfig.outroSec > 0) {
                    if (isNotEmpty()) append(" / ")
                    append("片尾 -").append(skipConfig.outroSec).append('s')
                }
            }
            if (skipLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Badge(skipLabel)
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProbeCard(
    label: String,
    detail: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            color = if (ok) Color(0xFF7EC8E3) else Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun WatchBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
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

internal fun formatRuntime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/**
 * Sube la página del todo. Con prioridad alta: el desplazamiento que hace el foco al llegar al botón ("traer a la
 * vista") tiene prioridad normal y, la segunda vez, cancelaba un animateScrollTo(0) normal a medio camino.
 */
private suspend fun androidx.compose.foundation.ScrollState.scrollToTopFirmly() {
    scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
        var last = value.toFloat()
        androidx.compose.animation.core.animate(
            initialValue = last,
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 260),
        ) { current, _ ->
            scrollBy(current - last)
            last = current
        }
    }
}
