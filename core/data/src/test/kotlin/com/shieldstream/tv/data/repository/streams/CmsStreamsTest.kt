package com.shieldstream.tv.data.repository.streams

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `sources include top five verified dyttzy, bfzy, ruyi, ffzy, and ikun`() {
        val labels = CmsStreams.SOURCES.map { it.first }
        assertEquals(listOf("电影天堂", "暴风资源", "如意资源", "非凡影视", "iKun资源"), labels)
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

    @Test
    fun `pickBestMatch uses year and category hints to pick 1994 live-action drama over same-title anime`() {
        val json = Json { ignoreUnknownKeys = true }
        val rows = listOf(
            json.parseToJsonElement("""{"vod_id":33299,"vod_name":"三国演义","type_name":"国产动漫","vod_year":"2025"}""").jsonObject,
            json.parseToJsonElement("""{"vod_id":58244,"vod_name":"三国演义1994","type_name":"国产剧","vod_year":"1994"}""").jsonObject,
            json.parseToJsonElement("""{"vod_id":338,"vod_name":"三国演义","type_name":"日韩动漫","vod_year":"2009"}""").jsonObject,
        )

        val liveActionHint = CmsStreams.MatchHint(year = 1994, isTv = true, isAnime = false)
        val pickedDrama = cms.pickBestMatch(rows, "三国演义", liveActionHint)
        assertEquals("58244", pickedDrama?.get("vod_id")?.toString())

        val animeHint = CmsStreams.MatchHint(year = 2009, isTv = true, isAnime = true)
        val pickedAnime = cms.pickBestMatch(rows, "三国演义", animeHint)
        assertEquals("338", pickedAnime?.get("vod_id")?.toString())
    }

    @Test
    fun `matchHintFor leaves isAnime null when genres are empty`() {
        val emptyGenres = CatalogItem(
            id = "37457082",
            imdbId = null,
            type = MediaType.TV,
            title = "凡人修仙传 年番4",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            year = 2026,
            rating = 8.9f,
            genres = emptyList(),
        )
        assertNull(cms.matchHintFor(emptyGenres).isAnime)

        val animeGenres = emptyGenres.copy(genres = listOf("动作", "动画", "奇幻"))
        assertEquals(true, cms.matchHintFor(animeGenres).isAnime)
    }

    @Test
    fun `pickBestMatch picks continuous anime over newer live-action and short dramas`() {
        val json = Json { ignoreUnknownKeys = true }
        val combinedDonghua = json.parseToJsonElement(
            """{"vod_id":9145,"vod_name":"凡人修仙传","type_name":"国产动漫","vod_year":"2020"}""",
        ).jsonObject
        val liveActionRemake = json.parseToJsonElement(
            """{"vod_id":112168,"vod_name":"凡人修仙传2025","type_name":"国产剧","vod_year":"2025"}""",
        ).jsonObject
        val liveActionMarked = json.parseToJsonElement(
            """{"vod_id":97083,"vod_name":"凡人修仙传真人版","type_name":"国产剧","vod_year":"2025"}""",
        ).jsonObject
        val shortDrama = json.parseToJsonElement(
            """{"vod_id":51623,"vod_name":"凡人修仙传","type_name":"爽文短剧","vod_year":"2026"}""",
        ).jsonObject

        val hint = CmsStreams.MatchHint(year = 2026, isTv = true, isAnime = true)
        val picked = cms.pickBestMatch(listOf(liveActionRemake, liveActionMarked, shortDrama, combinedDonghua), "凡人修仙传 年番4", hint)
        assertEquals("9145", picked?.get("vod_id")?.toString())

        // Without any anime entry, a live-action drama must not match an anime query.
        assertNull(cms.pickBestMatch(listOf(liveActionRemake, liveActionMarked, shortDrama), "凡人修仙传 年番4", hint))
    }
}
