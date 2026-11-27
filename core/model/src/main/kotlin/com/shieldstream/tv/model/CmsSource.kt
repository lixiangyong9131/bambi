package com.shieldstream.tv.model

import java.net.URI
import java.security.MessageDigest

data class CmsSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

object CmsSources {
    val DEFAULT_SOURCES: List<CmsSource> = listOf(
        CmsSource("builtin_ffzy", "非凡影视", "https://cj.ffzyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_iqiyi", "iQIYI", "https://www.iqiyizyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_hongniu", "红牛资源", "https://www.hongniuzy2.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_jisu", "速播资源", "https://www.jisuzy.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_modu", "魔都资源", "https://caiji.moduapi.cc/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_ryi", "如意资源", "https://cj.rycjapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_dytt", "电影天堂", "https://caiji.dyttzyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_wujin", "无尽资源", "https://api.wujinapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_bfzy", "暴风资源", "https://bfzyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_ikun", "iKun资源", "https://ikunzyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
        CmsSource("builtin_360zy", "360资源", "https://360zy.com/api.php/provide/vod", enabled = true, builtIn = true),
    )

    private val builtInById = DEFAULT_SOURCES.associateBy { it.id }
    private val builtInByUrl = DEFAULT_SOURCES.associateBy { it.url.lowercase() }

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (!host.contains('.')) return null
        return trimmed
    }

    fun hostLabel(url: String): String = runCatching {
        URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
    }.getOrDefault(url)

    fun stableId(url: String): String {
        val normalized = normalizeUrl(url) ?: url.trim()
        builtInByUrl[normalized.lowercase()]?.let { return it.id }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(normalized.lowercase().toByteArray(Charsets.UTF_8))
            .take(12)
        return "custom_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun createCustom(name: String, rawUrl: String, enabled: Boolean = true): CmsSource? {
        val url = normalizeUrl(rawUrl) ?: return null
        val cleanName = name.trim().ifBlank { hostLabel(url) }
        val builtIn = builtInByUrl[url.lowercase()]
        return CmsSource(
            id = builtIn?.id ?: stableId(url),
            name = cleanName,
            url = url,
            enabled = enabled,
            builtIn = builtIn != null,
        )
    }

    /**
     * Restores built-in metadata (immutable builtIn flag & default fallback) while preserving the
     * user's saved ordering, enabled state, and custom sources.
     */
    fun mergeWithDefaults(saved: List<CmsSource>?): List<CmsSource> {
        if (saved == null) return DEFAULT_SOURCES
        val seenUrls = HashSet<String>()
        val out = ArrayList<CmsSource>(saved.size + DEFAULT_SOURCES.size)
        for (entry in saved) {
            val url = normalizeUrl(entry.url) ?: continue
            val known = builtInById[entry.id] ?: builtInByUrl[url.lowercase()]
            val dedupKey = (known?.url ?: url).lowercase()
            if (!seenUrls.add(dedupKey)) continue
            if (known != null) {
                out += known.copy(enabled = entry.enabled)
            } else {
                out += CmsSource(
                    id = entry.id.ifBlank { stableId(url) },
                    name = entry.name.trim().ifBlank { hostLabel(url) },
                    url = url,
                    enabled = entry.enabled,
                    builtIn = false,
                )
            }
        }
        for (def in DEFAULT_SOURCES) {
            if (seenUrls.add(def.url.lowercase())) {
                out += def
            }
        }
        return out
    }
}
