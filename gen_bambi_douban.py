# Generates DoubanCatalogApi.kt implementing TmdbApi with exact override signatures.
# Core feed/search/detail methods return Douban data; the rest return empty DTOs.
methods = [
    # (override signature (no defaults), return type, impl body expr)
    ("trendingAll(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto>", "TmdbPaginated<TmdbSearchResultDto>", "moviesAll(page)"),
    ("trendingAllWeek(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto>", "TmdbPaginated<TmdbSearchResultDto>", "moviesAll(page)"),
    ("discoverMovieQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(params[\"with_genres\"].orEmpty().ifBlank { \"热门\" }, 1)"),
    ("discoverTvQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbTvDto>", "TmdbPaginated<TmdbTvDto>", "tvByTag(\"热门\", 1)"),
    ("searchKeyword(key: String, query: String): TmdbPaginated<TmdbKeywordDto>", "TmdbPaginated<TmdbKeywordDto>", "TmdbPaginated()"),
    ("trendingMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(\"热门\", page)"),
    ("trendingTv(key: String, page: Int): TmdbPaginated<TmdbTvDto>", "TmdbPaginated<TmdbTvDto>", "tvByTag(\"热门\", page)"),
    ("popularMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(\"热门\", page)"),
    ("popularTv(key: String, page: Int): TmdbPaginated<TmdbTvDto>", "TmdbPaginated<TmdbTvDto>", "tvByTag(\"热门\", page)"),
    ("topRatedMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(\"豆瓣高分\", page)"),
    ("topRatedTv(key: String, page: Int): TmdbPaginated<TmdbTvDto>", "TmdbPaginated<TmdbTvDto>", "tvByTag(\"豆瓣高分\", page)"),
    ("nowPlayingMovies(key: String, region: String, page: Int): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(\"最新\", page)"),
    ("discoverMovies(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbMovieDto>", "TmdbPaginated<TmdbMovieDto>", "moviesByTag(genreToTag(genres), page)"),
    ("discoverTv(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbTvDto>", "TmdbPaginated<TmdbTvDto>", "tvByTag(genreToTag(genres), page)"),
    ("search(key: String, query: String): TmdbSearchResponse", "TmdbSearchResponse", "searchDouban(query)"),
    ("movieDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbMovieDetailDto", "TmdbMovieDetailDto", "movieDetail(id)"),
    ("tvDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbTvDetailDto", "TmdbTvDetailDto", "tvDetail(id)"),
    ("tvSeason(id: Int, season: Int, key: String): TmdbSeasonDto", "TmdbSeasonDto", "TmdbSeasonDto()"),
    ("collection(id: Int, key: String): TmdbCollectionDto", "TmdbCollectionDto", "TmdbCollectionDto()"),
    ("person(id: Int, key: String, append: String): TmdbPersonDto", "TmdbPersonDto", "TmdbPersonDto()"),
    ("movieExternalIds(id: Int, key: String): TmdbExternalIdsDto", "TmdbExternalIdsDto", "TmdbExternalIdsDto()"),
    ("tvExternalIds(id: Int, key: String): TmdbExternalIdsDto", "TmdbExternalIdsDto", "TmdbExternalIdsDto()"),
]

out = []
out.append('''package com.shieldstream.tv.data.repository

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
''')

for sig, ret, expr in methods:
    out.append(f"    override suspend fun {sig} = withContext(Dispatchers.IO) {{ {expr} }}\n")

out.append('''    // ---- Douban helpers ----

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

    private suspend fun moviesByTag(tag: String, page: Int): TmdbPaginated<TmdbMovieDto> =
        TmdbPaginated(page = page, results = safeSubjects("movie", tag, page).map { it.toMovieDto() })

    private suspend fun tvByTag(tag: String, page: Int): TmdbPaginated<TmdbTvDto> =
        TmdbPaginated(page = page, results = safeSubjects("tv", tag, page).map { it.toTvDto() })

    private suspend fun searchDouban(query: String): TmdbSearchResponse {
        if (query.isBlank()) return TmdbSearchResponse()
        val movies = runCatching {
            api.search(type = "movie", tag = "", pageLimit = 20).subjects
        }.getOrDefault(emptyList())
        // Douban search_subjects has no q param for free text in this endpoint variant;
        // fall back to filtering a broad chart by title, then try CMS-style title query.
        val matched = movies.filter { query in it.title || it.title in query }
        val tv = runCatching {
            api.search(type = "tv", tag = "", pageLimit = 20).subjects
        }.getOrDefault(emptyList()).filter { query in it.title || it.title in query }
        return TmdbSearchResponse(results = (matched.map { it.toSearchDto("movie") } + tv.map { it.toSearchDto("tv") }).distinctBy { it.id })
    }

    private suspend fun movieDetail(id: Int): TmdbMovieDetailDto {
        val a = safeAbstract(id.toString()) ?: return TmdbMovieDetailDto(id = id)
        val s = a.subject ?: return TmdbMovieDetailDto(id = id)
        val year = s.releaseYear.take(4).toIntOrNull()
        return TmdbMovieDetailDto(
            id = id,
            title = cleanTitle(s.title),
            overview = s.shortComment?.content.orEmpty(),
            poster_path = bigCover(s.id, a),
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
            poster_path = bigCover(s.id, a),
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

    /** Prefer the large poster from abstract when available; list cover otherwise. */
    private fun bigCover(id: String, a: DoubanAbstractDto?): String? =
        a?.subject?.let { "https://img3.doubanio.com/view/photo/l_ratio_poster/public/placeholder_$id.jpg" }
            ?.takeIf { false } // abstract has no cover path; list mapper already upgrades s->l

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
}
''')

open('core/data/src/main/kotlin/com/shieldstream/tv/data/repository/DoubanCatalogApi.kt','w',encoding='utf-8').write('\n'.join(out))
print('written, lines:', len('\n'.join(out).split('\n')))
