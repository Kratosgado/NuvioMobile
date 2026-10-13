package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs "download this season" / "download all seasons" requests in two phases:
 *
 * 1. **Probe** — [probeSeriesResolutions] fetches stream candidates headlessly
 *    ([BulkStreamFetcher]) for every eligible episode, classifies each candidate's resolution and
 *    groups the available resolutions so the user can pick one ([BulkDownloadsUiState.pendingChoice]).
 * 2. **Download** — [startSeriesDownloadAtResolution] picks the best downloadable stream matching
 *    the chosen resolution for each episode (falling back to the next-best resolution when an
 *    episode has nothing at the chosen one) and enqueues it into [DownloadsRepository], pacing the
 *    batch so only a couple of files download concurrently.
 */
object BulkDownloadsRepository {
    private val log = Logger.withTag("BulkDownloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cap per-episode stream resolution attempts to keep the batch moving. */
    private const val BULK_MAX_CANDIDATES = 8
    private const val BULK_MAX_ACTIVE_DOWNLOADS = 2
    private const val BULK_THROTTLE_INTERVAL_MS = 1_000L
    private const val BULK_FINISHED_CLEAR_DELAY_MS = 12_000L

    private val _uiState = MutableStateFlow(BulkDownloadsUiState())
    val uiState: StateFlow<BulkDownloadsUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var nextBatchId = 0L

    /**
     * Phase 1. Fetches stream candidates for every eligible episode of [requestedEpisodes] and,
     * when finished, publishes a [BulkDownloadResolutionChoice] via [uiState] for the user to pick
     * a resolution from. Episodes that already have an active or completed download are skipped.
     *
     * No download is started here; call [startSeriesDownloadAtResolution] once the user picks.
     */
    fun probeSeriesResolutions(
        meta: MetaDetails,
        requestedEpisodes: List<MetaVideo>,
        scopeLabel: String,
    ) {
        activeJob?.cancel()
        activeJob = null
        _uiState.value = BulkDownloadsUiState()
        DownloadsRepository.ensureLoaded()

        val queue = BulkDownloadPolicy.buildQueue(requestedEpisodes) { season, episode ->
            DownloadsRepository.statusForEpisode(
                parentMetaId = meta.id,
                seasonNumber = season,
                episodeNumber = episode,
            )
        }
        if (queue.isEmpty()) {
            log.d { "Bulk download: nothing to probe for ${meta.name}" }
            return
        }

        val probe = BulkDownloadProbe(
            parentMetaId = meta.id,
            title = meta.name,
            scopeLabel = scopeLabel,
            totalEpisodes = queue.size,
        )
        _uiState.value = BulkDownloadsUiState(activeProbe = probe)
        activeJob = scope.launch {
            runProbe(meta = meta, queue = queue, scopeLabel = scopeLabel)
        }
    }

    /**
     * Phase 2. Starts downloading the previously probed episodes ([BulkDownloadsUiState.pendingChoice])
     * at [resolution], choosing the best downloadable stream per episode and falling back to the
     * next-best resolution when an episode has no stream at the chosen one.
     */
    fun startSeriesDownloadAtResolution(resolution: DebridStreamResolution) {
        val choice = _uiState.value.pendingChoice ?: return
        activeJob?.cancel()
        activeJob = null

        val meta = choice.meta
        val batchId = ++nextBatchId
        val batch = BulkDownloadBatch(
            id = batchId,
            parentMetaId = meta.id,
            title = meta.name,
            scopeLabel = choice.scopeLabel,
            totalEpisodes = choice.totalEpisodes,
            startedAtEpochMs = DownloadsClock.nowEpochMs(),
        )
        _uiState.value = BulkDownloadsUiState(activeBatch = batch)
        activeJob = scope.launch {
            runBatch(choice = choice, resolution = resolution, batch = batch)
        }
    }

    /** Dismisses the pending resolution picker without downloading anything. */
    fun dismissPendingChoice() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { it.copy(activeProbe = null, pendingChoice = null) }
    }

    /** Cancels whichever phase is running (probe or active batch). */
    fun cancelActiveBatch() {
        activeJob?.cancel()
        activeJob = null
    }

    fun isDownloading(parentMetaId: String): Boolean {
        val id = parentMetaId.trim()
        val state = _uiState.value
        return state.activeBatch?.parentMetaId == id ||
            state.activeProbe?.parentMetaId == id ||
            state.pendingChoice?.meta?.id?.trim() == id
    }

    private suspend fun runProbe(
        meta: MetaDetails,
        queue: List<MetaVideo>,
        scopeLabel: String,
    ) {
        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val autoPlayMode = resolveAutoPlayMode(playerSettings)

        val candidatesByEpisode = linkedMapOf<String, List<StreamItem>>()
        try {
            for (episode in queue) {
                currentCoroutineContext().ensureActive()

                val seasonNumber = episode.season ?: continue
                val episodeNumber = episode.episode ?: continue
                val episodeLabel = episodeQueueLabel(seasonNumber, episodeNumber)
                updateActiveProbe { it.copy(currentEpisodeLabel = episodeLabel) }

                val videoId = buildPlaybackVideoId(
                    parentMetaId = meta.id,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    fallbackVideoId = episode.id,
                )
                val key = BulkDownloadPolicy.episodeContentKey(seasonNumber, episodeNumber)
                try {
                    val fetched = BulkStreamFetcher.fetchStreams(
                        type = meta.type,
                        videoId = videoId,
                        season = seasonNumber,
                        episode = episodeNumber,
                    )
                    val autoPlayEvaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
                        streams = fetched.streams,
                        mode = autoPlayMode,
                        regexPattern = playerSettings.streamAutoPlayRegex,
                        source = playerSettings.streamAutoPlaySource,
                        installedAddonNames = fetched.installedAddonOrder.toSet(),
                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                        debridEnabled = debridSettings.canResolvePlayableLinks,
                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                    )
                    val candidates = BulkDownloadPolicy.orderCandidates(
                        streams = fetched.streams,
                        installedAddonOrder = fetched.installedAddonOrder,
                        autoplayEvaluation = autoPlayEvaluation,
                    )
                        // Only keep candidates that can plausibly become a download: a supported
                        // direct URL now, or a debrid stream we can resolve to one later. Keep *all*
                        // of them (no cap here) so every available resolution is represented in the
                        // picker; the per-episode attempt cap is applied later at download time.
                        .filter { it.isLikelyDownloadable() }

                    if (candidates.isNotEmpty()) {
                        candidatesByEpisode[key] = candidates
                    } else {
                        log.d { "Bulk download: no downloadable candidate while probing $videoId" }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error) { "Bulk download: probe failed for $videoId" }
                }

                updateActiveProbe { it.copy(probedEpisodes = it.probedEpisodes + 1, currentEpisodeLabel = null) }
            }

            val options = BulkDownloadPolicy.groupResolutions(candidatesByEpisode)
            val probedEpisodes = queue.filter { episode ->
                val season = episode.season ?: return@filter false
                val number = episode.episode ?: return@filter false
                BulkDownloadPolicy.episodeContentKey(season, number) in candidatesByEpisode
            }
            publishChoice(
                choice = BulkDownloadResolutionChoice(
                    meta = meta,
                    scopeLabel = scopeLabel,
                    episodes = probedEpisodes,
                    candidatesByEpisode = candidatesByEpisode,
                    options = options,
                ),
            )
        } catch (error: CancellationException) {
            _uiState.update { it.copy(activeProbe = null) }
            throw error
        }
    }

    private fun publishChoice(choice: BulkDownloadResolutionChoice) {
        _uiState.update { state ->
            state.copy(
                activeProbe = null,
                // Nothing to pick (no downloadable streams found): clear everything.
                pendingChoice = choice.takeIf { it.hasOptions },
            )
        }
    }

    private suspend fun runBatch(
        choice: BulkDownloadResolutionChoice,
        resolution: DebridStreamResolution,
        batch: BulkDownloadBatch,
    ) {
        val meta = choice.meta
        try {
            for (episode in choice.episodes) {
                currentCoroutineContext().ensureActive()

                val seasonNumber = episode.season ?: continue
                val episodeNumber = episode.episode ?: continue
                val episodeLabel = episodeQueueLabel(seasonNumber, episodeNumber)
                updateActiveBatch { it.copy(currentEpisodeLabel = episodeLabel) }

                val videoId = buildPlaybackVideoId(
                    parentMetaId = meta.id,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    fallbackVideoId = episode.id,
                )
                val key = BulkDownloadPolicy.episodeContentKey(seasonNumber, episodeNumber)
                val candidates = choice.candidatesByEpisode[key].orEmpty()
                try {
                    // Prefer the chosen resolution; order remaining candidates for fallback so a
                    // debrid resolve failure can still try the next-best stream for the episode.
                    val orderedForDownload = orderForDownload(candidates, resolution)
                    val stream = pickDownloadableStream(
                        candidates = orderedForDownload,
                        season = seasonNumber,
                        episode = episodeNumber,
                    )
                    if (stream == null) {
                        log.d { "Bulk download: no downloadable stream for $videoId" }
                        updateActiveBatch { state ->
                            state.copy(
                                processedCount = state.processedCount + 1,
                                skippedCount = state.skippedCount + 1,
                                currentEpisodeLabel = null,
                            )
                        }
                        continue
                    }

                    val result = DownloadsRepository.enqueueFromStream(
                        contentType = meta.type,
                        videoId = videoId,
                        parentMetaId = meta.id,
                        parentMetaType = meta.type,
                        title = meta.name,
                        logo = meta.logo,
                        poster = meta.poster,
                        background = meta.background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episode.title,
                        episodeThumbnail = episode.thumbnail,
                        stream = stream,
                    )
                    if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                        updateActiveBatch { state ->
                            state.copy(
                                processedCount = state.processedCount + 1,
                                enqueuedCount = state.enqueuedCount + 1,
                                currentEpisodeLabel = null,
                            )
                        }
                    } else {
                        log.d { "Bulk download: enqueue rejected ($result) for $videoId" }
                        updateActiveBatch { state ->
                            state.copy(
                                processedCount = state.processedCount + 1,
                                failedCount = state.failedCount + 1,
                                currentEpisodeLabel = null,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error) { "Bulk download: failed to resolve $videoId" }
                    updateActiveBatch { state ->
                        state.copy(
                            processedCount = state.processedCount + 1,
                            failedCount = state.failedCount + 1,
                            currentEpisodeLabel = null,
                        )
                    }
                }

                awaitDownloadQuota()
            }
            finishBatch(wasCancelled = false)
        } catch (error: CancellationException) {
            finishBatch(wasCancelled = true)
            throw error
        }
    }

    /**
     * Orders an episode's candidates so streams at the chosen [resolution] come first (best-first),
     * then the remaining candidates as fallbacks by proximity, so a failed resolve can retry
     * another stream at the same resolution before dropping to a different one.
     */
    private fun orderForDownload(
        candidates: List<StreamItem>,
        resolution: DebridStreamResolution,
    ): List<StreamItem> = BulkDownloadPolicy.orderForResolution(candidates, resolution)

    /** True when a candidate can plausibly produce a download (direct URL now or resolvable later). */
    private fun StreamItem.isLikelyDownloadable(): Boolean {
        val directUrl = playableDirectUrl?.trim()
        if (!directUrl.isNullOrBlank() && directUrl.isSupportedDownloadUrl()) return true
        return DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this)
    }

    private fun resolveAutoPlayMode(
        playerSettings: PlayerSettingsUiState,
    ): StreamAutoPlayMode =
        // Honor the user's autoplay preferences for stream picking; fall back to "first stream"
        // when no prediction is meaningful, since there is no one to pick manually in a batch.
        when {
            playerSettings.streamAutoPlayMode != StreamAutoPlayMode.REGEX_MATCH ->
                playerSettings.streamAutoPlayMode.takeIf { it != StreamAutoPlayMode.MANUAL }
                    ?: StreamAutoPlayMode.FIRST_STREAM

            StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex) ->
                StreamAutoPlayMode.REGEX_MATCH

            else -> StreamAutoPlayMode.FIRST_STREAM
        }

    private suspend fun pickDownloadableStream(
        candidates: List<StreamItem>,
        season: Int?,
        episode: Int?,
    ): StreamItem? {
        for (candidate in candidates.take(BULK_MAX_CANDIDATES)) {
            val directUrl = candidate.playableDirectUrl?.trim()
            if (!directUrl.isNullOrBlank() && directUrl.isSupportedDownloadUrl()) {
                return candidate
            }
            if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate)) {
                continue
            }
            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = candidate,
                season = season,
                episode = episode,
            )
            if (resolved is DirectDebridPlayableResult.Success) {
                val resolvedUrl = resolved.stream.playableDirectUrl?.trim()
                if (!resolvedUrl.isNullOrBlank() && resolvedUrl.isSupportedDownloadUrl()) {
                    return resolved.stream
                }
            }
        }
        return null
    }

    /** Keeps the number of simultaneously active downloads bounded. */
    private suspend fun awaitDownloadQuota() {
        while (DownloadsRepository.activeDownloadCount() >= BULK_MAX_ACTIVE_DOWNLOADS) {
            currentCoroutineContext().ensureActive()
            delay(BULK_THROTTLE_INTERVAL_MS)
        }
    }

    private fun updateActiveProbe(transform: (BulkDownloadProbe) -> BulkDownloadProbe) {
        _uiState.update { state ->
            state.activeProbe?.let { current ->
                state.copy(activeProbe = transform(current))
            } ?: state
        }
    }

    private fun updateActiveBatch(transform: (BulkDownloadBatch) -> BulkDownloadBatch) {
        _uiState.update { state ->
            state.activeBatch?.let { current ->
                state.copy(activeBatch = transform(current))
            } ?: state
        }
    }

    private fun finishBatch(wasCancelled: Boolean) {
        _uiState.update { state ->
            state.copy(
                activeBatch = null,
                finishedBatch = state.activeBatch
                    ?.copy(isFinished = true, wasCancelled = wasCancelled, currentEpisodeLabel = null),
            )
        }
        cleanupScope()
    }

    private fun cleanupScope() {
        scope.launch {
            delay(BULK_FINISHED_CLEAR_DELAY_MS)
            _uiState.update { current ->
                if (current.activeBatch == null) {
                    current.copy(finishedBatch = null)
                } else {
                    current
                }
            }
        }
    }

    private fun episodeQueueLabel(seasonNumber: Int, episodeNumber: Int): String =
        buildString {
            append('S')
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
        }
}
