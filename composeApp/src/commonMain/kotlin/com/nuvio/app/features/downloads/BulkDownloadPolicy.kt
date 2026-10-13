package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayEvaluation
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.sortedForGroupedDisplay

/**
 * Pure queueing/selection policy for bulk ("download season", "download all seasons") downloads.
 * Kept free of networking and UI state so it can be unit tested.
 */
internal object BulkDownloadPolicy {

    /**
     * Builds the ordered, de-duplicated episode queue for a bulk download run. Episodes that
     * already have an active ([DownloadStatus.Downloading]) or completed
     * ([DownloadStatus.Completed]) download are skipped; paused and failed ones stay queued.
     */
    fun buildQueue(
        episodes: List<MetaVideo>,
        statusForEpisode: (season: Int, episode: Int) -> DownloadStatus? = { _, _ -> null },
    ): List<MetaVideo> =
        episodes
            .filter { it.season != null && it.episode != null }
            .groupBy { it.season to it.episode }
            .map { (_, group) -> group.first() }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .filter { episode ->
                when (statusForEpisode(episode.season!!, episode.episode!!)) {
                    DownloadStatus.Downloading, DownloadStatus.Completed -> false
                    else -> true
                }
            }

    /** Number of episodes a bulk run would actually enqueue. */
    fun countBulkCandidates(
        episodes: List<MetaVideo>,
        statusForEpisode: (season: Int, episode: Int) -> DownloadStatus? = { _, _ -> null },
    ): Int = buildQueue(episodes, statusForEpisode).size

    /** Stable key for a logical episode download, shared by the policy and the badge helpers. */
    fun episodeContentKey(seasonNumber: Int, episodeNumber: Int): String =
        "$seasonNumber|$episodeNumber"

    /** Key used for movie downloads (no season/episode). */
    const val MOVIE_CONTENT_KEY = "movie"

    /**
     * Orders download candidates: autoplay-eligible streams first (honoring the user's autoplay
     * preferences), then all remaining streams grouped debrid-first, then by installed addon
     * order, with plugin sources last.
     */
    fun orderCandidates(
        streams: List<StreamItem>,
        installedAddonOrder: List<String>,
        autoplayEvaluation: StreamAutoPlayEvaluation,
    ): List<StreamItem> {
        if (streams.isEmpty()) return emptyList()
        val autoplayPicks = autoplayEvaluation.readyStreams.filter { ready ->
            streams.any { it == ready }
        }
        val remaining = streams.filter { stream -> autoplayPicks.none { it == stream } }
        if (remaining.isEmpty()) return autoplayPicks

        val groups = remaining
            .groupBy { it.addonId }
            .map { (addonId, groupStreams) ->
                AddonStreamGroup(
                    addonName = groupStreams.first().addonName,
                    addonId = addonId,
                    streams = groupStreams.sortedForGroupedDisplay(),
                )
            }
        return autoplayPicks + StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder)
            .flatMap { it.streams }
    }

    /**
     * Groups each episode's downloadable candidates by resolution and returns one option per
     * resolution that at least one episode can satisfy, sorted highest-to-lowest quality
     * ([BulkDownloadResolution.descendingOrder]). [episodeCount] is the number of distinct
     * episodes that have at least one candidate at that resolution.
     *
     * [candidatesByEpisode] is keyed by [episodeContentKey]; each value is the ordered candidate
     * list for that episode (best first, as produced by [orderCandidates]).
     */
    fun groupResolutions(
        candidatesByEpisode: Map<String, List<StreamItem>>,
    ): List<BulkDownloadResolutionOption> {
        if (candidatesByEpisode.isEmpty()) return emptyList()
        val countByResolution = mutableMapOf<DebridStreamResolution, Int>()
        candidatesByEpisode.values.forEach { candidates ->
            val resolutions = candidates.mapTo(mutableSetOf()) { BulkDownloadResolution.of(it) }
            resolutions.forEach { resolution ->
                countByResolution[resolution] = (countByResolution[resolution] ?: 0) + 1
            }
        }
        return BulkDownloadResolution.descendingOrder
            .filter { countByResolution.containsKey(it) }
            .map { resolution ->
                BulkDownloadResolutionOption(
                    resolution = resolution,
                    label = BulkDownloadResolution.labelOf(resolution),
                    episodeCount = countByResolution.getValue(resolution),
                )
            }
    }

    /**
     * Picks the best downloadable candidate for a single episode at [target] resolution. When the
     * episode has no candidate at exactly [target], falls back to the next-best resolution:
     * resolutions are ranked by distance from [target] in [BulkDownloadResolution.descendingOrder],
     * breaking ties toward higher quality. Returns null only when [candidates] is empty.
     *
     * [candidates] must be pre-ordered best-first; the first candidate at the chosen resolution is
     * returned so autoplay/debrid preferences are preserved within a resolution.
     */
    fun bestStreamAtResolution(
        candidates: List<StreamItem>,
        target: DebridStreamResolution,
    ): StreamItem? {
        if (candidates.isEmpty()) return null
        val byResolution = candidates.groupBy { BulkDownloadResolution.of(it) }
        byResolution[target]?.firstOrNull()?.let { return it }

        val targetRank = BulkDownloadResolution.rankOf(target)
        val fallbackResolution = byResolution.keys
            .minWithOrNull(
                compareBy<DebridStreamResolution>(
                    // Closest to the requested resolution first.
                    { kotlin.math.abs(BulkDownloadResolution.rankOf(it) - targetRank) },
                    // Tie-break toward higher quality (lower rank index).
                    { BulkDownloadResolution.rankOf(it) },
                ),
            )
            ?: return candidates.first()
        return byResolution[fallbackResolution]?.firstOrNull() ?: candidates.first()
    }

    /**
     * Orders an episode's candidates for download at [target] resolution: every candidate at the
     * chosen resolution first (preserving their best-first order), then the remaining candidates
     * as fallbacks ordered by proximity to [target]. This lets the downloader retry other streams
     * at the chosen resolution before falling back to a different one when a resolve fails.
     */
    fun orderForResolution(
        candidates: List<StreamItem>,
        target: DebridStreamResolution,
    ): List<StreamItem> {
        if (candidates.isEmpty()) return emptyList()
        val targetRank = BulkDownloadResolution.rankOf(target)
        return candidates.sortedBy { candidate ->
            val rank = BulkDownloadResolution.rankOf(BulkDownloadResolution.of(candidate))
            if (rank == targetRank) -1 else kotlin.math.abs(rank - targetRank)
        }
    }
}
