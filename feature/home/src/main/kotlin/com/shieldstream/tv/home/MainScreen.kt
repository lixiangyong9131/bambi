package com.shieldstream.tv.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.shieldstream.tv.domain.EpisodeTarget
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.ContinueInfo
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.DownloadIndicator
import com.shieldstream.tv.ui.component.NavRail
import com.shieldstream.tv.ui.component.RailDestination
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
    @Suppress("UNUSED_PARAMETER") onPlaySomething: (CatalogItem, EpisodeTarget?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") profileInitial: String? = null,
    @Suppress("UNUSED_PARAMETER") profileAvatar: Int = 0,
    @Suppress("UNUSED_PARAMETER") profileImage: String? = null,
    @Suppress("UNUSED_PARAMETER") onSwitchProfile: () -> Unit = {},
) {
    var activeSection by rememberSaveable { mutableStateOf(RailDestination.HOME) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var railOpen by rememberSaveable { mutableStateOf(false) }
    var exitPromptVisible by remember { mutableStateOf(false) }
    var backToTopTick by remember { mutableStateOf(0) }
    var scrollJumpTick by remember { mutableStateOf(0) }
    var scrollToSectionRequest by remember { mutableStateOf<Pair<RailDestination, Int>?>(null) }

    val railRequester = remember { FocusRequester() }
    val feedRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val downloadsViewModel: DownloadsViewModel = hiltViewModel()
    val downloadList by downloadsViewModel.downloads.collectAsState()
    val scope = rememberCoroutineScope()

    val selectedRail = if (searchOpen) RailDestination.SEARCH else activeSection

    LaunchedEffect(railOpen) {
        if (railOpen) {
            railRequester.requestFocusWithRetry()
        }
    }
    LaunchedEffect(exitPromptVisible) {
        if (exitPromptVisible) {
            delay(EXIT_PROMPT_TIMEOUT_MS)
            exitPromptVisible = false
        }
    }
    LaunchedEffect(railOpen, searchOpen) {
        if (!railOpen || searchOpen) {
            exitPromptVisible = false
        }
    }

    val openRail: () -> Unit = {
        exitPromptVisible = false
        railOpen = true
    }

    val closeRailToContent: () -> Unit = {
        railOpen = false
        scope.launch {
            if (searchOpen) {
                searchRequester.requestFocusWithRetry()
            } else {
                feedRequester.requestFocusWithRetry()
            }
        }
    }

    val handleRootBack: () -> Unit = {
        when (
            resolveMainBackOutcome(
                categoryOpen = false,
                searchOpen = searchOpen,
                topBarFocused = railOpen,
                onHomeSection = activeSection == RailDestination.HOME,
                exitArmed = exitPromptVisible,
            )
        ) {
            MainBackOutcome.CLOSE_CATEGORY -> Unit
            MainBackOutcome.CLOSE_SEARCH -> {
                searchOpen = false
                railOpen = false
                exitPromptVisible = false
                scope.launch { feedRequester.requestFocusWithRetry() }
            }
            MainBackOutcome.OPEN_RAIL_IN_PLACE -> {
                exitPromptVisible = false
                railOpen = true
            }
            MainBackOutcome.BACK_TO_HOME -> {
                exitPromptVisible = false
                railOpen = false
                activeSection = RailDestination.HOME
                scrollJumpTick++
                scrollToSectionRequest = RailDestination.HOME to scrollJumpTick
            }
            MainBackOutcome.ARM_EXIT_PROMPT -> exitPromptVisible = true
            MainBackOutcome.EXIT_APP -> {
                exitPromptVisible = false
                onExit()
            }
        }
    }

    val saveable = rememberSaveableStateHolder()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onBackKey(enabled = true) { handleRootBack() },
    ) {
        // Handle system Back gesture on tablets / phones (gesture nav doesn't fire Key.Back).
        BackHandler(enabled = true) { handleRootBack() }
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { canFocus = !railOpen },
        ) {
            if (searchOpen) {
                saveable.SaveableStateProvider("tab_search") {
                    SearchScreen(
                        onItemClick = onOpenItem,
                        onQuickPlay = { item -> onPlaySomething(item, null) },
                        initialFocusRequester = searchRequester,
                        onOpenRail = openRail,
                    )
                }
            } else {
                saveable.SaveableStateProvider("unified_feed") {
                    UnifiedHomeScreen(
                        onItemClick = onOpenItem,
                        onContinueClick = onContinue,
                        onNewEpisodeClick = onOpenNewEpisode,
                        onUpNextClick = onUpNext,
                        onQuickPlay = { item -> onPlaySomething(item, null) },
                        onBackToTop = openRail,
                        contentFocusRequester = feedRequester,
                        onOpenRail = openRail,
                        railFocused = railOpen,
                        backToTopTick = backToTopTick,
                        onActiveSectionChange = { section ->
                            if (section != activeSection) {
                                activeSection = section
                            }
                        },
                        scrollToSectionRequest = scrollToSectionRequest,
                        onScrollToSectionConsumed = { scrollToSectionRequest = null },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = railOpen,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(180)) { -it / 2 },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it / 2 },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(5f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                NavRail(
                    selected = selectedRail,
                    onSelect = { dest ->
                        exitPromptVisible = false
                        when (dest) {
                            RailDestination.SETTINGS -> {
                                railOpen = false
                                onOpenSettings()
                            }
                            RailDestination.SEARCH -> {
                                searchOpen = true
                                railOpen = false
                                scope.launch { searchRequester.requestFocusWithRetry() }
                            }
                            RailDestination.HOME,
                            RailDestination.TV,
                            RailDestination.MOVIES,
                            RailDestination.ANIME,
                            -> {
                                searchOpen = false
                                railOpen = false
                                activeSection = dest
                                scrollJumpTick++
                                scrollToSectionRequest = dest to scrollJumpTick
                            }
                        }
                    },
                    selectedRequester = railRequester,
                    onRightExit = closeRailToContent,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        downloadIndicatorState(downloadList)?.let { indicator ->
            DownloadIndicator(
                state = indicator,
                onClick = onOpenDownloads,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 28.dp)
                    .zIndex(6f),
            )
        }

        AnimatedVisibility(
            visible = exitPromptVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .zIndex(6f),
        ) {
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
    CLOSE_SEARCH,
    OPEN_RAIL_IN_PLACE,
    BACK_TO_HOME,
    ARM_EXIT_PROMPT,
    EXIT_APP,
}

internal fun resolveMainBackOutcome(
    categoryOpen: Boolean,
    searchOpen: Boolean = false,
    topBarFocused: Boolean,
    onHomeSection: Boolean = true,
    exitArmed: Boolean,
): MainBackOutcome = when {
    categoryOpen -> MainBackOutcome.CLOSE_CATEGORY
    searchOpen -> MainBackOutcome.CLOSE_SEARCH
    !topBarFocused -> MainBackOutcome.OPEN_RAIL_IN_PLACE
    !onHomeSection -> MainBackOutcome.BACK_TO_HOME
    exitArmed -> MainBackOutcome.EXIT_APP
    else -> MainBackOutcome.ARM_EXIT_PROMPT
}

internal fun shouldRequestExit(categoryOpen: Boolean, topBarFocused: Boolean): Boolean = !categoryOpen && topBarFocused
