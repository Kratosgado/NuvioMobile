package com.nuvio.app.features.downloads

/**
 * Minimal HLS (RFC 8216) playlist parser for offline downloads.
 *
 * Scope is deliberately narrow: it supports the common "single muxed variant" case where one media
 * playlist lists self-contained MPEG-TS segments that can be concatenated into a single playable
 * `.ts` file. Anything that would require real muxing or decryption — encryption (`#EXT-X-KEY`),
 * separately-rendered audio/subtitles (`#EXT-X-MEDIA`), or fragmented-MP4 segments (`#EXT-X-MAP`) —
 * is reported as [HlsManifestResult.Unsupported] so callers can fall back gracefully instead of
 * producing a corrupt file.
 */
object HlsManifest {

    /** A selectable variant from a master playlist. */
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val height: Int?,
        /** True when the variant declares a separate audio group that would need muxing. */
        val hasSeparateAudio: Boolean,
    )

    /** A fully resolved media playlist ready to download as concatenated segments. */
    data class MediaPlaylist(
        val segmentUrls: List<String>,
    )

    sealed interface HlsManifestResult {
        /** The media playlist can be downloaded by concatenating [playlist] segments. */
        data class Media(val playlist: MediaPlaylist) : HlsManifestResult

        /**
         * The supplied text is a master playlist; [selectedVariantUrl] must be fetched and parsed
         * next. Null when no usable (muxed) variant exists.
         */
        data class Master(val selectedVariantUrl: String?) : HlsManifestResult

        /** The playlist uses a feature this downloader cannot handle offline. */
        data class Unsupported(val reason: UnsupportedReason) : HlsManifestResult
    }

    enum class UnsupportedReason {
        NotAPlaylist,
        Encrypted,
        SeparateAudioOrSubtitles,
        FragmentedMp4,
        NoMuxedVariant,
        NoSegments,
    }

    private const val HEADER = "#EXTM3U"

    /** True when [text] looks like an HLS playlist (used after an extension/content-type check). */
    fun looksLikePlaylist(text: String): Boolean =
        text.trimStart().startsWith(HEADER)

    /**
     * Parse a playlist [text] fetched from [sourceUrl].
     *
     * @param maxHeight preferred maximum variant height (e.g. 1080). The best variant at or below
     *   this is chosen; if none qualify, the lowest-height variant is used. A value <= 0 means "no
     *   preference" and selects the highest-bandwidth muxed variant.
     */
    fun parse(text: String, sourceUrl: String, maxHeight: Int = 0): HlsManifestResult {
        if (!looksLikePlaylist(text)) {
            return HlsManifestResult.Unsupported(UnsupportedReason.NotAPlaylist)
        }

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        return if (isMaster) {
            parseMaster(lines, sourceUrl, maxHeight)
        } else {
            parseMedia(lines, sourceUrl)
        }
    }

    private fun parseMaster(
        lines: List<String>,
        sourceUrl: String,
        maxHeight: Int,
    ): HlsManifestResult {
        // Audio/subtitle renditions declared separately require muxing we don't do.
        val hasSeparateMedia = lines.any { line ->
            line.startsWith("#EXT-X-MEDIA") &&
                run {
                    val type = attribute(line, "TYPE")?.uppercase()
                    type == "AUDIO" || type == "SUBTITLES"
                }
        }

        val variants = mutableListOf<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.getOrNull(i + 1)?.takeUnless { it.startsWith("#") } ?: continue
            val bandwidth = (attribute(line, "AVERAGE-BANDWIDTH") ?: attribute(line, "BANDWIDTH"))
                ?.toLongOrNull()
                ?: 0L
            val height = attribute(line, "RESOLUTION")
                ?.substringAfter('x', "")
                ?.toIntOrNull()
            val hasAudioGroup = attribute(line, "AUDIO") != null
            variants += Variant(
                url = resolveUrl(sourceUrl, uri),
                bandwidth = bandwidth,
                height = height,
                hasSeparateAudio = hasAudioGroup || hasSeparateMedia,
            )
        }

        val muxedVariants = variants.filterNot { it.hasSeparateAudio }
        if (muxedVariants.isEmpty()) {
            val reason = if (variants.isEmpty()) {
                UnsupportedReason.NoMuxedVariant
            } else {
                UnsupportedReason.SeparateAudioOrSubtitles
            }
            return HlsManifestResult.Unsupported(reason)
        }

        return HlsManifestResult.Master(selectVariant(muxedVariants, maxHeight)?.url)
    }

    private fun selectVariant(variants: List<Variant>, maxHeight: Int): Variant? {
        if (variants.isEmpty()) return null
        if (maxHeight <= 0) {
            return variants.maxByOrNull { it.bandwidth }
        }
        // Best (highest-bandwidth) variant whose height is within the cap.
        val withinCap = variants.filter { (it.height ?: Int.MAX_VALUE) <= maxHeight }
        if (withinCap.isNotEmpty()) {
            return withinCap.maxByOrNull { it.bandwidth }
        }
        // Nothing fits under the cap: fall back to the lowest-height (then lowest-bandwidth) variant.
        return variants.minWithOrNull(
            compareBy<Variant> { it.height ?: Int.MAX_VALUE }.thenBy { it.bandwidth },
        )
    }

    private fun parseMedia(lines: List<String>, sourceUrl: String): HlsManifestResult {
        // Encryption: anything other than METHOD=NONE needs key fetching + per-segment decryption.
        val encrypted = lines.any { line ->
            line.startsWith("#EXT-X-KEY") &&
                attribute(line, "METHOD")?.uppercase()?.let { it != "NONE" } == true
        }
        if (encrypted) {
            return HlsManifestResult.Unsupported(UnsupportedReason.Encrypted)
        }

        // #EXT-X-MAP indicates an initialization segment -> fragmented MP4, which concatenation of
        // media segments alone cannot assemble into a standard file reliably.
        if (lines.any { it.startsWith("#EXT-X-MAP") }) {
            return HlsManifestResult.Unsupported(UnsupportedReason.FragmentedMp4)
        }

        val segmentUrls = mutableListOf<String>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXTINF")) continue
            // The segment URI is the next non-tag line after #EXTINF.
            var j = i + 1
            while (j < lines.size && lines[j].startsWith("#")) j++
            val uri = lines.getOrNull(j) ?: continue
            segmentUrls += resolveUrl(sourceUrl, uri)
        }

        if (segmentUrls.isEmpty()) {
            return HlsManifestResult.Unsupported(UnsupportedReason.NoSegments)
        }

        return HlsManifestResult.Media(MediaPlaylist(segmentUrls))
    }

    /** Extract the value of [name] from an attribute list line, handling quoted values. */
    internal fun attribute(line: String, name: String): String? {
        val key = "$name="
        var searchFrom = 0
        while (true) {
            val idx = line.indexOf(key, searchFrom)
            if (idx < 0) return null
            // Ensure this is a real attribute boundary (start of line segment or preceded by , or space).
            val prev = line.getOrNull(idx - 1)
            if (prev != null && prev != ',' && prev != ':' && prev != ' ') {
                searchFrom = idx + key.length
                continue
            }
            val rest = line.substring(idx + key.length)
            return if (rest.startsWith('"')) {
                rest.substring(1, rest.indexOf('"', 1).takeIf { it > 0 } ?: rest.length)
            } else {
                rest.substringBefore(',').trim()
            }
        }
    }

    /**
     * Resolve a possibly-relative segment/variant [ref] against the playlist [baseUrl].
     * Handles absolute URLs, absolute paths, and relative paths. Query/fragment on the base are
     * dropped when resolving.
     */
    internal fun resolveUrl(baseUrl: String, ref: String): String {
        val reference = ref.trim()
        if (reference.startsWith("http://", ignoreCase = true) ||
            reference.startsWith("https://", ignoreCase = true)
        ) {
            return reference
        }

        val base = baseUrl.trim().substringBefore('#').substringBefore('?')
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) {
            // No scheme in base; best-effort join.
            return if (reference.startsWith("/")) reference else "${base.substringBeforeLast('/')}/$reference"
        }

        val afterScheme = base.substring(schemeEnd + 3)
        val authority = afterScheme.substringBefore('/')
        val origin = base.substring(0, schemeEnd + 3) + authority

        if (reference.startsWith("//")) {
            return base.substring(0, schemeEnd + 1) + reference
        }
        if (reference.startsWith("/")) {
            return origin + reference
        }

        // Relative to the directory of the base playlist.
        val path = afterScheme.substringAfter('/', "")
        val dir = if ('/' in path) path.substringBeforeLast('/') else ""
        val segments = ArrayDeque<String>()
        if (dir.isNotEmpty()) segments.addAll(dir.split('/'))
        for (part in reference.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return "$origin/${segments.joinToString("/")}"
    }
}
