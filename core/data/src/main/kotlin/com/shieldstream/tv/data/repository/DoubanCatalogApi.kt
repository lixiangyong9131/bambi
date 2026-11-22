package com.shieldstream.tv.data.repository

import com.shieldstream.tv.network.api.DoubanApi
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalog source backed by Douban's public endpoints, shaped as a TmdbApi so the
 * whole existing CatalogRepository / UI stack works unchanged. Poster URLs are full
 * Douban CDN URLs (TmdbImages passes http* through and upgrades to the large variant).
 */
@Singleton
class DoubanCatalogApi @Inject constructor(
    private val api: DoubanApi,
    private val cms: com.shieldstream.tv.data.repository.streams.CmsStreams,
) : TmdbApi {

    /** Douban id -> cover URL, filled by list mapping so details/featured can reuse it. */
    private val coverCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** Douban id -> title, so tvSeason() can look up the CMS episodes by title. */
    private val titleOf = java.util.concurrent.ConcurrentHashMap<Int, String>()

    override suspend fun trendingAll(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) { moviesAll(page) }

    override suspend fun trendingAllWeek(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) {
        // Mixed movie + TV so the TV tab's featured/billboard is not empty.
        val movies = moviesAll(page).results
        val shows = safeSubjects("tv", "热门", page).map { it.toSearchDto("tv") }
        TmdbPaginated(page = page, results = (movies + shows).distinctBy { it.media_type + it.id })
    }

    override suspend fun discoverMovieQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag(params["with_genres"].orEmpty().ifBlank { "热门" }, 1) }

    override suspend fun discoverTvQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag("热门", 1) }

    override suspend fun searchKeyword(key: String, query: String): TmdbPaginated<TmdbKeywordDto> = withContext(Dispatchers.IO) { TmdbPaginated() }

    override suspend fun trendingMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag("热门", page) }

    override suspend fun trendingTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag("热门", page) }

    override suspend fun popularMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag("热门", page) }

    override suspend fun popularTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag("热门", page) }

    override suspend fun topRatedMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag("豆瓣高分", page) }

    override suspend fun topRatedTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag("豆瓣高分", page) }

    override suspend fun nowPlayingMovies(key: String, region: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag("最新", page) }

    override suspend fun discoverMovies(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { moviesByTag(genreToTag(genres), page) }

    override suspend fun discoverTv(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag(genreToTvTag(genres), page) }

    override suspend fun search(key: String, query: String): TmdbSearchResponse = withContext(Dispatchers.IO) { searchDouban(query) }

    override suspend fun movieDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbMovieDetailDto = withContext(Dispatchers.IO) { movieDetail(id) }

    override suspend fun tvDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbTvDetailDto = withContext(Dispatchers.IO) { tvDetail(id) }

    override suspend fun tvSeason(id: Int, season: Int, key: String): TmdbSeasonDto = withContext(Dispatchers.IO) {
        // Modified build: episodes come from the CMS (Douban has none). Use the title cached by details.
        val title = titleOf[id] ?: return@withContext TmdbSeasonDto()
        val eps = runCatching { cms.getEpisodes(title) }.getOrDefault(emptyList())
        TmdbSeasonDto(
            season_number = season,
            episodes = eps.map { e ->
                com.shieldstream.tv.network.dto.TmdbEpisodeDto(
                    episode_number = e.number,
                    season_number = season,
                    name = e.title,
                    overview = "",
                )
            },
        )
    }

    override suspend fun collection(id: Int, key: String): TmdbCollectionDto = withContext(Dispatchers.IO) { TmdbCollectionDto() }

    override suspend fun person(id: Int, key: String, append: String): TmdbPersonDto = withContext(Dispatchers.IO) { TmdbPersonDto() }

    override suspend fun movieExternalIds(id: Int, key: String): TmdbExternalIdsDto = withContext(Dispatchers.IO) { TmdbExternalIdsDto() }

    override suspend fun tvExternalIds(id: Int, key: String): TmdbExternalIdsDto = withContext(Dispatchers.IO) { TmdbExternalIdsDto() }

    // ---- Douban helpers ----

    private val TAG_POOL_MOVIE = listOf("热门", "豆瓣高分", "最新", "经典", "华语", "欧美", "动作片", "喜剧片", "爱情片", "科幻片", "悬疑片", "犯罪片", "动画")
    private val TAG_POOL_TV = listOf("热门", "国产剧", "美剧", "日剧", "韩剧", "豆瓣高分")

    private fun genreToTag(genres: String): String {
        val id = genres.split(",").firstOrNull()?.trim().orEmpty()
        return when (id) {
            "28" -> "动作片"
            "35" -> "喜剧片"
            "18" -> "经典"
            "878" -> "科幻片"
            "80" -> "犯罪片"
            "99" -> "悬疑片"
            else -> "热门"
        }
    }

    /** TV genre ids -> Douban TV tags (电影 tag 用在 tv 上会返回空). */
    private fun genreToTvTag(genres: String): String {
        val id = genres.split(",").firstOrNull()?.trim().orEmpty()
        return when (id) {
            "18", "80" -> "国产剧"     // 剧情/犯罪
            "10765", "10759" -> "美剧" // 科幻/动作冒险
            "35" -> "韩剧"            // 喜剧
            "16" -> "日剧"            // 动画
            "99" -> "国产剧"
            else -> "热门"
        }
    }

    /** Mixed movie feed across several charts (used by "trending all"). */
    private suspend fun moviesAll(page: Int): TmdbPaginated<TmdbSearchResultDto> {
        val tag = TAG_POOL_MOVIE.getOrElse(page - 1) { "热门" }
        val subs = safeSubjects("movie", tag, page) +
                (if (page == 1) safeSubjects("movie", "豆瓣高分", 1) else emptyList())
        return TmdbPaginated(page = page, results = subs.distinctBy { it.id }.map { it.toSearchDto("movie") })
    }

    private suspend fun moviesByTag(tag: String, page: Int): TmdbPaginated<TmdbMovieDto> {
        val subs = safeSubjects("movie", tag, page)
        android.util.Log.i("DoubanHome", "moviesByTag($tag,p$page): ${subs.size} subjects")
        return TmdbPaginated(page = page, results = subs.map { it.toMovieDto() })
    }

    private suspend fun tvByTag(tag: String, page: Int): TmdbPaginated<TmdbTvDto> {
        val subs = safeSubjects("tv", tag, page)
        android.util.Log.i("DoubanHome", "tvByTag($tag,p$page): ${subs.size} subjects")
        return TmdbPaginated(page = page, results = subs.map { it.toTvDto() })
    }

    private suspend fun searchDouban(query: String): TmdbSearchResponse {
        if (query.isBlank()) return TmdbSearchResponse()
        // Search via the CMS (Moontv/Feifei) catalog: only titles that actually have playable
        // resources are returned. Douban suggest supplies the poster + a detail-compatible id.
        val hits = runCatching { cms.searchTitles(query) }.getOrDefault(emptyList())
        android.util.Log.i("DoubanHome", "CMS search '$query' -> ${hits.size} hits")
        val suggestions = runCatching { api.suggest(query) }.getOrDefault(emptyList())

        fun matches(cmsTitle: String, doubanTitle: String): Boolean {
            val a = cleanTitle(cmsTitle).replace(" ", "")
            val b = cleanTitle(doubanTitle).replace(" ", "")
            if (a.length < 3 || b.length < 3) return false
            val n = minOf(6, minOf(a.length, b.length))
            return a.take(n).equals(b.take(n), ignoreCase = true) || a.contains(b) || b.contains(a)
        }

        val results = mutableListOf<TmdbSearchResultDto>()
        // Prefer Douban suggestions that also exist in the CMS (correct id + poster + playable).
        for (sug in suggestions) {
            val sugTitle = sug.title
            if (!hits.any { matches(it.title, sugTitle) }) continue
            val did = sug.id.ifBlank { sug.url.extractDoubanId() }
            if (did.isBlank()) continue
            val isTv = sug.episode.isNotBlank()
            results += TmdbSearchResultDto(
                id = did.toIntOrNull() ?: did.hashCode(),
                media_type = if (isTv) "tv" else "movie",
                title = if (!isTv) sugTitle else null,
                name = if (isTv) sugTitle else null,
                overview = "",
                poster_path = sug.img,
                vote_average = 0.0,
            )
        }
        // CMS titles with no Douban suggestion: still show them (playable, no poster).
        for (hit in hits) {
            if (suggestions.any { matches(hit.title, it.title) }) continue
            val isTv = hit.type.contains("剧")
            results += TmdbSearchResultDto(
                id = hit.id.toIntOrNull() ?: hit.title.hashCode(),
                media_type = if (isTv) "tv" else "movie",
                title = if (!isTv) hit.title else null,
                name = if (isTv) hit.title else null,
                overview = hit.remarks,
                poster_path = null,
                vote_average = 0.0,
            )
        }
        android.util.Log.i("DoubanHome", "search results -> ${results.size}")
        return TmdbSearchResponse(results = results.distinctBy { it.title ?: it.name })
    }

    private suspend fun movieDetail(id: Int): TmdbMovieDetailDto {
        val a = safeAbstract(id.toString()) ?: return TmdbMovieDetailDto(id = id)
        val s = a.subject ?: return TmdbMovieDetailDto(id = id)
        val year = s.releaseYear.take(4).toIntOrNull()
        return TmdbMovieDetailDto(
            id = id,
            title = cleanTitle(s.title),
            overview = s.shortComment?.content.orEmpty(),
            poster_path = coverCache[id],
            backdrop_path = coverCache[id],
            release_date = s.releaseYear.ifBlank { null },
            vote_average = (s.rate.toDoubleOrNull() ?: 0.0),
            genres = s.types.map { TmdbGenreDto(name = it) },
        )
    }

    private suspend fun tvDetail(id: Int): TmdbTvDetailDto {
        val a = safeAbstract(id.toString()) ?: return TmdbTvDetailDto(id = id)
        val s = a.subject ?: return TmdbTvDetailDto(id = id)
        titleOf[id] = cleanTitle(s.title)
        // Modified build: Douban has no season data. Ask the CMS for the episode count so the
        // details screen can show a season/episode picker for TV shows.
        val episodeCount = runCatching { cms.getEpisodes(cleanTitle(s.title)).size }.getOrDefault(0)
        val seasons = if (episodeCount > 0) listOf(
            com.shieldstream.tv.network.dto.TmdbSeasonSummaryDto(
                season_number = 1, name = "第1季", episode_count = episodeCount,
            ),
        ) else emptyList()
        return TmdbTvDetailDto(
            id = id,
            name = cleanTitle(s.title),
            overview = s.shortComment?.content.orEmpty(),
            poster_path = coverCache[id],
            backdrop_path = coverCache[id],
            first_air_date = s.releaseYear.ifBlank { null },
            vote_average = (s.rate.toDoubleOrNull() ?: 0.0),
            genres = s.types.map { TmdbGenreDto(name = it) },
            seasons = seasons,
            number_of_seasons = if (episodeCount > 0) 1 else 0,
        )
    }

    // ---- raw helpers ----

    private suspend fun safeSubjects(type: String, tag: String, page: Int) = runCatching {
        api.subjects(type = type, tag = tag, pageStart = (page - 1) * 20).subjects
    }.getOrDefault(emptyList())

    private suspend fun safeAbstract(id: String) = runCatching {
        api.abstract("https://movie.douban.com/j/subject_abstract?subject_id=$id")
    }.getOrNull()

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*"), "").trim()

    private fun DoubanSubjectDto.toMovieDto() = TmdbMovieDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { coverCache[it] = cover },
        title = cleanTitle(title),
        overview = "",
        poster_path = cover,
        backdrop_path = cover,
        release_date = null,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toTvDto() = TmdbTvDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { coverCache[it] = cover },
        name = cleanTitle(title),
        overview = "",
        poster_path = cover,
        backdrop_path = cover,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toSearchDto(mediaType: String) = TmdbSearchResultDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { coverCache[it] = cover },
        media_type = mediaType,
        title = if (mediaType == "movie") cleanTitle(title) else null,
        name = if (mediaType == "tv") cleanTitle(title) else null,
        overview = "",
        poster_path = cover,
        backdrop_path = cover,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun String.extractDoubanId(): String =
        Regex("/subject/(\\d+)").find(this)?.groupValues?.get(1).orEmpty()
}
