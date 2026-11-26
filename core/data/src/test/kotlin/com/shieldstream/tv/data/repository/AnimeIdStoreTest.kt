package com.shieldstream.tv.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnimeIdStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("anime_ids", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `marked anime ids persist across store instances`() {
        val store = AnimeIdStore(context)
        store.mark("12345")
        store.markAll(listOf("", "67890", "12345"))

        val reloaded = AnimeIdStore(context)
        assertTrue(reloaded.contains("12345"))
        assertTrue(reloaded.contains("67890"))
        assertFalse(reloaded.contains("99999"))
    }
}
