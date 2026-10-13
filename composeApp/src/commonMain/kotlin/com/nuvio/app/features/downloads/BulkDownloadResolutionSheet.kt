package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.debrid.DebridStreamResolution
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bulk_resolution_option
import nuvio.composeapp.generated.resources.bulk_resolution_option_unknown
import nuvio.composeapp.generated.resources.bulk_resolution_picker_subtitle
import nuvio.composeapp.generated.resources.bulk_resolution_picker_title
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom sheet that lets the user pick a single resolution to download for every probed episode.
 * Each row shows how many episodes have a stream at that resolution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDownloadResolutionPickerSheet(
    choice: BulkDownloadResolutionChoice,
    onDismiss: () -> Unit,
    onSelect: (DebridStreamResolution) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.bulk_resolution_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        Res.string.bulk_resolution_picker_subtitle,
                        choice.scopeLabel,
                        choice.totalEpisodes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NuvioBottomSheetDivider()
            choice.options.forEach { option ->
                val icon = if (option.resolution == DebridStreamResolution.UNKNOWN) {
                    Icons.Rounded.Download
                } else {
                    Icons.Rounded.HighQuality
                }
                val title = if (option.resolution == DebridStreamResolution.UNKNOWN) {
                    stringResource(Res.string.bulk_resolution_option_unknown, option.episodeCount)
                } else {
                    stringResource(Res.string.bulk_resolution_option, option.label, option.episodeCount)
                }
                NuvioBottomSheetActionRow(
                    icon = icon,
                    title = title,
                    onClick = {
                        val resolution = option.resolution
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState) {
                                onSelect(resolution)
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Hosts [BulkDownloadResolutionPickerSheet], observing [BulkDownloadsRepository]. Renders nothing
 * unless there is a pending resolution choice to present.
 */
@Composable
fun BulkDownloadResolutionPickerHost() {
    val state by BulkDownloadsRepository.uiState.collectAsStateWithLifecycle()
    val choice = state.pendingChoice ?: return
    BulkDownloadResolutionPickerSheet(
        choice = choice,
        onDismiss = { BulkDownloadsRepository.dismissPendingChoice() },
        onSelect = { resolution -> BulkDownloadsRepository.startSeriesDownloadAtResolution(resolution) },
    )
}
