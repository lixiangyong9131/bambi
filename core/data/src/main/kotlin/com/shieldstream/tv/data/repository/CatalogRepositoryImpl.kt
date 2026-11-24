package com.shieldstream.tv.data.repository

import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import kotlinx.coroutines.CancellationException
import com.shieldstream.tv.model.Lang
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import com.shieldstream.tv.domain.StreamResults
import android.util.Log
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CatalogDetails
import com.shieldstream.tv.model.CollectionDetails
import com.shieldstream.tv.model.CollectionRef
import com.shieldstream.tv.model.Person
import com.shieldstream.tv.model.PersonDetails
import com.shieldstream.tv.model.Trailers
import com.shieldstream.tv.model.VideoInfo
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.CatalogRow
import com.shieldstream.tv.model.Category
import com.shieldstream.tv.model.FeaturedItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.RowStyle
import com.shieldstream.tv.model.Season
import com.shieldstream.tv.model.ShowAiring
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.api.TorrentioApi
import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.data.repository.streams.StreamSearch
import com.shieldstream.tv.data.repository.streams.englishTitle
import com.shieldstream.tv.network.dto.TmdbCastDto
import com.shieldstream.tv.network.dto.TmdbImagesDto
import com.shieldstream.tv.network.dto.TmdbSearchResultDto
import com.shieldstream.tv.network.dto.TmdbVideosDto
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val tmdb: TmdbApi,
    private val torrentio: TorrentioApi,
    private val extras: HomeExtrasSource,
    private val store: TokenStore,
    private val settings: SettingsRepository,
    private val profiles: com.shieldstream.tv.domain.ProfileRepository,
    private val texts: Texts,
    /** Copia en disco del último Inicio (null en tests). */
    private val diskCache: HomeDiskCache? = null,
    private val cmsStreams: com.shieldstream.tv.data.repository.streams.CmsStreams? = null,
) : CatalogRepository {

    private val kids: Boolean get() = profiles.active.value.kids

    private class Cached(val at: Long, val value: Any)
    private val homeCache = ConcurrentHashMap<String, Cached>()
    private val homeLocks = ConcurrentHashMap<String, Mutex>()

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var invalidatedAt = 0L

    override fun invalidateHome() {
        invalidatedAt = System.currentTimeMillis()
    }

    /** Guardado antes de [invalidateHome] y ya caducado: hay que esperar a la red. */
    private fun mustReload(savedAt: Long, now: Long) = savedAt < invalidatedAt && now - savedAt >= HOME_CACHE_MS
    // newKeySet() es de API 24 y la app admite desde la 21.
    private val refreshing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Memoria → disco → red.
     * - Menos de [HOME_CACHE_MS]: se usa tal cual.
     * - Hasta [HOME_STALE_MS]: se enseña al instante lo guardado y se actualiza por detrás (para la próxima vez).
     * - Más antiguo, o nada guardado: se espera a la red; si falla, se tira de lo guardado aunque sea viejo.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : List<*>> cachedHome(
        baseKey: String,
        readDisk: (HomeDiskCache, String) -> HomeDiskCache.Entry<T>?,
        writeDisk: (HomeDiskCache, String, T) -> Unit,
        load: suspend () -> T,
    ): T {
        // Los títulos y sinopsis dependen del idioma de la app.
        val cacheKey = "${baseKey}_${Lang.current.name}"
        return homeLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val hit = homeCache[cacheKey]
            if (hit != null && now - hit.at < HOME_CACHE_MS) return@withLock hit.value as T
            if (hit != null && now - hit.at < HOME_STALE_MS && !mustReload(hit.at, now)) {
                refreshInBackground(cacheKey, writeDisk, load)
                return@withLock hit.value as T
            }
            val disk = diskCache?.takeIf { hit == null }?.let { cache -> withContext(Dispatchers.IO) { readDisk(cache, cacheKey) } }
                ?.takeIf { it.value.isNotEmpty() }
            if (disk != null && now - disk.savedAt < HOME_STALE_MS && !mustReload(disk.savedAt, now)) {
                homeCache[cacheKey] = Cached(disk.savedAt, disk.value)
                if (now - disk.savedAt >= HOME_CACHE_MS) refreshInBackground(cacheKey, writeDisk, load)
                return@withLock disk.value
            }
            val fresh = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = (hit?.value as T?) ?: disk?.value
                if (fallback != null && fallback.isNotEmpty()) return@withLock fallback
                throw e
            }
            if (fresh.isNotEmpty()) {
                homeCache[cacheKey] = Cached(System.currentTimeMillis(), fresh)
                diskCache?.let { cache -> withContext(Dispatchers.IO) { writeDisk(cache, cacheKey, fresh) } }
            }
            fresh
        }
    }

    private fun <T : List<*>> refreshInBackground(
        cacheKey: String,
        writeDisk: (HomeDiskCache, String, T) -> Unit,
        load: suspend () -> T,
    ) {
        if (!refreshing.add(cacheKey)) return
        refreshScope.launch {
            try {
                val fresh = load()
                if (fresh.isNotEmpty()) {
                    homeCache[cacheKey] = Cached(System.currentTimeMillis(), fresh)
                    diskCache?.let { writeDisk(it, cacheKey, fresh) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("CatalogRepository", "Actualización en segundo plano de $cacheKey fallida: ${e.message}")
            } finally {
                refreshing.remove(cacheKey)
            }
        }
    }

    private suspend fun cachedRows(baseKey: String, load: suspend () -> List<CatalogRow>): List<CatalogRow> =
        cachedHome(baseKey, { c, k -> c.readRows(k) }, { c, k, v -> c.writeRows(k, v) }, load)

    private suspend fun cachedFeatured(baseKey: String, load: suspend () -> List<FeaturedItem>): List<FeaturedItem> =
        cachedHome(baseKey, { c, k -> c.readFeatured(k) }, { c, k, v -> c.writeFeatured(k, v) }, load)

    override suspend fun getFeatured(filter: HomeFilter): List<FeaturedItem> =
        if (kids) cachedFeatured("featured_kids_${filter.name}") { extras.kidsFeatured(filter) }
        else cachedFeatured("featured_${filter.name}") { extras.featured(filter) }

    override suspend fun getCategoryRows(category: Category, filter: HomeFilter): List<CatalogRow> =
        if (kids && category !in Category.KIDS_SAFE) emptyList() else extras.categoryRows(category, filter)

    /**
     * Modified build: a CMS category shown after pressing OK on a top tab.
     * Produces 3 rows (latest, more, discoveries) so the existing RowsBrowser renders it.
     */
    override suspend fun getCmsCategoryRows(typeId: Int, year: String?, filter: HomeFilter): List<CatalogRow> =
        coroutineScope {
            val isMovie = typeId in setOf(1, 6, 7, 8, 9, 10, 11, 12, 20, 34)
            val titles = listOf("最新更新", "更多内容", "继续发现")
            titles.mapIndexed { index, title ->
                async {
                    runCatching {
                        val params = buildMap {
                            put("with_genres", "cms:$typeId")
                            put("page", (index + 1).toString())
                            if (!year.isNullOrBlank()) put("year", year)
                        }
                        val items = if (isMovie) {
                            tmdb.discoverMovieQuery(key, params).results.map { it.toCatalogItem() }
                        } else {
                            tmdb.discoverTvQuery(key, params).results.map { it.toCatalogItem() }
                        }
                        CatalogRow("cms_cat_${typeId}_${year ?: "all"}_$index", title, items.filter { it.posterUrl != null })
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
        }

    // Catalog is backed by CMS resources (key-less); keep a non-blank placeholder so the
    // legacy "TMDB key missing" guard never blocks home content.
    private val key: String get() = store.tmdbKey?.takeIf { it.isNotBlank() } ?: "cms"

    private val streamSearch = StreamSearch(tmdb, torrentio, store, settings, texts, { key }, cmsStreams)

    private class RowSpec(
        val id: String,
        val title: String,
        val style: RowStyle = RowStyle.POSTER,
        /** Carga una página (1, 2, 3…) de la fila; TMDB devuelve 20 títulos por página. */
        val load: suspend (page: Int) -> List<CatalogItem>,
    )

    override suspend fun getHome(filter: HomeFilter, maxItems: Int): List<CatalogRow> {
        // Modified build: always one page per row for a fast first load (extra pages are fetched on scroll).
        val pages = 1
        return if (kids) cachedRows("home_kids_${filter.name}_p$pages") { loadHome(kidsRowsFor(filter, pages), pages) }
        else cachedRows("home_${filter.name}_p$pages") { loadHome(rowsFor(filter), pages) }
    }

    private suspend fun loadHome(specs: List<RowSpec>, pages: Int): List<CatalogRow> = coroutineScope {
        if (key.isBlank()) throw IllegalStateException(texts.get(StringsR.string.data_tmdb_key_missing_add_it_in_settings))
        specs
            .map { spec ->
                async {
                    runCatching {
                        val pageCount = if (spec.style == RowStyle.TOP10) 1 else pages
                        val items = (1..pageCount)
                            .map { page ->
                                // La primera página es obligatoria; si falla una de las siguientes, la fila se queda con lo que haya.
                                async { if (page == 1) spec.load(page) else runCatching { spec.load(page) }.getOrDefault(emptyList()) }
                            }
                            .awaitAll()
                            .flatten()
                            .distinctBy { it.type to it.id }
                            .filter { it.posterUrl != null }
                        val limited = if (spec.style == RowStyle.TOP10) items.take(10) else items
                        android.util.Log.i("DoubanHome", "row ${spec.id}: ${items.size} items (raw) -> ${limited.size}")
                        CatalogRow(spec.id, spec.title, limited, spec.style)
                    }.onFailure { android.util.Log.e("DoubanHome", "row ${spec.id} FAILED: ${it.javaClass.simpleName}: ${it.message}", it) }.getOrNull()
                }
            }
            .awaitAll()
            .filterNotNull()
            .filter { it.items.isNotEmpty() }
    }

    /**
     * Modified build: rows map directly to CMS V10 categories. Each row is ONE videolist
     * request (~1s) that already carries poster + Douban score + synopsis — no Douban fan-out.
     */
    private fun rowsFor(filter: HomeFilter): List<RowSpec> = when (filter) {
        HomeFilter.ALL -> listOf(
            RowSpec("hot_movies", "热门电影", RowStyle.TOP10) { page -> tmdb.trendingMovies(key, page = page).results.map { it.toCatalogItem() } },
            RowSpec("hot_tv", "热播剧集", RowStyle.TOP10) { page -> tmdb.trendingTv(key, page = page).results.map { it.toCatalogItem() } },
            RowSpec("action", "动作片") { page -> tmdb.discoverMovies(key, "cms:6", page = page).results.map { it.toCatalogItem() } },
            RowSpec("comedy", "喜剧片") { page -> tmdb.discoverMovies(key, "cms:7", page = page).results.map { it.toCatalogItem() } },
            RowSpec("scifi", "科幻片") { page -> tmdb.discoverMovies(key, "cms:9", page = page).results.map { it.toCatalogItem() } },
            RowSpec("cn_drama", "国产剧") { page -> tmdb.discoverTv(key, "cms:13", page = page).results.map { it.toCatalogItem() } },
            RowSpec("kr_drama", "韩剧") { page -> tmdb.discoverTv(key, "cms:15", page = page).results.map { it.toCatalogItem() } },
            RowSpec("west_drama", "欧美剧") { page -> tmdb.discoverTv(key, "cms:16", page = page).results.map { it.toCatalogItem() } },
            RowSpec("anime", "动漫") { page -> tmdb.discoverTv(key, "cms:30", page = page).results.map { it.toCatalogItem() } },
        )
        HomeFilter.MOVIES -> listOf(
            RowSpec("m_hot", "热门电影", RowStyle.TOP10) { page -> tmdb.trendingMovies(key, page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_action", "动作片") { page -> tmdb.discoverMovies(key, "cms:6", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_comedy", "喜剧片") { page -> tmdb.discoverMovies(key, "cms:7", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_romance", "爱情片") { page -> tmdb.discoverMovies(key, "cms:8", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_scifi", "科幻片") { page -> tmdb.discoverMovies(key, "cms:9", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_horror", "恐怖片") { page -> tmdb.discoverMovies(key, "cms:10", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_drama", "剧情片") { page -> tmdb.discoverMovies(key, "cms:11", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_war", "战争片") { page -> tmdb.discoverMovies(key, "cms:12", page = page).results.map { it.toCatalogItem() } },
            RowSpec("m_doc", "纪录片") { page -> tmdb.discoverMovies(key, "cms:20", page = page).results.map { it.toCatalogItem() } },
        )
        HomeFilter.TV -> listOf(
            RowSpec("t_hot", "热播剧集", RowStyle.TOP10) { page -> tmdb.trendingTv(key, page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_cn", "国产剧") { page -> tmdb.discoverTv(key, "cms:13", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_hk", "港剧") { page -> tmdb.discoverTv(key, "cms:14", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_kr", "韩剧") { page -> tmdb.discoverTv(key, "cms:15", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_west", "欧美剧") { page -> tmdb.discoverTv(key, "cms:16", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_tw", "台剧") { page -> tmdb.discoverTv(key, "cms:21", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_jp", "日剧") { page -> tmdb.discoverTv(key, "cms:22", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_thai", "泰剧") { page -> tmdb.discoverTv(key, "cms:24", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_short", "短剧") { page -> tmdb.discoverTv(key, "cms:36", page = page).results.map { it.toCatalogItem() } },
            RowSpec("t_variety", "综艺") { page -> tmdb.discoverTv(key, "cms:25", page = page).results.map { it.toCatalogItem() } },
        )
        HomeFilter.ANIME -> listOf(
            RowSpec("a_hot", "热门动漫", RowStyle.TOP10) { page -> tmdb.discoverTv(key, "cms:30", page).results.map { it.toCatalogItem() } },
            RowSpec("a_cn", "国产动漫") { page -> tmdb.discoverTv(key, "cms:29", page = page).results.map { it.toCatalogItem() } },
            RowSpec("a_jpkr", "日韩动漫") { page -> tmdb.discoverTv(key, "cms:30", page = page).results.map { it.toCatalogItem() } },
            RowSpec("a_west", "欧美动漫") { page -> tmdb.discoverTv(key, "cms:31", page = page).results.map { it.toCatalogItem() } },
            RowSpec("a_hktw", "港台动漫") { page -> tmdb.discoverTv(key, "cms:32", page = page).results.map { it.toCatalogItem() } },
        )
    }

    override suspend fun trending(): List<CatalogItem> =
        if (kids) kidsMovies(mapOf()).take(20)
        else tmdb.trendingAll(key).results.mapNotNull { it.toCatalogItemOrNull() }.filter { it.posterUrl != null }

    override suspend fun search(query: String): List<CatalogItem> {
        val results = tmdb.search(key, query).results
            .filter { !kids || it.genre_ids.any { g -> g in KIDS_GENRES } }
            .mapNotNull { it.toCatalogItemOrNull() } // descarta personas
        // Modified build: CMS-only titles (Chinese search hits from 如意/速播) have no Douban
        // poster. The old unconditional posterUrl filter silently dropped every one of them,
        // so a Chinese query could look empty even though the CMS had playable sources.
        // PosterCard falls back to the title text, so they are safe to keep.
        val withPoster = results.filter { it.posterUrl != null }
        val withoutPoster = results.filter { it.posterUrl == null }
        return withPoster + withoutPoster
    }

    override suspend fun searchOutsideKids(query: String): List<CatalogItem> {
        if (!kids) return emptyList()
        return tmdb.search(key, query).results
            .filter { it.genre_ids.none { g -> g in KIDS_GENRES } }
            .mapNotNull { it.toCatalogItemOrNull() }
            .filter { it.posterUrl != null }
    }

    private suspend fun kidsMovies(extra: Map<String, String>, genres: String = "16|10751"): List<CatalogItem> =
        tmdb.discoverMovieQuery(
            key,
            mapOf(
                "with_genres" to genres,
                "certification_country" to "US",
                "certification.lte" to "PG",
                "include_adult" to "false",
                "sort_by" to "popularity.desc",
            ) + extra,
        ).results.map { it.toCatalogItem() }

    private suspend fun kidsTv(extra: Map<String, String>, genres: String = "10762"): List<CatalogItem> =
        tmdb.discoverTvQuery(
            key,
            mapOf("with_genres" to genres, "include_adult" to "false", "sort_by" to "popularity.desc") + extra,
        ).results.map { it.toCatalogItem() }

    private fun kidsRowsFor(filter: HomeFilter, pages: Int): List<RowSpec> {
        // Las filas "Más…" siguen donde acaban las otras para no repetir títulos.
        fun p(page: Int) = mapOf("page" to page.toString())
        fun more(page: Int) = mapOf("page" to (pages + page).toString())
        val topRated = mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "300")
        val movies = listOf(
            RowSpec("k_movies", texts.get(StringsR.string.data_kids_movies)) { page -> kidsMovies(p(page)) },
            RowSpec("k_animation", texts.get(StringsR.string.data_animation)) { page -> kidsMovies(p(page), genres = "16") },
            RowSpec("k_family", texts.get(StringsR.string.data_for_the_whole_family)) { page -> kidsMovies(mapOf("certification.lte" to "G") + p(page), genres = "10751") },
            RowSpec("k_top_movies", texts.get(StringsR.string.data_top_rated)) { page -> kidsMovies(topRated + p(page)) },
            RowSpec("k_more_movies", texts.get(StringsR.string.data_more_movies)) { page -> kidsMovies(more(page)) },
        )
        val shows = listOf(
            RowSpec("k_tv", texts.get(StringsR.string.data_kids_shows)) { page -> kidsTv(p(page)) },
            RowSpec("k_cartoons", texts.get(StringsR.string.data_cartoons)) { page -> kidsTv(p(page), genres = "10762,16") },
            RowSpec("k_more_tv", texts.get(StringsR.string.data_more_shows)) { page -> kidsTv(more(page)) },
        )
        return when (filter) {
            HomeFilter.ALL -> listOf(movies[0], shows[0], movies[1], shows[1], movies[2], movies[3], shows[2])
            HomeFilter.MOVIES -> movies
            HomeFilter.TV -> shows
            HomeFilter.ANIME -> listOf(movies[1], shows[1], shows[2])
        }
    }

    override suspend fun getDetails(item: CatalogItem): CatalogDetails {
        val id = item.id.toIntOrNull() ?: return CatalogDetails(item)
        return if (item.type == MediaType.TV) {
            val d = tmdb.tvDetails(id, key, append = "images,external_ids,translations,aggregate_credits,videos")
            CatalogDetails(
                item = item.copy(
                    imdbId = d.external_ids?.imdb_id ?: item.imdbId,
                    overview = d.overview.ifBlank { item.overview },
                    backdropUrl = d.backdrop_path?.let { backdrop(it) } ?: item.backdropUrl,
                ),
                logoUrl = pickLogo(d.images),
                originalTitle = englishTitle(d.translations) ?: d.original_name,
                runtimeMinutes = d.episode_run_time.firstOrNull(),
                genres = d.genres.map { it.name },
                tagline = d.tagline?.takeIf { it.isNotBlank() },
                seasons = d.seasons
                    .filter { it.season_number > 0 && it.episode_count > 0 }
                    .map { Season(it.season_number, it.name.ifBlank { texts.get(StringsR.string.data_season_name, it.season_number) }, it.episode_count) },
                latestEpisode = d.last_episode_to_air?.toEpisode(),
                nextEpisode = d.next_episode_to_air?.toEpisode(),
                cast = (d.aggregate_credits ?: d.credits)?.cast.orEmpty().toPeople(),
                directors = d.created_by.map { Person(it.id, it.name, null, it.profile_path?.let(TmdbImages::profile)) },
                trailerKey = d.videos.trailers().firstOrNull(),
                trailerKeys = d.videos.trailers(),
            )
        } else {
            val d = tmdb.movieDetails(id, key, append = "images,translations,credits,videos")
            CatalogDetails(
                item = item.copy(
                    imdbId = d.imdb_id ?: item.imdbId,
                    overview = d.overview.ifBlank { item.overview },
                    backdropUrl = d.backdrop_path?.let { backdrop(it) } ?: item.backdropUrl,
                ),
                logoUrl = pickLogo(d.images),
                originalTitle = englishTitle(d.translations) ?: d.original_title,
                runtimeMinutes = d.runtime?.takeIf { it > 0 },
                genres = d.genres.map { it.name },
                tagline = d.tagline?.takeIf { it.isNotBlank() },
                cast = d.credits?.cast.orEmpty().toPeople(),
                directors = d.credits?.crew.orEmpty().filter { it.job == "Director" }.distinctBy { it.id }
                    .map { Person(it.id, it.name, null, it.profile_path?.let(TmdbImages::profile)) },
                trailerKey = d.videos.trailers().firstOrNull(),
                trailerKeys = d.videos.trailers(),
                collection = d.belongs_to_collection?.takeIf { it.id > 0 }?.let { CollectionRef(it.id, it.name) },
            )
        }
    }

    private fun List<TmdbCastDto>.toPeople(): List<Person> = sortedBy { it.order }
        .distinctBy { it.id }
        .take(MAX_CAST)
        .map { c ->
            val role = c.character?.takeIf { it.isNotBlank() } ?: c.roles.firstNotNullOfOrNull { r -> r.character?.takeIf { it.isNotBlank() } }
            Person(c.id, c.name, role, c.profile_path?.let(TmdbImages::profile))
        }

    private fun TmdbVideosDto?.trailers(): List<String> = this?.results.orEmpty()
        .map { VideoInfo(it.key, it.site, it.type, it.official, it.iso_639_1) }
        .let { Trailers.rank(it, if (Lang.isEnglish) "en" else "es") }
        .take(MAX_TRAILERS)

    override suspend fun getCollection(id: Int): CollectionDetails {
        val c = tmdb.collection(id, key)
        return CollectionDetails(
            id = c.id,
            name = c.name,
            overview = c.overview,
            backdropUrl = c.backdrop_path?.let(TmdbImages::backdrop),
            parts = c.parts
                .map { it.copy(media_type = it.media_type.ifBlank { "movie" }) }
                .sortedBy { (it.release_date ?: it.first_air_date).orEmpty().ifBlank { "9999" } }
                .mapNotNull { it.toCatalogItemOrNull() }
                .filter { it.posterUrl != null },
        )
    }

    override suspend fun getPerson(id: Int): PersonDetails {
        val p = tmdb.person(id, key)
        val credits = p.combined_credits
        val cast = credits?.cast.orEmpty().map { it to it.popularity }
        val crew = credits?.crew.orEmpty()
            .filter { it.job == "Director" || it.job == "Creator" || it.job == "Screenplay" || it.job == "Writer" }
            .map {
                TmdbSearchResultDto(
                    id = it.id, media_type = it.media_type, title = it.title, name = it.name, overview = it.overview,
                    poster_path = it.poster_path, backdrop_path = it.backdrop_path, release_date = it.release_date,
                    first_air_date = it.first_air_date, vote_average = it.vote_average, vote_count = it.vote_count,
                    genre_ids = it.genre_ids, popularity = it.popularity,
                ) to it.popularity
            }
        val items = (cast + crew)
            .filter { (dto, _) -> !kids || dto.genre_ids.any { g -> g in KIDS_GENRES } }
            // Talk shows y apariciones sueltas hunden la lista: se ordena por votos y popularidad.
            .sortedByDescending { (dto, pop) -> dto.vote_count * 0.01 + pop }
            .mapNotNull { (dto, _) -> dto.toCatalogItemOrNull() }
            .filter { it.posterUrl != null }
            .distinctBy { it.key }
            .take(MAX_PERSON_CREDITS)
        return PersonDetails(
            person = Person(p.id, p.name, null, p.profile_path?.let(TmdbImages::profile)),
            biography = p.biography,
            department = p.known_for_department,
            birthday = p.birthday,
            placeOfBirth = p.place_of_birth,
            knownFor = items,
        )
    }

    private val itemCache = ConcurrentHashMap<String, CatalogItem>()

    override suspend fun getItem(type: MediaType, id: String): CatalogItem? {
        val cacheKey = "${type.name}_$id"
        itemCache[cacheKey]?.let { return it }
        val tmdbId = id.toIntOrNull() ?: return null
        val item = if (type == MediaType.TV) {
            val d = tmdb.tvDetails(tmdbId, key, append = "external_ids")
            CatalogItem(
                id = id,
                imdbId = d.external_ids?.imdb_id,
                type = MediaType.TV,
                title = d.name,
                overview = d.overview,
                posterUrl = d.poster_path?.let { poster(it) },
                backdropUrl = d.backdrop_path?.let { backdrop(it) },
                year = d.first_air_date?.take(4)?.toIntOrNull(),
                rating = d.vote_average.toFloat(),
                genres = d.genres.map { it.name },
            )
        } else {
            val d = tmdb.movieDetails(tmdbId, key, append = "")
            CatalogItem(
                id = id,
                imdbId = d.imdb_id,
                type = MediaType.MOVIE,
                title = d.title,
                overview = d.overview,
                posterUrl = d.poster_path?.let { poster(it) },
                backdropUrl = d.backdrop_path?.let { backdrop(it) },
                year = d.release_date?.take(4)?.toIntOrNull(),
                rating = d.vote_average.toFloat(),
                genres = d.genres.map { it.name },
            )
        }
        itemCache[cacheKey] = item
        return item
    }

    private class CachedAiring(val at: Long, val value: ShowAiring)
    private val airingCache = ConcurrentHashMap<String, CachedAiring>()
    private val airingLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun getShowAiring(id: String): ShowAiring? {
        val tmdbId = id.toIntOrNull() ?: return null
        return airingLocks.getOrPut(id) { Mutex() }.withLock {
            airingCache[id]?.takeIf { System.currentTimeMillis() - it.at < AIRING_CACHE_MS }?.let { return@withLock it.value }
            val d = tmdb.tvDetails(tmdbId, key, append = "external_ids")
            val item = CatalogItem(
                id = id,
                imdbId = d.external_ids?.imdb_id,
                type = MediaType.TV,
                title = d.name,
                overview = d.overview,
                posterUrl = d.poster_path?.let { poster(it) },
                backdropUrl = d.backdrop_path?.let { backdrop(it) },
                year = d.first_air_date?.take(4)?.toIntOrNull(),
                rating = d.vote_average.toFloat(),
                genres = d.genres.map { it.name },
            )
            ShowAiring(
                item = item,
                latestEpisode = d.last_episode_to_air?.toEpisode(),
                nextEpisode = d.next_episode_to_air?.toEpisode(),
                seasons = d.seasons
                    .filter { it.season_number > 0 && it.episode_count > 0 }
                    .map { Season(it.season_number, it.name, it.episode_count) },
            ).also {
                itemCache["TV_$id"] = item
                airingCache[id] = CachedAiring(System.currentTimeMillis(), it)
            }
        }
    }

    private class CachedEpisodes(val at: Long, val value: List<Episode>)
    private val episodesCache = ConcurrentHashMap<String, CachedEpisodes>()
    private val episodeLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun getEpisodes(item: CatalogItem, season: Int): List<Episode> {
        val id = item.id.toIntOrNull() ?: return emptyList()
        val cacheKey = "$id:$season"
        return episodeLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            episodesCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < AIRING_CACHE_MS }?.let { return@withLock it.value }
            tmdb.tvSeason(id, season, key).episodes.mapNotNull { it.toEpisode(defaultSeason = season) }
                .also { episodes -> if (episodes.isNotEmpty()) episodesCache[cacheKey] = CachedEpisodes(System.currentTimeMillis(), episodes) }
        }
    }

    private fun com.shieldstream.tv.network.dto.TmdbEpisodeDto.toEpisode(defaultSeason: Int? = null): Episode? {
        val seasonNumber = season_number.takeIf { it > 0 } ?: defaultSeason ?: return null
        if (episode_number <= 0) return null
        return Episode(
            season = seasonNumber,
            number = episode_number,
            title = name.ifBlank { texts.get(StringsR.string.data_episode_name, episode_number) },
            overview = overview,
            stillUrl = still_path?.let { TmdbImages.still(it) },
            runtimeMinutes = runtime,
            airDate = air_date,
        )
    }

    /** Las fuentes de un título: cada buscador está en `streams/` ([StreamSearch]). */
    override fun getStreams(item: CatalogItem, episode: Episode?): Flow<StreamResults> = streamSearch.streams(item, episode)

    private fun pickLogo(images: TmdbImagesDto?): String? = TmdbImages.logo(images, Lang.current.code)

    private fun poster(path: String) = TmdbImages.poster(path)
    private fun backdrop(path: String) = TmdbImages.backdrop(path)

    private companion object {
        val KIDS_GENRES = setOf(16, 10751, 10762)
        const val MAX_CAST = 20
        const val MAX_TRAILERS = 8
        const val MAX_PERSON_CREDITS = 40
    }
}

private const val HOME_CACHE_MS = 30 * 60 * 1000L
/** Hasta cuándo lo guardado en disco se enseña al abrir mientras se actualiza por detrás. */
private const val HOME_STALE_MS = 24 * 60 * 60 * 1000L
private const val TMDB_PAGE_SIZE = 20
/** 5 páginas = 100 títulos, el máximo de "Elementos por fila de Inicio". */
private const val MAX_HOME_PAGES = 5

private const val AIRING_CACHE_MS = 6 * 60 * 60 * 1000L
