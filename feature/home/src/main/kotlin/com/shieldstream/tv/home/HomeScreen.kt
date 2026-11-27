package com.shieldstream.tv.home

import com.shieldstream.tv.ui.theme.AppSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.domain.EpisodeTarget
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.CatalogRow
import com.shieldstream.tv.model.Category
import com.shieldstream.tv.model.ContinueInfo
import com.shieldstream.tv.model.FeaturedItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.ui.component.RailDestination

internal val ContentStart = AppSpacing.ScreenEdge

internal val RowBackground = Color.Black

internal enum class UpNextBadge { UP_NEXT, NEW_EPISODE, NEW_SEASON }

internal fun upNextBadge(info: com.shieldstream.tv.model.UpNextInfo?): UpNextBadge = when {
    info?.isNewEpisode == true && info.isNewSeason -> UpNextBadge.NEW_SEASON
    info?.isNewEpisode == true -> UpNextBadge.NEW_EPISODE
    else -> UpNextBadge.UP_NEXT
}

@Composable
fun UnifiedHomeScreen(
    onItemClick: (CatalogItem) -> Unit,
    onContinueClick: (CatalogItem, ContinueInfo) -> Unit,
    onNewEpisodeClick: (CatalogItem, Episode) -> Unit,
    onUpNextClick: (CatalogItem, Episode) -> Unit,
    onBackToTop: () -> Unit,
    railRequester: FocusRequester? = null,
    contentFocusRequester: FocusRequester,
    onOpenRail: () -> Unit,
    railFocused: Boolean,
    backToTopTick: Int,
    onActiveSectionChange: (RailDestination) -> Unit,
    scrollToSectionRequest: Pair<RailDestination, Int>?,
    onScrollToSectionConsumed: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(key = "home_unified"),
) {
    LaunchedEffect(Unit) { viewModel.loadUnified() }
    val state by viewModel.unifiedState.collectAsStateWithLifecycle()

    when (val s = state) {
        UnifiedHomeUiState.Loading -> HomeSkeleton(modifier = Modifier.fillMaxSize())
        is UnifiedHomeUiState.Error -> Box(Modifier.fillMaxSize().background(RowBackground)) {
            HomeError(
                s.message,
                onRetry = { viewModel.loadUnified(force = true) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        is UnifiedHomeUiState.Content -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = !railFocused },
            ) {
                RowsBrowser(
                    sectionedRows = s.sections,
                    onItemClick = { item ->
                        viewModel.cancelBackgroundPrefetch()
                        onItemClick(item)
                    },
                    onContinueClick = { item, info ->
                        viewModel.cancelBackgroundPrefetch()
                        onContinueClick(item, info)
                    },
                    onNewEpisodeClick = { item, episode ->
                        viewModel.cancelBackgroundPrefetch()
                        onNewEpisodeClick(item, episode)
                    },
                    onUpNextClick = { item, episode ->
                        viewModel.cancelBackgroundPrefetch()
                        onUpNextClick(item, episode)
                    },
                    onRemoveContinue = viewModel::removeFromContinue,
                    onRemoveMyList = viewModel::removeFromMyList,
                    onMarkUpNextWatched = viewModel::markUpNextWatched,
                    onRemoveUpNext = viewModel::removeUpNext,
                    onExitTop = null,
                    onBack = onOpenRail,
                    backToTopTick = backToTopTick,
                    onBackToTop = onBackToTop,
                    onLoadMoreRow = { filter, rowId ->
                        if (filter != null) viewModel.loadMoreSectionRow(filter, rowId)
                    },
                    startPadding = ContentStart,
                    railRequester = railRequester,
                    contentFocusRequester = contentFocusRequester,
                    onOpenRail = onOpenRail,
                    railFocused = railFocused,
                    onActiveSectionChange = onActiveSectionChange,
                    scrollToSectionRequest = scrollToSectionRequest,
                    onScrollToSectionConsumed = onScrollToSectionConsumed,
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    filter: HomeFilter,
    onItemClick: (CatalogItem) -> Unit,
    onContinueClick: (CatalogItem, ContinueInfo) -> Unit,
    onNewEpisodeClick: (CatalogItem, Episode) -> Unit,
    onUpNextClick: (CatalogItem, Episode) -> Unit,
    onPlaySomething: (CatalogItem, EpisodeTarget?) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onContentReady: () -> Unit = {},
    topBar: @Composable (Modifier) -> Unit,
    topBarRequester: FocusRequester,
    focusTopBar: () -> Unit,
    preferTopBarFocus: Boolean,
    onTopBarFocusConsumed: () -> Unit,
    immersive: Boolean = false,
    topBarFocused: Boolean = false,
    backToTopTick: Int = 0,
    onExitTop: () -> Unit = {},
    onBackToTop: () -> Unit = {},
    onEnterImmersive: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(key = "home_${filter.name}"),
) {
    LaunchedEffect(filter) { viewModel.load(filter) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hiddenCategories.collectAsStateWithLifecycle()

    when (val s = state) {
        HomeUiState.Loading -> Column(Modifier.fillMaxSize().background(RowBackground)) {
            topBar(Modifier)
            LaunchedEffect(Unit) {
                if (preferTopBarFocus) {
                    withFrameNanos { }
                    focusTopBar()
                }
            }
            HomeSkeleton(modifier = Modifier.fillMaxWidth().weight(1f))
        }
        is HomeUiState.Error -> Column(Modifier.fillMaxSize().background(BillboardBackground)) {
            topBar(Modifier)
            HomeError(
                s.message,
                onRetry = { viewModel.load(filter, force = true) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        is HomeUiState.Content -> {
            LaunchedEffect(Unit) { onContentReady() }
            HomeContent(
            rows = s.rows,
            featured = s.featured,
            immersive = immersive,
            topBarFocused = topBarFocused,
            backToTopTick = backToTopTick,
            onExitTop = onExitTop,
            onBackToTop = onBackToTop,
            onEnterImmersive = onEnterImmersive,
            onLoadMoreRow = viewModel::loadMoreRow,
            categories = remember(filter, hidden) {
                when {
                    viewModel.kidsProfile -> Category.forFilter(filter).filter { it in Category.KIDS_SAFE }
                    else -> Category.forFilter(filter).filter { it.name !in hidden }.ifEmpty { Category.forFilter(filter) }
                }
            },
            onItemClick = { item ->
                viewModel.cancelBackgroundPrefetch()
                onItemClick(item)
            },
            onContinueClick = { item, info ->
                viewModel.cancelBackgroundPrefetch()
                onContinueClick(item, info)
            },
            onNewEpisodeClick = { item, episode ->
                viewModel.cancelBackgroundPrefetch()
                onNewEpisodeClick(item, episode)
            },
            onUpNextClick = { item, episode ->
                viewModel.cancelBackgroundPrefetch()
                onUpNextClick(item, episode)
            },
            onPlaySomething = { item, target ->
                viewModel.cancelBackgroundPrefetch()
                onPlaySomething(item, target)
            },
            onOpenCategory = onOpenCategory,
            onRemoveContinue = viewModel::removeFromContinue,
            onRemoveMyList = viewModel::removeFromMyList,
            onMarkUpNextWatched = viewModel::markUpNextWatched,
            onRemoveUpNext = viewModel::removeUpNext,
            topBar = topBar,
            topBarRequester = topBarRequester,
            focusTopBar = focusTopBar,
            preferTopBarFocus = preferTopBarFocus,
            onTopBarFocusConsumed = onTopBarFocusConsumed,
            )
        }
    }
}

internal enum class TopFocus { HERO, CHIPS }

internal data class ItemMenu(val row: CatalogRow, val virtual: Int, val item: CatalogItem)

internal data class UpNextMenu(val row: CatalogRow, val virtual: Int, val item: CatalogItem, val episode: Episode)

internal data class PendingFocus(val rowId: String, val itemKey: String?, val fallbackKey: String)

@Composable
private fun HomeContent(
    rows: List<CatalogRow>,
    featured: List<FeaturedItem>,
    categories: List<Category>,
    onItemClick: (CatalogItem) -> Unit,
    onContinueClick: (CatalogItem, ContinueInfo) -> Unit,
    onNewEpisodeClick: (CatalogItem, Episode) -> Unit,
    onUpNextClick: (CatalogItem, Episode) -> Unit,
    onPlaySomething: (CatalogItem, EpisodeTarget?) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onRemoveContinue: (CatalogItem) -> Unit,
    onRemoveMyList: (CatalogItem) -> Unit,
    onMarkUpNextWatched: (CatalogItem, Episode) -> Unit,
    onRemoveUpNext: (CatalogItem, Episode) -> Unit,
    topBar: @Composable (Modifier) -> Unit,
    topBarRequester: FocusRequester,
    focusTopBar: () -> Unit,
    preferTopBarFocus: Boolean,
    onTopBarFocusConsumed: () -> Unit,
    immersive: Boolean = false,
    topBarFocused: Boolean = false,
    backToTopTick: Int = 0,
    onExitTop: () -> Unit = {},
    onBackToTop: () -> Unit = {},
    onEnterImmersive: () -> Unit = {},
    onLoadMoreRow: (String) -> Unit = {},
) {
    // Como en Bambi: el Inicio arranca en la portada (destacados + categorías); ▼ baja a las filas.
    var inRows by rememberSaveable { mutableStateOf(true) }
    var topFocus by rememberSaveable { mutableStateOf(TopFocus.HERO) }
    val saveable = rememberSaveableStateHolder()
    // Swapping the rows view and the billboard disposes the focused item, and the new view only
    // requests focus a frame later. Park focus on this holder meanwhile; otherwise it falls to the
    // top bar and the screen jumps.
    var parkingFocus by remember { mutableStateOf(false) }
    val parkingRequester = remember { FocusRequester() }
    fun switchView(change: () -> Unit) {
        parkingFocus = true
        change()
    }
    LaunchedEffect(parkingFocus) {
        if (parkingFocus) runCatching { parkingRequester.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(parkingRequester)
            .onFocusChanged { if (it.hasFocus && !it.isFocused) parkingFocus = false }
            .focusProperties { canFocus = parkingFocus }
            .focusTarget(),
    ) {
        // Modified build: no banner page. Home always shows content rows with a focus-following
        // hero backdrop and a bottom fade (full-size next row peeks from behind).
        if (rows.isNotEmpty()) saveable.SaveableStateProvider("rows") {
            RowsBrowser(
                rows = rows,
                onItemClick = onItemClick,
                onContinueClick = onContinueClick,
                onNewEpisodeClick = onNewEpisodeClick,
                onUpNextClick = onUpNextClick,
                onRemoveContinue = onRemoveContinue,
                onRemoveMyList = onRemoveMyList,
                onMarkUpNextWatched = onMarkUpNextWatched,
                onRemoveUpNext = onRemoveUpNext,
                onExitTop = onExitTop,
                onBack = onBackToTop,
                topBar = if (immersive) { _ -> } else topBar,
                topBarFocused = topBarFocused,
                backToTopTick = backToTopTick,
                onBackToTop = onBackToTop,
                onEnterImmersive = onEnterImmersive,
                onLoadMoreRow = onLoadMoreRow,
            )
        }
    }
}
