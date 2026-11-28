package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppLanguage
import com.example.core.SoraFormatters
import com.example.data.sync.SyncState
import com.example.data.sync.SyncStatus
import com.example.ui.components.SoraStatusBadge
import com.example.ui.components.SoraSurfaceCard
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun syncStatusLabel(state: SyncState): String = when (state.status) {
    SyncStatus.IDLE -> stringResource(R.string.sync_status_idle)
    SyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
    SyncStatus.SYNCED -> stringResource(R.string.sync_status_synced)
    SyncStatus.OFFLINE_QUEUED -> stringResource(R.string.sync_status_queued, state.pendingCount)
    SyncStatus.CONFLICT_RESOLVED -> stringResource(R.string.sync_status_conflict, state.conflictsResolved)
    SyncStatus.ERROR -> stringResource(R.string.sync_status_error)
}

@Composable
fun SyncCenterDialog(
    syncState: SyncState,
    appLanguage: AppLanguage,
    onSyncNow: () -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val badgeColor = when (syncState.status) {
        SyncStatus.SYNCED -> SoraSuccess
        SyncStatus.SYNCING -> SoraSoftBlue
        SyncStatus.OFFLINE_QUEUED, SyncStatus.CONFLICT_RESOLVED -> SoraWarning
        SyncStatus.ERROR -> MaterialTheme.colorScheme.error
        SyncStatus.IDLE -> SoraViolet
    }

    val lastSyncFormatted = if (syncState.lastSyncedAtMillis > 0L) {
        SoraFormatters.formatDate(syncState.lastSyncedAtMillis, appLanguage)
    } else {
        stringResource(R.string.sync_never)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = badgeColor)
                Text(stringResource(R.string.sync_center_title))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("sync_center_dialog"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SoraStatusBadge(
                    text = syncStatusLabel(syncState),
                    color = badgeColor,
                    icon = Icons.Rounded.Sync
                )

                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.sync_pending_ops, syncState.pendingCount),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.sync_conflicts_resolved, syncState.conflictsResolved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.sync_last_synced, lastSyncFormatted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (syncState.localOnlyMode) {
                    Surface(
                        color = SoraWarning.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.sync_mode_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoraWarning,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else if (syncState.pendingCount == 0 && syncState.lastError.isBlank()) {
                    Text(
                        text = stringResource(R.string.sync_queue_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (syncState.lastError.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = syncState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (syncState.pendingCount > 0) {
                    TextButton(
                        onClick = onClearQueue,
                        modifier = Modifier.testTag("sync_clear_queue_button")
                    ) {
                        Text(
                            text = stringResource(R.string.sync_btn_clear_queue),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSyncNow,
                enabled = syncState.status != SyncStatus.SYNCING && !syncState.localOnlyMode,
                modifier = Modifier.testTag("sync_now_button")
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sync_btn_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
