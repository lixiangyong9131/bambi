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
    private fun isLowResCover(url: String?): Boolean =
        url.isNullOrBlank() || url.contains("imageView2/") || url.contains("s_ratio_poster")

    private fun rememberCover(id: Int, url: String) {
        if (url.isBlank() || url.contains("imageView2/")) return
        val upgraded = TmdbImages.poster(url)
        coverCache[id] = upgraded
        coverPrefs.edit().putString(id.toString(), upgraded).apply()
    }
    /** Douban id -> title & match hints, so tvSeason() can look up the matching CMS show. */
    private val titleOf = ConcurrentHashMap<Int, String>()
    private val hintOf = ConcurrentHashMap<Int, com.shieldstream.tv.data.repository.streams.CmsStreams.MatchHint>()
    private data class CachedAbstract(val at: Long, val value: DoubanAbstractDto)
    private val abstractCache = ConcurrentHashMap<String, CachedAbstract>()
    @Volatile
    private var abstractCooldownUntil = 0L
    private data class CachedSeries(val at: Long, val items: List<com.shieldstream.tv.model.CatalogItem>)
    private val seriesCache = ConcurrentHashMap<String, CachedSeries>()
    private val seriesLocks = ConcurrentHashMap<String, Mutex>()
    private data class CachedFeed(val at: Long, val items: List<DoubanRexxarCollectionItemDto>)
    private val feedCache = ConcurrentHashMap<String, CachedFeed>()
    private val feedCooldownUntil = ConcurrentHashMap<String, Long>()
    private val feedLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun trendingAll(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) {
        val weekly = async { safeCollection("movie_weekly_best", page) }
        val hot = async { safeCollection("movie_hot_gaia", page) }
        val merged = interleave(weekly.await(), hot.await())
        if (merged.isNotEmpty()) {
            TmdbPaginated(page = page, results = merged.map { it.toSearchDto("movie") })
        } else {
            moviesAll(page)
        }
    }

    override suspend fun trendingAllWeek(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> = withContext(Dispatchers.IO) {
        // Mixed movie + TV official weekly charts so the featured billboard stays varied.
        val movies = async { safeCollection("movie_weekly_best", page) }
        val tvCn = async { safeCollection("tv_chinese_best_weekly", page) }
        val tvGlobal = async { safeCollection("tv_global_best_weekly", page) }
        val movieDtos = movies.await().map { it.toSearchDto("movie") }
        val tvDtos = interleave(tvCn.await(), tvGlobal.await()).map { it.toSearchDto("tv") }
        val combined = interleaveSearch(movieDtos, tvDtos)
        if (combined.isNotEmpty()) {
            TmdbPaginated(page = page, results = combined)
        } else {
            val fallbackMovies = moviesAll(page).results
            val fallbackShows = safeSubjects("tv", "热门", page).map { it.toSearchDto("tv") }
            TmdbPaginated(page = page, results = (fallbackMovies + fallbackShows).distinctBy { it.media_type + it.id })
        }
    }

    override suspend fun discoverMovieQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val sort = doubanSortFor(params["sort_by"])
        val explicitCollection = params["douban_collection"]
        val explicitTags = params["douban_tags"]
        val rexxar = when {
            !explicitCollection.isNullOrBlank() -> safeCollection(explicitCollection, page)
            !explicitTags.isNullOrBlank() -> safeRecommend("movie", explicitTags, sort, page)
            else -> {
                val tags = recommendTagsForQuery(
                    genres = params["with_genres"].orEmpty(),
                    country = params["with_origin_country"].orEmpty(),
                    language = params["with_original_language"].orEmpty(),
                    isTv = false,
                )
                safeRecommend("movie", tags, sort, page)
            }
        }
        if (rexxar.isNotEmpty()) {
            TmdbPaginated(page = page, results = rexxar.map { it.toMovieDto() })
        } else {
            val fallbackTag = explicitTags?.takeIf { it.isNotBlank() } ?: genreToTag(params["with_genres"].orEmpty())
            moviesByTag(fallbackTag, page)
        }
    }

    override suspend fun discoverTvQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) {
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val genres = params["with_genres"].orEmpty()
        val country = params["with_origin_country"].orEmpty()
        val language = params["with_original_language"].orEmpty()
        val sortBy = params["sort_by"].orEmpty()
        val explicitCollection = params["douban_collection"]
        val explicitTags = params["douban_tags"]
        val collectionId = explicitCollection ?: if (genres.isBlank() && sortBy.isBlank() && explicitTags.isNullOrBlank()) {
            when (country.uppercase()) {
                "CN" -> "tv_domestic"
                "US" -> "tv_american"
                "KR" -> "tv_korean"
                "JP" -> "tv_japanese"
                else -> null
            }
        } else null
        val rexxar = when {
            collectionId != null -> safeCollection(collectionId, page)
            !explicitTags.isNullOrBlank() -> safeRecommend("tv", explicitTags, doubanSortFor(sortBy), page)
            else -> {
                val tags = recommendTagsForQuery(genres, country, language, isTv = true)
                safeRecommend("tv", tags, doubanSortFor(sortBy), page)
            }
        }
        if (rexxar.isNotEmpty()) {
            TmdbPaginated(page = page, results = rexxar.map { it.toTvDto() })
        } else {
            val fallbackTag = explicitTags?.takeIf { it.isNotBlank() } ?: when (country.uppercase()) {
                "CN" -> "国产剧"
                "US" -> "美剧"
                "KR" -> "韩剧"
                "JP" -> "日剧"
                else -> genreToTvTag(genres)
            }
            tvByTag(fallbackTag, page)
        }
    }

    override suspend fun searchKeyword(key: String, query: String): TmdbPaginated<TmdbKeywordDto> = withContext(Dispatchers.IO) { TmdbPaginated() }

    override suspend fun trendingMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val items = safeCollection("movie_weekly_best", page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toMovieDto() })
        else moviesByTag("热门", page)
    }

    override suspend fun trendingTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) {
        val cn = async { safeCollection("tv_chinese_best_weekly", page) }
        val global = async { safeCollection("tv_global_best_weekly", page) }
        val items = interleave(cn.await(), global.await())
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toTvDto() })
        else tvByTag("热门", page)
    }

    override suspend fun popularMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val items = safeCollection("movie_hot_gaia", page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toMovieDto() })
        else moviesByTag("热门", page)
    }

    override suspend fun popularTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) {
        val items = safeCollection("tv_hot", page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toTvDto() })
        else tvByTag("热门", page)
    }

    override suspend fun topRatedMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val items = safeCollection("movie_top250", page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toMovieDto() })
        else moviesByTag("豆瓣高分", page)
    }

    override suspend fun topRatedTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) {
        val items = safeRecommend("tv", tags = "", sort = "S", page = page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toTvDto() })
        else tvByTag("豆瓣高分", page)
    }

    override suspend fun nowPlayingMovies(key: String, region: String, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val items = safeCollection("movie_showing", page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toMovieDto() })
        else moviesByTag("最新", page)
    }

    override suspend fun discoverMovies(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) {
        val tag = movieGenreTag(genres)
        val items = safeRecommend("movie", tag, doubanSortFor(sortBy), page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toMovieDto() })
        else moviesByTag(genreToTag(genres), page)
    }

    override suspend fun discoverTv(key: String, genres: String, sortBy: String, minVotes: Int, page: Int): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) {
        val tag = tvGenreTag(genres)
        val items = safeRecommend("tv", tag, doubanSortFor(sortBy), page)
        if (items.isNotEmpty()) TmdbPaginated(page = page, results = items.map { it.toTvDto() })
        else tvByTag(genreToTvTag(genres), page)
    }

    override suspend fun search(key: String, query: String): TmdbSearchResponse = withContext(Dispatchers.IO) { searchDouban(query) }

    override suspend fun movieDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbMovieDetailDto = withContext(Dispatchers.IO) {
        movieDetail(id, full = append.contains("images") || append.contains("credits") || append.contains("videos"))
    }

    override suspend fun tvDetails(id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String): TmdbTvDetailDto = withContext(Dispatchers.IO) {
        tvDetail(id, full = append.contains("images") || append.contains("credits") || append.contains("videos"))
    }

    override suspend fun tvSeason(id: Int, season: Int, key: String): TmdbSeasonDto = withContext(Dispatchers.IO) {
        // Modified build: episodes come from the CMS (Douban has none). Use the title & hint cached by details.
        val title = titleOf[id] ?: return@withContext TmdbSeasonDto()
        val hint = hintOf[id] ?: com.shieldstream.tv.data.repository.streams.CmsStreams.MatchHint(isTv = true)
        val eps = runCatching { cms.getEpisodes(title, hint) }.getOrDefault(emptyList())
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

    private fun movieGenreTag(genres: String): String {
        val id = genres.split(',', '|').firstOrNull()?.trim().orEmpty()
        return when (id) {
            "28" -> "动作"
            "35" -> "喜剧"
            "18" -> "剧情"
            "878" -> "科幻"
            "80" -> "犯罪"
            "53" -> "惊悚"
            "27" -> "恐怖"
            "16", "10762" -> "动画"
            "9648" -> "悬疑"
            "99" -> "纪录片"
            "10749" -> "爱情"
            "14" -> "奇幻"
            "12" -> "冒险"
            "10751" -> "家庭"
            "10752", "36" -> "战争"
            "37" -> "西部"
            "10402" -> "音乐"
            else -> ""
        }
    }

    private fun tvGenreTag(genres: String): String {
        val id = genres.split(',', '|').firstOrNull()?.trim().orEmpty()
        return when (id) {
            "18" -> "剧情"
            "80" -> "犯罪"
            "10765", "14", "878" -> "科幻"
            "10759", "28", "12" -> "动作"
            "35" -> "喜剧"
            "16", "10762" -> "动画"
            "9648", "53" -> "悬疑"
            "27" -> "恐怖"
            "10764" -> "真人秀"
            "10751" -> "家庭"
            "10768", "36", "10752" -> "历史"
            "99" -> "悬疑"
            else -> ""
        }
    }

    private fun recommendTagsForQuery(
        genres: String,
        country: String,
        language: String,
        isTv: Boolean,
    ): String {
        val parts = ArrayList<String>(2)
        val regionTag = when (country.uppercase()) {
            "KR" -> "韩国"
            "JP" -> "日本"
            "US" -> "美国"
            "ES" -> "西班牙"
            "CN" -> if (isTv) "国产" else "华语"
            else -> when (language.lowercase()) {
                "ja" -> "日本"
                "ko" -> "韩国"
                "es" -> "西班牙"
                else -> ""
            }
        }
        if (regionTag.isNotEmpty()) parts += regionTag
        val genreTag = if (isTv) tvGenreTag(genres) else movieGenreTag(genres)
        if (genreTag.isNotEmpty() && genreTag != regionTag) parts += genreTag
        return parts.joinToString(",")
    }

    private fun doubanSortFor(sortBy: String?): String = when {
        sortBy.isNullOrBlank() -> "U"
        sortBy.startsWith("vote_average") -> "S"
        sortBy.contains("date") -> "R"
        else -> "U"
    }

    private fun genreToTag(genres: String): String {
        val id = genres.split(',', '|').firstOrNull()?.trim().orEmpty()
        return when (id) {
            "28" -> "动作片"
            "35" -> "喜剧片"
            "18" -> "经典"
            "878" -> "科幻片"
            "80" -> "犯罪片"
            "99", "9648" -> "悬疑片"
            else -> "热门"
        }
    }

    /** TV genre ids -> Douban TV tags fallback when j/search_subjects is used. */
    private fun genreToTvTag(genres: String): String {
        val id = genres.split(',', '|').firstOrNull()?.trim().orEmpty()
        return when (id) {
            "18", "80" -> "国产剧"
            "10765", "10759" -> "美剧"
            "35" -> "韩剧"
            "16" -> "日本动画"
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
        info("moviesByTag($tag,p$page): ${subs.size} subjects")
        return TmdbPaginated(page = page, results = subs.map { it.toMovieDto() })
    }

    private suspend fun tvByTag(tag: String, page: Int): TmdbPaginated<TmdbTvDto> {
        val subs = safeSubjects("tv", tag, page)
        info("tvByTag($tag,p$page): ${subs.size} subjects")
        return TmdbPaginated(page = page, results = subs.map { it.toTvDto() })
    }

    private suspend fun searchDouban(query: String): TmdbSearchResponse {
        if (query.isBlank()) return TmdbSearchResponse()
        // Modified build: query Douban suggest (or Rexxar search fallback when movie.douban.com 403s)
        // and direct CMS search in parallel.
        val (rawSuggestions, initialHits) = coroutineScope {
            val sugJob = async {
                if (System.currentTimeMillis() < abstractCooldownUntil) {
                    emptyList()
                } else {
                    runCatching { api.suggest(query) }.getOrDefault(emptyList())
                }
            }
            val cmsJob = async { runCatching { cms.searchTitles(query) }.getOrDefault(emptyList()) }
            sugJob.await() to cmsJob.await()
        }
        val rexxarSuggestions = if (rawSuggestions.isEmpty()) {
            runCatching {
                val enc = java.net.URLEncoder.encode(query, "UTF-8")
                api.rexxarSearch("$REXXAR_BASE/search?q=$enc&start=0&count=15")
                    .subjects?.items.orEmpty()
                    .filter { (it.targetType == "tv" || it.targetType == "movie") && it.target != null }
                    .mapNotNull { entry ->
                        val t = entry.target ?: return@mapNotNull null
                        val clean = cleanTitle(t.title)
                        if (t.id.isBlank() || clean.isBlank()) return@mapNotNull null
                        DoubanSuggestDto(
                            title = clean,
                            img = t.bestCoverUrl(),
                            url = "",
                            id = t.id,
                            episode = if (entry.targetType == "tv") "1" else "",
                            year = t.year,
                        )
                    }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val suggestions = if (rawSuggestions.isNotEmpty()) rawSuggestions else rexxarSuggestions
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
            val sugYear = sug.year.ifBlank { null }
            results += TmdbSearchResultDto(
                id = did.toIntOrNull() ?: did.hashCode(),
                media_type = if (isTv) "tv" else "movie",
                title = if (!isTv) sugTitle else null,
                name = if (isTv) sugTitle else null,
                overview = "",
                poster_path = sug.img,
                release_date = if (!isTv) sugYear else null,
                first_air_date = if (isTv) sugYear else null,
                vote_average = 0.0,
            )
        }
        // CMS titles with no Douban suggestion: still show them (playable, no poster).
        for (hit in hits) {
            if (suggestions.any { matches(hit.title, it.title) }) continue
            val isTv = hit.type.contains("剧") || hit.type.contains("动漫") || hit.type.contains("动画")
            results += TmdbSearchResultDto(
                id = hit.id.toIntOrNull() ?: hit.title.hashCode(),
                media_type = if (isTv) "tv" else "movie",
                title = if (!isTv) hit.title else null,
                name = if (isTv) hit.title else null,
                overview = hit.remarks,
                poster_path = null,
                vote_average = 0.0,
                genres = listOf(hit.type).filter { it.isNotBlank() },
            )
        }
        android.util.Log.i("DoubanHome", "search results -> ${results.size}")
        return TmdbSearchResponse(results = results.distinctBy { it.title ?: it.name })
    }

    private suspend fun movieDetail(id: Int, full: Boolean): TmdbMovieDetailDto {
        val (abstractDto, initialRexxar) = coroutineScope {
            val absJob = async { safeAbstract(id.toString()) }
            val rexJob = async {
                if (full || System.currentTimeMillis() < abstractCooldownUntil) rexxarDetail("movie", id) else (null to null)
            }
            absJob.await() to rexJob.await()
        }
        val rexxar = if (abstractDto?.subject == null && initialRexxar.first == null) {
            rexxarDetail("movie", id)
        } else {
            initialRexxar
        }
        val s = abstractDto?.subject
        val rxSubject = rexxar.first
        val rawTitle = s?.title ?: rxSubject?.title.orEmpty()
        if (rawTitle.isBlank()) return TmdbMovieDetailDto(id = id)
        val clean = cleanTitle(rawTitle)
        val genresList = s?.types?.takeIf { it.isNotEmpty() } ?: rxSubject?.genres.orEmpty()
        val releaseYearStr = (s?.releaseYear ?: rxSubject?.year.orEmpty()).ifBlank { null }
        val matchHint = com.shieldstream.tv.data.repository.streams.CmsStreams.MatchHint(
            year = releaseYearStr?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
            isTv = false,
            isAnime = genresList.any { it.contains("动画") || it.equals("Animation", ignoreCase = true) },
        )
        val fallbackScore = (s?.rate?.toDoubleOrNull() ?: rxSubject?.rating?.value ?: 0.0).toFloat()
        val doubanRating = if (full) buildRatingDto(rxSubject, rexxar.second, fallbackScore) else null
        val (cover, synopsis) = coroutineScope {
            val coverJob = async { coverFor(id, rawTitle, rxSubject?.pic) }
            val fallbackDesc = s?.shortComment?.content.orEmpty().ifBlank { rxSubject?.intro.orEmpty() }
            val descJob = async {
                if (full) {
                    runCatching { cms.getDescription(clean, matchHint) }.getOrDefault("").ifBlank { fallbackDesc }
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
            release_date = releaseYearStr,
            vote_average = (doubanRating?.score?.toDouble() ?: fallbackScore.toDouble()),
            genres = genresList.map { TmdbGenreDto(name = it) },
            credits = cast,
            douban_rating = doubanRating,
        )
    }

    private suspend fun tvDetail(id: Int, full: Boolean): TmdbTvDetailDto {
        val (abstractDto, initialRexxar) = coroutineScope {
            val absJob = async { safeAbstract(id.toString()) }
            val rexJob = async {
                if (full || System.currentTimeMillis() < abstractCooldownUntil) rexxarDetail("tv", id) else (null to null)
            }
            absJob.await() to rexJob.await()
        }
        val rexxar = if (abstractDto?.subject == null && initialRexxar.first == null) {
            rexxarDetail("tv", id)
        } else {
            initialRexxar
        }
        val s = abstractDto?.subject
        val rxSubject = rexxar.first
        val rawTitle = s?.title ?: rxSubject?.title.orEmpty()
        if (rawTitle.isBlank()) return TmdbTvDetailDto(id = id)
        val clean = cleanTitle(rawTitle)
        val genresList = s?.types?.takeIf { it.isNotEmpty() } ?: rxSubject?.genres.orEmpty()
        val releaseYearStr = (s?.releaseYear ?: rxSubject?.year.orEmpty()).ifBlank { null }
        val matchHint = com.shieldstream.tv.data.repository.streams.CmsStreams.MatchHint(
            year = releaseYearStr?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
            isTv = true,
            isAnime = genresList.any { it.contains("动画") || it.equals("Animation", ignoreCase = true) },
        )
        titleOf[id] = clean
        hintOf[id] = matchHint
        val fallbackScore = (s?.rate?.toDoubleOrNull() ?: rxSubject?.rating?.value ?: 0.0).toFloat()
        val doubanRating = if (full) buildRatingDto(rxSubject, rexxar.second, fallbackScore) else null
        // Modified build: Douban has no season data. Fetch cover + CMS bundle in parallel only when
        // full details are requested; lightweight item lookups skip extra calls.
        val (cover, epsCount, synopsis) = coroutineScope {
            val coverJob = async { coverFor(id, rawTitle, rxSubject?.pic) }
            val epsJob = async {
                if (full) runCatching { cms.getEpisodes(clean, matchHint).size }.getOrDefault(0) else 0
            }
            val fallbackDesc = s?.shortComment?.content.orEmpty().ifBlank { rxSubject?.intro.orEmpty() }
            val descJob = async {
                if (full) {
                    runCatching { cms.getDescription(clean, matchHint) }.getOrDefault("").ifBlank { fallbackDesc }
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

    private suspend fun safeCollection(collectionId: String, page: Int, count: Int = PAGE_SIZE): List<DoubanRexxarCollectionItemDto> {
        val cacheKey = "col_${collectionId}_p${page}_c$count"
        return cachedFeed(cacheKey) {
            val start = (page - 1) * count
            api.rexxarCollection(
                "$REXXAR_BASE/subject_collection/$collectionId/items?start=$start&count=$count",
            ).subjectCollectionItems.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.bestCoverUrl().isNotBlank() }
        }
    }

    private suspend fun safeRecommend(
        kind: String,
        tags: String,
        sort: String,
        page: Int,
        count: Int = PAGE_SIZE,
    ): List<DoubanRexxarCollectionItemDto> {
        val cacheKey = "rec_${kind}_${tags}_${sort}_p${page}_c$count"
        return cachedFeed(cacheKey) {
            val start = (page - 1) * count
            val encodedTags = java.net.URLEncoder.encode(tags, "UTF-8")
            api.rexxarRecommend(
                "$REXXAR_BASE/$kind/recommend?refresh=0&start=$start&count=$count&tags=$encodedTags&sort=$sort",
            ).items.filter {
                (it.type == kind || it.type == "movie" || it.type == "tv") &&
                    it.id.isNotBlank() &&
                    it.title.isNotBlank() &&
                    it.bestCoverUrl().isNotBlank()
            }
        }
    }

    private suspend fun cachedFeed(
        cacheKey: String,
        loader: suspend () -> List<DoubanRexxarCollectionItemDto>,
    ): List<DoubanRexxarCollectionItemDto> {
        val now = System.currentTimeMillis()
        feedCache[cacheKey]?.takeIf { now - it.at < ABSTRACT_CACHE_MS }?.let { return it.items }
        feedCooldownUntil[cacheKey]?.takeIf { now < it }?.let { return emptyList() }
        return feedLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            val ts = System.currentTimeMillis()
            feedCache[cacheKey]?.takeIf { ts - it.at < ABSTRACT_CACHE_MS }?.let { return@withLock it.items }
            feedCooldownUntil[cacheKey]?.takeIf { ts < it }?.let { return@withLock emptyList() }
            val result = runCatching { loader() }
            val items = result.getOrElse { e ->
                warn("rexxar feed $cacheKey failed: ${e.message}")
                feedCooldownUntil[cacheKey] = ts + RATE_LIMIT_COOLDOWN_MS
                emptyList()
            }
            if (items.isNotEmpty()) {
                feedCooldownUntil.remove(cacheKey)
                feedCache[cacheKey] = CachedFeed(ts, items)
            }
            items
        }
    }

    private fun interleave(
        first: List<DoubanRexxarCollectionItemDto>,
        second: List<DoubanRexxarCollectionItemDto>,
    ): List<DoubanRexxarCollectionItemDto> {
        val out = ArrayList<DoubanRexxarCollectionItemDto>(first.size + second.size)
        val seen = HashSet<String>()
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.takeIf { seen.add(it.id) }?.let { out += it }
            second.getOrNull(i)?.takeIf { seen.add(it.id) }?.let { out += it }
        }
        return out
    }

    private fun interleaveSearch(
        first: List<TmdbSearchResultDto>,
        second: List<TmdbSearchResultDto>,
    ): List<TmdbSearchResultDto> {
        val out = ArrayList<TmdbSearchResultDto>(first.size + second.size)
        val seen = HashSet<String>()
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.takeIf { seen.add("${it.media_type}:${it.id}") }?.let { out += it }
            second.getOrNull(i)?.takeIf { seen.add("${it.media_type}:${it.id}") }?.let { out += it }
        }
        return out
    }

    /** Modified build: fill the cover from Rexxar / Douban suggest, upgrading any low-res search thumbnails. */
    private suspend fun coverFor(id: Int, title: String, rexxarPic: DoubanRexxarPicDto? = null): String? {
        val picUrl = rexxarPic?.large?.takeIf { it.isNotBlank() && !it.contains("imageView2/") }
            ?: rexxarPic?.normal?.takeIf { it.isNotBlank() && !it.contains("imageView2/") }
        if (picUrl != null) {
            rememberCover(id, picUrl)
            return TmdbImages.poster(picUrl)
        }
        coverCache[id]?.takeIf { !isLowResCover(it) }?.let { return it }
        coverPrefs.getString(id.toString(), null)?.takeIf { !isLowResCover(it) }?.let {
            coverCache[id] = it
            return it
        }
        // Titles may carry an English original suffix ("肖申克的救赎 The Shawshank Redemption")
        // which Douban suggest does not index - strip it so the Chinese title matches.
        var q = cleanTitle(title)
        if (q.any { it.code > 0x2E80 }) {
            q = q.replace(Regex(" *[A-Za-z][A-Za-z0-9 '’.,!?-]*$"), "").trim()
        }
        if (System.currentTimeMillis() < abstractCooldownUntil) {
            return coverCache[id] ?: coverPrefs.getString(id.toString(), null)
        }
        val img = runCatching { api.suggest(q).firstOrNull()?.img }
            .getOrDefault(null)
        if (!img.isNullOrBlank()) {
            rememberCover(id, img)
            return TmdbImages.poster(img)
        }
        return coverCache[id] ?: coverPrefs.getString(id.toString(), null)
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
        if (now < abstractCooldownUntil) return null
        val resp = runCatching {
            api.abstract("https://movie.douban.com/j/subject_abstract?subject_id=$id")
        }.getOrElse {
            // movie.douban.com may return HTTP 403/418 under burst load; cool down briefly so lookups fall back to m.douban.com.
            abstractCooldownUntil = now + ABSTRACT_COOLDOWN_MS
            null
        }
        if (resp?.subject != null) {
            abstractCooldownUntil = 0L
            abstractCache[id] = CachedAbstract(now, resp)
        }
        return resp
    }

    /**
     * Queries Douban public search + suggest concurrently to collect all seasons / installments
     * belonging to the same TV series (e.g. 绝命毒师 第一季..第五季, 凡人修仙传 年番4..).
     */
    suspend fun getSeriesWorks(item: com.shieldstream.tv.model.CatalogItem): List<com.shieldstream.tv.model.CatalogItem> =
        withContext(Dispatchers.IO) {
            if (item.type != com.shieldstream.tv.model.MediaType.TV) return@withContext emptyList()
            val queries = seriesQueryCandidates(item.title)
            val base = queries.lastOrNull().orEmpty()
            if (base.length < 2) return@withContext emptyList()
            val itemIsAnime = if (item.genres.isEmpty()) {
                null
            } else {
                item.genres.any { it.contains("动画") || it.contains("动漫") || it.equals("Animation", ignoreCase = true) }
            }
            val cacheKey = "${base}_${itemIsAnime ?: "any"}"
            val raw = seriesLocks.getOrPut(cacheKey) { Mutex() }.withLock {
                val now = System.currentTimeMillis()
                seriesCache[cacheKey]?.takeIf { now - it.at < SERIES_CACHE_MS }?.let { return@withLock it.items }

                val merged = LinkedHashMap<String, com.shieldstream.tv.model.CatalogItem>()
                for (q in queries) {
                    val (rexxarItems, suggestItems) = coroutineScope {
                        val rxJob = async {
                            runCatching {
                                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                                api.rexxarSearch("$REXXAR_BASE/search?q=$enc&type=tv&start=0&count=20")
                                    .subjects?.items.orEmpty()
                                    .filter { it.targetType == "tv" && it.target != null }
                                    .mapNotNull { it.target }
                            }.getOrDefault(emptyList())
                        }
                        val sgJob = async {
                            runCatching { api.suggest(q) }.getOrDefault(emptyList())
                                .filter { it.episode.isNotBlank() }
                        }
                        rxJob.await() to sgJob.await()
                    }
                    for (t in rexxarItems) {
                        val candId = t.id.trim()
                        val title = cleanTitle(t.title)
                        if (candId.isBlank() || title.isBlank()) continue
                        val candGenres = t.subtitleGenres()
                        if (!matchesSeries(q, title, candGenres, itemIsAnime)) continue
                        val cover = t.bestCoverUrl().takeIf { it.isNotBlank() }?.let { TmdbImages.poster(it) }
                        val yr = t.year.takeIf { YEAR_4_REGEX.matches(it) }?.toIntOrNull()
                            ?: YEAR_ANY_REGEX.find(t.cardSubtitle)?.value?.toIntOrNull()
                        val numericId = candId.toIntOrNull() ?: candId.hashCode()
                        t.bestCoverUrl().takeIf { it.isNotBlank() }?.let { rememberCover(numericId, it) }
                        merged[candId] = com.shieldstream.tv.model.CatalogItem(
                            id = candId,
                            imdbId = null,
                            type = com.shieldstream.tv.model.MediaType.TV,
                            title = title,
                            overview = t.cardSubtitle,
                            posterUrl = cover,
                            backdropUrl = cover,
                            year = yr,
                            rating = t.rating?.value?.toFloat()?.takeIf { it > 0f },
                            genres = candGenres,
                        )
                    }
                    for (sug in suggestItems) {
                        val candId = sug.id.ifBlank { sug.url.extractDoubanId() }.trim()
                        val title = cleanTitle(sug.title)
                        if (candId.isBlank() || title.isBlank()) continue
                        if (!matchesSeries(q, title, emptyList(), itemIsAnime)) continue
                        if (merged.containsKey(candId)) continue
                        val cover = sug.img.takeIf { it.isNotBlank() }?.let { TmdbImages.poster(it) }
                        val yr = sug.year.takeIf { YEAR_4_REGEX.matches(it) }?.toIntOrNull()
                        val numericId = candId.toIntOrNull() ?: candId.hashCode()
                        sug.img.takeIf { it.isNotBlank() }?.let { rememberCover(numericId, it) }
                        merged[candId] = com.shieldstream.tv.model.CatalogItem(
                            id = candId,
                            imdbId = null,
                            type = com.shieldstream.tv.model.MediaType.TV,
                            title = title,
                            overview = sug.episode.takeIf { it.isNotBlank() }?.let { "全${it}集" }.orEmpty(),
                            posterUrl = cover,
                            backdropUrl = cover,
                            year = yr,
                            rating = null,
                            genres = emptyList(),
                        )
                    }
                    if (merged.size >= 2) break
                }
                val enriched = if (merged.size >= 2) {
                    coroutineScope {
                        merged.values.map { entry ->
                            async {
                                val numId = entry.id.toIntOrNull()
                                if (numId != null && (isLowResCover(entry.posterUrl) || entry.rating == null)) {
                                    val (subj, _) = rexxarDetail("tv", numId)
                                    val hdCover = subj?.pic?.let { pic ->
                                        pic.large.takeIf { it.isNotBlank() } ?: pic.normal.takeIf { it.isNotBlank() }
                                    }?.let { rawUrl ->
                                        rememberCover(numId, rawUrl)
                                        TmdbImages.poster(rawUrl)
                                    }
                                    entry.copy(
                                        posterUrl = hdCover ?: entry.posterUrl,
                                        backdropUrl = hdCover ?: entry.backdropUrl,
                                        rating = entry.rating ?: subj?.rating?.value?.toFloat()?.takeIf { it > 0f },
                                        genres = entry.genres.ifEmpty { subj?.genres.orEmpty() },
                                    )
                                } else {
                                    entry
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    merged.values.toList()
                }
                val list = enriched.sortedWith(
                    compareBy<com.shieldstream.tv.model.CatalogItem>(
                        { it.year ?: Int.MAX_VALUE },
                        { seriesSeasonOrdinal(it.title) ?: 0 },
                        { it.id },
                    ),
                )
                if (list.isNotEmpty()) {
                    seriesCache[cacheKey] = CachedSeries(now, list)
                }
                list
            }
            if (raw.size <= 1) return@withContext raw
            // Ensure current item stays represented if Douban search omitted that exact ID.
            val withCurrent = if (raw.any { it.id == item.id }) {
                raw
            } else {
                (raw + item).sortedWith(
                    compareBy<com.shieldstream.tv.model.CatalogItem>(
                        { it.year ?: Int.MAX_VALUE },
                        { seriesSeasonOrdinal(it.title) ?: 0 },
                        { it.id },
                    ),
                )
            }
            withCurrent
        }

    internal fun seriesQueryCandidates(rawTitle: String): List<String> {
        val cleaned = cleanTitle(rawTitle)
        if (cleaned.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val stripped = stripSeasonMarkers(cleaned)
        if (stripped.length >= 2) out += stripped
        val beforeColon = stripped.replace(Regex("[:：].*$"), "").trim()
        if (beforeColon.length >= 2) out += beforeColon
        val zhiIdx = beforeColon.indexOf('之')
        if (zhiIdx >= 3) out += beforeColon.substring(0, zhiIdx).trim()
        return out.toList()
    }

    private fun stripSeasonMarkers(title: String): String {
        var current = title.trim()
        val trailingSeason = Regex(
            "\\s*(?:第[一二三四五六七八九十百零两\\d]+[季部辑篇]|(?:Season|Series|Part|Vol\\.?|Book)\\s*[IVX\\d]+|S\\d{1,2}|年番\\s*\\d*|特别篇|番外篇?|前传|后传|终章|完结篇)$",
            RegexOption.IGNORE_CASE,
        )
        repeat(2) {
            val next = current.replace(trailingSeason, "").trim()
            if (next.length >= 2 && next != current) current = next
        }
        return current
    }

    private fun matchesSeries(
        base: String,
        candidateTitle: String,
        candidateGenres: List<String>,
        itemIsAnime: Boolean?,
    ): Boolean {
        if (candidateTitle.contains("预告") || candidateTitle.contains("花絮")) return false
        val normBase = base.replace(Regex("\\s+"), "")
        val normCand = cleanTitle(candidateTitle).replace(Regex("\\s+"), "")
        val candBase = stripSeasonMarkers(candidateTitle).replace(Regex("\\s+"), "")
        val titleOk = candBase.equals(normBase, ignoreCase = true) || (
            normBase.length >= 2 &&
                normCand.startsWith(normBase, ignoreCase = true) &&
                (normCand.length == normBase.length || normCand[normBase.length] in SERIES_BOUNDARY_CHARS)
            )
        if (!titleOk) return false
        if (itemIsAnime != null && candidateGenres.isNotEmpty()) {
            val candIsAnime = candidateGenres.any {
                it.contains("动画") || it.contains("动漫") || it.equals("Animation", ignoreCase = true)
            }
            if (candIsAnime != itemIsAnime) return false
        }
        return true
    }

    private fun seriesSeasonOrdinal(title: String): Int? {
        Regex("第([一二三四五六七八九十\\d]+)[季部]").find(title)?.groupValues?.getOrNull(1)?.let { token ->
            return when (token) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                else -> token.toIntOrNull()
            }
        }
        Regex("年番\\s*(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("(?:Season|Part)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun info(message: String) {
        runCatching { android.util.Log.i("DoubanHome", message) }
    }

    private fun warn(message: String) {
        runCatching { android.util.Log.w("DoubanHome", message) }
    }

    private companion object {
        const val ABSTRACT_CACHE_MS = 6 * 60 * 60 * 1000L
        const val ABSTRACT_COOLDOWN_MS = 5 * 60 * 1000L
        const val SERIES_CACHE_MS = 30 * 60 * 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val REXXAR_BASE = "https://m.douban.com/rexxar/api/v2"
        const val PAGE_SIZE = 20
        val YEAR_4_REGEX = Regex("^\\d{4}$")
        val YEAR_ANY_REGEX = Regex("(?:19|20)\\d{2}")
        val SERIES_BOUNDARY_CHARS = setOf('第', '年', '：', ':', '之', '前', '后', '特', '番', '剧', '续', '全', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'S', 's')
    }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*"), "").trim()

    private fun DoubanRexxarCollectionItemDto.releaseYearString(): String? {
        val y = year.takeIf { YEAR_4_REGEX.matches(it) } ?: YEAR_ANY_REGEX.find(cardSubtitle)?.value
        return y?.let { "$it-01-01" }
    }

    private fun DoubanRexxarCollectionItemDto.toMovieDto(): TmdbMovieDto {
        val cover = bestCoverUrl()
        val numericId = id.toIntOrNull() ?: id.hashCode()
        rememberCover(numericId, cover)
        return TmdbMovieDto(
            id = numericId,
            title = cleanTitle(title),
            overview = description.ifBlank { cardSubtitle },
            poster_path = cover.ifBlank { null },
            backdrop_path = cover.ifBlank { null },
            release_date = releaseYearString(),
            vote_average = rating?.value ?: 0.0,
            genres = subtitleGenres(),
        )
    }

    private fun DoubanRexxarCollectionItemDto.toTvDto(): TmdbTvDto {
        val cover = bestCoverUrl()
        val numericId = id.toIntOrNull() ?: id.hashCode()
        val clean = cleanTitle(title)
        rememberCover(numericId, cover)
        if (clean.isNotBlank()) titleOf.putIfAbsent(numericId, clean)
        return TmdbTvDto(
            id = numericId,
            name = clean,
            overview = description.ifBlank { cardSubtitle },
            poster_path = cover.ifBlank { null },
            backdrop_path = cover.ifBlank { null },
            first_air_date = releaseYearString(),
            vote_average = rating?.value ?: 0.0,
            genres = subtitleGenres(),
        )
    }

    private fun DoubanRexxarCollectionItemDto.toSearchDto(defaultType: String): TmdbSearchResultDto {
        val cover = bestCoverUrl()
        val numericId = id.toIntOrNull() ?: id.hashCode()
        val clean = cleanTitle(title)
        rememberCover(numericId, cover)
        val kind = if (type == "movie" || type == "tv") type else defaultType
        if (kind == "tv" && clean.isNotBlank()) titleOf.putIfAbsent(numericId, clean)
        val yearStr = releaseYearString()
        return TmdbSearchResultDto(
            id = numericId,
            media_type = kind,
            title = if (kind == "movie") clean else null,
            name = if (kind == "tv") clean else null,
            overview = description.ifBlank { cardSubtitle },
            poster_path = cover.ifBlank { null },
            backdrop_path = cover.ifBlank { null },
            release_date = if (kind == "movie") yearStr else null,
            first_air_date = if (kind == "tv") yearStr else null,
            vote_average = rating?.value ?: 0.0,
            genres = subtitleGenres(),
        )
    }

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
        id = (id.toIntOrNull() ?: id.hashCode()).also {
            rememberCover(it, cover)
            val clean = cleanTitle(title)
            if (clean.isNotBlank()) titleOf.putIfAbsent(it, clean)
        },
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
