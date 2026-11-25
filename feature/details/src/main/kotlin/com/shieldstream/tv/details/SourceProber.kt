package com.shieldstream.tv.details

import com.shieldstream.tv.media.DohDns
import com.shieldstream.tv.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modified build: lightweight source quality/speed probe, inspired by MoonTVPlus's
 * probe logic (HEAD latency + m3u8 variant resolution + sample bandwidth), adapted
 * to plain OkHttp since this TV app has no browser/hls.js.
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

    private val client = OkHttpClient.Builder()
        .dns(DohDns)
        .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    suspend fun probe(source: StreamSource): Result = withContext(Dispatchers.IO) {
        val url = source.directUrl?.takeIf { it.startsWith("http") }
            ?: return@withContext Result(null, "", "", false)
        val started = System.nanoTime()
        try {
            // Modified build: probe with GET only. Many CMS m3u8 CDNs reject HEAD (502/405)
            // while serving GET fine - a HEAD failure must NOT mark a good source unusable.
            val get = Request.Builder().url(url).get().build()
            client.newCall(get).execute().use { resp ->
                if (resp.code >= 400) return@withContext Result(null, "", "", false)
                val body = resp.body ?: return@withContext Result(null, "", "", false)
                val responseSource = body.source()
                // Read up to 64 KB - enough for an m3u8 master playlist or an mp4 header.
                val buffer = okio.Buffer()
                var total = 0L
                var firstByteMs: Long? = null
                repeat(64) {
                    if (responseSource.exhausted()) return@repeat
                    val read = responseSource.read(buffer, 4096)
                    if (read <= 0) return@repeat
                    if (firstByteMs == null) firstByteMs = (System.nanoTime() - started) / 1_000_000L
                    total += read
                }
                if (total <= 0L) return@withContext Result(null, "", "", false)
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val text = buffer.clone().readUtf8()
                // A playable manifest must start with #EXTM3U (or be a direct media file).
                val isMedia = text.startsWith("#EXTM3U", ignoreCase = true) ||
                    url.contains(".mp4", true) || url.contains(".m3u8", true)
                if (!isMedia) return@withContext Result(null, "", "", false)
                val quality = qualityOf(text, url)
                val bytesPerSec = total * 1000L / elapsedMs
                val speed = when {
                    bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024.0))
                    bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%d KB/s", bytesPerSec / 1024)
                    else -> ""
                }
                Result(firstByteMs, quality, speed, true)
            }
        } catch (t: Throwable) {
            Result(null, "", "", false)
        }
    }

    /** Highest resolution from an m3u8 master playlist, or infer from URL for direct files. */
    private fun qualityOf(text: String, url: String): String {
        val resolutions = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].toIntOrNull() ?: 0 }
            .filter { it > 0 }
            .toList()
        if (resolutions.isNotEmpty()) return labelForWidth(resolutions.maxOrNull()!!)
        // A media playlist has no RESOLUTION tag; the app's own metadata is a decent hint.
        return ""
    }

    private fun labelForWidth(h: Int): String = when {
        h >= 2160 -> "4K"
        h >= 1440 -> "2K"
        h >= 1080 -> "1080p"
        h >= 720 -> "720p"
        h >= 480 -> "480p"
        else -> "SD"
    }

    companion object {
        private const val TIMEOUT_MS = 3_500
    }
}
