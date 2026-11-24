package com.shieldstream.tv.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.tvClickable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A CMS category chip: id is a CMS V10 type id, or -1 for a year tag. */
internal data class CmsChip(val label: String, val typeId: Int, val year: String? = null)

private object CmsBrowseData {
    val MOVIE_CATEGORIES = listOf(
        CmsChip("动作片", 6), CmsChip("喜剧片", 7), CmsChip("爱情片", 8), CmsChip("科幻片", 9),
        CmsChip("恐怖片", 10), CmsChip("剧情片", 11), CmsChip("战争片", 12), CmsChip("纪录片", 20),
    )
    val TV_CATEGORIES = listOf(
        CmsChip("国产剧", 13), CmsChip("港剧", 14), CmsChip("韩剧", 15), CmsChip("欧美剧", 16),
        CmsChip("台剧", 21), CmsChip("日剧", 22), CmsChip("泰剧", 24), CmsChip("短剧", 36),
        CmsChip("综艺", 25),
    )
    val ANIME_CATEGORIES = listOf(
        CmsChip("国产动漫", 29), CmsChip("日韩动漫", 30), CmsChip("欧美动漫", 31), CmsChip("港台动漫", 32),
    )
    /** 常用标签 = year filters; verified working on the CMS API. */
    val YEAR_TAGS = listOf(
        CmsChip("2026", -1, "2026"), CmsChip("2025", -1, "2025"), CmsChip("2024", -1, "2024"),
        CmsChip("2023", -1, "2023"), CmsChip("2022", -1, "2022"),
    )

    fun categories(filter: HomeFilter) = when (filter) {
        HomeFilter.MOVIES -> MOVIE_CATEGORIES
        HomeFilter.TV -> TV_CATEGORIES
        HomeFilter.ANIME -> ANIME_CATEGORIES
        HomeFilter.ALL -> MOVIE_CATEGORIES + TV_CATEGORIES.take(4) + ANIME_CATEGORIES.take(2)
    }
}

@Composable
fun CmsCategoryScreen(
    filter: HomeFilter,
    onItemClick: (CatalogItem) -> Unit,
    onBack: () -> Unit,
    viewModel: CmsCategoryViewModel = hiltViewModel(key = "cms_cat_${filter.name}"),
) {
    val categories = remember(filter) { CmsBrowseData.categories(filter) }
    var selected by remember(filter) { mutableStateOf(categories.firstOrNull()) }
    var year by remember(filter) { mutableStateOf<String?>(null) }
    val rows by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(filter, selected?.typeId, year) {
        val chip = selected ?: return@LaunchedEffect
        viewModel.load(chip.typeId, year, filter)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onBackKey(enabled = true) { onBack() }) {
        Column(Modifier.fillMaxSize()) {
            // Header: two chip rows — 热门分类 and 常用标签, styled like the home categories.
            Text(
                text = "热门分类",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 40.dp, top = 20.dp, bottom = 8.dp),
            )
            ChipRow(
                chips = categories,
                selectedTypeId = selected?.typeId,
                onSelect = { selected = it; year = null },
            )
            Text(
                text = "常用标签",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 40.dp, top = 12.dp, bottom = 8.dp),
            )
            ChipRow(
                chips = CmsBrowseData.YEAR_TAGS,
                selectedTypeId = if (year != null) -1 else null,
                selectedYear = year,
                onSelect = { year = it.year },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    rows.isEmpty() -> HomeSkeleton()
                    else -> RowsBrowser(
                        rows = rows,
                        onItemClick = onItemClick,
                        onRemoveContinue = {},
                        onRemoveMyList = {},
                        onExitTop = null,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    chips: List<CmsChip>,
    selectedTypeId: Int?,
    selectedYear: String? = null,
    onSelect: (CmsChip) -> Unit,
) {
    val requester = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp),
    ) {
        items(chips) { chip ->
            val isSelected = chip.year?.let { it == selectedYear } ?: (chip.typeId == selectedTypeId)
            CmsCategoryChip(
                label = chip.label,
                selected = isSelected,
                modifier = if (isSelected) Modifier.focusRequester(requester) else Modifier,
                onClick = { onSelect(chip) },
            )
        }
    }
}

@Composable
private fun CmsCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cmsChipScale")
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(46.dp)
            .widthIn(min = 110.dp)
            .background(
                shape = shape,
                brush = if (focused || selected) {
                    Brush.verticalGradient(listOf(Color.White, Color.White))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFF2A2C3C), Color(0xFF17181F)))
                },
            )
            .border(1.dp, Color.White.copy(alpha = if (focused || selected) 0f else 0.12f), shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = label,
            fontSize = 20.sp,
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (focused || selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

@HiltViewModel
class CmsCategoryViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val texts: Texts,
) : ViewModel() {
    private val _uiState = MutableStateFlow(emptyList<com.shieldstream.tv.model.CatalogRow>())
    val uiState: StateFlow<List<com.shieldstream.tv.model.CatalogRow>> = _uiState

    private var loaded: Triple<Int, String?, HomeFilter>? = null

    fun load(typeId: Int, year: String?, filter: HomeFilter, force: Boolean = false) {
        val key = Triple(typeId, year, filter)
        if (!force && loaded == key && _uiState.value.isNotEmpty()) return
        loaded = key
        viewModelScope.launch {
            _uiState.value = emptyList()
            runCatching { catalogRepository.getCmsCategoryRows(typeId, year, filter) }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = emptyList() }
        }
    }
}
