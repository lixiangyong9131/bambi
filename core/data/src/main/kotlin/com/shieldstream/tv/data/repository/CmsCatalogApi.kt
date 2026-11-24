package com.shieldstream.tv.data.repository

import android.content.Context
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.dto.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modified build: catalog backed by CMS V10 resource APIs (如意/非凡/速播/红牛).
 *
 * One `ac=videolist` call returns poster, Douban score, synopsis, category, year, area and cast —
 * the exact data SeedHub showed, without touching Douban (6.4s/call) or a Cloudflare challenge.
 * Shaped as [TmdbApi] so the whole existing repository/UI stack works unchanged.
 */
@Singleton
class CmsCatalogApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val cms: CmsStreams,
    @ApplicationContext private val appContext: Context,
) : TmdbApi {

    companion object {
        private const val RYI = "http://www.ryzyw.com/api.php/provide/vod"
        private const val API = "http://ffzy5.tv/api.php/provide/vod"
        private const val JISU = "https://www.jisuzy.com/api.php/provide/vod"
        private const val HONGNIU = "https://www.hongniuzy2.com/api.php/provide/vod"
        /** Catalog source order. 如意 is fastest for list queries and has the best Chinese search. */
        private val SOURCES = listOf(RYI, API, JISU, HONGNIU)
        /** id = sourceIndex * STRIDE + vodId, so an id can be decoded without a cache lookup. */
        private const val STRIDE = 10_000_000

        // CMS V10 type ids (verified against ffzy/如意 class tree).
        const val TYPE_MOVIE = 1
        const val TYPE_TV = 2
        const val TYPE_VARIETY = 3
        const val TYPE_ANIME = 4
        const val TYPE_ACTION = 6
        const val TYPE_COMEDY = 7
        const val TYPE_ROMANCE = 8
        const val TYPE_SCIFI = 9
        const val TYPE_HORROR = 10
        const val TYPE_DRAMA_MOVIE = 11
        const val TYPE_WAR = 12
        const val TYPE_DOCUMENTARY = 20
        const val TYPE_CN_DRAMA = 13
        const val TYPE_HK_DRAMA = 14
        const val TYPE_KR_DRAMA = 15
        const val TYPE_WEST_DRAMA = 16
        const val TYPE_TW_DRAMA = 21
        const val TYPE_JP_DRAMA = 22
        const val TYPE_OVERSEAS_DRAMA = 23
        const val TYPE_THAI_DRAMA = 24
        const val TYPE_SHORT_DRAMA = 36
        const val TYPE_CN_ANIME = 29
        const val TYPE_JPKR_ANIME = 30
        const val TYPE_WEST_ANIME = 31
        const val TYPE_HKTW_ANIME = 32
        const val TYPE_OVERSEAS_ANIME = 33

        private val MOVIE_TYPES = setOf(TYPE_MOVIE, TYPE_ACTION, TYPE_COMEDY, TYPE_ROMANCE,
            TYPE_SCIFI, TYPE_HORROR, TYPE_DRAMA_MOVIE, TYPE_WAR, TYPE_DOCUMENTARY)
    }

    /** One CMS row, with everything the UI needs already present. */
    internal data class Vod(
        val vodId: String,
        val typeId: Int,
        val name: String,
        val pic: String,
        val score: Double,
        val content: String,
        val year: String?,
        val area: String?,
        val classTags: String?,
        val actor: String?,
        val director: String?,
        val remarks: String?,
        val duration: String?,
        val sourceIndex: Int,
    )

    private val vodCache = ConcurrentHashMap<Int, Vod>()
    private val titleOf = ConcurrentHashMap<Int, String>()
    private val prefs = appContext.getSharedPreferences("cms_catalog", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- TmdbApi surface

    override suspend fun trendingAll(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> =
        withContext(Dispatchers.IO) { mixed(page) }

    override suspend fun trendingAllWeek(key: String, page: Int): TmdbPaginated<TmdbSearchResultDto> =
        withContext(Dispatchers.IO) { mixed(page) }

    override suspend fun discoverMovieQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbMovieDto> =
        withContext(Dispatchers.IO) {
            val type = params["with_genres"].orEmpty().ifBlank { "cms:$TYPE_ACTION" }
            val year = params["year"]
            list(type, page = params["page"]?.toIntOrNull() ?: 1, year = year).mapMovies()
        }

    override suspend fun discoverTvQuery(key: String, params: Map<String, String>): TmdbPaginated<TmdbTvDto> =
        withContext(Dispatchers.IO) {
            val type = params["with_genres"].orEmpty().ifBlank { "cms:$TYPE_CN_DRAMA" }
            val year = params["year"]
            list(type, page = params["page"]?.toIntOrNull() ?: 1, year = year).mapSeries()
        }

    override suspend fun searchKeyword(key: String, query: String): TmdbPaginated<TmdbKeywordDto> =
        TmdbPaginated()

    override suspend fun trendingMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_ACTION", page).mapMovies() }

    override suspend fun trendingTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_CN_DRAMA", page).mapSeries() }

    override suspend fun popularMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_MOVIE", page).mapMovies() }

    override suspend fun popularTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_TV", page).mapSeries() }

    override suspend fun topRatedMovies(key: String, page: Int): TmdbPaginated<TmdbMovieDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_DRAMA_MOVIE", page).mapMovies() }

    override suspend fun topRatedTv(key: String, page: Int): TmdbPaginated<TmdbTvDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_CN_DRAMA", page).mapSeries() }

    override suspend fun nowPlayingMovies(key: String, region: String, page: Int): TmdbPaginated<TmdbMovieDto> =
        withContext(Dispatchers.IO) { list("cms:$TYPE_SCIFI", page).mapMovies() }

    override suspend fun discoverMovies(
        key: String, genres: String, sortBy: String, minVotes: Int, page: Int,
    ): TmdbPaginated<TmdbMovieDto> = withContext(Dispatchers.IO) { list(genres, page).mapMovies() }

    override suspend fun discoverTv(
        key: String, genres: String, sortBy: String, minVotes: Int, page: Int,
    ): TmdbPaginated<TmdbTvDto> = withContext(Dispatchers.IO) { list(genres, page).mapSeries() }

    override suspend fun search(key: String, query: String): TmdbSearchResponse =
        withContext(Dispatchers.IO) { searchCms(query) }

    override suspend fun movieDetails(
        id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String,
    ): TmdbMovieDetailDto = withContext(Dispatchers.IO) {
        val v = resolve(id)
        TmdbMovieDetailDto(
            id = id,
            title = clean(v?.name ?: titleOf[id] ?: ""),
            overview = plain(v?.content.orEmpty()),
            poster_path = v?.pic ?: prefs.getString("p_$id", null),
            backdrop_path = v?.pic ?: prefs.getString("p_$id", null),
            release_date = v?.year,
            vote_average = v?.score ?: 0.0,
            genres = genresOf(v),
            runtime = v?.duration?.toIntOrNull(),
            credits = castOf(v),
        )
    }

    override suspend fun tvDetails(
        id: Int, key: String, append: String, imageLanguages: String, videoLanguages: String,
    ): TmdbTvDetailDto = withContext(Dispatchers.IO) {
        val v = resolve(id)
        val title = clean(v?.name ?: titleOf[id] ?: "")
        titleOf[id] = title
        val episodes = if (title.isBlank()) 0 else runCatching { cms.getEpisodes(title).size }.getOrDefault(0)
        TmdbTvDetailDto(
            id = id,
            name = title,
            overview = plain(v?.content.orEmpty()),
            poster_path = v?.pic ?: prefs.getString("p_$id", null),
            backdrop_path = v?.pic ?: prefs.getString("p_$id", null),
            first_air_date = v?.year,
            vote_average = v?.score ?: 0.0,
            genres = genresOf(v),
            aggregate_credits = castOf(v),
            seasons = if (episodes > 0) listOf(
                TmdbSeasonSummaryDto(season_number = 1, name = "第1季", episode_count = episodes)
            ) else emptyList(),
            number_of_seasons = if (episodes > 0) 1 else 0,
        )
    }

    override suspend fun tvSeason(id: Int, season: Int, key: String): TmdbSeasonDto =
        withContext(Dispatchers.IO) {
            val title = titleOf[id] ?: prefs.getString("t_$id", null) ?: return@withContext TmdbSeasonDto()
            val eps = runCatching { cms.getEpisodes(title) }.getOrDefault(emptyList())
            TmdbSeasonDto(
                season_number = season,
                episodes = eps.map {
                    TmdbEpisodeDto(
                        episode_number = it.number,
                        season_number = season,
                        name = it.title,
                    )
                },
            )
        }

    override suspend fun collection(id: Int, key: String): TmdbCollectionDto = TmdbCollectionDto()
    override suspend fun person(id: Int, key: String, append: String): TmdbPersonDto = TmdbPersonDto()
    override suspend fun movieExternalIds(id: Int, key: String): TmdbExternalIdsDto = TmdbExternalIdsDto()
    override suspend fun tvExternalIds(id: Int, key: String): TmdbExternalIdsDto = TmdbExternalIdsDto()

    // ---------------------------------------------------------------- helpers

    /** genres may be "cms:29", a CMS type id, or a legacy TMDB genre id. */
    private fun cmsType(genres: String): Int {
        val first = genres.substringBefore(',').trim()
        if (first.startsWith("cms:")) return first.removePrefix("cms:").toIntOrNull() ?: TYPE_ACTION
        val id = first.toIntOrNull() ?: return TYPE_ACTION
        if (id in 1..36) return id
        return when (id) {
            28, 10759 -> TYPE_ACTION
            35 -> TYPE_COMEDY
            10749 -> TYPE_ROMANCE
            878, 10765 -> TYPE_SCIFI
            27 -> TYPE_HORROR
            18, 80, 53, 9648 -> TYPE_DRAMA_MOVIE
            10752, 10768 -> TYPE_WAR
            99 -> TYPE_DOCUMENTARY
            16 -> TYPE_JPKR_ANIME
            else -> TYPE_ACTION
        }
    }

    /** One videolist request per row — no per-item fan-out. */
    private suspend fun list(genres: String, page: Int, year: String? = null): List<Vod> {
        val type = cmsType(genres)
        for (index in SOURCES.indices) {
            val base = SOURCES[index]
            val url = buildString {
                append(base).append("?ac=videolist&t=").append(type).append("&pg=").append(page)
                if (!year.isNullOrBlank()) append("&year=").append(URLEncoder.encode(year, "UTF-8"))
            }
            val rows = parseRows(get(url), index)
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    /** Mixed first page for the billboard: movies + series + anime. */
    private suspend fun mixed(page: Int): TmdbPaginated<TmdbSearchResultDto> {
        val types = listOf(TYPE_ACTION, TYPE_CN_DRAMA, TYPE_JPKR_ANIME)
        val rows = withContext(Dispatchers.IO) {
            types.map { t -> async { list("cms:$t", page) } }.awaitAll().flatten()
        }
        return TmdbPaginated(
            page = page,
            results = rows.distinctBy { it.name }.map { it.toSearchDto() },
        )
    }

    /** CMS wd= search across sources, with poster + Douban score in the same response. */
    private suspend fun searchCms(query: String): TmdbSearchResponse {
        if (query.isBlank()) return TmdbSearchResponse()
        val terms = LinkedHashSet<String>()
        terms += query
        // Pinyin initials / Douban suggest are only used to find the Chinese CMS title.
        if (query.matches(Regex("(?i)^[a-z\\s]+$"))) {
            terms += runCatching { cms.searchByInitials(query) }.getOrDefault(emptyList())
        }
        val rows = withContext(Dispatchers.IO) {
            terms.flatMap { term ->
                SOURCES.mapIndexed { index, base ->
                    async {
                        parseRows(
                            get("$base?ac=videolist&wd=" + URLEncoder.encode(term, "UTF-8")),
                            index,
                        )
                    }
                }.awaitAll().flatten()
            }
        }
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<TmdbSearchResultDto>()
        for (row in rows) {
            val key = clean(row.name)
            if (key.isBlank() || !seen.add(key)) continue
            results += row.toSearchDto()
        }
        android.util.Log.i("CmsCatalog", "search '$query' -> ${results.size} results")
        return TmdbSearchResponse(results = results)
    }

    private suspend fun resolve(id: Int): Vod? {
        vodCache[id]?.let { return it }
        val sourceIndex = id / STRIDE
        val vodId = id % STRIDE
        if (sourceIndex in SOURCES.indices && vodId > 0) {
            val rows = parseRows(get(SOURCES[sourceIndex] + "?ac=detail&ids=" + vodId), sourceIndex)
            val v = rows.firstOrNull()
            if (v != null) { remember(id, v); return v }
        }
        // Cold-start fallback: only title/poster were persisted.
        val title = prefs.getString("t_$id", null)
        if (!title.isNullOrBlank()) titleOf[id] = title
        return null
    }

    private fun remember(id: Int, vod: Vod) {
        vodCache[id] = vod
        titleOf[id] = clean(vod.name)
        prefs.edit()
            .putString("t_$id", clean(vod.name))
            .putString("p_$id", vod.pic)
            .apply()
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    private fun parseRows(body: String?, sourceIndex: Int): List<Vod> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["list"]?.jsonArray
                ?.map { it.jsonObject }
                ?.mapNotNull { it.toVod(sourceIndex) }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.toVod(sourceIndex: Int): Vod? {
        val id = str("vod_id").ifBlank { return null }
        val name = str("vod_name").ifBlank { return null }
        val pic = str("vod_pic")
        val v = Vod(
            vodId = id,
            typeId = str("type_id").toIntOrNull() ?: str("type_id_1").toIntOrNull() ?: 0,
            name = name,
            pic = pic,
            score = str("vod_douban_score").toDoubleOrNull() ?: 0.0,
            content = str("vod_content"),
            year = str("vod_year").ifBlank { null },
            area = str("vod_area").ifBlank { null },
            classTags = str("vod_class").ifBlank { null },
            actor = str("vod_actor").ifBlank { null },
            director = str("vod_director").ifBlank { null },
            remarks = str("vod_remarks").ifBlank { null },
            duration = str("vod_duration").ifBlank { null },
            sourceIndex = sourceIndex,
        )
        val numeric = id.toIntOrNull()
        if (numeric != null) remember(sourceIndex * STRIDE + numeric % STRIDE, v)
        return v
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun clean(t: String): String = t
        .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*"), "")
        .replace(Regex("[（(].*?[)）]"), "")
        .trim()

    private fun plain(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun genresOf(v: Vod?): List<TmdbGenreDto> {
        if (v == null) return emptyList()
        val tags = v.classTags?.split(",")?.map { it.trim() }.orEmpty() +
            listOfNotNull(v.area?.trim())
        return tags.filter { it.isNotBlank() }.take(4).map { TmdbGenreDto(name = it) }
    }

    private fun castOf(v: Vod?): TmdbCreditsDto = TmdbCreditsDto(
        cast = v?.actor?.split(",")?.mapIndexed { i, name ->
            TmdbCastDto(id = i, name = name.trim(), order = i)
        }.orEmpty().filter { it.name.isNotBlank() },
    )

    private fun List<Vod>.mapMovies(): TmdbPaginated<TmdbMovieDto> = TmdbPaginated(
        results = map {
            TmdbMovieDto(
                id = idOf(it),
                title = clean(it.name),
                overview = plain(it.content),
                poster_path = it.pic,
                backdrop_path = it.pic,
                release_date = it.year,
                vote_average = it.score,
            )
        },
    )

    private fun List<Vod>.mapSeries(): TmdbPaginated<TmdbTvDto> = TmdbPaginated(
        results = map {
            TmdbTvDto(
                id = idOf(it),
                name = clean(it.name),
                overview = plain(it.content),
                poster_path = it.pic,
                backdrop_path = it.pic,
                first_air_date = it.year,
                vote_average = it.score,
            )
        },
    )

    private fun idOf(v: Vod): Int {
        val numeric = v.vodId.toIntOrNull() ?: return clean(v.name).hashCode()
        return v.sourceIndex * STRIDE + numeric % STRIDE
    }

    private fun Vod.toSearchDto(): TmdbSearchResultDto {
        val isMovie = typeId in MOVIE_TYPES
        return TmdbSearchResultDto(
            id = idOf(this),
            media_type = if (isMovie) "movie" else "tv",
            title = if (isMovie) clean(name) else null,
            name = if (isMovie) null else clean(name),
            overview = plain(content),
            poster_path = pic,
            backdrop_path = pic,
            release_date = if (isMovie) year else null,
            first_air_date = if (isMovie) null else year,
            vote_average = score,
        )
    }
}
