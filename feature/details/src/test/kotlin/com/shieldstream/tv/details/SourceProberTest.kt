package com.shieldstream.tv.details

import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

class SourceProberTest {

    @Test
    fun `labelForDimensions handles widescreen and portrait without downranking`() {
        assertEquals("4K", SourceProber.labelForDimensions(3840, 1600))
        assertEquals("2K", SourceProber.labelForDimensions(2560, 1080))
        assertEquals("1080p", SourceProber.labelForDimensions(1920, 960))
        assertEquals("720p", SourceProber.labelForDimensions(1280, 534))
        assertEquals("720p", SourceProber.labelForDimensions(720, 1280))
        assertEquals("480p", SourceProber.labelForDimensions(854, 480))
    }

    @Test
    fun `probe reads true H264 SPS from segment when master playlist has fake lower resolution tag`() = runBlocking {
        val segmentBytes = buildDisguisedTsWithReal720x1280Sps()
        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread {
            runCatching {
                repeat(3) {
                    val sock = server.accept()
                    val req = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = req.split(" ").getOrNull(1).orEmpty()
                    val out = sock.getOutputStream()
                    when {
                        path.endsWith("/master.m3u8") -> {
                            val body = "#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1080x608\nsub/index.m3u8\n"
                            writeHttp(out, "application/vnd.apple.mpegurl", body.toByteArray())
                        }
                        path.endsWith("/sub/index.m3u8") -> {
                            val body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\nseg0.ts\n"
                            writeHttp(out, "application/vnd.apple.mpegurl", body.toByteArray())
                        }
                        path.endsWith("/sub/seg0.ts") -> {
                            writeHttp(out, "video/mp2t", segmentBytes)
                        }
                    }
                    sock.close()
                }
            }
        }
        worker.isDaemon = true
        worker.start()

        try {
            val prober = SourceProber()
            val result = prober.probe(
                StreamSource(
                    id = "cms:1",
                    title = "Test",
                    infoHash = "h",
                    fileIdx = null,
                    magnet = "",
                    quality = Quality.FHD_1080,
                    hdrType = HdrType.SDR,
                    sizeBytes = 0L,
                    seeders = null,
                    isCached = true,
                    directUrl = "http://127.0.0.1:$port/master.m3u8",
                ),
            )
            assertTrue(result.ok)
            assertEquals("720p", result.quality)
            assertTrue(result.speed.isNotBlank())
        } finally {
            server.close()
        }
    }

    @Test
    fun `encrypted playlist falls back to width-aware manifest resolution tag`() = runBlocking {
        val segmentBytes = ByteArray(16 * 1024) { 0x5A }
        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread {
            runCatching {
                repeat(3) {
                    val sock = server.accept()
                    val req = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = req.split(" ").getOrNull(1).orEmpty()
                    val out = sock.getOutputStream()
                    when {
                        path.endsWith("/master.m3u8") -> {
                            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1920x960\nmedia.m3u8\n"
                            writeHttp(out, "application/vnd.apple.mpegurl", body.toByteArray())
                        }
                        path.endsWith("/media.m3u8") -> {
                            val body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:4.0,\nseg0.ts\n"
                            writeHttp(out, "application/vnd.apple.mpegurl", body.toByteArray())
                        }
                        path.endsWith("/seg0.ts") -> {
                            writeHttp(out, "video/mp2t", segmentBytes)
                        }
                    }
                    sock.close()
                }
            }
        }
        worker.isDaemon = true
        worker.start()

        try {
            val result = SourceProber().probe(
                StreamSource(
                    id = "cms:2",
                    title = "Test",
                    infoHash = "h2",
                    fileIdx = null,
                    magnet = "",
                    quality = Quality.FHD_1080,
                    hdrType = HdrType.SDR,
                    sizeBytes = 0L,
                    seeders = null,
                    isCached = true,
                    directUrl = "http://127.0.0.1:$port/master.m3u8",
                ),
            )
            assertTrue(result.ok)
            assertEquals("1080p", result.quality)
        } finally {
            server.close()
        }
    }

    private fun writeHttp(out: java.io.OutputStream, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun buildDisguisedTsWithReal720x1280Sps(): ByteArray {
        // Real High-profile H.264 SPS encoding 720x1280 from CMS segment.
        val sps = "00000167640028ac721c0b40a1b016a02040280000030008000003019078c1843c00"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pes = byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte(), 0x00, 0x00, 0x80.toByte(), 0x00, 0x00) + sps
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = 0x41 // PUSI + PID 0x0100
        pkt[2] = 0x00
        pkt[3] = 0x10 // payload only
        System.arraycopy(pes, 0, pkt, 4, pes.size)

        val out = ByteArrayOutputStream()
        // Fake JPEG header (75 bytes) before TS packets, matching disguised CDN segments.
        out.write(ByteArray(75) { 0xFF.toByte() })
        out.write(pkt)
        // Two more sync-only 188-byte TS packets + padding so MIN_SPEED_BYTES is exceeded.
        repeat(60) {
            val extra = ByteArray(188)
            extra[0] = 0x47
            extra[1] = 0x01
            extra[2] = 0x00
            extra[3] = 0x10
            out.write(extra)
        }
        return out.toByteArray()
    }
}
