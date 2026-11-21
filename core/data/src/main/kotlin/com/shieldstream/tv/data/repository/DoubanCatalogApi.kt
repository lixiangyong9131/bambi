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
) : TmdbApi {

    override suspend fun trendingAll(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) { moviesAll(page) }

    override suspend fun trendingAllWeek(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) { moviesAll(page) }

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

    override suspend fun discoverTv(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { tvByTag(genreToTag(genres), page) }

    override suspend fun search(key: String, query: String): TmdbSearchResponse = withContext(Dispatchers.IO) { searchDouban(query) }

    override suspend fun movieDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbMovieDetailDto = withContext(Dispatchers.IO) { movieDetail(id) }

    override suspend fun tvDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbTvDetailDto = withContext(Dispatchers.IO) { tvDetail(id) }

    override suspend fun tvSeason(id: Int, season: Int, key: String): TmdbSeasonDto = withContext(Dispatchers.IO) { TmdbSeasonDto() }

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
        val suggestions = runCatching { api.suggest(query) }.getOrDefault(emptyList())
        val results = suggestions.mapNotNull { sug ->
            val did = sug.id.ifBlank { sug.url.extractDoubanId() }
            if (did.isBlank()) return@mapNotNull null
            val isTv = sug.episode.isNotBlank()
            TmdbSearchResultDto(
                id = did.toIntOrNull() ?: did.hashCode(),
                media_type = if (isTv) "tv" else "movie",
                title = if (!isTv) sug.title else null,
                name = if (isTv) sug.title else null,
                overview = "",
                poster_path = sug.img,
                vote_average = 0.0,
            )
        }
        return TmdbSearchResponse(results = results)
    }

    private suspend fun movieDetail(id: Int): TmdbMovieDetailDto {
        val a = safeAbstract(id.toString()) ?: return TmdbMovieDetailDto(id = id)
        val s = a.subject ?: return TmdbMovieDetailDto(id = id)
        val year = s.releaseYear.take(4).toIntOrNull()
        return TmdbMovieDetailDto(
            id = id,
            title = cleanTitle(s.title),
            overview = s.shortComment?.content.orEmpty(),
            poster_path = null,
            release_date = s.releaseYear.ifBlank { null },
            vote_average = (s.rate.toDoubleOrNull() ?: 0.0),
            genres = s.types.map { TmdbGenreDto(name = it) },
        )
    }

    private suspend fun tvDetail(id: Int): TmdbTvDetailDto {
        val a = safeAbstract(id.toString()) ?: return TmdbTvDetailDto(id = id)
        val s = a.subject ?: return TmdbTvDetailDto(id = id)
        return TmdbTvDetailDto(
            id = id,
            name = cleanTitle(s.title),
            overview = s.shortComment?.content.orEmpty(),
            poster_path = null,
            first_air_date = s.releaseYear.ifBlank { null },
            vote_average = (s.rate.toDoubleOrNull() ?: 0.0),
            genres = s.types.map { TmdbGenreDto(name = it) },
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
        id = id.toIntOrNull() ?: id.hashCode(),
        title = cleanTitle(title),
        overview = "",
        poster_path = cover,
        release_date = null,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toTvDto() = TmdbTvDto(
        id = id.toIntOrNull() ?: id.hashCode(),
        name = cleanTitle(title),
        overview = "",
        poster_path = cover,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toSearchDto(mediaType: String) = TmdbSearchResultDto(
        id = id.toIntOrNull() ?: id.hashCode(),
        media_type = mediaType,
        title = if (mediaType == "movie") cleanTitle(title) else null,
        name = if (mediaType == "tv") cleanTitle(title) else null,
        overview = "",
        poster_path = cover,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun String.extractDoubanId(): String =
        Regex("/subject/(\\d+)").find(this)?.groupValues?.get(1).orEmpty()
}
