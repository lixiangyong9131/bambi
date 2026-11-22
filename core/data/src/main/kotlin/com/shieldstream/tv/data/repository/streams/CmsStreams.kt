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
        // Modified build: Moontv CMS sources. User-preferred order first.
        private const val DYTT = "http://caiji.dyttzyapi.com/api.php/provide/vod"       // 电影天堂资源
        private const val BFZY = "https://bfzyapi.com/api.php/provide/vod"               // 暴风资源
        private const val API = "http://ffzy5.tv/api.php/provide/vod"                     // 非凡影视
        private const val TAOPIAN = "https://taopianapi.com/cjapi/mc/vod/json.html"       // 淘片
        private val SOURCES = listOf(API, DYTT, BFZY, TAOPIAN)  // ffzy first: the only reachable source on this box
        // Search stays on the fast source (ffzy) so typing results are instant.
        private val SEARCH_SOURCES = listOf(API, TAOPIAN)
        // Short timeout so an unreachable source is skipped fast instead of hanging playback.
        private val cmsClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Debug self-test: logs whether CMS search can reach a source and parse results. */
    suspend fun selfTest() {
        val hits = runCatching { searchTitles("肖申克") }.getOrDefault(emptyList())
        android.util.Log.i("CmsStream", "SELFTEST hits=${hits.size} first=${hits.firstOrNull()?.title}")
        if (hits.isNotEmpty()) {
            val item = com.shieldstream.tv.model.CatalogItem(
                id = "selftest", imdbId = null, type = com.shieldstream.tv.model.MediaType.MOVIE,
                title = hits.first().title, overview = "", posterUrl = null, backdropUrl = null,
                year = null, rating = 0f, genres = emptyList(),
            )
            val sources = runCatching { find(item) }.getOrDefault(emptyList())
            android.util.Log.i("CmsStream", "SELFTEST sources=${sources.size} url=${sources.firstOrNull()?.directUrl?.take(80)} spanish=${sources.firstOrNull()?.advertisesSpanishAudio} hash=${sources.firstOrNull()?.infoHash}")
        }
    }

    /** Titles that exist in the CMS catalog (has playable resources). Used for search. */
    data class Hit(val title: String, val id: String, val type: String, val remarks: String)

    suspend fun searchTitles(query: String): List<Hit> {
        if (query.isBlank()) return emptyList()
        for (base in SEARCH_SOURCES) {
            val rows = parseList(get("$base?ac=vod&wd=" + URLEncoder.encode(query, "UTF-8"))) ?: continue
            if (rows.isEmpty()) continue
            val hits = rows.map { r ->
                Hit(
                    title = str(r, "vod_name"),
                    id = str(r, "vod_id"),
                    type = str(r, "type_name"),
                    remarks = str(r, "vod_remarks"),
                )
            }.filter { it.title.isNotBlank() }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    suspend fun find(item: CatalogItem): List<StreamSource> {
        val title = clean(item.title)
        android.util.Log.i("CmsStream", "find(title=${item.title}, clean=$title)")
        if (title.isBlank()) return emptyList()
        // Try the fast source first, then backup.
        for (base in SOURCES) {
            val sources = searchAndBuild(base, title)
            android.util.Log.i("CmsStream", "$base -> ${sources.size} sources")
            if (sources.isNotEmpty()) return sources
        }
        android.util.Log.w("CmsStream", "NO direct url found for $title")
        return emptyList()
    }

    private fun searchAndBuild(base: String, title: String): List<StreamSource> {
        val searchUrl = "$base?ac=vod&wd=" + URLEncoder.encode(title, "UTF-8")
        val body = get(searchUrl)
        android.util.Log.i("CmsStream", "search url=" + searchUrl + " body=" + body?.take(120))
        val rows = parseList(body) ?: return emptyList()
        android.util.Log.i("CmsStream", "search title=" + title + " -> " + rows.size + " rows")
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
        android.util.Log.i("CmsStream", "picked url: ${url.take(100)}")
        val name = str(v, "vod_name").ifBlank { title }
        return listOf(
            StreamSource(
                id = "cms:$vid",
                title = name,
                // Modified build: unique infoHash so distinctBy() doesn't collapse CMS sources,
                // and an "ES" language tag so the "Spanish only" source filter doesn't drop them.
                infoHash = "cms-$vid",
                fileIdx = null,
                magnet = "",
                quality = Quality.FHD_1080,
                hdrType = HdrType.SDR,
                sizeBytes = 0L,
                seeders = null,
                isCached = true,
                directUrl = url,
                fileName = name,
                languages = listOf("ES"),
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
        // Modified build: use the system DNS directly. The box's DNS recovered and works;
        // the DoH layer only added latency and used stale/wrong IPs.
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        cmsClient.newCall(req).execute().use { resp ->
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
