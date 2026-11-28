package com.shieldstream.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsSourcesTest {

    @Test
    fun `normalizeUrl trims trailing slashes and rejects invalid schemes`() {
        assertEquals(
            "https://example.com/api.php/provide/vod",
            CmsSources.normalizeUrl("  https://example.com/api.php/provide/vod/  "),
        )
        assertNull(CmsSources.normalizeUrl(""))
        assertNull(CmsSources.normalizeUrl("ftp://example.com/vod"))
        assertNull(CmsSources.normalizeUrl("https://localhost/vod"))
    }

    @Test
    fun `mergeWithDefaults preserves custom sources and built-in toggle state while restoring missing defaults`() {
        val firstBuiltIn = CmsSources.DEFAULT_SOURCES.first()
        val custom = CmsSources.createCustom("我的测试源", "https://my.cms.test/api.php/provide/vod")!!

        val merged = CmsSources.mergeWithDefaults(
            listOf(
                custom,
                firstBuiltIn.copy(enabled = false, builtIn = false, name = "tampered"),
            ),
        )

        assertEquals(CmsSources.DEFAULT_SOURCES.size + 1, merged.size)
        assertEquals(custom.url, merged[0].url)
        assertFalse(merged[0].builtIn)

        val restoredFirst = merged.first { it.id == firstBuiltIn.id }
        assertFalse(restoredFirst.enabled)
        assertTrue(restoredFirst.builtIn)
        assertEquals(firstBuiltIn.name, restoredFirst.name)
    }

    @Test
    fun `createCustom deduplicates known built-in url as built-in id`() {
        val builtIn = CmsSources.DEFAULT_SOURCES[1]
        val created = CmsSources.createCustom("Renamed", builtIn.url)
        assertNotNull(created)
        assertEquals(builtIn.id, created!!.id)
        assertTrue(created.builtIn)
    }

    @Test
    fun `default sources keep top five reliable providers and prune retired built-ins on merge`() {
        assertEquals(
            listOf("builtin_dytt", "builtin_bfzy", "builtin_ryi", "builtin_ffzy", "builtin_ikun"),
            CmsSources.DEFAULT_SOURCES.map { it.id },
        )
        assertEquals("https://bambi-dytt-proxy.netlify.app/dytt/api.php/provide/vod", CmsSources.DEFAULT_SOURCES.first { it.id == "builtin_dytt" }.url)
        assertEquals("https://bambi-dytt-proxy.netlify.app/bfzy/api.php/provide/vod", CmsSources.DEFAULT_SOURCES.first { it.id == "builtin_bfzy" }.url)
        assertEquals("https://bambi-dytt-proxy.netlify.app/ryi/api.php/provide/vod", CmsSources.DEFAULT_SOURCES.first { it.id == "builtin_ryi" }.url)
        assertEquals("http://ffzy5.tv/api.php/provide/vod", CmsSources.DEFAULT_SOURCES.first { it.id == "builtin_ffzy" }.url)
        assertEquals("https://bambi-dytt-proxy.netlify.app/ikun/api.php/provide/vod", CmsSources.DEFAULT_SOURCES.first { it.id == "builtin_ikun" }.url)

        val legacySaved = listOf(
            CmsSource("builtin_ffzy", "非凡影视", "https://cj.ffzyapi.com/api.php/provide/vod", enabled = false, builtIn = true),
            CmsSource("builtin_dytt", "电影天堂", "http://caiji.dyttzyapi.com/api.php/provide/vod", enabled = true, builtIn = true),
            CmsSource("builtin_iqiyi", "iQIYI", "https://www.iqiyizyapi.com/api.php/provide/vod", enabled = false, builtIn = true),
            CmsSource("builtin_modu", "魔都资源", "https://caiji.moduapi.cc/api.php/provide/vod", enabled = false, builtIn = true),
            CmsSource("builtin_360zy", "360资源", "https://360zy.com/api.php/provide/vod", enabled = false, builtIn = true),
        )
        val merged = CmsSources.mergeWithDefaults(legacySaved)

        assertEquals(CmsSources.DEFAULT_SOURCES.size, merged.size)
        val ffzy = merged.first { it.id == "builtin_ffzy" }
        assertFalse(ffzy.enabled)
        assertEquals("http://ffzy5.tv/api.php/provide/vod", ffzy.url)
        assertEquals(1, merged.count { it.id == "builtin_ffzy" })
        assertEquals("https://bambi-dytt-proxy.netlify.app/dytt/api.php/provide/vod", merged.first { it.id == "builtin_dytt" }.url)
        assertFalse(merged.any { it.id in setOf("builtin_iqiyi", "builtin_modu", "builtin_360zy") })
    }
}
