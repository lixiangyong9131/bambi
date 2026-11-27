package com.shieldstream.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CmsSource
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.ActionMenuDialog
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.ProvideMinimalScroll
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.AppShapes

@Composable
fun CmsSourcesScreen(
    onBack: () -> Unit,
    onOpenPhoneConfig: () -> Unit,
    viewModel: CmsSourcesViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val testStatus by viewModel.testStatus.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<CmsSource?>(null) }
    val firstRequester = remember { FocusRequester() }

    LaunchedEffect(actionsFor, editor) {
        if (actionsFor != null || editor != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }

    val enabledCount = sources.count { it.enabled }

    Box(
        Modifier
            .fillMaxSize()
            .background(SettingsBackground)
            .onBackKey(enabled = editor == null) {
                if (actionsFor != null) actionsFor = null else onBack()
            },
    ) {
        ProvideMinimalScroll(margin = 56.dp) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 96.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height(26.dp)
                                        .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = localizedStringResource(StringsR.string.settings_cms_sources_title),
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            }
                            Text(
                                text = localizedStringResource(StringsR.string.settings_cms_sources_count, enabledCount, sources.size),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (enabledCount > 0) SettingsGreen else SettingsAmber,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = localizedStringResource(StringsR.string.settings_cms_sources_manage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsMuted,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }

                item(key = "top_actions") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ActionTile(
                            icon = AppIcons.Add,
                            title = localizedStringResource(StringsR.string.settings_cms_sources_add),
                            subtitle = localizedStringResource(StringsR.string.settings_cms_sources_add_hint),
                            onClick = { viewModel.openEditor() },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(firstRequester),
                        )
                        ActionTile(
                            icon = AppIcons.List,
                            title = localizedStringResource(StringsR.string.settings_cms_sources_scan_import),
                            subtitle = localizedStringResource(StringsR.string.settings_cms_sources_scan_import_hint),
                            onClick = onOpenPhoneConfig,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                itemsIndexed(sources, key = { _, src -> src.id }) { _, source ->
                    CmsSourceCard(
                        source = source,
                        testStatus = testStatus[source.id],
                        onClick = { actionsFor = source },
                    )
                }

                item(key = "reset_defaults") {
                    Spacer(Modifier.height(4.dp))
                    FocusCard(onClick = viewModel::resetDefaults) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = LineIcons.Settings,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = localizedStringResource(StringsR.string.settings_cms_sources_reset),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White,
                                )
                                Text(
                                    text = localizedStringResource(StringsR.string.settings_cms_sources_reset_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SettingsMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        actionsFor?.let { selected ->
            val current = sources.firstOrNull { it.id == selected.id } ?: selected
            val index = sources.indexOfFirst { it.id == current.id }
            val menuActions = buildList {
                add(
                    ActionMenuItem(
                        text = localizedStringResource(
                            if (current.enabled) StringsR.string.settings_cms_sources_disable
                            else StringsR.string.settings_cms_sources_enable,
                        ),
                        icon = if (current.enabled) LineIcons.Close else AppIcons.Check,
                        onClick = {
                            actionsFor = null
                            viewModel.toggleEnabled(current.id)
                        },
                    ),
                )
                add(
                    ActionMenuItem(
                        text = localizedStringResource(StringsR.string.settings_cms_sources_test),
                        icon = AppIcons.Bolt,
                        onClick = {
                            actionsFor = null
                            viewModel.test(current.id)
                        },
                    ),
                )
                if (index > 0) {
                    add(
                        ActionMenuItem(
                            text = localizedStringResource(StringsR.string.settings_cms_sources_move_top),
                            icon = AppIcons.Trending,
                            onClick = {
                                actionsFor = null
                                viewModel.moveToTop(current.id)
                            },
                        ),
                    )
                }
                if (!current.builtIn) {
                    add(
                        ActionMenuItem(
                            text = localizedStringResource(StringsR.string.settings_cms_sources_edit),
                            icon = AppIcons.Edit,
                            onClick = {
                                actionsFor = null
                                viewModel.openEditor(current.id)
                            },
                        ),
                    )
                    add(
                        ActionMenuItem(
                            text = localizedStringResource(StringsR.string.settings_cms_sources_remove),
                            icon = LineIcons.Delete,
                            onClick = {
                                actionsFor = null
                                viewModel.remove(current.id)
                            },
                        ),
                    )
                }
            }
            ActionMenuDialog(
                title = current.name,
                onDismiss = { actionsFor = null },
                scrimAlpha = 0.94f,
                actions = menuActions,
            )
        }

        editor?.let { state ->
            CmsSourceEditorDialog(
                editor = state,
                onSave = viewModel::save,
                onClose = viewModel::closeEditor,
            )
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CardShape)
            .background(if (focused) CardFocused else CardColor, CardShape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = CardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .background(
                    if (focused) AppColors.Accent else Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(8.dp),
                ),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SettingsMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CmsSourceCard(
    source: CmsSource,
    testStatus: CmsSourceTestStatus?,
    onClick: () -> Unit,
) {
    FocusCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            if (source.enabled) SettingsGreen else Color.White.copy(alpha = 0.28f),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (source.enabled) Color.White else Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                SourcePill(
                    text = localizedStringResource(
                        if (source.builtIn) StringsR.string.settings_cms_sources_builtin
                        else StringsR.string.settings_cms_sources_custom,
                    ),
                    color = if (source.builtIn) Color.White.copy(alpha = 0.78f) else Color(0xFFFFB4B8),
                    background = if (source.builtIn) Color.White.copy(alpha = 0.08f) else AppColors.Accent.copy(alpha = 0.22f),
                )
                Spacer(Modifier.weight(1f))
                testStatus?.let { status ->
                    val statusText = if (status.checking) {
                        localizedStringResource(StringsR.string.settings_cms_sources_testing)
                    } else {
                        status.detail.orEmpty()
                    }
                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                status.checking -> SettingsMuted
                                status.error -> SettingsRed
                                else -> SettingsGreen
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
                Text(
                    text = localizedStringResource(
                        if (source.enabled) StringsR.string.settings_cms_sources_enabled
                        else StringsR.string.settings_cms_sources_disabled,
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (source.enabled) SettingsGreen else SettingsAmber,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (source.enabled) SettingsMuted else Color.White.copy(alpha = 0.36f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 21.dp),
            )
        }
    }
}

@Composable
private fun SourcePill(
    text: String,
    color: Color,
    background: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CmsSourceEditorDialog(
    editor: CmsSourceEditor,
    onSave: (name: String, url: String) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable(editor.id) { mutableStateOf(editor.initialName) }
    var url by rememberSaveable(editor.id) { mutableStateOf(editor.initialUrl) }
    val nameRequester = remember { FocusRequester() }
    val urlRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    var nameFocused by remember { mutableStateOf(false) }
    var urlFocused by remember { mutableStateOf(false) }

    LaunchedEffect(editor.id) {
        withFrameNanos { }
        runCatching { urlRequester.requestFocus() }
    }
    LaunchedEffect(editor.busy, editor.error) {
        if (!editor.busy && editor.error != null) {
            withFrameNanos { }
            runCatching { saveRequester.requestFocus() }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .onBackKey(enabled = !editor.busy, onBack = onClose),
    ) {
        Column(
            Modifier
                .width(740.dp)
                .background(CardColor, AppShapes.Panel)
                .border(1.dp, Color.White.copy(alpha = 0.14f), AppShapes.Panel)
                .padding(36.dp),
        ) {
            Text(
                text = localizedStringResource(
                    if (editor.id == null) StringsR.string.settings_cms_sources_add
                    else StringsR.string.settings_cms_sources_edit,
                ),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = localizedStringResource(StringsR.string.settings_cms_sources_add_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsMuted,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = localizedStringResource(StringsR.string.settings_cms_sources_name_label),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            EditorField(
                value = name,
                onValueChange = { name = it.replace("\n", "") },
                placeholder = "非凡影视 / 我的影视源",
                enabled = !editor.busy,
                focused = nameFocused,
                onFocusChanged = { nameFocused = it },
                monospace = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                onImeAction = { runCatching { urlRequester.requestFocus() } },
                onMoveDown = { runCatching { urlRequester.requestFocus() } },
                modifier = Modifier.focusRequester(nameRequester),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = localizedStringResource(StringsR.string.settings_cms_sources_url_label),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            EditorField(
                value = url,
                onValueChange = { url = it.replace("\n", "") },
                placeholder = "https://example.com/api.php/provide/vod",
                enabled = !editor.busy,
                focused = urlFocused,
                onFocusChanged = { urlFocused = it },
                monospace = true,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = { onSave(name, url) },
                onMoveUp = { runCatching { nameRequester.requestFocus() } },
                onMoveDown = { runCatching { saveRequester.requestFocus() } },
                modifier = Modifier.focusRequester(urlRequester),
            )
            Spacer(Modifier.height(14.dp))
            val feedback = when {
                editor.busy -> localizedStringResource(StringsR.string.settings_cms_sources_testing)
                editor.error != null -> editor.error
                else -> null
            }
            if (feedback != null) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (editor.error != null) SettingsRed else SettingsMuted,
                )
                Spacer(Modifier.height(14.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = localizedStringResource(StringsR.string.settings_cms_sources_save),
                    onClick = { onSave(name, url) },
                    enabled = !editor.busy,
                    modifier = Modifier.focusRequester(saveRequester),
                )
                TvButton(
                    text = localizedStringResource(StringsR.string.settings_cancel),
                    onClick = onClose,
                    enabled = !editor.busy,
                )
            }
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    monospace: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onMoveDown?.invoke()
                        onMoveDown != null
                    }
                    Key.DirectionUp -> {
                        onMoveUp?.invoke()
                        onMoveUp != null
                    }
                    else -> false
                }
            },
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0B0D), AppShapes.Control)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) Color.White else Color.White.copy(alpha = 0.22f),
                        shape = AppShapes.Control,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 17.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        color = Color.White.copy(alpha = 0.34f),
                    )
                }
                field()
            }
        },
    )
}
