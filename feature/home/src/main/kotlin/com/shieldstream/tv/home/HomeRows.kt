package com.shieldstream.tv.home

import androidx.compose.ui.text.style.TextOverflow
import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.Lang
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import com.shieldstream.tv.ui.component.tvClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import com.shieldstream.tv.ui.theme.DisplayFont
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.CatalogRow
import com.shieldstream.tv.model.ContinueInfo
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.strings.localizedCode
import com.shieldstream.tv.model.RowStyle
import com.shieldstream.tv.ui.component.ActionMenuDialog
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.ContinueCard
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.NewEpisodeCard
import com.shieldstream.tv.ui.component.CatalogPosterCard
import com.shieldstream.tv.ui.component.ProvidePivotScroll
import com.shieldstream.tv.ui.component.RailDestination
import com.shieldstream.tv.ui.component.Top10Card
import com.shieldstream.tv.ui.component.LabeledPosterCard
import com.shieldstream.tv.ui.component.firstVirtualIndex
import com.shieldstream.tv.ui.component.formatEpisodeDate
import com.shieldstream.tv.ui.component.loopingItems
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.rememberLoopingRowState
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.component.shouldLoop
import com.shieldstream.tv.ui.theme.LocalLightweightUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot

// Inicio → filas de pósteres, menús de cada tarjeta y el foco al moverse entre filas.

@Composable
internal fun RowsBrowser(
    rows: List<CatalogRow>,
    onItemClick: (CatalogItem) -> Unit,
    onRemoveContinue: (CatalogItem) -> Unit,
    onRemoveMyList: (CatalogItem) -> Unit,
    onExitTop: (() -> Unit)?,
    onNewEpisodeClick: ((CatalogItem, Episode) -> Unit)? = null,
    onContinueClick: ((CatalogItem, ContinueInfo) -> Unit)? = null,
    onUpNextClick: ((CatalogItem, Episode) -> Unit)? = null,
    onMarkUpNextWatched: ((CatalogItem, Episode) -> Unit)? = null,
    onRemoveUpNext: ((CatalogItem, Episode) -> Unit)? = null,
    onBack: () -> Unit,
    header: String? = null,
    topBar: @Composable (Modifier) -> Unit = {},
    topBarFocused: Boolean = false,
    backToTopTick: Int = 0,
    onBackToTop: () -> Unit = {},
    onEnterImmersive: () -> Unit = {},
    onLoadMoreRow: ((String) -> Unit)? = null,
) = RowsBrowser(
    sectionedRows = rows.map { SectionedRow(RailDestination.HOME, null, it) },
    onItemClick = onItemClick,
    onRemoveContinue = onRemoveContinue,
    onRemoveMyList = onRemoveMyList,
    onExitTop = onExitTop,
    onNewEpisodeClick = onNewEpisodeClick,
    onContinueClick = onContinueClick,
    onUpNextClick = onUpNextClick,
    onMarkUpNextWatched = onMarkUpNextWatched,
    onRemoveUpNext = onRemoveUpNext,
    onBack = onBack,
    header = header,
    topBar = topBar,
    topBarFocused = topBarFocused,
    backToTopTick = backToTopTick,
    onBackToTop = onBackToTop,
    onEnterImmersive = onEnterImmersive,
    onLoadMoreRow = onLoadMoreRow?.let { fn -> { _, rowId -> fn(rowId) } },
)

@Composable
internal fun RowsBrowser(
    sectionedRows: List<SectionedRow>,
    onItemClick: (CatalogItem) -> Unit,
    onRemoveContinue: (CatalogItem) -> Unit,
    onRemoveMyList: (CatalogItem) -> Unit,
    onExitTop: (() -> Unit)?,
    onNewEpisodeClick: ((CatalogItem, Episode) -> Unit)? = null,
    onContinueClick: ((CatalogItem, ContinueInfo) -> Unit)? = null,
    onUpNextClick: ((CatalogItem, Episode) -> Unit)? = null,
    onMarkUpNextWatched: ((CatalogItem, Episode) -> Unit)? = null,
    onRemoveUpNext: ((CatalogItem, Episode) -> Unit)? = null,
    onBack: () -> Unit,
    header: String? = null,
    topBar: @Composable (Modifier) -> Unit = {},
    topBarFocused: Boolean = false,
    backToTopTick: Int = 0,
    onBackToTop: () -> Unit = {},
    onEnterImmersive: () -> Unit = {},
    onLoadMoreRow: ((HomeFilter?, String) -> Unit)? = null,
    startPadding: Dp = ContentStart,
    railRequester: FocusRequester? = null,
    contentFocusRequester: FocusRequester? = null,
    onOpenRail: (() -> Unit)? = null,
    railFocused: Boolean = false,
    onActiveSectionChange: (RailDestination) -> Unit = {},
    scrollToSectionRequest: Pair<RailDestination, Int>? = null,
    onScrollToSectionConsumed: () -> Unit = {},
    activeSectionFilters: Map<HomeFilter, CategoryFilterSelection> = emptyMap(),
    loadingSectionFilters: Set<HomeFilter> = emptySet(),
    sectionFilterPillRequesters: Map<RailDestination, FocusRequester> = emptyMap(),
    onOpenSectionCategoryPicker: ((RailDestination) -> Unit)? = null,
    onClearSectionCategoryFilter: ((RailDestination) -> Unit)? = null,
) {
    val rows = remember(sectionedRows) { sectionedRows.map { it.row } }
    fun loopsFor(row: CatalogRow): Boolean =
        shouldLoop(row.items.size) &&
            row.style != RowStyle.TOP10 &&
            railRequester == null &&
            onOpenRail == null &&
            !(onLoadMoreRow != null && row.style == RowStyle.POSTER)

    val savedFocus = rememberSaveable { mutableStateOf(firstKey(rows.first(), loopsFor(rows.first()))) }
    fun validKey(key: String) = if (findByKey(rows, key) != null) key else firstKey(rows.first(), loopsFor(rows.first()))
    var restoreKey by remember { mutableStateOf(Snapshot.withoutReadObservation { validKey(savedFocus.value) }) }
    var lastVerticalRepeat by remember { mutableStateOf(0L) }
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }
    var continueMenu by remember { mutableStateOf<ItemMenu?>(null) }
    var myListMenu by remember { mutableStateOf<ItemMenu?>(null) }
    var upNextMenu by remember { mutableStateOf<UpNextMenu?>(null) }
    var topFilterBarFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focusedSection by remember {
        mutableStateOf(
            Snapshot.withoutReadObservation {
                val initRowId = validKey(restoreKey).substringBeforeLast('/')
                sectionedRows.firstOrNull { it.row.id == initRowId }?.section ?: sectionedRows.first().section
            },
        )
    }

    val heroState = remember {
        MutableStateFlow(Snapshot.withoutReadObservation { findByKey(rows, restoreKey) ?: rows.first().items.first() })
    }
    LaunchedEffect(rows) {
        snapshotFlow { savedFocus.value }.collectLatest { key ->
            val target = findByKey(rows, validKey(key)) ?: return@collectLatest
            // Debounce above VERTICAL_REPEAT_MS (130ms) so holding Up/Down skips intermediate hero backdrop decodes.
            delay(180)
            heroState.value = target
        }
    }

    val restoreRequester = contentFocusRequester ?: remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val currentRow = findByKey(rows, validKey(savedFocus.value))
        if (currentRow != null) {
            val section = sectionedRows.firstOrNull { entry ->
                entry.row.id == validKey(savedFocus.value).substringBeforeLast('/')
            }?.section ?: sectionedRows.first().section
            focusedSection = section
            onActiveSectionChange(section)
        }
        if (!railFocused) restoreRequester.requestFocusWithRetry()
    }
    LaunchedEffect(backToTopTick) {
        if (backToTopTick > 0 && rows.isNotEmpty()) {
            val topKey = firstKey(rows.first(), loopsFor(rows.first()))
            savedFocus.value = topKey
            restoreKey = topKey
            heroState.value = rows.first().items.first()
            focusedSection = sectionedRows.first().section
            onActiveSectionChange(sectionedRows.first().section)
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(sectionedRows, scrollToSectionRequest) {
        val request = scrollToSectionRequest ?: return@LaunchedEffect
        val targetIndex = sectionedRows.indexOfFirst { it.section == request.first }
            .takeIf { it >= 0 }
            ?: if (request.first == RailDestination.HOME && sectionedRows.isNotEmpty()) 0 else -1
        if (targetIndex < 0) return@LaunchedEffect
        val targetEntry = sectionedRows[targetIndex]
        val topKey = firstKey(targetEntry.row, loopsFor(targetEntry.row))
        savedFocus.value = topKey
        restoreKey = topKey
        targetEntry.row.items.firstOrNull()?.let { heroState.value = it }
        focusedSection = targetEntry.section
        onActiveSectionChange(targetEntry.section)
        listState.scrollToItem(targetIndex)
        withFrameNanos { }
        restoreRequester.requestFocusWithRetry()
        onScrollToSectionConsumed()
    }
    LaunchedEffect(rows) {
        val pending = pendingFocus ?: return@LaunchedEffect
        restoreKey = pending.itemKey
            ?.let { itemKey -> focusKeyForItem(rows, pending.rowId, itemKey) }
            ?: pending.fallbackKey
        savedFocus.value = restoreKey
        restoreRequester.requestFocusWithRetry()
        pendingFocus = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(RowBackground)
            .onPreviewKeyEvent { e ->
                val vertical = e.key == Key.DirectionDown || e.key == Key.DirectionUp
                if (e.type != KeyEventType.KeyDown || !vertical || e.nativeKeyEvent.repeatCount == 0) return@onPreviewKeyEvent false
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastVerticalRepeat < com.shieldstream.tv.ui.component.VERTICAL_REPEAT_MS) true else { lastVerticalRepeat = now; false }
            },
    ) {
        if (!LocalLightweightUi.current) {
            Box(Modifier.fillMaxSize().graphicsLayer {}) {
                HeroBackdropSlot(heroState)
            }
        }
        Column(Modifier.fillMaxSize()) {
            topBar(
                Modifier.onPreviewKeyEvent { event ->
                    if (topBarFocused && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && rows.isNotEmpty()) {
                        scope.launch {
                            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                                listState.scrollToItem(0)
                            }
                            restoreRequester.requestFocusWithRetry()
                        }
                        true
                    } else {
                        false
                    }
                },
            )
            val fallbackPillRequester = remember { FocusRequester() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(194.dp)
                    .padding(start = startPadding, top = 14.dp, end = 360.dp),
            ) {
                val showTopFilterBar = header == null &&
                    onOpenSectionCategoryPicker != null &&
                    (focusedSection == RailDestination.TV || focusedSection == RailDestination.MOVIES)
                if (showTopFilterBar) {
                    val activeSection = focusedSection
                    val homeFilter = CategoryFilterCatalog.filterForSection(activeSection)
                    val activeSelection = homeFilter?.let { activeSectionFilters[it] } ?: CategoryFilterSelection()
                    val isLoading = homeFilter != null && homeFilter in loadingSectionFilters
                    val pillRequester = sectionFilterPillRequesters[activeSection] ?: fallbackPillRequester
                    val jumpToRow: (Int) -> Unit = { idx ->
                        val targetEntry = sectionedRows.getOrNull(idx)
                        if (targetEntry != null) {
                            val key = firstKey(targetEntry.row, loopsFor(targetEntry.row))
                            savedFocus.value = key
                            restoreKey = key
                            targetEntry.row.items.firstOrNull()?.let { heroState.value = it }
                            focusedSection = targetEntry.section
                            onActiveSectionChange(targetEntry.section)
                            scope.launch {
                                listState.scrollToItem(idx)
                                withFrameNanos { }
                                restoreRequester.requestFocusWithRetry()
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .onFocusChanged { topFilterBarFocused = it.hasFocus }
                            .onBackKey(enabled = topFilterBarFocused && !railFocused) {
                                val sectionStart = sectionedRows.indexOfFirst { it.section == activeSection }
                                if (sectionStart >= 0) jumpToRow(sectionStart)
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val sectionStart = sectionedRows.indexOfFirst { it.section == activeSection }
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (sectionStart >= 0) jumpToRow(sectionStart)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        val prevIdx = (sectionStart - 1).coerceAtLeast(0)
                                        if (sectionStart > 0) jumpToRow(prevIdx)
                                        true
                                    }
                                    else -> false
                                }
                            },
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(18.dp)
                                .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = sectionLabel(activeSection).orEmpty(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                        Spacer(Modifier.width(16.dp))
                        val pillSummary = if (activeSelection.isActive) {
                            formatFilterSelectionSummary(activeSelection)
                        } else {
                            localizedStringResource(StringsR.string.home_filter_all_categories)
                        }
                        SectionCategoryPill(
                            label = pillSummary,
                            active = activeSelection.isActive,
                            onClick = { onOpenSectionCategoryPicker.invoke(activeSection) },
                            onLeftAtEdge = onOpenRail,
                            modifier = Modifier.focusRequester(pillRequester),
                        )
                        if (activeSelection.isActive && onClearSectionCategoryFilter != null) {
                            Spacer(Modifier.width(10.dp))
                            SectionClearFilterPill(
                                label = localizedStringResource(StringsR.string.home_filter_clear),
                                onClick = { onClearSectionCategoryFilter(activeSection) },
                            )
                        }
                        if (isLoading) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AppColors.Accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                } else {
                    LaunchedEffect(Unit) { topFilterBarFocused = false }
                }
                Column(
                    Modifier.align(Alignment.BottomStart),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    header?.let {
                        Text(
                            text = it.uppercase(Lang.locale),
                            fontFamily = DisplayFont,
                            fontSize = 30.sp,
                            color = AppColors.Accent,
                        )
                    }
                    HeroInfoSlot(heroState)
                }
            }
            ProvidePivotScroll(leading = 51.dp) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().graphicsLayer {},
                    contentPadding = PaddingValues(bottom = 260.dp),
                ) {
                    itemsIndexed(sectionedRows, key = { _, entry -> "${entry.section.name}:${entry.row.id}" }, contentType = { _, entry -> entry.row.style }) { rowIndex, entry ->
                        val row = entry.row
                        val isSectionStart = entry.section != RailDestination.HOME &&
                            (rowIndex == 0 || sectionedRows[rowIndex - 1].section != entry.section)
                        val sectionHeaderLabel = if (isSectionStart) sectionLabel(entry.section) else null
                        val filterableSectionStart = isSectionStart &&
                            onOpenSectionCategoryPicker != null &&
                            (entry.section == RailDestination.TV || entry.section == RailDestination.MOVIES)
                        val sectionPillRequester = if (filterableSectionStart) sectionFilterPillRequesters[entry.section] else null
                        val rowLoops = loopsFor(row)
                        CatalogRowView(
                            row = row,
                            sectionLabel = sectionHeaderLabel,
                            sectionFilterPillRequester = sectionPillRequester,
                            startPadding = startPadding,
                            loops = rowLoops,
                            focusKey = restoreKey,
                            restoreRequester = restoreRequester,
                            railRequester = railRequester,
                            onOpenRail = onOpenRail,
                            resetScrollTick = if (rowIndex == 0) backToTopTick else 0,
                            onLoadMore = if (row.style == RowStyle.POSTER && onLoadMoreRow != null) {
                                { rowId -> onLoadMoreRow(entry.filter, rowId) }
                            } else {
                                null
                            },
                            onFocused = { virtual ->
                                savedFocus.value = keyOf(row, virtual)
                                focusedSection = entry.section
                                onActiveSectionChange(entry.section)
                                onEnterImmersive()
                            },
                            onItemClick = onItemClick,
                            onNewEpisodeClick = onNewEpisodeClick,
                            onContinueClick = onContinueClick,
                            onUpNextClick = onUpNextClick,
                            onContinueMenu = { virtual, item -> continueMenu = ItemMenu(row, virtual, item) },
                            onMyListMenu = { virtual, item -> myListMenu = ItemMenu(row, virtual, item) },
                            onUpNextMenu = { virtual, item, episode -> upNextMenu = UpNextMenu(row, virtual, item, episode) },
                            modifier = if (rowIndex == 0 && onExitTop != null) {
                                Modifier.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                        onExitTop()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black),
                    ),
                ),
        )
    }

    continueMenu?.let { menu ->
        ContinueActions(
            item = menu.item,
            onDetails = {
                continueMenu = null
                onItemClick(menu.item)
            },
            onRemove = {
                continueMenu = null
                pendingFocus = PendingFocus(menu.row.id, itemKey = null, fallbackKey = neighbourKey(rows, menu.row, menu.virtual, loopsFor(menu.row)))
                onRemoveContinue(menu.item)
            },
            onCancel = { continueMenu = null },
        )
    }

    myListMenu?.let { menu ->
        ActionMenuDialog(
            title = menu.item.title,
            onDismiss = { myListMenu = null },
            consumeOpeningKeyRelease = true,
            actions = listOf(
                ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close, { myListMenu = null }),
                ActionMenuItem(localizedStringResource(StringsR.string.home_remove), LineIcons.Delete) {
                    myListMenu = null
                    pendingFocus = PendingFocus(menu.row.id, itemKey = null, fallbackKey = neighbourKey(rows, menu.row, menu.virtual, loopsFor(menu.row)))
                    onRemoveMyList(menu.item)
                },
            ),
        )
    }

    upNextMenu?.let { menu ->
        UpNextActions(
            item = menu.item,
            onDetails = {
                upNextMenu = null
                onItemClick(menu.item)
            },
            onMarkWatched = {
                upNextMenu = null
                pendingFocus = PendingFocus(menu.row.id, itemKey = null, fallbackKey = neighbourKey(rows, menu.row, menu.virtual, loopsFor(menu.row)))
                onMarkUpNextWatched?.invoke(menu.item, menu.episode)
            },
            onRemove = {
                upNextMenu = null
                pendingFocus = PendingFocus(menu.row.id, itemKey = null, fallbackKey = neighbourKey(rows, menu.row, menu.virtual, loopsFor(menu.row)))
                onRemoveUpNext?.invoke(menu.item, menu.episode)
            },
            onCancel = { upNextMenu = null },
        )
    }
}

@Composable
private fun sectionLabel(section: RailDestination): String? = when (section) {
    RailDestination.TV -> localizedStringResource(StringsR.string.common_tv_shows)
    RailDestination.MOVIES -> localizedStringResource(StringsR.string.common_movies)
    RailDestination.ANIME -> localizedStringResource(StringsR.string.common_anime)
    else -> null
}

@Composable
private fun SectionCategoryPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLeftAtEdge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        active -> Color(0x4DE50914)
        else -> Color(0xCC1C1C1C)
    }
    val border = when {
        focused -> Color.White
        active -> AppColors.Accent
        else -> Color.White.copy(alpha = 0.28f)
    }
    val textColor = if (focused) Color.Black else Color.White
    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = if (focused) 1.05f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (onLeftAtEdge != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onLeftAtEdge()
                    true
                } else {
                    false
                }
            }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = "$label  ▾",
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (focused || active) FontWeight.Bold else FontWeight.Medium,
            ),
            modifier = Modifier.widthIn(max = 440.dp),
        )
    }
}

@Composable
private fun SectionClearFilterPill(
    label: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .graphicsLayer {
                val scale = if (focused) 1.05f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x991C1C1C), CircleShape)
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.22f), CircleShape)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = "✕  $label",
            color = if (focused) Color.Black else Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun HeroBackdropSlot(heroState: StateFlow<CatalogItem>) {
    val hero by heroState.collectAsState()
    HeroBackdrop(hero)
}

@Composable
private fun HeroInfoSlot(heroState: StateFlow<CatalogItem>) {
    val hero by heroState.collectAsState()
    HeroInfo(item = hero)
}

@Composable
private fun ContinueActions(
    item: CatalogItem,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) = ActionMenuDialog(
    title = item.title,
    onDismiss = onCancel,
    consumeOpeningKeyRelease = true,
    actions = listOf(
        ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close, onCancel),
        ActionMenuItem(localizedStringResource(StringsR.string.home_view_details), AppIcons.List, onDetails),
        ActionMenuItem(localizedStringResource(StringsR.string.home_remove), LineIcons.Delete, onRemove),
    ),
)

@Composable
private fun UpNextActions(
    item: CatalogItem,
    onDetails: () -> Unit,
    onMarkWatched: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) = ActionMenuDialog(
    title = item.title,
    onDismiss = onCancel,
    consumeOpeningKeyRelease = true,
    actions = listOf(
        ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close, onCancel),
        ActionMenuItem(localizedStringResource(StringsR.string.home_view_details), AppIcons.List, onDetails),
        ActionMenuItem(localizedStringResource(StringsR.string.home_mark_watched), LineIcons.Eye, onMarkWatched),
        ActionMenuItem(localizedStringResource(StringsR.string.home_remove), LineIcons.Delete, onRemove),
    ),
)

@Composable
private fun CatalogRowView(
    row: CatalogRow,
    sectionLabel: String? = null,
    sectionFilterPillRequester: FocusRequester? = null,
    startPadding: Dp = ContentStart,
    loops: Boolean = false,
    focusKey: String?,
    restoreRequester: FocusRequester,
    railRequester: FocusRequester? = null,
    onOpenRail: (() -> Unit)? = null,
    resetScrollTick: Int = 0,
    onLoadMore: ((String) -> Unit)? = null,
    onFocused: (Int) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onNewEpisodeClick: ((CatalogItem, Episode) -> Unit)?,
    onContinueClick: ((CatalogItem, ContinueInfo) -> Unit)?,
    onUpNextClick: ((CatalogItem, Episode) -> Unit)?,
    onContinueMenu: (Int, CatalogItem) -> Unit,
    onMyListMenu: (Int, CatalogItem) -> Unit,
    onUpNextMenu: (Int, CatalogItem, Episode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (sectionLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = startPadding, end = 48.dp, top = 18.dp, bottom = 6.dp),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = sectionLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                            ),
                        ),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(start = startPadding, top = 4.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            if (row.style == RowStyle.CONTINUE || row.style == RowStyle.UP_NEXT || row.style == RowStyle.MY_LIST) {
                Text(
                    text = "   " + localizedStringResource(StringsR.string.home_hold_ok_for_options),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }
        val expandable = onLoadMore != null && row.style == RowStyle.POSTER
        key(loops) {
            ProvidePivotScroll(leading = startPadding + 6.dp) {
                val rowState = rememberLoopingRowState(if (loops) row.items.size else 0)
                LaunchedEffect(resetScrollTick) {
                    if (resetScrollTick > 0) {
                        rowState.scrollToItem(if (loops) firstVirtualIndex(row.items.size) else 0)
                    }
                }
                if (expandable) {
                    LaunchedEffect(rowState, row.items.size) {
                        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                            .collectLatest { lastVisible ->
                                if (lastVisible >= row.items.lastIndex - 4 && row.items.isNotEmpty()) {
                                    onLoadMore?.invoke(row.id)
                                }
                            }
                    }
                }
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = startPadding, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (row.style == RowStyle.TOP10) 6.dp else 12.dp),
                ) {
                    if (loops) {
                        loopingItems(row.items) { virtual, index, item ->
                            val itemModifier = if (keyOf(row, virtual) == focusKey) {
                                Modifier.focusRequester(restoreRequester)
                            } else {
                                Modifier
                            }
                            RowItemCard(
                                row = row,
                                index = index,
                                virtual = virtual,
                                item = item,
                                modifier = itemModifier,
                                onFocused = { onFocused(virtual) },
                                onItemClick = onItemClick,
                                onNewEpisodeClick = onNewEpisodeClick,
                                onContinueClick = onContinueClick,
                                onUpNextClick = onUpNextClick,
                                onContinueMenu = { onContinueMenu(virtual, item) },
                                onMyListMenu = { onMyListMenu(virtual, item) },
                                onUpNextMenu = { episode -> onUpNextMenu(virtual, item, episode) },
                            )
                        }
                    } else {
                        itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                            val focusMod = if (keyOf(row, index) == focusKey) {
                                Modifier.focusRequester(restoreRequester)
                            } else {
                                Modifier
                            }
                            val edgeMod = when {
                                index == 0 && onOpenRail != null -> focusMod.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                        onOpenRail()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                index == 0 && railRequester != null -> focusMod.focusProperties { left = railRequester }
                                else -> focusMod
                            }.let { baseMod ->
                                if (sectionFilterPillRequester != null) {
                                    baseMod.focusProperties { up = sectionFilterPillRequester }
                                } else {
                                    baseMod
                                }
                            }
                            RowItemCard(
                                row = row,
                                index = index,
                                virtual = index,
                                item = item,
                                modifier = edgeMod,
                                onFocused = {
                                    onFocused(index)
                                    if (expandable && index >= row.items.lastIndex - 4) {
                                        onLoadMore?.invoke(row.id)
                                    }
                                },
                                onItemClick = onItemClick,
                                onNewEpisodeClick = onNewEpisodeClick,
                                onContinueClick = onContinueClick,
                                onUpNextClick = onUpNextClick,
                                onContinueMenu = { onContinueMenu(index, item) },
                                onMyListMenu = { onMyListMenu(index, item) },
                                onUpNextMenu = { episode -> onUpNextMenu(index, item, episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowItemCard(
    row: CatalogRow,
    index: Int,
    virtual: Int,
    item: CatalogItem,
    modifier: Modifier,
    onFocused: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onNewEpisodeClick: ((CatalogItem, Episode) -> Unit)?,
    onContinueClick: ((CatalogItem, ContinueInfo) -> Unit)?,
    onUpNextClick: ((CatalogItem, Episode) -> Unit)?,
    onContinueMenu: () -> Unit,
    onMyListMenu: () -> Unit,
    onUpNextMenu: (Episode) -> Unit,
) {
    when (row.style) {
        RowStyle.POSTER -> CatalogPosterCard(
            item = item,
            onClick = { onItemClick(item) },
            onFocused = onFocused,
            newEpisodeCount = row.newEpisodeCounts[item.key] ?: 0,
            modifier = modifier,
        )
        RowStyle.MY_LIST -> CatalogPosterCard(
            item = item,
            onClick = { onItemClick(item) },
            onFocused = onFocused,
            onLongClick = onMyListMenu,
            newEpisodeCount = row.newEpisodeCounts[item.key] ?: 0,
            modifier = modifier,
        )
        RowStyle.CONTINUE -> {
            val info = row.progress[item.key]
            ContinueCard(
                item = item,
                fraction = info?.fraction ?: 0f,
                label = info?.label.orEmpty(),
                onClick = {
                    if (info == null || onContinueClick == null) onItemClick(item)
                    else onContinueClick(item, info)
                },
                onLongClick = onContinueMenu,
                onFocused = onFocused,
                newEpisodeCount = row.newEpisodeCounts[item.key] ?: 0,
                modifier = modifier,
            )
        }
        RowStyle.NEW_EPISODES -> {
            val episode = row.newEpisodes[item.key]
            val episodeCode = episode?.localizedCode()
            val episodeLabel = remember(row.newEpisodes, item.key, episodeCode) {
                episode?.let {
                    listOf(episodeCode.orEmpty(), formatEpisodeDate(it.airDate)).filter { part -> part.isNotEmpty() }.joinToString(" · ")
                }.orEmpty()
            }
            NewEpisodeCard(
                item = item,
                label = episodeLabel,
                count = row.newEpisodeCounts[item.key] ?: 1,
                onClick = {
                    if (episode == null || onNewEpisodeClick == null) onItemClick(item)
                    else onNewEpisodeClick(item, episode)
                },
                onFocused = onFocused,
                modifier = modifier,
            )
        }
        RowStyle.UP_NEXT -> {
            val info = row.upNext[item.key]
            val episode = info?.episode
            val episodeCode = episode?.localizedCode()
            val episodeLabel = remember(row.upNext, item.key, episodeCode) {
                episode?.let {
                    listOf(episodeCode.orEmpty(), it.title).filter { part -> part.isNotEmpty() }.joinToString(" · ")
                }.orEmpty()
            }
            val releaseLabel = remember(row.upNext, item.key) {
                formatEpisodeDate(episode?.airDate)
            }
            val badgeText = when (upNextBadge(info)) {
                UpNextBadge.NEW_SEASON -> localizedStringResource(StringsR.string.home_new_season)
                UpNextBadge.NEW_EPISODE -> localizedStringResource(StringsR.string.home_new_episode)
                UpNextBadge.UP_NEXT -> localizedStringResource(StringsR.string.home_up_next)
            }
            if (episode == null) {
                CatalogPosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onFocused = onFocused,
                    modifier = modifier,
                )
            } else {
                LabeledPosterCard(
                    item = item,
                    badge = badgeText,
                    badgeDot = upNextBadge(info) != UpNextBadge.UP_NEXT,
                    label = listOf(episodeLabel, releaseLabel).filter { it.isNotEmpty() }.joinToString("\n"),
                    onClick = {
                        if (onUpNextClick == null) onItemClick(item)
                        else onUpNextClick(item, episode)
                    },
                    onLongClick = { onUpNextMenu(episode) },
                    onFocused = onFocused,
                    modifier = modifier,
                )
            }
        }
        RowStyle.TOP10 -> Top10Card(
            rank = index + 1,
            item = item,
            onClick = { onItemClick(item) },
            onFocused = onFocused,
            newEpisodeCount = row.newEpisodeCounts[item.key] ?: 0,
            modifier = modifier,
        )
    }
}

private fun keyOf(row: CatalogRow, virtual: Int) = "${row.id}/$virtual"

private fun firstKey(row: CatalogRow, loops: Boolean = shouldLoop(row.items.size)) =
    keyOf(row, if (loops) firstVirtualIndex(row.items.size) else 0)

private fun findByKey(rows: List<CatalogRow>, key: String?): CatalogItem? {
    if (key == null) return null
    val row = rows.firstOrNull { it.id == key.substringBeforeLast('/') } ?: return null
    if (row.items.isEmpty()) return null
    val virtual = key.substringAfterLast('/').toIntOrNull() ?: return null
    if (!shouldLoop(row.items.size) && virtual >= row.items.size) return null
    return row.items[virtual % row.items.size]
}

internal fun focusKeyForItem(rows: List<CatalogRow>, rowId: String, itemKey: String): String? {
    val row = rows.firstOrNull { it.id == rowId } ?: return null
    val index = row.items.indexOfFirst { it.key == itemKey }
    if (index < 0) return null
    return keyOf(row, firstVirtualIndex(row.items.size) + index)
}

private fun neighbourKey(rows: List<CatalogRow>, row: CatalogRow, virtual: Int, loops: Boolean): String {
    val newSize = row.items.size - 1
    if (newSize <= 0) {
        val below = rows.getOrNull(rows.indexOf(row) + 1) ?: rows.first()
        return firstKey(below, loops = false)
    }
    val index = minOf(virtual % row.items.size, newSize - 1)
    return keyOf(row, if (loops && shouldLoop(newSize)) firstVirtualIndex(newSize) + index else index)
}
