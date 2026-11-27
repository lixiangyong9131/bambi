package com.shieldstream.tv.settings

import com.shieldstream.tv.domain.ApiKeysRepository
import com.shieldstream.tv.domain.KeyCheck
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CmsSource
import com.shieldstream.tv.model.CmsSources
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CmsSourcesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sourcesFlow = MutableStateFlow(CmsSources.DEFAULT_SOURCES)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val apiKeys = mockk<ApiKeysRepository>(relaxed = true)
    private lateinit var viewModel: CmsSourcesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        every { settings.cmsSources } returns sourcesFlow
        coEvery { settings.setCmsSources(any()) } coAnswers {
            sourcesFlow.value = firstArg<List<CmsSource>>()
        }
        viewModel = CmsSourcesViewModel(settings, apiKeys, FakeTexts())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleEnabled flips built-in source state without removing it`() = runTest {
        val target = CmsSources.DEFAULT_SOURCES.first()
        viewModel.toggleEnabled(target.id)
        advanceUntilIdle()

        val updated = sourcesFlow.value.first { it.id == target.id }
        assertFalse(updated.enabled)
        assertEquals(CmsSources.DEFAULT_SOURCES.size, sourcesFlow.value.size)
    }

    @Test
    fun `save validates and prepends single custom source`() = runTest {
        val url = "https://custom.cms.org/api.php/provide/vod"
        coEvery { apiKeys.checkCmsSource(url) } returns KeyCheck.Valid("已连接 · 返回 10 条片源")

        viewModel.openEditor()
        viewModel.save("自定义源", url)
        advanceUntilIdle()

        assertNull(viewModel.editor.value)
        assertEquals(url, sourcesFlow.value.first().url)
        assertEquals("自定义源", sourcesFlow.value.first().name)
        assertFalse(sourcesFlow.value.first().builtIn)
        assertEquals("已连接 · 返回 10 条片源", viewModel.testStatus.value[sourcesFlow.value.first().id]?.detail)
    }

    @Test
    fun `remove deletes custom source but ignores built-in source`() = runTest {
        val custom = CmsSources.createCustom("Custom", "https://custom.site/api.php/provide/vod")!!
        sourcesFlow.value = listOf(custom) + CmsSources.DEFAULT_SOURCES

        viewModel.remove(CmsSources.DEFAULT_SOURCES.first().id)
        advanceUntilIdle()
        assertEquals(CmsSources.DEFAULT_SOURCES.size + 1, sourcesFlow.value.size)

        viewModel.remove(custom.id)
        advanceUntilIdle()
        assertEquals(CmsSources.DEFAULT_SOURCES.size, sourcesFlow.value.size)
        assertNull(sourcesFlow.value.firstOrNull { it.id == custom.id })
    }

    @Test
    fun `invalid url keeps editor open with error`() = runTest {
        viewModel.openEditor()
        viewModel.save("Bad", "invalid-url")
        advanceUntilIdle()

        assertNotNull(viewModel.editor.value?.error)
        assertEquals(CmsSources.DEFAULT_SOURCES.size, sourcesFlow.value.size)
    }

    private class FakeTexts : Texts {
        override fun get(id: Int, vararg formatArgs: Any): String = id.toString()
        override fun quantity(id: Int, count: Int, vararg formatArgs: Any): String = id.toString()
    }
}
