package com.shieldstream.tv.data.repository

import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Profile
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.api.TorrentioApi
import com.shieldstream.tv.network.dto.TmdbGenreDto
import com.shieldstream.tv.network.dto.TmdbMovieDto
import com.shieldstream.tv.network.dto.TmdbPaginated
import com.shieldstream.tv.network.dto.TmdbTvDetailDto
import com.shieldstream.tv.network.dto.TmdbTvDto
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryStreamsTest {
    private val torrentio = mockk<TorrentioApi>()
    private val settings = mockk<SettingsRepository> {
        every { sourceProviders } returns MutableStateFlow(setOf(SourceProvider.TORRENTIO, SourceProvider.KNABEN))
        every { debridService } returns MutableStateFlow(DebridService.ALLDEBRID)
        every { spanishFirst } returns MutableStateFlow(false)
    }
    private val store = mockk<TokenStore> {
        every { getSecret(any()) } answers {
            if (firstArg<String>() == TokenStore.keyNameFor(ApiService.ALLDEBRID)) "test-key" else null
        }
    }
    private val profiles = mockk<ProfileRepository> {
        every { active } returns MutableStateFlow(Profile(id = "test", name = "Test", avatar = 0))
    }
    private val texts = mockk<Texts> {
        every { get(any(), *anyVararg()) } answers { "text-${firstArg<Int>()}" }
    }
    private val repository = CatalogRepositoryImpl(
        tmdb = mockk<TmdbApi>(),
        torrentio = torrentio,
        extras = mockk<HomeExtrasSource>(),
        store = store,
        settings = settings,
        profiles = profiles,
        texts = texts,
    )

    @Test
    fun `AllDebrid scraper result survives a Torrentio failure`() = runTest {
        coEvery { torrentio.streamsWithConfig(any(), "movie", "tt1234567") } throws IOException("Torrentio unavailable")
        coEvery { torrentio.postJson("https://api.knaben.org/v1", any()) } returns """
            {"hits":[{"title":"Example Movie 2024 1080p","hash":"0123456789abcdef0123456789abcdef01234567","bytes":1073741824,"seeders":12}]}
        """.trimIndent().toResponseBody("application/json".toMediaType())

        val states = repository.getStreams(item).toList()
        val result = states.last()

        assertEquals(listOf("0123456789abcdef0123456789abcdef01234567"), result.sources.map { it.infoHash })
        assertEquals("KNABEN:0123456789abcdef0123456789abcdef01234567:0", result.sources.single().id)
        assertTrue(result.pending.isEmpty())
        coVerify(exactly = 1) { torrentio.streamsWithConfig(any(), "movie", "tt1234567") }
        coVerify(exactly = 1) { torrentio.postJson("https://api.knaben.org/v1", any()) }
    }

    @Test
    fun `browseCategoryFilter supports default popular collections and composite anime tags`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.discoverTvQuery(any(), match { it["douban_collection"] == "tv_hot" && it["page"] == "1" })
        } returns TmdbPaginated(
            results = listOf(TmdbTvDto(id = 1, name = "Hot TV", poster_path = "https://img.test/tv.jpg")),
        )
        coEvery {
            tmdbApi.discoverMovieQuery(any(), match { it["douban_collection"] == "movie_hot_gaia" && it["page"] == "1" })
        } returns TmdbPaginated(
            results = listOf(TmdbMovieDto(id = 2, title = "Hot Movie", poster_path = "https://img.test/mv.jpg")),
        )
        coEvery {
            tmdbApi.discoverTvQuery(any(), match { it["douban_collection"] == "tv_animation" && it["page"] == "1" })
        } returns TmdbPaginated(
            results = listOf(TmdbTvDto(id = 3, name = "Hot Anime", poster_path = "https://img.test/an.jpg")),
        )
        coEvery {
            tmdbApi.discoverTvQuery(
                any(),
                match { it["douban_tags"] == "动画,日本,悬疑" && it["sort_by"] == "vote_average.desc" && it["page"] == "1" },
            )
        } returns TmdbPaginated(
            results = listOf(TmdbTvDto(id = 4, name = "Mystery Anime", poster_path = "https://img.test/my.jpg")),
        )
        val tmdbStore = mockk<TokenStore>(relaxed = true) {
            every { tmdbKey } returns "douban-key"
        }
        val repo = CatalogRepositoryImpl(
            tmdb = tmdbApi,
            torrentio = torrentio,
            extras = mockk<HomeExtrasSource>(),
            store = tmdbStore,
            settings = settings,
            profiles = profiles,
            texts = texts,
        )

        assertEquals(listOf("Hot TV"), repo.browseCategoryFilter(HomeFilter.TV, "", "U", 1).map { it.title })
        assertEquals(listOf("Hot Movie"), repo.browseCategoryFilter(HomeFilter.MOVIES, "", "U", 1).map { it.title })
        assertEquals(listOf("Hot Anime"), repo.browseCategoryFilter(HomeFilter.ANIME, "", "U", 1).map { it.title })
        assertEquals(listOf("Mystery Anime"), repo.browseCategoryFilter(HomeFilter.ANIME, "日本,悬疑", "S", 1).map { it.title })
    }

    @Test
    fun `getDetails propagates genres and year onto details item`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        coEvery { tmdbApi.tvDetails(37457082, any(), any(), any(), any()) } returns TmdbTvDetailDto(
            id = 37457082,
            name = "凡人修仙传 年番4",
            first_air_date = "2026",
            genres = listOf(TmdbGenreDto(name = "动作"), TmdbGenreDto(name = "动画"), TmdbGenreDto(name = "奇幻")),
        )
        val tmdbStore = mockk<TokenStore>(relaxed = true) {
            every { tmdbKey } returns "douban-key"
        }
        val repo = CatalogRepositoryImpl(
            tmdb = tmdbApi,
            torrentio = torrentio,
            extras = mockk<HomeExtrasSource>(),
            store = tmdbStore,
            settings = settings,
            profiles = profiles,
            texts = texts,
        )

        val stub = CatalogItem(
            id = "37457082",
            imdbId = null,
            type = MediaType.TV,
            title = "凡人修仙传 年番4",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            year = null,
            rating = null,
        )
        val details = repo.getDetails(stub)
        assertEquals(listOf("动作", "动画", "奇幻"), details.item.genres)
        assertEquals(2026, details.item.year)
    }

    private val item = CatalogItem(
        id = "not-numeric",
        imdbId = "tt1234567",
        type = MediaType.MOVIE,
        title = "Example Movie",
        overview = "",
        posterUrl = null,
        backdropUrl = null,
        year = 2024,
        rating = null,
    )
}
