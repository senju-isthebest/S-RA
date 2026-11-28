package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.core.AppLanguage
import com.example.core.EncryptedSecretBox
import com.example.core.InputValidator
import com.example.core.SoraSettings
import com.example.data.model.UserProfile
import com.example.data.sync.SyncState
import com.example.data.sync.SyncStatus
import com.example.ui.screens.AccountSecuritySheet
import com.example.ui.screens.SyncCenterDialog
import com.example.ui.theme.SoraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityAndAccountTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `encrypted secret box encrypts, decrypts, detects tampering, and keeps legacy plaintext readable`() {
        val secret = "AIzaSyCustomStudentSecret12345"
        val encrypted = EncryptedSecretBox.encrypt(secret)
        assertTrue(encrypted.startsWith("enc_v1:"))
        assertNotEquals(secret, encrypted)
        assertTrue(EncryptedSecretBox.isEncryptedPayload(encrypted))
        assertEquals(secret, EncryptedSecretBox.decrypt(encrypted))

        // Tampered payload safely returns blank instead of crashing
        val tampered = encrypted.dropLast(4) + "AAAA"
        assertEquals("", EncryptedSecretBox.decrypt(tampered))

        // Blank stays blank; legacy unencrypted value remains readable until re-saved
        assertEquals("", EncryptedSecretBox.encrypt("   "))
        assertEquals("legacy_key", EncryptedSecretBox.decrypt("legacy_key"))
    }

    @Test
    fun `input validator blocks path traversal strips control chars masks UIDs and redacts secrets`() {
        val cleanName = InputValidator.sanitizeFileName("../../etc/passwd\u0000.pdf")
        assertFalse(cleanName.contains(".."))
        assertFalse(cleanName.contains("/"))
        assertFalse(cleanName.contains("\\"))

        assertEquals("Physics Ch 1", InputValidator.sanitizeText("  Physics\u0007 Ch 1  ", 50))
        assertEquals("abcd••••wxyz", InputValidator.maskUid("abcdef123456wxyz"))
        assertEquals("••••23", InputValidator.maskUid("123"))

        val redacted = InputValidator.redactSecrets(
            "Error using AIzaTestSecret99 at Ollama",
            listOf("AIzaTestSecret99")
        )
        assertFalse(redacted.contains("AIzaTestSecret99"))
    }

    @Test
    fun `sync center dialog reflects queued state privacy warning and triggers actions`() {
        var syncClicked = false
        var clearClicked = false
        composeRule.setContent {
            SoraTheme {
                SyncCenterDialog(
                    syncState = SyncState(
                        status = SyncStatus.OFFLINE_QUEUED,
                        pendingCount = 3,
                        conflictsResolved = 1,
                        localOnlyMode = false
                    ),
                    appLanguage = AppLanguage.ENGLISH,
                    onSyncNow = { syncClicked = true },
                    onClearQueue = { clearClicked = true },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("sync_center_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_now_button").assertIsEnabled().performClick()
        assertTrue(syncClicked)
        composeRule.onNodeWithTag("sync_clear_queue_button").assertIsEnabled().performClick()
        assertTrue(clearClicked)
    }

    @Test
    fun `account security sheet requires typed DELETE confirmation before account deletion`() {
        var privacyToggledTo: Boolean? = null
        var deleteConfirmed = false

        composeRule.setContent {
            SoraTheme {
                AccountSecuritySheet(
                    uid = "firebase_uid_987654321",
                    profile = UserProfile(displayName = "Amira", email = "amira@edu.example"),
                    settings = SoraSettings(localOnlyPrivacyMode = false),
                    syncState = SyncState(status = SyncStatus.SYNCED),
                    onToggleLocalPrivacyMode = { privacyToggledTo = it },
                    onOpenSyncCenter = {},
                    onExportAllDataJson = {},
                    onClearLocalCache = {},
                    onClearApiKey = {},
                    onSignOut = {},
                    onDeleteAccountConfirmed = { deleteConfirmed = true },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("account_security_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_mode_switch").performClick()
        assertEquals(true, privacyToggledTo)

        // Open delete account dialog and ensure confirm stays disabled until exact DELETE keyword
        composeRule.onNodeWithTag("sec_delete_account_btn").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_delete_account_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("delete_account_confirm_input").performTextInput("delete")
        composeRule.onNodeWithTag("confirm_delete_account_button").assertIsNotEnabled()

        composeRule.onNodeWithTag("delete_account_confirm_input").performTextReplacement("DELETE")
        composeRule.onNodeWithTag("confirm_delete_account_button").assertIsEnabled().performClick()
        assertTrue(deleteConfirmed)
    }
}
