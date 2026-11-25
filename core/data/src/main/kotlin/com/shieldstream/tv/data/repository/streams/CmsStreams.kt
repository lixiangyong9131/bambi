package com.shieldstream.tv.data.repository.streams

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
        // Modified build: extra CMS sites, verified reachable with direct m3u8 streams.
        private const val IQIYI = "https://www.iqiyizyapi.com/api.php/provide/vod"        // iQIYI资源
        private const val MODU = "https://www.mdzyapi.com/api.php/provide/vod"            // 魔都资源
        // Modified build: newly verified sources (both titles -> 29-30 valid m3u8 lines).
        private const val RYI = "http://cj.rycjapi.com/api.php/provide/vod"                    // 如意资源
        private const val JISU = "https://www.jisuzy.com/api.php/provide/vod"             // 速播资源(备域名)
        private const val HONGNIU = "https://www.hongniuzy2.com/api.php/provide/vod"      // 红牛资源
        // Put consistently low-latency direct sources first; slow/ISP-throttled mirrors stay at the tail.
        private val SOURCES = listOf(
            "非凡影视" to API,
            "iQIYI" to IQIYI,
            "红牛资源" to HONGNIU,
            "速播资源" to JISU,
            "如意资源" to RYI,
        )
        // Metadata & search use fast responsive endpoints first so Details/Search never stall on blocked edges.
        private val SEARCH_SOURCES = listOf(API, IQIYI, HONGNIU, JISU)
        private const val CACHE_TTL_MS = 10 * 60_000L
        private const val DNS_TTL_MS = 5 * 60_000L

        /**
         * System DNS first (0ms overhead for clean hosts), falling back to DoH only when system DNS
         * fails or resolves to a poisoned loopback/unspecified address.
         */
        private object SmartDns : Dns {
            private class Entry(val at: Long, val addrs: List<InetAddress>)
            private val cache = ConcurrentHashMap<String, Entry>()
            private val bootstrap = OkHttpClient.Builder()
                .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            private val dohPub = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://doh.pub/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.12.12.12"),
                    InetAddress.getByName("223.5.5.5"),
                )
                .includeIPv6(false)
                .build()

            override fun lookup(hostname: String): List<InetAddress> {
                val now = System.currentTimeMillis()
                cache[hostname]?.takeIf { now - it.at < DNS_TTL_MS }?.let { return it.addrs }
                val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
                if (sys.isNotEmpty()) {
                    cache[hostname] = Entry(now, sys)
                    return sys
                }
                val dohAddrs = runCatching { dohPub.lookup(hostname) }.getOrDefault(emptyList())
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
                if (dohAddrs.isNotEmpty()) {
                    cache[hostname] = Entry(now, dohAddrs)
                    return dohAddrs
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        }

        // Fast timeouts so unresponsive mirrors never stall source discovery on Details entry.
        private val cmsClient: OkHttpClient = OkHttpClient.Builder()
            .dns(SmartDns)
            .connectTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
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

    /** Cached CMS title metadata (description + episodes + raw detail per site) to avoid duplicate lookups across Details/Play. */
    private data class TitleBundle(val at: Long, val description: String, val episodes: List<EpisodeInfo>)
    private data class CachedSiteDetail(val at: Long, val detail: JsonObject)
    private data class InitialEntry(val title: String, val pinyin: String, val initials: String)
    private data class InitialPool(val at: Long, val entries: List<InitialEntry>)

    private val titleBundleCache = ConcurrentHashMap<String, TitleBundle>()
    private val titleBundleLocks = ConcurrentHashMap<String, Mutex>()
    private val siteDetailCache = ConcurrentHashMap<String, CachedSiteDetail>()
    private val siteDetailLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile
    private var initialPool: InitialPool? = null
    private val initialPoolLock = Mutex()

    /** Match pinyin initials / full pinyin (xxrj -> title) against recently updated CMS titles. */
    suspend fun searchByInitials(query: String): List<String> {
        val q = query.lowercase().replace(" ", "")
        if (q.length < 2 || !q.all { it in 'a'..'z' }) return emptyList()
        val entries = getInitialPool()
        val out = mutableListOf<String>()
        for (entry in entries) {
            if (entry.initials == q || entry.initials.startsWith(q) || entry.pinyin.startsWith(q) || entry.pinyin.contains(q)) {
                out += entry.title
                if (out.size >= 8) break
            }
        }
        return out
    }

    private suspend fun getInitialPool(): List<InitialEntry> = initialPoolLock.withLock {
        val now = System.currentTimeMillis()
        initialPool?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withLock it.entries }
        val base = SEARCH_SOURCES.firstOrNull() ?: return@withLock emptyList()
        val pool = withContext(Dispatchers.IO) {
            val pages = (1..5).map { pg ->
                async { runCatching { parseList(get("$base?ac=vod&pg=$pg")) }.getOrNull().orEmpty() }
            }.awaitAll()
            val names = LinkedHashSet<String>()
            pages.forEach { rows -> rows.forEach { r -> names += clean(str(r, "vod_name")) } }
            names.filter { it.isNotBlank() }.map { title ->
                val syllables = title.map { Pinyin.toPinyin(it).lowercase() }.filter { it.isNotBlank() }
                InitialEntry(
                    title = title,
                    pinyin = syllables.joinToString(""),
                    initials = syllables.map { it.first() }.joinToString(""),
                )
            }
        }
        if (pool.isNotEmpty()) initialPool = InitialPool(now, pool)
        pool
    }

    suspend fun searchTitles(query: String): List<Hit> {
        if (query.isBlank()) return emptyList()
        // Modified build: query all search sources in parallel and merge. The old
        // "first source with hits wins" loop meant 如意 (best Chinese substring matching,
        // 211 rows for "谷") was never reached because 非凡 answered first with fewer rows.
        val perSource = withContext(Dispatchers.IO) {
            SEARCH_SOURCES.map { base ->
                async {
                    val rows = parseList(get("$base?ac=vod&wd=" + URLEncoder.encode(query, "UTF-8")))
                        ?: return@async emptyList()
                    rows.map { r ->
                        Hit(
                            title = str(r, "vod_name"),
                            id = str(r, "vod_id"),
                            type = str(r, "type_name"),
                            remarks = str(r, "vod_remarks"),
                        )
                    }.filter { it.title.isNotBlank() }
                }
            }.awaitAll()
        }
        // Keep source order (= reliability order) but drop duplicate titles.
        val out = LinkedHashMap<String, Hit>()
        for (hits in perSource) {
            for (h in hits) out.putIfAbsent(h.title, h)
        }
        return out.values.toList()
    }

    /** Fetch the synopsis (vod_content) for a title from the CMS, plain text. */
    suspend fun getDescription(title: String): String = getTitleBundle(title).description

    /** A CMS episode: number, title and its direct m3u8 URL. */
    data class EpisodeInfo(val number: Int, val title: String, val url: String)

    /** Fetch the full episode list of a TV show from the CMS detail (第01集$url#第02集$url...). */
    suspend fun getEpisodes(title: String): List<EpisodeInfo> = getTitleBundle(title).episodes

    /** Fetch both description and episodes in a single parallel CMS pass and cache across Details/Playback. */
    private suspend fun getTitleBundle(title: String): TitleBundle {
        val t = clean(title)
        if (t.isBlank()) return TitleBundle(0L, "", emptyList())
        return titleBundleLocks.getOrPut(t) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            titleBundleCache[t]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) {
                var desc = ""
                var eps: List<EpisodeInfo> = emptyList()
                val fastDetails = SEARCH_SOURCES.take(2).map { base ->
                    async { fetchDetailForTitle(base, t) }
                }.awaitAll()
                for (v in fastDetails) {
                    if (v == null) continue
                    if (desc.isBlank()) desc = parseDescription(v)
                    if (eps.isEmpty()) eps = parseEpisodes(v)
                }
                if (desc.isBlank() || eps.isEmpty()) {
                    for (base in SEARCH_SOURCES.drop(2)) {
                        val v = fetchDetailForTitle(base, t) ?: continue
                        if (desc.isBlank()) desc = parseDescription(v)
                        if (eps.isEmpty()) eps = parseEpisodes(v)
                        if (desc.isNotBlank() && eps.isNotEmpty()) break
                    }
                }
                TitleBundle(System.currentTimeMillis(), desc, eps)
            }
            if (loaded.description.isNotBlank() || loaded.episodes.isNotEmpty()) {
                titleBundleCache[t] = loaded
            }
            loaded
        }
    }

    private suspend fun fetchDetailForTitle(base: String, cleanTitle: String): JsonObject? {
        val cacheKey = "$base|$cleanTitle"
        siteDetailCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.let { return it.detail }
        return siteDetailLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            siteDetailCache[cacheKey]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withLock it.detail }

            val rows = parseList(get("$base?ac=vod&wd=" + URLEncoder.encode(cleanTitle, "UTF-8"))) ?: return@withLock null
            if (rows.isEmpty()) return@withLock null
            val cand = rows.firstOrNull { row ->
                val n = clean(str(row, "vod_name"))
                n == cleanTitle || n.contains(cleanTitle.take(6), true) || cleanTitle.contains(n.take(6), true)
            } ?: rows.first()
            val vid = str(cand, "vod_id")
            val detailRows = parseList(get("$base?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8")))
            val v = detailRows?.firstOrNull() ?: cand
            siteDetailCache[cacheKey] = CachedSiteDetail(now, v)
            v
        }
    }

    private fun parseDescription(v: JsonObject): String {
        val raw = str(v, "vod_content")
        if (raw.isBlank()) return ""
        return raw.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun parseEpisodes(v: JsonObject): List<EpisodeInfo> {
        val play = str(v, "vod_play_url")
        if (play.isBlank()) return emptyList()
        for (group in play.split("\$\$\$")) {
            val groupEps = mutableListOf<EpisodeInfo>()
            for (ep in group.split("#")) {
                val idx = ep.lastIndexOf('$')
                if (idx <= 0) continue
                val label = ep.substring(0, idx).trim()
                val url = ep.substring(idx + 1).trim()
                if (!url.startsWith("http")) continue
                if (!url.contains(".m3u8") && !url.contains(".mp4")) continue
                val num = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: (groupEps.size + 1)
                if (groupEps.none { it.number == num }) groupEps += EpisodeInfo(num, label, url)
            }
            if (groupEps.isNotEmpty()) return groupEps.sortedBy { it.number }
        }
        return emptyList()
    }

    /** Stream CMS sources as each site responds so playback can start without waiting for slow mirrors. */
    fun findFlow(item: CatalogItem, episode: com.shieldstream.tv.model.Episode? = null): Flow<List<StreamSource>> = channelFlow {
        val title = clean(item.title)
        android.util.Log.i("CmsStream", "find(title=${item.title}, clean=$title)")
        if (title.isBlank()) return@channelFlow
        val lock = Mutex()
        val bySite = arrayOfNulls<List<StreamSource>>(SOURCES.size)
        withContext(Dispatchers.IO) {
            SOURCES.mapIndexed { idx, (label, base) ->
                launch {
                    val sources = kotlinx.coroutines.withTimeoutOrNull(4_500L) {
                        runCatching { searchAndBuild(base, title, episode, label) }.getOrDefault(emptyList())
                    }.orEmpty()
                    android.util.Log.i("CmsStream", "$label -> ${sources.size} sources")
                    if (sources.isNotEmpty()) {
                        val snapshot = lock.withLock {
                            bySite[idx] = sources
                            val all = mutableListOf<StreamSource>()
                            val seenUrls = HashSet<String>()
                            for (siteList in bySite) {
                                if (siteList == null) continue
                                for (src in siteList) {
                                    val key = src.directUrl.orEmpty()
                                    if (key.isNotBlank() && seenUrls.add(key)) all += src
                                }
                            }
                            all
                        }
                        if (snapshot.isNotEmpty()) send(snapshot)
                    }
                }
            }.joinAll()
        }
    }

    suspend fun find(item: CatalogItem, episode: com.shieldstream.tv.model.Episode? = null): List<StreamSource> {
        var latest: List<StreamSource> = emptyList()
        findFlow(item, episode).collect { latest = it }
        return latest
    }

    private suspend fun searchAndBuild(base: String, title: String, episode: com.shieldstream.tv.model.Episode?, siteLabel: String = "CMS"): List<StreamSource> {
        val v = fetchDetailForTitle(base, title) ?: return emptyList()
        val vid = str(v, "vod_id")
        val url = pickPlayUrl(v, episode?.number) ?: return emptyList()
        android.util.Log.i("CmsStream", "picked url (ep=${episode?.number}): ${url.take(100)}")
        val name = str(v, "vod_name").ifBlank { title }
        return listOf(
            StreamSource(
                id = "cms:${siteLabel}:$vid",
                title = if (siteLabel.isNotBlank()) "$siteLabel · $name" else name,
                // Modified build: unique infoHash so distinctBy() doesn't collapse CMS sources,
                // and an "ES" language tag so the "Spanish only" source filter doesn't drop them.
                infoHash = "cms-$siteLabel-$vid",
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
                provider = "CMS·$siteLabel",
            )
        )
    }

    /** CMS V10: vod_play_from and vod_play_url are '$$$' separated groups. Pick a direct http m3u8/mp4. */
    private fun pickPlayUrl(v: JsonObject, episodeNumber: Int? = null): String? {
        val from = str(v, "vod_play_from")
        val play = str(v, "vod_play_url")
        if (play.isBlank()) return null
        val groups = play.split("\$\$\$")
        val names = from.split("\$\$\$")
        for (i in groups.indices) {
            val label = names.getOrNull(i).orEmpty()
            if (label.contains("share", true) || label.equals("feifan", true)) continue
            val entries = groups[i].split("#").mapNotNull { ep ->
                val idx = ep.lastIndexOf('$')
                if (idx < 0) null else {
                    val name = ep.substring(0, idx).trim()
                    val u = ep.substring(idx + 1).trim()
                    if (u.startsWith("http") && (u.contains(".m3u8") || u.contains(".mp4"))) name to u else null
                }
            }
            // Modified build: pick the URL for the selected episode (第NN集), not always the first.
            if (episodeNumber != null) {
                entries.firstOrNull { (name, _) ->
                    Regex("\\d+").find(name)?.value?.toIntOrNull() == episodeNumber
                }?.second?.let { return it }
            }
            entries.firstOrNull()?.second?.let { return it }
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
        cmsClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w("CmsStream", "GET $url -> HTTP ${resp.code}")
                return null
            }
            resp.body?.string()
        }
    }.onFailure { e ->
        android.util.Log.w("CmsStream", "GET $url failed: ${e.javaClass.simpleName}: ${e.message}")
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

    private fun clean(t: String): String {
        var v = t
            .replace(Regex("[（(].*?[)）]"), "")
            .replace(Regex("[:：].*$"), "")
            .trim()
        // Modified build: Douban titles carry the original English name; CMS sources index only the
        // Chinese title. Keep the CJK part, drop the ASCII suffix.
        if (v.any { it.code > 0x2E80 }) {
            v = v.replace(Regex(" *[A-Za-z][A-Za-z0-9 '’.,!?-]*$"), "").trim()
            v = v.filter { ch -> ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.isDigit() }
        }
        return v.replace(" ", "").trim()
    }
}
