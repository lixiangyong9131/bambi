package com.shieldstream.tv.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shieldstream.tv.domain.EpisodeTarget
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Category
import com.shieldstream.tv.model.ContinueInfo
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.TopBar
import com.shieldstream.tv.ui.component.TopTab
import com.shieldstream.tv.ui.component.downloadIndicatorState
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onExit: () -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
    onContinue: (CatalogItem, ContinueInfo) -> Unit,
    onOpenNewEpisode: (CatalogItem, Episode) -> Unit,
    onUpNext: (CatalogItem, Episode) -> Unit,
    onPlaySomething: (CatalogItem, EpisodeTarget?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    profileInitial: String? = null,
    profileAvatar: Int = 0,
    profileImage: String? = null,
    onSwitchProfile: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(TopTab.HOME) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    // Modified build: immersive browsing - top bar hides after a tab is chosen, Back restores it.
    var immersive by rememberSaveable { mutableStateOf(false) }
    // Ignore the automatic initial card focus; only real DPAD navigation arms immersive mode.
    var immersiveArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(900); immersiveArmed = true }
    var focusMenuAfterSwitch by remember { mutableStateOf(false) }
    var topBarFocused by remember { mutableStateOf(false) }
    var exitPromptVisible by remember { mutableStateOf(false) }
    var backToTopTick by remember { mutableStateOf(0) }
    val topBarRequester = remember { FocusRequester() }
    val filter = when (tab) {
        TopTab.TV -> HomeFilter.TV
        TopTab.MOVIES -> HomeFilter.MOVIES
        TopTab.ANIME -> HomeFilter.ANIME
        else -> HomeFilter.ALL
    }
    val episodeCenter: EpisodeCenterViewModel? = if (tab == TopTab.EPISODES) hiltViewModel() else null
    val downloadsViewModel: DownloadsViewModel = hiltViewModel()
    val downloadList by downloadsViewModel.downloads.collectAsState()
    val episodeState by (episodeCenter?.uiState?.collectAsState() ?: remember { mutableStateOf<EpisodeCenterUiState>(EpisodeCenterUiState.Loading) })
    LaunchedEffect(tab) { if (tab == TopTab.EPISODES) episodeCenter?.activate() }

    LaunchedEffect(exitPromptVisible) {
        if (exitPromptVisible) {
            delay(EXIT_PROMPT_TIMEOUT_MS)
            exitPromptVisible = false
        }
    }
    LaunchedEffect(topBarFocused, category, tab) {
        if (!topBarFocused || category != null) {
            exitPromptVisible = false
        }
    }

    val scope = rememberCoroutineScope()
    val focusTopBar: () -> Unit = {
        scope.launch { topBarRequester.requestFocusWithRetry() }
    }
    val showTopBarAndFocus: () -> Unit = {
        if (immersive) {
            immersive = false
        }
        focusTopBar()
    }
    val onBackToTop: () -> Unit = {
        exitPromptVisible = false
        backToTopTick++
        showTopBarAndFocus()
    }
    val onEnterImmersive: () -> Unit = { if (immersiveArmed) immersive = true }
    val handleRootBack: () -> Unit = {
        when (resolveMainBackOutcome(category != null, topBarFocused, exitPromptVisible)) {
            MainBackOutcome.CLOSE_CATEGORY -> category = null
            MainBackOutcome.BACK_TO_TOP -> {
                exitPromptVisible = false
                if (tab == TopTab.SEARCH || tab == TopTab.MY_LIST || tab == TopTab.EPISODES) {
                    showTopBarAndFocus()
                } else {
                    onBackToTop()
                }
            }
            MainBackOutcome.ARM_EXIT_PROMPT -> exitPromptVisible = true
            MainBackOutcome.EXIT_APP -> {
                exitPromptVisible = false
                onExit()
            }
        }
    }

    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        TopBar(
            selected = tab,
            onSelect = { selected ->
                exitPromptVisible = false
                if (selected != tab) {
                    tab = selected
                    focusMenuAfterSwitch = selected != TopTab.SEARCH && selected != TopTab.MY_LIST && selected != TopTab.EPISODES
                    immersive = true
                }
            },
            onSettings = onOpenSettings,
            selectedRequester = topBarRequester,
            newEpisodeCount = (episodeState as? EpisodeCenterUiState.Content)?.newEpisodeCount ?: 0,
            modifier = modifier.onFocusChanged { topBarFocused = it.hasFocus },
            profileInitial = profileInitial,
            profileAvatar = profileAvatar,
            profileImage = profileImage,
            onProfile = onSwitchProfile,
            downloads = downloadIndicatorState(downloadList),
            onDownloads = onOpenDownloads,
        )
    }

    // Fallback for system back / predictive back gestures or unfocused loading states.
    BackHandler(enabled = true) {
        handleRootBack()
    }

    val saveable = rememberSaveableStateHolder()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Catch Key.Back / Key.Escape directly inside the Compose focus tree so TV remotes
            // always trigger back-to-top / double-back exit even when TopBar holds focus.
            .onBackKey(enabled = category == null) { handleRootBack() },
    ) {
        val openCategory = category
        when {
            openCategory != null -> CategoryScreen(
                category = openCategory,
                filter = filter,
                onItemClick = onOpenItem,
                onBack = { category = null },
            )
            tab == TopTab.SEARCH || tab == TopTab.MY_LIST || tab == TopTab.EPISODES -> {
                Column(Modifier.fillMaxSize().onBackKey(enabled = !topBarFocused) { focusTopBar() }) {
                    topBar(Modifier)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            TopTab.SEARCH -> saveable.SaveableStateProvider("tab_${tab.name}") { SearchScreen(onItemClick = onOpenItem) }
                            TopTab.EPISODES -> EpisodeCenterScreen(onOpenEpisode = onOpenNewEpisode, viewModel = episodeCenter!!)
                            else -> saveable.SaveableStateProvider("tab_${tab.name}") { MyListScreen(onItemClick = onOpenItem) }
                        }
                    }
                }
            }
            else -> saveable.SaveableStateProvider("tab_${tab.name}") {
                HomeScreen(
                    filter = filter,
                    onItemClick = onOpenItem,
                    onContinueClick = onContinue,
                    onNewEpisodeClick = onOpenNewEpisode,
                    onUpNextClick = onUpNext,
                    onPlaySomething = onPlaySomething,
                    onOpenCategory = { category = it },
                    topBar = topBar,
                    topBarRequester = topBarRequester,
                    focusTopBar = focusTopBar,
                    preferTopBarFocus = focusMenuAfterSwitch,
                    onTopBarFocusConsumed = { focusMenuAfterSwitch = false },
                    immersive = immersive,
                    topBarFocused = topBarFocused,
                    backToTopTick = backToTopTick,
                    onExitTop = showTopBarAndFocus,
                    onBackToTop = onBackToTop,
                    onEnterImmersive = onEnterImmersive,
                )
            }
        }
        AnimatedVisibility(
            visible = exitPromptVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        ) {
            // Keep non-focusable so TopBar stays focused and a second Back immediately exits.
            Box(
                Modifier
                    .background(Color(0xE6141414), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = localizedStringResource(StringsR.string.home_press_back_to_exit),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

private const val EXIT_PROMPT_TIMEOUT_MS = 2_500L

internal enum class MainBackOutcome {
    CLOSE_CATEGORY,
    BACK_TO_TOP,
    ARM_EXIT_PROMPT,
    EXIT_APP,
}

internal fun resolveMainBackOutcome(
    categoryOpen: Boolean,
    topBarFocused: Boolean,
    exitArmed: Boolean,
): MainBackOutcome = when {
    categoryOpen -> MainBackOutcome.CLOSE_CATEGORY
    !topBarFocused -> MainBackOutcome.BACK_TO_TOP
    exitArmed -> MainBackOutcome.EXIT_APP
    else -> MainBackOutcome.ARM_EXIT_PROMPT
}

internal fun shouldRequestExit(categoryOpen: Boolean, topBarFocused: Boolean): Boolean = !categoryOpen && topBarFocused
