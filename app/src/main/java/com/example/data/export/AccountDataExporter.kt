package com.example.data.export

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * Exports the signed-in user's Firestore records to a shareable JSON file.
 * Original textbook binaries in Firebase Storage are intentionally not copied;
 * their metadata remains part of each exported book record.
 */
class AccountDataExporter(
    context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val appContext = context.applicationContext

    suspend fun exportToCache(): Result<File> = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Sign in before exporting account data."))

        try {
            val userRef = firestore.collection("users").document(uid)
            val export = JSONObject()
                .put("format", "sora-account-export")
                .put("schemaVersion", 1)
                .put("exportedAtEpochMillis", System.currentTimeMillis())
                .put("userId", uid)
                .put("includesStorageSourceFiles", false)

            val profile = userRef.get().await()
            export.put("profile", jsonObject(profile.data.orEmpty()))

            val rootCollections = listOf(
                "subjects", "questions", "flashcards", "exams", "notes",
                "plannerTasks", "focusSessions", "tutorMessages", "citations"
            )
            rootCollections.forEach { collectionName ->
                export.put(collectionName, documentsToJson(userRef.collection(collectionName).get().await()))
            }

            val books = userRef.collection("books").get().await()
            val booksJson = JSONArray()
            for (book in books.documents) {
                val bookJson = JSONObject()
                    .put("id", book.id)
                    .put("data", jsonObject(book.data.orEmpty()))
                listOf("pages", "chapters", "glossary").forEach { childCollection ->
                    bookJson.put(
                        childCollection,
                        documentsToJson(book.reference.collection(childCollection).get().await())
                    )
                }
                booksJson.put(bookJson)
            }
            export.put("books", booksJson)

            val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, "sora-account-export-${System.currentTimeMillis()}.json")
            file.writeText(export.toString(2), Charsets.UTF_8)
            Result.success(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun documentsToJson(snapshot: QuerySnapshot): JSONArray =
        JSONArray().apply {
            snapshot.documents.forEach { document ->
                put(
                    JSONObject()
                        .put("id", document.id)
                        .put("data", jsonObject(document.data.orEmpty()))
                )
            }
        }

    private fun jsonObject(values: Map<String, Any?>): JSONObject =
        JSONObject().apply {
            values.forEach { (key, value) -> put(key, jsonValue(value)) }
        }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Timestamp -> JSONObject()
            .put("_type", "timestamp")
            .put("epochMillis", value.toDate().time)
        is Date -> value.time
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nested) ->
                if (key is String) put(key, jsonValue(nested))
            }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is ByteArray -> android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
        is String, is Number, is Boolean, is JSONObject, is JSONArray -> value
        else -> value.toString()
    }
}
