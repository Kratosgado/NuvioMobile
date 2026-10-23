package com.nuvio.app.features.downloads

import com.nuvio.app.features.downloads.HlsManifest.HlsManifestResult
import com.nuvio.app.features.downloads.HlsManifest.UnsupportedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HlsManifestTest {

    @Test
    fun rejectsNonPlaylistText() {
        val result = HlsManifest.parse("<html>not a playlist</html>", "https://x/y.m3u8")
        assertEquals(
            HlsManifestResult.Unsupported(UnsupportedReason.NotAPlaylist),
            result,
        )
    }

    @Test
    fun parsesMediaPlaylistWithRelativeSegments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:3.2,
            sub/seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/video/index.m3u8")
        val media = assertIs<HlsManifestResult.Media>(result)
        assertEquals(
            listOf(
                "https://cdn.example.com/video/seg0.ts",
                "https://cdn.example.com/video/seg1.ts",
                "https://cdn.example.com/video/sub/seg2.ts",
            ),
            media.playlist.segmentUrls,
        )
    }

    @Test
    fun parsesMediaPlaylistWithAbsoluteSegmentUrls() {
        val text = """
            #EXTM3U
            #EXTINF:6.0,
            https://other.example.com/a.ts
            #EXTINF:6.0,
            /root/b.ts
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/video/index.m3u8")
        val media = assertIs<HlsManifestResult.Media>(result)
        assertEquals(
            listOf(
                "https://other.example.com/a.ts",
                "https://cdn.example.com/root/b.ts",
            ),
            media.playlist.segmentUrls,
        )
    }

    @Test
    fun detectsEncryptedPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://k/key"
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://x/index.m3u8")
        assertEquals(
            HlsManifestResult.Unsupported(UnsupportedReason.Encrypted),
            result,
        )
    }

    @Test
    fun allowsExplicitKeyMethodNone() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://x/index.m3u8")
        assertIs<HlsManifestResult.Media>(result)
    }

    @Test
    fun detectsFragmentedMp4ViaMap() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg0.m4s
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://x/index.m3u8")
        assertEquals(
            HlsManifestResult.Unsupported(UnsupportedReason.FragmentedMp4),
            result,
        )
    }

    @Test
    fun reportsNoSegmentsWhenEmpty() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-ENDLIST
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://x/index.m3u8")
        assertEquals(
            HlsManifestResult.Unsupported(UnsupportedReason.NoSegments),
            result,
        )
    }

    @Test
    fun selectsBestVariantWithinHeightCap() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            v720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            v1080.m3u8
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/master.m3u8", maxHeight = 720)
        val master = assertIs<HlsManifestResult.Master>(result)
        assertEquals("https://cdn.example.com/v720.m3u8", master.selectedVariantUrl)
    }

    @Test
    fun selectsHighestBandwidthWhenNoCap() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            v1080.m3u8
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/master.m3u8", maxHeight = 0)
        val master = assertIs<HlsManifestResult.Master>(result)
        assertEquals("https://cdn.example.com/v1080.m3u8", master.selectedVariantUrl)
    }

    @Test
    fun fallsBackToLowestWhenNothingUnderCap() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            v1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=2560x1440
            v1440.m3u8
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/master.m3u8", maxHeight = 480)
        val master = assertIs<HlsManifestResult.Master>(result)
        assertEquals("https://cdn.example.com/v1080.m3u8", master.selectedVariantUrl)
    }

    @Test
    fun rejectsMasterWithOnlySeparateAudioVariants() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
            v720.m3u8
        """.trimIndent()
        val result = HlsManifest.parse(text, "https://cdn.example.com/master.m3u8", maxHeight = 1080)
        assertEquals(
            HlsManifestResult.Unsupported(UnsupportedReason.SeparateAudioOrSubtitles),
            result,
        )
    }

    @Test
    fun looksLikePlaylistDetectsHeader() {
        assertTrue(HlsManifest.looksLikePlaylist("\n  #EXTM3U\n#EXT-X-VERSION:3"))
        assertTrue(!HlsManifest.looksLikePlaylist("GET / HTTP/1.1"))
    }

    @Test
    fun resolveUrlHandlesParentDirectory() {
        assertEquals(
            "https://cdn.example.com/a/c.ts",
            HlsManifest.resolveUrl("https://cdn.example.com/a/b/index.m3u8", "../c.ts"),
        )
    }
}
