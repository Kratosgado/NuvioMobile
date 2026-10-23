package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.contentLength
import io.ktor.client.statement.bodyAsChannel

/**
 * Resolves an HLS (`.m3u8`) URL into a concrete, downloadable list of media segment URLs by
 * fetching and parsing the playlist(s). A master playlist is followed one level to its selected
 * variant. Returns [HlsDownloadResolution.Unsupported] (rather than throwing) whenever the stream
 * needs capabilities this downloader lacks, so callers can fall back cleanly.
 */
internal object HlsDownloadResolver {

    sealed interface HlsDownloadResolution {
        data class Supported(val segmentUrls: List<String>) : HlsDownloadResolution
        data class Unsupported(val reason: HlsManifest.UnsupportedReason) : HlsDownloadResolution
        data class Failed(val message: String) : HlsDownloadResolution
    }

    /** Guards against pathological master->variant->master chains. */
    private const val MAX_FOLLOW_DEPTH = 2

    suspend fun resolve(
        url: String,
        headers: Map<String, String>,
        maxHeight: Int = 0,
        client: HttpClient = createApiHttpClient(),
    ): HlsDownloadResolution {
        var currentUrl = url
        repeat(MAX_FOLLOW_DEPTH + 1) {
            val text = runCatching { fetchText(currentUrl, headers, client) }
                .getOrElse { return HlsDownloadResolution.Failed(it.message ?: "Failed to fetch playlist") }

            when (val parsed = HlsManifest.parse(text, currentUrl, maxHeight)) {
                is HlsManifest.HlsManifestResult.Media ->
                    return HlsDownloadResolution.Supported(parsed.playlist.segmentUrls)

                is HlsManifest.HlsManifestResult.Unsupported ->
                    return HlsDownloadResolution.Unsupported(parsed.reason)

                is HlsManifest.HlsManifestResult.Master -> {
                    val variantUrl = parsed.selectedVariantUrl
                        ?: return HlsDownloadResolution.Unsupported(HlsManifest.UnsupportedReason.NoMuxedVariant)
                    currentUrl = variantUrl
                }
            }
        }
        // Exceeded follow depth without reaching a media playlist.
        return HlsDownloadResolution.Unsupported(HlsManifest.UnsupportedReason.NoMuxedVariant)
    }

    private suspend fun fetchText(
        url: String,
        headers: Map<String, String>,
        client: HttpClient,
    ): String =
        client.prepareRequest(url) {
            headers.forEach { (key, value) -> header(key, value) }
        }.execute { response ->
            check(response.status.value in 200..299) {
                "Request failed with HTTP ${response.status.value}"
            }
            readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
        }
}
