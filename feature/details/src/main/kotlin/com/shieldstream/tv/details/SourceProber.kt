package com.shieldstream.tv.details

import com.shieldstream.tv.media.DohDns
import com.shieldstream.tv.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modified build: lightweight source quality/speed probe, inspired by MoonTVPlus's
 * probe logic (GET latency + master-to-media variant follow + segment download throughput
 * and unencrypted H.264 SPS dimension inspection), adapted to plain OkHttp since this TV
 * app has no browser/hls.js.
 *
 * Runs off-main, has a hard timeout, and never throws - failures are returned as
 * an unusable result so probing one bad source doesn't stop the others.
 */
@Singleton
class SourceProber @Inject constructor() {

    data class Result(
        val pingMs: Long?,
        val quality: String,
        val speed: String,
        val ok: Boolean,
    ) {
        val summary: String
            get() = buildString {
                if (ok) {
                    if (quality.isNotBlank()) append(quality)
                    if (pingMs != null) {
                        if (isNotEmpty()) append(" · ")
                        append("${pingMs}ms")
                    }
                    if (speed.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(speed)
                    }
                } else append("不可用")
            }
    }

    private data class FetchSample(
        val httpCode: Int,
        val bytes: ByteArray,
        val firstByteMs: Long?,
        val elapsedMs: Long,
    )

    private val client = OkHttpClient.Builder()
        .dns(DohDns)
        .connectTimeout(ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(ENTRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    // Shares client's ConnectionPool for zero-handshake Keep-Alive reuse while capping slow CDNs.
    private val followClient = client.newBuilder()
        .connectTimeout(FOLLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FOLLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FOLLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun probe(source: StreamSource): Result = withContext(Dispatchers.IO) {
        val url = source.directUrl?.takeIf { it.startsWith("http") }
            ?: return@withContext Result(null, "", "", false)
        val probeStartedNs = System.nanoTime()
        try {
            // Modified build: probe with GET only. Many CMS m3u8 CDNs reject HEAD (502/405)
            // while serving GET fine - a HEAD failure must NOT mark a good source unusable.
            val entry = fetchSample(client, url, MAX_PLAYLIST_BYTES) ?: return@withContext Result(null, "", "", false)
            if (entry.httpCode >= 400 || entry.bytes.isEmpty()) return@withContext Result(null, "", "", false)

            val entryText = entry.bytes.toString(Charsets.UTF_8)
            val isM3u = entryText.startsWith("#EXTM3U", ignoreCase = true) || url.contains(".m3u8", true)
            val isDirectMedia = isM3u || url.contains(".mp4", true)
            if (!isDirectMedia) return@withContext Result(null, "", "", false)

            if (!isM3u) {
                return@withContext Result(entry.firstByteMs, "", formatSpeed(entry.bytes.size.toLong(), entry.elapsedMs), true)
            }

            // Capture RESOLUTION=WxH from master playlist as fallback metadata (width-aware!).
            val declaredQuality = qualityFromManifestTags(entryText)
            val fallbackResult = Result(
                pingMs = entry.firstByteMs,
                quality = declaredQuality,
                speed = formatSpeed(entry.bytes.size.toLong(), entry.elapsedMs),
                ok = true,
            )

            val remainingMs = (PROBE_BUDGET_MS - (System.nanoTime() - probeStartedNs) / 1_000_000L)
            if (remainingMs < MIN_FOLLOW_BUDGET_MS) return@withContext fallbackResult

            withTimeoutOrNull(remainingMs) {
                // Follow highest-bandwidth variant if this is a master playlist.
                val variantUrl = bestVariantUrl(url, entryText)
                val (mediaUrl, mediaSample, mediaText) = if (variantUrl != null) {
                    val sub = fetchSample(followClient, variantUrl, MAX_PLAYLIST_BYTES)
                        ?: return@withTimeoutOrNull fallbackResult
                    if (sub.httpCode >= 400 || sub.bytes.isEmpty()) {
                        return@withTimeoutOrNull Result(null, "", "", false)
                    }
                    val subText = sub.bytes.toString(Charsets.UTF_8)
                    if (!subText.startsWith("#EXTM3U", ignoreCase = true)) {
                        return@withTimeoutOrNull Result(null, "", "", false)
                    }
                    Triple(variantUrl, sub, subText)
                } else {
                    Triple(url, entry, entryText)
                }

                // Sample first video segment (up to 32 KB) for real throughput and unencrypted H.264 SPS resolution.
                val encrypted = mediaText.contains("#EXT-X-KEY", ignoreCase = true) &&
                    !mediaText.contains("METHOD=NONE", ignoreCase = true)
                val segmentUrl = firstSegmentUrl(mediaUrl, mediaText)
                var speedSample: FetchSample = mediaSample
                var detectedQuality = ""

                if (segmentUrl != null && remainingBudgetMs(probeStartedNs) >= MIN_FOLLOW_BUDGET_MS) {
                    val seg = fetchSample(followClient, segmentUrl, MAX_SEGMENT_BYTES)
                    if (seg != null) {
                        if (seg.httpCode == 404 || seg.httpCode == 410 || seg.httpCode >= 500) {
                            return@withTimeoutOrNull Result(null, "", "", false)
                        }
                        if (seg.httpCode < 400 && seg.bytes.isNotEmpty()) {
                            speedSample = seg
                            if (!encrypted) {
                                detectedQuality = parseTsH264Resolution(seg.bytes)
                                    ?.let { (w, h) -> labelForDimensions(w, h) }
                                    .orEmpty()
                            }
                        }
                    }
                }

                val quality = detectedQuality.ifBlank {
                    qualityFromManifestTags(mediaText).ifBlank { declaredQuality }
                }
                val speed = formatSpeed(speedSample.bytes.size.toLong(), speedSample.elapsedMs)
                Result(entry.firstByteMs, quality, speed, true)
            } ?: fallbackResult
        } catch (_: Throwable) {
            Result(null, "", "", false)
        }
    }

    private fun remainingBudgetMs(startedNs: Long): Long =
        PROBE_BUDGET_MS - (System.nanoTime() - startedNs) / 1_000_000L

    private fun fetchSample(httpClient: OkHttpClient, url: String, maxBytes: Long): FetchSample? {
        val started = System.nanoTime()
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code >= 400) {
                    return FetchSample(resp.code, ByteArray(0), null, 1L)
                }
                val body = resp.body ?: return null
                val responseSource = body.source()
                val buffer = Buffer()
                var total = 0L
                var firstByteMs: Long? = null
                while (total < maxBytes && !responseSource.exhausted()) {
                    val toRead = (maxBytes - total).coerceAtMost(READ_CHUNK_BYTES)
                    val read = responseSource.read(buffer, toRead)
                    if (read <= 0L) break
                    if (firstByteMs == null) firstByteMs = (System.nanoTime() - started) / 1_000_000L
                    total += read
                }
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                FetchSample(resp.code, buffer.readByteArray(), firstByteMs, elapsedMs)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatSpeed(bytes: Long, elapsedMs: Long): String {
        if (bytes < MIN_SPEED_BYTES) return ""
        val bytesPerSec = bytes * 1000L / elapsedMs.coerceAtLeast(1L)
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%d KB/s", bytesPerSec / 1024)
            else -> ""
        }
    }

    internal companion object {
        private const val ENTRY_TIMEOUT_MS = 2_800L
        private const val FOLLOW_TIMEOUT_MS = 1_500L
        private const val PROBE_BUDGET_MS = 3_500L
        private const val MIN_FOLLOW_BUDGET_MS = 300L
        private const val READ_CHUNK_BYTES = 4_096L
        private const val MAX_PLAYLIST_BYTES = 128 * 1024L
        private const val MAX_SEGMENT_BYTES = 32 * 1024L
        private const val MIN_SPEED_BYTES = 4 * 1024L

        internal fun qualityFromManifestTags(text: String): String {
            val dims = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
                .findAll(text)
                .mapNotNull { m ->
                    val w = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val h = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    if (w > 0 && h > 0) w to h else null
                }
                .toList()
            val best = dims.maxWithOrNull(compareBy<Pair<Int, Int>> { maxOf(it.first, it.second) }.thenBy { minOf(it.first, it.second) })
                ?: return ""
            return labelForDimensions(best.first, best.second)
        }

        internal fun labelForDimensions(width: Int, height: Int): String {
            val longSide = maxOf(width, height)
            val shortSide = minOf(width, height)
            return when {
                longSide >= 3800 || shortSide >= 2000 -> "4K"
                longSide >= 2500 || shortSide >= 1440 -> "2K"
                longSide >= 1900 || shortSide >= 1080 -> "1080p"
                longSide >= 1260 || shortSide >= 720 -> "720p"
                longSide >= 840 || shortSide >= 480 -> "480p"
                else -> "SD"
            }
        }

        internal fun bestVariantUrl(baseUrl: String, masterText: String): String? {
            if (!masterText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return null
            val baseHttp = baseUrl.toHttpUrlOrNull() ?: return null
            val lines = masterText.lineSequence().map { it.trim() }.toList()
            var bestBw = -1L
            var bestUri: String? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bw = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size && bw >= bestBw) {
                        bestBw = bw
                        bestUri = baseHttp.resolve(lines[j])?.toString()
                    }
                    i = j
                } else {
                    i++
                }
            }
            return bestUri
        }

        internal fun firstSegmentUrl(mediaUrl: String, mediaText: String): String? {
            val baseHttp = mediaUrl.toHttpUrlOrNull() ?: return null
            val lines = mediaText.lineSequence().map { it.trim() }.toList()
            var seenExtInf = false
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    seenExtInf = true
                    continue
                }
                if (line.startsWith("#")) continue
                if (seenExtInf) return baseHttp.resolve(line)?.toString()
            }
            return null
        }

        /**
         * Parses actual (width, height) from an H.264 SPS NAL unit carried in an MPEG-TS segment
         * (including TS files prepended with disguised image headers) or raw Annex-B stream.
         */
        internal fun parseTsH264Resolution(bytes: ByteArray): Pair<Int, Int>? {
            val sync = findTsSyncOffset(bytes)
            if (sync >= 0) {
                val streams = LinkedHashMap<Int, ByteArrayOutputStream>()
                var pos = sync
                while (pos + 188 <= bytes.size) {
                    if (bytes[pos] != 0x47.toByte()) {
                        pos++
                        continue
                    }
                    val b1 = bytes[pos + 1].toInt() and 0xFF
                    val b2 = bytes[pos + 2].toInt() and 0xFF
                    val pid = ((b1 and 0x1F) shl 8) or b2
                    if (pid == 0x0000 || pid == 0x1FFF) {
                        pos += 188
                        continue
                    }
                    val pusi = (b1 and 0x40) != 0
                    val afc = (bytes[pos + 3].toInt() and 0x30) ushr 4
                    var payloadStart = pos + 4
                    if (afc == 2 || afc == 0) {
                        pos += 188
                        continue
                    }
                    if (afc == 3) {
                        val adaptLen = bytes[payloadStart].toInt() and 0xFF
                        payloadStart += 1 + adaptLen
                    }
                    val packetEnd = pos + 188
                    if (payloadStart < packetEnd) {
                        if (pusi && payloadStart + 9 <= packetEnd &&
                            bytes[payloadStart] == 0.toByte() &&
                            bytes[payloadStart + 1] == 0.toByte() &&
                            bytes[payloadStart + 2] == 1.toByte()
                        ) {
                            val streamId = bytes[payloadStart + 3].toInt() and 0xFF
                            if (streamId in 0xE0..0xEF) {
                                val pesHeaderLen = bytes[payloadStart + 8].toInt() and 0xFF
                                val esStart = payloadStart + 9 + pesHeaderLen
                                val buf = streams.getOrPut(pid) { ByteArrayOutputStream() }
                                if (esStart < packetEnd) buf.write(bytes, esStart, packetEnd - esStart)
                            }
                        } else if (!pusi) {
                            streams[pid]?.write(bytes, payloadStart, packetEnd - payloadStart)
                        }
                    }
                    pos += 188
                }
                for (buf in streams.values) {
                    findAnnexBSpsDimensions(buf.toByteArray())?.let { return it }
                }
            }
            return findAnnexBSpsDimensions(bytes)
        }

        private fun findTsSyncOffset(bytes: ByteArray): Int {
            val limit = (bytes.size - 377).coerceAtMost(2048)
            for (i in 0..limit) {
                if (bytes[i] == 0x47.toByte() &&
                    bytes[i + 188] == 0x47.toByte() &&
                    bytes[i + 376] == 0x47.toByte()
                ) {
                    return i
                }
            }
            return -1
        }

        internal fun findAnnexBSpsDimensions(data: ByteArray): Pair<Int, Int>? {
            var i = 0
            while (i + 4 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    val nalType = data[i + 3].toInt() and 0x1F
                    if (nalType == 7) {
                        val end = (i + 96).coerceAtMost(data.size)
                        parseH264Sps(data, i + 4, end)?.let { (w, h) ->
                            if (w in 128..7680 && h in 96..4320) return w to h
                        }
                    }
                    i += 3
                } else {
                    i++
                }
            }
            return null
        }

        private fun parseH264Sps(src: ByteArray, start: Int, end: Int): Pair<Int, Int>? = runCatching {
            val rbsp = ByteArray(end - start)
            var len = 0
            var i = start
            while (i < end) {
                if (i + 2 < end && src[i] == 0.toByte() && src[i + 1] == 0.toByte() && src[i + 2] == 3.toByte()) {
                    rbsp[len++] = 0
                    rbsp[len++] = 0
                    i += 3
                } else {
                    rbsp[len++] = src[i++]
                }
            }
            val r = BitReader(rbsp, len)
            val profileIdc = r.bits(8)
            r.bits(16) // constraint flags + level_idc
            r.ue() // seq_parameter_set_id
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = r.ue()
                if (chromaFormatIdc == 3) r.bit()
                r.ue()
                r.ue()
                r.bit()
                if (r.bit() == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (idx in 0 until count) {
                        if (r.bit() == 1) {
                            val size = if (idx < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) {
                                    nextScale = (lastScale + r.se() + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }
            r.ue() // log2_max_frame_num_minus4
            val picOrderCntType = r.ue()
            if (picOrderCntType == 0) {
                r.ue()
            } else if (picOrderCntType == 1) {
                r.bit()
                r.se()
                r.se()
                val numRef = r.ue().coerceAtMost(255)
                repeat(numRef) { r.se() }
            }
            r.ue() // max_num_ref_frames
            r.bit() // gaps_in_frame_num_allowed_flag
            val widthInMbs = r.ue() + 1
            val heightInMapUnits = r.ue() + 1
            val frameMbsOnlyFlag = r.bit()
            if (frameMbsOnlyFlag == 0) r.bit()
            r.bit() // direct_8x8_inference_flag
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (r.bit() == 1) {
                cropLeft = r.ue()
                cropRight = r.ue()
                cropTop = r.ue()
                cropBottom = r.ue()
            }
            val w = widthInMbs * 16 - (cropLeft + cropRight) * 2
            val h = (2 - frameMbsOnlyFlag) * heightInMapUnits * 16 - (cropTop + cropBottom) * 2
            if (r.overflow || w <= 0 || h <= 0) null else w to h
        }.getOrNull()

        private class BitReader(private val data: ByteArray, length: Int) {
            private val totalBits = length * 8
            private var pos = 0
            var overflow = false

            fun bit(): Int {
                if (pos >= totalBits) {
                    overflow = true
                    return 0
                }
                val v = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
                pos++
                return v
            }

            fun bits(n: Int): Int {
                var v = 0
                repeat(n) { v = (v shl 1) or bit() }
                return v
            }

            fun ue(): Int {
                var zeros = 0
                while (!overflow && bit() == 0) {
                    zeros++
                    if (zeros > 31) {
                        overflow = true
                        return 0
                    }
                }
                return if (zeros == 0) 0 else ((1 shl zeros) - 1) + bits(zeros)
            }

            fun se(): Int {
                val v = ue()
                return if ((v and 1) == 1) (v + 1) ushr 1 else -(v ushr 1)
            }
        }
    }
}
