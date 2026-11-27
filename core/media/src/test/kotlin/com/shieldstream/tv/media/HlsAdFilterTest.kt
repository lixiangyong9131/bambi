package com.shieldstream.tv.media

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdFilterTest {

    @Test
    fun `removes mid-roll short discontinuity ad blocks from long VOD stream`() {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:7")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(35) { idx ->
                appendLine("#EXTINF:4.000000,")
                appendLine("main_a_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            // Mid-roll ad splice (5 segments ~19.6s)
            listOf(4.866667, 3.333333, 5.6, 2.866667, 2.966667).forEachIndexed { idx, dur ->
                appendLine("#EXTINF:$dur,")
                appendLine("ad_mid_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(40) { idx ->
                appendLine("#EXTINF:4.040000,")
                appendLine("main_b_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val res = HlsAdFilter.filter(playlist)

        assertEquals(5, res.strippedSegments)
        assertTrue(res.strippedSeconds > 19.0)
        assertFalse(res.content.contains("ad_mid_"))
        assertTrue(res.content.contains("main_a_0.ts"))
        assertTrue(res.content.contains("main_b_39.ts"))
        assertTrue(res.content.trimEnd().endsWith("#EXT-X-ENDLIST"))
        // Leading discontinuity is omitted; single discontinuity separates main_a and main_b
        assertEquals(1, res.content.lines().count { it == "#EXT-X-DISCONTINUITY" })
    }

    @Test
    fun `removes explicit ad url discontinuity block and pre-roll outlier`() {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:6")
            // Pre-roll short outlier
            repeat(3) { idx ->
                appendLine("#EXTINF:5.0,")
                appendLine("https://cdn.example.com/preroll_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(30) { idx ->
                appendLine("#EXTINF:4.0,")
                appendLine("https://cdn.example.com/movie/seg_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(2) { idx ->
                appendLine("#EXTINF:5.0,")
                appendLine("https://cdn.example.com/adjump/promo_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(30) { idx ->
                appendLine("#EXTINF:4.0,")
                appendLine("https://cdn.example.com/movie/seg2_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val res = HlsAdFilter.filter(playlist)

        assertEquals(5, res.strippedSegments)
        assertFalse(res.content.contains("preroll_"))
        assertFalse(res.content.contains("/adjump/"))
        assertTrue(res.content.contains("seg_0.ts"))
        assertTrue(res.content.contains("seg2_29.ts"))
    }

    @Test
    fun `preserves frequent-discontinuity movie streams without false positives`() {
        // Real CMS providers (e.g. dyttplay) insert EXT-X-DISCONTINUITY every 5 segments of the movie itself.
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            repeat(12) { blk ->
                if (blk > 0) appendLine("#EXT-X-DISCONTINUITY")
                repeat(5) { idx ->
                    appendLine("#EXTINF:4.0,")
                    appendLine("seg_${blk}_$idx.ts")
                }
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val res = HlsAdFilter.filter(playlist)

        assertEquals(0, res.strippedSegments)
        assertEquals(playlist, res.content)
    }

    @Test
    fun `preserves EXT-X-KEY across stripped unencrypted ad block`() {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\"")
            repeat(28) { idx ->
                appendLine("#EXTINF:4.0,")
                appendLine("enc_a_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-KEY:METHOD=NONE")
            repeat(4) { idx ->
                appendLine("#EXTINF:4.0,")
                appendLine("clear_ad_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\"")
            repeat(28) { idx ->
                appendLine("#EXTINF:4.0,")
                appendLine("enc_b_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val res = HlsAdFilter.filter(playlist)

        assertEquals(4, res.strippedSegments)
        assertFalse(res.content.contains("clear_ad_"))
        assertFalse(res.content.contains("METHOD=NONE"))
        assertTrue(res.content.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\""))
    }

    @Test
    fun `leaves master playlist and live stream untouched`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1920x1080
            3000k/hls/mixed.m3u8
        """.trimIndent()
        val live = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-MEDIA-SEQUENCE:100")
            repeat(30) { appendLine("#EXTINF:4.0,\nseg_$it.ts") }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(3) { appendLine("#EXTINF:4.0,\nclip_$it.ts") }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(30) { appendLine("#EXTINF:4.0,\nseg2_$it.ts") }
        }

        assertEquals(0, HlsAdFilter.filter(master).strippedSegments)
        assertEquals(0, HlsAdFilter.filter(live).strippedSegments)
    }

    @Test
    fun `interceptor strips ads over http and respects disabled toggle`() {
        val m3u8 = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:6")
            repeat(26) { appendLine("#EXTINF:4.0,\nm_$it.ts") }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(3) { appendLine("#EXTINF:4.0,\nad_$it.ts") }
            appendLine("#EXT-X-DISCONTINUITY")
            repeat(26) { appendLine("#EXTINF:4.0,\nm2_$it.ts") }
            appendLine("#EXT-X-ENDLIST")
        }
        val prevEnabled = HlsAdFilter.enabled
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(HlsAdFilterInterceptor())
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/vnd.apple.mpegurl")
                        .body(m3u8.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                        .build()
                }
                .build()

            HlsAdFilter.enabled = true
            val filteredBody = client.newCall(Request.Builder().url("https://cdn.example.com/video/index.m3u8").build())
                .execute().use { it.body!!.string() }
            assertFalse(filteredBody.contains("ad_0.ts"))

            HlsAdFilter.enabled = false
            val rawBody = client.newCall(Request.Builder().url("https://cdn.example.com/video/index.m3u8").build())
                .execute().use { it.body!!.string() }
            assertTrue(rawBody.contains("ad_0.ts"))
        } finally {
            HlsAdFilter.enabled = prevEnabled
        }
    }
}
