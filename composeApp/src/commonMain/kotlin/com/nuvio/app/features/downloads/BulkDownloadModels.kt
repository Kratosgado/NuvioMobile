package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem

/**
 * A single bulk download run ("download this season" / "download all seasons") driven by
 * [BulkDownloadsRepository].
 */
data class BulkDownloadBatch(
    val id: Long,
    val parentMetaId: String,
    val title: String,
    /** Localized label of what is being downloaded, e.g. "Season 1" or "All seasons". */
    val scopeLabel: String,
    val totalEpisodes: Int,
    val startedAtEpochMs: Long,
    /** Episodes already handled (enqueued, skipped or failed). */
    val processedCount: Int = 0,
    val enqueuedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    /** Short label of the episode currently being resolved, e.g. "S01E04". */
    val currentEpisodeLabel: String? = null,
    val isFinished: Boolean = false,
    val wasCancelled: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalEpisodes > 0) {
            (processedCount.toFloat() / totalEpisodes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Progress of the probe phase that fetches every queued episode's streams so the user can pick a
 * resolution before anything downloads.
 */
data class BulkDownloadProbe(
    val parentMetaId: String,
    val title: String,
    val scopeLabel: String,
    val totalEpisodes: Int,
    val probedEpisodes: Int = 0,
    /** Short label of the episode currently being probed, e.g. "S01E04". */
    val currentEpisodeLabel: String? = null,
    val wasCancelled: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalEpisodes > 0) {
            (probedEpisodes.toFloat() / totalEpisodes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * One selectable resolution in the picker, with the number of queued episodes that have at least
 * one downloadable stream at this resolution.
 */
data class BulkDownloadResolutionOption(
    val resolution: DebridStreamResolution,
    val label: String,
    val episodeCount: Int,
)

/**
 * Result of probing all queued episodes. Carries everything the download phase needs so streams
 * are fetched only once: the meta, the ordered downloadable candidates per episode (keyed by the
 * logical episode key), and the resolution options to show in the picker.
 */
data class BulkDownloadResolutionChoice(
    val meta: MetaDetails,
    val scopeLabel: String,
    /** Logical episode key -> the episode, in queue order. */
    val episodes: List<MetaVideo>,
    /** Logical episode key -> ordered downloadable stream candidates found during probing. */
    val candidatesByEpisode: Map<String, List<StreamItem>>,
    val options: List<BulkDownloadResolutionOption>,
) {
    val totalEpisodes: Int get() = episodes.size
    val hasOptions: Boolean get() = options.isNotEmpty()
}

data class BulkDownloadsUiState(
    val activeBatch: BulkDownloadBatch? = null,
    /** Active probe phase (fetching streams before the resolution picker is shown). */
    val activeProbe: BulkDownloadProbe? = null,
    /** Ready-to-pick resolution options once probing finishes; null while probing or idle. */
    val pendingChoice: BulkDownloadResolutionChoice? = null,
    /** Last finished (or cancelled) batch, kept briefly so the UI can show the outcome. */
    val finishedBatch: BulkDownloadBatch? = null,
)
