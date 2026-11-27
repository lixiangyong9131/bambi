package com.shieldstream.tv.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.CmsSources
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsCmsSourcesTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppLanguage.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `persists custom source and disabled built-in across repository recreation`() = runTest {
        val tokenStore = mockk<TokenStore>(relaxed = true)
        val profiles = mockk<ProfileRepository>(relaxed = true)
        val repo1 = SettingsRepositoryImpl(context, tokenStore, profiles)

        val custom = CmsSources.createCustom("测试源", "https://cms.test.org/api.php/provide/vod")!!
        val updated = listOf(custom) + CmsSources.DEFAULT_SOURCES.mapIndexed { idx, src ->
            if (idx == 0) src.copy(enabled = false) else src
        }
        repo1.setCmsSources(updated)

        val repo2 = SettingsRepositoryImpl(context, tokenStore, profiles)
        val loaded = repo2.cmsSources.first()

        assertEquals(CmsSources.DEFAULT_SOURCES.size + 1, loaded.size)
        assertEquals(custom.url, loaded[0].url)
        assertEquals("测试源", loaded[0].name)
        assertFalse(loaded[0].builtIn)
        assertFalse(loaded[1].enabled)
        assertTrue(loaded[1].builtIn)
    }
}
