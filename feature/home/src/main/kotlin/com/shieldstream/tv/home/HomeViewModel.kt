package com.shieldstream.tv.home

import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.EpisodeReleaseRepository
import com.shieldstream.tv.domain.EpisodeTrackingRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.domain.TraktRepository
import com.shieldstream.tv.domain.NewEpisodes
import com.shieldstream.tv.domain.UpNext
import com.shieldstream.tv.domain.UpNextCandidate
import com.shieldstream.tv.domain.ContinueEntry
import com.shieldstream.tv.domain.ContinueWatching
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.domain.mapConcurrent
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.CatalogRow
import com.shieldstream.tv.model.ContinueInfo
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.FeaturedItem
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.RowStyle
import com.shieldstream.tv.model.UpNextInfo
import com.shieldstream.tv.ui.component.RailDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Content(val rows: List<CatalogRow>, val featured: List<FeaturedItem> = emptyList()) : HomeUiState
}

data class SectionedRow(
    val section: RailDestination,
    val filter: HomeFilter?,
    val row: CatalogRow,
)

sealed interface UnifiedHomeUiState {
    data object Loading : UnifiedHomeUiState
    data class Error(val message: String) : UnifiedHomeUiState
    data class Content(val sections: List<SectionedRow>) : UnifiedHomeUiState
}

private data class HomeRowQuery(
    val filter: HomeFilter,
    val maxItems: Int,
)

private data class NewEpisodeQuery(
    val showIds: List<String>,
    val watchedKeys: Set<String>,
    val dismissedKeys: Set<String>,
    val maxItems: Int,
)

private data class UpNextQuery(
    val candidates: List<UpNextCandidate>,
    val watchedKeys: Set<String>,
    val dismissedKeys: Set<String>,
    val maxItems: Int,
)

private data class RecommendationQuery(
    val filter: HomeFilter,
    val profileId: String,
    val traktUser: String?,
    val kids: Boolean,
    val maxItems: Int,
)

private data class RecommendationResult(
    val query: RecommendationQuery,
    val rows: List<CatalogRow>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val watchProgress: WatchProgressRepository,
    private val episodeReleases: EpisodeReleaseRepository,
    private val tracking: EpisodeTrackingRepository,
    settings: SettingsRepository,
    private val myList: MyListRepository,
    profiles: ProfileRepository,
    private val trakt: TraktRepository,
    private val texts: Texts,
    private val preloader: HomePreloader,
) : ViewModel() {

    val kidsProfile: Boolean = profiles.active.value.kids

    val hiddenCategories: StateFlow<Set<String>> = settings.hiddenCategories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val myListKeys: StateFlow<Set<String>> = myList.entries
        .map { entries -> entries.map { "${it.type.name}:${it.id}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleMyList(item: CatalogItem): Boolean {
        val adding = !myList.contains(item.type, item.id)
        if (adding) myList.add(item.type, item.id) else myList.remove(item.type, item.id)
        return adding
    }

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

    private val continueRows = MutableStateFlow<List<CatalogRow>?>(null)
    private val filterFlow = MutableStateFlow<HomeFilter?>(null)
    private val homeRowQuery = combine(filterFlow.filterNotNull(), settings.homeRowItemLimit) { filter, maxItems ->
        HomeRowQuery(filter, maxItems)
    }

    private val newEpisodeRows = MutableStateFlow<List<CatalogRow>?>(null)

    private val myListRows = MutableStateFlow<List<CatalogRow>?>(null)

    private val upNextRows = MutableStateFlow<List<CatalogRow>>(emptyList())

    private val recommendationRows = MutableStateFlow<List<CatalogRow>>(emptyList())

    private val extraRowItems = MutableStateFlow<Map<Pair<HomeFilter, String>, List<CatalogItem>>>(emptyMap())
    private val rowNextPage = java.util.concurrent.ConcurrentHashMap<Pair<HomeFilter, String>, Int>()
    private val rowLoadingMore = java.util.Collections.synchronizedSet(HashSet<Pair<HomeFilter, String>>())
    private val rowExhausted = java.util.Collections.synchronizedSet(HashSet<Pair<HomeFilter, String>>())

    private val trailingRows = combine(myListRows, recommendationRows) { listRow, recommendationRow ->
        listRow?.plus(recommendationRow)
    }

    private val baseAndPersonalState = combine(
        _uiState,
        continueRows,
        newEpisodeRows,
        upNextRows,
        trailingRows,
    ) { catalog, continueRow, newRow, upNextRow, trailingRow ->
        when {
            catalog !is HomeUiState.Content -> catalog
            continueRow == null || newRow == null || trailingRow == null -> HomeUiState.Loading
            else -> {
                val releases = newRow.flatMap { it.newEpisodes.entries }.associate { it.key to it.value }
                val counts = newRow.flatMap { it.newEpisodeCounts.entries }.associate { it.key to it.value }
                val rows = continueRow + upNextRow + newRow + trailingRow + catalog.rows
                catalog.copy(
                    rows = rows.map { row ->
                        if (row.style == RowStyle.NEW_EPISODES || row.style == RowStyle.UP_NEXT || releases.isEmpty()) return@map row
                        row.copy(
                            newEpisodes = row.items.mapNotNull { item ->
                                releases[item.key]?.let { episode -> item.key to episode }
                            }.toMap(),
                            newEpisodeCounts = row.items.mapNotNull { item ->
                                counts[item.key]?.let { count -> item.key to count }
                            }.toMap(),
                        )
                    },
                )
            }
        }
    }.combine(settings.homeRowItemLimit) { state, maxItems ->
        if (state !is HomeUiState.Content) return@combine state
        state.copy(
            rows = state.rows.map { row ->
                if (row.style == RowStyle.CONTINUE) row else row.withItemLimit(maxItems)
            },
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        baseAndPersonalState,
        filterFlow,
        extraRowItems,
    ) { state, filter, extras ->
        if (state !is HomeUiState.Content || filter == null || extras.isEmpty()) return@combine state
        state.copy(
            rows = state.rows.map { row ->
                val more = extras[filter to row.id]
                if (more.isNullOrEmpty()) row
                else row.copy(items = (row.items + more).distinctBy { it.key })
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState.Loading)

    private var loadedFilter: HomeFilter? = null
    /** Máximo de títulos por fila (Ajustes): decide cuántas páginas se piden a TMDB. */
    private val rowItemLimit = settings.homeRowItemLimit
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_HOME_ROW_ITEM_LIMIT)
    private var loadedLimit: Int? = null
    private var prefetchJob: Job? = null

    private var unifiedMode = false
    private var unifiedJob: Job? = null
    private val _unifiedCatalog = MutableStateFlow<Map<HomeFilter, List<CatalogRow>>?>(null)
    private val _unifiedError = MutableStateFlow<String?>(null)

    val unifiedState: StateFlow<UnifiedHomeUiState> = combine(
        _unifiedCatalog,
        _unifiedError,
        continueRows,
        myListRows,
        settings.homeRowItemLimit,
        extraRowItems,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val catalog = values[0] as Map<HomeFilter, List<CatalogRow>>?
        val errorMsg = values[1] as String?
        @Suppress("UNCHECKED_CAST")
        val continueList = values[2] as List<CatalogRow>?
        @Suppress("UNCHECKED_CAST")
        val myListList = values[3] as List<CatalogRow>?
        val maxItems = values[4] as Int
        @Suppress("UNCHECKED_CAST")
        val extras = values[5] as Map<Pair<HomeFilter, String>, List<CatalogItem>>

        if (catalog == null || continueList == null || myListList == null) {
            if (errorMsg != null && continueList == null && myListList == null) {
                return@combine UnifiedHomeUiState.Error(errorMsg)
            }
            return@combine UnifiedHomeUiState.Loading
        }
        val sections = buildList {
            val seenIds = HashSet<String>()
            fun uniqueRow(row: CatalogRow): CatalogRow =
                if (seenIds.add(row.id)) row else row.copy(id = "${row.id}_${seenIds.size}")

            continueList.orEmpty().forEach { row ->
                add(SectionedRow(RailDestination.HOME, null, uniqueRow(row)))
            }
            myListList.orEmpty().forEach { row ->
                add(SectionedRow(RailDestination.HOME, null, uniqueRow(row.withItemLimit(maxItems))))
            }
            fun appendSection(section: RailDestination, filter: HomeFilter) {
                catalog[filter].orEmpty().forEach { baseRow ->
                    val more = extras[filter to baseRow.id]
                    val limited = baseRow.withItemLimit(maxItems)
                    val merged = if (more.isNullOrEmpty()) limited else limited.copy(items = (limited.items + more).distinctBy { it.key })
                    add(SectionedRow(section, filter, uniqueRow(merged)))
                }
            }
            appendSection(RailDestination.TV, HomeFilter.TV)
            appendSection(RailDestination.MOVIES, HomeFilter.MOVIES)
            appendSection(RailDestination.ANIME, HomeFilter.ANIME)
        }
        when {
            sections.isNotEmpty() -> UnifiedHomeUiState.Content(sections = sections)
            errorMsg != null -> UnifiedHomeUiState.Error(errorMsg)
            else -> UnifiedHomeUiState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UnifiedHomeUiState.Loading)

    init {
        // Tras mucho rato fuera, la intro recarga el Inicio: se cambia sin pasar por la pantalla de carga.
        viewModelScope.launch {
            preloader.refreshed.collect { version ->
                if (version <= 0) return@collect
                if (unifiedMode) {
                    loadUnified(force = true, silent = true)
                } else {
                    val filter = loadedFilter
                    if (filter != null) load(filter, force = true, silent = true)
                }
            }
        }
        // Al cambiar "Elementos por fila de Inicio" se vuelven a pedir las filas con el nuevo tamaño.
        viewModelScope.launch {
            rowItemLimit.collect { limit ->
                if (loadedLimit != null && limit != loadedLimit) {
                    if (unifiedMode) {
                        loadUnified(force = true)
                    } else {
                        val filter = loadedFilter
                        if (filter != null) load(filter, force = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(filterFlow.filterNotNull(), watchProgress.progress) { filter, all ->
                ContinueWatching.entries(all, filter, catalogRepository::isAnime)
            }
                .distinctUntilChanged()
                .mapLatest { entries -> listOfNotNull(buildContinueRow(entries)) }
                .collect { continueRows.value = it }
        }
        viewModelScope.launch {
            combine(
                homeRowQuery,
                watchProgress.progress,
                myList.entries,
                tracking.followedShowIds.combine(tracking.hiddenShowIds) { followed, hidden -> followed to hidden },
                tracking.dismissedEpisodeKeys,
            ) { rowQuery, progress, list, (followed, hidden), dismissed ->
                NewEpisodeQuery(
                    showIds = NewEpisodes.candidateIds(
                        progress,
                        list,
                        rowQuery.filter,
                        followed,
                        maxItems = rowQuery.maxItems,
                        hiddenShowIds = hidden,
                        isAnime = catalogRepository::isAnime,
                    ),
                    watchedKeys = progress.filterValues { it.isWatched }.keys,
                    dismissedKeys = dismissed,
                    maxItems = rowQuery.maxItems,
                )
            }
                .distinctUntilChanged()
                .mapLatest { query -> listOfNotNull(buildNewEpisodesRow(query)) }
                .collect { newEpisodeRows.value = it }
        }
        viewModelScope.launch {
            combine(homeRowQuery, myList.entries) { rowQuery, entries ->
                entries.filter {
                    when (rowQuery.filter) {
                        HomeFilter.ALL -> true
                        HomeFilter.MOVIES -> it.type == MediaType.MOVIE
                        HomeFilter.TV -> it.type == MediaType.TV
                        HomeFilter.ANIME -> catalogRepository.isAnime(it.id)
                    }
                }.take(rowQuery.maxItems)
            }
                .distinctUntilChanged()
                .mapLatest { entries -> listOfNotNull(buildMyListRow(entries)) }
                .collect { myListRows.value = it }
        }
        viewModelScope.launch {
            combine(
                homeRowQuery,
                watchProgress.progress,
                tracking.dismissedEpisodeKeys,
            ) { rowQuery, progress, dismissed ->
                UpNextQuery(
                    candidates = UpNext.candidates(progress, rowQuery.filter, maxItems = rowQuery.maxItems, isAnime = catalogRepository::isAnime),
                    watchedKeys = progress.filterValues { it.isWatched }.keys,
                    dismissedKeys = dismissed,
                    maxItems = rowQuery.maxItems,
                )
            }
                .distinctUntilChangedBy { query ->
                    // Position saves change updatedAt without changing the target inputs.
                    query.copy(candidates = query.candidates.map { it.copy(updatedAt = 0L) })
                }
                .mapLatest { query -> listOfNotNull(buildUpNextRow(query)) }
                .collect { upNextRows.value = it }
        }
        viewModelScope.launch {
            combine(homeRowQuery, profiles.active) { rowQuery, profile ->
                RecommendationQuery(
                    rowQuery.filter,
                    profile.id,
                    profile.traktUser,
                    profile.kids,
                    rowQuery.maxItems,
                )
            }
                .distinctUntilChanged()
                .onEach { recommendationRows.value = emptyList() }
                .mapLatest { query -> RecommendationResult(query, listOfNotNull(buildRecommendationsRow(query))) }
                .collect { result ->
                    val profile = profiles.active.value
                    val query = result.query
                    if (
                        filterFlow.value == query.filter &&
                        profile.id == query.profileId &&
                        profile.traktUser == query.traktUser &&
                        profile.kids == query.kids
                    ) {
                        recommendationRows.value = result.rows
                    } else {
                        Log.d(TAG, "Ignoring stale recommendations for ${query.filter}")
                    }
                }
        }
    }

    fun removeFromContinue(item: CatalogItem) {
        ContinueWatching.keysToRemove(watchProgress.progress.value, item.type, item.id)
            .forEach(watchProgress::clear)
    }

    fun removeFromMyList(item: CatalogItem) = myList.remove(item.type, item.id)

    fun markUpNextWatched(item: CatalogItem, episode: Episode) {
        val key = com.shieldstream.tv.model.WatchProgress.episodeKey(item.id, episode.season, episode.number)
        trakt.setWatched(listOf(key), watched = true)
        val duration = watchProgress.get(key)?.durationMs?.takeIf { it > 1 }
            ?: episode.runtimeMinutes?.toLong()?.times(60_000L)
            ?: 1L
        watchProgress.save(key, duration, duration)
    }

    fun removeUpNext(item: CatalogItem, episode: Episode) {
        tracking.dismissEpisodes(
            listOf(com.shieldstream.tv.model.WatchProgress.episodeKey(item.id, episode.season, episode.number)),
        )
    }

    private suspend fun buildContinueRow(entries: List<ContinueEntry>): CatalogRow? {
        if (entries.isEmpty()) return null
        val loaded = entries.mapConcurrent(concurrency = 4) { entry ->
            val item = withTimeoutOrNull(5_000) {
                runCatching { catalogRepository.getItem(entry.type, entry.id) }.getOrNull()
            }
            item?.let { entry to it }
        }.filterNotNull()
        if (loaded.isEmpty()) return null
        return CatalogRow(
            id = CONTINUE_ROW_ID,
            title = texts.get(StringsR.string.home_continue_watching),
            items = loaded.map { it.second },
            style = RowStyle.CONTINUE,
            progress = loaded.associate { (entry, item) ->
                item.key to ContinueInfo(
                    fraction = entry.progress.fraction,
                    label = labelFor(entry),
                    season = entry.season,
                    episode = entry.episode,
                )
            },
        )
    }

    private suspend fun buildUpNextRow(query: UpNextQuery): CatalogRow? {
        if (query.candidates.isEmpty()) return null
        val today = NewEpisodes.dateWindow(System.currentTimeMillis()).second
        val loaded = query.candidates.mapConcurrent(concurrency = 4) { candidate ->
            withTimeoutOrNull(8_000) {
                runCatching {
                    val airing = catalogRepository.getShowAiring(candidate.showId) ?: return@runCatching null
                    val seasons = UpNext.seasonsToLoad(candidate, airing.seasons)
                    if (seasons.isEmpty()) return@runCatching null
                    val currentSeason = seasons.first()
                    val currentSeasonEpisodes = catalogRepository.getEpisodes(airing.item, currentSeason.number)
                    if (!UpNext.hasCompleteEpisodeList(currentSeason, currentSeasonEpisodes)) {
                        return@runCatching null
                    }
                    val currentEpisodes = mapOf(currentSeason.number to currentSeasonEpisodes)
                    var episode = UpNext.nextEpisode(
                        candidate,
                        currentEpisodes,
                        query.watchedKeys,
                        query.dismissedKeys,
                        today,
                    )
                    if (
                        episode == null &&
                        !UpNext.hasNextUnwatched(candidate, currentEpisodes, query.watchedKeys) &&
                        seasons.size > 1
                    ) {
                        val nextSeason = seasons[1]
                        val nextSeasonEpisodes = catalogRepository.getEpisodes(airing.item, nextSeason.number)
                        if (!UpNext.hasCompleteEpisodeList(nextSeason, nextSeasonEpisodes)) {
                            return@runCatching null
                        }
                        episode = UpNext.nextEpisode(
                            candidate,
                            mapOf(nextSeason.number to nextSeasonEpisodes),
                            query.watchedKeys,
                            query.dismissedKeys,
                            today,
                        )
                    }
                    if (episode == null) return@runCatching null
                    val lastWatchedDate = NewEpisodes.dateWindow(candidate.updatedAt).second
                    val info = UpNextInfo(
                        episode = episode,
                        lastWatched = candidate.updatedAt,
                        isNewEpisode = episode.airDate?.let { it > lastWatchedDate } == true,
                        isNewSeason = episode.season != candidate.season,
                    )
                    Triple(airing.item, info, candidate.updatedAt)
                }.getOrNull()
            }
        }.filterNotNull()
        if (loaded.isEmpty()) return null
        val entries = loaded.sortedByDescending { it.third }.take(query.maxItems)
        return CatalogRow(
            id = UP_NEXT_ROW_ID,
            title = texts.get(StringsR.string.home_up_next),
            items = entries.map { it.first },
            style = RowStyle.UP_NEXT,
            upNext = entries.associate { (item, info, _) -> item.key to info },
        )
    }

    private suspend fun buildNewEpisodesRow(query: NewEpisodeQuery): CatalogRow? {
        if (query.showIds.isEmpty()) return null
        val loaded = query.showIds.mapConcurrent(concurrency = 4) { showId ->
            withTimeoutOrNull(8_000) {
                runCatching { episodeReleases.status(showId) }.getOrNull()
            }?.let { status ->
                val episode = status.availableEpisodes.firstOrNull() ?: return@let null
                if (status.item.posterUrl == null) null else Triple(status.item, episode, status.availableEpisodes.size)
            }
        }.filterNotNull()
        if (loaded.isEmpty()) return null
        val entries = loaded.sortedByDescending { it.second.airDate }.take(query.maxItems)
        return CatalogRow(
            id = NEW_EPISODES_ROW_ID,
            title = texts.get(StringsR.string.home_new_episodes),
            items = entries.map { it.first },
            style = RowStyle.NEW_EPISODES,
            newEpisodes = entries.associate { (item, episode, _) -> item.key to episode },
            newEpisodeCounts = entries.associate { (item, _, count) -> item.key to count },
        )
    }

    private suspend fun buildMyListRow(entries: List<com.shieldstream.tv.domain.MyListEntry>): CatalogRow? {
        if (entries.isEmpty()) return null
        val items = entries.mapConcurrent(concurrency = 4) { e ->
            withTimeoutOrNull(5_000) { runCatching { catalogRepository.getItem(e.type, e.id) }.getOrNull() }
        }.filterNotNull()
        if (items.isEmpty()) return null
        return CatalogRow(
            id = MY_LIST_ROW_ID,
            title = texts.get(StringsR.string.home_my_list_f0348a),
            items = items,
            style = RowStyle.MY_LIST,
        )
    }

    private suspend fun buildRecommendationsRow(query: RecommendationQuery): CatalogRow? {
        Log.d(
            TAG,
            "Recommendations requested: filter=${query.filter}, connected=${query.traktUser != null}, kids=${query.kids}",
        )
        if (query.kids || query.traktUser == null) {
            Log.d(TAG, "Recommendations hidden: profile is not eligible")
            return null
        }
        val startedAt = System.currentTimeMillis()
        val entries = recommendationEntries(query).take(query.maxItems)
        if (entries.isEmpty()) {
            Log.w(TAG, "Recommendations hidden: Trakt returned no usable ids for ${query.filter}")
            return null
        }
        val items = entries.mapConcurrent(concurrency = 4) { (type, id) ->
            withTimeoutOrNull(5_000) {
                runCatching { catalogRepository.getItem(type, id) }
                    .onFailure { error ->
                        Log.d(TAG, "Recommendation hydration failed for $type/$id: ${error.logSummary()}")
                    }
                    .getOrNull()
            }
        }.filterNotNull()
        if (items.isEmpty()) {
            Log.w(TAG, "Recommendations hidden: none of ${entries.size} TMDB items hydrated")
            return null
        }
        Log.d(
            TAG,
            "Recommendations ready: ${items.size}/${entries.size} items in ${System.currentTimeMillis() - startedAt} ms",
        )
        return CatalogRow(
            id = RECOMMENDATIONS_ROW_ID,
            title = texts.get(StringsR.string.home_recommended_for_you),
            items = items,
        )
    }

    private suspend fun recommendationEntries(query: RecommendationQuery): List<Pair<MediaType, String>> = coroutineScope {
        suspend fun load(type: MediaType): List<String> {
            val result = withTimeoutOrNull(5_000) {
                runCatching { trakt.recommendations(query.profileId, type, query.maxItems) }
            }
            return result?.fold(
                onSuccess = { ids ->
                    Log.d(TAG, "Trakt recommendations for $type: ${ids.size} ids")
                    ids
                },
                onFailure = { error ->
                    Log.w(TAG, "Trakt recommendations for $type failed: ${error.logSummary()}")
                    emptyList()
                },
            ) ?: run {
                Log.w(TAG, "Trakt recommendations for $type timed out")
                emptyList()
            }
        }

        when (query.filter) {
            HomeFilter.MOVIES -> load(MediaType.MOVIE).map { MediaType.MOVIE to it }
            HomeFilter.TV -> load(MediaType.TV).map { MediaType.TV to it }
            HomeFilter.ANIME -> emptyList()
            HomeFilter.ALL -> {
                val movies = async { load(MediaType.MOVIE).map { MediaType.MOVIE to it } }
                val shows = async { load(MediaType.TV).map { MediaType.TV to it } }
                val movieEntries = movies.await()
                val showEntries = shows.await()
                buildList {
                    for (index in 0 until maxOf(movieEntries.size, showEntries.size)) {
                        movieEntries.getOrNull(index)?.let { add(it) }
                        showEntries.getOrNull(index)?.let { add(it) }
                    }
                }
            }
        }
    }

    private fun labelFor(entry: ContinueEntry): String {
        val remaining = formatRemaining(entry.progress.remainingMs)
        return if (entry.type == MediaType.TV) {
            texts.get(StringsR.string.home_continue_episode_remaining, entry.season.toString(), entry.episode.toString(), remaining)
        } else {
            texts.get(StringsR.string.home_remaining, remaining)
        }
    }

    private fun formatRemaining(ms: Long): String {
        val totalMin = ((ms + 59_999) / 60_000).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    private fun Throwable.logSummary() = "${javaClass.simpleName}: ${message ?: "no message"}"

    fun loadMoreRow(rowId: String) {
        val filter = loadedFilter ?: return
        loadMoreSectionRow(filter, rowId)
    }

    fun loadMoreSectionRow(filter: HomeFilter, rowId: String) {
        val key = filter to rowId
        if (key in rowExhausted || !rowLoadingMore.add(key)) return
        val nextPage = rowNextPage[key] ?: 2
        if (nextPage > MAX_EXTRA_PAGES) {
            rowExhausted += key
            rowLoadingMore -= key
            return
        }
        viewModelScope.launch {
            try {
                val items = runCatching { catalogRepository.loadMoreRow(filter, rowId, nextPage) }.getOrDefault(emptyList())
                if (items.isEmpty()) {
                    rowExhausted += key
                } else {
                    rowNextPage[key] = nextPage + 1
                    extraRowItems.value = extraRowItems.value.toMutableMap().apply {
                        val existing = this[key].orEmpty()
                        put(key, (existing + items).distinctBy { it.key })
                    }
                }
            } finally {
                rowLoadingMore -= key
            }
        }
    }

    private companion object {
        const val TAG = "HomeRows"
        const val MAX_EXTRA_PAGES = 5
        const val CONTINUE_ROW_ID = "continue_watching"
        const val NEW_EPISODES_ROW_ID = "new_episodes"
        const val UP_NEXT_ROW_ID = "up_next"
        const val MY_LIST_ROW_ID = "my_list"
        const val RECOMMENDATIONS_ROW_ID = "recommended_for_you"
    }

    fun cancelBackgroundPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun prefetchOtherTabs() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(1_500)
            for (other in listOf(HomeFilter.TV, HomeFilter.MOVIES)) {
                coroutineScope {
                    launch { runCatching { catalogRepository.getFeatured(other) } }
                    runCatching { catalogRepository.getHome(other, rowItemLimit.value) }
                }
            }
        }
    }

    fun loadUnified(force: Boolean = false, silent: Boolean = false) {
        unifiedMode = true
        filterFlow.value = HomeFilter.ALL
        val limit = rowItemLimit.value
        if (!force && loadedLimit == limit && _unifiedCatalog.value != null) return
        if (force) {
            extraRowItems.value = emptyMap()
            rowNextPage.clear()
            synchronized(rowExhausted) { rowExhausted.clear() }
        }
        prefetchJob?.cancel()
        unifiedJob?.cancel()
        loadedLimit = limit
        unifiedJob = viewModelScope.launch {
            if (!(silent && _unifiedCatalog.value != null)) {
                _unifiedCatalog.value = null
            }
            _unifiedError.value = null
            val tvJob = async { runCatching { catalogRepository.getHome(HomeFilter.TV, limit) } }
            val moviesJob = async { runCatching { catalogRepository.getHome(HomeFilter.MOVIES, limit) } }
            val animeJob = async { runCatching { catalogRepository.getHome(HomeFilter.ANIME, limit) } }

            val collected = linkedMapOf<HomeFilter, List<CatalogRow>>()
            val tvResult = tvJob.await()
            val tvRows = tvResult.getOrNull().orEmpty()
            if (tvRows.isNotEmpty()) {
                collected[HomeFilter.TV] = tvRows
                _unifiedCatalog.value = collected.toMap()
            }

            val moviesResult = moviesJob.await()
            val moviesRows = moviesResult.getOrNull().orEmpty()
            if (moviesRows.isNotEmpty()) {
                collected[HomeFilter.MOVIES] = moviesRows
                _unifiedCatalog.value = collected.toMap()
            }

            val animeResult = animeJob.await()
            val animeRows = animeResult.getOrNull().orEmpty()
            if (animeRows.isNotEmpty()) {
                collected[HomeFilter.ANIME] = animeRows
                _unifiedCatalog.value = collected.toMap()
            }

            if (collected.isEmpty()) {
                val firstError = tvResult.exceptionOrNull()
                    ?: moviesResult.exceptionOrNull()
                    ?: animeResult.exceptionOrNull()
                if (!(silent && _unifiedCatalog.value != null)) {
                    _unifiedCatalog.value = emptyMap()
                    _unifiedError.value = firstError?.message
                        ?: texts.get(StringsR.string.home_couldn_t_load_the_catalog_check_your)
                }
            }
        }
    }

    fun load(filter: HomeFilter, force: Boolean = false, silent: Boolean = false) {
        filterFlow.value = filter
        val limit = rowItemLimit.value
        if (!force && loadedFilter == filter && loadedLimit == limit && _uiState.value is HomeUiState.Content) return
        if (force) {
            extraRowItems.value = extraRowItems.value.filterKeys { it.first != filter }
            rowNextPage.keys.removeAll { it.first == filter }
            synchronized(rowExhausted) { rowExhausted.removeAll { it.first == filter } }
        }
        prefetchJob?.cancel()
        loadedFilter = filter
        loadedLimit = limit
        viewModelScope.launch {
            // En silencio se mantiene lo que hay en pantalla hasta que llega lo nuevo.
            if (!(silent && _uiState.value is HomeUiState.Content)) _uiState.value = HomeUiState.Loading
            val featured = async { runCatching { catalogRepository.getFeatured(filter) }.getOrDefault(emptyList()) }
            runCatching { catalogRepository.getHome(filter, limit) }
                .onSuccess { rows ->
                    _uiState.value = if (rows.isEmpty()) {
                        HomeUiState.Error(texts.get(StringsR.string.home_couldn_t_load_the_catalog_check_your))
                    } else {
                        HomeUiState.Content(rows, featured.await())
                    }
                    if (filter == HomeFilter.ALL) prefetchOtherTabs()
                }
                .onFailure { e ->
                    // Una recarga en silencio que falla deja lo que ya se veía.
                    if (!(silent && _uiState.value is HomeUiState.Content)) {
                        _uiState.value = HomeUiState.Error(e.message ?: texts.get(StringsR.string.home_error_loading_the_catalog))
                    }
                }
        }
    }
}
