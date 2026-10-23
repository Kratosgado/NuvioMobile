package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bulk_banner_cancel
import nuvio.composeapp.generated.resources.bulk_banner_downloading
import nuvio.composeapp.generated.resources.bulk_banner_done
import nuvio.composeapp.generated.resources.bulk_banner_probing
import nuvio.composeapp.generated.resources.download_badge_completed
import nuvio.composeapp.generated.resources.download_badge_downloading
import org.jetbrains.compose.resources.stringResource

/**
 * Floating progress banner for the active bulk download run, plus the finished/cancelled
 * result shown briefly. Render nothing when there is nothing to show.
 */
@Composable
fun BulkDownloadStatusBanner(
    state: BulkDownloadsUiState,
    modifier: Modifier = Modifier,
) {
    var dismissedFinishedBatchId by remember { mutableStateOf<Long?>(null) }
    val finishedBatch = state.finishedBatch
        ?.takeIf { it.id != dismissedFinishedBatchId }

    // While the pending resolution picker is shown, let the picker own the screen.
    if (state.pendingChoice != null) return

    val activeProbe = state.activeProbe
    if (activeProbe != null) {
        BulkDownloadProbeBanner(probe = activeProbe, modifier = modifier)
        return
    }

    val batch = state.activeBatch ?: finishedBatch ?: return

    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = nuvioSafeBottomPadding(16.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f))
                .clickable(
                    enabled = batch.isFinished,
                    onClick = { dismissedFinishedBatchId = batch.id },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (batch.isFinished) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (batch.isFinished) {
                            stringResource(
                                Res.string.bulk_banner_done,
                                batch.scopeLabel,
                                batch.enqueuedCount,
                                batch.skippedCount,
                                batch.failedCount,
                            )
                        } else {
                            stringResource(
                                Res.string.bulk_banner_downloading,
                                batch.scopeLabel,
                                batch.processedCount,
                                batch.totalEpisodes,
                            )
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!batch.isFinished && !batch.currentEpisodeLabel.isNullOrBlank()) {
                        Text(
                            text = batch.currentEpisodeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.bulk_banner_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (batch.isFinished) {
                                dismissedFinishedBatchId = batch.id
                            } else {
                                BulkDownloadsRepository.cancelActiveBatch()
                            }
                        }
                        .padding(2.dp),
                )
            }
            if (!batch.isFinished) {
                NuvioProgressBar(
                    progress = batch.progressFraction,
                    modifier = Modifier.fillMaxWidth(),
                    height = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.22f),
                    fillColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Floating progress banner for the probe phase ("Finding streams…") shown before the resolution
 * picker appears. Lets the user cancel the probe.
 */
@Composable
private fun BulkDownloadProbeBanner(
    probe: BulkDownloadProbe,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = nuvioSafeBottomPadding(16.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.bulk_banner_probing,
                            probe.scopeLabel,
                            probe.probedEpisodes,
                            probe.totalEpisodes,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!probe.currentEpisodeLabel.isNullOrBlank()) {
                        Text(
                            text = probe.currentEpisodeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.bulk_banner_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { BulkDownloadsRepository.cancelActiveBatch() }
                        .padding(2.dp),
                )
            }
            NuvioProgressBar(
                progress = probe.progressFraction,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                trackColor = Color.White.copy(alpha = 0.22f),
                fillColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Watches [BulkDownloadsRepository] and renders the poll banner when relevant.
 */
@Composable
fun BulkDownloadStatusBannerHost(
    modifier: Modifier = Modifier,
) {
    val state by BulkDownloadsRepository.uiState.collectAsStateWithLifecycle()
    BulkDownloadStatusBanner(
        state = state,
        modifier = modifier,
    )
}

/**
 * Small circular "downloaded" mark matching [com.nuvio.app.core.ui.NuvioWatchedBadge] size and shape.
 */
@Composable
fun NuvioDownloadedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(Res.string.download_badge_completed),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

/**
 * Small circular "downloading" mark: a determinate progress ring wrapped around a download glyph,
 * matching [NuvioDownloadedBadge] footprint so the two can swap in place.
 *
 * @param progress fraction in [0f, 1f]; values outside the range are coerced.
 */
@Composable
fun NuvioDownloadingBadge(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { clamped },
            modifier = Modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeWidth = 2.dp,
        )
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(Res.string.download_badge_downloading),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
