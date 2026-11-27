package com.shieldstream.tv.media

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale
import kotlin.math.abs

/**
 * Smart HLS (.m3u8) ad-segment filter for direct VOD playback (primarily CMS/direct streams).
 *
 * Unlike WebView host blocklists (which only block external HTML/JS ad requests), CMS video ads
 * are spliced directly inside `.m3u8` playlists separated by `#EXT-X-DISCONTINUITY`.
 *
 * This filter avoids naive "drop all discontinuity blocks" rules that would break legitimate
 * multi-discontinuity encodes (where every ~5 segments has a discontinuity), and instead only
 * strips blocks when high-confidence signals hold:
 * 1. Explicit ad URL/path markers isolated inside a discontinuity block.
 * 2. Short pre-roll / mid-roll / post-roll discontinuity outliers inside a VOD stream (has
 *    `#EXT-X-ENDLIST`) whose main content blocks are substantially longer (>= 25 segments or
 *    >= 100s), preserving encryption (`#EXT-X-KEY`), init maps (`#EXT-X-MAP`), and clean
 *    discontinuity transitions across removed spans.
 */
object HlsAdFilter {

    @Volatile
    var enabled: Boolean = true

    data class FilterResult(
        val content: String,
        val strippedSegments: Int,
        val strippedSeconds: Double,
    )

    private data class SegmentEntry(
        val preTags: List<String>,
        val uri: String,
        val durationSeconds: Double,
        val effectiveKey: String?,
        val effectiveMap: String?,
    )

    private data class DiscontinuityBlock(
        val index: Int,
        val hadLeadingDiscontinuity: Boolean,
        val segments: List<SegmentEntry>,
    ) {
        val totalDuration: Double get() = segments.sumOf { it.durationSeconds }
        val isLargeMainBlock: Boolean get() = segments.size >= MAIN_BLOCK_MIN_SEGMENTS || totalDuration >= MAIN_BLOCK_MIN_SECONDS
        val isSubstantialFlank: Boolean get() = segments.size >= FLANK_BLOCK_MIN_SEGMENTS || totalDuration >= FLANK_BLOCK_MIN_SECONDS
    }

    private const val MAIN_BLOCK_MIN_SEGMENTS = 25
    private const val MAIN_BLOCK_MIN_SECONDS = 100.0
    private const val FLANK_BLOCK_MIN_SEGMENTS = 20
    private const val FLANK_BLOCK_MIN_SECONDS = 80.0

    private const val MIDROLL_MAX_SEGMENTS = 14
    private const val MIDROLL_MAX_SECONDS = 55.0
    private const val EDGEROLL_MAX_SEGMENTS = 10
    private const val EDGEROLL_MAX_SECONDS = 42.0
    private const val SHORT_POSTROLL_MAX_SEGMENTS = 6
    private const val SHORT_POSTROLL_MAX_SECONDS = 25.0

    private val AD_URL_REGEX = Regex(
        """(?i)(^|[/?&._-])(adjump|guanggao|advert|adverts|ad_segment|ad_ts|ads_ts)([/?&._-]|$)|/ads?/|/ad_[^/]*\.ts""",
    )

    fun filter(playlist: String): FilterResult {
        val cleaned = playlist.removePrefix("\uFEFF")
        val rawLines = cleaned.lineSequence().map { it.trimEnd('\r') }.toList()
        val firstNonBlank = rawLines.firstOrNull { it.isNotBlank() }?.trim()
        if (firstNonBlank == null || !firstNonBlank.startsWith("#EXTM3U", ignoreCase = true)) {
            return FilterResult(playlist, strippedSegments = 0, strippedSeconds = 0.0)
        }

        // Master playlists and live sliding-window playlists (without ENDLIST) are left untouched
        // for outlier heuristics; also master playlists have no media segments.
        val hasStreamInf = rawLines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val hasExtInf = rawLines.any { it.startsWith("#EXTINF:", ignoreCase = true) }
        if (hasStreamInf || !hasExtInf) {
            return FilterResult(playlist, strippedSegments = 0, strippedSeconds = 0.0)
        }
        val isVod = rawLines.any { it.trim().equals("#EXT-X-ENDLIST", ignoreCase = true) }

        val headerLines = mutableListOf<String>()
        val blocks = mutableListOf<DiscontinuityBlock>()
        var currentSegments = mutableListOf<SegmentEntry>()
        var pendingTags = mutableListOf<String>()
        var seenSegmentOrDisc = false
        var currentHadDisc = false
        var effectiveKey: String? = null
        var effectiveMap: String? = null
        var trailingEndList = false

        for (rawLine in rawLines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    trailingEndList = true
                }
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    if (!seenSegmentOrDisc) {
                        seenSegmentOrDisc = true
                    }
                    if (currentSegments.isNotEmpty()) {
                        blocks += DiscontinuityBlock(
                            index = blocks.size,
                            hadLeadingDiscontinuity = currentHadDisc,
                            segments = currentSegments.toList(),
                        )
                        currentSegments = mutableListOf()
                    }
                    currentHadDisc = true
                    pendingTags.clear()
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    effectiveKey = line
                    if (!seenSegmentOrDisc) {
                        // Header-level key still tracked for continuity if overridden later
                        effectiveKey = line
                    }
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    effectiveMap = line
                }
                line.startsWith("#") -> {
                    if (!seenSegmentOrDisc && isPlaylistHeaderTag(line)) {
                        headerLines += rawLine
                    } else {
                        pendingTags += rawLine
                    }
                }
                else -> {
                    seenSegmentOrDisc = true
                    val duration = parseExtInfDuration(pendingTags)
                    currentSegments += SegmentEntry(
                        preTags = pendingTags.toList(),
                        uri = line,
                        durationSeconds = duration,
                        effectiveKey = effectiveKey,
                        effectiveMap = effectiveMap,
                    )
                    pendingTags.clear()
                }
            }
        }
        if (currentSegments.isNotEmpty()) {
            blocks += DiscontinuityBlock(
                index = blocks.size,
                hadLeadingDiscontinuity = currentHadDisc,
                segments = currentSegments.toList(),
            )
        }

        if (blocks.size <= 1) {
            // Single contiguous block (no discontinuity splices): never strip whole stream,
            // only check if individual explicit-ad URLs were somehow NOT needed unless mixed,
            // keep untouched to preserve zero false positives.
            return FilterResult(playlist, strippedSegments = 0, strippedSeconds = 0.0)
        }

        val adIndices = detectAdBlockIndices(blocks, isVod = isVod)
        if (adIndices.isEmpty() || adIndices.size >= blocks.size) {
            return FilterResult(playlist, strippedSegments = 0, strippedSeconds = 0.0)
        }

        val keptBlocks = blocks.filterIndexed { index, _ -> index !in adIndices }
        val strippedCount = adIndices.sumOf { blocks[it].segments.size }
        val strippedSecs = adIndices.sumOf { blocks[it].totalDuration }

        val lineSep = if (playlist.contains("\r\n")) "\r\n" else "\n"
        val out = buildString {
            headerLines.forEach { append(it).append(lineSep) }
            var emittedKey: String? = null
            var emittedMap: String? = null

            keptBlocks.forEachIndexed { keptPos, block ->
                if (keptPos > 0) {
                    append("#EXT-X-DISCONTINUITY").append(lineSep)
                }
                block.segments.forEach { seg ->
                    if (seg.effectiveKey != null && seg.effectiveKey != emittedKey) {
                        append(seg.effectiveKey).append(lineSep)
                        emittedKey = seg.effectiveKey
                    }
                    if (seg.effectiveMap != null && seg.effectiveMap != emittedMap) {
                        append(seg.effectiveMap).append(lineSep)
                        emittedMap = seg.effectiveMap
                    }
                    seg.preTags.forEach { tag ->
                        append(tag).append(lineSep)
                    }
                    append(seg.uri).append(lineSep)
                }
            }
            if (trailingEndList) {
                append("#EXT-X-ENDLIST").append(lineSep)
            }
        }

        return FilterResult(
            content = out,
            strippedSegments = strippedCount,
            strippedSeconds = strippedSecs,
        )
    }

    private fun detectAdBlockIndices(blocks: List<DiscontinuityBlock>, isVod: Boolean): Set<Int> {
        val result = linkedSetOf<Int>()

        // Pass 1: explicit ad URL/path tokens inside a discontinuity block.
        blocks.forEachIndexed { idx, block ->
            if (block.segments.isNotEmpty() && block.segments.count { isExplicitAdUri(it.uri) } * 5 >= block.segments.size * 4) {
                result += idx
            }
        }

        if (!isVod) return result

        // Pass 2: VOD statistical outlier splices where main content blocks are large (>= 25 segs or >= 100s).
        // Streams like dyttplay (every block is ~5 segs) have largeBlocksCount == 0 and are untouched.
        val nonAdBlocks = blocks.filterIndexed { idx, _ -> idx !in result }
        val largeBlocks = nonAdBlocks.filter { it.isLargeMainBlock }
        if (largeBlocks.isEmpty()) return result

        val firstLargeIdx = blocks.indexOfFirst { it.index !in result && it.isLargeMainBlock }
        val lastLargeIdx = blocks.indexOfLast { it.index !in result && it.isLargeMainBlock }
        val mainPrefixes = largeBlocks.flatMap { it.segments }
            .mapNotNull(::uriPathKey)
            .groupingBy { it }
            .eachCount()
        val dominantPrefix = mainPrefixes.maxByOrNull { it.value }?.key
        val mainLooksFixedQuarterTick = largeBlocks.flatMap { it.segments }
            .take(40)
            .let { segs -> segs.size >= 10 && segs.count { isCleanVideoTick(it.durationSeconds) } >= (segs.size * 7) / 10 }

        blocks.forEachIndexed { idx, block ->
            if (idx in result || block.segments.isEmpty() || block.isLargeMainBlock) return@forEachIndexed
            val segs = block.segments.size
            val dur = block.totalDuration

            val hasSubstantialBefore = blocks.subList(0, idx).any { it.index !in result && it.isSubstantialFlank }
            val hasSubstantialAfter = blocks.subList(idx + 1, blocks.size).any { it.index !in result && it.isSubstantialFlank }

            val isMidRoll = hasSubstantialBefore && hasSubstantialAfter &&
                segs <= MIDROLL_MAX_SEGMENTS && dur <= MIDROLL_MAX_SECONDS

            val isPreRoll = idx < firstLargeIdx &&
                segs <= EDGEROLL_MAX_SEGMENTS && dur <= EDGEROLL_MAX_SECONDS

            val isPostRoll = idx > lastLargeIdx &&
                segs <= EDGEROLL_MAX_SEGMENTS && dur <= EDGEROLL_MAX_SECONDS &&
                (
                    (segs <= SHORT_POSTROLL_MAX_SEGMENTS && dur <= SHORT_POSTROLL_MAX_SECONDS) ||
                        prefixDiffers(block, dominantPrefix) ||
                        (mainLooksFixedQuarterTick && blockHasNonQuarterTicks(block))
                    )

            if (isMidRoll || isPreRoll || isPostRoll) {
                result += idx
            }
        }

        return result
    }

    private fun isPlaylistHeaderTag(line: String): Boolean {
        return line.startsWith("#EXTM3U", ignoreCase = true) ||
            line.startsWith("#EXT-X-VERSION", ignoreCase = true) ||
            line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) ||
            line.startsWith("#EXT-X-PLAYLIST-TYPE", ignoreCase = true) ||
            line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS", ignoreCase = true) ||
            line.startsWith("#EXT-X-ALLOW-CACHE", ignoreCase = true)
    }

    private fun parseExtInfDuration(tags: List<String>): Double {
        val extinf = tags.lastOrNull { it.startsWith("#EXTINF:", ignoreCase = true) } ?: return 0.0
        val valuePart = extinf.substringAfter(':', "").substringBefore(',').trim()
        return valuePart.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    private fun isExplicitAdUri(uri: String): Boolean = AD_URL_REGEX.containsMatchIn(uri)

    private fun uriPathKey(seg: SegmentEntry): String? {
        val withoutQuery = seg.uri.substringBefore('?')
        val lastSlash = withoutQuery.lastIndexOf('/')
        return if (lastSlash > 0) withoutQuery.substring(0, lastSlash) else null
    }

    private fun prefixDiffers(block: DiscontinuityBlock, dominantPrefix: String?): Boolean {
        if (dominantPrefix == null) return false
        val prefixes = block.segments.mapNotNull(::uriPathKey)
        return prefixes.isNotEmpty() && prefixes.all { it != dominantPrefix }
    }

    /** 25fps / 50fps encoders emit multiples of 0.04s/0.02s; 29.97/30fps injectors often emit .333/.667 ticks. */
    private fun isCleanVideoTick(duration: Double): Boolean {
        if (duration <= 0.0) return false
        val ms = duration * 1000.0
        val nearest20ms = kotlin.math.round(ms / 20.0) * 20.0
        return abs(ms - nearest20ms) < 1.5
    }

    private fun blockHasNonQuarterTicks(block: DiscontinuityBlock): Boolean {
        return block.segments.count { !isCleanVideoTick(it.durationSeconds) } > block.segments.size / 2
    }
}

class HlsAdFilterInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!HlsAdFilter.enabled || !response.isSuccessful) return response

        val url = request.url.toString().lowercase(Locale.US)
        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
        val looksLikePlaylist = url.contains(".m3u8") ||
            contentType.contains("mpegurl") ||
            contentType.contains("m3u")
        if (!looksLikePlaylist) return response

        val body = response.body ?: return response
        val contentLength = body.contentLength()
        if (contentLength > MAX_PLAYLIST_BYTES) return response

        val rawBytes = try {
            body.bytes()
        } catch (_: Throwable) {
            return response
        }
        val mediaType = body.contentType() ?: "application/vnd.apple.mpegurl; charset=utf-8".toMediaTypeOrNull()
        val text = rawBytes.toString(Charsets.UTF_8)
        val filtered = HlsAdFilter.filter(text)
        if (filtered.strippedSegments > 0) {
            runCatching {
                android.util.Log.i(
                    "HlsAdFilter",
                    "Stripped ${filtered.strippedSegments} ad segment(s) (~${filtered.strippedSeconds.toInt()}s) from ${request.url.host}",
                )
            }
            return response.newBuilder()
                .removeHeader("Content-Length")
                .body(filtered.content.toResponseBody(mediaType))
                .build()
        }

        // Body stream was consumed cleanly; rebuild identical response body so Media3 can read it.
        return response.newBuilder()
            .body(rawBytes.toResponseBody(mediaType))
            .build()
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 4L * 1024L * 1024L
    }
}
