package com.nuvio.app.features.downloads

/**
 * Derived "what is available offline" information for a single meta item's downloads, used to
 * decorate the details screen (episode cards, season chips, hero badge) with downloaded marks.
 */
data class DownloadedContentInfo(
    val movieDownloaded: Boolean = false,
    /** Keys of completed episode downloads, in [BulkDownloadPolicy.episodeContentKey] format ("s|e"). */
    val downloadedEpisodeKeys: Set<String> = emptySet(),
    /** Completed download counts per season. */
    val seasonDownloadedCounts: Map<Int, Int> = emptyMap(),
) {
    val hasAnyDownloadedContent: Boolean
        get() = movieDownloaded || downloadedEpisodeKeys.isNotEmpty()

    fun isEpisodeDownloaded(seasonNumber: Int, episodeNumber: Int): Boolean =
        "${BulkDownloadPolicy.episodeContentKey(seasonNumber, episodeNumber)}" in
            downloadedEpisodeKeys

    fun isSeasonFullyDownloaded(seasonNumber: Int, seasonEpisodeCount: Int): Boolean =
        seasonEpisodeCount > 0 &&
            (seasonDownloadedCounts[seasonNumber] ?: 0) >= seasonEpisodeCount

    companion object {
        fun of(parentMetaId: String, items: List<DownloadItem>): DownloadedContentInfo {
            val normalizedParentMetaId = parentMetaId.trim()
            val completed = items.filter { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.status == DownloadStatus.Completed
            }
            val episodeItems = completed.filter { it.seasonNumber != null && it.episodeNumber != null }
            return DownloadedContentInfo(
                movieDownloaded = completed.any { it.seasonNumber == null && it.episodeNumber == null },
                downloadedEpisodeKeys = episodeItems
                    .map { item ->
                        BulkDownloadPolicy.episodeContentKey(item.seasonNumber!!, item.episodeNumber!!)
                    }
                    .toSet(),
                seasonDownloadedCounts = episodeItems
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
