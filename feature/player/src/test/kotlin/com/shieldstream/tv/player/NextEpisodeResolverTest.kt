package com.shieldstream.tv.player

import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.DebridCacheRepository
import com.shieldstream.tv.domain.DownloadsRepository
import com.shieldstream.tv.domain.PrioritizeSourcesUseCase
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.media.DeviceCapabilityDetector
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.strings.Texts
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NextEpisodeResolverTest {

    private val resolver = NextEpisodeResolver(
        catalogRepository = mockk<CatalogRepository>(relaxed = true),
        prioritizeSourcesUseCase = PrioritizeSourcesUseCase(),
        deviceCapabilities = mockk<DeviceCapabilityDetector>(relaxed = true),
        settings = mockk<SettingsRepository>(relaxed = true),
        downloads = mockk<DownloadsRepository>(relaxed = true),
        debridCache = mockk<DebridCacheRepository>(relaxed = true),
        texts = mockk<Texts>(relaxed = true),
    )

    @Test
    fun `prioritizeDirectQueue puts fastest working stream first and failed direct streams last`() = runTest {
        val brokenDirect = directSource("broken", "https://broken.example/ep.m3u8")
        val slowDirect = directSource("slow", "https://slow.example/ep.m3u8")
        val fastDirect = directSource("fast", "https://fast.example/ep.m3u8")
        val debridFallback = StreamSource(
            id = "debrid",
            title = "Debrid",
            infoHash = "hash-debrid",
            fileIdx = 0,
            magnet = "magnet:?xt=urn:btih:123",
            quality = Quality.FHD_1080,
            hdrType = HdrType.SDR,
            sizeBytes = 1_000L,
            seeders = 10,
            isCached = true,
            resolveUrl = "https://debrid.example/resolve",
        )

        val latencies = mapOf(
            "broken" to null,
            "slow" to 420L,
            "fast" to 45L,
        )

        val reordered = resolver.prioritizeDirectQueue(
            listOf(brokenDirect, slowDirect, fastDirect, debridFallback),
            probe = { src -> latencies[src.id] },
        )

        assertEquals(listOf("fast", "slow", "debrid", "broken"), reordered.map { it.id })
    }

    private fun directSource(id: String, url: String) = StreamSource(
        id = id,
        title = id,
        infoHash = "hash-$id",
        fileIdx = null,
        magnet = "",
        quality = Quality.FHD_1080,
        hdrType = HdrType.SDR,
        sizeBytes = 0L,
        seeders = null,
        isCached = true,
        directUrl = url,
    )
}
