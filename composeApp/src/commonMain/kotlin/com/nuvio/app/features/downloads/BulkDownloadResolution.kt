package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.streams.StreamItem

/**
 * Resolution classification used by bulk downloads. Wraps [DebridStreamResolution] so the
 * download policy and repository can group streams by picture quality without depending on
 * debrid preference plumbing.
 */
internal object BulkDownloadResolution {

    /** Classify a single stream to its [DebridStreamResolution] (falls back to [DebridStreamResolution.UNKNOWN]). */
    fun of(stream: StreamItem): DebridStreamResolution = DebridStreamMetadata.resolutionOf(stream)

    /**
     * Resolutions ordered highest-to-lowest, with [DebridStreamResolution.UNKNOWN] always last.
     * Used both to display the picker and to drive next-best fallback.
     */
    val descendingOrder: List<DebridStreamResolution> = DebridStreamResolution.defaultOrder

    /** Index of [resolution] within [descendingOrder]; higher value means lower quality. */
    fun rankOf(resolution: DebridStreamResolution): Int =
        descendingOrder.indexOf(resolution).let { if (it >= 0) it else descendingOrder.lastIndex }

    /** Human label, e.g. "2160p" or "Unknown". */
    fun labelOf(resolution: DebridStreamResolution): String = resolution.label
}
