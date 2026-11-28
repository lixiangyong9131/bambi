package com.shieldstream.tv.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.strings.localizedStringResource

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

    fun clearGenres(): CategoryFilterSelection = copy(genres = emptySet())

    fun toggleRegion(tag: String?): CategoryFilterSelection = copy(
        region = if (tag == null || region == tag) null else tag,
    )

    fun toggleEra(tag: String?): CategoryFilterSelection = copy(
        era = if (tag == null || era == tag) null else tag,
    )

    fun sanitizeFor(filter: HomeFilter): CategoryFilterSelection {
        val validGenres = CategoryFilterCatalog.genresFor(filter).map { it.doubanTag }.toSet()
        val validRegions = CategoryFilterCatalog.regionsFor(filter).map { it.doubanTag }.toSet()
        return copy(
            genres = genres.filterTo(LinkedHashSet()) { it in validGenres },
            region = region?.takeIf { it in validRegions },
        )
    }

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
        FilterTagOption("冷门佳片", StringsR.string.home_filter_cat_hidden_gems),
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

    val animeGenres: List<FilterTagOption> = listOf(
        FilterTagOption("奇幻", StringsR.string.home_filter_cat_fantasy),
        FilterTagOption("冒险", StringsR.string.home_filter_cat_adventure),
        FilterTagOption("科幻", StringsR.string.home_filter_cat_scifi),
        FilterTagOption("动作", StringsR.string.home_filter_cat_action),
        FilterTagOption("悬疑", StringsR.string.home_filter_cat_mystery),
        FilterTagOption("喜剧", StringsR.string.home_filter_cat_comedy),
        FilterTagOption("剧情", StringsR.string.home_filter_cat_drama),
        FilterTagOption("爱情", StringsR.string.home_filter_cat_romance),
        FilterTagOption("惊悚", StringsR.string.home_filter_cat_thriller),
        FilterTagOption("恐怖", StringsR.string.home_filter_cat_horror),
        FilterTagOption("家庭", StringsR.string.home_filter_cat_family),
        FilterTagOption("古装", StringsR.string.home_filter_cat_costume),
        FilterTagOption("武侠", StringsR.string.home_filter_cat_wuxia),
        FilterTagOption("历史", StringsR.string.home_filter_cat_history),
    )

    val animeRegions: List<FilterTagOption> = listOf(
        FilterTagOption("日本", StringsR.string.home_filter_cat_japan),
        FilterTagOption("中国大陆", StringsR.string.home_filter_cat_domestic),
        FilterTagOption("美国", StringsR.string.home_filter_cat_us),
        FilterTagOption("韩国", StringsR.string.home_filter_cat_korea),
        FilterTagOption("英国", StringsR.string.home_filter_cat_uk),
        FilterTagOption("欧美", StringsR.string.home_filter_cat_western),
        FilterTagOption("法国", StringsR.string.home_filter_cat_france),
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

    private val allByTag: Map<String, Int> =
        (tvGenres + tvRegions + movieGenres + movieRegions + animeGenres + animeRegions + eras)
            .associate { it.doubanTag to it.labelRes }

    fun findLabelRes(tag: String): Int? = allByTag[tag]

    fun genresFor(filter: HomeFilter): List<FilterTagOption> = when (filter) {
        HomeFilter.TV -> tvGenres
        HomeFilter.MOVIES -> movieGenres
        HomeFilter.ANIME -> animeGenres
        HomeFilter.ALL -> emptyList()
    }

    fun regionsFor(filter: HomeFilter): List<FilterTagOption> = when (filter) {
        HomeFilter.TV -> tvRegions
        HomeFilter.MOVIES -> movieRegions
        HomeFilter.ANIME -> animeRegions
        HomeFilter.ALL -> emptyList()
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
