package com.shieldstream.tv.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.shieldstream.tv.data.repository.streams.CmsStreams
import com.shieldstream.tv.network.api.DoubanApi
import com.shieldstream.tv.network.dto.DoubanAbstractDto
import com.shieldstream.tv.network.dto.DoubanAbstractSubjectDto
import com.shieldstream.tv.network.dto.DoubanRexxarRatingDto
import com.shieldstream.tv.network.dto.DoubanRexxarStatsDto
import com.shieldstream.tv.network.dto.DoubanRexxarSubjectDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DoubanCatalogApiTest {

    @Test
    fun `lightweight item lookup skips extra calls while full details caches abstract and fetches Douban rating breakdown`() = runTest {
        val api = mockk<DoubanApi>()
        coEvery { api.abstract(any()) } returns DoubanAbstractDto(
            subject = DoubanAbstractSubjectDto(
                id = "123",
                title = "谷雨",
                rate = "8.5",
                types = listOf("剧情"),
                releaseYear = "2026",
            ),
        )
        coEvery { api.suggest(any()) } returns emptyList()
        coEvery { api.rexxarSubject(any()) } returns DoubanRexxarSubjectDto(
            title = "谷雨",
            rating = DoubanRexxarRatingDto(count = 38267, star_count = 3.5f, value = 7.3),
        )
        coEvery { api.rexxarRatingStats(any()) } returns DoubanRexxarStatsDto(
            stats = listOf(0.009f, 0.058f, 0.354f, 0.417f, 0.163f),
        )
        val cms = mockk<CmsStreams>()
        coEvery { cms.getDescription("谷雨") } returns "简介内容"
        coEvery { cms.getEpisodes("谷雨") } returns listOf(CmsStreams.EpisodeInfo(1, "第01集", "https://example.com/1.m3u8"))
        val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } returns this
        }
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns null
            every { edit() } returns prefsEditor
        }
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns prefs
        }

        val catalogApi = DoubanCatalogApi(api, cms, context)

        // Lightweight lookup used by Continue Watching / My List / Up Next
        val light = catalogApi.tvDetails(123, "douban", append = "external_ids")
        assertEquals("谷雨", light.name)
        coVerify(exactly = 0) { cms.getDescription(any()) }
        coVerify(exactly = 0) { cms.getEpisodes(any()) }
        coVerify(exactly = 0) { api.rexxarSubject(any()) }

        // Full details lookup used by DetailsScreen
        val full = catalogApi.tvDetails(123, "douban", append = "images,external_ids,translations,aggregate_credits,videos")
        assertEquals("简介内容", full.overview)
        assertEquals(1, full.seasons.first().episode_count)
        assertEquals(38267, full.douban_rating?.voteCount)
        assertEquals(3.5f, full.douban_rating?.starCount)
        assertEquals(5, full.douban_rating?.starDistribution?.size)
        coVerify(exactly = 1) { cms.getDescription("谷雨") }
        coVerify(exactly = 1) { cms.getEpisodes("谷雨") }
        // Subject abstract was cached from the first call
        coVerify(exactly = 1) { api.abstract(any()) }
        coVerify(exactly = 1) { api.rexxarSubject("https://m.douban.com/rexxar/api/v2/tv/123") }
    }
}
