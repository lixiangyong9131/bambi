package com.shieldstream.tv.home

import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.Lang
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
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
    myListKeys: Set<String> = emptySet(),
    onQuickPlay: ((CatalogItem) -> Unit)? = null,
    onToggleMyList: ((CatalogItem) -> Boolean)? = null,
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
    myListKeys = myListKeys,
    onQuickPlay = onQuickPlay,
    onToggleMyList = onToggleMyList,
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
    myListKeys: Set<String> = emptySet(),
    onQuickPlay: ((CatalogItem) -> Unit)? = null,
    onToggleMyList: ((CatalogItem) -> Boolean)? = null,
) {
    val rows = remember(sectionedRows) { sectionedRows.map { it.row } }
    fun loopsFor(row: CatalogRow): Boolean =
        shouldLoop(row.items.size) &&
            row.style != RowStyle.TOP10 &&
            railRequester == null &&
            onOpenRail == null &&
            !(onLoadMoreRow != null && row.style == RowStyle.POSTER)

    val savedFocus = rememberSaveable { mutableStateOf(firstKey(rows.first(), loopsFor(rows.first()))) }
    val savedRowSlots = rememberSaveable(
        saver = listSaver<MutableMap<String, Int>, String>(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value.toString()) } },
            restore = { saved ->
                mutableMapOf<String, Int>().apply {
                    for (i in 0 until saved.size - 1 step 2) {
                        saved[i + 1].toIntOrNull()?.let { slot -> put(saved[i], slot) }
                    }
                }
            },
        ),
    ) { mutableMapOf() }
    fun validKey(key: String) = if (findByKey(rows, key) != null) key else firstKey(rows.first(), loopsFor(rows.first()))
    var restoreKey by remember { mutableStateOf(Snapshot.withoutReadObservation { validKey(savedFocus.value) }) }
    var lastVerticalRepeat by remember { mutableStateOf(0L) }
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }
    var continueMenu by remember { mutableStateOf<ItemMenu?>(null) }
    var myListMenu by remember { mutableStateOf<ItemMenu?>(null) }
    var upNextMenu by remember { mutableStateOf<UpNextMenu?>(null) }
    var quickMenuItem by remember { mutableStateOf<CatalogItem?>(null) }
    var quickNoticeRes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(quickNoticeRes) {
        if (quickNoticeRes != null) {
            delay(2_400)
            quickNoticeRes = null
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHandles = remember { HashMap<String, RowFocusHandle>() }
    val verticalState = remember { VerticalFocusState() }
    var verticalParkingActive by remember { mutableStateOf(false) }
    val verticalParkingRequester = remember { FocusRequester() }
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
            // Debounce rapid D-pad sweeps so holding keys skips intermediate hero image loads.
            delay(220)
            heroState.value = target
        }
    }

    val restoreRequester = contentFocusRequester ?: remember { FocusRequester() }
    val syncRestoreKey = {
        val current = validKey(savedFocus.value)
        if (restoreKey != current) restoreKey = current
    }
    fun defaultSlotFor(row: CatalogRow, loops: Boolean): Int =
        if (loops) firstVirtualIndex(row.items.size) else 0

    fun resolveTargetSlot(entry: SectionedRow): Int {
        val row = entry.row
        if (row.items.isEmpty()) return 0
        val loops = loopsFor(row)
        val saved = savedRowSlots[rowStorageKey(entry)]
        return if (loops) {
            if (saved != null && saved >= 0) saved else firstVirtualIndex(row.items.size)
        } else {
            (saved ?: 0).coerceIn(0, row.items.lastIndex)
        }
    }

    suspend fun scrollAndFocusRow(
        targetRowIndex: Int,
        slotOverride: Int? = null,
        useRestoreRequester: Boolean = false,
    ): Boolean {
        val entry = sectionedRows.getOrNull(targetRowIndex) ?: return false
        val row = entry.row
        if (row.items.isEmpty()) return false
        val storageKey = rowStorageKey(entry)
        val rawSlot = slotOverride ?: resolveTargetSlot(entry)
        val clampedSlot = if (loopsFor(row)) rawSlot.coerceAtLeast(0) else rawSlot.coerceIn(0, row.items.lastIndex)
        savedRowSlots[storageKey] = clampedSlot
        val targetKey = keyOf(row, clampedSlot)
        savedFocus.value = targetKey
        restoreKey = targetKey
        focusedSection = entry.section
        onActiveSectionChange(entry.section)
        onEnterImmersive()

        val rowVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetRowIndex }
        if (!rowVisible) {
            listState.scrollToItem(targetRowIndex)
        }
        for (attempt in 0 until 12) {
            val handle = rowHandles[storageKey]
            if (handle != null) {
                val primary = if (useRestoreRequester) restoreRequester else null
                if (handle.scrollToAndFocusSlot(clampedSlot, primary)) {
                    return true
                }
            }
            if (attempt == 2 && listState.layoutInfo.visibleItemsInfo.none { it.index == targetRowIndex }) {
                listState.scrollToItem(targetRowIndex)
            }
            withFrameNanos { }
        }
        return if (useRestoreRequester) {
            runCatching { restoreRequester.requestFocus() }.getOrDefault(false)
        } else {
            false
        }
    }

    fun moveVertical(delta: Int): Boolean {
        val activeIndex = if (verticalState.job?.isActive == true && verticalState.pendingRowIndex in sectionedRows.indices) {
            verticalState.pendingRowIndex
        } else if (verticalState.currentRowIndex in sectionedRows.indices) {
            verticalState.currentRowIndex
        } else {
            val rowId = validKey(savedFocus.value).substringBeforeLast('/')
            sectionedRows.indexOfFirst { it.row.id == rowId }.takeIf { it >= 0 } ?: 0
        }
        if (delta < 0 && activeIndex <= 0) {
            verticalState.job?.cancel()
            verticalState.pendingRowIndex = -1
            verticalParkingActive = false
            return if (onExitTop != null) {
                onExitTop()
                true
            } else {
                false
            }
        }
        var targetIndex = activeIndex + delta
        while (targetIndex in sectionedRows.indices && sectionedRows[targetIndex].row.items.isEmpty()) {
            targetIndex += delta
        }
        if (targetIndex !in sectionedRows.indices) return true

        sectionedRows.getOrNull(activeIndex)?.let { current ->
            rowHandles[rowStorageKey(current)]?.cancelHorizontalScroll()
        }
        verticalState.pendingRowIndex = targetIndex

        val targetEntry = sectionedRows[targetIndex]
        val targetStorageKey = rowStorageKey(targetEntry)
        val targetSlot = resolveTargetSlot(targetEntry)
        val targetKey = keyOf(targetEntry.row, targetSlot)
        savedRowSlots[targetStorageKey] = targetSlot
        savedFocus.value = targetKey
        restoreKey = targetKey
        focusedSection = targetEntry.section
        onActiveSectionChange(targetEntry.section)
        onEnterImmersive()

        val rowVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (rowVisible && rowHandles[targetStorageKey]?.focusSlotImmediate(targetSlot) == true) {
            verticalState.job?.cancel()
            verticalState.pendingRowIndex = -1
            verticalParkingActive = false
            return true
        }

        // Keep focus parked inside RowsBrowser while LazyColumn scrolls an off-screen row into composition.
        verticalParkingActive = true
        runCatching { verticalParkingRequester.requestFocus() }
        verticalState.job?.cancel()
        verticalState.job = scope.launch {
            try {
                scrollAndFocusRow(targetIndex, slotOverride = targetSlot, useRestoreRequester = true)
            } finally {
                if (verticalState.pendingRowIndex == targetIndex) {
                    verticalState.pendingRowIndex = -1
                }
                verticalParkingActive = false
            }
        }
        return true
    }

    LaunchedEffect(railFocused) {
        if (railFocused) syncRestoreKey()
    }
    LaunchedEffect(Unit) {
        val currentKey = validKey(savedFocus.value)
        val currentRowId = currentKey.substringBeforeLast('/')
        val currentRowIndex = sectionedRows.indexOfFirst { it.row.id == currentRowId }.takeIf { it >= 0 } ?: 0
        val currentEntry = sectionedRows[currentRowIndex]
        val section = currentEntry.section
        focusedSection = section
        onActiveSectionChange(section)
        if (!railFocused) {
            val parsedSlot = currentKey.substringAfterLast('/').toIntOrNull()
            val slot = parsedSlot ?: resolveTargetSlot(currentEntry)
            scrollAndFocusRow(currentRowIndex, slotOverride = slot, useRestoreRequester = true)
        }
    }
    LaunchedEffect(backToTopTick) {
        if (backToTopTick > 0 && rows.isNotEmpty()) {
            val firstEntry = sectionedRows.first()
            val topSlot = defaultSlotFor(firstEntry.row, loopsFor(firstEntry.row))
            val topKey = keyOf(firstEntry.row, topSlot)
            savedRowSlots[rowStorageKey(firstEntry)] = topSlot
            savedFocus.value = topKey
            restoreKey = topKey
            heroState.value = firstEntry.row.items.first()
            focusedSection = firstEntry.section
            onActiveSectionChange(firstEntry.section)
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
        val topSlot = defaultSlotFor(targetEntry.row, loopsFor(targetEntry.row))
        val topKey = keyOf(targetEntry.row, topSlot)
        savedRowSlots[rowStorageKey(targetEntry)] = topSlot
        savedFocus.value = topKey
        restoreKey = topKey
        targetEntry.row.items.firstOrNull()?.let { heroState.value = it }
        focusedSection = targetEntry.section
        onActiveSectionChange(targetEntry.section)
        scrollAndFocusRow(targetIndex, slotOverride = topSlot, useRestoreRequester = true)
        onScrollToSectionConsumed()
    }
    LaunchedEffect(rows) {
        val pending = pendingFocus ?: return@LaunchedEffect
        val targetKey = pending.itemKey
            ?.let { itemKey -> focusKeyForItem(rows, pending.rowId, itemKey) }
            ?: pending.fallbackKey
        val targetRowIndex = sectionedRows.indexOfFirst { it.row.id == pending.rowId }.takeIf { it >= 0 } ?: 0
        val targetSlot = targetKey.substringAfterLast('/').toIntOrNull()
        scrollAndFocusRow(targetRowIndex, slotOverride = targetSlot, useRestoreRequester = true)
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
                            scrollAndFocusRow(0, useRestoreRequester = true)
                        }
                        true
                    } else {
                        false
                    }
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(194.dp)
                    .padding(start = startPadding, top = 14.dp, end = 360.dp),
            ) {
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (topBarFocused || railFocused || event.type != KeyEventType.KeyDown || sectionedRows.isEmpty()) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.DirectionDown -> moveVertical(+1)
                            Key.DirectionUp -> moveVertical(-1)
                            else -> false
                        }
                    },
            ) {
                Box(
                    Modifier
                        .size(1.dp)
                        .focusRequester(verticalParkingRequester)
                        .focusProperties { canFocus = verticalParkingActive }
                        .focusTarget(),
                )
                ProvidePivotScroll(leading = 51.dp) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer {},
                        contentPadding = PaddingValues(bottom = 260.dp),
                    ) {
                        itemsIndexed(sectionedRows, key = { _, entry -> rowStorageKey(entry) }, contentType = { _, entry -> entry.row.style }) { rowIndex, entry ->
                            val row = entry.row
                            val storageKey = rowStorageKey(entry)
                            val isSectionStart = entry.section != RailDestination.HOME &&
                                (rowIndex == 0 || sectionedRows[rowIndex - 1].section != entry.section)
                            val sectionHeaderLabel = if (isSectionStart) sectionLabel(entry.section) else null
                            val rowLoops = loopsFor(row)
                            val initialSlot = if (rowLoops) {
                                savedRowSlots[storageKey]?.takeIf { it >= 0 } ?: firstVirtualIndex(row.items.size)
                            } else {
                                (savedRowSlots[storageKey] ?: 0).coerceIn(0, row.items.lastIndex.coerceAtLeast(0))
                            }
                            val syncAndClick: (CatalogItem) -> Unit = { clicked ->
                                syncRestoreKey()
                                onItemClick(clicked)
                            }
                            val syncAndContinue: ((CatalogItem, ContinueInfo) -> Unit)? = onContinueClick?.let { fn ->
                                { clicked, info ->
                                    syncRestoreKey()
                                    fn(clicked, info)
                                }
                            }
                            val syncAndNewEp: ((CatalogItem, Episode) -> Unit)? = onNewEpisodeClick?.let { fn ->
                                { clicked, ep ->
                                    syncRestoreKey()
                                    fn(clicked, ep)
                                }
                            }
                            val syncAndUpNext: ((CatalogItem, Episode) -> Unit)? = onUpNextClick?.let { fn ->
                                { clicked, ep ->
                                    syncRestoreKey()
                                    fn(clicked, ep)
                                }
                            }
                            CatalogRowView(
                                row = row,
                                storageKey = storageKey,
                                initialSlot = initialSlot,
                                onRegisterHandle = { handle ->
                                    if (handle != null) {
                                        rowHandles[storageKey] = handle
                                    } else {
                                        rowHandles.remove(storageKey)
                                    }
                                },
                                sectionLabel = sectionHeaderLabel,
                                startPadding = startPadding,
                                loops = rowLoops,
                                focusKey = restoreKey,
                                restoreRequester = restoreRequester,
                                railRequester = railRequester,
                                onOpenRail = onOpenRail?.let { open ->
                                    {
                                        syncRestoreKey()
                                        open()
                                    }
                                },
                                resetScrollTick = if (rowIndex == 0) backToTopTick else 0,
                                onLoadMore = if (row.style == RowStyle.POSTER && onLoadMoreRow != null) {
                                    { rowId -> onLoadMoreRow(entry.filter, rowId) }
                                } else {
                                    null
                                },
                                onFocused = { virtual ->
                                    verticalState.currentRowIndex = rowIndex
                                    savedRowSlots[storageKey] = virtual
                                    savedFocus.value = keyOf(row, virtual)
                                    focusedSection = entry.section
                                    onActiveSectionChange(entry.section)
                                    onEnterImmersive()
                                },
                                onItemClick = syncAndClick,
                                onNewEpisodeClick = syncAndNewEp,
                                onContinueClick = syncAndContinue,
                                onUpNextClick = syncAndUpNext,
                                onContinueMenu = { virtual, item -> continueMenu = ItemMenu(row, virtual, item) },
                                onMyListMenu = { virtual, item -> myListMenu = ItemMenu(row, virtual, item) },
                                onUpNextMenu = { virtual, item, episode -> upNextMenu = UpNextMenu(row, virtual, item, episode) },
                                onQuickMenu = { item -> quickMenuItem = item },
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
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black),
                    ),
                ),
        )
        quickNoticeRes?.let { msgRes ->
            Text(
                text = localizedStringResource(msgRes),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 40.dp)
                    .background(Color(0xE6141416), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
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
        val actions = buildList {
            if (onQuickPlay != null) {
                add(
                    ActionMenuItem(localizedStringResource(StringsR.string.home_play_now), AppIcons.Play) {
                        myListMenu = null
                        onQuickPlay(menu.item)
                    },
                )
            }
            add(
                ActionMenuItem(localizedStringResource(StringsR.string.home_view_details), AppIcons.List) {
                    myListMenu = null
                    onItemClick(menu.item)
                },
            )
            add(
                ActionMenuItem(localizedStringResource(StringsR.string.home_remove), LineIcons.Delete) {
                    myListMenu = null
                    pendingFocus = PendingFocus(menu.row.id, itemKey = null, fallbackKey = neighbourKey(rows, menu.row, menu.virtual, loopsFor(menu.row)))
                    onRemoveMyList(menu.item)
                },
            )
            add(ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close) { myListMenu = null })
        }
        ActionMenuDialog(
            title = menu.item.title,
            onDismiss = { myListMenu = null },
            consumeOpeningKeyRelease = true,
            actions = actions,
        )
    }

    quickMenuItem?.let { item ->
        val inList = "${item.type.name}:${item.id}" in myListKeys
        PosterQuickActionsDialog(
            item = item,
            inMyList = inList,
            onPlayNow = onQuickPlay?.let { play ->
                {
                    quickMenuItem = null
                    play(item)
                }
            },
            onToggleMyList = onToggleMyList?.let { toggle ->
                {
                    quickMenuItem = null
                    val added = toggle(item)
                    quickNoticeRes = if (added) StringsR.string.home_added_to_my_list else StringsR.string.home_removed_from_my_list
                }
            },
            onDetails = {
                quickMenuItem = null
                onItemClick(item)
            },
            onDismiss = { quickMenuItem = null },
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
internal fun PosterQuickActionsDialog(
    item: CatalogItem,
    inMyList: Boolean,
    onPlayNow: (() -> Unit)?,
    onToggleMyList: (() -> Unit)?,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        if (onPlayNow != null) {
            add(ActionMenuItem(localizedStringResource(StringsR.string.home_play_now), AppIcons.Play, onPlayNow))
        }
        if (onToggleMyList != null) {
            add(
                ActionMenuItem(
                    text = localizedStringResource(
                        if (inMyList) StringsR.string.home_remove_from_my_list
                        else StringsR.string.home_add_to_my_list,
                    ),
                    icon = if (inMyList) AppIcons.Check else AppIcons.Add,
                    onClick = onToggleMyList,
                ),
            )
        }
        add(ActionMenuItem(localizedStringResource(StringsR.string.home_view_details), AppIcons.List, onDetails))
        add(ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close, onDismiss))
    }
    ActionMenuDialog(
        title = item.title,
        onDismiss = onDismiss,
        consumeOpeningKeyRelease = true,
        actions = actions,
    )
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
    storageKey: String,
    initialSlot: Int,
    onRegisterHandle: (RowFocusHandle?) -> Unit,
    sectionLabel: String? = null,
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
    onQuickMenu: (CatalogItem) -> Unit = {},
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
                val rowState = rememberLoopingRowState(if (loops) row.items.size else 0, initialIndex = initialSlot)
                val tracker = remember { RowFocusTracker() }
                val slotRequesters = remember { HashMap<Int, FocusRequester>() }
                val rowScope = rememberCoroutineScope()
                var fastScrollJob by remember { mutableStateOf<Job?>(null) }
                var parkingActive by remember { mutableStateOf(false) }
                val rowParkingRequester = remember { FocusRequester() }
                var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
                var pendingRestoreSlot by remember { mutableStateOf(-1) }
                var lastSizeSeen by remember { mutableStateOf(row.items.size) }
                val paginationRestoreRequester = remember { FocusRequester() }

                DisposableEffect(storageKey, rowState, loops, row.items.size, focusKey) {
                    val cancelHorizontal = {
                        tracker.pendingSlot = -1
                        fastScrollJob?.cancel()
                        parkingActive = false
                    }
                    val clampSlot: (Int) -> Int = { raw ->
                        if (loops) raw.coerceAtLeast(0) else raw.coerceIn(0, row.items.lastIndex.coerceAtLeast(0))
                    }
                    val handle = RowFocusHandle(
                        cancelHorizontalScroll = cancelHorizontal,
                        focusSlotImmediate = { rawSlot ->
                            if (row.items.isEmpty()) {
                                false
                            } else {
                                val target = clampSlot(rawSlot)
                                val layout = rowState.layoutInfo
                                val info = layout.visibleItemsInfo.firstOrNull { it.index == target }
                                val fullyVisible = info != null &&
                                    info.offset >= 0 &&
                                    info.offset + info.size <= layout.viewportEndOffset
                                if (!fullyVisible) {
                                    false
                                } else {
                                    cancelHorizontal()
                                    slotRequesters[target]?.let { req ->
                                        runCatching { req.requestFocus() }.getOrDefault(false)
                                    } == true
                                }
                            }
                        },
                        scrollToAndFocusSlot = { rawSlot, primaryRequester ->
                            if (row.items.isEmpty()) {
                                false
                            } else {
                                cancelHorizontal()
                                val target = clampSlot(rawSlot)
                                val layout = rowState.layoutInfo
                                val info = layout.visibleItemsInfo.firstOrNull { it.index == target }
                                val fullyVisible = info != null &&
                                    info.offset >= 0 &&
                                    info.offset + info.size <= layout.viewportEndOffset
                                if (!fullyVisible) {
                                    rowState.scrollToItem(target)
                                    withFrameNanos { }
                                }
                                var focused = false
                                for (attempt in 0 until 8) {
                                    val tryPrimary = primaryRequester != null &&
                                        keyOf(row, target) == focusKey &&
                                        runCatching { primaryRequester.requestFocus() }.getOrDefault(false)
                                    if (tryPrimary) {
                                        focused = true
                                        break
                                    }
                                    val req = slotRequesters[target]
                                    if (req != null && runCatching { req.requestFocus() }.getOrDefault(false)) {
                                        focused = true
                                        break
                                    }
                                    if (attempt == 2) {
                                        rowState.scrollToItem(target)
                                    }
                                    withFrameNanos { }
                                }
                                if (!focused) {
                                    val visibleFallback = rowState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { vis ->
                                        slotRequesters[vis.index]?.takeIf { req ->
                                            runCatching { req.requestFocus() }.getOrDefault(false)
                                        }
                                    }
                                    focused = visibleFallback != null
                                }
                                focused
                            }
                        },
                    )
                    onRegisterHandle(handle)
                    onDispose { onRegisterHandle(null) }
                }

                LaunchedEffect(resetScrollTick) {
                    if (resetScrollTick > 0) {
                        tracker.pendingSlot = -1
                        fastScrollJob?.cancel()
                        parkingActive = false
                        rowState.scrollToItem(if (loops) firstVirtualIndex(row.items.size) else 0)
                    }
                }
                if (expandable) {
                    LaunchedEffect(rowState, row.items.size) {
                        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                            .collectLatest { lastVisible ->
                                if (lastVisible >= row.items.lastIndex - 6 && row.items.isNotEmpty()) {
                                    onLoadMore?.invoke(row.id)
                                }
                            }
                    }
                    LaunchedEffect(row.items.size) {
                        val grew = row.items.size > lastSizeSeen
                        val wasFocused = tracker.hasFocus
                        val restoreTarget = tracker.key
                        val restoreSlot = tracker.slot
                        lastSizeSeen = row.items.size
                        if (grew && wasFocused && restoreTarget != null) {
                            withFrameNanos { }
                            // Only restore if focus was actually dropped off the row (not when user actively moved).
                            if (!tracker.hasFocus && !parkingActive) {
                                pendingRestoreSlot = restoreSlot
                                pendingRestoreKey = restoreTarget
                            }
                        }
                    }
                    LaunchedEffect(pendingRestoreKey) {
                        if (pendingRestoreKey != null) {
                            withFrameNanos { }
                            val restored = runCatching { paginationRestoreRequester.requestFocus() }.isSuccess
                            if (!restored && pendingRestoreSlot >= 0) {
                                runCatching { slotRequesters[pendingRestoreSlot]?.requestFocus() }
                            }
                            pendingRestoreKey = null
                            pendingRestoreSlot = -1
                        }
                    }
                }
                fun driveFocusToSlot(targetSlot: Int) {
                    tracker.pendingSlot = targetSlot
                    val layout = rowState.layoutInfo
                    val visibleInfo = layout.visibleItemsInfo.firstOrNull { it.index == targetSlot }
                    val fullyVisible = visibleInfo != null &&
                        visibleInfo.offset >= 0 &&
                        visibleInfo.offset + visibleInfo.size <= layout.viewportEndOffset
                    if (fullyVisible) {
                        val moved = slotRequesters[targetSlot]?.let { req ->
                            runCatching { req.requestFocus() }.getOrDefault(false)
                        } == true
                        if (moved) {
                            if (tracker.pendingSlot == targetSlot) tracker.pendingSlot = -1
                            parkingActive = false
                            return
                        }
                    }
                    // Keep focus parked inside this row while LazyRow scrolls so off-screen item
                    // disposal cannot drop focus out of the row or snap back to slot 0.
                    parkingActive = true
                    runCatching { rowParkingRequester.requestFocus() }
                    if (fastScrollJob?.isActive == true) return
                    fastScrollJob = rowScope.launch {
                        while (tracker.pendingSlot >= 0) {
                            val target = tracker.pendingSlot
                            rowState.scrollToItem(target)
                            var focused = false
                            for (attempt in 0 until 8) {
                                withFrameNanos { }
                                if (tracker.pendingSlot != target) break
                                val req = slotRequesters[target]
                                if (req != null && runCatching { req.requestFocus() }.getOrDefault(false)) {
                                    focused = true
                                    break
                                }
                            }
                            if (tracker.pendingSlot == target) {
                                tracker.pendingSlot = -1
                                if (focused || attemptRestoreCurrent(slotRequesters, tracker.slot)) break
                                break
                            }
                        }
                        parkingActive = false
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { tracker.hasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || row.items.isEmpty()) return@onPreviewKeyEvent false
                            val currentSlot = if (tracker.pendingSlot >= 0) tracker.pendingSlot else tracker.slot
                            if (currentSlot < 0) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionRight -> {
                                    if (!loops && currentSlot >= row.items.lastIndex) {
                                        if (expandable) onLoadMore?.invoke(row.id)
                                        return@onPreviewKeyEvent true
                                    }
                                    val targetSlot = if (loops) currentSlot + 1 else (currentSlot + 1).coerceAtMost(row.items.lastIndex)
                                    if (expandable && targetSlot >= row.items.lastIndex - 6) {
                                        onLoadMore?.invoke(row.id)
                                    }
                                    driveFocusToSlot(targetSlot)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (!loops && currentSlot <= 0) {
                                        // Stop cleanly at item 0 while holding Left; a fresh press at 0 opens the rail.
                                        return@onPreviewKeyEvent event.nativeKeyEvent.repeatCount > 0
                                    }
                                    driveFocusToSlot(currentSlot - 1)
                                    true
                                }
                                Key.DirectionUp, Key.DirectionDown -> {
                                    tracker.pendingSlot = -1
                                    fastScrollJob?.cancel()
                                    parkingActive = false
                                    false
                                }
                                else -> false
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .size(1.dp)
                            .focusRequester(rowParkingRequester)
                            .focusProperties { canFocus = parkingActive }
                            .focusTarget(),
                    )
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = startPadding, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    if (loops) {
                        loopingItems(row.items) { virtual, index, item ->
                            val slotReq = remember(virtual) { FocusRequester() }
                            DisposableEffect(virtual) {
                                slotRequesters[virtual] = slotReq
                                onDispose {
                                    if (slotRequesters[virtual] === slotReq) {
                                        slotRequesters.remove(virtual)
                                    }
                                }
                            }
                            val itemModifier = if (keyOf(row, virtual) == focusKey) {
                                Modifier.focusRequester(restoreRequester).focusRequester(slotReq)
                            } else {
                                Modifier.focusRequester(slotReq)
                            }
                            RowItemCard(
                                row = row,
                                index = index,
                                virtual = virtual,
                                item = item,
                                modifier = itemModifier,
                                onFocused = {
                                    tracker.slot = virtual
                                    tracker.key = item.key
                                    if (tracker.pendingSlot == virtual) tracker.pendingSlot = -1
                                    if (parkingActive) parkingActive = false
                                    onFocused(virtual)
                                },
                                onItemClick = onItemClick,
                                onNewEpisodeClick = onNewEpisodeClick,
                                onContinueClick = onContinueClick,
                                onUpNextClick = onUpNextClick,
                                onContinueMenu = { onContinueMenu(virtual, item) },
                                onMyListMenu = { onMyListMenu(virtual, item) },
                                onUpNextMenu = { episode -> onUpNextMenu(virtual, item, episode) },
                                onQuickMenu = { onQuickMenu(item) },
                            )
                        }
                    } else {
                        itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                            val slotReq = remember(index) { FocusRequester() }
                            DisposableEffect(index) {
                                slotRequesters[index] = slotReq
                                onDispose {
                                    if (slotRequesters[index] === slotReq) {
                                        slotRequesters.remove(index)
                                    }
                                }
                            }
                            val baseFocusMod = if (keyOf(row, index) == focusKey) {
                                Modifier.focusRequester(restoreRequester).focusRequester(slotReq)
                            } else {
                                Modifier.focusRequester(slotReq)
                            }
                            val focusMod = if (item.key == pendingRestoreKey) {
                                baseFocusMod.focusRequester(paginationRestoreRequester)
                            } else {
                                baseFocusMod
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
                            }
                            RowItemCard(
                                row = row,
                                index = index,
                                virtual = index,
                                item = item,
                                modifier = edgeMod,
                                onFocused = {
                                    tracker.slot = index
                                    tracker.key = item.key
                                    if (tracker.pendingSlot == index) tracker.pendingSlot = -1
                                    if (parkingActive) parkingActive = false
                                    onFocused(index)
                                    if (expandable && index >= row.items.lastIndex - 6) {
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
                                onQuickMenu = { onQuickMenu(item) },
                            )
                        }
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
    onQuickMenu: () -> Unit = {},
) {
    when (row.style) {
        RowStyle.POSTER -> CatalogPosterCard(
            item = item,
            onClick = { onItemClick(item) },
            onFocused = onFocused,
            onLongClick = onQuickMenu,
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
                onLongClick = onQuickMenu,
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
            onLongClick = onQuickMenu,
            newEpisodeCount = row.newEpisodeCounts[item.key] ?: 0,
            modifier = modifier,
        )
    }
}

private fun rowStorageKey(entry: SectionedRow): String = "${entry.section.name}:${entry.row.id}"

private class RowFocusHandle(
    val cancelHorizontalScroll: () -> Unit,
    val focusSlotImmediate: (Int) -> Boolean,
    val scrollToAndFocusSlot: suspend (Int, FocusRequester?) -> Boolean,
)

private class VerticalFocusState {
    var currentRowIndex: Int = -1
    var pendingRowIndex: Int = -1
    var job: Job? = null
}

private class RowFocusTracker {
    var hasFocus: Boolean = false
    var key: String? = null
    var slot: Int = -1
    var pendingSlot: Int = -1
}

private fun attemptRestoreCurrent(slotRequesters: Map<Int, FocusRequester>, slot: Int): Boolean =
    slot >= 0 && runCatching { slotRequesters[slot]?.requestFocus() }.getOrDefault(false) == true

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
