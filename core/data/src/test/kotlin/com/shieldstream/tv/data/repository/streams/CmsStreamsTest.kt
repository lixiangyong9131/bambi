package com.shieldstream.tv.data.repository.streams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
