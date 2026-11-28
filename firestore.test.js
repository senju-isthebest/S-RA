const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_student_1";
const BOB_UID = "bob_student_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any SORA user data", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("books").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("questions").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("books")
      .doc("book_1")
      .set({
        title: "Hack",
        subjectName: "CS",
        sourceType: "PDF",
        localBookId: "local_1",
        totalPages: 10,
        processedPages: 10,
        currentPage: 1,
        extractionStatus: "COMPLETE",
        archived: false,
        masteryScore: 0,
        createdAt: new Date(Date.now() - 1000),
        updatedAt: new Date(Date.now() - 1000),
      })
  );
});

test("User profile creation, update, and cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.edu",
      studyStreakDays: 3,
      weeklyStudyMinutes: 120,
      dailyGoalMinutes: 60,
      lastStudyDateKey: "2026-11-27",
      dashboardSections: ["continue", "tasks", "exams"],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      weeklyStudyMinutes: 150,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Cross-user read/write must fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      studyStreakDays: 99,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Future timestamp must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      weeklyStudyMinutes: 200,
      updatedAt: new Date(Date.now() + 120000),
    })
  );
});

test("Book, Chapter, and Glossary CRUD mirrors Kotlin payloads & rejects ghost fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 3000);

  const bookRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("books")
    .doc("book_bio_1");

  await assertSucceeds(
    bookRef.set({
      title: "Molecular Cell Biology",
      author: "Lodish",
      subjectId: "subj_1",
      subjectName: "Biology",
      semester: "Fall 2026",
      folder: "Core",
      sourceType: "PDF",
      fileName: "cell_bio.pdf",
      localBookId: "book_bio_1",
      totalPages: 42,
      processedPages: 42,
      currentPage: 1,
      failedPages: [],
      extractionStatus: "COMPLETE",
      overview: "Comprehensive overview",
      archived: false,
      masteryScore: 0,
      bookmarkedPages: [1, 5],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject ghost field "id" (mirrors @DocumentId exclusion)
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("books")
      .doc("book_ghost")
      .set({
        id: "book_ghost",
        title: "Ghost Book",
        subjectName: "Math",
        sourceType: "PDF",
        localBookId: "book_ghost",
        totalPages: 10,
        processedPages: 10,
        currentPage: 1,
        extractionStatus: "COMPLETE",
        archived: false,
        masteryScore: 0,
        createdAt: now,
        updatedAt: now,
      })
  );

  // Chapter create & partial update
  const chapRef = bookRef.collection("chapters").doc("ch_1");
  await assertSucceeds(
    chapRef.set({
      bookId: "book_bio_1",
      chapterNumber: 1,
      title: "Membrane Structure",
      startPage: 1,
      endPage: 18,
      estimatedStudyMinutes: 35,
      masteryScore: 40,
      masteryStatus: "LEARNING",
      learningObjectives: ["Understand lipid bilayers"],
      keyConcepts: ["Phospholipid", "Fluid mosaic"],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    chapRef.update({
      masteryScore: 75,
      masteryStatus: "REVIEWING",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Immutable createdAt cannot be modified
  await assertFails(
    chapRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Glossary entry
  await assertSucceeds(
    bookRef.collection("glossary").doc("term_1").set({
      bookId: "book_bio_1",
      chapterNumber: 1,
      term: "Phospholipid",
      definition: "Amphipathic lipid forming bilayers.",
      simplifiedExplanation: "Fat molecule with water-loving head and water-fearing tail.",
      example: "Cell plasma membrane.",
      pageReference: 3,
      relatedTerms: ["Bilayer"],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Cross-user list & get denied
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("books").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("books")
      .doc("book_bio_1")
      .collection("chapters")
      .get()
  );
});

test("Questions, Flashcards, Notes, PlannerTasks, FocusSessions, TutorMessages, Citations", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("questions")
      .doc("q_1")
      .set({
        bookId: "book_bio_1",
        bookTitle: "Molecular Cell Biology",
        chapterNumber: 1,
        section: "1.2",
        topic: "Membranes",
        sourcePage: 4,
        difficulty: "MEDIUM",
        questionType: "MCQ",
        questionText: "Which property allows membrane proteins to diffuse laterally?",
        options: ["Fluidity", "Rigidity", "Covalent locking", "Dehydration"],
        correctAnswer: "Fluidity",
        explanation: "Lipid bilayers behave as two-dimensional fluids.",
        marks: 2,
        bookmarked: false,
        hidden: false,
        reported: false,
        attempts: 0,
        correctCount: 0,
        incorrectCount: 0,
        mistakeResolved: false,
        aiGenerated: true,
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("flashcards")
      .doc("fc_1")
      .set({
        bookId: "book_bio_1",
        chapterNumber: 1,
        deckName: "Chapter 1",
        format: "TERM_DEF",
        front: "Amphipathic",
        back: "Having both hydrophilic and hydrophobic regions.",
        pageReference: 3,
        bookmarked: false,
        easeFactor: 2.5,
        intervalDays: 1,
        repetitions: 0,
        nextReviewAtMillis: Date.now(),
        lastRating: "UNSEEN",
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .doc("note_1")
      .set({
        bookId: "book_bio_1",
        subjectName: "Biology",
        chapterNumber: 1,
        linkedPage: 4,
        title: "Bilayer Notes",
        contentMarkdown: "# Membrane\n- Hydrophilic heads face water",
        tags: ["chapter1"],
        bookmarked: true,
        quickNote: false,
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("plannerTasks")
      .doc("task_1")
      .set({
        title: "Midterm Review",
        taskType: "EXAM",
        subjectName: "Biology",
        dueDateMillis: Date.now() + 86400000,
        estimatedMinutes: 60,
        priority: "HIGH",
        completed: false,
        recurring: "NONE",
        confidenceRating: 3,
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("focusSessions")
      .doc("fs_1")
      .set({
        subjectName: "Biology",
        bookId: "book_bio_1",
        chapterNumber: 1,
        mode: "POMODORO",
        durationMinutes: 25,
        goalText: "Finish Chapter 1 summary",
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("tutorMessages")
      .doc("tm_1")
      .set({
        bookId: "book_bio_1",
        chapterNumber: 1,
        pageNumber: 3,
        mode: "EXPLAIN",
        role: "USER",
        text: "Explain the fluid mosaic model.",
        citations: ["Ch. 1, p. 3"],
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("citations")
      .doc("cit_1")
      .set({
        bookId: "book_bio_1",
        style: "APA",
        authors: "Lodish, H.",
        title: "Molecular Cell Biology",
        year: "2021",
        publisher: "Macmillan",
        pages: "1-42",
        formattedText: "Lodish, H. (2021). Molecular Cell Biology (pp. 1-42). Macmillan.",
        createdAt: now,
        updatedAt: now,
      })
  );
});

test("Exams enforce terminal SUBMITTED state lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 3000);
  const examRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("exams")
    .doc("exam_1");

  await assertSucceeds(
    examRef.set({
      title: "Bio Quick Quiz",
      preset: "QUICK_QUIZ",
      bookIds: ["book_bio_1"],
      questionIds: ["q_1"],
      durationMinutes: 15,
      totalMarks: 2,
      allowRevisit: true,
      showExplanationEach: false,
      status: "IN_PROGRESS",
      answersJson: "{}",
      flaggedQuestionIds: [],
      score: 0,
      percentage: 0.0,
      correctCount: 0,
      incorrectCount: 0,
      unansweredCount: 1,
      timeSpentSeconds: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Submit exam
  await assertSucceeds(
    examRef.update({
      status: "SUBMITTED",
      score: 2,
      percentage: 100.0,
      correctCount: 1,
      incorrectCount: 0,
      unansweredCount: 0,
      timeSpentSeconds: 120,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Subsequent tampering after submission must fail
  await assertFails(
    examRef.update({
      score: 0,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Synced BookPageDoc and SyncedUserPreferences enforce ownership and block secrets", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  const pageRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("books")
    .doc("book_bio_1")
    .collection("pages")
    .doc("p_1");

  await assertSucceeds(
    pageRef.set({
      bookId: "book_bio_1",
      pageNumber: 1,
      text: "Lipid bilayers separate aqueous compartments.",
      extractionMethod: "PDF_TEXT",
      error: "",
      clientUpdatedAtMillis: Date.now() - 2000,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("books").doc("book_bio_1").collection("pages").get());

  const prefRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("preferences")
    .doc("app");

  await assertSucceeds(
    prefRef.set({
      themeMode: "DARK",
      languageCode: "ar",
      pomodoroWorkMinutes: 25,
      pomodoroShortBreakMinutes: 5,
      pomodoroLongBreakMinutes: 15,
      ambientFocusSound: true,
      aiStrategy: "GEMINI_FIRST",
      aiFallbackEnabled: true,
      ollamaModel: "qwen2.5:3b",
      geminiModel: "gemini-3.8-flash",
      aiTimeoutMs: 60000,
      clientUpdatedAtMillis: Date.now() - 2000,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Storing API keys in cloud preferences must be blocked
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("preferences")
      .doc("bad_secret")
      .set({
        themeMode: "DARK",
        languageCode: "en",
        pomodoroWorkMinutes: 25,
        pomodoroShortBreakMinutes: 5,
        pomodoroLongBreakMinutes: 15,
        ambientFocusSound: false,
        aiStrategy: "GEMINI_FIRST",
        aiFallbackEnabled: true,
        ollamaModel: "qwen2.5:3b",
        geminiModel: "gemini-3.8-flash",
        geminiApiKey: "AIzaSecretLeak",
        aiTimeoutMs: 60000,
        clientUpdatedAtMillis: Date.now() - 2000,
        createdAt: now,
        updatedAt: now,
      })
  );
});

