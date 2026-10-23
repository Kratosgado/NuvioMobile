package com.nuvio.app.features.downloads

/**
 * Lightweight, poster-level download state for a single meta item, used to decorate poster cards
 * across the app (Home, Catalog, Library, folders, rails, etc.).
 *
 * A poster represents a whole movie/series, so this intentionally collapses the richer per-episode
 * detail into a simple tri-state: nothing, actively downloading, or has offline content. Progress
 * is surfaced only while actively downloading so a spinner/ring can be shown.
 */
enum class PosterDownloadStatus {
    None,
    Downloading,
    Downloaded,
}

data class PosterDownloadState(
    val status: PosterDownloadStatus = PosterDownloadStatus.None,
    /** Aggregate progress fraction [0f, 1f] while [status] is [PosterDownloadStatus.Downloading]. */
    val progress: Float = 0f,
) {
    val isDownloaded: Boolean get() = status == PosterDownloadStatus.Downloaded
    val isDownloading: Boolean get() = status == PosterDownloadStatus.Downloading

    companion object {
        val None = PosterDownloadState()

        /**
         * Derive the poster state for [metaId] from a full download list.
         *
         * Prefer deriving a map once per screen via [buildPosterStates] and doing O(1) lookups per
         * card rather than calling this per poster.
         */
        fun of(metaId: String, items: List<DownloadItem>): PosterDownloadState {
            val normalized = metaId.trim()
            if (normalized.isEmpty()) return None
            val forMeta = items.filter { it.parentMetaId == normalized }
            if (forMeta.isEmpty()) return None
            return fromItems(forMeta)
        }

        /**
         * Build a `metaId -> PosterDownloadState` map in a single pass. Only meta ids that have at
         * least one download appear in the result; absent ids are implicitly [None].
         */
        fun buildPosterStates(items: List<DownloadItem>): Map<String, PosterDownloadState> {
            if (items.isEmpty()) return emptyMap()
            return items
                .groupBy { it.parentMetaId.trim() }
                .mapNotNull { (metaId, group) ->
                    if (metaId.isEmpty()) return@mapNotNull null
                    metaId to fromItems(group)
                }
                .toMap()
        }

        private fun fromItems(forMeta: List<DownloadItem>): PosterDownloadState {
            val hasCompleted = forMeta.any { it.status == DownloadStatus.Completed }
            val inProgress = forMeta.filter { it.status != DownloadStatus.Completed }
            return when {
                inProgress.isNotEmpty() -> PosterDownloadState(
                    status = PosterDownloadStatus.Downloading,
                    progress = inProgress
                        .map { it.progressFraction }
                        .average()
                        .toFloat()
                        .coerceIn(0f, 1f),
                )
                hasCompleted -> PosterDownloadState(status = PosterDownloadStatus.Downloaded)
                else -> None
            }
        }
    }
}
