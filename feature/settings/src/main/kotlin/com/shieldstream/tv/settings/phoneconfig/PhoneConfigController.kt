package com.shieldstream.tv.settings.phoneconfig

import com.shieldstream.tv.domain.ApiKeysRepository
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.KeyCheck
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.domain.StremioAddons
import com.shieldstream.tv.domain.mapConcurrent
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.CmsSource
import com.shieldstream.tv.model.CmsSources
import com.shieldstream.tv.model.SubtitlePreference
import com.shieldstream.tv.settings.SetupAudioPreference
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.strings.keyNameResource
import com.shieldstream.tv.strings.sectionResource
import com.shieldstream.tv.strings.titleResource
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

enum class PhoneConfigMode { EDIT, SETUP }

@Serializable
data class PhoneOptionDto(val id: String, val title: String)

@Serializable
data class PhoneKeyDto(
    val service: String,
    val title: String,
    val keyLabel: String,
    val helpUrl: String,
    val configured: Boolean,
    val last4: String? = null,
    val required: Boolean,
)

@Serializable
data class PhoneProviderGroupDto(
    val title: String,
    val providers: List<String>,
    val enabled: Boolean,
)

@Serializable
data class PhoneAddonDto(
    val id: String,
    val title: String,
    val valid: Boolean,
    val detail: String? = null,
    val logoUrl: String? = null,
)

@Serializable
data class PhoneAddonUpdateDto(
    val id: String? = null,
    val value: String? = null,
    val remove: Boolean = false,
    val test: Boolean = false,
)

@Serializable
data class PhoneCmsSourceDto(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val detail: String? = null,
)

@Serializable
data class PhoneCmsSourceUpdateDto(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val enabled: Boolean? = null,
    val remove: Boolean = false,
    val test: Boolean = false,
    val resetDefaults: Boolean = false,
)

@Serializable
data class PhonePreferencesDto(
    val lightweightUi: Boolean,
    val experimentalSubtitleSync: Boolean,
    val autoNextEpisode: Boolean,
)

@Serializable
data class PhoneSetupDto(
    val audio: String,
    val subtitles: String,
    val complete: Boolean,
)

@Serializable
data class PhoneConfigDto(
    val language: String,
    val debrid: String,
    val debridOptions: List<PhoneOptionDto>,
    val keys: List<PhoneKeyDto>,
    val providerGroups: List<PhoneProviderGroupDto>,
    val preferences: PhonePreferencesDto,
    val addons: List<PhoneAddonDto>,
    val cmsSources: List<PhoneCmsSourceDto> = emptyList(),
    val setup: PhoneSetupDto? = null,
)

@Serializable
data class PhoneKeyUpdateDto(
    val service: String,
    val value: String? = null,
    val remove: Boolean = false,
)

@Serializable
data class PhoneConfigUpdateDto(
    val debrid: String,
    val providers: List<String>,
    val lightweightUi: Boolean,
    val experimentalSubtitleSync: Boolean,
    val autoNextEpisode: Boolean,
    val keys: List<PhoneKeyUpdateDto> = emptyList(),
    val addons: List<PhoneAddonUpdateDto> = emptyList(),
    val cmsSources: List<PhoneCmsSourceUpdateDto> = emptyList(),
    val language: String? = null,
    val audio: String? = null,
    val subtitles: String? = null,
)

sealed interface PhoneConfigUpdateResult {
    data class Success(val config: PhoneConfigDto) : PhoneConfigUpdateResult
    data class ValidationFailed(val errors: Map<String, String>) : PhoneConfigUpdateResult
}

class PhoneConfigController @Inject constructor(
    private val apiKeys: ApiKeysRepository,
    private val settings: SettingsRepository,
    private val texts: Texts,
) {
    private var selectedSetupLanguage: AppLanguage? = null
    private val addonTestResults = mutableMapOf<String, String>()
    private val cmsTestResults = mutableMapOf<String, String>()

    private suspend fun loadCmsSources(): List<CmsSource> =
        runCatching { settings.cmsSources.first() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: CmsSources.DEFAULT_SOURCES

    suspend fun snapshot(mode: PhoneConfigMode = PhoneConfigMode.EDIT): PhoneConfigDto {
        val keys = apiKeys.keys.value
        val debrid = settings.debridService.first()
        val enabledProviders = settings.sourceProviders.first()
        val setup = if (mode == PhoneConfigMode.SETUP) {
            PhoneSetupDto(
                audio = when {
                    settings.spanishOnlySources.first() -> SetupAudioPreference.SPANISH_ONLY
                    settings.spanishFirst.first() -> SetupAudioPreference.SPANISH
                    else -> SetupAudioPreference.ORIGINAL
                }.name,
                subtitles = settings.subtitlePreference.first().name,
                complete = setupComplete(keys, debrid),
            )
        } else {
            null
        }
        val addons = StremioAddons.parse(keys[ApiService.AIOSTREAMS].orEmpty())
        val metadata = addons.mapConcurrent { addon ->
            runCatching { apiKeys.addonMetadata(addon.manifestUrl) }.getOrNull()
        }
        val cms = loadCmsSources()
        return PhoneConfigDto(
            language = (if (mode == PhoneConfigMode.SETUP) selectedSetupLanguage ?: settings.language else settings.language).name.lowercase(),
            debrid = debrid.name,
            debridOptions = DebridService.entries.map { PhoneOptionDto(it.name, it.title) },
            keys = ApiService.entries
                .filter { mode == PhoneConfigMode.EDIT || it != ApiService.AIOSTREAMS }
                .map { service ->
                    val key = keys[service]
                    PhoneKeyDto(
                        service = service.name,
                        title = texts.get(service.titleResource),
                        keyLabel = texts.get(service.keyNameResource),
                        helpUrl = service.url,
                        configured = !key.isNullOrBlank(),
                        last4 = key?.takeLast(4),
                        required = service.required || (mode == PhoneConfigMode.SETUP && service == debrid.apiService),
                    )
                },
            providerGroups = SourceProvider.entries
                .groupBy { texts.get(it.sectionResource) }
                .map { (title, group) ->
                    PhoneProviderGroupDto(
                        title = title,
                        providers = group.map { it.name },
                        enabled = group.all { it in enabledProviders },
                    )
                },
            preferences = PhonePreferencesDto(
                lightweightUi = settings.lightweightUi.first(),
                experimentalSubtitleSync = settings.experimentalSubtitleSync.first(),
                autoNextEpisode = settings.autoNextEpisode.first(),
            ),
            addons = addons.mapIndexed { index, addon ->
                PhoneAddonDto(
                    id = addon.id,
                    title = metadata[index]?.name?.takeUnless { it.isBlank() } ?: addon.host,
                    valid = addon.isValid,
                    detail = addonTestResults[addon.id],
                    logoUrl = metadata[index]?.logoUrl?.takeUnless { it.isBlank() },
                )
            },
            cmsSources = cms.map { src ->
                PhoneCmsSourceDto(
                    id = src.id,
                    name = src.name,
                    url = src.url,
                    enabled = src.enabled,
                    builtIn = src.builtIn,
                    detail = cmsTestResults[src.id],
                )
            },
            setup = setup,
        )
    }

    suspend fun update(
        request: PhoneConfigUpdateDto,
        mode: PhoneConfigMode = PhoneConfigMode.EDIT,
    ): PhoneConfigUpdateResult {
        val debrid = request.debrid.toEnum<DebridService>()
        val providers = request.providers.map { it.toEnum<SourceProvider>() }.toSet()
        require(providers.isNotEmpty()) { texts.get(StringsR.string.settings_phone_config_source_required) }
        require(request.keys.map { it.service }.distinct().size == request.keys.size) {
            texts.get(StringsR.string.settings_phone_config_invalid_request)
        }
        val setupChanges = if (mode == PhoneConfigMode.SETUP) {
            Triple(
                request.language?.toEnum<AppLanguage>(),
                request.audio?.toEnum<SetupAudioPreference>(),
                request.subtitles?.toEnum<SubtitlePreference>(),
            )
        } else {
            require(request.language == null && request.audio == null && request.subtitles == null) {
                texts.get(StringsR.string.settings_phone_config_invalid_request)
            }
            null
        }

        val changes = request.keys.map { change ->
            val service = change.service.toEnum<ApiService>()
            Triple(service, change.value?.trim(), change.remove)
        }
        val errors = linkedMapOf<String, String>()
        for ((service, value, remove) in changes) {
            val required = service.required || (mode == PhoneConfigMode.SETUP && service == debrid.apiService)
            when {
                remove && required -> errors[service.name] = texts.get(StringsR.string.settings_phone_config_required_key)
                remove && value != null -> errors[service.name] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                !remove && value != null && value.isEmpty() -> errors[service.name] = texts.get(StringsR.string.settings_phone_config_key_required)
            }
        }
        val updatedAddons = StremioAddons.parse(apiKeys.keys.value[ApiService.AIOSTREAMS].orEmpty()).toMutableList()
        request.addons.forEachIndexed { index, change ->
            val errorKey = "addon:$index"
            val value = change.value?.trim()
            val existingIndex = change.id?.let { id -> updatedAddons.indexOfFirst { it.id == id } } ?: -1
            when {
                change.id != null && existingIndex < 0 -> errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                change.remove && (change.id == null || value != null) ->
                    errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                change.test && (change.id == null || change.remove || value != null) ->
                    errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                !change.remove && !change.test && value.isNullOrEmpty() ->
                    errors[errorKey] = texts.get(StringsR.string.settings_phone_config_key_required)
            }
            if (errors.containsKey(errorKey)) return@forEachIndexed
            if (change.remove) {
                updatedAddons.removeAt(existingIndex)
                change.id?.let { addonTestResults.remove(it) }
                return@forEachIndexed
            }
            if (change.test) {
                val addon = updatedAddons[existingIndex]
                when (val result = apiKeys.check(ApiService.AIOSTREAMS, addon.manifestUrl)) {
                    is KeyCheck.Valid -> addonTestResults[addon.id] =
                        result.detail ?: texts.get(StringsR.string.settings_connected)
                    is KeyCheck.Invalid -> {
                        addonTestResults.remove(addon.id)
                        errors[errorKey] = result.message
                    }
                }
                return@forEachIndexed
            }

            val candidate = StremioAddons.parse(value.orEmpty()).singleOrNull()
            if (candidate == null || !candidate.isValid) {
                errors[errorKey] = texts.get(StringsR.string.settings_phone_config_one_manifest)
                return@forEachIndexed
            }
            if (updatedAddons.any { it.id == candidate.id && it.id != change.id }) {
                errors[errorKey] = texts.get(StringsR.string.settings_phone_config_addon_exists)
                return@forEachIndexed
            }
            when (val result = apiKeys.check(ApiService.AIOSTREAMS, candidate.manifestUrl)) {
                is KeyCheck.Valid -> {
                    change.id?.let { addonTestResults.remove(it) }
                    if (change.id == null) {
                        updatedAddons += candidate
                    } else {
                        updatedAddons[existingIndex] = candidate
                    }
                }
                is KeyCheck.Invalid -> errors[errorKey] = result.message
            }
        }
        val (updatedCmsSources, cmsModified) = applyCmsChanges(request.cmsSources, errors)
        if (errors.isNotEmpty()) return PhoneConfigUpdateResult.ValidationFailed(errors)

        for ((service, value, remove) in changes) {
            if (remove || value == null) continue
            when (val result = apiKeys.check(service, value)) {
                is KeyCheck.Valid -> Unit
                is KeyCheck.Invalid -> errors[service.name] = result.message
            }
        }
        if (errors.isNotEmpty()) return PhoneConfigUpdateResult.ValidationFailed(errors)

        val addonsTestOnly = request.addons.isNotEmpty() && request.addons.all { it.test }
        val cmsTestOnly = request.cmsSources.isNotEmpty() && request.cmsSources.all { it.test } && !cmsModified
        if (request.keys.isEmpty() && (addonsTestOnly || cmsTestOnly) &&
            (request.addons.isEmpty() || addonsTestOnly) &&
            (request.cmsSources.isEmpty() || cmsTestOnly)
        ) {
            return PhoneConfigUpdateResult.Success(snapshot(mode))
        }
        for ((service, value, remove) in changes) {
            when {
                remove -> apiKeys.set(service, null)
                value != null -> apiKeys.set(service, value)
            }
        }
        if (request.addons.any { !it.test }) {
            apiKeys.set(ApiService.AIOSTREAMS, StremioAddons.serialize(updatedAddons))
        }
        if (cmsModified) {
            settings.setCmsSources(updatedCmsSources)
        }
        settings.setDebridService(debrid)
        settings.setSourceProviders(providers)
        settings.setLightweightUi(request.lightweightUi)
        settings.setExperimentalSubtitleSync(request.experimentalSubtitleSync)
        settings.setAutoNextEpisode(request.autoNextEpisode)
        if (setupChanges != null) {
            val (language, audio, subtitles) = setupChanges
            if (language != null) {
                settings.setLanguage(language)
                selectedSetupLanguage = language
            }
            if (audio != null) {
                settings.setSpanishOnlySources(audio == SetupAudioPreference.SPANISH_ONLY)
                settings.setSpanishFirst(audio != SetupAudioPreference.ORIGINAL)
            }
            if (subtitles != null) settings.setSubtitlePreference(subtitles)
        }
        return PhoneConfigUpdateResult.Success(snapshot(mode))
    }

    private suspend fun applyCmsChanges(
        changes: List<PhoneCmsSourceUpdateDto>,
        errors: MutableMap<String, String>,
    ): Pair<List<CmsSource>, Boolean> {
        if (changes.isEmpty()) return emptyList<CmsSource>() to false
        var updated = loadCmsSources().toMutableList()
        var modified = false
        changes.forEachIndexed { index, change ->
            val errorKey = "cms:$index"
            if (change.resetDefaults) {
                val customOnly = updated.filterNot { it.builtIn }
                updated = (CmsSources.DEFAULT_SOURCES + customOnly).toMutableList()
                modified = true
                return@forEachIndexed
            }
            val existingIndex = change.id?.let { id -> updated.indexOfFirst { it.id == id } } ?: -1
            if (change.id != null && existingIndex < 0) {
                errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                return@forEachIndexed
            }
            if (change.test) {
                val targetUrl = change.url?.trim()?.takeIf { it.isNotEmpty() }
                    ?: updated.getOrNull(existingIndex)?.url
                if (targetUrl == null || change.remove) {
                    errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                    return@forEachIndexed
                }
                when (val result = apiKeys.checkCmsSource(targetUrl)) {
                    is KeyCheck.Valid -> {
                        val key = change.id ?: CmsSources.stableId(targetUrl)
                        cmsTestResults[key] = result.detail ?: texts.get(StringsR.string.settings_connected)
                    }
                    is KeyCheck.Invalid -> {
                        change.id?.let { cmsTestResults.remove(it) }
                        errors[errorKey] = result.message
                    }
                }
                return@forEachIndexed
            }
            if (change.remove) {
                if (existingIndex < 0) {
                    errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                    return@forEachIndexed
                }
                val existing = updated[existingIndex]
                if (existing.builtIn) {
                    errors[errorKey] = texts.get(StringsR.string.settings_cms_sources_remove_builtin_error)
                    return@forEachIndexed
                }
                updated.removeAt(existingIndex)
                cmsTestResults.remove(existing.id)
                modified = true
                return@forEachIndexed
            }
            if (existingIndex >= 0) {
                val current = updated[existingIndex]
                val hasNameOrUrl = change.name != null || change.url != null
                if (!hasNameOrUrl) {
                    val nextEnabled = change.enabled ?: current.enabled
                    if (nextEnabled != current.enabled) {
                        updated[existingIndex] = current.copy(enabled = nextEnabled)
                        modified = true
                    }
                    return@forEachIndexed
                }
                if (current.builtIn) {
                    val urlNorm = change.url?.let { CmsSources.normalizeUrl(it) }
                    val nameTrim = change.name?.trim()
                    if ((urlNorm != null && !urlNorm.equals(current.url, ignoreCase = true)) ||
                        (nameTrim != null && nameTrim != current.name)
                    ) {
                        errors[errorKey] = texts.get(StringsR.string.settings_phone_config_invalid_request)
                        return@forEachIndexed
                    }
                    val nextEnabled = change.enabled ?: current.enabled
                    if (nextEnabled != current.enabled) {
                        updated[existingIndex] = current.copy(enabled = nextEnabled)
                        modified = true
                    }
                    return@forEachIndexed
                }
                val rawUrl = (change.url ?: current.url).trim()
                val normalized = CmsSources.normalizeUrl(rawUrl)
                if (normalized == null) {
                    errors[errorKey] = texts.get(StringsR.string.settings_cms_sources_invalid_url)
                    return@forEachIndexed
                }
                if (updated.any { it.id != current.id && it.url.equals(normalized, ignoreCase = true) }) {
                    errors[errorKey] = texts.get(StringsR.string.settings_cms_sources_exists)
                    return@forEachIndexed
                }
                val urlChanged = !normalized.equals(current.url, ignoreCase = true)
                if (urlChanged) {
                    when (val result = apiKeys.checkCmsSource(normalized)) {
                        is KeyCheck.Valid -> {
                            cmsTestResults.remove(current.id)
                            val newId = CmsSources.stableId(normalized)
                            result.detail?.let { cmsTestResults[newId] = it }
                            val cleanName = (change.name ?: current.name).trim().ifBlank { CmsSources.hostLabel(normalized) }
                            updated[existingIndex] = current.copy(
                                id = newId,
                                name = cleanName,
                                url = normalized,
                                enabled = change.enabled ?: current.enabled,
                            )
                            modified = true
                        }
                        is KeyCheck.Invalid -> errors[errorKey] = result.message
                    }
                } else {
                    val cleanName = (change.name ?: current.name).trim().ifBlank { CmsSources.hostLabel(normalized) }
                    val nextEnabled = change.enabled ?: current.enabled
                    if (cleanName != current.name || nextEnabled != current.enabled) {
                        updated[existingIndex] = current.copy(name = cleanName, enabled = nextEnabled)
                        modified = true
                    }
                }
                return@forEachIndexed
            }

            val normalized = CmsSources.normalizeUrl(change.url.orEmpty())
            if (normalized == null) {
                errors[errorKey] = texts.get(StringsR.string.settings_cms_sources_invalid_url)
                return@forEachIndexed
            }
            if (updated.any { it.url.equals(normalized, ignoreCase = true) }) {
                errors[errorKey] = texts.get(StringsR.string.settings_cms_sources_exists)
                return@forEachIndexed
            }
            when (val result = apiKeys.checkCmsSource(normalized)) {
                is KeyCheck.Valid -> {
                    val created = CmsSources.createCustom(
                        name = change.name.orEmpty(),
                        rawUrl = normalized,
                        enabled = change.enabled ?: true,
                    )!!
                    result.detail?.let { cmsTestResults[created.id] = it }
                    updated.add(0, created)
                    modified = true
                }
                is KeyCheck.Invalid -> errors[errorKey] = result.message
            }
        }
        return updated to modified
    }

    private fun setupComplete(keys: Map<ApiService, String?>, debrid: DebridService): Boolean =
        !keys[ApiService.TMDB].isNullOrBlank() && !keys[debrid.apiService].isNullOrBlank()

    private inline fun <reified T : Enum<T>> String.toEnum(): T =
        enumValues<T>().firstOrNull { it.name == this }
            ?: throw IllegalArgumentException(texts.get(StringsR.string.settings_phone_config_invalid_request))
}
