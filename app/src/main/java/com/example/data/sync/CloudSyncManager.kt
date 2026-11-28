package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.core.InputValidator
import com.example.core.SoraSettings
import com.example.data.ai.JsonObj
import com.example.data.ai.JsonStr
import com.example.data.ai.SoraJson
import com.example.data.local.DocumentProcessor
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.StudyNote
import com.example.data.model.SyncedUserPreferences
import com.example.data.repository.SoraRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

private const val TAG = "CloudSyncManager"

enum class SyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    OFFLINE_QUEUED,
    CONFLICT_RESOLVED,
    ERROR
}

enum class SyncOpType {
    SYNC_BOOK_PAGES,
    UPDATE_BOOK_FIELDS,
    SYNC_PREFERENCES,
    UPSERT_NOTE,
    DELETE_NOTE,
    SUBMIT_EXAM
}

data class OfflineSyncOperation(
    val id: String = UUID.randomUUID().toString(),
    val opType: SyncOpType,
    val entityId: String,
    val payloadJson: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String = ""
)

data class SyncState(
    val status: SyncStatus = SyncStatus.IDLE,
    val pendingCount: Int = 0,
    val conflictsResolved: Int = 0,
    val lastSyncedAtMillis: Long = 0L,
    val lastError: String = "",
    val isOnline: Boolean = true,
    val localOnlyMode: Boolean = false
)

interface NetworkConnectivityProvider {
    fun isOnline(): Boolean
}

class AndroidConnectivityProvider(private val context: Context) : NetworkConnectivityProvider {
    override fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            // Do not falsely block on environments lacking connectivity service privileges
            true
        }
    }
}

/**
 * Deterministic conflict resolution helpers for multi-device SŌRA cloud sync.
 */
object ConflictResolver {

    fun mergeReadingPage(
        localPage: Long,
        remotePage: Long,
        localUpdatedMs: Long,
        remoteUpdatedMs: Long
    ): Pair<Long, Boolean> {
        val safeLocal = localPage.coerceAtLeast(1L)
        val safeRemote = remotePage.coerceAtLeast(1L)
        if (safeLocal == safeRemote) return safeLocal to false
        return when {
            kotlin.math.abs(localUpdatedMs - remoteUpdatedMs) <= 1_500L -> {
                maxOf(safeLocal, safeRemote) to true
            }
            remoteUpdatedMs > localUpdatedMs -> safeRemote to true
            else -> safeLocal to true
        }
    }

    fun mergeBookmarks(local: List<Long>, remote: List<Long>): Pair<List<Long>, Boolean> {
        val merged = (local + remote).filter { it >= 1L }.distinct().sorted().take(500)
        val changed = merged != local.sorted() && local != remote
        return merged to changed
    }

    fun mergeHighlightsJson(localJson: String, remoteJson: String): Pair<String, Boolean> {
        val localObj = parseJsonSafe(localJson)
        val remoteObj = parseJsonSafe(remoteJson)
        val allKeys = (localObj.entries.keys + remoteObj.entries.keys).distinct()
        var hadConflict = false
        val mergedMap = LinkedHashMap<String, JsonStr>()

        for (key in allKeys) {
            val lRaw = localObj.str(key, "").trim()
            val rRaw = remoteObj.str(key, "").trim()
            when {
                lRaw.isEmpty() && rRaw.isNotEmpty() -> {
                    mergedMap[key] = JsonStr(rRaw.take(1500))
                }
                rRaw.isEmpty() && lRaw.isNotEmpty() -> {
                    mergedMap[key] = JsonStr(lRaw.take(1500))
                }
                lRaw == rRaw -> {
                    if (lRaw.isNotEmpty()) mergedMap[key] = JsonStr(lRaw.take(1500))
                }
                else -> {
                    hadConflict = true
                    val lItems = lRaw.split("\n• ").map { it.trim().trimStart('•').trim() }.filter { it.isNotBlank() }
                    val rItems = rRaw.split("\n• ").map { it.trim().trimStart('•').trim() }.filter { it.isNotBlank() }
                    val combined = (lItems + rItems).distinct()
                    val joined = combined.joinToString("\n• ").take(1500)
                    mergedMap[key] = JsonStr(joined)
                }
            }
        }
        return JsonObj(mergedMap).toJsonString() to hadConflict
    }

    fun shouldApplyRemotePreferences(local: SoraSettings, remote: SyncedUserPreferences): Boolean {
        return remote.clientUpdatedAtMillis > local.updatedAtMillis
    }

    private fun parseJsonSafe(raw: String): JsonObj {
        if (raw.isBlank()) return JsonObj(emptyMap())
        return try {
            SoraJson.parseObject(raw)
        } catch (_: Exception) {
            JsonObj(emptyMap())
        }
    }
}

interface SyncEngineBackend {
    suspend fun pushBookPages(bookId: String, localBookId: String): Result<Unit>
    suspend fun pullBookPages(bookId: String): Result<List<BookPageContent>>
    suspend fun updateBookFields(bookId: String, fields: Map<String, Any>): Result<Unit>
    suspend fun pushPreferences(prefs: SyncedUserPreferences): Result<Unit>
    suspend fun upsertNote(noteId: String?, note: StudyNote): Result<String>
    suspend fun deleteNote(noteId: String): Result<Unit>
    suspend fun submitExamFields(examId: String, fields: Map<String, Any>): Result<Unit>
}

class FirestoreSyncBackend(
    private val repository: SoraRepository,
    private val documentProcessor: DocumentProcessor
) : SyncEngineBackend {
    override suspend fun pushBookPages(bookId: String, localBookId: String): Result<Unit> {
        val pages = documentProcessor.loadPages(localBookId)
        if (pages.isEmpty()) return Result.success(Unit)
        return repository.saveBookPages(bookId, pages)
    }

    override suspend fun pullBookPages(bookId: String): Result<List<BookPageContent>> {
        return repository.fetchBookPages(bookId)
    }

    override suspend fun updateBookFields(bookId: String, fields: Map<String, Any>): Result<Unit> {
        return repository.updateBook(bookId, fields)
    }

    override suspend fun pushPreferences(prefs: SyncedUserPreferences): Result<Unit> {
        return repository.saveSyncedPreferences(prefs)
    }

    override suspend fun upsertNote(noteId: String?, note: StudyNote): Result<String> {
        return if (noteId.isNullOrBlank()) {
            repository.createNote(note)
        } else {
            val fields = note.toCreateMap().toMutableMap().apply { remove("createdAt") }
            repository.updateNote(noteId, fields).map { noteId }
        }
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> {
        return repository.deleteNote(noteId)
    }

    override suspend fun submitExamFields(examId: String, fields: Map<String, Any>): Result<Unit> {
        return repository.updateExamProgress(examId, fields)
    }
}

class CloudSyncManager(
    private val uid: String,
    private val filesDir: File,
    private val backend: SyncEngineBackend,
    private val documentProcessor: DocumentProcessor,
    private val connectivityProvider: NetworkConnectivityProvider,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val syncDir = File(filesDir, "sync").apply { mkdirs() }
    private val queueFile = File(syncDir, "queue_${InputValidator.sanitizeFileName(uid, "user")}.json")

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        val initial = loadQueueLocked()
        _syncState.value = SyncState(
            status = if (initial.isEmpty()) SyncStatus.IDLE else SyncStatus.OFFLINE_QUEUED,
            pendingCount = initial.size,
            isOnline = connectivityProvider.isOnline()
        )
    }

    suspend fun getPendingOperations(): List<OfflineSyncOperation> = mutex.withLock {
        loadQueueLocked()
    }

    suspend fun enqueueOrRun(
        op: OfflineSyncOperation,
        localOnlyPrivacyMode: Boolean
    ): Result<Unit> {
        mutex.withLock {
            val current = loadQueueLocked().toMutableList()
            // Coalesce duplicate preference or reading page sync ops for same entity
            if (op.opType == SyncOpType.SYNC_PREFERENCES || op.opType == SyncOpType.SYNC_BOOK_PAGES) {
                current.removeAll { it.opType == op.opType && it.entityId == op.entityId }
            }
            current.add(op)
            saveQueueLocked(current)
            _syncState.value = _syncState.value.copy(
                pendingCount = current.size,
                localOnlyMode = localOnlyPrivacyMode,
                isOnline = connectivityProvider.isOnline(),
                status = if (localOnlyPrivacyMode || !connectivityProvider.isOnline()) {
                    SyncStatus.OFFLINE_QUEUED
                } else {
                    _syncState.value.status
                }
            )
        }
        return if (localOnlyPrivacyMode || !connectivityProvider.isOnline()) {
            Result.success(Unit)
        } else {
            flushPendingQueue(localOnlyPrivacyMode = false)
        }
    }

    suspend fun flushPendingQueue(
        maxRetries: Int = 3,
        localOnlyPrivacyMode: Boolean = false
    ): Result<Unit> = mutex.withLock {
        val online = connectivityProvider.isOnline()
        if (localOnlyPrivacyMode) {
            val count = loadQueueLocked().size
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.OFFLINE_QUEUED,
                pendingCount = count,
                localOnlyMode = true,
                isOnline = online
            )
            return Result.success(Unit)
        }
        if (!online) {
            val count = loadQueueLocked().size
            _syncState.value = _syncState.value.copy(
                status = if (count > 0) SyncStatus.OFFLINE_QUEUED else SyncStatus.IDLE,
                pendingCount = count,
                isOnline = false,
                localOnlyMode = false
            )
            return Result.failure(IllegalStateException("Device offline; sync queued locally."))
        }

        val queue = loadQueueLocked().toMutableList()
        if (queue.isEmpty()) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.SYNCED,
                pendingCount = 0,
                isOnline = true,
                localOnlyMode = false,
                lastError = ""
            )
            return Result.success(Unit)
        }

        _syncState.value = _syncState.value.copy(
            status = SyncStatus.SYNCING,
            pendingCount = queue.size,
            isOnline = true,
            localOnlyMode = false,
            lastError = ""
        )

        val remaining = mutableListOf<OfflineSyncOperation>()
        var lastErr: Throwable? = null

        for (op in queue) {
            var succeeded = false
            var opErr: Throwable? = null
            for (attempt in 0 until maxRetries.coerceAtLeast(1)) {
                val res = executeOperation(op)
                if (res.isSuccess) {
                    succeeded = true
                    break
                } else {
                    opErr = res.exceptionOrNull()
                    if (attempt < maxRetries - 1) {
                        delayFn(500L shl attempt)
                    }
                }
            }
            if (!succeeded) {
                lastErr = opErr
                remaining.add(
                    op.copy(
                        attempts = op.attempts + 1,
                        lastError = opErr?.localizedMessage ?: "Sync error"
                    )
                )
            }
        }

        saveQueueLocked(remaining)
        return if (remaining.isEmpty()) {
            val now = System.currentTimeMillis()
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.SYNCED,
                pendingCount = 0,
                lastSyncedAtMillis = now,
                lastError = "",
                isOnline = true
            )
            Result.success(Unit)
        } else {
            val errMsg = lastErr?.localizedMessage ?: "Pending sync items could not reach cloud."
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.ERROR,
                pendingCount = remaining.size,
                lastError = errMsg,
                isOnline = connectivityProvider.isOnline()
            )
            Result.failure(lastErr ?: IllegalStateException(errMsg))
        }
    }

    suspend fun hydrateMissingBookPages(book: Book): Result<List<BookPageContent>> {
        val local = documentProcessor.loadPages(book.localBookId)
        if (local.isNotEmpty()) return Result.success(local)
        if (!connectivityProvider.isOnline()) {
            return Result.success(emptyList())
        }
        val pulled = backend.pullBookPages(book.id)
        pulled.onSuccess { remotePages ->
            if (remotePages.isNotEmpty()) {
                documentProcessor.savePages(book.localBookId, remotePages)
            }
        }
        return pulled
    }

    fun reconcileBookConflicts(
        localBook: Book,
        remoteBook: Book,
        localUpdatedMs: Long,
        remoteUpdatedMs: Long
    ): Pair<Book, Int> {
        var conflicts = 0
        val (mergedPage, pageConflict) = ConflictResolver.mergeReadingPage(
            localBook.currentPage,
            remoteBook.currentPage,
            localUpdatedMs,
            remoteUpdatedMs
        )
        if (pageConflict) conflicts++

        val (mergedBookmarks, bmConflict) = ConflictResolver.mergeBookmarks(
            localBook.bookmarkedPages,
            remoteBook.bookmarkedPages
        )
        if (bmConflict) conflicts++

        val (mergedHighlights, hlConflict) = ConflictResolver.mergeHighlightsJson(
            localBook.highlightsJson,
            remoteBook.highlightsJson
        )
        if (hlConflict) conflicts++

        if (conflicts > 0) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.CONFLICT_RESOLVED,
                conflictsResolved = _syncState.value.conflictsResolved + conflicts
            )
        }

        val resolved = remoteBook.copy(
            currentPage = mergedPage,
            bookmarkedPages = mergedBookmarks,
            highlightsJson = mergedHighlights
        )
        return resolved to conflicts
    }

    suspend fun clearLocalQueue() = mutex.withLock {
        if (queueFile.exists()) queueFile.delete()
        _syncState.value = SyncState(isOnline = connectivityProvider.isOnline())
    }

    private suspend fun executeOperation(op: OfflineSyncOperation): Result<Unit> {
        return try {
            val obj = SoraJson.parseObject(op.payloadJson.ifBlank { "{}" })
            when (op.opType) {
                SyncOpType.SYNC_BOOK_PAGES -> {
                    val localBookId = obj.str("localBookId", op.entityId)
                    backend.pushBookPages(op.entityId, localBookId)
                }
                SyncOpType.UPDATE_BOOK_FIELDS -> {
                    backend.updateBookFields(op.entityId, jsonObjToMap(obj))
                }
                SyncOpType.SYNC_PREFERENCES -> {
                    val prefs = SyncedUserPreferences(
                        themeMode = obj.str("themeMode", "DARK"),
                        languageCode = obj.str("languageCode", "en"),
                        pomodoroWorkMinutes = obj.longVal("pomodoroWorkMinutes", 25L),
                        pomodoroShortBreakMinutes = obj.longVal("pomodoroShortBreakMinutes", 5L),
                        pomodoroLongBreakMinutes = obj.longVal("pomodoroLongBreakMinutes", 15L),
                        ambientFocusSound = obj.boolVal("ambientFocusSound", false),
                        aiStrategy = obj.str("aiStrategy", "GEMINI_FIRST"),
                        aiFallbackEnabled = obj.boolVal("aiFallbackEnabled", true),
                        ollamaModel = obj.str("ollamaModel", "qwen2.5:3b"),
                        ollamaVisionModel = obj.str("ollamaVisionModel", ""),
                        geminiModel = obj.str("geminiModel", "gemini-3.8-flash"),
                        aiTimeoutMs = obj.longVal("aiTimeoutMs", 60000L),
                        clientUpdatedAtMillis = obj.longVal("clientUpdatedAtMillis", op.createdAtMillis)
                    )
                    backend.pushPreferences(prefs)
                }
                SyncOpType.UPSERT_NOTE -> {
                    val existingId = obj.str("existingId", "").ifBlank { null }
                    val note = StudyNote(
                        id = existingId.orEmpty(),
                        bookId = obj.str("bookId"),
                        subjectName = obj.str("subjectName", "General"),
                        chapterNumber = obj.longVal("chapterNumber", 0L),
                        linkedPage = obj.longVal("linkedPage", 0L),
                        title = obj.str("title"),
                        contentMarkdown = obj.str("contentMarkdown"),
                        tags = obj.strList("tags"),
                        bookmarked = obj.boolVal("bookmarked", false),
                        quickNote = obj.boolVal("quickNote", false)
                    )
                    backend.upsertNote(existingId, note).map { Unit }
                }
                SyncOpType.DELETE_NOTE -> {
                    backend.deleteNote(op.entityId)
                }
                SyncOpType.SUBMIT_EXAM -> {
                    backend.submitExamFields(op.entityId, jsonObjToMap(obj))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Operation ${op.opType} failed", e)
            Result.failure(e)
        }
    }

    private fun jsonObjToMap(obj: JsonObj): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        obj.entries.forEach { (k, v) ->
            when (v) {
                is com.example.data.ai.JsonStr -> out[k] = v.value
                is com.example.data.ai.JsonBool -> out[k] = v.value
                is com.example.data.ai.JsonNum -> {
                    val d = v.toDoubleOrNull()
                    if (v.raw.contains('.')) out[k] = d ?: 0.0
                    else out[k] = v.toLongOrNull() ?: 0L
                }
                is com.example.data.ai.JsonArr -> {
                    val items = v.items
                    if (items.all { it is com.example.data.ai.JsonNum }) {
                        out[k] = items.mapNotNull { (it as? com.example.data.ai.JsonNum)?.toLongOrNull() }
                    } else {
                        out[k] = items.mapNotNull { (it as? com.example.data.ai.JsonStr)?.value }
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun loadQueueLocked(): List<OfflineSyncOperation> {
        if (!queueFile.exists()) return emptyList()
        return try {
            val root = SoraJson.parseObject(queueFile.readText(Charsets.UTF_8))
            val arr = root.arr("operations") ?: return emptyList()
            arr.items.mapNotNull { item ->
                val obj = item as? JsonObj ?: return@mapNotNull null
                val type = SyncOpType.entries.firstOrNull { it.name == obj.str("opType") }
                    ?: return@mapNotNull null
                OfflineSyncOperation(
                    id = obj.str("id").ifBlank { UUID.randomUUID().toString() },
                    opType = type,
                    entityId = obj.str("entityId"),
                    payloadJson = obj.str("payloadJson", "{}"),
                    createdAtMillis = obj.longVal("createdAtMillis", 0L),
                    attempts = obj.intVal("attempts", 0),
                    lastError = obj.str("lastError", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read offline sync queue", e)
            emptyList()
        }
    }

    private fun saveQueueLocked(items: List<OfflineSyncOperation>) {
        try {
            val jsonItems = items.map { op ->
                SoraJson.obj(
                    "id" to op.id,
                    "opType" to op.opType.name,
                    "entityId" to op.entityId,
                    "payloadJson" to op.payloadJson,
                    "createdAtMillis" to op.createdAtMillis,
                    "attempts" to op.attempts,
                    "lastError" to op.lastError
                )
            }
            val payload = SoraJson.obj("operations" to jsonItems).toJsonString()
            queueFile.writeText(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist offline sync queue", e)
        }
    }
}
