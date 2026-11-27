package com.shieldstream.tv.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.domain.RequestRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchMediaType(
    val filter: HomeFilter,
    @param:StringRes val labelRes: Int,
) {
    TV(HomeFilter.TV, StringsR.string.common_tv_shows),
    MOVIES(HomeFilter.MOVIES, StringsR.string.common_movies),
    ANIME(HomeFilter.ANIME, StringsR.string.common_anime),
}

enum class SearchSort(
    val doubanSort: String,
    @param:StringRes val labelRes: Int,
) {
    POPULAR("U", StringsR.string.home_filter_sort_popular),
    TOP_RATED("S", StringsR.string.home_filter_sort_top_rated),
    LATEST("R", StringsR.string.home_filter_sort_latest),
}

data class SearchFilterState(
    val mediaType: SearchMediaType = SearchMediaType.TV,
    val sort: SearchSort = SearchSort.POPULAR,
    val selection: CategoryFilterSelection = CategoryFilterSelection(),
) {
    val canReset: Boolean
        get() = sort != SearchSort.POPULAR || selection.isActive
}

sealed interface SearchUiState {
    data class Browse(
        val filter: SearchFilterState,
        val items: List<CatalogItem>,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val canLoadMore: Boolean = false,
        val error: String? = null,
    ) : SearchUiState

    data object Loading : SearchUiState

    /** [locked]: solo en perfiles infantiles, lo que hay que pedir a un adulto; [pending]: claves ya pedidas. */
    data class Results(
        val query: String,
        val items: List<CatalogItem>,
        val locked: List<CatalogItem> = emptyList(),
        val pending: Set<String> = emptySet(),
    ) : SearchUiState

    data class Empty(val query: String) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val settings: SettingsRepository,
    private val texts: Texts,
    private val requests: RequestRepository,
    private val myList: MyListRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    val myListKeys: StateFlow<Set<String>> = myList.entries
        .map { entries -> entries.map { "${it.type.name}:${it.id}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleMyList(item: CatalogItem) {
        val adding = !myList.contains(item.type, item.id)
        if (adding) myList.add(item.type, item.id) else myList.remove(item.type, item.id)
        _notice.value = texts.get(
            if (adding) StringsR.string.home_added_to_my_list else StringsR.string.home_removed_from_my_list,
        )
    }

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    /** Perfil infantil: pide a un adulto un título bloqueado. */
    fun requestTitle(item: CatalogItem) {
        requests.request(item)
        val current = _uiState.value as? SearchUiState.Results
        if (current != null) _uiState.value = current.copy(pending = current.pending + item.key)
        _notice.value = texts.get(StringsR.string.home_request_sent)
    }

    val qwerty: StateFlow<Boolean> = settings.searchQwerty.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleKeyboardLayout() {
        viewModelScope.launch { settings.setSearchQwerty(!qwerty.value) }
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filterState = MutableStateFlow(SearchFilterState())
    val filterState: StateFlow<SearchFilterState> = _filterState.asStateFlow()

    // ---- 拼音输入法状态 ----
    /**
     * Currently composed pinyin (e.g. "guyu") shown after the committed [query].
     * Committing a candidate moves it into [query] and clears this buffer.
     */
    private val _composing = MutableStateFlow("")
    val composing: StateFlow<String> = _composing.asStateFlow()

    /** Chinese candidates for the current [composing] buffer; empty when idle. */
    private val _candidates = MutableStateFlow<List<String>>(emptyList())
    val candidates: StateFlow<List<String>> = _candidates.asStateFlow()
    private var candidateJob: Job? = null

    private fun refreshCandidates() {
        val buf = _composing.value
        candidateJob?.cancel()
        if (buf.isEmpty()) {
            _candidates.value = emptyList()
            return
        }
        candidateJob = viewModelScope.launch(Dispatchers.Default) {
            val result = PinyinEngine.candidates(appContext, buf)
            if (isActive && _composing.value == buf) {
                _candidates.value = result
            }
        }
    }

    /** A candidate was picked: append it to the query and clear the pinyin buffer. */
    fun commitCandidate(text: String) {
        if (text.isEmpty()) return
        _composing.value = ""
        refreshCandidates()
        setQuery(_query.value + text)
    }

    /** Type one letter into the pinyin buffer instead of straight into the query. */
    fun typeLetter(char: Char) {
        val c = char.lowercaseChar()
        if (c !in 'a'..'z') {
            // Digits/symbols go straight through, flushing whatever was composing.
            _composing.value = ""
            refreshCandidates()
            setQuery(_query.value + char)
            return
        }
        _composing.value += c
        refreshCandidates()
    }

    /** Backspace removes the pinyin buffer first, then the committed query. */
    fun backspaceComposing(): Boolean {
        val buf = _composing.value
        if (buf.isEmpty()) return false
        _composing.value = buf.dropLast(1)
        refreshCandidates()
        return true
    }

    fun clearComposing() {
        _composing.value = ""
        refreshCandidates()
    }

    /** Search now, keeping whatever letters are still composing as plain text. */
    fun flushComposingToQuery(): String {
        val buf = _composing.value
        if (buf.isNotEmpty()) {
            _composing.value = ""
            refreshCandidates()
            setQuery(_query.value + buf)
        }
        return _query.value
    }

    private val _uiState = MutableStateFlow<SearchUiState>(
        SearchUiState.Browse(
            filter = SearchFilterState(),
            items = emptyList(),
            isLoading = true,
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var filterJob: Job? = null
    private var loadMoreJob: Job? = null
    private var browsePage = 1
    private var browseExhausted = false
    private var lastBrowseFilter: SearchFilterState? = null
    private var lastBrowseItems: List<CatalogItem> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            PinyinEngine.ensureLoaded(appContext)
        }
        loadBrowse(_filterState.value, debounceMs = 0L)
    }

    fun selectMediaType(mediaType: SearchMediaType) {
        val current = _filterState.value
        val next = current.copy(
            mediaType = mediaType,
            selection = current.selection.sanitizeFor(mediaType.filter),
        )
        applyFilterChange(next)
    }

    fun selectSort(sort: SearchSort) {
        val current = _filterState.value
        if (current.sort == sort && _query.value.isBlank()) return
        applyFilterChange(current.copy(sort = sort))
    }

    fun toggleGenre(tag: String) {
        val current = _filterState.value
        applyFilterChange(current.copy(selection = current.selection.toggleGenre(tag)))
    }

    fun clearGenres() {
        val current = _filterState.value
        if (current.selection.genres.isEmpty() && _query.value.isBlank()) return
        applyFilterChange(current.copy(selection = current.selection.clearGenres()))
    }

    fun selectRegion(tag: String?) {
        val current = _filterState.value
        applyFilterChange(current.copy(selection = current.selection.toggleRegion(tag)))
    }

    fun selectEra(tag: String?) {
        val current = _filterState.value
        applyFilterChange(current.copy(selection = current.selection.toggleEra(tag)))
    }

    fun resetFilters() {
        val current = _filterState.value
        applyFilterChange(
            current.copy(
                sort = SearchSort.POPULAR,
                selection = CategoryFilterSelection(),
            ),
        )
    }

    fun retryBrowse() {
        loadBrowse(_filterState.value, debounceMs = 0L)
    }

    fun loadMoreFiltered() {
        val currentBrowse = _uiState.value as? SearchUiState.Browse ?: return
        if (currentBrowse.isLoading || currentBrowse.isLoadingMore || browseExhausted || !currentBrowse.canLoadMore) return
        val nextPage = browsePage + 1
        if (nextPage > MAX_FILTER_PAGES) {
            browseExhausted = true
            _uiState.value = currentBrowse.copy(canLoadMore = false)
            return
        }
        val activeFilter = currentBrowse.filter
        _uiState.value = currentBrowse.copy(isLoadingMore = true)
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            val extra = runCatching {
                catalogRepository.browseCategoryFilter(
                    filter = activeFilter.mediaType.filter,
                    tag = activeFilter.selection.toDoubanTags(),
                    sort = activeFilter.sort.doubanSort,
                    page = nextPage,
                )
            }.getOrDefault(emptyList())

            if (_query.value.isNotBlank() || _filterState.value != activeFilter) return@launch
            val latestBrowse = _uiState.value as? SearchUiState.Browse ?: return@launch
            if (extra.isEmpty()) {
                browseExhausted = true
                _uiState.value = latestBrowse.copy(isLoadingMore = false, canLoadMore = false)
            } else {
                browsePage = nextPage
                val merged = (latestBrowse.items + extra).distinctBy { it.key }
                lastBrowseFilter = activeFilter
                lastBrowseItems = merged
                val hasMore = nextPage < MAX_FILTER_PAGES && merged.size > latestBrowse.items.size
                if (!hasMore) browseExhausted = true
                _uiState.value = latestBrowse.copy(
                    items = merged,
                    isLoadingMore = false,
                    canLoadMore = hasMore,
                )
            }
        }
    }

    private fun applyFilterChange(next: SearchFilterState) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        val hadQuery = _query.value.isNotEmpty() || _composing.value.isNotEmpty()
        if (hadQuery) {
            _composing.value = ""
            refreshCandidates()
            _query.value = ""
        }
        _filterState.value = next
        loadBrowse(next, debounceMs = if (hadQuery) 0L else FILTER_DEBOUNCE_MS)
    }

    private fun loadBrowse(filter: SearchFilterState, debounceMs: Long) {
        filterJob?.cancel()
        loadMoreJob?.cancel()
        val preservedItems = (_uiState.value as? SearchUiState.Browse)?.items
            ?: lastBrowseItems.takeIf { lastBrowseFilter == filter }
            ?: emptyList()
        _uiState.value = SearchUiState.Browse(
            filter = filter,
            items = preservedItems,
            isLoading = true,
            isLoadingMore = false,
            canLoadMore = false,
            error = null,
        )
        filterJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val result = runCatching {
                catalogRepository.browseCategoryFilter(
                    filter = filter.mediaType.filter,
                    tag = filter.selection.toDoubanTags(),
                    sort = filter.sort.doubanSort,
                    page = 1,
                )
            }
            if (_query.value.isNotBlank() || _filterState.value != filter) return@launch
            result
                .onSuccess { items ->
                    browsePage = 1
                    browseExhausted = items.isEmpty()
                    lastBrowseFilter = filter
                    lastBrowseItems = items
                    _uiState.value = SearchUiState.Browse(
                        filter = filter,
                        items = items,
                        isLoading = false,
                        isLoadingMore = false,
                        canLoadMore = items.isNotEmpty(),
                        error = null,
                    )
                }
                .onFailure { e ->
                    _uiState.value = SearchUiState.Browse(
                        filter = filter,
                        items = emptyList(),
                        isLoading = false,
                        isLoadingMore = false,
                        canLoadMore = false,
                        error = e.message ?: texts.get(StringsR.string.home_search_error),
                    )
                }
        }
    }

    fun type(char: Char) = setQuery(_query.value + char)

    fun backspace() = setQuery(_query.value.dropLast(1))

    fun clear() = setQuery("")

    fun setQuery(value: String) {
        _query.value = value
        searchJob?.cancel()
        val q = value.trim()
        // Modified build: a single Chinese character is a valid query ("谷" -> 211 CMS hits),
        // whereas a single Latin letter is not worth a round-trip.
        val minLen = if (PinyinEngine.hasChinese(q)) 1 else 2
        if (q.length < minLen) {
            val activeFilter = _filterState.value
            if (lastBrowseFilter == activeFilter && lastBrowseItems.isNotEmpty()) {
                _uiState.value = SearchUiState.Browse(
                    filter = activeFilter,
                    items = lastBrowseItems,
                    isLoading = false,
                    isLoadingMore = false,
                    canLoadMore = !browseExhausted && browsePage < MAX_FILTER_PAGES,
                    error = null,
                )
            } else {
                loadBrowse(activeFilter, debounceMs = 0L)
            }
            return
        }
        filterJob?.cancel()
        loadMoreJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(450)
            _uiState.value = SearchUiState.Loading
            runCatching {
                coroutineScope {
                    val allowed = async { catalogRepository.search(q) }
                    val outside = async { runCatching { catalogRepository.searchOutsideKids(q) }.getOrDefault(emptyList()) }
                    allowed.await() to outside.await()
                }
            }
                .onSuccess { (allowed, outside) ->
                    // Lo que un adulto ya aprobó deja de estar bloqueado.
                    val (approved, locked) = outside.partition { requests.isApproved(it) }
                    val items = (allowed + approved).distinctBy { it.key }
                    _uiState.value = if (items.isEmpty() && locked.isEmpty()) {
                        SearchUiState.Empty(q)
                    } else {
                        SearchUiState.Results(q, items, locked, locked.filter(requests::isPending).map { it.key }.toSet())
                    }
                }
                .onFailure { e -> _uiState.value = SearchUiState.Error(e.message ?: texts.get(StringsR.string.home_search_error)) }
        }
    }

    private companion object {
        const val FILTER_DEBOUNCE_MS = 260L
        const val MAX_FILTER_PAGES = 5
    }
}
