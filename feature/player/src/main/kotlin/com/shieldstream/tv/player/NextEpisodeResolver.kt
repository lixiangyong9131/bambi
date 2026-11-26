package com.shieldstream.tv.player

import android.net.Uri
import android.util.Log
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.DebridCacheRepository
import com.shieldstream.tv.domain.DownloadsRepository
import com.shieldstream.tv.domain.NextEpisode
import com.shieldstream.tv.domain.PrioritizeSourcesUseCase
import com.shieldstream.tv.domain.SeriesPlaybackContext
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.media.DeviceCapabilityDetector
import com.shieldstream.tv.model.DeviceOptimization
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.PlaybackMedia
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.model.WatchProgress
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.strings.episodeCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import com.shieldstream.tv.media.DohDns
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal data class ResolvedNextEpisode(
    val queue: List<StreamSource>,
    val label: String,
    val progressKey: String,
    val media: PlaybackMedia,
    val next: NextEpisode?,
    val seriesContext: SeriesPlaybackContext,
)

/**
 * Resolves the next episode's playback queue directly for in-player episode transitions
 * so playback continues without popping back to the Details screen.
 */
class NextEpisodeResolver @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val prioritizeSourcesUseCase: PrioritizeSourcesUseCase,
    private val deviceCapabilities: DeviceCapabilityDetector,
    private val settings: SettingsRepository,
    private val downloads: DownloadsRepository,
    private val debridCache: DebridCacheRepository,
    private val texts: Texts,
) {
    internal suspend fun resolve(
        context: SeriesPlaybackContext,
        target: NextEpisode,
    ): ResolvedNextEpisode? {
        val seasonEpisodes = findSeasonEpisodes(context, target.season)
        val episode = seasonEpisodes.firstOrNull {
            it.season == target.season && it.number == target.episode
        } ?: Episode(
            season = target.season,
            number = target.episode,
            title = target.label,
            overview = "",
            runtimeMinutes = null,
            airDate = null,
            stillUrl = null,
        )

        val item = context.item
        val progressKey = WatchProgress.episodeKey(item.id, episode.season, episode.number)
        val label = "${item.title} · ${texts.episodeCode(episode)} · ${episode.title}"
        val media = PlaybackMedia(
            type = item.type,
            imdbId = item.imdbId,
            title = item.title,
            originalTitle = context.originalTitle,
            year = item.year,
            season = episode.season,
            episode = episode.number,
        )
        val updatedContext = if (seasonEpisodes.isNotEmpty()) {
            context.copy(episodes = seasonEpisodes)
        } else {
            context
        }
        val following = nextEpisodeAfter(updatedContext, episode)

        val localPath = downloads.localFileFor(progressKey)
        if (localPath != null) {
            val file = File(localPath)
            val local = StreamSource(
                id = "LOCAL:$progressKey",
                title = texts.get(StringsR.string.downloads_local_source),
                infoHash = "",
                fileIdx = null,
                magnet = "",
                quality = Quality.UNKNOWN,
                hdrType = HdrType.SDR,
                sizeBytes = file.length(),
                seeders = null,
                isCached = true,
                directUrl = Uri.fromFile(file).toString(),
                provider = texts.get(StringsR.string.downloads_title),
            )
            return ResolvedNextEpisode(
                queue = listOf(local),
                label = label,
                progressKey = progressKey,
                media = media,
                next = following,
                seriesContext = updatedContext,
            )
        }

        val spanishFirst = settings.spanishFirst.first()
        val spanishOnly = settings.spanishOnlySources.first()
        val sourceExclusions = settings.sourceExclusions.first()
        val brokenSources = settings.brokenSourceIds.first()
        val preferredQuality = settings.preferredQuality.first()
        val optimization = settings.deviceOptimization.first()
        val capabilities = deviceCapabilities.capabilities
        val hardwareCodecs = when (optimization) {
            DeviceOptimization.AUTOMATIC -> capabilities.hardwareVideoCodecs
            DeviceOptimization.CUSTOM -> emptySet()
        }

        val checkedHashes = HashSet<String>()
        val debridCached = HashSet<String>()
        val queue = catalogRepository.getStreams(item, episode)
            .mapNotNull { result ->
                val unchecked = result.sources.filter { it.resolveUrl == null }
                    .map { it.infoHash.lowercase() }
                    .filter { it !in checkedHashes }
                    .distinct()
                if (unchecked.isNotEmpty()) {
                    checkedHashes += unchecked
                    val ready = runCatching { debridCache.cachedAmong(unchecked) }
                        .onFailure { Log.w(TAG, "Debrid cache check failed: ${it.message}") }
                        .getOrDefault(emptySet())
                    debridCached += ready
                }
                val knownCached = debridCached + result.sources.filter { it.isCached }.map { it.infoHash.lowercase() }
                val enriched = result.sources.map {
                    if (it.resolveUrl == null) it.copy(isCached = it.infoHash.lowercase() in knownCached) else it
                }
                val sorted = prioritizeSourcesUseCase(
                    enriched,
                    capabilities.supportedHdr,
                    hardwareVideoCodecs = hardwareCodecs,
                    preferSpanish = spanishFirst || spanishOnly,
                    preferred = preferredQuality,
                )
                val eligible = sorted.filter { source ->
                    val brokenKey = source.infoHash.trim().lowercase().ifBlank { source.id }
                    brokenKey !in brokenSources &&
                        source.id !in brokenSources &&
                        !sourceExclusions.excludes(source) &&
                        (!spanishOnly || source.advertisesSpanishAudio)
                }
                    .distinctBy { it.infoHash.lowercase() }
                    .filterIndexed { index, s -> index == 0 || s.resolveUrl == null || s.isCached }
                    .take(MAX_QUEUE)
                eligible.takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            ?.let { prioritizeDirectQueue(it) }
            ?: return null

        return ResolvedNextEpisode(
            queue = queue,
            label = label,
            progressKey = progressKey,
            media = media,
            next = following,
            seriesContext = updatedContext,
        )
    }

    private suspend fun findSeasonEpisodes(
        context: SeriesPlaybackContext,
        season: Int,
    ): List<Episode> {
        val current = context.episodes
        if (current.any { it.season == season }) return current
        return runCatching { catalogRepository.getEpisodes(context.item, season) }
            .onFailure { Log.w(TAG, "Failed to load episodes for S$season: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun nextEpisodeAfter(
        context: SeriesPlaybackContext,
        episode: Episode,
    ): NextEpisode? {
        val eps = context.episodes
        val showId = context.item.id
        val index = eps.indexOfFirst { it.season == episode.season && it.number == episode.number }
        eps.getOrNull(index + 1)?.takeIf { index >= 0 }?.let {
            return NextEpisode(showId, it.season, it.number, "${texts.episodeCode(it)} · ${it.title}")
        }
        val nextSeason = context.seasons.map { it.number }.filter { it > episode.season }.minOrNull() ?: return null
        return NextEpisode(showId, nextSeason, 1, texts.get(StringsR.string.details_next_episode_label, nextSeason))
    }

    internal suspend fun prioritizeDirectQueue(
        sources: List<StreamSource>,
        probe: suspend (StreamSource) -> Long? = ::probeDirectSourceMs,
    ): List<StreamSource> {
        val candidates = sources.filter { it.directUrl?.startsWith("http") == true }.take(MAX_DIRECT_PROBES)
        if (candidates.size < 2) return sources
        val results = coroutineScope {
            candidates.map { source -> async { source.id to probe(source) } }.awaitAll()
        }
        val latencyById = results.toMap()
        val fastWorking = sources
            .filter { latencyById[it.id] != null }
            .sortedBy { latencyById.getValue(it.id) }
        val untouched = sources.filter { it.id !in latencyById }
        val failedDirect = sources.filter { it.id in latencyById && latencyById[it.id] == null }
        return fastWorking + untouched + failedDirect
    }

    private suspend fun probeDirectSourceMs(source: StreamSource): Long? = withContext(Dispatchers.IO) {
        val url = source.directUrl?.takeIf { it.startsWith("http") } ?: return@withContext null
        val started = System.nanoTime()
        runCatching {
            val request = Request.Builder().url(url).get().build()
            probeClient.newCall(request).execute().use { resp ->
                if (resp.code >= 400) return@use null
                val body = resp.body ?: return@use null
                val buffer = Buffer()
                val read = body.source().read(buffer, PROBE_READ_BYTES)
                if (read <= 0L) return@use null
                val head = buffer.readUtf8()
                val playable = head.startsWith("#EXTM3U", ignoreCase = true) ||
                    url.contains(".mp4", ignoreCase = true) ||
                    url.contains(".m3u8", ignoreCase = true)
                if (!playable) return@use null
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "NextEpisodeResolver"
        const val MAX_QUEUE = 30
        const val MAX_DIRECT_PROBES = 4
        const val PROBE_TIMEOUT_MS = 1_500L
        const val PROBE_READ_BYTES = 8 * 1024L

        val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(DohDns)
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
