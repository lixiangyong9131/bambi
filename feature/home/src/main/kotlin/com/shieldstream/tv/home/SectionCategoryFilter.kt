package com.shieldstream.tv.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.ProvidePivotScroll
import com.shieldstream.tv.ui.component.RailDestination
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.DisplayFont

data class FilterTagOption(
    val doubanTag: String,
    @param:StringRes val labelRes: Int,
)

data class CategoryFilterSelection(
    val genres: Set<String> = emptySet(),
    val region: String? = null,
    val era: String? = null,
) {
    val isActive: Boolean get() = genres.isNotEmpty() || region != null || era != null

    fun toDoubanTags(): String = buildList {
        region?.let { add(it) }
        addAll(genres)
        era?.let { add(it) }
    }.joinToString(",")

    fun toggleGenre(tag: String): CategoryFilterSelection = copy(
        genres = if (tag in genres) genres - tag else genres + tag,
    )

    fun toggleRegion(tag: String?): CategoryFilterSelection = copy(
        region = if (tag == null || region == tag) null else tag,
    )

    fun toggleEra(tag: String?): CategoryFilterSelection = copy(
        era = if (tag == null || era == tag) null else tag,
    )

    fun displayParts(texts: Texts): List<String> = buildList {
        region?.let { r ->
            CategoryFilterCatalog.findLabelRes(r)?.let { add(texts.get(it)) } ?: add(r)
        }
        genres.forEach { g ->
            CategoryFilterCatalog.findLabelRes(g)?.let { add(texts.get(it)) } ?: add(g)
        }
        era?.let { e ->
            CategoryFilterCatalog.findLabelRes(e)?.let { add(texts.get(it)) } ?: add(e)
        }
    }

    fun summary(texts: Texts): String = displayParts(texts).joinToString(" · ")
}

object CategoryFilterCatalog {
    val tvGenres: List<FilterTagOption> = listOf(
        FilterTagOption("喜剧", StringsR.string.home_filter_cat_comedy),
        FilterTagOption("爱情", StringsR.string.home_filter_cat_romance),
        FilterTagOption("悬疑", StringsR.string.home_filter_cat_mystery),
        FilterTagOption("犯罪", StringsR.string.home_filter_cat_crime),
        FilterTagOption("动作", StringsR.string.home_filter_cat_action),
        FilterTagOption("科幻", StringsR.string.home_filter_cat_scifi),
        FilterTagOption("奇幻", StringsR.string.home_filter_cat_fantasy),
        FilterTagOption("惊悚", StringsR.string.home_filter_cat_thriller),
        FilterTagOption("恐怖", StringsR.string.home_filter_cat_horror),
        FilterTagOption("剧情", StringsR.string.home_filter_cat_drama),
        FilterTagOption("古装", StringsR.string.home_filter_cat_costume),
        FilterTagOption("武侠", StringsR.string.home_filter_cat_wuxia),
        FilterTagOption("历史", StringsR.string.home_filter_cat_history),
        FilterTagOption("战争", StringsR.string.home_filter_cat_war),
        FilterTagOption("家庭", StringsR.string.home_filter_cat_family),
        FilterTagOption("动画", StringsR.string.home_filter_cat_animation),
        FilterTagOption("冒险", StringsR.string.home_filter_cat_adventure),
        FilterTagOption("灾难", StringsR.string.home_filter_cat_disaster),
        FilterTagOption("传记", StringsR.string.home_filter_cat_biography),
        FilterTagOption("真人秀", StringsR.string.home_filter_cat_reality),
        FilterTagOption("脱口秀", StringsR.string.home_filter_cat_talkshow),
    )

    val tvRegions: List<FilterTagOption> = listOf(
        FilterTagOption("中国大陆", StringsR.string.home_filter_cat_domestic),
        FilterTagOption("中国香港", StringsR.string.home_filter_cat_hk),
        FilterTagOption("中国台湾", StringsR.string.home_filter_cat_tw),
        FilterTagOption("美国", StringsR.string.home_filter_cat_us),
        FilterTagOption("英国", StringsR.string.home_filter_cat_uk),
        FilterTagOption("韩国", StringsR.string.home_filter_cat_korea),
        FilterTagOption("日本", StringsR.string.home_filter_cat_japan),
        FilterTagOption("泰国", StringsR.string.home_filter_cat_thailand),
        FilterTagOption("欧美", StringsR.string.home_filter_cat_western),
        FilterTagOption("法国", StringsR.string.home_filter_cat_france),
        FilterTagOption("德国", StringsR.string.home_filter_cat_germany),
        FilterTagOption("西班牙", StringsR.string.home_filter_cat_spain),
    )

    val movieGenres: List<FilterTagOption> = listOf(
        FilterTagOption("剧情", StringsR.string.home_filter_cat_drama),
        FilterTagOption("喜剧", StringsR.string.home_filter_cat_comedy),
        FilterTagOption("爱情", StringsR.string.home_filter_cat_romance),
        FilterTagOption("动作", StringsR.string.home_filter_cat_action),
        FilterTagOption("科幻", StringsR.string.home_filter_cat_scifi),
        FilterTagOption("悬疑", StringsR.string.home_filter_cat_mystery),
        FilterTagOption("犯罪", StringsR.string.home_filter_cat_crime),
        FilterTagOption("惊悚", StringsR.string.home_filter_cat_thriller),
        FilterTagOption("恐怖", StringsR.string.home_filter_cat_horror),
        FilterTagOption("动画", StringsR.string.home_filter_cat_animation),
        FilterTagOption("奇幻", StringsR.string.home_filter_cat_fantasy),
        FilterTagOption("冒险", StringsR.string.home_filter_cat_adventure),
        FilterTagOption("历史", StringsR.string.home_filter_cat_history),
        FilterTagOption("战争", StringsR.string.home_filter_cat_war),
        FilterTagOption("武侠", StringsR.string.home_filter_cat_wuxia),
        FilterTagOption("灾难", StringsR.string.home_filter_cat_disaster),
        FilterTagOption("传记", StringsR.string.home_filter_cat_biography),
        FilterTagOption("西部", StringsR.string.home_filter_cat_western_genre),
        FilterTagOption("家庭", StringsR.string.home_filter_cat_family),
        FilterTagOption("纪录片", StringsR.string.home_filter_cat_documentary),
        FilterTagOption("音乐", StringsR.string.home_filter_cat_music),
    )

    val movieRegions: List<FilterTagOption> = listOf(
        FilterTagOption("华语", StringsR.string.home_filter_cat_chinese),
        FilterTagOption("中国大陆", StringsR.string.home_filter_cat_domestic),
        FilterTagOption("中国香港", StringsR.string.home_filter_cat_hk),
        FilterTagOption("中国台湾", StringsR.string.home_filter_cat_tw),
        FilterTagOption("欧美", StringsR.string.home_filter_cat_western),
        FilterTagOption("美国", StringsR.string.home_filter_cat_us),
        FilterTagOption("英国", StringsR.string.home_filter_cat_uk),
        FilterTagOption("韩国", StringsR.string.home_filter_cat_korea),
        FilterTagOption("日本", StringsR.string.home_filter_cat_japan),
        FilterTagOption("法国", StringsR.string.home_filter_cat_france),
        FilterTagOption("德国", StringsR.string.home_filter_cat_germany),
        FilterTagOption("印度", StringsR.string.home_filter_cat_india),
        FilterTagOption("泰国", StringsR.string.home_filter_cat_thailand),
        FilterTagOption("西班牙", StringsR.string.home_filter_cat_spain),
    )

    val eras: List<FilterTagOption> = listOf(
        FilterTagOption("2026", StringsR.string.home_filter_era_2026),
        FilterTagOption("2025", StringsR.string.home_filter_era_2025),
        FilterTagOption("2024", StringsR.string.home_filter_era_2024),
        FilterTagOption("2020年代", StringsR.string.home_filter_era_2020s),
        FilterTagOption("2010年代", StringsR.string.home_filter_era_2010s),
        FilterTagOption("2000年代", StringsR.string.home_filter_era_2000s),
        FilterTagOption("90年代", StringsR.string.home_filter_era_90s),
        FilterTagOption("80年代", StringsR.string.home_filter_era_80s),
    )

    private val allByTag: Map<String, Int> = (tvGenres + tvRegions + movieGenres + movieRegions + eras)
        .associate { it.doubanTag to it.labelRes }

    fun findLabelRes(tag: String): Int? = allByTag[tag]

    fun genresFor(section: RailDestination): List<FilterTagOption> = when (section) {
        RailDestination.TV -> tvGenres
        RailDestination.MOVIES -> movieGenres
        else -> emptyList()
    }

    fun regionsFor(section: RailDestination): List<FilterTagOption> = when (section) {
        RailDestination.TV -> tvRegions
        RailDestination.MOVIES -> movieRegions
        else -> emptyList()
    }

    fun filterForSection(section: RailDestination): HomeFilter? = when (section) {
        RailDestination.TV -> HomeFilter.TV
        RailDestination.MOVIES -> HomeFilter.MOVIES
        else -> null
    }
}

@Composable
internal fun formatFilterSelectionSummary(selection: CategoryFilterSelection): String {
    val parts = buildList {
        selection.region?.let { tag ->
            val res = CategoryFilterCatalog.findLabelRes(tag)
            add(if (res != null) localizedStringResource(res) else tag)
        }
        selection.genres.forEach { tag ->
            val res = CategoryFilterCatalog.findLabelRes(tag)
            add(if (res != null) localizedStringResource(res) else tag)
        }
        selection.era?.let { tag ->
            val res = CategoryFilterCatalog.findLabelRes(tag)
            add(if (res != null) localizedStringResource(res) else tag)
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Full-screen Netflix TV style multi-dimensional Douban filter overlay (multi-select genres + region + era).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetflixCategoryOverlay(
    section: RailDestination,
    activeSelection: CategoryFilterSelection,
    onApply: (CategoryFilterSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val genres = remember(section) { CategoryFilterCatalog.genresFor(section) }
    val regions = remember(section) { CategoryFilterCatalog.regionsFor(section) }
    val eras = remember { CategoryFilterCatalog.eras }
    var draft by remember(section, activeSelection) { mutableStateOf(activeSelection) }
    val initialRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val resetRequester = remember { FocusRequester() }
    val sectionTitle = when (section) {
        RailDestination.TV -> localizedStringResource(StringsR.string.common_tv_shows)
        RailDestination.MOVIES -> localizedStringResource(StringsR.string.common_movies)
        else -> ""
    }

    LaunchedEffect(section) {
        initialRequester.requestFocusWithRetry()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(12f)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xF5141416), Color(0xFA050506)),
                ),
            )
            .focusGroup()
            .onBackKey(enabled = true) { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(1060.dp)
                .padding(vertical = 36.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(20.dp)
                                .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = sectionTitle,
                            color = AppColors.Accent,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = DisplayFont,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = localizedStringResource(StringsR.string.home_filter_modal_title),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val summary = formatFilterSelectionSummary(draft)
                    Text(
                        text = if (summary.isNotEmpty()) {
                            summary
                        } else {
                            localizedStringResource(StringsR.string.home_filter_modal_hint)
                        },
                        color = if (summary.isNotEmpty()) Color(0xFFFF8A8F) else Color.White.copy(alpha = 0.58f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverlayActionButton(
                        label = localizedStringResource(StringsR.string.home_filter_apply),
                        primary = true,
                        onClick = { onApply(draft) },
                        modifier = Modifier
                            .focusRequester(applyRequester)
                            .focusProperties {
                                down = initialRequester
                                right = resetRequester
                            },
                    )
                    OverlayActionButton(
                        label = localizedStringResource(StringsR.string.home_filter_reset_all),
                        primary = false,
                        onClick = { draft = CategoryFilterSelection() },
                        modifier = Modifier
                            .focusRequester(resetRequester)
                            .focusProperties {
                                down = initialRequester
                                left = applyRequester
                            },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            ProvidePivotScroll(leading = 60.dp) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(540.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item(key = "genres") {
                        FilterGroupSection(title = localizedStringResource(StringsR.string.home_filter_group_genre)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                genres.forEachIndexed { idx, opt ->
                                    val selected = opt.doubanTag in draft.genres
                                    FilterChip(
                                        label = localizedStringResource(opt.labelRes),
                                        selected = selected,
                                        multiCheck = true,
                                        onClick = { draft = draft.toggleGenre(opt.doubanTag) },
                                        modifier = (if (idx == 0) Modifier.focusRequester(initialRequester) else Modifier)
                                            .then(
                                                if (idx < 12) {
                                                    Modifier.focusProperties { up = applyRequester }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }

                    item(key = "regions") {
                        FilterGroupSection(title = localizedStringResource(StringsR.string.home_filter_group_region)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FilterChip(
                                    label = localizedStringResource(StringsR.string.home_filter_all_regions),
                                    selected = draft.region == null,
                                    multiCheck = false,
                                    onClick = { draft = draft.toggleRegion(null) },
                                )
                                regions.forEach { opt ->
                                    FilterChip(
                                        label = localizedStringResource(opt.labelRes),
                                        selected = draft.region == opt.doubanTag,
                                        multiCheck = false,
                                        onClick = { draft = draft.toggleRegion(opt.doubanTag) },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "eras") {
                        FilterGroupSection(title = localizedStringResource(StringsR.string.home_filter_group_era)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FilterChip(
                                    label = localizedStringResource(StringsR.string.home_filter_all_eras),
                                    selected = draft.era == null,
                                    multiCheck = false,
                                    onClick = { draft = draft.toggleEra(null) },
                                )
                                eras.forEach { opt ->
                                    FilterChip(
                                        label = localizedStringResource(opt.labelRes),
                                        selected = draft.era == opt.doubanTag,
                                        multiCheck = false,
                                        onClick = { draft = draft.toggleEra(opt.doubanTag) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterGroupSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    multiCheck: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color(0x4DE50914)
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
        else -> Color.White.copy(alpha = 0.78f)
    }
    val text = if (selected && multiCheck) "✓ $label" else label
    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = if (focused) 1.06f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun OverlayActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
                val scale = if (focused) 1.06f else 1f
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
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}
