package com.shieldstream.tv.details

import com.shieldstream.tv.model.CatalogDetails
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyStreamHintTest {

    private val movie = CatalogDetails(
        item = CatalogItem(
            id = "100",
            imdbId = "tt100",
            type = MediaType.MOVIE,
            title = "Test Movie",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            year = 2025,
            rating = null,
        ),
    )

    private fun directSource(
        id: String = "s1",
        quality: Quality = Quality.FHD_1080,
        hdr: HdrType = HdrType.SDR,
        provider: String = "非凡影视",
    ) = StreamSource(
        id = id,
        title = provider,
        infoHash = "",
        fileIdx = null,
        magnet = "",
        quality = quality,
        hdrType = hdr,
        sizeBytes = 0L,
        seeders = null,
        isCached = false,
        directUrl = "https://cdn.example.com/index.m3u8",
        provider = provider,
    )

    @Test
    fun `formats direct ready hint with probed quality and latency`() {
        val src = directSource(quality = Quality.UNKNOWN, provider = "非凡影视")
        val state = DetailsUiState.Content(
            details = movie,
            sources = SourcesState.Ready(listOf(src)),
            sourceProbes = mapOf(
                src.id to SourceProber.Result(pingMs = 64L, quality = "1080p", speed = "12 MB/s", ok = true),
            ),
        )

        val hint = buildReadyStreamHint(state, spanish = false)
        assertEquals("已就绪 · 1080p 直连 · 非凡影视 · 64ms", hint?.text)
        assertTrue(hint?.isReady == true)
    }

    @Test
    fun `prefers saved sourceId from watch progress`() {
        val s1 = directSource(id = "s1", provider = "魔都资源")
        val s2 = directSource(id = "s2", quality = Quality.HD_720, provider = "无尽资源")
        val key = WatchProgress.movieKey(movie.item.id)
        val state = DetailsUiState.Content(
            details = movie,
            sources = SourcesState.Ready(listOf(s1, s2)),
            progress = mapOf(
                key to WatchProgress(
                    positionMs = 60_000L,
                    durationMs = 3_600_000L,
                    updatedAt = 1L,
                    sourceId = "s2",
                ),
            ),
        )

        val hint = buildReadyStreamHint(state, spanish = false)
        assertEquals("已就绪 · 720p 直连 · 无尽资源", hint?.text)
    }

    @Test
    fun `formats cached debrid HDR stream cleanly`() {
        val debrid = StreamSource(
            id = "rd1",
            title = "Movie.2160p.DV.REMUX",
            infoHash = "abc",
            fileIdx = 0,
            magnet = "magnet:?xt=urn:btih:abc",
            quality = Quality.UHD_2160,
            hdrType = HdrType.DOLBY_VISION,
            sizeBytes = 10_000L,
            seeders = 50,
            isCached = true,
            directUrl = null,
            provider = "Torrentio",
            debrid = "Real-Debrid",
        )
        val state = DetailsUiState.Content(
            details = movie,
            sources = SourcesState.Ready(listOf(debrid)),
        )

        val chHint = buildReadyStreamHint(state, spanish = false)
        assertEquals("已就绪 · 4K Dolby Vision 秒播缓存 · Torrentio", chHint?.text)

        val esHint = buildReadyStreamHint(state, spanish = true)
        assertEquals("Listo · 4K Dolby Vision Caché ⚡ · Torrentio", esHint?.text)
    }

    @Test
    fun `shows matching state while loading and hides when no sources`() {
        val loadingState = DetailsUiState.Content(details = movie, sources = SourcesState.Loading)
        val loadingHint = buildReadyStreamHint(loadingState, spanish = false)
        assertFalse(loadingHint!!.isReady)

        val emptyState = DetailsUiState.Content(details = movie, sources = SourcesState.Ready(emptyList()))
        assertNull(buildReadyStreamHint(emptyState, spanish = false))
    }
}
