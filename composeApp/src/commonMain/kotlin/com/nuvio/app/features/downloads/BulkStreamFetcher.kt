package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Headless stream fetcher used by bulk ("download season") downloads. Collects the raw stream
 * candidates for a single video — installed addons + media-server sources + enabled plugin
 * scrapers — without touching any UI state.
 */
internal object BulkStreamFetcher {
    private val log = Logger.withTag("BulkStreamFetch")

    data class FetchedStreams(
        val streams: List<StreamItem>,
        /** Display names of installed addons supporting this video, in install order. */
        val installedAddonOrder: List<String>,
    )

    suspend fun fetchStreams(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): FetchedStreams = coroutineScope {
        val isNativeServerRequest = ServerStreams.isNativeRequest(videoId)
        val installedAddons = if (isNativeServerRequest) {
            emptyList()
        } else {
            AddonRepository.uiState.value.addons.enabledAddons()
        }
        val serverSources = ServerStreams.sources(type, videoId, season, episode, forceRefresh = false)
        val providerGroups = if (AppFeaturePolicy.pluginsEnabled && !isNativeServerRequest) {
            PluginRepository.initialize()
            val pluginUiState = PluginRepository.uiState.value
            PluginRepository.getEnabledScrapersForType(type).toPluginProviderGroups(
                repositories = pluginUiState.repositories,
                groupByRepository = pluginUiState.groupStreamsByRepository,
            )
        } else {
            emptyList()
        }

        val streamAddons = installedAddons.mapNotNull { addon ->
            val manifest = addon.manifest
            if (manifest == null || !manifest.supportsStream(type, videoId)) {
                return@mapNotNull null
            }
            addon to manifest
        }

        val addonJobs = streamAddons.map { (addon, manifest) ->
            async {
                val displayName = addon.displayTitle.ifBlank { manifest.name }
                runCatchingUnlessCancelled {
                    val payload = fetchAddonResponseText(
                        url = buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl,
                            resource = "stream",
                            type = type,
                            id = videoId,
                        ),
                        forceRefresh = false,
                    )
                    StreamParser.parse(
                        payload = payload,
                        addonName = displayName,
                        addonId = addon.streamAddonInstanceId(manifest.id),
                        addonLogo = manifest.logoUrl,
                    )
                }.onFailure { error ->
                    log.w(error) { "Bulk download: stream fetch failed for $displayName ($videoId)" }
                }.getOrDefault(emptyList())
            }
        }

        val serverJobs = serverSources.map { source ->
            async {
                runCatchingUnlessCancelled { source.load().streams }
                    .onFailure { error ->
                        log.w(error) { "Bulk download: media-server source failed for $videoId" }
                    }
                    .getOrDefault(emptyList())
            }
        }

        val scraperJobs = providerGroups.flatMap { providerGroup ->
            providerGroup.scrapers.map { scraper ->
                async {
                    PluginRepository.executeScraper(
                        scraper = scraper,
                        tmdbId = pluginContentId(
                            videoId = videoId,
                            season = season,
                            episode = episode,
                        ),
                        mediaType = type,
                        season = season,
                        episode = episode,
                    ).fold(
                        onSuccess = { results ->
                            results.map { result ->
                                result.toStreamItem(
                                    scraper = scraper,
                                    addonName = providerGroup.addonName,
                                    addonId = providerGroup.addonId,
                                    includeScraperNameInSubtitle = false,
                                )
                            }
                        },
                        onFailure = { error ->
                            log.w(error) { "Bulk download: plugin scraper failed: ${scraper.name}" }
                            emptyList()
                        },
                    )
                }
            }
        }

        val streams = (addonJobs + serverJobs + scraperJobs)
            .awaitAll()
            .flatten()
            // Media-server targets are streamed from the server, not downloadable files.
            .filter { it.serverTarget == null }

        FetchedStreams(
            streams = streams,
            installedAddonOrder = streamAddons.map { (addon, manifest) ->
                addon.displayTitle.ifBlank { manifest.name }
            },
        )
    }
}