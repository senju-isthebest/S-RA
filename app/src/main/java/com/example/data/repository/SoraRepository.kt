package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.BookPageDoc
import com.example.data.model.Chapter
import com.example.data.model.CitationEntry
import com.example.data.model.ExamRecord
import com.example.data.model.Flashcard
import com.example.data.model.FocusSession
import com.example.data.model.GlossaryEntry
import com.example.data.model.PlannerTask
import com.example.data.model.QuestionItem
import com.example.data.model.SmartRevisionItem
import com.example.data.model.StudyNote
import com.example.data.model.Subject
import com.example.data.model.SyncedUserPreferences
import com.example.data.model.TutorMessage
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SoraRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    private fun <T> observeCollection(
        uid: String,
        collectionName: String,
        clazz: Class<T>,
        subPath: String? = null
    ): Flow<List<T>> = flow {
        require(uid.isNotBlank()) { "UID must not be blank" }
        val ref = if (subPath == null) {
            userDoc(uid).collection(collectionName)
        } else {
            userDoc(uid).collection(collectionName).document(subPath.substringBefore("/")).collection(subPath.substringAfter("/"))
        }
        val path = if (subPath == null) "users/$uid/$collectionName" else "users/$uid/$collectionName/$subPath"
        emitAll(
            ref.snapshots()
                .map { snap -> snap.toObjects(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    val toLog = fsErr ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeProfile(uid: String): Flow<UserProfile?> = flow {
        require(uid.isNotBlank()) { "UID must not be blank" }
        val ref = userDoc(uid)
        emitAll(
            ref.snapshots()
                .map { snap ->
                    if (snap.exists()) snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    else null
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    val toLog = fsErr ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, ref.path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(): Result<Unit> {
        val current = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val ref = userDoc(current.uid)
        return try {
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to current.uid,
                    "displayName" to (current.displayName?.take(100) ?: "Student"),
                    "email" to (current.email?.take(200) ?: ""),
                    "studyStreakDays" to 1L,
                    "weeklyStudyMinutes" to 0L,
                    "dailyGoalMinutes" to 60L,
                    "lastStudyDateKey" to todayDateKey(),
                    "dashboardSections" to listOf("continue", "quick_actions", "revision_queue", "tasks", "exams", "weak_topics", "library"),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun updateDashboardSections(sections: List<String>): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val ref = userDoc(uid)
        return try {
            ref.update(
                mapOf(
                    "dashboardSections" to sections.take(15),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            Result.failure(e)
        }
    }

    fun observeSubjects(uid: String): Flow<List<Subject>> =
        observeCollection(uid, "subjects", Subject::class.java)

    fun observeBooks(uid: String): Flow<List<Book>> =
        observeCollection(uid, "books", Book::class.java)

    fun observeChapters(uid: String, bookId: String): Flow<List<Chapter>> =
        observeCollection(uid, "books", Chapter::class.java, "$bookId/chapters")

    fun observeGlossary(uid: String, bookId: String): Flow<List<GlossaryEntry>> =
        observeCollection(uid, "books", GlossaryEntry::class.java, "$bookId/glossary")

    fun observeQuestions(uid: String): Flow<List<QuestionItem>> =
        observeCollection(uid, "questions", QuestionItem::class.java)

    fun observeFlashcards(uid: String): Flow<List<Flashcard>> =
        observeCollection(uid, "flashcards", Flashcard::class.java)

    fun observeExams(uid: String): Flow<List<ExamRecord>> =
        observeCollection(uid, "exams", ExamRecord::class.java)

    fun observeNotes(uid: String): Flow<List<StudyNote>> =
        observeCollection(uid, "notes", StudyNote::class.java)

    fun observePlannerTasks(uid: String): Flow<List<PlannerTask>> =
        observeCollection(uid, "plannerTasks", PlannerTask::class.java)

    fun observeFocusSessions(uid: String): Flow<List<FocusSession>> =
        observeCollection(uid, "focusSessions", FocusSession::class.java)

    fun observeTutorMessages(uid: String): Flow<List<TutorMessage>> =
        observeCollection(uid, "tutorMessages", TutorMessage::class.java)

    fun observeCitations(uid: String): Flow<List<CitationEntry>> =
        observeCollection(uid, "citations", CitationEntry::class.java)

    // --- SUBJECTS ---
    suspend fun addSubject(subject: Subject): Result<String> = createDoc("subjects", subject.toCreateMap())

    suspend fun updateSubject(subjectId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("subjects", subjectId, fields)

    // --- BOOKS ---
    suspend fun createBookWithId(bookId: String, book: Book): Result<String> {
        var path = "users/unauth/books/$bookId"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection("books").document(bookId)
            path = ref.path
            ref.set(book.toCreateMap()).await()
            Result.success(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateBook(bookId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("books", bookId, fields)

    suspend fun deleteBook(bookId: String): Result<Unit> =
        deleteDoc("books", bookId)

    // --- SYNCED BOOK PAGES & NON-SECRET PREFERENCES (TASK 2) ---
    suspend fun saveBookPages(bookId: String, pages: List<BookPageContent>, clientUpdatedAtMillis: Long = System.currentTimeMillis()): Result<Unit> {
        if (pages.isEmpty()) return Result.success(Unit)
        var path = "users/unauth/books/$bookId/pages"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("books").document(bookId).collection("pages")
            path = col.path
            // Firestore batch limit is 500 writes; chunk safely at 350
            pages.chunked(350).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { page ->
                    val docId = "p_${page.pageNumber}"
                    val ref = col.document(docId)
                    val payload = BookPageDoc(
                        bookId = bookId,
                        pageNumber = page.pageNumber.toLong(),
                        text = page.text.take(12000),
                        extractionMethod = page.extractionMethod.ifBlank { "TEXT" },
                        error = page.error.orEmpty(),
                        clientUpdatedAtMillis = clientUpdatedAtMillis
                    ).toCreateMap()
                    batch.set(ref, payload)
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun fetchBookPages(bookId: String): Result<List<BookPageContent>> {
        var path = "users/unauth/books/$bookId/pages"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("books").document(bookId).collection("pages")
            path = col.path
            val snap = col.get().await()
            val list = snap.toObjects(BookPageDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .map { it.toBookPageContent() }
                .sortedBy { it.pageNumber }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    fun observeSyncedPreferences(uid: String): Flow<SyncedUserPreferences?> = flow {
        require(uid.isNotBlank()) { "UID must not be blank" }
        val ref = userDoc(uid).collection("preferences").document("app")
        emitAll(
            ref.snapshots()
                .map { snap ->
                    if (snap.exists()) snap.toObject(SyncedUserPreferences::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    else null
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    val toLog = fsErr ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, ref.path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveSyncedPreferences(prefs: SyncedUserPreferences): Result<Unit> {
        var path = "users/unauth/preferences/app"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection("preferences").document("app")
            path = ref.path
            val existing = ref.get().await()
            if (existing.exists()) {
                val updates = prefs.toCreateMap().toMutableMap().apply { remove("createdAt") }
                updates["updatedAt"] = FieldValue.serverTimestamp()
                ref.update(updates).await()
            } else {
                ref.set(prefs.toCreateMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- ACCOUNT DATA DELETION (TASK 5) ---
    suspend fun deleteAllUserCloudData(): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val root = userDoc(uid)
            val booksSnap = root.collection("books").get().await()
            for (bookDoc in booksSnap.documents) {
                deleteCollectionBatched(bookDoc.reference.collection("chapters"))
                deleteCollectionBatched(bookDoc.reference.collection("glossary"))
                deleteCollectionBatched(bookDoc.reference.collection("pages"))
                bookDoc.reference.delete().await()
            }
            val topCollections = listOf(
                "subjects", "questions", "flashcards", "exams",
                "notes", "plannerTasks", "focusSessions",
                "tutorMessages", "citations", "preferences"
            )
            topCollections.forEach { name ->
                deleteCollectionBatched(root.collection(name))
            }
            root.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid")
            Result.failure(e)
        }
    }

    private suspend fun deleteCollectionBatched(col: CollectionReference) {
        while (true) {
            val snap = col.limit(350).get().await()
            if (snap.isEmpty) break
            val batch = db.batch()
            snap.documents.forEach { doc -> batch.delete(doc.reference) }
            batch.commit().await()
            if (snap.size() < 350) break
        }
    }

    // --- CHAPTERS & GLOSSARY ---
    suspend fun saveChapter(bookId: String, chapter: Chapter): Result<String> {
        var path = "users/unauth/books/$bookId/chapters"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("books").document(bookId).collection("chapters")
            val ref = if (chapter.id.isNotBlank()) col.document(chapter.id) else col.document()
            path = ref.path
            val snap = ref.get().await()
            if (snap.exists()) {
                val updates = chapter.toCreateMap().toMutableMap().apply { remove("createdAt"); remove("bookId") }
                updates["updatedAt"] = FieldValue.serverTimestamp()
                ref.update(updates).await()
            } else {
                ref.set(chapter.copy(bookId = bookId).toCreateMap()).await()
            }
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateChapterFields(bookId: String, chapterId: String, fields: Map<String, Any>): Result<Unit> {
        var path = "users/unauth/books/$bookId/chapters/$chapterId"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection("books").document(bookId).collection("chapters").document(chapterId)
            path = ref.path
            val payload = fields.toMutableMap().apply { put("updatedAt", FieldValue.serverTimestamp()) }
            ref.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun addGlossaryEntries(bookId: String, entries: List<GlossaryEntry>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        var path = "users/unauth/books/$bookId/glossary"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("books").document(bookId).collection("glossary")
            path = "users/$uid/books/$bookId/glossary"
            val batch = db.batch()
            entries.forEach { entry ->
                val ref = col.document()
                batch.set(ref, entry.copy(bookId = bookId).toCreateMap())
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    // --- QUESTIONS ---
    suspend fun addQuestions(questions: List<QuestionItem>): Result<Unit> {
        if (questions.isEmpty()) return Result.success(Unit)
        var path = "users/unauth/questions"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("questions")
            path = "users/$uid/questions"
            val batch = db.batch()
            questions.forEach { q ->
                batch.set(col.document(), q.toCreateMap())
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateQuestion(questionId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("questions", questionId, fields)

    suspend fun deleteQuestion(questionId: String): Result<Unit> =
        deleteDoc("questions", questionId)

    suspend fun recordQuestionAttempt(question: QuestionItem, isCorrect: Boolean): Result<Unit> {
        val newAttempts = question.attempts + 1L
        val newCorrect = question.correctCount + if (isCorrect) 1L else 0L
        val newIncorrect = question.incorrectCount + if (!isCorrect) 1L else 0L
        return updateQuestion(
            question.id,
            mapOf(
                "attempts" to newAttempts,
                "correctCount" to newCorrect,
                "incorrectCount" to newIncorrect,
                "lastAnsweredCorrect" to isCorrect,
                "mistakeResolved" to isCorrect
            )
        )
    }

    // --- FLASHCARDS (SM-2 SPACED REPETITION) ---
    suspend fun addFlashcards(cards: List<Flashcard>): Result<Unit> {
        if (cards.isEmpty()) return Result.success(Unit)
        var path = "users/unauth/flashcards"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("flashcards")
            path = "users/$uid/flashcards"
            val batch = db.batch()
            cards.forEach { fc ->
                batch.set(col.document(), fc.toCreateMap())
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateFlashcard(cardId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("flashcards", cardId, fields)

    suspend fun deleteFlashcard(cardId: String): Result<Unit> =
        deleteDoc("flashcards", cardId)

    suspend fun rateFlashcardSm2(card: Flashcard, rating: String): Result<Unit> {
        // SM-2 adaptation: HARD (quality 2), MEDIUM (quality 4), EASY (quality 5)
        val quality = when (rating) {
            "EASY" -> 5
            "MEDIUM" -> 4
            else -> 2
        }
        val (newReps, newIntervalDays) = if (quality < 3) {
            0L to 1L
        } else {
            val reps = card.repetitions + 1L
            val interval = when (reps) {
                1L -> 1L
                2L -> 6L
                else -> (card.intervalDays * card.easeFactor).roundToInt().toLong().coerceAtLeast(1L)
            }
            reps to interval
        }
        val newEase = (card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))
            .coerceIn(1.3, 3.5)
        val nextReview = System.currentTimeMillis() + newIntervalDays * 86_400_000L

        return updateFlashcard(
            card.id,
            mapOf(
                "repetitions" to newReps,
                "intervalDays" to newIntervalDays,
                "easeFactor" to newEase,
                "nextReviewAtMillis" to nextReview,
                "lastRating" to rating
            )
        )
    }

    // --- EXAMS ---
    suspend fun createExam(exam: ExamRecord): Result<String> = createDoc("exams", exam.toCreateMap())

    suspend fun updateExamProgress(examId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("exams", examId, fields)

    // --- NOTES ---
    suspend fun createNote(note: StudyNote): Result<String> = createDoc("notes", note.toCreateMap())

    suspend fun updateNote(noteId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("notes", noteId, fields)

    suspend fun deleteNote(noteId: String): Result<Unit> =
        deleteDoc("notes", noteId)

    // --- PLANNER ---
    suspend fun createPlannerTask(task: PlannerTask): Result<String> =
        createDoc("plannerTasks", task.toCreateMap())

    suspend fun updatePlannerTask(taskId: String, fields: Map<String, Any>): Result<Unit> =
        updateDoc("plannerTasks", taskId, fields)

    suspend fun deletePlannerTask(taskId: String): Result<Unit> =
        deleteDoc("plannerTasks", taskId)

    // --- FOCUS SESSIONS ---
    suspend fun recordFocusSession(session: FocusSession): Result<String> {
        val res = createDoc("focusSessions", session.toCreateMap())
        if (res.isSuccess && session.durationMinutes > 0L) {
            val uid = auth.currentUser?.uid
            if (uid != null) {
                try {
                    val doc = userDoc(uid).get().await()
                    val today = todayDateKey()
                    val lastDay = doc.getString("lastStudyDateKey") ?: ""
                    val streak = (doc.getLong("studyStreakDays") ?: 0L) + if (lastDay != today) 1L else 0L
                    userDoc(uid).update(
                        mapOf(
                            "weeklyStudyMinutes" to FieldValue.increment(session.durationMinutes),
                            "studyStreakDays" to streak,
                            "lastStudyDateKey" to today,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
                }
            }
        }
        return res
    }

    // --- TUTOR MESSAGES & CITATIONS ---
    suspend fun addTutorMessage(msg: TutorMessage): Result<String> =
        createDoc("tutorMessages", msg.toCreateMap())

    suspend fun addCitation(citation: CitationEntry): Result<String> =
        createDoc("citations", citation.toCreateMap())

    suspend fun deleteCitation(citationId: String): Result<Unit> =
        deleteDoc("citations", citationId)

    // --- MASTERY & SMART REVISION ALGORITHMS (Performance-Based, Never Reading Time Alone) ---
    fun computeChapterMastery(
        chapterQuestions: List<QuestionItem>,
        chapterCards: List<Flashcard>
    ): Pair<Int, String> {
        val attempted = chapterQuestions.filter { it.attempts > 0L }
        val qAccuracy = if (attempted.isNotEmpty()) {
            val totalCorrect = attempted.sumOf { it.correctCount }.toDouble()
            val totalAttempts = attempted.sumOf { it.attempts }.toDouble().coerceAtLeast(1.0)
            (totalCorrect / totalAttempts) * 100.0
        } else null

        val reviewedCards = chapterCards.filter { it.lastRating != "UNSEEN" }
        val cardScore = if (reviewedCards.isNotEmpty()) {
            reviewedCards.map { card ->
                when (card.lastRating) {
                    "EASY" -> 100.0
                    "MEDIUM" -> 70.0
                    else -> 30.0
                }
            }.average()
        } else null

        val score = when {
            qAccuracy != null && cardScore != null -> (qAccuracy * 0.7 + cardScore * 0.3).roundToInt()
            qAccuracy != null -> qAccuracy.roundToInt()
            cardScore != null -> cardScore.roundToInt()
            else -> 0
        }.coerceIn(0, 100)

        val status = when {
            attempted.isEmpty() && reviewedCards.isEmpty() -> "NOT_STARTED"
            score >= 85 && attempted.size >= 3 -> "MASTERED"
            score >= 55 -> "REVIEWING"
            else -> "LEARNING"
        }
        return score to status
    }

    fun buildSmartRevisionQueue(
        books: List<Book>,
        chaptersByBook: Map<String, List<Chapter>>,
        questions: List<QuestionItem>,
        flashcards: List<Flashcard>,
        tasks: List<PlannerTask>
    ): List<SmartRevisionItem> {
        val now = System.currentTimeMillis()
        val queue = mutableListOf<SmartRevisionItem>()

        // 1. Repeated Mistakes (highest priority)
        val mistakes = questions.filter { !it.hidden && it.incorrectCount > 0 && !it.mistakeResolved }
            .groupBy { it.bookId to it.chapterNumber }
        mistakes.forEach { (key, list) ->
            val bookTitle = books.firstOrNull { it.id == key.first }?.title ?: "Textbook"
            queue.add(
                SmartRevisionItem(
                    id = "mistake_${key.first}_${key.second}",
                    title = "Mistake Review: $bookTitle (Ch. ${key.second})",
                    subtitle = "${list.size} unresolved question(s) missed previously",
                    reason = "Priority 1: Repeated errors detected",
                    priorityRank = 1,
                    bookId = key.first,
                    chapterNumber = key.second,
                    estimatedMinutes = (list.size * 3).coerceIn(5, 30),
                    itemType = "MISTAKE"
                )
            )
        }

        // 2. Due Spaced-Repetition Flashcards
        val dueCards = flashcards.filter { it.nextReviewAtMillis <= now }
            .groupBy { it.bookId to it.chapterNumber }
        dueCards.forEach { (key, list) ->
            val bookTitle = books.firstOrNull { it.id == key.first }?.title ?: "Deck"
            queue.add(
                SmartRevisionItem(
                    id = "fc_${key.first}_${key.second}",
                    title = "Due Flashcards: $bookTitle (Ch. ${key.second})",
                    subtitle = "${list.size} card(s) due for spaced repetition",
                    reason = "Priority 2: Scheduled forgetting-curve review",
                    priorityRank = 2,
                    bookId = key.first,
                    chapterNumber = key.second,
                    estimatedMinutes = (list.size * 1).coerceIn(5, 25),
                    itemType = "FLASHCARD"
                )
            )
        }

        // 3. Unstudied or low-mastery important chapters
        books.filter { !it.archived }.forEach { book ->
            chaptersByBook[book.id].orEmpty()
                .filter { it.masteryStatus == "NOT_STARTED" || it.masteryScore < 60 }
                .take(2)
                .forEach { ch ->
                    queue.add(
                        SmartRevisionItem(
                            id = "ch_${book.id}_${ch.chapterNumber}",
                            title = "${book.title}: Ch. ${ch.chapterNumber} — ${ch.title}",
                            subtitle = "Mastery ${ch.masteryScore}% • Est. ${ch.estimatedStudyMinutes}m",
                            reason = if (ch.masteryStatus == "NOT_STARTED") "Priority 3: Important unstudied chapter" else "Priority 3: Below 60% mastery",
                            priorityRank = 3,
                            bookId = book.id,
                            chapterNumber = ch.chapterNumber,
                            estimatedMinutes = ch.estimatedStudyMinutes.toInt().coerceIn(10, 60),
                            itemType = "CHAPTER"
                        )
                    )
                }
        }

        // 4. Upcoming Exams within 7 days
        val sevenDaysLater = now + 7L * 86_400_000L
        tasks.filter { !it.completed && it.taskType == "EXAM" && it.dueDateMillis in now..sevenDaysLater }
            .forEach { examTask ->
                val daysLeft = ((examTask.dueDateMillis - now) / 86_400_000L).coerceAtLeast(0L)
                queue.add(
                    SmartRevisionItem(
                        id = "exam_${examTask.id}",
                        title = "Exam Prep: ${examTask.title}",
                        subtitle = "${examTask.subjectName} • Due in $daysLeft day(s)",
                        reason = "Priority 4: Upcoming exam deadline",
                        priorityRank = 4,
                        bookId = examTask.bookId,
                        chapterNumber = examTask.chapterNumber,
                        estimatedMinutes = examTask.estimatedMinutes.toInt(),
                        itemType = "EXAM_PREP"
                    )
                )
            }

        return queue.sortedBy { it.priorityRank }
    }

    private suspend fun createDoc(collection: String, payload: Map<String, Any>): Result<String> {
        var path = "users/unauth/$collection"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection(collection).document()
            path = ref.path
            ref.set(payload).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    private suspend fun updateDoc(collection: String, docId: String, fields: Map<String, Any>): Result<Unit> {
        var path = "users/unauth/$collection/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank()) { "Document ID cannot be blank" }
            val ref = userDoc(uid).collection(collection).document(docId)
            path = ref.path
            val payload = fields.toMutableMap().apply {
                put("updatedAt", FieldValue.serverTimestamp())
            }
            ref.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private suspend fun deleteDoc(collection: String, docId: String): Result<Unit> {
        var path = "users/unauth/$collection/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank()) { "Document ID cannot be blank" }
            val ref = userDoc(uid).collection(collection).document(docId)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun todayDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
