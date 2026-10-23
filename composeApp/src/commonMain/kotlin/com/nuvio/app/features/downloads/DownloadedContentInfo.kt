package com.nuvio.app.features.downloads

/**
 * Derived "what is available offline" information for a single meta item's downloads, used to
 * decorate the details screen (episode cards, season chips, hero badge) with downloaded marks
 * and in-progress download indicators.
 */
data class DownloadedContentInfo(
    val movieDownloaded: Boolean = false,
    /** Keys of completed episode downloads, in [BulkDownloadPolicy.episodeContentKey] format ("s|e"). */
    val downloadedEpisodeKeys: Set<String> = emptySet(),
    /** Completed download counts per season. */
    val seasonDownloadedCounts: Map<Int, Int> = emptyMap(),
    /** Progress fraction [0f, 1f] for an in-progress movie download, or null if not downloading. */
    val movieDownloadProgress: Float? = null,
    /**
     * In-progress episode downloads keyed by [BulkDownloadPolicy.episodeContentKey] ("s|e"),
     * mapped to their current progress fraction [0f, 1f]. Excludes completed episodes.
     */
    val downloadingEpisodeProgress: Map<String, Float> = emptyMap(),
    /** In-progress (non-completed) download counts per season. */
    val seasonDownloadingCounts: Map<Int, Int> = emptyMap(),
) {
    val hasAnyDownloadedContent: Boolean
        get() = movieDownloaded || downloadedEpisodeKeys.isNotEmpty()

    val hasAnyInProgressContent: Boolean
        get() = movieDownloadProgress != null || downloadingEpisodeProgress.isNotEmpty()

    fun isEpisodeDownloaded(seasonNumber: Int, episodeNumber: Int): Boolean =
        "${BulkDownloadPolicy.episodeContentKey(seasonNumber, episodeNumber)}" in
            downloadedEpisodeKeys

    /** Progress fraction for an in-progress episode download, or null if it is not downloading. */
    fun episodeDownloadProgress(seasonNumber: Int, episodeNumber: Int): Float? =
        downloadingEpisodeProgress[BulkDownloadPolicy.episodeContentKey(seasonNumber, episodeNumber)]

    fun isSeasonFullyDownloaded(seasonNumber: Int, seasonEpisodeCount: Int): Boolean =
        seasonEpisodeCount > 0 &&
            (seasonDownloadedCounts[seasonNumber] ?: 0) >= seasonEpisodeCount

    companion object {
        fun of(parentMetaId: String, items: List<DownloadItem>): DownloadedContentInfo {
            val normalizedParentMetaId = parentMetaId.trim()
            val forMeta = items.filter { item -> item.parentMetaId == normalizedParentMetaId }
            val completed = forMeta.filter { it.status == DownloadStatus.Completed }
            val inProgress = forMeta.filter { it.status != DownloadStatus.Completed }

            val completedEpisodeItems = completed.filter { it.seasonNumber != null && it.episodeNumber != null }
            val completedEpisodeKeys = completedEpisodeItems
                .map { item ->
                    BulkDownloadPolicy.episodeContentKey(item.seasonNumber!!, item.episodeNumber!!)
                }
                .toSet()

            val inProgressEpisodeItems = inProgress.filter { it.seasonNumber != null && it.episodeNumber != null }
            // Only surface episodes that are not already completed under a separate download.
            val downloadingEpisodeProgress = inProgressEpisodeItems
                .associate { item ->
                    BulkDownloadPolicy.episodeContentKey(item.seasonNumber!!, item.episodeNumber!!) to
                        item.progressFraction
                }
                .filterKeys { it !in completedEpisodeKeys }

            val movieInProgress = inProgress.firstOrNull { it.seasonNumber == null && it.episodeNumber == null }
            val movieDownloaded = completed.any { it.seasonNumber == null && it.episodeNumber == null }

            return DownloadedContentInfo(
                movieDownloaded = movieDownloaded,
                downloadedEpisodeKeys = completedEpisodeKeys,
                seasonDownloadedCounts = completedEpisodeItems
                    .groupBy { it.seasonNumber!! }
                    .mapValues { (_, group) -> group.size },
                movieDownloadProgress = if (!movieDownloaded && movieInProgress != null) {
                    movieInProgress.progressFraction
                } else {
                    null
                },
                downloadingEpisodeProgress = downloadingEpisodeProgress,
                seasonDownloadingCounts = inProgressEpisodeItems
                    .filter {
                        BulkDownloadPolicy.episodeContentKey(it.seasonNumber!!, it.episodeNumber!!) in
                            downloadingEpisodeProgress
                    }
                    .groupBy { it.seasonNumber!! }
                    .mapValues { (_, group) -> group.size },
            )
        }

        /** Seasons whose every episode has a completed download. */
        fun fullyDownloadedSeasons(
            seasonEpisodeTotals: Map<Int, Int>,
            seasonDownloadedCounts: Map<Int, Int>,
        ): Set<Int> =
            seasonEpisodeTotals
                .mapNotNull { (season, total) ->
                    val downloaded = seasonDownloadedCounts[season] ?: 0
                    if (total > 0 && downloaded >= total) season else null
                }
                .toSet()
    }
}
