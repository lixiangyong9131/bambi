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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
        private const val IKUN = "https://ikunzyapi.com/api.php/provide/vod"              // iKun资源
        private const val ZY360 = "https://360zy.com/api.php/provide/vod"                 // 360资源
        // Put consistently low-latency direct sources first; optional/region-sensitive mirrors stay at the tail
        // with automatic per-device cooldown so blocked/poisoned devices pay zero ongoing cost.
        internal val SOURCES = listOf(
            "非凡影视" to API,
            "iQIYI" to IQIYI,
            "红牛资源" to HONGNIU,
            "速播资源" to JISU,
            "如意资源" to RYI,
            "电影天堂" to DYTT,
            "iKun资源" to IKUN,
            "360资源" to ZY360,
        )
        // Metadata & search use fast responsive endpoints first so Details/Search never stall on blocked edges.
        private val SEARCH_SOURCES = listOf(API, IQIYI, HONGNIU, JISU)
        private const val CACHE_TTL_MS = 10 * 60_000L
        private const val DNS_TTL_MS = 5 * 60_000L
        internal const val SITE_COOLDOWN_MS = 30 * 60_000L

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

        // Fast timeouts so unresponsive/DNS-poisoned mirrors fail quickly once and enter cooldown.
        private val cmsClient: OkHttpClient = OkHttpClient.Builder()
            .dns(SmartDns)
            .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
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
    private val siteCooldownUntil = ConcurrentHashMap<String, Long>()
    @Volatile
    private var initialPool: InitialPool? = null
    private val initialPoolLock = Mutex()

    private sealed interface SiteResponse {
        data class Ok(val body: String) : SiteResponse
        data object Unavailable : SiteResponse
    }

    internal fun isSiteCoolingDown(base: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = siteCooldownUntil[base] ?: return false
        if (nowMs >= until) {
            siteCooldownUntil.remove(base, until)
            return false
        }
        return true
    }

    internal fun markSiteUnavailable(base: String, nowMs: Long = System.currentTimeMillis()) {
        siteCooldownUntil[base] = nowMs + SITE_COOLDOWN_MS
    }

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
                async {
                    val body = (getForSite(base, "$base?ac=vod&pg=$pg") as? SiteResponse.Ok)?.body
                    runCatching { parseList(body) }.getOrNull().orEmpty()
                }
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
                    val body = (getForSite(base, "$base?ac=vod&wd=" + URLEncoder.encode(query, "UTF-8")) as? SiteResponse.Ok)?.body
                    val rows = parseList(body) ?: return@async emptyList()
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

    /** Fetch both description and episodes across CMS sources and cache across Details/Playback. */
    private suspend fun getTitleBundle(title: String): TitleBundle {
        val raw = title.trim()
        val key = clean(raw).ifBlank { raw }
        if (key.isBlank()) return TitleBundle(0L, "", emptyList())
        return titleBundleLocks.getOrPut(key) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            titleBundleCache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) {
                var desc = ""
                var eps: List<EpisodeInfo> = emptyList()
                suspend fun probeSources(bases: List<String>) {
                    val details = bases.map { base ->
                        async { fetchDetailForTitle(base, raw) }
                    }.awaitAll()
                    for (v in details) {
                        if (v == null) continue
                        if (desc.isBlank()) desc = parseDescription(v)
                        val candidateEps = parseEpisodes(v)
                        if (candidateEps.size > eps.size) eps = candidateEps
                    }
                }
                probeSources(SEARCH_SOURCES.take(2))
                // Fall back to remaining fast search sources when the primary two lack episode lists.
                if (desc.isBlank() || eps.isEmpty()) {
                    probeSources(SEARCH_SOURCES.drop(2))
                }
                TitleBundle(System.currentTimeMillis(), desc, eps)
            }
            if (loaded.description.isNotBlank() || loaded.episodes.isNotEmpty()) {
                titleBundleCache[key] = loaded
            }
            loaded
        }
    }

    internal suspend fun fetchDetailForTitle(base: String, rawTitle: String): JsonObject? {
        if (isSiteCoolingDown(base)) return null
        val queries = queryCandidates(rawTitle)
        if (queries.isEmpty()) return null
        val cacheKey = "$base|${queries.first()}"
        siteDetailCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.let { return it.detail }
        return siteDetailLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            if (isSiteCoolingDown(base)) return@withLock null
            val now = System.currentTimeMillis()
            siteDetailCache[cacheKey]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withLock it.detail }

            var bestMatch: JsonObject? = null
            for (q in queries) {
                val resp = getForSite(base, "$base?ac=vod&wd=" + URLEncoder.encode(q, "UTF-8"))
                if (resp !is SiteResponse.Ok) return@withLock null
                val rows = parseList(resp.body) ?: continue
                if (rows.isEmpty()) continue
                val matched = pickBestMatch(rows, rawTitle)
                if (matched != null) {
                    bestMatch = matched
                    break
                }
            }
            val cand = bestMatch ?: return@withLock null
            val vid = str(cand, "vod_id")
            val detailResp = getForSite(base, "$base?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8"))
            if (detailResp !is SiteResponse.Ok) return@withLock null
            val detailRows = parseList(detailResp.body)
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
        val rawTitle = item.title.trim()
        android.util.Log.i("CmsStream", "find(title=${item.title})")
        if (rawTitle.isBlank()) return@channelFlow
        val lock = Mutex()
        val bySite = arrayOfNulls<List<StreamSource>>(SOURCES.size)
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                SOURCES.mapIndexed { idx, (label, base) ->
                    launch {
                        val probed = withTimeoutOrNull(4_500L) {
                            runCatching { searchAndBuild(base, rawTitle, episode, label) }.getOrDefault(emptyList())
                        }
                        if (probed == null) markSiteUnavailable(base)
                        val sources = probed.orEmpty()
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
    }

    suspend fun find(item: CatalogItem, episode: com.shieldstream.tv.model.Episode? = null): List<StreamSource> {
        var latest: List<StreamSource> = emptyList()
        findFlow(item, episode).collect { latest = it }
        return latest
    }

    private suspend fun searchAndBuild(base: String, title: String, episode: com.shieldstream.tv.model.Episode?, siteLabel: String = "CMS"): List<StreamSource> {
        val v = fetchDetailForTitle(base, title) ?: return emptyList()
        val vid = str(v, "vod_id")
        val urls = pickPlayUrls(v, episode?.number)
        if (urls.isEmpty()) return emptyList()
        android.util.Log.i("CmsStream", "picked ${urls.size} urls (ep=${episode?.number}): ${urls.first().second.take(100)}")
        val name = str(v, "vod_name").ifBlank { title }
        return urls.mapIndexed { idx, (lineLabel, url) ->
            val tag = if (urls.size > 1 && lineLabel.isNotBlank()) "$siteLabel · $lineLabel" else siteLabel
            StreamSource(
                id = "cms:$siteLabel:$vid:$idx",
                title = if (tag.isNotBlank()) "$tag · $name" else name,
                // Modified build: unique infoHash so distinctBy() doesn't collapse CMS sources,
                // and an "ES" language tag so the "Spanish only" source filter doesn't drop them.
                infoHash = "cms-$siteLabel-$vid-$idx",
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
        }
    }

    /** CMS V10: vod_play_from and vod_play_url are '$$$' separated groups. Expand all direct http m3u8/mp4 lines. */
    internal fun pickPlayUrls(v: JsonObject, episodeNumber: Int? = null): List<Pair<String, String>> {
        val from = str(v, "vod_play_from")
        val play = str(v, "vod_play_url")
        if (play.isBlank()) return emptyList()
        val groups = play.split("\$\$\$")
        val names = from.split("\$\$\$")
        val picked = ArrayList<Pair<String, String>>(groups.size)
        val seen = HashSet<String>()
        for (i in groups.indices) {
            val label = names.getOrNull(i).orEmpty().trim()
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
            val chosenUrl = if (episodeNumber != null) {
                entries.firstOrNull { (name, _) ->
                    Regex("\\d+").find(name)?.value?.toIntOrNull() == episodeNumber
                }?.second
            } else {
                entries.firstOrNull()?.second
            }
            if (chosenUrl != null && seen.add(chosenUrl)) {
                picked += label to chosenUrl
            }
        }
        if (picked.isNotEmpty()) return picked
        if (episodeNumber != null) {
            // If requested episode isn't available yet on this source, fall back to first playable line only for movies.
            return emptyList()
        }
        // Fallback: any http url
        for (ep in play.split("#", "\$\$\$")) {
            val idx = ep.lastIndexOf('$')
            val u = (if (idx >= 0 && idx < ep.length - 1) ep.substring(idx + 1) else ep).trim()
            if (u.startsWith("http") && seen.add(u)) return listOf("" to u)
        }
        return emptyList()
    }

    private suspend fun getForSite(base: String, url: String): SiteResponse {
        if (isSiteCoolingDown(base)) return SiteResponse.Unavailable
        return suspendCancellableCoroutine { cont ->
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val call = cmsClient.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) {
                        android.util.Log.w("CmsStream", "GET $url failed: ${e.javaClass.simpleName}: ${e.message}")
                        markSiteUnavailable(base)
                    }
                    if (cont.isActive) cont.resume(SiteResponse.Unavailable)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val result = if (!resp.isSuccessful) {
                            android.util.Log.w("CmsStream", "GET $url -> HTTP ${resp.code}")
                            if (resp.code >= 500 || resp.code == 403 || resp.code == 404) {
                                markSiteUnavailable(base)
                            }
                            SiteResponse.Unavailable
                        } else {
                            siteCooldownUntil.remove(base)
                            SiteResponse.Ok(resp.body?.string().orEmpty())
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }
    }

    private fun parseList(body: String?): List<JsonObject>? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["list"]?.jsonArray
                ?.map { it.jsonObject }
        }.getOrNull()
    }

    private fun str(v: JsonObject, key: String): String =
        v[key]?.jsonPrimitive?.contentOrNull ?: ""

    internal fun queryCandidates(rawTitle: String): List<String> {
        val out = LinkedHashSet<String>()
        val noYear = rawTitle.replace(Regex("[（(].*?[)）]"), "").trim()
        val cjkOnly = clean(noYear)
        if (cjkOnly.isNotBlank()) out += cjkOnly
        val noSeason = clean(noYear.replace(Regex("(?:第[一二三四五六七八九十百\\d]+季|Season\\s*\\d+|S\\d+|年番\\s*\\d*)$", RegexOption.IGNORE_CASE), ""))
        if (noSeason.isNotBlank()) out += noSeason
        val beforeColon = clean(noYear.replace(Regex("[:：].*$"), ""))
        if (beforeColon.length >= 2) out += beforeColon
        return out.toList()
    }

    internal fun pickBestMatch(rows: List<JsonObject>, rawTitle: String): JsonObject? {
        val targetNorm = normalizeForMatch(rawTitle)
        val targetBase = normalizeForMatch(rawTitle.replace(Regex("(?:第[一二三四五六七八九十百\\d]+季|Season\\s*\\d+|S\\d+|年番\\s*\\d*)$", RegexOption.IGNORE_CASE), ""))
        val targetColonBase = normalizeForMatch(rawTitle.replace(Regex("[:：].*$"), ""))
        val targetSeason = extractSeasonNumber(rawTitle)

        var bestRow: JsonObject? = null
        var bestScore = 0
        for (row in rows) {
            val vodName = str(row, "vod_name")
            val candNorm = normalizeForMatch(vodName)
            if (candNorm.isBlank()) continue
            val candBase = normalizeForMatch(vodName.replace(Regex("(?:第[一二三四五六七八九十百\\d]+季|Season\\s*\\d+|S\\d+|年番\\s*\\d*)$", RegexOption.IGNORE_CASE), ""))
            val candSeason = extractSeasonNumber(vodName)

            var score = when {
                candNorm == targetNorm -> 100
                candBase == targetBase && targetSeason != null && candSeason == targetSeason -> 95
                candNorm == targetBase && targetSeason == 1 -> 90
                candBase == targetBase && targetSeason == null -> 85
                targetColonBase.length >= 2 && (candBase == targetColonBase || candNorm == targetColonBase) -> 80
                candNorm.length >= 2 && targetNorm.contains(candNorm) -> 65
                targetNorm.length >= 2 && candNorm.contains(targetNorm) -> 60
                targetBase.length >= 3 && candBase.contains(targetBase) -> 55
                else -> 0
            }
            if (targetSeason != null && candSeason != null && targetSeason != candSeason) {
                score -= 50
            }
            if (score > bestScore) {
                bestScore = score
                bestRow = row
            }
        }
        return if (bestScore >= 50) bestRow else null
    }

    private fun normalizeForMatch(t: String): String {
        val noYear = t.replace(Regex("[（(].*?[)）]"), "").trim()
        val unified = noYear
            .replace("第一季", "1").replace("第1季", "1")
            .replace("第二季", "2").replace("第2季", "2")
            .replace("第三季", "3").replace("第3季", "3")
            .replace("第四季", "4").replace("第4季", "4")
            .replace("第五季", "5").replace("第5季", "5")
            .replace("第六季", "6").replace("第6季", "6")
            .replace("第七季", "7").replace("第7季", "7")
            .replace("第八季", "8").replace("第8季", "8")
            .replace("第九季", "9").replace("第9季", "9")
            .replace("第十季", "10").replace("第10季", "10")
        return clean(unified)
    }

    private fun extractSeasonNumber(t: String): Int? {
        Regex("第([一二三四五六七八九十\\d]+)季").find(t)?.groupValues?.getOrNull(1)?.let { token ->
            return when (token) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                else -> token.toIntOrNull()
            }
        }
        Regex("(?:Season\\s*|S)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }
        return null
    }

    private fun clean(t: String): String {
        var v = t
            .replace(Regex("[（(].*?[)）]"), "")
            .replace("：", "")
            .replace(":", "")
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
