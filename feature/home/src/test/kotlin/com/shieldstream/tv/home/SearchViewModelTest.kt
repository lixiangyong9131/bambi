package com.shieldstream.tv.home

import android.content.Context
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.RequestRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun sampleDictStream() = ByteArrayInputStream(
        """
        S	gu	谷,古
        S	yu	雨,鱼
        W	guyu	谷雨,古玉
        W	guyuan	古元
        """.trimIndent().toByteArray(Charsets.UTF_8),
    )

    private fun testItem(id: String, type: MediaType = MediaType.TV, title: String = "Title $id") = CatalogItem(
        id = id,
        imdbId = null,
        type = type,
        title = title,
        overview = "",
        posterUrl = "https://img.test/$id.jpg",
        backdropUrl = null,
        year = 2026,
        rating = 8.5f,
    )

    private fun createViewModel(catalog: CatalogRepository): SearchViewModel {
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk(relaxed = true)
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { searchQwerty } returns MutableStateFlow(false)
        }
        return SearchViewModel(
            catalogRepository = catalog,
            settings = settings,
            texts = mockk<Texts>(relaxed = true),
            requests = mockk<RequestRepository>(relaxed = true),
            appContext = context,
        )
    }

    @Test
    fun `typing letters composes pinyin and commits Chinese candidate asynchronously`() = runTest {
        PinyinEngine.loadFromStream(sampleDictStream())
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { browseCategoryFilter(any(), any(), any(), any()) } returns emptyList()
            coEvery { search(any()) } returns emptyList()
            coEvery { searchOutsideKids(any()) } returns emptyList()
        }
        val viewModel = createViewModel(catalog)

        viewModel.typeLetter('g')
        viewModel.typeLetter('u')
        viewModel.typeLetter('y')
        viewModel.typeLetter('u')
        val deadline = System.currentTimeMillis() + 2_000
        while ("谷雨" !in viewModel.candidates.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        advanceUntilIdle()

        assertEquals("guyu", viewModel.composing.value)
        assertTrue("谷雨" in viewModel.candidates.value)

        viewModel.commitCandidate("谷雨")
        assertEquals("", viewModel.composing.value)
        assertEquals("谷雨", viewModel.query.value)
        assertTrue(viewModel.candidates.value.isEmpty())
    }

    @Test
    fun `backspace clears composing buffer before committed query`() = runTest {
        PinyinEngine.loadFromStream(sampleDictStream())
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { browseCategoryFilter(any(), any(), any(), any()) } returns emptyList()
        }
        val viewModel = createViewModel(catalog)

        viewModel.typeLetter('g')
        assertTrue(viewModel.backspaceComposing())
        assertEquals("", viewModel.composing.value)
        assertFalse(viewModel.backspaceComposing())
    }

    @Test
    fun `initializes with TV popular browse results and updates live on filter changes`() = runTest {
        val tvHot = listOf(testItem("tv-1", MediaType.TV, "Hot TV"))
        val tvHotPage2 = listOf(testItem("tv-2", MediaType.TV, "Hot TV 2"))
        val koreanMystery = listOf(testItem("kr-1", MediaType.TV, "Signal"))
        val movieTop = listOf(testItem("mv-1", MediaType.MOVIE, "Top Movie"))
        val animeTop = listOf(testItem("an-1", MediaType.TV, "Frieren"))
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { browseCategoryFilter(HomeFilter.TV, "", "U", 1) } returns tvHot
            coEvery { browseCategoryFilter(HomeFilter.TV, "", "U", 2) } returns tvHotPage2
            coEvery { browseCategoryFilter(HomeFilter.TV, "韩国,悬疑", "U", 1) } returns koreanMystery
            coEvery { browseCategoryFilter(HomeFilter.MOVIES, "悬疑", "S", 1) } returns movieTop
            coEvery { browseCategoryFilter(HomeFilter.ANIME, "", "U", 1) } returns animeTop
        }
        val viewModel = createViewModel(catalog)
        advanceUntilIdle()

        val initial = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(SearchMediaType.TV, initial.filter.mediaType)
        assertEquals(SearchSort.POPULAR, initial.filter.sort)
        assertEquals(listOf("tv-1"), initial.items.map { it.id })
        assertTrue(initial.canLoadMore)

        viewModel.loadMoreFiltered()
        advanceUntilIdle()
        val paged = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(listOf("tv-1", "tv-2"), paged.items.map { it.id })

        viewModel.selectRegion("韩国")
        viewModel.toggleGenre("悬疑")
        advanceUntilIdle()

        val filtered = viewModel.uiState.value as SearchUiState.Browse
        assertEquals("韩国,悬疑", filtered.filter.selection.toDoubanTags())
        assertEquals(listOf("kr-1"), filtered.items.map { it.id })

        // Switching to MOVIES + TOP_RATED drops TV-only region? 韩国 is valid in MOVIES too,
        // while 脱口秀 (TV-only) is dropped when switching to MOVIES.
        viewModel.toggleGenre("脱口秀")
        viewModel.selectRegion(null)
        viewModel.selectSort(SearchSort.TOP_RATED)
        viewModel.selectMediaType(SearchMediaType.MOVIES)
        advanceUntilIdle()

        val movieState = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(SearchMediaType.MOVIES, movieState.filter.mediaType)
        assertEquals(setOf("悬疑"), movieState.filter.selection.genres)
        assertEquals(listOf("mv-1"), movieState.items.map { it.id })

        viewModel.selectMediaType(SearchMediaType.ANIME)
        viewModel.resetFilters()
        advanceUntilIdle()

        val animeReset = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(SearchMediaType.ANIME, animeReset.filter.mediaType)
        assertEquals(SearchSort.POPULAR, animeReset.filter.sort)
        assertFalse(animeReset.filter.selection.isActive)
        assertNull(animeReset.filter.selection.region)
        assertEquals(listOf("an-1"), animeReset.items.map { it.id })
    }

    @Test
    fun `keyword search switches to Results and clicking a filter restores Browse`() = runTest {
        val defaultTv = listOf(testItem("tv-1", MediaType.TV, "Default TV"))
        val searchHits = listOf(testItem("hit-1", MediaType.MOVIE, "沙丘"))
        val comedyTv = listOf(testItem("com-1", MediaType.TV, "喜剧剧集"))
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { browseCategoryFilter(HomeFilter.TV, "", "U", 1) } returns defaultTv
            coEvery { browseCategoryFilter(HomeFilter.TV, "喜剧", "U", 1) } returns comedyTv
            coEvery { search("沙丘") } returns searchHits
            coEvery { searchOutsideKids("沙丘") } returns emptyList()
        }
        val viewModel = createViewModel(catalog)
        advanceUntilIdle()

        viewModel.setQuery("沙丘")
        advanceUntilIdle()
        val searchState = viewModel.uiState.value as SearchUiState.Results
        assertEquals(listOf("hit-1"), searchState.items.map { it.id })

        // Clearing query returns immediately to cached browse results without re-querying
        viewModel.clear()
        advanceUntilIdle()
        val restored = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(listOf("tv-1"), restored.items.map { it.id })
        coVerify(exactly = 1) { catalog.browseCategoryFilter(HomeFilter.TV, "", "U", 1) }

        // Clicking a filter while a search query is active clears query and switches to Browse
        viewModel.setQuery("沙丘")
        advanceUntilIdle()
        viewModel.toggleGenre("喜剧")
        advanceUntilIdle()
        assertEquals("", viewModel.query.value)
        val afterFilter = viewModel.uiState.value as SearchUiState.Browse
        assertEquals(listOf("com-1"), afterFilter.items.map { it.id })
    }
}
