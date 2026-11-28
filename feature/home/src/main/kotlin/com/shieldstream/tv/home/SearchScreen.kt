package com.shieldstream.tv.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.ActionMenuDialog
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.PosterCard
import com.shieldstream.tv.ui.component.ProvidePivotScroll
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// 5 列大键位，遥控器上下左右移动更少、更好按。
private val PINYIN_ROWS = listOf("qwert", "yuiop", "asdfg", "hjklz", "xcvbn", "m1234")
private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private const val COMPACT_GENRE_COUNT = 9

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onItemClick: (CatalogItem) -> Unit,
    onQuickPlay: ((CatalogItem) -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
    onOpenRail: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val myListKeys by viewModel.myListKeys.collectAsStateWithLifecycle()
    var lockedPick by remember { mutableStateOf<CatalogItem?>(null) }
    var quickMenuItem by remember { mutableStateOf<CatalogItem?>(null) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var genresExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            viewModel.consumeNotice()
        }
    }
    lockedPick?.let { item ->
        ActionMenuDialog(
            title = item.title,
            onDismiss = { lockedPick = null },
            actions = listOf(
                ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close) { lockedPick = null },
                ActionMenuItem(localizedStringResource(StringsR.string.home_ask_an_adult_action), AppIcons.Lock) {
                    viewModel.requestTitle(item)
                    lockedPick = null
                },
            ),
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
            onToggleMyList = {
                quickMenuItem = null
                viewModel.toggleMyList(item)
            },
            onDetails = {
                quickMenuItem = null
                onItemClick(item)
            },
            onDismiss = { quickMenuItem = null },
        )
    }

    val qwerty by viewModel.qwerty.collectAsStateWithLifecycle()
    val composing by viewModel.composing.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()

    val searchBoxRequester = initialFocusRequester ?: remember { FocusRequester() }
    val topActionRequester = remember { FocusRequester() }
    val firstKeyRequester = remember { FocusRequester() }
    val layoutKeyRequester = remember { FocusRequester() }
    val firstFilterRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val visibleResultRequester = remember { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    val gridTracker = remember { GridFocusTracker() }
    val leftFocusTracker = remember { PaneFocusTracker() }
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        val backOnCard = lastCardKey != null && run {
            repeat(4) {
                if (runCatching { cardRequester.requestFocus() }.isSuccess) return@run true
                delay(60)
            }
            false
        }
        if (!backOnCard) {
            searchBoxRequester.requestFocusWithRetry()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val fieldRequester = remember { FocusRequester() }
    var systemKeyboard by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }

    // In on-screen IME mode the field mirrors "committed text + pinyin being composed".
    // The composing part is tinted so the user can see it is not committed yet.
    val display = remember(query, composing, systemKeyboard) {
        if (systemKeyboard) return@remember field
        val text = query + composing
        val styled = buildAnnotatedString {
            append(query)
            if (composing.isNotEmpty()) {
                withStyle(SpanStyle(color = Color(0xFF7FD4FF))) { append(composing) }
            }
        }
        TextFieldValue(styled, TextRange(text.length))
    }

    LaunchedEffect(systemKeyboard) {
        if (systemKeyboard) {
            val full = query + composing
            field = TextFieldValue(full, TextRange(full.length))
            withFrameNanos { }
            runCatching { fieldRequester.requestFocus() }
            keyboard?.show()
        }
    }

    var wasKeyboardOpen by remember { mutableStateOf(keyboardOpen) }
    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen && !wasKeyboardOpen) {
            withFrameNanos { }
            firstKeyRequester.requestFocusWithRetry()
        } else if (!keyboardOpen && wasKeyboardOpen) {
            withFrameNanos { }
            searchBoxRequester.requestFocusWithRetry()
        }
        wasKeyboardOpen = keyboardOpen
    }

    fun closeSystemKeyboard() {
        keyboard?.hide()
        systemKeyboard = false
        runCatching { layoutKeyRequester.requestFocus() }
    }

    fun collapseKeyboard() {
        if (systemKeyboard) {
            keyboard?.hide()
            systemKeyboard = false
        }
        keyboardOpen = false
    }

    val interceptBack = lockedPick != null || systemKeyboard || keyboardOpen
    val handleLocalBack: () -> Unit = {
        when {
            lockedPick != null -> lockedPick = null
            systemKeyboard -> closeSystemKeyboard()
            keyboardOpen -> collapseKeyboard()
        }
    }
    BackHandler(enabled = interceptBack, onBack = handleLocalBack)

    val leftColumnWidth = if (keyboardOpen && qwerty) 452.dp else 390.dp
    val currentGridItems = when (val s = state) {
        is SearchUiState.Browse -> s.items
        is SearchUiState.Results -> s.items
        else -> emptyList()
    }
    val currentLocked = (state as? SearchUiState.Results)?.locked.orEmpty()
    val hasRightTargets = currentGridItems.isNotEmpty() || currentLocked.isNotEmpty()
    val moveFocusToResults: () -> Boolean = {
        if (!hasRightTargets) {
            false
        } else {
            val restored = lastCardKey != null && runCatching { cardRequester.requestFocus() }.getOrDefault(false)
            restored ||
                runCatching { firstResultRequester.requestFocus() }.getOrDefault(false) ||
                runCatching { visibleResultRequester.requestFocus() }.getOrDefault(false)
        }
    }
    val moveFocusToLeftPane: () -> Boolean = {
        runCatching { leftFocusTracker.lastRequester?.requestFocus() == true }.getOrDefault(false) ||
            (keyboardOpen && runCatching { firstKeyRequester.requestFocus() }.getOrDefault(false)) ||
            (!keyboardOpen && runCatching { firstFilterRequester.requestFocus() }.getOrDefault(false)) ||
            runCatching { searchBoxRequester.requestFocus() }.getOrDefault(false)
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onBackKey(enabled = interceptBack, onBack = handleLocalBack),
    ) {
        if (onOpenRail != null) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .onFocusChanged { if (it.isFocused) onOpenRail() }
                    .focusable(),
            )
        }

        Column(
            Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .padding(start = 36.dp, top = 34.dp, end = 16.dp, bottom = 20.dp)
                .onFocusChanged { leftFocusTracker.hasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    if (!systemKeyboard &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionRight
                    ) {
                        val beforeBounds = leftFocusTracker.focusedBounds
                        val moved = focusManager.moveFocus(FocusDirection.Right)
                        if (!moved) {
                            moveFocusToResults()
                        } else if (!leftFocusTracker.hasFocus) {
                            true
                        } else {
                            val afterBounds = leftFocusTracker.focusedBounds
                            val stayedSameRow = beforeBounds != null &&
                                afterBounds != null &&
                                afterBounds.left >= beforeBounds.right - 2f &&
                                abs(afterBounds.center.y - beforeBounds.center.y) <
                                    minOf(beforeBounds.height, afterBounds.height) * 0.65f
                            if (stayedSameRow) true else moveFocusToResults()
                        }
                    } else {
                        false
                    }
                },
        ) {
            val hasTopAction = keyboardOpen || query.isNotEmpty() || composing.isNotEmpty()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                var searchBoxFocused by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .onFocusChanged { searchBoxFocused = it.isFocused }
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                systemKeyboard -> Color(0xFF1C1C1F)
                                searchBoxFocused -> Color.White
                                else -> Color(0xFF1C1C1F)
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                systemKeyboard -> Color.White
                                searchBoxFocused -> Color.White
                                keyboardOpen -> AppColors.Accent
                                else -> Color.White.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        .then(
                            if (systemKeyboard) {
                                Modifier
                            } else {
                                Modifier
                                    .trackLeftFocus(leftFocusTracker, searchBoxRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionDown -> {
                                                val target = if (keyboardOpen) firstKeyRequester else firstFilterRequester
                                                runCatching { target.requestFocus() }.getOrDefault(false)
                                            }
                                            Key.DirectionRight -> {
                                                if (hasTopAction) {
                                                    runCatching { topActionRequester.requestFocus() }.getOrDefault(false)
                                                } else {
                                                    moveFocusToResults()
                                                }
                                            }
                                            else -> false
                                        }
                                    }
                                    .tvClickable {
                                        keyboardOpen = !keyboardOpen
                                    }
                            },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconTint = if (searchBoxFocused && !systemKeyboard) Color.Black else Color.White.copy(alpha = 0.7f)
                    Icon(AppIcons.Search, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(
                            value = if (systemKeyboard) field else display,
                            onValueChange = {
                                if (systemKeyboard) {
                                    field = it
                                    viewModel.setQuery(it.text.replace("\n", ""))
                                }
                            },
                            enabled = true,
                            readOnly = !systemKeyboard,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = if (searchBoxFocused && !systemKeyboard) Color.Black else Color.White,
                                fontWeight = FontWeight.Medium,
                            ),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { closeSystemKeyboard() }, onDone = { closeSystemKeyboard() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(fieldRequester)
                                .focusProperties { canFocus = systemKeyboard }
                                .onPreviewKeyEvent { event ->
                                    val exit = event.key == Key.DirectionDown || event.key == Key.Back || event.key == Key.Escape
                                    if (!exit) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyUp) closeSystemKeyboard()
                                    true
                                }
                                .onFocusChanged { if (!it.isFocused && systemKeyboard) systemKeyboard = false },
                        )
                        if (query.isEmpty() && composing.isEmpty()) {
                            Text(
                                text = localizedStringResource(StringsR.string.home_search_open_keyboard_hint),
                                color = if (searchBoxFocused && !systemKeyboard) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.48f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                val topActionKeyModifier = Modifier
                    .trackLeftFocus(leftFocusTracker, topActionRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> moveFocusToResults()
                            Key.DirectionLeft -> runCatching { searchBoxRequester.requestFocus() }.getOrDefault(false)
                            Key.DirectionDown -> {
                                val target = if (keyboardOpen) firstKeyRequester else firstFilterRequester
                                runCatching { target.requestFocus() }.getOrDefault(false)
                            }
                            else -> false
                        }
                    }
                if (keyboardOpen) {
                    CompactActionChip(
                        label = localizedStringResource(StringsR.string.home_search_close_keyboard),
                        onClick = { collapseKeyboard() },
                        modifier = topActionKeyModifier,
                    )
                } else if (query.isNotEmpty() || composing.isNotEmpty()) {
                    CompactActionChip(
                        label = localizedStringResource(StringsR.string.home_clear),
                        onClick = {
                            viewModel.clearComposing()
                            viewModel.clear()
                        },
                        modifier = topActionKeyModifier,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (keyboardOpen) {
                // 候选词条固定在键盘上方，且始终占位：否则候选出现/消失会把按键推上推下，
                // 遥控器用户点一次 G 再点 Y 就点到别的键上了。
                if (!qwerty) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        itemsIndexed(candidates, key = { _, cand -> cand }) { _, cand ->
                            CandidateChip(
                                text = cand,
                                onClick = {
                                    viewModel.commitCandidate(cand)
                                    scope.launch {
                                        withFrameNanos { }
                                        runCatching { firstKeyRequester.requestFocus() }
                                    }
                                },
                                modifier = Modifier.trackLeftFocus(leftFocusTracker),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OnScreenKeyboard(
                    qwerty = qwerty,
                    composing = composing,
                    candidateCount = if (qwerty) 0 else candidates.size,
                    columnWidth = leftColumnWidth,
                    firstKeyRequester = firstKeyRequester,
                    layoutKeyRequester = layoutKeyRequester,
                    leftFocusTracker = leftFocusTracker,
                    onChar = { c ->
                        if (qwerty) viewModel.type(c) else viewModel.typeLetter(c)
                    },
                    onBackspace = {
                        // En modo pinyin, primero borra el buffer; si está vacío, borra la consulta.
                        if (qwerty || !viewModel.backspaceComposing()) viewModel.backspace()
                    },
                    onClear = {
                        viewModel.clearComposing()
                        viewModel.clear()
                    },
                    onToggleLayout = viewModel::toggleKeyboardLayout,
                    onSystemKeyboard = { systemKeyboard = true },
                    onSearch = {
                        viewModel.flushComposingToQuery()
                    },
                    onCollapse = { collapseKeyboard() },
                )
            } else {
                SearchFilterPanel(
                    filterState = filterState,
                    genresExpanded = genresExpanded,
                    firstFilterRequester = firstFilterRequester,
                    searchBoxRequester = searchBoxRequester,
                    leftFocusTracker = leftFocusTracker,
                    onMoveRightToResults = moveFocusToResults,
                    onOpenRail = onOpenRail,
                    onToggleGenresExpanded = { genresExpanded = !genresExpanded },
                    onSelectMediaType = viewModel::selectMediaType,
                    onSelectSort = viewModel::selectSort,
                    onToggleGenre = viewModel::toggleGenre,
                    onClearGenres = viewModel::clearGenres,
                    onSelectRegion = viewModel::selectRegion,
                    onSelectEra = viewModel::selectEra,
                    onReset = viewModel::resetFilters,
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 34.dp, end = 24.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        val activeKey = gridTracker.key
                        val focusedGridItem = if (activeKey != null) {
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                                val idx = it.index
                                currentGridItems.getOrNull(idx)?.key == activeKey
                            }
                        } else {
                            null
                        }
                        if (!gridTracker.hasFocus || focusedGridItem == null || focusedGridItem.column == 0) {
                            moveFocusToLeftPane()
                        } else {
                            focusManager.moveFocus(FocusDirection.Left)
                        }
                    } else {
                        false
                    }
                },
        ) {
            val header = when (val s = state) {
                SearchUiState.Loading -> localizedStringResource(StringsR.string.home_searching)
                is SearchUiState.Browse -> buildBrowseHeader(s.filter)
                is SearchUiState.Results -> localizedStringResource(StringsR.string.home_search_results_for, s.query)
                is SearchUiState.Empty -> localizedStringResource(StringsR.string.home_no_results_for, s.query)
                is SearchUiState.Error -> s.message
            }
            val isBrowseLoading = (state as? SearchUiState.Browse)?.isLoading == true
            val isLoadingMore = (state as? SearchUiState.Browse)?.isLoadingMore == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            ) {
                Text(
                    text = header,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBrowseLoading || isLoadingMore) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = AppColors.Accent,
                        strokeWidth = 2.dp,
                    )
                }
            }

            val items = when (val s = state) {
                is SearchUiState.Browse -> s.items
                is SearchUiState.Results -> s.items
                else -> emptyList()
            }
            val browseState = state as? SearchUiState.Browse
            val results = state as? SearchUiState.Results
            val locked = results?.locked.orEmpty()
            val pending = results?.pending.orEmpty()

            notice?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .padding(start = 12.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            var pendingGridRestoreKey by remember { mutableStateOf<String?>(null) }
            var lastGridSizeSeen by remember { mutableStateOf(items.size) }
            val gridRestoreRequester = remember { FocusRequester() }

            if (browseState != null && browseState.canLoadMore) {
                LaunchedEffect(gridState, items.size, browseState.canLoadMore) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collectLatest { lastVisible ->
                            if (items.isNotEmpty() && lastVisible >= items.lastIndex - 6) {
                                viewModel.loadMoreFiltered()
                            }
                        }
                }
            }
            if (browseState != null) {
                LaunchedEffect(items.size, browseState.filter) {
                    val grew = items.size > lastGridSizeSeen
                    val wasFocused = gridTracker.hasFocus
                    val restoreTarget = gridTracker.key
                    lastGridSizeSeen = items.size
                    if (grew && wasFocused && restoreTarget != null) {
                        withFrameNanos { }
                        if (!gridTracker.hasFocus) {
                            pendingGridRestoreKey = restoreTarget
                        }
                    }
                }
                LaunchedEffect(pendingGridRestoreKey) {
                    if (pendingGridRestoreKey != null) {
                        withFrameNanos { }
                        runCatching { gridRestoreRequester.requestFocus() }
                        pendingGridRestoreKey = null
                    }
                }
            }

            when {
                browseState != null && !browseState.isLoading && browseState.error != null && items.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = browseState.error,
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            CompactActionChip(
                                label = localizedStringResource(StringsR.string.home_retry),
                                primary = true,
                                onClick = viewModel::retryBrowse,
                            )
                        }
                    }
                }
                browseState != null && !browseState.isLoading && items.isEmpty() -> {
                    val summary = formatBrowseSummary(browseState.filter)
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = localizedStringResource(StringsR.string.home_filter_empty, summary),
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (browseState.filter.canReset) {
                                CompactActionChip(
                                    label = localizedStringResource(StringsR.string.home_filter_reset_all),
                                    primary = true,
                                    onClick = viewModel::resetFilters,
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(118.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged {
                                gridTracker.hasFocus = it.hasFocus
                                if (!it.hasFocus && gridTracker.key != null) {
                                    lastCardKey = gridTracker.key
                                }
                            },
                    ) {
                        val showSkeletons = state is SearchUiState.Loading || (browseState != null && browseState.isLoading && items.isEmpty())
                        if (showSkeletons) {
                            items(12, key = { "search_skeleton_$it" }) {
                                Box(
                                    Modifier
                                        .width(118.dp)
                                        .aspectRatio(2f / 3f)
                                        .background(SkeletonColor, RoundedCornerShape(6.dp)),
                                )
                            }
                        }
                        val firstVisibleResultIdx = if (items.isNotEmpty()) {
                            gridState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
                        } else {
                            -1
                        }
                        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                            var mod: Modifier = Modifier
                            if (index == 0) mod = mod.focusRequester(firstResultRequester)
                            if (index == firstVisibleResultIdx) mod = mod.focusRequester(visibleResultRequester)
                            if (item.key == lastCardKey) mod = mod.focusRequester(cardRequester)
                            if (item.key == pendingGridRestoreKey) mod = mod.focusRequester(gridRestoreRequester)
                            PosterCard(
                                item = item,
                                onClick = {
                                    lastCardKey = item.key
                                    onItemClick(item)
                                },
                                onLongClick = {
                                    lastCardKey = item.key
                                    quickMenuItem = item
                                },
                                onFocused = { gridTracker.key = item.key },
                                width = 118.dp,
                                showYearOnFocus = true,
                                modifier = mod,
                            )
                        }
                        if (locked.isNotEmpty()) {
                            item(key = "locked_header", span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.padding(top = 12.dp)) {
                                    Text(
                                        localizedStringResource(StringsR.string.home_ask_an_adult),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Text(
                                        localizedStringResource(StringsR.string.home_ask_an_adult_hint),
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            itemsIndexed(locked, key = { _, it -> "locked_" + it.key }) { index, item ->
                                val asked = item.key in pending
                                val lockedKey = "locked_" + item.key
                                var mod: Modifier = Modifier
                                if (items.isEmpty() && index == 0) {
                                    mod = mod.focusRequester(firstResultRequester).focusRequester(visibleResultRequester)
                                }
                                if (lockedKey == lastCardKey) mod = mod.focusRequester(cardRequester)
                                PosterCard(
                                    item = item,
                                    onClick = {
                                        lastCardKey = lockedKey
                                        if (!asked) lockedPick = item
                                    },
                                    onFocused = { gridTracker.key = lockedKey },
                                    width = 118.dp,
                                    modifier = mod,
                                    overlay = { LockedOverlay(asked) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class GridFocusTracker {
    var hasFocus: Boolean = false
    var key: String? = null
}

private class PaneFocusTracker {
    var hasFocus: Boolean = false
    var focusedBounds: Rect? = null
    var lastRequester: FocusRequester? = null
}

@Composable
private fun Modifier.trackLeftFocus(
    tracker: PaneFocusTracker,
    requester: FocusRequester? = null,
): Modifier {
    val target = requester ?: remember { FocusRequester() }
    val boundsRef = remember { arrayOfNulls<Rect>(1) }
    return this
        .focusRequester(target)
        .onGloballyPositioned { coords ->
            val bounds = coords.boundsInRoot()
            boundsRef[0] = bounds
            if (tracker.lastRequester === target) {
                tracker.focusedBounds = bounds
            }
        }
        .onFocusChanged { state ->
            if (state.isFocused) {
                tracker.lastRequester = target
                tracker.focusedBounds = boundsRef[0]
            }
        }
}

@Composable
private fun buildBrowseHeader(filter: SearchFilterState): String =
    localizedStringResource(StringsR.string.home_search_filter_results_header, formatBrowseSummary(filter))

@Composable
private fun formatBrowseSummary(filter: SearchFilterState): String {
    val typeLabel = localizedStringResource(filter.mediaType.labelRes)
    val sortLabel = localizedStringResource(filter.sort.labelRes)
    val selSummary = formatFilterSelectionSummary(filter.selection)
    return buildList {
        add(typeLabel)
        add(sortLabel)
        if (selSummary.isNotEmpty()) add(selSummary)
    }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterPanel(
    filterState: SearchFilterState,
    genresExpanded: Boolean,
    firstFilterRequester: FocusRequester,
    searchBoxRequester: FocusRequester,
    leftFocusTracker: PaneFocusTracker,
    onMoveRightToResults: () -> Boolean,
    onOpenRail: (() -> Unit)?,
    onToggleGenresExpanded: () -> Unit,
    onSelectMediaType: (SearchMediaType) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onToggleGenre: (String) -> Unit,
    onClearGenres: () -> Unit,
    onSelectRegion: (String?) -> Unit,
    onSelectEra: (String?) -> Unit,
    onReset: () -> Unit,
) {
    val allGenres = remember(filterState.mediaType) { CategoryFilterCatalog.genresFor(filterState.mediaType.filter) }
    val regions = remember(filterState.mediaType) { CategoryFilterCatalog.regionsFor(filterState.mediaType.filter) }
    val eras = remember { CategoryFilterCatalog.eras }
    val mediaSortRequesters = remember(firstFilterRequester) {
        Array(SearchMediaType.entries.size + SearchSort.entries.size) { slot ->
            if (slot == 0) firstFilterRequester else FocusRequester()
        }
    }
    val hasHiddenSelectedGenre = remember(allGenres, filterState.selection.genres) {
        filterState.selection.genres.any { tag ->
            val idx = allGenres.indexOfFirst { it.doubanTag == tag }
            idx >= COMPACT_GENRE_COUNT
        }
    }
    val showAllGenres = genresExpanded || hasHiddenSelectedGenre
    val visibleGenres = remember(allGenres, showAllGenres) {
        if (showAllGenres || allGenres.size <= COMPACT_GENRE_COUNT) allGenres else allGenres.take(COMPACT_GENRE_COUNT)
    }

    ProvidePivotScroll(leading = 36.dp) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "media_and_sort") {
                @Composable
                fun topChipKeyMod(slot: Int): Modifier = Modifier
                    .trackLeftFocus(leftFocusTracker, mediaSortRequesters[slot])
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> runCatching { searchBoxRequester.requestFocus() }.getOrDefault(false)
                            Key.DirectionRight -> {
                                if (slot < mediaSortRequesters.lastIndex) {
                                    runCatching { mediaSortRequesters[slot + 1].requestFocus() }.getOrDefault(false)
                                } else {
                                    onMoveRightToResults()
                                }
                            }
                            Key.DirectionLeft -> {
                                if (slot > 0) {
                                    runCatching { mediaSortRequesters[slot - 1].requestFocus() }.getOrDefault(false)
                                } else if (onOpenRail != null) {
                                    onOpenRail()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SearchMediaType.entries.forEachIndexed { idx, type ->
                            FilterPillChip(
                                label = localizedStringResource(type.labelRes),
                                selected = filterState.mediaType == type,
                                onClick = { onSelectMediaType(type) },
                                compact = true,
                                modifier = topChipKeyMod(idx),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SearchSort.entries.forEachIndexed { sIdx, sort ->
                            FilterPillChip(
                                label = localizedStringResource(sort.labelRes),
                                selected = filterState.sort == sort,
                                onClick = { onSelectSort(sort) },
                                compact = true,
                                modifier = topChipKeyMod(SearchMediaType.entries.size + sIdx),
                            )
                        }
                    }
                }
            }

            item(key = "genres") {
                FilterGroupBlock(
                    title = localizedStringResource(StringsR.string.home_filter_group_genre),
                    trailing = if (filterState.canReset) {
                        {
                            CompactActionChip(
                                label = localizedStringResource(StringsR.string.home_filter_reset),
                                onClick = onReset,
                                compact = true,
                                modifier = Modifier
                                    .trackLeftFocus(leftFocusTracker)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                            onMoveRightToResults()
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }
                    } else {
                        null
                    },
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterPillChip(
                            label = localizedStringResource(StringsR.string.home_filter_all_genres),
                            selected = filterState.selection.genres.isEmpty(),
                            onClick = onClearGenres,
                            modifier = Modifier.trackLeftFocus(leftFocusTracker),
                        )
                        visibleGenres.forEach { opt ->
                            val selected = opt.doubanTag in filterState.selection.genres
                            val text = localizedStringResource(opt.labelRes)
                            FilterPillChip(
                                label = if (selected) "✓ $text" else text,
                                selected = selected,
                                onClick = { onToggleGenre(opt.doubanTag) },
                                modifier = Modifier.trackLeftFocus(leftFocusTracker),
                            )
                        }
                        if (allGenres.size > COMPACT_GENRE_COUNT) {
                            FilterPillChip(
                                label = localizedStringResource(
                                    if (showAllGenres) StringsR.string.home_filter_less_genres else StringsR.string.home_filter_more_genres,
                                ),
                                selected = false,
                                subtle = true,
                                onClick = onToggleGenresExpanded,
                                modifier = Modifier.trackLeftFocus(leftFocusTracker),
                            )
                        }
                    }
                }
            }

            item(key = "regions") {
                FilterGroupBlock(title = localizedStringResource(StringsR.string.home_filter_group_region)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterPillChip(
                            label = localizedStringResource(StringsR.string.home_filter_all_regions),
                            selected = filterState.selection.region == null,
                            onClick = { onSelectRegion(null) },
                            modifier = Modifier.trackLeftFocus(leftFocusTracker),
                        )
                        regions.forEach { opt ->
                            FilterPillChip(
                                label = localizedStringResource(opt.labelRes),
                                selected = filterState.selection.region == opt.doubanTag,
                                onClick = { onSelectRegion(opt.doubanTag) },
                                modifier = Modifier.trackLeftFocus(leftFocusTracker),
                            )
                        }
                    }
                }
            }

            item(key = "eras") {
                FilterGroupBlock(title = localizedStringResource(StringsR.string.home_filter_group_era)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterPillChip(
                            label = localizedStringResource(StringsR.string.home_filter_all_eras),
                            selected = filterState.selection.era == null,
                            onClick = { onSelectEra(null) },
                            modifier = Modifier.trackLeftFocus(leftFocusTracker),
                        )
                        eras.forEach { opt ->
                            FilterPillChip(
                                label = localizedStringResource(opt.labelRes),
                                selected = filterState.selection.era == opt.doubanTag,
                                onClick = { onSelectEra(opt.doubanTag) },
                                modifier = Modifier.trackLeftFocus(leftFocusTracker),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterGroupBlock(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun FilterPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color(0x4DE50914)
        subtle -> Color.White.copy(alpha = 0.04f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val border = when {
        focused -> Color.White
        selected -> AppColors.Accent
        else -> Color.White.copy(alpha = 0.16f)
    }
    val textColor = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.8f)
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = if (focused) 1.05f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .tvClickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 6.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        primary -> AppColors.Accent
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = if (focused) Color.Black else Color.White
    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = if (focused) 1.05f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(
                width = 1.dp,
                color = if (focused) Color.White else if (primary) AppColors.Accent else Color.White.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .tvClickable(onClick = onClick)
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = if (compact) 11.sp else MaterialTheme.typography.labelMedium.fontSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun OnScreenKeyboard(
    qwerty: Boolean,
    composing: String,
    candidateCount: Int,
    columnWidth: Dp,
    firstKeyRequester: FocusRequester,
    layoutKeyRequester: FocusRequester,
    leftFocusTracker: PaneFocusTracker,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onToggleLayout: () -> Unit,
    onSystemKeyboard: () -> Unit,
    onSearch: () -> Unit,
    onCollapse: () -> Unit,
) {
    var toggled by remember { mutableStateOf(false) }
    LaunchedEffect(qwerty) {
        if (toggled) {
            withFrameNanos { }
            runCatching { layoutKeyRequester.requestFocus() }
        }
    }
    // 拼音模式 5 列大键位（遥控器好按），英文模式 10 列紧凑排列。
    val rows = if (qwerty) QWERTY_ROWS else PINYIN_ROWS
    val contentWidth = columnWidth - 52.dp
    val perRow = rows.first().length
    val keyGap = if (qwerty) 4.dp else 5.dp
    val keyWidth = (contentWidth - keyGap * (perRow - 1)) / perRow
    val fullWidth = keyWidth * perRow + keyGap * (perRow - 1)
    // 拼音模式 6 行字母 + 候选条 + 3 行功能键要在一屏 1080 里放下，键高缩到 36dp。
    val keyHeight = if (qwerty) 40.dp else 36.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (qwerty) 6.dp else 5.dp)) {
        rows.forEachIndexed { rowIndex, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                rowKeys.forEachIndexed { colIndex, c ->
                    val mod = Modifier.trackLeftFocus(
                        leftFocusTracker,
                        if (rowIndex == 0 && colIndex == 0) firstKeyRequester else null,
                    )
                    KeyButton(width = keyWidth, height = keyHeight, onClick = { onChar(c) }, modifier = mod) {
                        Text(c.uppercase(), style = MaterialTheme.typography.titleMedium, color = it)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
            val backWidth = keyWidth * 1.0f + keyGap
            val clearWidth = keyWidth * 1.5f + keyGap
            KeyButton(
                width = fullWidth - backWidth - clearWidth - keyGap * 2,
                height = keyHeight,
                onClick = { onChar(' ') },
                modifier = Modifier.trackLeftFocus(leftFocusTracker),
            ) {
                Text(localizedStringResource(StringsR.string.home_space), style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(
                width = backWidth,
                height = keyHeight,
                onClick = onBackspace,
                modifier = Modifier.trackLeftFocus(leftFocusTracker),
            ) {
                Icon(AppIcons.Backspace, contentDescription = localizedStringResource(StringsR.string.home_clear), tint = it, modifier = Modifier.size(20.dp))
            }
            KeyButton(
                width = clearWidth,
                height = keyHeight,
                onClick = onClear,
                modifier = Modifier.trackLeftFocus(leftFocusTracker),
            ) {
                Text(localizedStringResource(StringsR.string.home_clear), style = MaterialTheme.typography.bodyMedium, color = it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
            val sideWidth = fullWidth * 0.34f - keyGap
            val sysWidth = fullWidth - sideWidth * 2 - keyGap * 2
            KeyButton(
                width = sideWidth,
                height = keyHeight,
                onClick = {
                    toggled = true
                    onToggleLayout()
                },
                modifier = Modifier.trackLeftFocus(leftFocusTracker, layoutKeyRequester),
            ) {
                Text(if (qwerty) "拼音" else "英文", style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(
                width = sideWidth,
                height = keyHeight,
                onClick = onSearch,
                modifier = Modifier.trackLeftFocus(leftFocusTracker),
            ) {
                // 把还在拼音缓冲里的字母落成正文并立即搜索。
                Text("搜索", style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(
                width = sysWidth,
                height = keyHeight,
                onClick = onSystemKeyboard,
                modifier = Modifier.trackLeftFocus(leftFocusTracker),
            ) {
                Text(localizedStringResource(StringsR.string.home_system_keyboard), style = MaterialTheme.typography.bodySmall, color = it, maxLines = 1)
            }
        }
        KeyButton(
            width = fullWidth,
            height = 34.dp,
            onClick = onCollapse,
            modifier = Modifier.trackLeftFocus(leftFocusTracker),
        ) {
            Text(localizedStringResource(StringsR.string.home_search_close_keyboard), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = it)
        }
        // 当前拼音缓冲：让用户知道这些字母还没上屏。
        if (!qwerty && composing.isNotEmpty()) {
            Text(
                text = "拼音 “$composing”" + if (candidateCount == 0) " · 无匹配，可点「搜索」按字母查" else "",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

/** 拼音候选词：一个汉字/词组一格，遥控器左右移动选择。 */
@Composable
private fun CandidateChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White else Color(0xFF1C1C1F))
            .then(if (focused) Modifier else Modifier.border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(6.dp)))
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun KeyButton(
    width: Dp,
    height: Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White else Color(0xFF1C1C1F))
            .then(if (focused) Modifier else Modifier.border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(4.dp)))
            .tvClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (focused) Color.Black else Color.White)
    }
}

/** Candado sobre la carátula: el título no es infantil; si ya se pidió, lo indica. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LockedOverlay(asked: Boolean) {
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.align(Alignment.Center).padding(8.dp),
    ) {
        Icon(if (asked) AppIcons.Check else AppIcons.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = localizedStringResource(if (asked) StringsR.string.home_requested else StringsR.string.home_locked),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}
