package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamAutoPlayEvaluation
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkDownloadPolicyTest {

    private fun episode(
        season: Int,
        number: Int,
        id: String = "s${season}e$number",
    ): MetaVideo = MetaVideo(
        id = id,
        title = "Episode $number",
        season = season,
        episode = number,
    )

    private fun downloadedItem(
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        status: DownloadStatus,
    ): DownloadItem = DownloadItem(
        id = "id-$parentMetaId-$season-$episode",
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "video-$season-$episode",
        title = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "provider",
        sourceUrl = "https://example.com/file.mp4",
        fileName = "file.mp4",
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @Test
    fun buildQueueSortsAndDeduplicatesEpisodes() {
        val queue = BulkDownloadPolicy.buildQueue(
            listOf(
                episode(2, 1),
                episode(1, 3),
                episode(1, 1),
                episode(1, 1, id = "duplicate"),
                episode(season = 0, number = 2),
            ),
        )
        assertEquals(
            listOf(
                0 to 2,
                1 to 1,
                1 to 3,
                2 to 1,
            ),
            queue.map { it.season to it.episode },
        )
    }

    @Test
    fun buildQueueSkipsOnlyActiveOrCompletedDownloads() {
        val existing = listOf(
            downloadedItem("tmdb:1", 1, 1, DownloadStatus.Completed),
            downloadedItem("tmdb:1", 2, 1, DownloadStatus.Downloading),
            downloadedItem("tmdb:1", 3, 1, DownloadStatus.Paused),
            downloadedItem("tmdb:1", 4, 1, DownloadStatus.Failed),
            downloadedItem("tmdb:2", 5, 1, DownloadStatus.Completed),
        )
        val queue = BulkDownloadPolicy.buildQueue(
            episodes = (1..4).flatMap { season -> listOf(episode(season, 1)) },
            statusForEpisode = { season, episode ->
                existing.firstOrNull { item ->
                    item.parentMetaId == "tmdb:1" &&
                        item.seasonNumber == season &&
                        item.episodeNumber == episode
                }?.status
            },
        )
        // Seasons 1 and 2 have a Completed / Downloading download and are skipped;
        // Paused and Failed episodes stay queued for a retry.
        assertEquals(listOf(3 to 1, 4 to 1), queue.map { it.season to it.episode })
    }

    @Test
    fun countBulkCandidatesMatchesQueueSize() {
        val episodes = listOf(episode(1, 1), episode(1, 2))
        val candidates = BulkDownloadPolicy.countBulkCandidates(episodes) { season, number ->
            if (season == 1 && number == 2) DownloadStatus.Completed else null
        }
        assertEquals(1, candidates)
    }

    @Test
    fun orderCandidatesPutsAutoplayPicksFirstThenDebridGroups() {
        val addonStream = StreamItem(name = "Addon 1080p", addonName = "Addon", addonId = "addon:a")
        val debridStream = StreamItem(name = "Debrid 4K", addonName = "Debrid", addonId = "debrid:x")
        val pluginStream = StreamItem(name = "Plugin 720p", addonName = "Plugin", addonId = "plugin:1")
        val streams = listOf(addonStream, debridStream, pluginStream)

        val ordered = BulkDownloadPolicy.orderCandidates(
            streams = streams,
            installedAddonOrder = listOf("Addon"),
            autoplayEvaluation = StreamAutoPlayEvaluation(readyStreams = listOf(debridStream, addonStream)),
        )

        assertEquals(listOf(debridStream, addonStream, pluginStream), ordered)
    }

    @Test
    fun orderCandidatesFallsBackToDebridFirstOrdering() {
        val addonStream = StreamItem(name = "Addon 1080p", addonName = "Addon", addonId = "addon:a")
        val debridStream = StreamItem(name = "Debrid 4K", addonName = "Debrid", addonId = "debrid:x")
        val pluginStream = StreamItem(name = "Plugin 720p", addonName = "Plugin", addonId = "plugin:1")
        val streams = listOf(addonStream, pluginStream, debridStream)

        val ordered = BulkDownloadPolicy.orderCandidates(
            streams = streams,
            installedAddonOrder = listOf("Addon"),
            autoplayEvaluation = StreamAutoPlayEvaluation(),
        )

        assertEquals(listOf(debridStream, addonStream, pluginStream), ordered)
    }

    @Test
    fun groupResolutionsCountsEpisodesPerResolutionHighestFirst() {
        val candidatesByEpisode = mapOf(
            "1|1" to listOf(
                StreamItem(name = "S01E01 2160p", addonName = "A", addonId = "a"),
                StreamItem(name = "S01E01 1080p", addonName = "A", addonId = "a"),
            ),
            "1|2" to listOf(
                StreamItem(name = "S01E02 1080p", addonName = "A", addonId = "a"),
                StreamItem(name = "S01E02 720p", addonName = "A", addonId = "a"),
            ),
            "1|3" to listOf(
                StreamItem(name = "S01E03 720p", addonName = "A", addonId = "a"),
            ),
        )

        val options = BulkDownloadPolicy.groupResolutions(candidatesByEpisode)

        assertEquals(
            listOf("2160p" to 1, "1080p" to 2, "720p" to 2),
            options.map { it.label to it.episodeCount },
        )
    }

    @Test
    fun bestStreamAtResolutionPrefersExactMatchInOrder() {
        val exactFirst = StreamItem(name = "Best 1080p", addonName = "A", addonId = "a")
        val exactSecond = StreamItem(name = "Also 1080p", addonName = "A", addonId = "a")
        val other = StreamItem(name = "2160p", addonName = "A", addonId = "a")
        val candidates = listOf(other, exactFirst, exactSecond)

        val chosen = BulkDownloadPolicy.bestStreamAtResolution(
            candidates = candidates,
            target = DebridStreamResolution.P1080,
        )

        assertEquals(exactFirst, chosen)
    }

    @Test
    fun bestStreamAtResolutionFallsBackToClosestResolution() {
        val higher = StreamItem(name = "2160p", addonName = "A", addonId = "a")
        val lower = StreamItem(name = "720p", addonName = "A", addonId = "a")
        // Requested 1080p is absent: 720p (rank distance 1) is closer than 2160p (distance 2).
        val candidates = listOf(higher, lower)

        val chosen = BulkDownloadPolicy.bestStreamAtResolution(
            candidates = candidates,
            target = DebridStreamResolution.P1080,
        )

        assertEquals(lower, chosen)
    }

    @Test
    fun bestStreamAtResolutionBreaksTieTowardHigherQuality() {
        val higher = StreamItem(name = "1080p", addonName = "A", addonId = "a")
        val lower = StreamItem(name = "576p", addonName = "A", addonId = "a")
        // Requested 720p is absent: 1080p and 576p are equidistant (rank distance 1); prefer 1080p.
        val candidates = listOf(lower, higher)

        val chosen = BulkDownloadPolicy.bestStreamAtResolution(
            candidates = candidates,
            target = DebridStreamResolution.P720,
        )

        assertEquals(higher, chosen)
    }

    @Test
    fun bestStreamAtResolutionReturnsNullWhenEmpty() {
        assertEquals(
            null,
            BulkDownloadPolicy.bestStreamAtResolution(emptyList(), DebridStreamResolution.P1080),
        )
    }

    @Test
    fun orderForResolutionPutsChosenResolutionFirstThenFallbackByProximity() {
        val uhd = StreamItem(name = "2160p", addonName = "A", addonId = "a")
        val fhdFirst = StreamItem(name = "1080p one", addonName = "A", addonId = "a")
        val hd = StreamItem(name = "720p", addonName = "A", addonId = "a")
        val fhdSecond = StreamItem(name = "1080p two", addonName = "A", addonId = "a")
        // Deliberately interleaved so we can see grouping + stable order within a resolution.
        val candidates = listOf(uhd, fhdFirst, hd, fhdSecond)

        val ordered = BulkDownloadPolicy.orderForResolution(candidates, DebridStreamResolution.P1080)

        // Both 1080p streams first (original order preserved), then 720p (distance 1) before
        // 2160p (distance 2).
        assertEquals(listOf(fhdFirst, fhdSecond, hd, uhd), ordered)
    }
}

class DownloadedContentInfoTest {

    private fun item(
        parentMetaId: String,
        season: Int?,
        episodeNumber: Int?,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
    ) =
        DownloadItem(
            id = "item-$parentMetaId-$season-$episodeNumber",
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = "video-$season-$episodeNumber",
            title = "Show",
            seasonNumber = season,
            episodeNumber = episodeNumber,
            streamTitle = "stream",
            providerName = "provider",
            sourceUrl = "https://example.com/file.mp4",
            fileName = "file.mp4",
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )

    @Test
    fun tracksOnlyCompletedDownloadsForParent() {
        val info = DownloadedContentInfo.of(
            parentMetaId = "tmdb:1",
            items = listOf(
                item("tmdb:1", 1, 1, DownloadStatus.Completed),
                item("tmdb:1", 1, 2, DownloadStatus.Downloading),
                item("tmdb:1", null, null, DownloadStatus.Completed),
                item("tmdb:2", 1, 1, DownloadStatus.Completed),
            ),
        )
        assertTrue(info.movieDownloaded)
        assertTrue(info.isEpisodeDownloaded(1, 1))
        assertFalse(info.isEpisodeDownloaded(1, 2))
        assertFalse(info.isEpisodeDownloaded(2, 1))
        assertEquals(1, info.seasonDownloadedCounts[1])
    }

    @Test
    fun detectsFullyDownloadedSeasons() {
        val fully = DownloadedContentInfo.fullyDownloadedSeasons(
            seasonEpisodeTotals = mapOf(1 to 2, 2 to 1, 3 to 0),
            seasonDownloadedCounts = mapOf(1 to 2, 2 to 0, 3 to 0),
        )
        assertEquals(setOf(1), fully)
    }

    @Test
    fun tracksInProgressEpisodeAndMovieProgress() {
        val info = DownloadedContentInfo.of(
            parentMetaId = "tmdb:1",
            items = listOf(
                item("tmdb:1", 1, 1, DownloadStatus.Completed),
                item("tmdb:1", 1, 2, DownloadStatus.Downloading, downloadedBytes = 50L, totalBytes = 100L),
                item("tmdb:1", 2, 1, DownloadStatus.Paused, downloadedBytes = 10L, totalBytes = 100L),
            ),
        )
        assertTrue(info.hasAnyInProgressContent)
        assertTrue(info.isEpisodeDownloaded(1, 1))
        // Completed episode is not reported as downloading.
        assertEquals(null, info.episodeDownloadProgress(1, 1))
        assertEquals(0.5f, info.episodeDownloadProgress(1, 2))
        assertEquals(0.1f, info.episodeDownloadProgress(2, 1))
        assertEquals(1, info.seasonDownloadingCounts[1])
        assertEquals(1, info.seasonDownloadingCounts[2])
    }

    @Test
    fun reportsMovieInProgressOnlyWhenNotCompleted() {
        val downloading = DownloadedContentInfo.of(
            parentMetaId = "tmdb:9",
            items = listOf(
                item("tmdb:9", null, null, DownloadStatus.Downloading, downloadedBytes = 25L, totalBytes = 100L),
            ),
        )
        assertEquals(0.25f, downloading.movieDownloadProgress)
        assertFalse(downloading.movieDownloaded)

        val completed = DownloadedContentInfo.of(
            parentMetaId = "tmdb:9",
            items = listOf(
                item("tmdb:9", null, null, DownloadStatus.Completed),
            ),
        )
        assertEquals(null, completed.movieDownloadProgress)
        assertTrue(completed.movieDownloaded)
    }
}

class PosterDownloadStateTest {

    private fun item(
        parentMetaId: String,
        status: DownloadStatus,
        season: Int? = null,
        episodeNumber: Int? = null,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
    ) =
        DownloadItem(
            id = "item-$parentMetaId-$season-$episodeNumber-$status",
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = "video-$season-$episodeNumber",
            title = "Thing",
            seasonNumber = season,
            episodeNumber = episodeNumber,
            streamTitle = "stream",
            providerName = "provider",
            sourceUrl = "https://example.com/file.mp4",
            fileName = "file.mp4",
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )

    @Test
    fun noneWhenNoDownloads() {
        assertEquals(
            PosterDownloadStatus.None,
            PosterDownloadState.of("tmdb:1", emptyList()).status,
        )
    }

    @Test
    fun downloadingTakesPriorityAndAveragesProgress() {
        val state = PosterDownloadState.of(
            metaId = "tmdb:1",
            items = listOf(
                item("tmdb:1", DownloadStatus.Completed, season = 1, episodeNumber = 1),
                item("tmdb:1", DownloadStatus.Downloading, season = 1, episodeNumber = 2, downloadedBytes = 20L, totalBytes = 100L),
                item("tmdb:1", DownloadStatus.Downloading, season = 1, episodeNumber = 3, downloadedBytes = 60L, totalBytes = 100L),
            ),
        )
        assertEquals(PosterDownloadStatus.Downloading, state.status)
        assertEquals(0.4f, state.progress)
    }

    @Test
    fun downloadedWhenOnlyCompleted() {
        val state = PosterDownloadState.of(
            metaId = "tmdb:1",
            items = listOf(item("tmdb:1", DownloadStatus.Completed)),
        )
        assertEquals(PosterDownloadStatus.Downloaded, state.status)
    }

    @Test
    fun buildPosterStatesGroupsByMetaId() {
        val map = PosterDownloadState.buildPosterStates(
            listOf(
                item("tmdb:1", DownloadStatus.Completed),
                item("tmdb:2", DownloadStatus.Downloading, downloadedBytes = 10L, totalBytes = 100L),
            ),
        )
        assertEquals(PosterDownloadStatus.Downloaded, map["tmdb:1"]?.status)
        assertEquals(PosterDownloadStatus.Downloading, map["tmdb:2"]?.status)
        assertEquals(null, map["tmdb:3"])
    }
}
