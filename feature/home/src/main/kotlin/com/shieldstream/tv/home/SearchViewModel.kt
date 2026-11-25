package com.shieldstream.tv.home

import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CatalogItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchUiState {
    data object Loading : SearchUiState
    data class Suggestions(val items: List<CatalogItem>) : SearchUiState
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
    private val requests: com.shieldstream.tv.domain.RequestRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

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

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Loading)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestions: List<CatalogItem> = emptyList()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            PinyinEngine.ensureLoaded(appContext)
        }
        viewModelScope.launch {
            suggestions = runCatching { catalogRepository.trending() }.getOrDefault(emptyList())
            if (_query.value.isBlank()) _uiState.value = SearchUiState.Suggestions(suggestions)
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
            _uiState.value = SearchUiState.Suggestions(suggestions)
            return
        }
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
}
