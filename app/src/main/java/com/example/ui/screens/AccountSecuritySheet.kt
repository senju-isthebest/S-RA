package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.InputValidator
import com.example.core.SoraSettings
import com.example.data.model.UserProfile
import com.example.data.sync.SyncState
import com.example.ui.components.SoraStatusBadge
import com.example.ui.components.SoraSurfaceCard
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecuritySheet(
    uid: String,
    profile: UserProfile?,
    settings: SoraSettings,
    syncState: SyncState,
    onToggleLocalPrivacyMode: (Boolean) -> Unit,
    onOpenSyncCenter: () -> Unit,
    onExportAllDataJson: () -> Unit,
    onClearLocalCache: () -> Unit,
    onClearApiKey: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccountConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteConfirmInput by remember { mutableStateOf("") }
    val requiredWord = stringResource(R.string.sec_delete_word)
    val maskedUid = remember(uid) { InputValidator.maskUid(uid) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("account_security_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sec_sheet_title), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = SoraViolet)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile?.displayName?.ifBlank { stringResource(R.string.home_scholar_fallback) }
                                    ?: stringResource(R.string.home_scholar_fallback),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!profile?.email.isNullOrBlank()) {
                                Text(
                                    text = profile.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = stringResource(R.string.sec_uid_masked, maskedUid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SoraStatusBadge(
                        text = stringResource(R.string.sec_encryption_badge),
                        color = SoraSuccess,
                        icon = Icons.Rounded.EnhancedEncryption
                    )
                }
            }

            SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.sec_privacy_toggle), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.sec_privacy_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.localOnlyPrivacyMode,
                        onCheckedChange = onToggleLocalPrivacyMode,
                        modifier = Modifier.testTag("privacy_mode_switch")
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenSyncCenter()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_open_sync_btn")
            ) {
                Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${stringResource(R.string.sec_open_sync)} (${syncStatusLabel(syncState)})")
            }

            FilledTonalButton(
                onClick = onExportAllDataJson,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_export_json_btn")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sec_export_json))
            }

            OutlinedButton(
                onClick = onClearLocalCache,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_clear_cache_btn")
            ) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sec_clear_cache))
            }

            if (settings.geminiApiKey.isNotBlank()) {
                OutlinedButton(
                    onClick = onClearApiKey,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_clear_key_btn")
                ) {
                    Icon(Icons.Rounded.KeyOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_clear))
                }
            }

            OutlinedButton(
                onClick = {
                    onDismiss()
                    onSignOut()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_sign_out_btn")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cd_sign_out))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Button(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sec_delete_account_btn")
            ) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sec_delete_account_btn))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.sec_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.sec_delete_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = deleteConfirmInput,
                        onValueChange = { deleteConfirmInput = it },
                        label = { Text(stringResource(R.string.sec_delete_confirm_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("delete_account_confirm_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                        onDeleteAccountConfirmed()
                    },
                    enabled = deleteConfirmInput.trim() == requiredWord,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
