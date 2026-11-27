package com.example.data.sync

import android.net.Uri
import com.example.data.model.BookPageContent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.MessageDigest

/** User-scoped textbook source and extracted-page sync. */
class CloudBookSyncManager(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    companion object {
        const val MAX_SOURCE_BYTES = 80L * 1024L * 1024L
        const val PAGE_CHUNK_CHARS = 8_000
        const val MAX_BATCH_OPERATIONS = 450
        const val MAX_PAGE_TEXT_CHARS = 100_000
        const val MAX_TOTAL_TEXT_CHARS = 25_000_000
    }

    private fun uid() = auth.currentUser?.uid
        ?: throw IllegalStateException("Sign in before syncing books.")

    private fun validateBookId(bookId: String) {
        require(bookId.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))) { "Invalid book ID." }
    }

    private fun bookRef(uid: String, bookId: String) =
        firestore.collection("users").document(uid).collection("books").document(bookId)

    private fun sourceRef(uid: String, bookId: String) =
        storage.reference.child("users/$uid/books/$bookId/source")

    suspend fun uploadSourceFile(
        bookId: String,
        file: File,
        originalFileName: String,
        contentType: String,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<String> {
        val userId = try { uid() } catch (error: Exception) { return Result.failure(error) }
        try {
            validateBookId(bookId)
            require(file.isFile && file.exists()) { "Book source file not found." }
            require(file.length() in 1..MAX_SOURCE_BYTES) { "File must be smaller than 80 MiB." }
        } catch (error: Exception) {
            return Result.failure(error)
        }
        val allowedTypes = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown",
            "image/jpeg",
            "image/png",
            "image/webp"
        )
        if (contentType !in allowedTypes) return Result.failure(IllegalArgumentException("Unsupported file type."))
        val ref = sourceRef(userId, bookId)
        val safeName = originalFileName.take(200)
        val hash = sha256(file)
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType(contentType.take(120).ifBlank { "application/octet-stream" })
            .setCustomMetadata("ownerUid", userId)
            .setCustomMetadata("bookId", bookId)
            .setCustomMetadata("originalFileName", safeName)
            .setCustomMetadata("sha256", hash)
            .build()
        return try {
            bookRef(userId, bookId).update(mapOf("syncStatus" to "UPLOADING", "updatedAt" to FieldValue.serverTimestamp())).await()
            val uploadTask = ref.putFile(Uri.fromFile(file), metadata)
            uploadTask.addOnProgressListener { snapshot ->
                onProgress(snapshot.bytesTransferred, snapshot.totalByteCount)
            }
            uploadTask.await()
            bookRef(userId, bookId).update(
                mapOf(
                    "fileName" to safeName,
                    "storagePath" to ref.path,
                    "sourceSizeBytes" to file.length(),
                    "sourceSha256" to hash,
                    "syncStatus" to "SYNCED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(ref.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching {
                bookRef(userId, bookId).update(
                    mapOf("syncStatus" to "FAILED", "updatedAt" to FieldValue.serverTimestamp())
                ).await()
            }
            Result.failure(error)
        }
    }

    suspend fun downloadSourceFile(bookId: String, destination: File): Result<File> {
        val userId = try { uid() } catch (error: Exception) { return Result.failure(error) }
        return try {
            validateBookId(bookId)
            val meta = bookRef(userId, bookId).get().await()
            require(meta.getString("storagePath") == "users/$userId/books/$bookId/source") {
                "Cloud file path is missing or does not match the signed-in user."
            }
            val size = meta.getLong("sourceSizeBytes") ?: 0L
            require(size in 1..MAX_SOURCE_BYTES) { "Invalid cloud file size." }
            destination.parentFile?.mkdirs()
            sourceRef(userId, bookId).getFile(destination).await()
            require(destination.length() == size) { "Downloaded file size does not match metadata." }
            val expectedHash = meta.getString("sourceSha256").orEmpty()
            if (expectedHash.isNotBlank()) require(sha256(destination) == expectedHash) {
                "Downloaded file checksum verification failed."
            }
            Result.success(destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            destination.delete()
            Result.failure(error)
        }
    }

    suspend fun uploadPages(
        bookId: String,
        pages: List<BookPageContent>,
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> }
    ): Result<Int> {
        val userId = try { uid() } catch (error: Exception) { return Result.failure(error) }
        val pagesRef = bookRef(userId, bookId).collection("pages")
        return try {
            validateBookId(bookId)
            require(pages.size <= 5_000) { "Page count exceeds the supported limit." }
            require(pages.map { it.pageNumber }.distinct().size == pages.size) { "Page numbers must be unique." }
            require(pages.sumOf { it.text.length.toLong() } <= MAX_TOTAL_TEXT_CHARS) { "Extracted text exceeds the cloud sync limit." }
            bookRef(userId, bookId).update(mapOf("pagesSyncStatus" to "UPLOADING", "updatedAt" to FieldValue.serverTimestamp())).await()
            val existing = pagesRef.get().await()
            val records = pages.flatMap { page ->
                require(page.pageNumber in 1..5000) { "Invalid page number." }
                require(page.text.length <= MAX_PAGE_TEXT_CHARS) { "Page text exceeds the supported limit." }
                val chunks = page.text.chunked(PAGE_CHUNK_CHARS).ifEmpty { listOf("") }
                require(chunks.size <= 100) { "Page text has too many chunks." }
                chunks.mapIndexed { chunkIndex, chunk ->
                    val docId = "p%05d_c%03d".format(page.pageNumber, chunkIndex)
                    val payload = mutableMapOf<String, Any>(
                        "pageNumber" to page.pageNumber,
                        "chunkIndex" to chunkIndex,
                        "chunkCount" to chunks.size,
                        "text" to chunk,
                        "extractionMethod" to page.extractionMethod.take(40),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    page.error?.take(500)?.let { payload["error"] = it }
                    docId to payload
                }
            }
            val newIds = records.map { it.first }.toSet()
            val operations = mutableListOf<Pair<String, Map<String, Any>?>>()
            records.forEach { operations += it }
            existing.documents.forEach { if (it.id !in newIds) operations += it.id to null }

            operations.chunked(MAX_BATCH_OPERATIONS).forEach { group ->
                val batch = firestore.batch()
                group.forEach { (id, data) ->
                    val ref = pagesRef.document(id)
                    if (data == null) batch.delete(ref) else batch.set(ref, data)
                }
                batch.commit().await()
            }
            pages.indices.forEach { index -> onProgress(index + 1, pages.size) }
            bookRef(userId, bookId).update(
                mapOf("cloudPageCount" to pages.size, "pagesSyncStatus" to "SYNCED", "updatedAt" to FieldValue.serverTimestamp())
            ).await()
            Result.success(records.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching { bookRef(userId, bookId).update(mapOf("pagesSyncStatus" to "FAILED", "updatedAt" to FieldValue.serverTimestamp())).await() }
            Result.failure(error)
        }
    }

    suspend fun downloadPages(bookId: String): Result<List<BookPageContent>> {
        val userId = try { uid() } catch (error: Exception) { return Result.failure(error) }
        return try {
            validateBookId(bookId)
            val snapshot = bookRef(userId, bookId).collection("pages").get().await()
            val parts = snapshot.documents.mapNotNull { doc ->
                val page = doc.getLong("pageNumber")?.toInt() ?: return@mapNotNull null
                val index = doc.getLong("chunkIndex")?.toInt() ?: 0
                val text = doc.getString("text") ?: ""
                val method = doc.getString("extractionMethod") ?: "TEXT"
                val error = doc.getString("error")
                val count = doc.getLong("chunkCount")?.toInt() ?: 1
                require(doc.id == "p%05d_c%03d".format(page, index)) { "Cloud page chunk ID does not match its payload." }
                Triple(page, index, PagePart(count, text, method, error))
            }
            val result = parts.groupBy { it.first }.toSortedMap().map { (pageNumber, pageParts) ->
                val sorted = pageParts.sortedBy { it.second }
                val expectedCount = sorted.firstOrNull()?.third?.count ?: 1
                require(sorted.all { it.third.count == expectedCount }) { "Cloud page chunk metadata is inconsistent." }
                require(sorted.size == expectedCount && sorted.map { it.second } == (0 until expectedCount).toList()) {
                    "Cloud page text is incomplete."
                }
                BookPageContent(
                    pageNumber = pageNumber,
                    text = sorted.joinToString("") { it.third.text },
                    extractionMethod = sorted.firstOrNull()?.third?.method ?: "TEXT",
                    error = sorted.firstOrNull()?.third?.error
                )
            }
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun deleteCloudBookData(bookId: String): Result<Unit> {
        val userId = try { uid() } catch (error: Exception) { return Result.failure(error) }
        return try {
            validateBookId(bookId)
            val pages = bookRef(userId, bookId).collection("pages").get().await()
            pages.documents.chunked(MAX_BATCH_OPERATIONS).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            try {
                sourceRef(userId, bookId).delete().await()
            } catch (storageError: com.google.firebase.storage.StorageException) {
                if (storageError.errorCode != com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) throw storageError
            }
            bookRef(userId, bookId).update(mapOf(
                "storagePath" to "", "sourceSizeBytes" to 0L, "sourceSha256" to "",
                "syncStatus" to "LOCAL", "cloudPageCount" to 0, "pagesSyncStatus" to "LOCAL",
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private data class PagePart(val count: Int, val text: String, val method: String, val error: String?)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
