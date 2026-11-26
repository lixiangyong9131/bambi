package com.shieldstream.tv.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shieldstream.tv.domain.ManualSkipRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualSkipRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var repo: ManualSkipRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("manual_skip", Context.MODE_PRIVATE).edit().clear().commit()
        repo = ManualSkipRepositoryImpl(context)
    }

    @Test
    fun `keyFor strips invisible direction marks and preserves match across restarts`() {
        val cleanKey = ManualSkipRepository.keyFor("凡人修仙传：重返天南", "TV")
        val dirtyKey = ManualSkipRepository.keyFor("凡人修仙传：重返天南\u200E ", "TV")
        assertEquals(cleanKey, dirtyKey)

        repo.save(cleanKey, ManualSkipRepository.Config(enabled = true, introSec = 122, outroSec = 897))
        val restored = ManualSkipRepositoryImpl(context).get(dirtyKey)
        assertEquals(ManualSkipRepository.Config(enabled = true, introSec = 122, outroSec = 897), restored)
    }

    @Test
    fun `get recovers legacy config saved with invisible characters`() {
        val prefs = context.getSharedPreferences("manual_skip", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("TV:小城良方\u200E_enabled", true)
            .putInt("TV:小城良方\u200E_intro", 32)
            .putInt("TV:小城良方\u200E_outro", 703)
            .commit()

        val loaded = repo.get(ManualSkipRepository.keyFor("小城良方", "TV"))
        assertEquals(ManualSkipRepository.Config(enabled = true, introSec = 32, outroSec = 703), loaded)
    }
}
