package com.shieldstream.tv.data.repository

import com.shieldstream.tv.network.api.DoubanApi
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : TmdbApi {

    /** Douban id -> cover URL, filled by list mapping so details/featured can reuse it. */
    private val coverCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** Persistent id->cover so cold-start Continue Watching has art even if suggest is rate-limited. */
    private val coverPrefs = appContext.getSharedPreferences("douban_covers", android.content.Context.MODE_PRIVATE)
    private val ratingLocks = ConcurrentHashMap<String, Mutex>()
    private fun rememberCover(id: Int, url: String) {
        if (url.isBlank()) return
        coverCache[id] = url
        coverPrefs.edit().putString(id.toString(), url).apply()
    }
    /** Douban id -> title, so tvSeason() can look up the CMS episodes by title. */
    private val titleOf = ConcurrentHashMap<Int, String>()
    private data class CachedAbstract(val at: Long, val value: DoubanAbstractDto)
    private val abstractCache = ConcurrentHashMap<String, CachedAbstract>()

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

    override suspend fun movieDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbMovieDetailDto = withContext(Dispatchers.IO) {
        movieDetail(id, full = append.contains("images") || append.contains("credits") || append.contains("videos"))
    }

    override suspend fun tvDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbTvDetailDto = withContext(Dispatchers.IO) {
        tvDetail(id, full = append.contains("images") || append.contains("credits") || append.contains("videos"))
    }

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
        // Modified build: query Douban suggest and direct CMS search in parallel, then resolve any
        // pinyin-suggested Chinese titles concurrently across CMS sources.
        val (suggestions, initialHits) = coroutineScope {
            val sugJob = async { runCatching { api.suggest(query) }.getOrDefault(emptyList()) }
            val cmsJob = async { runCatching { cms.searchTitles(query) }.getOrDefault(emptyList()) }
            sugJob.await() to cmsJob.await()
        }
        val rawHits = initialHits.toMutableList()
        if (query.matches(Regex("(?i)^[a-z\\s]+$"))) {
            val cnTitles = LinkedHashSet<String>()
            suggestions.take(4).forEach { if (it.title.isNotBlank()) cnTitles += it.title }
            // Modified build: local pinyin-initials match (e.g. xxrj -> 小巷人家) as a fallback
            // when Douban suggest is rate-limited or has no entry for the abbreviation.
            if (rawHits.isEmpty()) {
                cnTitles += runCatching { cms.searchByInitials(query) }.getOrDefault(emptyList())
            }
            if (cnTitles.isNotEmpty()) {
                coroutineScope {
                    cnTitles.map { cn ->
                        async { runCatching { cms.searchTitles(cn) }.getOrDefault(emptyList()) }
                    }.awaitAll().forEach { rawHits += it }
                }
            }
        }
        val hits = rawHits.distinctBy { it.title }
        android.util.Log.i("DoubanHome", "CMS search '$query' -> ${hits.size} hits (suggest ${suggestions.size})")

        fun matches(cmsTitle: String, doubanTitle: String): Boolean {
            val a = cleanTitle(cmsTitle).replace(" ", "")
            val b = cleanTitle(doubanTitle).replace(" ", "")
            if (a.isBlank() || b.isBlank()) return false
            if (a.equals(b, ignoreCase = true)) return true
            // Modified build: short CJK titles (e.g. 谷雨) must still match exactly - the old
            // length<3 guard dropped every 2-character Chinese title from suggestions.
            if (a.length < 2 || b.length < 2) return false
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

    private suspend fun movieDetail(id: Int, full: Boolean): TmdbMovieDetailDto {
        val (abstractDto, rexxar) = coroutineScope {
            val absJob = async { safeAbstract(id.toString()) }
            val rexJob = async { if (full) rexxarDetail("movie", id) else (null to null) }
            absJob.await() to rexJob.await()
        }
        val s = abstractDto?.subject
        val rxSubject = rexxar.first
        val rawTitle = s?.title ?: rxSubject?.title.orEmpty()
        if (rawTitle.isBlank()) return TmdbMovieDetailDto(id = id)
        val clean = cleanTitle(rawTitle)
        val fallbackScore = (s?.rate?.toDoubleOrNull() ?: rxSubject?.rating?.value ?: 0.0).toFloat()
        val doubanRating = if (full) buildRatingDto(rxSubject, rexxar.second, fallbackScore) else null
        val (cover, synopsis) = coroutineScope {
            val coverJob = async { coverFor(id, rawTitle, rxSubject?.pic) }
            val fallbackDesc = s?.shortComment?.content.orEmpty().ifBlank { rxSubject?.intro.orEmpty() }
            val descJob = async {
                if (full) {
                    runCatching { cms.getDescription(clean) }.getOrDefault("").ifBlank { fallbackDesc }
                } else {
                    fallbackDesc
                }
            }
            coverJob.await() to descJob.await()
        }
        val cast = com.shieldstream.tv.network.dto.TmdbCreditsDto(
            cast = s?.actors.orEmpty().mapIndexed { i, name ->
                com.shieldstream.tv.network.dto.TmdbCastDto(id = i, name = name, order = i)
            },
        )
        return TmdbMovieDetailDto(
            id = id,
            title = clean,
            overview = synopsis,
            poster_path = cover,
            backdrop_path = cover,
            release_date = (s?.releaseYear ?: rxSubject?.year.orEmpty()).ifBlank { null },
            vote_average = (doubanRating?.score?.toDouble() ?: fallbackScore.toDouble()),
            genres = (s?.types?.takeIf { it.isNotEmpty() } ?: rxSubject?.genres.orEmpty()).map { TmdbGenreDto(name = it) },
            credits = cast,
            douban_rating = doubanRating,
        )
    }

    private suspend fun tvDetail(id: Int, full: Boolean): TmdbTvDetailDto {
        val (abstractDto, rexxar) = coroutineScope {
            val absJob = async { safeAbstract(id.toString()) }
            val rexJob = async { if (full) rexxarDetail("tv", id) else (null to null) }
            absJob.await() to rexJob.await()
        }
        val s = abstractDto?.subject
        val rxSubject = rexxar.first
        val rawTitle = s?.title ?: rxSubject?.title.orEmpty()
        if (rawTitle.isBlank()) return TmdbTvDetailDto(id = id)
        val clean = cleanTitle(rawTitle)
        titleOf[id] = clean
        val fallbackScore = (s?.rate?.toDoubleOrNull() ?: rxSubject?.rating?.value ?: 0.0).toFloat()
        val doubanRating = if (full) buildRatingDto(rxSubject, rexxar.second, fallbackScore) else null
        // Modified build: Douban has no season data. Fetch cover + CMS bundle in parallel only when
        // full details are requested; lightweight item lookups skip extra calls.
        val (cover, epsCount, synopsis) = coroutineScope {
            val coverJob = async { coverFor(id, rawTitle, rxSubject?.pic) }
            val epsJob = async {
                if (full) runCatching { cms.getEpisodes(clean).size }.getOrDefault(0) else 0
            }
            val fallbackDesc = s?.shortComment?.content.orEmpty().ifBlank { rxSubject?.intro.orEmpty() }
            val descJob = async {
                if (full) {
                    runCatching { cms.getDescription(clean) }.getOrDefault("").ifBlank { fallbackDesc }
                } else {
                    fallbackDesc
                }
            }
            Triple(coverJob.await(), epsJob.await(), descJob.await())
        }
        val seasons = if (epsCount > 0) listOf(
            com.shieldstream.tv.network.dto.TmdbSeasonSummaryDto(
                season_number = 1, name = "第1季", episode_count = epsCount,
            ),
        ) else emptyList()
        val cast = com.shieldstream.tv.network.dto.TmdbCreditsDto(
            cast = s?.actors.orEmpty().mapIndexed { i, name ->
                com.shieldstream.tv.network.dto.TmdbCastDto(id = i, name = name, order = i)
            },
        )
        return TmdbTvDetailDto(
            id = id,
            name = clean,
            overview = synopsis,
            poster_path = cover,
            backdrop_path = cover,
            first_air_date = (s?.releaseYear ?: rxSubject?.year.orEmpty()).ifBlank { null },
            vote_average = (doubanRating?.score?.toDouble() ?: fallbackScore.toDouble()),
            genres = (s?.types?.takeIf { it.isNotEmpty() } ?: rxSubject?.genres.orEmpty()).map { TmdbGenreDto(name = it) },
            seasons = seasons,
            number_of_seasons = if (epsCount > 0) 1 else 0,
            aggregate_credits = cast,
            douban_rating = doubanRating,
        )
    }

    // ---- raw helpers ----

    private suspend fun safeSubjects(type: String, tag: String, page: Int) = runCatching {
        api.subjects(type = type, tag = tag, pageStart = (page - 1) * 20).subjects
    }.getOrDefault(emptyList())

    /** Modified build: fill the cover from Douban suggest when the list cache is empty
     * (e.g. Continue Watching rebuilds items by id on a cold start). */
    private suspend fun coverFor(id: Int, title: String, rexxarPic: DoubanRexxarPicDto? = null): String? {
        coverCache[id]?.let { return it }
        coverPrefs.getString(id.toString(), null)?.let { coverCache[id] = it; return it }
        val picUrl = rexxarPic?.large?.takeIf { it.isNotBlank() } ?: rexxarPic?.normal?.takeIf { it.isNotBlank() }
        if (picUrl != null) {
            rememberCover(id, picUrl)
            return picUrl
        }
        // Titles may carry an English original suffix ("肖申克的救赎 The Shawshank Redemption")
        // which Douban suggest does not index - strip it so the Chinese title matches.
        var q = cleanTitle(title)
        if (q.any { it.code > 0x2E80 }) {
            q = q.replace(Regex(" *[A-Za-z][A-Za-z0-9 '’.,!?-]*$"), "").trim()
        }
        val img = runCatching { api.suggest(q).firstOrNull()?.img }
            .getOrDefault(null)
        if (!img.isNullOrBlank()) rememberCover(id, img)
        return img
    }

    private data class RexxarBundle(val at: Long, val subject: DoubanRexxarSubjectDto?, val stats: DoubanRexxarStatsDto?)
    private val rexxarCache = ConcurrentHashMap<String, RexxarBundle>()

    /** Fetches Douban Rexxar subject details and 1..5 star distribution in parallel with 6h caching. */
    private suspend fun rexxarDetail(mediaType: String, id: Int): Pair<DoubanRexxarSubjectDto?, DoubanRexxarStatsDto?> {
        val key = "${mediaType}_$id"
        rexxarCache[key]?.takeIf { System.currentTimeMillis() - it.at < ABSTRACT_CACHE_MS }?.let {
            return it.subject to it.stats
        }
        return ratingLocks.getOrPut(key) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            rexxarCache[key]?.takeIf { now - it.at < ABSTRACT_CACHE_MS }?.let { return@withLock it.subject to it.stats }

            val typesToTry = if (mediaType == "tv") listOf("tv", "movie") else listOf("movie", "tv")
            var bestSubject: DoubanRexxarSubjectDto? = null
            var bestStats: DoubanRexxarStatsDto? = null
            for (kind in typesToTry) {
                val (subject, statsDto) = coroutineScope {
                    val subjectJob = async {
                        runCatching { api.rexxarSubject("https://m.douban.com/rexxar/api/v2/$kind/$id") }.getOrNull()
                    }
                    val statsJob = async {
                        runCatching { api.rexxarRatingStats("https://m.douban.com/rexxar/api/v2/$kind/$id/rating") }.getOrNull()
                    }
                    subjectJob.await() to statsJob.await()
                }
                if (subject != null) bestSubject = subject
                if (statsDto?.stats?.isNotEmpty() == true) bestStats = statsDto
                if (bestSubject != null && bestStats != null) break
            }
            rexxarCache[key] = RexxarBundle(now, bestSubject, bestStats)
            bestSubject to bestStats
        }
    }

    private fun buildRatingDto(
        subject: DoubanRexxarSubjectDto?,
        statsDto: DoubanRexxarStatsDto?,
        fallbackScore: Float,
    ): TmdbDoubanRatingDto? {
        val rating = subject?.rating
        val score = (rating?.value?.toFloat()?.takeIf { it > 0f } ?: fallbackScore)
        val stats = statsDto?.stats?.takeIf { it.size == 5 }.orEmpty()
        val count = rating?.count ?: 0
        if (count <= 0 && stats.isEmpty() && score <= 0f) return null
        return TmdbDoubanRatingDto(
            score = score,
            starCount = rating?.star_count?.takeIf { it > 0f } ?: (score / 2f),
            voteCount = count,
            starDistribution = stats,
        )
    }

    private suspend fun safeAbstract(id: String): DoubanAbstractDto? {
        val now = System.currentTimeMillis()
        abstractCache[id]?.takeIf { now - it.at < ABSTRACT_CACHE_MS }?.let { return it.value }
        val resp = runCatching {
            api.abstract("https://movie.douban.com/j/subject_abstract?subject_id=$id")
        }.getOrNull()
        if (resp?.subject != null) {
            abstractCache[id] = CachedAbstract(now, resp)
        }
        return resp
    }

    private companion object {
        const val ABSTRACT_CACHE_MS = 6 * 60 * 60 * 1000L
    }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*"), "").trim()

    private fun DoubanSubjectDto.toMovieDto() = TmdbMovieDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { rememberCover(it, cover) },
        title = cleanTitle(title),
        overview = "",
        poster_path = cover,
        backdrop_path = cover,
        release_date = null,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toTvDto() = TmdbTvDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { rememberCover(it, cover) },
        name = cleanTitle(title),
        overview = "",
        poster_path = cover,
        backdrop_path = cover,
        vote_average = rate.toDoubleOrNull() ?: 0.0,
    )

    private fun DoubanSubjectDto.toSearchDto(mediaType: String) = TmdbSearchResultDto(
        id = (id.toIntOrNull() ?: id.hashCode()).also { rememberCover(it, cover) },
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
