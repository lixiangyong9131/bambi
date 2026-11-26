package com.shieldstream.tv.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.shieldstream.tv.data.repository.streams.CmsStreams
import com.shieldstream.tv.network.api.DoubanApi
import com.shieldstream.tv.network.dto.DoubanAbstractDto
import com.shieldstream.tv.network.dto.DoubanAbstractSubjectDto
import com.shieldstream.tv.network.dto.DoubanRexxarCollectionItemDto
import com.shieldstream.tv.network.dto.DoubanRexxarCollectionResponseDto
import com.shieldstream.tv.network.dto.DoubanRexxarPicDto
import com.shieldstream.tv.network.dto.DoubanRexxarRatingDto
import com.shieldstream.tv.network.dto.DoubanRexxarRecommendResponseDto
import com.shieldstream.tv.network.dto.DoubanRexxarStatsDto
import com.shieldstream.tv.network.dto.DoubanRexxarSubjectDto
import com.shieldstream.tv.network.dto.DoubanSubjectDto
import com.shieldstream.tv.network.dto.DoubanSubjectsDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `charts and genre discovery use distinct rexxar feeds and cache responses`() = runTest {
        val api = mockk<DoubanApi>()
        coEvery {
            api.rexxarCollection(match { it.contains("movie_weekly_best") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "101",
                    title = "Weekly Film",
                    type = "movie",
                    cardSubtitle = "2026 / 剧情",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/weekly.jpg"),
                    rating = DoubanRexxarRatingDto(value = 8.9),
                ),
            ),
        )
        coEvery {
            api.rexxarCollection(match { it.contains("movie_hot_gaia") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "102",
                    title = "Hot Film",
                    type = "movie",
                    year = "2025",
                    cover = buildJsonObject { put("url", "https://img3.doubanio.com/hot.jpg") },
                    rating = DoubanRexxarRatingDto(value = 7.8),
                ),
            ),
        )
        coEvery {
            api.rexxarCollection(match { it.contains("movie_top250") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "1292052",
                    title = "肖申克的救赎",
                    type = "movie",
                    cardSubtitle = "1994 / 美国 / 剧情 犯罪",
                    coverUrl = "https://img3.doubanio.com/top250.jpg",
                    rating = DoubanRexxarRatingDto(value = 9.7),
                ),
            ),
        )
        coEvery {
            api.rexxarCollection(match { it.contains("tv_chinese_best_weekly") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "201",
                    title = "华语口碑剧",
                    type = "tv",
                    year = "2026",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/tvcn.jpg"),
                ),
            ),
        )
        coEvery {
            api.rexxarCollection(match { it.contains("tv_global_best_weekly") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "202",
                    title = "Global Hit",
                    type = "tv",
                    year = "2026",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/tvglobal.jpg"),
                ),
            ),
        )
        coEvery {
            api.rexxarRecommend(match { it.contains("/movie/recommend") && it.contains("%E6%83%8A%E6%82%9A") })
        } returns DoubanRexxarRecommendResponseDto(
            items = listOf(
                // Promo chart cards returned at position 0 must be filtered out.
                DoubanRexxarCollectionItemDto(id = "chart1", title = "高分榜", type = "chart", coverUrl = "https://img3.doubanio.com/c.jpg"),
                DoubanRexxarCollectionItemDto(
                    id = "301",
                    title = "釜山行",
                    type = "movie",
                    year = "2016",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/thriller.jpg"),
                    rating = DoubanRexxarRatingDto(value = 8.6),
                ),
            ),
        )
        val catalogApi = newCatalogApi(api)

        val topMovies = catalogApi.trendingMovies("douban", page = 1).results
        val topMoviesAgain = catalogApi.trendingMovies("douban", page = 1).results
        val popularMovies = catalogApi.popularMovies("douban", page = 1).results
        val top250 = catalogApi.topRatedMovies("douban", page = 1).results
        val weeklyTv = catalogApi.trendingTv("douban", page = 1).results
        val thrillers = catalogApi.discoverMovies("douban", genres = "53", page = 1).results

        assertEquals(listOf("Weekly Film"), topMovies.map { it.title })
        assertEquals("2026-01-01", topMovies.first().release_date)
        assertEquals(topMovies, topMoviesAgain)
        assertEquals(listOf("Hot Film"), popularMovies.map { it.title })
        assertEquals("https://img3.doubanio.com/hot.jpg", popularMovies.first().poster_path)
        assertEquals(listOf("肖申克的救赎"), top250.map { it.title })
        assertEquals("1994-01-01", top250.first().release_date)
        assertEquals(listOf("华语口碑剧", "Global Hit"), weeklyTv.map { it.name })
        assertEquals(listOf("釜山行"), thrillers.map { it.title })

        coVerify(exactly = 1) { api.rexxarCollection(match { it.contains("movie_weekly_best") }) }
    }

    @Test
    fun `falls back to legacy subjects search when rexxar fails`() = runTest {
        val api = mockk<DoubanApi>()
        coEvery { api.rexxarCollection(any()) } throws RuntimeException("rate limited")
        coEvery { api.rexxarRecommend(any()) } throws RuntimeException("rate limited")
        coEvery { api.subjects(type = "movie", tag = "热门", pageStart = 0) } returns DoubanSubjectsDto(
            subjects = listOf(DoubanSubjectDto(id = "555", title = "Fallback Movie", rate = "8.1", cover = "https://img3.doubanio.com/fb.jpg")),
        )
        val catalogApi = newCatalogApi(api)

        val movies = catalogApi.popularMovies("douban", page = 1).results

        assertTrue(movies.isNotEmpty())
        assertEquals("Fallback Movie", movies.first().title)
    }

    @Test
    fun `anime queries route to tv_animation collection and composite anime tags`() = runTest {
        val api = mockk<DoubanApi>()
        coEvery {
            api.rexxarCollection(match { it.contains("tv_animation") })
        } returns DoubanRexxarCollectionResponseDto(
            subjectCollectionItems = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "701",
                    title = "药屋少女的呢喃 第三季",
                    type = "tv",
                    year = "2026",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/anime.jpg"),
                ),
            ),
        )
        coEvery {
            api.rexxarRecommend(match { it.contains("/tv/recommend") && it.contains("%E5%8A%A8%E7%94%BB%2C%E6%82%AC%E7%96%91") })
        } returns DoubanRexxarRecommendResponseDto(
            items = listOf(
                DoubanRexxarCollectionItemDto(
                    id = "702",
                    title = "时光代理人 第三季",
                    type = "tv",
                    year = "2026",
                    pic = DoubanRexxarPicDto(large = "https://img3.doubanio.com/mystery.jpg"),
                ),
            ),
        )
        val catalogApi = newCatalogApi(api)

        val topAnime = catalogApi.discoverTvQuery("douban", mapOf("douban_collection" to "tv_animation", "page" to "1")).results
        val mysteryAnime = catalogApi.discoverTvQuery("douban", mapOf("douban_tags" to "动画,悬疑", "page" to "1")).results

        assertEquals(listOf("药屋少女的呢喃 第三季"), topAnime.map { it.name })
        assertEquals(listOf("时光代理人 第三季"), mysteryAnime.map { it.name })
    }

    private fun newCatalogApi(api: DoubanApi): DoubanCatalogApi {
        val cms = mockk<CmsStreams>()
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
        return DoubanCatalogApi(api, cms, context)
    }
}
