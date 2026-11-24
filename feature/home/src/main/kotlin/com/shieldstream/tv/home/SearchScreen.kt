package com.shieldstream.tv.home

import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.PosterCard
import com.shieldstream.tv.ui.component.tvClickable
import kotlinx.coroutines.delay
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.ActionMenuDialog
import androidx.compose.foundation.lazy.grid.GridItemSpan

private const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"
private const val KEY_COLUMNS = 6

// 5 列大键位，遥控器上下左右移动更少、更好按。
private val PINYIN_ROWS = listOf("qwert", "yuiop", "asdfg", "hjklz", "xcvbn", "m1234")
private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

@Composable
fun SearchScreen(
    onItemClick: (CatalogItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var lockedPick by remember { mutableStateOf<CatalogItem?>(null) }
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
    val qwerty by viewModel.qwerty.collectAsStateWithLifecycle()
    val composing by viewModel.composing.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()

    val firstKey = remember { FocusRequester() }
    val layoutKey = remember { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val backOnCard = lastCardKey != null && run {
            repeat(4) {
                if (runCatching { cardRequester.requestFocus() }.isSuccess) return@run true
                delay(60)
            }
            false
        }
        if (!backOnCard) runCatching { firstKey.requestFocus() }
    }

    // 预热拼音字典（约 1MB / 4.7 万词条），在后台线程解析，避免首次按键卡顿。
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PinyinEngine.ensureLoaded(context)
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
    LaunchedEffect(query, composing, systemKeyboard) {
        if (!systemKeyboard && field.text != query + composing) {
            field = TextFieldValue(query + composing, TextRange((query + composing).length))
        }
    }
    LaunchedEffect(systemKeyboard) {
        if (systemKeyboard) {
            withFrameNanos { }
            runCatching { fieldRequester.requestFocus() }
            keyboard?.show()
        }
    }
    fun closeSystemKeyboard() {
        keyboard?.hide()
        systemKeyboard = false
        runCatching { layoutKey.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .width(if (qwerty) 452.dp else 330.dp)
                .fillMaxHeight()
                .padding(start = 40.dp, top = 40.dp, end = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1C1C1F))
                    .then(if (systemKeyboard) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
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
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
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
                    if (query.isEmpty()) {
                        Text(
                            text = localizedStringResource(StringsR.string.home_search),
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 候选词条固定在键盘上方，且始终占位：否则候选出现/消失会把按键推上推下，
            // 遥控器用户点一次 G 再点 Y 就点到别的键上了。
            if (!qwerty) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    itemsIndexed(candidates) { _, cand ->
                        CandidateChip(text = cand, onClick = { viewModel.commitCandidate(cand) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OnScreenKeyboard(
                qwerty = qwerty,
                composing = composing,
                candidateCount = if (qwerty) 0 else candidates.size,
                firstKeyRequester = firstKey,
                layoutKeyRequester = layoutKey,
                onChar = { c ->
                    if (qwerty) viewModel.type(c) else viewModel.typeLetter(c)
                },
                onBackspace = {
                    // En modo pinyin, primero borra el buffer; si está vacío, borra la consulta.
                    if (qwerty || !viewModel.backspaceComposing()) viewModel.backspace()
                },
                onClear = viewModel::clear,
                onToggleLayout = viewModel::toggleKeyboardLayout,
                onSystemKeyboard = { systemKeyboard = true },
                onSearch = viewModel::flushComposingToQuery,
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 40.dp, end = 24.dp)) {
            val header = when (val s = state) {
                SearchUiState.Loading -> localizedStringResource(StringsR.string.home_searching)
                is SearchUiState.Suggestions -> localizedStringResource(StringsR.string.home_most_watched_today)
                is SearchUiState.Results -> localizedStringResource(StringsR.string.home_search_results_for, s.query)
                is SearchUiState.Empty -> localizedStringResource(StringsR.string.home_no_results_for, s.query)
                is SearchUiState.Error -> s.message
            }
            Text(
                text = header,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            val items = when (val s = state) {
                is SearchUiState.Suggestions -> s.items
                is SearchUiState.Results -> s.items
                else -> emptyList()
            }
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.key }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onFocused = { lastCardKey = item.key },
                        width = 118.dp,
                        modifier = if (item.key == lastCardKey) Modifier.focusRequester(cardRequester) else Modifier,
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
                    items(locked, key = { "locked_" + it.key }) { item ->
                        val asked = item.key in pending
                        PosterCard(
                            item = item,
                            onClick = { if (!asked) lockedPick = item },
                            onFocused = { lastCardKey = "locked_" + item.key },
                            width = 118.dp,
                            modifier = if ("locked_" + item.key == lastCardKey) Modifier.focusRequester(cardRequester) else Modifier,
                            overlay = { LockedOverlay(asked) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnScreenKeyboard(
    qwerty: Boolean,
    composing: String,
    candidateCount: Int,
    firstKeyRequester: FocusRequester,
    layoutKeyRequester: FocusRequester,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onToggleLayout: () -> Unit,
    onSystemKeyboard: () -> Unit,
    onSearch: () -> Unit,
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
    // 列宽 = 内容区宽度（Column 宽度 - 左右 padding 56dp），按最长一行等分算键宽。
    val columnWidth = if (qwerty) 452.dp else 300.dp
    val contentWidth = columnWidth - 56.dp
    val perRow = rows.first().length
    val keyGap = if (qwerty) 4.dp else 5.dp
    val keyWidth = (contentWidth - keyGap * (perRow - 1)) / perRow
    val fullWidth = keyWidth * perRow + keyGap * (perRow - 1)
    // 拼音模式 6 行字母 + 候选条 + 2 行功能键要在一屏 1080 里放下，键高缩到 36dp。
    val keyHeight = if (qwerty) 40.dp else 36.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (qwerty) 6.dp else 5.dp)) {
        rows.forEachIndexed { rowIndex, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                rowKeys.forEachIndexed { colIndex, c ->
                    val mod = if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstKeyRequester) else Modifier
                    KeyButton(width = keyWidth, height = keyHeight, onClick = { onChar(c) }, modifier = mod) {
                        Text(c.uppercase(), style = MaterialTheme.typography.titleMedium, color = it)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
            val backWidth = keyWidth * 1.0f + keyGap
            val clearWidth = keyWidth * 1.5f + keyGap
            KeyButton(width = fullWidth - backWidth - clearWidth - keyGap * 2, height = keyHeight, onClick = { onChar(' ') }) {
                Text(localizedStringResource(StringsR.string.home_space), style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = backWidth, height = keyHeight, onClick = onBackspace) {
                Icon(AppIcons.Backspace, contentDescription = localizedStringResource(StringsR.string.home_clear), tint = it, modifier = Modifier.size(20.dp))
            }
            KeyButton(width = clearWidth, height = keyHeight, onClick = onClear) {
                Text(localizedStringResource(StringsR.string.home_clear), style = MaterialTheme.typography.bodyMedium, color = it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
            val sideWidth = fullWidth * 0.38f - keyGap
            val sysWidth = fullWidth - sideWidth * 2 - keyGap * 2
            KeyButton(
                width = sideWidth,
                height = keyHeight,
                onClick = {
                    toggled = true
                    onToggleLayout()
                },
                modifier = Modifier.focusRequester(layoutKeyRequester),
            ) {
                Text(if (qwerty) "拼音" else "英文", style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = sideWidth, height = keyHeight, onClick = onSearch) {
                // 把还在拼音缓冲里的字母落成正文并立即搜索。
                Text("搜索", style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = sysWidth, height = keyHeight, onClick = onSystemKeyboard) {
                Text(localizedStringResource(StringsR.string.home_system_keyboard), style = MaterialTheme.typography.bodySmall, color = it, maxLines = 1)
            }
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
private fun CandidateChip(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
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
