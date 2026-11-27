package com.shieldstream.tv.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.ApiKeysRepository
import com.shieldstream.tv.domain.KeyCheck
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CmsSource
import com.shieldstream.tv.model.CmsSources
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CmsSourceEditor(
    val id: String? = null,
    val initialName: String = "",
    val initialUrl: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class CmsSourceTestStatus(
    val checking: Boolean = false,
    val detail: String? = null,
    val error: Boolean = false,
)

@HiltViewModel
class CmsSourcesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeys: ApiKeysRepository,
    private val texts: Texts,
) : ViewModel() {

    val sources: StateFlow<List<CmsSource>> = settings.cmsSources
        .map { it.ifEmpty { CmsSources.DEFAULT_SOURCES } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CmsSources.DEFAULT_SOURCES)

    private val _editor = MutableStateFlow<CmsSourceEditor?>(null)
    val editor: StateFlow<CmsSourceEditor?> = _editor.asStateFlow()

    private val _testStatus = MutableStateFlow<Map<String, CmsSourceTestStatus>>(emptyMap())
    val testStatus: StateFlow<Map<String, CmsSourceTestStatus>> = _testStatus.asStateFlow()

    fun openEditor(id: String? = null) {
        if (id == null) {
            _editor.value = CmsSourceEditor()
            return
        }
        val existing = sources.value.firstOrNull { it.id == id && !it.builtIn } ?: return
        _editor.value = CmsSourceEditor(
            id = existing.id,
            initialName = existing.name,
            initialUrl = existing.url,
        )
    }

    fun closeEditor() {
        if (_editor.value?.busy != true) _editor.value = null
    }

    fun save(rawName: String, rawUrl: String) {
        val edit = _editor.value ?: return
        if (edit.busy) return
        val normalized = CmsSources.normalizeUrl(rawUrl)
        if (normalized == null) {
            _editor.value = edit.copy(error = texts.get(StringsR.string.settings_cms_sources_invalid_url))
            return
        }
        _editor.value = edit.copy(busy = true, error = null)
        viewModelScope.launch {
            val current = latestSources()
            if (current.any { it.url.equals(normalized, ignoreCase = true) && it.id != edit.id }) {
                _editor.value = edit.copy(busy = false, error = texts.get(StringsR.string.settings_cms_sources_exists))
                return@launch
            }
            when (val result = apiKeys.checkCmsSource(normalized)) {
                is KeyCheck.Valid -> {
                    val updated = current.toMutableList()
                    val index = edit.id?.let { id -> updated.indexOfFirst { it.id == id } } ?: -1
                    if (edit.id != null && index < 0) {
                        _editor.value = edit.copy(busy = false, error = texts.get(StringsR.string.settings_phone_config_invalid_request))
                        return@launch
                    }
                    val savedId = if (index < 0) {
                        val created = CmsSources.createCustom(rawName, normalized, enabled = true) ?: run {
                            _editor.value = edit.copy(busy = false, error = texts.get(StringsR.string.settings_cms_sources_invalid_url))
                            return@launch
                        }
                        updated.add(0, created)
                        created.id
                    } else {
                        val existing = updated[index]
                        if (existing.builtIn) {
                            _editor.value = null
                            return@launch
                        }
                        val nextId = CmsSources.stableId(normalized)
                        val cleanName = rawName.trim().ifBlank { CmsSources.hostLabel(normalized) }
                        updated[index] = existing.copy(id = nextId, name = cleanName, url = normalized)
                        nextId
                    }
                    settings.setCmsSources(updated)
                    _testStatus.value += savedId to CmsSourceTestStatus(
                        detail = result.detail ?: texts.get(StringsR.string.settings_connected),
                        error = false,
                    )
                    _editor.value = null
                }
                is KeyCheck.Invalid -> {
                    _editor.value = edit.copy(busy = false, error = result.message)
                }
            }
        }
    }

    fun toggleEnabled(id: String) {
        viewModelScope.launch {
            val current = latestSources()
            val updated = current.map { src -> if (src.id == id) src.copy(enabled = !src.enabled) else src }
            if (updated != current) settings.setCmsSources(updated)
        }
    }

    fun moveToTop(id: String) {
        viewModelScope.launch {
            val current = latestSources().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx <= 0) return@launch
            val item = current.removeAt(idx)
            current.add(0, item)
            settings.setCmsSources(current)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val current = latestSources()
            val target = current.firstOrNull { it.id == id } ?: return@launch
            if (target.builtIn) return@launch
            settings.setCmsSources(current.filterNot { it.id == id })
            _testStatus.value -= id
        }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            val customOnly = latestSources().filterNot { it.builtIn }
            settings.setCmsSources(CmsSources.DEFAULT_SOURCES + customOnly)
        }
    }

    fun test(id: String) {
        if (_testStatus.value[id]?.checking == true) return
        viewModelScope.launch {
            val source = latestSources().firstOrNull { it.id == id } ?: return@launch
            _testStatus.value += id to CmsSourceTestStatus(checking = true)
            when (val result = apiKeys.checkCmsSource(source.url)) {
                is KeyCheck.Valid -> _testStatus.value += id to CmsSourceTestStatus(
                    detail = result.detail ?: texts.get(StringsR.string.settings_connected),
                    error = false,
                )
                is KeyCheck.Invalid -> _testStatus.value += id to CmsSourceTestStatus(
                    detail = result.message,
                    error = true,
                )
            }
        }
    }

    private suspend fun latestSources(): List<CmsSource> =
        runCatching { settings.cmsSources.first() }.getOrNull()?.ifEmpty { CmsSources.DEFAULT_SOURCES }
            ?: CmsSources.DEFAULT_SOURCES
}
