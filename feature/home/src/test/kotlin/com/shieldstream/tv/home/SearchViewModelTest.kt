package com.shieldstream.tv.home

import android.content.Context
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.RequestRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `typing letters composes pinyin and commits Chinese candidate asynchronously`() = runTest {
        PinyinEngine.loadFromStream(sampleDictStream())
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk(relaxed = true)
        }
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { trending() } returns emptyList()
            coEvery { search(any()) } returns emptyList()
            coEvery { searchOutsideKids(any()) } returns emptyList()
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { searchQwerty } returns MutableStateFlow(false)
        }
        val viewModel = SearchViewModel(
            catalogRepository = catalog,
            settings = settings,
            texts = mockk<Texts>(relaxed = true),
            requests = mockk<RequestRepository>(relaxed = true),
            appContext = context,
        )

        viewModel.typeLetter('g')
        viewModel.typeLetter('u')
        viewModel.typeLetter('y')
        viewModel.typeLetter('u')
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
        val context = mockk<Context>(relaxed = true)
        val catalog = mockk<CatalogRepository>(relaxed = true) {
            coEvery { trending() } returns emptyList()
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { searchQwerty } returns MutableStateFlow(false)
        }
        val viewModel = SearchViewModel(
            catalogRepository = catalog,
            settings = settings,
            texts = mockk<Texts>(relaxed = true),
            requests = mockk<RequestRepository>(relaxed = true),
            appContext = context,
        )

        viewModel.typeLetter('g')
        assertTrue(viewModel.backspaceComposing())
        assertEquals("", viewModel.composing.value)
        assertFalse(viewModel.backspaceComposing())
    }
}
