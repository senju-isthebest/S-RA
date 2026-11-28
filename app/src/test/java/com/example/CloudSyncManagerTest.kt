package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLanguage
import com.example.core.SoraSettings
import com.example.data.ai.SoraAiService
import com.example.data.local.DocumentProcessor
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.StudyNote
import com.example.data.model.SyncedUserPreferences
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.ConflictResolver
import com.example.data.sync.NetworkConnectivityProvider
import com.example.data.sync.OfflineSyncOperation
import com.example.data.sync.SyncEngineBackend
import com.example.data.sync.SyncOpType
import com.example.data.sync.SyncStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private class FakeSyncBackend(
    var failUpdateTimes: Int = 0,
    var pulledPages: List<BookPageContent> = emptyList()
) : SyncEngineBackend {
    val pushedFieldUpdates = mutableListOf<Pair<String, Map<String, Any>>>()
    val pushedPrefs = mutableListOf<SyncedUserPreferences>()
    var updateCallCount = 0

    override suspend fun pushBookPages(bookId: String, localBookId: String): Result<Unit> = Result.success(Unit)
    override suspend fun pullBookPages(bookId: String): Result<List<BookPageContent>> = Result.success(pulledPages)
    override suspend fun updateBookFields(bookId: String, fields: Map<String, Any>): Result<Unit> {
        updateCallCount++
        if (updateCallCount <= failUpdateTimes) {
            return Result.failure(IOException("Transient offline failure #$updateCallCount"))
        }
        pushedFieldUpdates.add(bookId to fields)
        return Result.success(Unit)
    }

    override suspend fun pushPreferences(prefs: SyncedUserPreferences): Result<Unit> {
        pushedPrefs.add(prefs)
        return Result.success(Unit)
    }

    override suspend fun upsertNote(noteId: String?, note: StudyNote): Result<String> = Result.success(noteId ?: "n1")
    override suspend fun deleteNote(noteId: String): Result<Unit> = Result.success(Unit)
    override suspend fun submitExamFields(examId: String, fields: Map<String, Any>): Result<Unit> = Result.success(Unit)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudSyncManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun docProcessor(): DocumentProcessor =
        DocumentProcessor(ApplicationProvider.getApplicationContext(), SoraAiService())

    @Test
    fun `conflict resolver merges reading pages, bookmarks, highlights, and preferences deterministically`() {
        val (nearPage, nearConflict) = ConflictResolver.mergeReadingPage(12L, 19L, 10_000L, 10_800L)
        assertEquals(19L, nearPage)
        assertTrue(nearConflict)

        val (lwwPage, _) = ConflictResolver.mergeReadingPage(25L, 8L, 50_000L, 20_000L)
        assertEquals(25L, lwwPage)

        val (mergedBookmarks, bmConflict) = ConflictResolver.mergeBookmarks(listOf(3L, 10L), listOf(5L, 10L))
        assertEquals(listOf(3L, 5L, 10L), mergedBookmarks)
        assertTrue(bmConflict)

        val (mergedHighlights, hlConflict) = ConflictResolver.mergeHighlightsJson(
            """{"4":"Newton's First Law\n• Inertia"}""",
            """{"4":"Inertia\n• F=ma"}"""
        )
        assertTrue(hlConflict)
        assertTrue(mergedHighlights.contains("Newton's First Law"))
        assertTrue(mergedHighlights.contains("Inertia"))
        assertTrue(mergedHighlights.contains("F=ma"))

        assertTrue(ConflictResolver.shouldApplyRemotePreferences(SoraSettings(updatedAtMillis = 100L), SyncedUserPreferences(clientUpdatedAtMillis = 200L)))
        assertFalse(ConflictResolver.shouldApplyRemotePreferences(SoraSettings(updatedAtMillis = 300L), SyncedUserPreferences(clientUpdatedAtMillis = 200L)))
    }

    @Test
    fun `offline operations persist to disk and flush cleanly with retry backoff once online`() = runTest {
        val dir = tempFolder.newFolder("sync_test")
        val online = false
        val backend = FakeSyncBackend(failUpdateTimes = 1)
        val processor = docProcessor()
        val manager = CloudSyncManager(
            uid = "user_alpha",
            filesDir = dir,
            backend = backend,
            documentProcessor = processor,
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = online
            },
            delayFn = {}
        )

        manager.enqueueOrRun(
            OfflineSyncOperation(
                opType = SyncOpType.UPDATE_BOOK_FIELDS,
                entityId = "book_1",
                payloadJson = """{"currentPage":14}"""
            ),
            localOnlyPrivacyMode = false
        )
        assertEquals(SyncStatus.OFFLINE_QUEUED, manager.syncState.value.status)
        assertEquals(1, manager.syncState.value.pendingCount)

        val reloaded = CloudSyncManager(
            uid = "user_alpha",
            filesDir = dir,
            backend = backend,
            documentProcessor = processor,
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = true
            },
            delayFn = {}
        )
        assertEquals(1, reloaded.syncState.value.pendingCount)

        val flushResult = reloaded.flushPendingQueue(maxRetries = 4, localOnlyPrivacyMode = false)
        assertTrue(flushResult.isSuccess)
        assertEquals(SyncStatus.SYNCED, reloaded.syncState.value.status)
        assertEquals(0, reloaded.syncState.value.pendingCount)
        assertEquals(1, backend.pushedFieldUpdates.size)
        assertEquals("book_1", backend.pushedFieldUpdates.first().first)
        assertEquals(14L, backend.pushedFieldUpdates.first().second["currentPage"])
    }

    @Test
    fun `local only privacy mode keeps changes offline without invoking remote backend`() = runTest {
        val dir = tempFolder.newFolder("privacy_test")
        val backend = FakeSyncBackend()
        val manager = CloudSyncManager(
            uid = "user_beta",
            filesDir = dir,
            backend = backend,
            documentProcessor = docProcessor(),
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = true
            },
            delayFn = {}
        )

        manager.enqueueOrRun(
            OfflineSyncOperation(
                opType = SyncOpType.UPDATE_BOOK_FIELDS,
                entityId = "book_99",
                payloadJson = """{"currentPage":5}"""
            ),
            localOnlyPrivacyMode = true
        )
        assertTrue(manager.syncState.value.localOnlyMode)
        assertEquals(1, manager.syncState.value.pendingCount)
        assertTrue(backend.pushedFieldUpdates.isEmpty())
    }

    @Test
    fun `reconcile book conflicts resolves page bookmarks highlights and hydrates pages`() = runTest {
        val dir = tempFolder.newFolder("reconcile_test")
        val backend = FakeSyncBackend(
            pulledPages = listOf(BookPageContent(1, "Hydrated page 1", "CLOUD_SYNC"))
        )
        val processor = docProcessor()
        val manager = CloudSyncManager(
            uid = "user_gamma",
            filesDir = dir,
            backend = backend,
            documentProcessor = processor,
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = true
            },
            delayFn = {}
        )
        val local = Book(
            id = "b1",
            localBookId = "local_b1",
            currentPage = 10L,
            bookmarkedPages = listOf(1L, 4L),
            highlightsJson = """{"2":"Key formula"}"""
        )
        val remote = Book(
            id = "b1",
            localBookId = "local_b1",
            currentPage = 16L,
            bookmarkedPages = listOf(4L, 9L),
            highlightsJson = """{"2":"Second insight"}"""
        )
        val (resolved, conflictCount) = manager.reconcileBookConflicts(
            localBook = local,
            remoteBook = remote,
            localUpdatedMs = 1000L,
            remoteUpdatedMs = 9000L
        )
        assertEquals(16L, resolved.currentPage)
        assertEquals(listOf(1L, 4L, 9L), resolved.bookmarkedPages)
        assertTrue(resolved.highlightsJson.contains("Key formula"))
        assertTrue(resolved.highlightsJson.contains("Second insight"))
        assertTrue(conflictCount >= 1)
        assertTrue(manager.syncState.value.conflictsResolved >= 1)

        val hydrated = manager.hydrateMissingBookPages(resolved).getOrThrow()
        assertEquals(1, hydrated.size)
        assertEquals("Hydrated page 1", processor.loadPages("local_b1").first().text)
    }

    @Test
    fun `synced preferences snapshot excludes any sensitive secret fields`() {
        val settings = SoraSettings(
            appLanguage = AppLanguage.ARABIC,
            geminiApiKey = "AIzaSuperSecretDoNotSync",
            pomodoroWorkMinutes = 40
        )
        val doc = SyncedUserPreferences(
            languageCode = settings.appLanguage.code,
            pomodoroWorkMinutes = settings.pomodoroWorkMinutes.toLong(),
            ollamaModel = settings.ollamaModel,
            geminiModel = settings.geminiModel
        )
        val map = doc.toCreateMap()
        assertEquals("ar", map["languageCode"])
        assertEquals(40L, map["pomodoroWorkMinutes"])
        assertFalse(map.containsKey("geminiApiKey"))
        assertFalse(map.values.joinToString().contains("AIzaSuperSecretDoNotSync"))
    }

    @Test
    fun `blank uid is rejected and corrupt queue file is quarantined with visible error`() = runTest {
        val dir = tempFolder.newFolder("quarantine_test")
        val backend = FakeSyncBackend()
        val processor = docProcessor()

        try {
            CloudSyncManager(
                uid = "   ",
                filesDir = dir,
                backend = backend,
                documentProcessor = processor,
                connectivityProvider = object : NetworkConnectivityProvider {
                    override fun isOnline(): Boolean = true
                },
                delayFn = {}
            )
            fail("Expected IllegalArgumentException for blank UID")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val syncDir = java.io.File(dir, "sync").apply { mkdirs() }
        val corruptFile = java.io.File(syncDir, "queue_user_corrupt.json")
        corruptFile.writeText("{not-valid-json", Charsets.UTF_8)

        val manager = CloudSyncManager(
            uid = "user_corrupt",
            filesDir = dir,
            backend = backend,
            documentProcessor = processor,
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = true
            },
            delayFn = {}
        )

        assertEquals(SyncStatus.ERROR, manager.syncState.value.status)
        assertTrue(manager.syncState.value.lastError.contains("quarantined", ignoreCase = true))
        assertFalse("Original corrupt queue file must be moved", corruptFile.exists())
        val quarantined = syncDir.listFiles { _, name -> name.startsWith("queue_user_corrupt.corrupt.") }
        assertTrue("Quarantined backup must remain on disk", !quarantined.isNullOrEmpty())

        // Per-UID queue isolation check
        val otherManager = CloudSyncManager(
            uid = "user_clean",
            filesDir = dir,
            backend = backend,
            documentProcessor = processor,
            connectivityProvider = object : NetworkConnectivityProvider {
                override fun isOnline(): Boolean = false
            },
            delayFn = {}
        )
        otherManager.enqueueOrRun(
            OfflineSyncOperation(opType = SyncOpType.UPDATE_BOOK_FIELDS, entityId = "b_clean", payloadJson = "{}"),
            localOnlyPrivacyMode = false
        )
        assertEquals(1, otherManager.getPendingOperations().size)
        assertEquals(0, manager.getPendingOperations().size)
    }
}
