package com.shieldstream.tv.data.repository.streams

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CmsStreamsTest {

    private val cms = CmsStreams(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `queryCandidates keeps subtitle keywords and generates season fallbacks`() {
        val candidates = cms.queryCandidates("凡人修仙传：星海飞驰 第二季 (2024)")

        assertEquals("凡人修仙传星海飞驰第二季", candidates.first())
        assertTrue("凡人修仙传星海飞驰" in candidates)
        assertTrue("凡人修仙传" in candidates)
    }

    @Test
    fun `pickBestMatch picks exact season over unrelated first hit`() {
        val seasonOne = buildJsonObject {
            put("vod_id", "101")
            put("vod_name", "庆余年 第一季")
        }
        val seasonTwo = buildJsonObject {
            put("vod_id", "202")
            put("vod_name", "庆余年 第二季")
        }

        val picked = cms.pickBestMatch(listOf(seasonOne, seasonTwo), "庆余年 第二季")
        assertEquals("202", picked?.get("vod_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `pickBestMatch rejects wrong season instead of falling back blindly`() {
        val wrongSeason = buildJsonObject {
            put("vod_id", "101")
            put("vod_name", "白夜追凶 第一季")
        }

        val picked = cms.pickBestMatch(listOf(wrongSeason), "白夜追凶 第二季")
        assertNull(picked)
    }

    @Test
    fun `pickPlayUrls expands all playable direct lines for selected episode`() {
        val sep = "$$$"
        val detail = buildJsonObject {
            put("vod_play_from", "share${sep}lzm3u8${sep}lzcdn")
            put(
                "vod_play_url",
                "第01集\$https://share.example/1#第02集\$https://share.example/2" +
                    sep +
                    "第01集\$https://cdn1.example/ep1.m3u8#第02集\$https://cdn1.example/ep2.m3u8" +
                    sep +
                    "第01集\$https://cdn2.example/ep1.mp4#第02集\$https://cdn2.example/ep2.mp4",
            )
        }

        val urls = cms.pickPlayUrls(detail, episodeNumber = 2)

        assertEquals(
            listOf(
                "lzm3u8" to "https://cdn1.example/ep2.m3u8",
                "lzcdn" to "https://cdn2.example/ep2.mp4",
            ),
            urls,
        )
    }

    @Test
    fun `sources include verified dyttzy, ruyi, ikun, and 360zy and exclude broken bfzy`() {
        val labels = CmsStreams.SOURCES.map { it.first }
        assertTrue("电影天堂" in labels)
        assertTrue("如意资源" in labels)
        assertTrue("iKun资源" in labels)
        assertTrue("360资源" in labels)
        assertFalse("暴风资源" in labels)
    }

    @Test
    fun `unreachable site enters cooldown after one failure and skips subsequent queries`() = runBlocking {
        val hits = AtomicInteger(0)
        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread {
            runCatching {
                while (!server.isClosed) {
                    val sock = server.accept()
                    hits.incrementAndGet()
                    sock.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    sock.close()
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val base = "http://127.0.0.1:$port/api.php/provide/vod"
            val first = cms.fetchDetailForTitle(base, "庆余年：第二季")
            val second = cms.fetchDetailForTitle(base, "凡人修仙传")

            assertNull(first)
            assertNull(second)
            assertTrue(cms.isSiteCoolingDown(base))
            assertEquals(1, hits.get())
        } finally {
            server.close()
        }
    }
}
