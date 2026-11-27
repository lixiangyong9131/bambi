package com.shieldstream.tv.details

import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.Quality

internal data class ReadyStreamHint(
    val text: String,
    val isReady: Boolean,
)

/**
 * Netflix-style subtle readiness line rendered next to / below the Play button so the user knows
 * immediately what direct/cached quality & provider is already matched before pressing OK.
 */
internal fun buildReadyStreamHint(
    state: DetailsUiState.Content,
    spanish: Boolean = Lang.current == AppLanguage.ES,
): ReadyStreamHint? {
    val sourcesReady = state.sources as? SourcesState.Ready
    val eligible = if (sourcesReady != null) state.eligibleSources(sourcesReady.sources) else emptyList()
    val sourcesLoading = state.sources is SourcesState.Idle ||
        state.sources is SourcesState.Loading ||
        (sourcesReady != null && eligible.isEmpty() && sourcesReady.pending.isNotEmpty())
    val noSourcesTerminal = (sourcesReady != null && eligible.isEmpty() && sourcesReady.pending.isEmpty()) ||
        state.sources is SourcesState.Error

    if (noSourcesTerminal) return null

    if (sourcesLoading) {
        return ReadyStreamHint(
            text = if (spanish) "Buscando el mejor enlace disponible…" else "正在匹配最佳直连片源…",
            isReady = false,
        )
    }

    if (eligible.isEmpty()) return null

    val savedId = state.mainProgress?.sourceId
    val primary = eligible.firstOrNull { it.id == savedId } ?: eligible.first()
    val primaryProbe = state.sourceProbes[primary.id]

    // Prefer measured probe quality if available; fall back to source metadata quality.
    val qualityText = primaryProbe?.takeIf { it.ok }?.quality?.takeIf { it.isNotBlank() }
        ?: primary.quality.takeIf { it != Quality.UNKNOWN }?.label
    val spec = buildString {
        if (!qualityText.isNullOrBlank()) append(qualityText)
        if (primary.hdrType != HdrType.SDR) {
            if (isNotEmpty()) append(' ')
            append(primary.hdrType.label)
        }
        val delivery = when {
            primary.directUrl != null -> if (spanish) "Directo" else "直连"
            primary.isCached -> if (spanish) "Caché ⚡" else "秒播缓存"
            else -> ""
        }
        if (delivery.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append(delivery)
        }
    }

    val providerName = primary.provider.trim().ifEmpty { primary.debrid.orEmpty().trim() }
    val parts = mutableListOf<String>()
    parts += if (spanish) "Listo" else "已就绪"
    if (spec.isNotEmpty()) parts += spec
    if (providerName.isNotEmpty()) parts += providerName

    val pingMs = primaryProbe?.takeIf { it.ok }?.pingMs
    if (pingMs != null && pingMs > 0) {
        parts += "${pingMs}ms"
    }

    return ReadyStreamHint(
        text = parts.joinToString(" · "),
        isReady = true,
    )
}
