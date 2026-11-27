package com.shieldstream.tv.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.AudioCodec
import com.shieldstream.tv.model.DeviceOptimization
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.SourceExclusions
import com.shieldstream.tv.settings.update.UpdateCard
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.strings.sectionResource
import com.shieldstream.tv.ui.component.ProvideMinimalScroll
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.component.restartApp
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

private enum class SettingsSectionTab(@param:StringRes val labelRes: Int) {
    SOURCES(StringsR.string.settings_cat_sources),
    APPEARANCE(StringsR.string.settings_home),
    PLAYBACK(StringsR.string.settings_playback),
    SYSTEM(StringsR.string.settings_about),
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenPhoneConfig: () -> Unit = {},
    onOpenAddons: () -> Unit = {},
    onOpenCmsSources: () -> Unit = {},
    onOpenRequests: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = state as? SettingsUiState.Ready
    val context = LocalContext.current
    val cmsSources by viewModel.cmsSources.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val sourceExclusions by viewModel.sourceExclusions.collectAsStateWithLifecycle()
    val brokenSourceCount by viewModel.brokenSourceCount.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val pendingRequestCount by viewModel.pendingRequestCount.collectAsStateWithLifecycle()
    val matchContentFrameRate by viewModel.matchContentFrameRate.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(SettingsBackground),
    ) {
        when (val s = state) {
            SettingsUiState.Loading -> Unit
            is SettingsUiState.Ready -> {
                NetflixSettingsLayout(
                    state = s,
                    onBack = onBack,
                    onEditKey = { service ->
                        if (service == ApiService.AIOSTREAMS) onOpenAddons() else viewModel.openEditor(service)
                    },
                    onBuffer = viewModel::changeBuffer,
                    onSeekMode = viewModel::setSeekThumbnails,
                    onDebrid = viewModel::setDebrid,
                    onDeviceOptimization = viewModel::setDeviceOptimization,
                    onExperimentalSync = viewModel::setExperimentalSubtitleSync,
                    matchContentFrameRate = matchContentFrameRate,
                    onMatchContentFrameRate = viewModel::setMatchContentFrameRate,
                    onOpenSetup = onOpenSetup,
                    onOpenPhoneConfig = onOpenPhoneConfig,
                    onOpenCmsSources = onOpenCmsSources,
                    cmsEnabledCount = cmsSources.count { it.enabled },
                    cmsTotalCount = cmsSources.size,
                    onOpenRequests = onOpenRequests,
                    showRequests = isAdmin,
                    pendingRequestCount = pendingRequestCount,
                    onLanguage = { language -> viewModel.setLanguage(language) { restartApp(context) } },
                    providers = providers,
                    onToggleProvider = viewModel::toggleProvider,
                    sourceExclusions = sourceExclusions,
                    brokenSourceCount = brokenSourceCount,
                    onRestoreBrokenSources = viewModel::restoreBrokenSources,
                    onToggleQuality = viewModel::toggleExcludedQuality,
                    onToggleHdrType = viewModel::toggleExcludedHdrType,
                    onToggleAudioCodec = viewModel::toggleExcludedAudioCodec,
                )
                s.editor?.let { editor ->
                    KeyEditorDialog(
                        editor = editor,
                        onSave = viewModel::saveKey,
                        onRemove = viewModel::removeKey,
                        onClose = viewModel::closeEditor,
                        onAllDebridConnected = viewModel::completeAllDebridConnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetflixSettingsLayout(
    state: SettingsUiState.Ready,
    onBack: () -> Unit,
    onEditKey: (ApiService) -> Unit,
    onBuffer: (Int) -> Unit,
    onSeekMode: (Boolean) -> Unit,
    onDebrid: (DebridService) -> Unit,
    onDeviceOptimization: (DeviceOptimization) -> Unit,
    onExperimentalSync: (Boolean) -> Unit,
    matchContentFrameRate: Boolean,
    onMatchContentFrameRate: (Boolean) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenPhoneConfig: () -> Unit,
    onOpenCmsSources: () -> Unit,
    cmsEnabledCount: Int,
    cmsTotalCount: Int,
    onOpenRequests: () -> Unit,
    showRequests: Boolean,
    pendingRequestCount: Int,
    onLanguage: (AppLanguage) -> Unit,
    providers: Set<SourceProvider>,
    onToggleProvider: (SourceProvider) -> Unit,
    sourceExclusions: SourceExclusions,
    brokenSourceCount: Int,
    onRestoreBrokenSources: () -> Unit,
    onToggleQuality: (Quality) -> Unit,
    onToggleHdrType: (HdrType) -> Unit,
    onToggleAudioCodec: (AudioCodec) -> Unit,
) {
    var activeTab by rememberSaveable { mutableStateOf(SettingsSectionTab.SOURCES) }
    var rightPaneFocused by remember { mutableStateOf(false) }
    val railRequesters = remember { SettingsSectionTab.entries.associateWith { FocusRequester() } }
    val contentFirstRequester = remember { FocusRequester() }
    val serviceRequesters = remember { ApiService.entries.associateWith { FocusRequester() } }
    var lastEdited by rememberSaveable { mutableStateOf<ApiService?>(null) }
    var qualityExclusionsExpanded by rememberSaveable { mutableStateOf(false) }
    var videoExclusionsExpanded by rememberSaveable { mutableStateOf(false) }
    var audioExclusionsExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (state.editor == null) {
            railRequesters[activeTab]?.requestFocusWithRetry()
        }
    }

    LaunchedEffect(state.editor == null) {
        if (state.editor == null && lastEdited != null) {
            withFrameNanos { }
            runCatching { serviceRequesters.getValue(lastEdited!!).requestFocus() }
            lastEdited = null
        }
    }

    LaunchedEffect(activeTab) {
        listState.scrollToItem(0)
    }

    val debridUi = state.services.first { it.service == state.debrid.apiService }
    val otherServices = state.services.filter { !it.service.isDebrid }
    val providerSections = SourceProvider.entries.map { it to localizedStringResource(it.sectionResource) }

    Row(
        Modifier
            .fillMaxSize()
            .focusProperties { canFocus = state.editor == null }
            .onBackKey(enabled = state.editor == null) {
                if (rightPaneFocused) {
                    scope.launch { railRequesters[activeTab]?.requestFocusWithRetry() }
                } else {
                    onBack()
                }
            },
    ) {
        // Left Netflix TV Settings Sidebar
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(Color(0xFF09090B))
                .padding(start = 32.dp, end = 20.dp, top = 40.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(22.dp)
                        .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = localizedStringResource(StringsR.string.settings_settings),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = localizedStringResource(StringsR.string.settings_service_keys_playback_and_app_version),
                style = MaterialTheme.typography.bodySmall,
                color = SettingsMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsSectionTab.entries.forEach { tab ->
                    SettingsNavTabRow(
                        label = localizedStringResource(tab.labelRes),
                        selected = tab == activeTab,
                        onFocused = { activeTab = tab },
                        onEnterPane = { scope.launch { contentFirstRequester.requestFocusWithRetry() } },
                        modifier = Modifier.focusRequester(railRequesters.getValue(tab)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = localizedStringResource(StringsR.string.player_back_to_return_to_the_movie),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.42f),
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        // Hairline vertical divider
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))

        // Right Content Pane
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { rightPaneFocused = it.hasFocus },
        ) {
            ProvideMinimalScroll(margin = 48.dp) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 56.dp, vertical = 38.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "pane_header") {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(
                                text = localizedStringResource(activeTab.labelRes),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                        }
                    }

                    when (activeTab) {
                        SettingsSectionTab.SOURCES -> {
                            item(key = "sec_cms_sources") { SectionTitle(localizedStringResource(StringsR.string.settings_cms_sources_title)) }
                            item(key = "cms_sources_entry") {
                                CmsSourcesEntryCard(
                                    enabledCount = cmsEnabledCount,
                                    totalCount = cmsTotalCount,
                                    onClick = onOpenCmsSources,
                                    modifier = Modifier.focusRequester(contentFirstRequester),
                                )
                            }
                            item(key = "sec_debrid") { SectionTitle(localizedStringResource(StringsR.string.settings_debrid_service)) }
                            item(key = "debrid_selector") {
                                DebridSelectorCard(
                                    active = state.debrid,
                                    onChange = onDebrid,
                                )
                            }
                            item(key = "debrid_key") {
                                ServiceCard(
                                    ui = debridUi,
                                    onClick = {
                                        lastEdited = debridUi.service
                                        onEditKey(debridUi.service)
                                    },
                                    modifier = Modifier.focusRequester(serviceRequesters.getValue(debridUi.service)),
                                )
                            }
                            item(key = "debrid_hint") {
                                Text(
                                    if (state.debrid.viaTorrentio && state.debrid.supportsDirectMagnetResolution) {
                                        localizedStringResource(StringsR.string.settings_native_debrid_explanation, state.debrid.title)
                                    } else if (state.debrid.viaTorrentio) {
                                        localizedStringResource(StringsR.string.settings_torrentio_debrid_explanation, state.debrid.title)
                                    } else {
                                        localizedStringResource(StringsR.string.settings_real_debrid_connects_directly_from_the_shield)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SettingsMuted,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                )
                            }
                            item(key = "sec_other_services") { SectionTitle(localizedStringResource(StringsR.string.settings_other_services)) }
                            items(otherServices, key = { it.service.name }) { ui ->
                                ServiceCard(
                                    ui = ui,
                                    onClick = {
                                        lastEdited = ui.service
                                        onEditKey(ui.service)
                                    },
                                    modifier = Modifier.focusRequester(serviceRequesters.getValue(ui.service)),
                                )
                            }
                            item(key = "other_services_hint") {
                                Text(
                                    localizedStringResource(StringsR.string.settings_torrentio_sources_opensubtitles_and_podnapisi_subtitles_don),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SettingsMuted,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                )
                            }
                            item(key = "sec_finders") { SectionTitle(localizedStringResource(StringsR.string.settings_source_finders)) }
                            items(providerSections.distinctBy { it.second }, key = { "provider_${it.first.name}" }) { (provider, section) ->
                                val enabled = providerSections.filter { it.second == section }.all { it.first in providers }
                                ProviderCard(provider, enabled = enabled, onClick = { onToggleProvider(provider) })
                            }
                            if (brokenSourceCount > 0) {
                                item(key = "broken_sources") {
                                    BrokenSourcesCard(count = brokenSourceCount, onRestore = onRestoreBrokenSources)
                                }
                            }
                            item(key = "sec_exclusions") { SectionTitle(localizedStringResource(StringsR.string.settings_source_exclusions)) }
                            item(key = "exclusions_hint") {
                                Text(
                                    localizedStringResource(StringsR.string.settings_source_exclusions_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SettingsMuted,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                )
                            }
                            item(key = "excluded-quality-header") {
                                SourceExclusionGroupCard(
                                    title = localizedStringResource(StringsR.string.settings_quality),
                                    summary = acceptedSummary(
                                        acceptedLabels = Quality.entries.filterNot { it in sourceExclusions.qualities }.map { qualityLabel(it) },
                                        acceptsAll = sourceExclusions.qualities.isEmpty(),
                                    ),
                                    expanded = qualityExclusionsExpanded,
                                    onClick = { qualityExclusionsExpanded = !qualityExclusionsExpanded },
                                )
                            }
                            if (qualityExclusionsExpanded) {
                                items(Quality.entries, key = { "excluded-quality:${it.name}" }) { quality ->
                                    SourceExclusionCard(
                                        label = qualityLabel(quality),
                                        excluded = quality in sourceExclusions.qualities,
                                        onClick = { onToggleQuality(quality) },
                                    )
                                }
                            }
                            item(key = "excluded-hdr-header") {
                                SourceExclusionGroupCard(
                                    title = localizedStringResource(StringsR.string.settings_video_features),
                                    summary = acceptedSummary(
                                        acceptedLabels = HdrType.entries.filterNot { it in sourceExclusions.hdrTypes }.map { it.label },
                                        acceptsAll = sourceExclusions.hdrTypes.isEmpty(),
                                    ),
                                    expanded = videoExclusionsExpanded,
                                    onClick = { videoExclusionsExpanded = !videoExclusionsExpanded },
                                )
                            }
                            if (videoExclusionsExpanded) {
                                items(HdrType.entries, key = { "excluded-hdr:${it.name}" }) { hdrType ->
                                    SourceExclusionCard(
                                        label = hdrType.label,
                                        excluded = hdrType in sourceExclusions.hdrTypes,
                                        onClick = { onToggleHdrType(hdrType) },
                                    )
                                }
                            }
                            item(key = "excluded-audio-header") {
                                SourceExclusionGroupCard(
                                    title = localizedStringResource(StringsR.string.settings_audio_codecs),
                                    summary = acceptedSummary(
                                        acceptedLabels = AudioCodec.entries.filterNot { it in sourceExclusions.audioCodecs }.map { it.label },
                                        acceptsAll = sourceExclusions.audioCodecs.isEmpty(),
                                    ),
                                    expanded = audioExclusionsExpanded,
                                    onClick = { audioExclusionsExpanded = !audioExclusionsExpanded },
                                )
                            }
                            item(key = "audio_exclusions_hint") {
                                Text(
                                    localizedStringResource(StringsR.string.settings_audio_codec_exclusions_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SettingsMuted,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            if (audioExclusionsExpanded) {
                                items(AudioCodec.entries, key = { "excluded-audio:${it.name}" }) { codec ->
                                    SourceExclusionCard(
                                        label = codec.label,
                                        excluded = codec in sourceExclusions.audioCodecs,
                                        onClick = { onToggleAudioCodec(codec) },
                                    )
                                }
                            }
                        }

                        SettingsSectionTab.APPEARANCE -> {
                            item(key = "home_row_limit") {
                                Box(Modifier.focusRequester(contentFirstRequester)) { HomeRowLimitCard() }
                            }
                            item(key = "lightweight") { LightweightModeCard() }
                            item(key = "intro_always") { IntroAlwaysCard() }
                            item(key = "soft_backdrops") { SoftBackdropsCard() }
                            item(key = "high_res_backdrops") { HighResBackdropsCard() }
                            item(key = "sec_language") { SectionTitle("Idioma · Language") }
                            item(key = "language") { LanguageCard(current = state.language, onApply = onLanguage) }
                        }

                        SettingsSectionTab.PLAYBACK -> {
                            item(key = "play_priority") {
                                Box(Modifier.focusRequester(contentFirstRequester)) { PlaybackPriorityCard() }
                            }
                            item(key = "preferred_quality") { PreferredQualityCard() }
                            item(key = "switch_picked") { SwitchPickedSourceCard() }
                            item(key = "smart_ad_filter") { SmartAdFilterCard() }
                            item(key = "frame_rate") { MatchContentFrameRateCard(enabled = matchContentFrameRate, onChange = onMatchContentFrameRate) }
                            item(key = "exp_sync") { ExperimentalSyncCard(enabled = state.experimentalSubtitleSync, onChange = onExperimentalSync) }
                            item(key = "device_opt") {
                                DeviceOptimizationCard(
                                    optimization = state.optimization,
                                    capabilities = state.capabilities,
                                    memoryProfile = state.memoryProfile,
                                    onChange = onDeviceOptimization,
                                )
                            }
                            item(key = "seek_mode") {
                                SeekModeCard(
                                    thumbnails = state.seekThumbnails,
                                    enabled = state.optimization == DeviceOptimization.CUSTOM,
                                    onChange = onSeekMode,
                                )
                            }
                            item(key = "auto_next") { AutoNextCard() }
                            item(key = "auto_skip") { AutoSkipIntroCard() }
                            item(key = "show_cast") { ShowCastCard() }
                            item(key = "tracks_style") { TracksPanelStyleCard() }
                            item(key = "download_storage") { DownloadStorageCard() }
                            item(key = "episode_notif") { EpisodeNotificationsCard() }
                            item(key = "buffer") {
                                BufferCard(
                                    seconds = state.bufferSeconds,
                                    enabled = state.optimization == DeviceOptimization.CUSTOM,
                                    onChange = onBuffer,
                                )
                            }
                        }

                        SettingsSectionTab.SYSTEM -> {
                            item(key = "about") {
                                Box(Modifier.focusRequester(contentFirstRequester)) { AboutCard() }
                            }
                            item(key = "update") { UpdateCard() }
                            if (showRequests) {
                                item(key = "family_requests") { FamilyRequestsCard(count = pendingRequestCount, onClick = onOpenRequests) }
                            }
                            item(key = "phone_config") { PhoneConfigCard(onClick = onOpenPhoneConfig) }
                            item(key = "setup_guide") { SetupGuideCard(onClick = onOpenSetup) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavTabRow(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onEnterPane: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.65f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onEnterPane()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .tvClickable(onClick = onEnterPane)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(
                    when {
                        focused -> AppColors.Accent
                        selected -> AppColors.Accent
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CmsSourcesEntryCard(
    enabledCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusCard(modifier = modifier, onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = localizedStringResource(StringsR.string.settings_cms_sources_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = localizedStringResource(StringsR.string.settings_cms_sources_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsMuted,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = localizedStringResource(StringsR.string.settings_cms_sources_count, enabledCount, totalCount),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabledCount > 0) SettingsGreen else SettingsAmber,
            )
        }
    }
}
