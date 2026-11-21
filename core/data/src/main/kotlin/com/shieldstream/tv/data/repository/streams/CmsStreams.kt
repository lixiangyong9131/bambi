package com.shieldstream.tv.data.repository.streams

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct m3u8 source backed by a standard CMS V10 collection API (e.g. ffzy/taopian).
 * Given a title from the catalog (Douban), it searches the CMS, opens detail and returns
 * the first playable .m3u8 as a StreamSource with directUrl set — ResolveStreamUseCase
 * then plays it directly, bypassing any debrid service.
 */
@Singleton
class CmsStreams @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val API = "http://ffzy5.tv/api.php/provide/vod"
        private const val BACKUP_API = "https://taopianapi.com/cjapi/mc/vod/json.html"
    }

    suspend fun find(item: CatalogItem): List<StreamSource> {
        val title = clean(item.title)
        if (title.isBlank()) return emptyList()
        // Try the fast source first, then backup.
        for (base in listOf(API, BACKUP_API)) {
            val sources = searchAndBuild(base, title)
            if (sources.isNotEmpty()) return sources
        }
        return emptyList()
    }

    private fun searchAndBuild(base: String, title: String): List<StreamSource> {
        val searchUrl = "$base?ac=vod&wd=" + URLEncoder.encode(title, "UTF-8")
        val rows = parseList(get(searchUrl)) ?: return emptyList()
        if (rows.isEmpty()) return emptyList()
        // Prefer an exact/near title match, else first row.
        val cand = rows.firstOrNull { row ->
            val n = clean(str(row, "vod_name"))
            n.contains(title.take(6), true) || title.contains(n.take(6), true)
        } ?: rows.first()

        val vid = str(cand, "vod_id")
        val detailUrl = "$base?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8")
        val detailRows = parseList(get(detailUrl)) ?: listOf(cand)
        val v = detailRows.firstOrNull() ?: cand
        val url = pickPlayUrl(v) ?: return emptyList()
        val name = str(v, "vod_name").ifBlank { title }
        return listOf(
            StreamSource(
                id = "cms:$vid",
                title = name,
                infoHash = "",
                fileIdx = null,
                magnet = "",
                quality = Quality.FHD_1080,
                hdrType = HdrType.SDR,
                sizeBytes = 0L,
                seeders = null,
                isCached = true,
                directUrl = url,
                fileName = name,
                provider = "CMS",
            )
        )
    }

    /** CMS V10: vod_play_from and vod_play_url are '$$$' separated groups. Pick a direct http m3u8/mp4. */
    private fun pickPlayUrl(v: JsonObject): String? {
        val from = str(v, "vod_play_from")
        val play = str(v, "vod_play_url")
        if (play.isBlank()) return null
        val groups = play.split("\$\$\$")
        val names = from.split("\$\$\$")
        for (i in groups.indices) {
            val label = names.getOrNull(i).orEmpty()
            if (label.contains("share", true) || label.equals("feifan", true)) continue
            for (ep in groups[i].split("#")) {
                val idx = ep.lastIndexOf('$')
                val u = (if (idx >= 0 && idx < ep.length - 1) ep.substring(idx + 1) else ep).trim()
                if (u.startsWith("http") && (u.contains(".m3u8") || u.contains(".mp4"))) return u
            }
        }
        // Fallback: any http url
        for (ep in play.split("#", "\$\$\$")) {
            val idx = ep.lastIndexOf('$')
            val u = (if (idx >= 0 && idx < ep.length - 1) ep.substring(idx + 1) else ep).trim()
            if (u.startsWith("http")) return u
        }
        return null
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    private fun parseList(body: String?): List<JsonObject>? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["list"]?.jsonArray
                ?.map { it.jsonObject }
        }.getOrNull()
    }

    private fun str(v: JsonObject, key: String): String =
        v[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun clean(t: String): String = t
        .replace(Regex("[（(].*?[)）]"), "")
        .replace(Regex("[:：].*$"), "")
        .replace(Regex("\\s+"), "")
        .trim()
}
