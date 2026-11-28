import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  generateWithRouter,
  testGeminiConnection,
  testOllamaConnection,
  validateOllamaEndpoint
} from './ai/aiRouter';
import { extractPdfTextFromArrayBuffer } from './engine/pdfExtractor';
import {
  evaluateAnswerOffline,
  nextSm2Schedule,
  parseUploadedTextBook,
  retrievePassages
} from './engine/textbookEngine';
import { applyDocumentDirection, t } from './i18n/translations';
import {
  ConflictResolver,
  OfflineWorkspaceStore,
  WebSessionSecretStore,
  flushSyncQueueWithRetry
} from './storage/offlineStore';
import {
  FirebaseSyncError,
  deleteAllCloudUserData,
  deleteFirebaseAccount,
  ensureFreshSession,
  ensureUserProfile,
  isFirebaseSyncConfigured,
  pullWorkspaceFromFirestore,
  pushSyncOperationToFirestore,
  readFirebaseWebEnv,
  signInWithEmail,
  signUpWithEmail
} from './sync/firebaseBridge';
import type {
  AiStrategy,
  AnswerEvaluation,
  AppLanguage,
  AuthSession,
  Book,
  BookPage,
  ExamRecord,
  Flashcard,
  QuestionItem,
  RetrievedPassage,
  SoraWorkspaceState,
  SyncOperation
} from './types/sora';

type NavTab =
  | 'dashboard'
  | 'library'
  | 'reader'
  | 'exams'
  | 'flashcards'
  | 'notes'
  | 'toolbox'
  | 'security';

const store = new OfflineWorkspaceStore();
const secretStore = new WebSessionSecretStore();

/**
 * Deterministic recursive-descent arithmetic evaluator (+ - * / ^ and parentheses).
 * Never uses eval() or Function().
 */
export function evaluateMathExpression(expr: string): string {
  const input = expr.replace(/\s+/g, '');
  if (!input || /[^0-9+\-*/^().]/.test(input)) return 'Invalid expression';

  let pos = 0;

  function parseExpression(): number {
    let val = parseTerm();
    while (pos < input.length && (input[pos] === '+' || input[pos] === '-')) {
      const op = input[pos++];
      const rhs = parseTerm();
      val = op === '+' ? val + rhs : val - rhs;
    }
    return val;
  }

  function parseTerm(): number {
    let val = parsePower();
    while (pos < input.length && (input[pos] === '*' || input[pos] === '/')) {
      const op = input[pos++];
      const rhs = parsePower();
      if (op === '/') {
        if (rhs === 0) throw new Error('Division by zero');
        val /= rhs;
      } else {
        val *= rhs;
      }
    }
    return val;
  }

  function parsePower(): number {
    const base = parseUnary();
    if (pos < input.length && input[pos] === '^') {
      pos++;
      const exp = parsePower();
      return Math.pow(base, exp);
    }
    return base;
  }

  function parseUnary(): number {
    if (input[pos] === '+') {
      pos++;
      return parseUnary();
    }
    if (input[pos] === '-') {
      pos++;
      return -parseUnary();
    }
    return parsePrimary();
  }

  function parsePrimary(): number {
    if (input[pos] === '(') {
      pos++;
      const inner = parseExpression();
      if (input[pos] !== ')') throw new Error('Unbalanced parentheses');
      pos++;
      return inner;
    }
    const start = pos;
    let seenDot = false;
    while (pos < input.length && ((input[pos] >= '0' && input[pos] <= '9') || input[pos] === '.')) {
      if (input[pos] === '.') {
        if (seenDot) throw new Error('Multiple decimals');
        seenDot = true;
      }
      pos++;
    }
    if (start === pos) throw new Error('Expected number');
    const num = Number(input.slice(start, pos));
    if (!Number.isFinite(num)) throw new Error('Invalid number');
    return num;
  }

  try {
    const result = parseExpression();
    if (pos !== input.length || !Number.isFinite(result)) return 'Invalid expression';
    return String(Math.round(result * 100000) / 100000);
  } catch {
    return 'Invalid expression';
  }
}

export default function App() {
  const [workspace, setWorkspace] = useState<SoraWorkspaceState>(() => store.loadWorkspace());
  const [authSession, setAuthSession] = useState<AuthSession | null>(() => store.loadAuthSession());
  const [syncState, setSyncState] = useState(() => store.loadSyncMeta(store.loadAuthSession()?.uid));
  const [activeTab, setActiveTab] = useState<NavTab>('dashboard');
  const [selectedBookId, setSelectedBookId] = useState<string>(() => store.loadWorkspace().books[0]?.id ?? '');
  const [banner, setBanner] = useState<string>('');

  // Ephemeral runtime Gemini API key (memory / optional WebCrypto sessionStorage, NEVER localStorage)
  const [geminiKeyInput, setGeminiKeyInput] = useState<string>(() => secretStore.getMemorySecret('gemini_api_key'));
  const [aiStatusMsg, setAiStatusMsg] = useState<string>('');

  // Auth form state
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authDisplayName, setAuthDisplayName] = useState('');
  const [authBusy, setAuthBusy] = useState(false);
  const [deleteConfirmText, setDeleteConfirmText] = useState('');

  // Library upload form
  const [uploadTitle, setUploadTitle] = useState('');
  const [uploadSubject, setUploadSubject] = useState('Physics');
  const [uploadRawText, setUploadRawText] = useState('');
  const [extractedPdfPages, setExtractedPdfPages] = useState<BookPage[] | undefined>(undefined);
  const [pdfProgress, setPdfProgress] = useState<{ processed: number; total: number } | null>(null);

  // Reader & Full-Book Q&A / Analysis
  const [highlightInput, setHighlightInput] = useState('');
  const [qaQuery, setQaQuery] = useState('');
  const [qaReply, setQaReply] = useState('');
  const [qaPassages, setQaPassages] = useState<RetrievedPassage[]>([]);
  const [qaBusy, setQaBusy] = useState(false);
  const [fullAnalysisBusy, setFullAnalysisBusy] = useState(false);
  const fullAnalysisCancelledRef = useRef(false);

  // Exam engine UI
  const [examChapters, setExamChapters] = useState<number[]>([]);
  const [examTypes, setExamTypes] = useState<string[]>([]);
  const [activeExamId, setActiveExamId] = useState<string | null>(null);
  const [examAnswers, setExamAnswers] = useState<Record<string, string>>({});

  // Flashcard UI
  const [cardIdx, setCardIdx] = useState(0);
  const [cardFlipped, setCardFlipped] = useState(false);
  const [newCardFront, setNewCardFront] = useState('');
  const [newCardBack, setNewCardBack] = useState('');

  // Notes UI
  const [noteTitle, setNoteTitle] = useState('');
  const [noteBody, setNoteBody] = useState('');

  // Toolbox UI
  const [calcExpr, setCalcExpr] = useState('(12 + 8) * 3.5');
  const [calcOutput, setCalcOutput] = useState('70');

  const lang = workspace.settings.language;
  const currentUid = authSession?.uid;
  const firebaseEnv = useMemo(() => readFirebaseWebEnv(), []);
  const firebaseConfigured = isFirebaseSyncConfigured(firebaseEnv);

  useEffect(() => {
    applyDocumentDirection(lang);
    store.saveWorkspace(workspace);
  }, [lang, workspace]);

  useEffect(() => {
    setSyncState(store.loadSyncMeta(currentUid));
  }, [currentUid]);

  const selectedBook: Book | undefined = useMemo(
    () => workspace.books.find((b) => b.id === selectedBookId) ?? workspace.books[0],
    [workspace.books, selectedBookId]
  );

  const enqueueSync = (opType: SyncOperation['opType'], entityId: string, payload: unknown) => {
    const queue = store.enqueueOperation(
      {
        id: `op_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        opType,
        entityId,
        payloadJson: JSON.stringify(payload),
        createdAtMillis: Date.now(),
        attempts: 0,
        lastError: ''
      },
      currentUid
    );
    const deadCount = store.loadDeadLetterQueue(currentUid).length;
    const nextMeta = {
      ...store.loadSyncMeta(currentUid),
      status: deadCount > 0 ? ('ERROR' as const) : ('OFFLINE_QUEUED' as const),
      pendingCount: queue.length + deadCount,
      deadLetterCount: deadCount,
      localOnlyMode: workspace.settings.localOnlyPrivacyMode
    };
    store.saveSyncMeta(nextMeta, currentUid);
    setSyncState(nextMeta);
  };

  const mergeRemoteWorkspace = (remote: Awaited<ReturnType<typeof pullWorkspaceFromFirestore>>) => {
    let conflicts = 0;
    setWorkspace((prev) => {
      const bookMap = new Map(prev.books.map((b) => [b.id, b]));
      for (const rb of remote.books) {
        const lb = bookMap.get(rb.id);
        if (!lb) {
          bookMap.set(rb.id, rb);
        } else {
          const merged = ConflictResolver.mergeBook(lb, rb);
          if (merged.hadConflict) conflicts++;
          bookMap.set(rb.id, merged.book);
        }
      }

      const noteMap = new Map(prev.notes.map((n) => [n.id, n]));
      for (const rn of remote.notes) {
        const ln = noteMap.get(rn.id);
        if (!ln || rn.updatedAtMillis > ln.updatedAtMillis) {
          if (ln && rn.contentMarkdown !== ln.contentMarkdown) conflicts++;
          noteMap.set(rn.id, rn);
        }
      }

      const examMap = new Map(prev.exams.map((e) => [e.id, e]));
      for (const re of remote.exams) {
        if (!examMap.has(re.id)) examMap.set(re.id, re);
      }

      return {
        ...prev,
        books: Array.from(bookMap.values()),
        notes: Array.from(noteMap.values()),
        exams: Array.from(examMap.values())
      };
    });
    return conflicts;
  };

  const handleSyncNow = async () => {
    if (workspace.settings.localOnlyPrivacyMode) {
      setBanner('Local-Only Privacy Mode is active; cloud sync is disabled.');
      return;
    }
    if (!firebaseConfigured) {
      const errState = {
        ...store.loadSyncMeta(currentUid),
        status: 'ERROR' as const,
        lastError: 'Firebase web env vars are not configured; queued changes remain saved locally.'
      };
      store.saveSyncMeta(errState, currentUid);
      setSyncState(errState);
      setBanner(errState.lastError);
      return;
    }
    if (!authSession) {
      const errState = {
        ...store.loadSyncMeta(currentUid),
        status: 'ERROR' as const,
        lastError: 'Sign in first to sync queued changes with SŌRA Cloud.'
      };
      store.saveSyncMeta(errState, currentUid);
      setSyncState(errState);
      setBanner(errState.lastError);
      return;
    }

    const isOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;
    try {
      const freshSession = await ensureFreshSession(authSession, firebaseEnv);
      if (freshSession.idToken !== authSession.idToken) {
        store.saveAuthSession(freshSession);
        setAuthSession(freshSession);
      }
      await ensureUserProfile(freshSession, firebaseEnv);

      const pushResult = await flushSyncQueueWithRetry(
        store,
        async (op) => pushSyncOperationToFirestore(op, freshSession, firebaseEnv),
        isOnline,
        workspace.settings.localOnlyPrivacyMode,
        4,
        freshSession.uid
      );

      let conflicts = pushResult.conflictsResolved;
      if (isOnline) {
        const remote = await pullWorkspaceFromFirestore(freshSession, firebaseEnv);
        conflicts += mergeRemoteWorkspace(remote);
      }

      const finalState = {
        ...pushResult,
        status:
          pushResult.status === 'SYNCED' && conflicts > 0
            ? ('CONFLICT_RESOLVED' as const)
            : pushResult.status,
        conflictsResolved: conflicts
      };
      store.saveSyncMeta(finalState, freshSession.uid);
      setSyncState(finalState);
      setBanner(
        finalState.pendingCount === 0
          ? `Cloud sync complete (${conflicts} conflict(s) reconciled).`
          : `Sync paused/failed: ${finalState.lastError || finalState.status}`
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Cloud sync failed';
      const errState = {
        ...store.loadSyncMeta(currentUid),
        status: 'ERROR' as const,
        lastError: msg
      };
      store.saveSyncMeta(errState, currentUid);
      setSyncState(errState);
      setBanner(msg);
    }
  };

  const handleAuthAction = async (mode: 'signin' | 'signup') => {
    if (!firebaseConfigured) {
      setBanner(t(lang, 'authNotConfigured'));
      return;
    }
    if (!authEmail.trim() || !authPassword) {
      setBanner('Email and password are required.');
      return;
    }
    setAuthBusy(true);
    try {
      const session =
        mode === 'signup'
          ? await signUpWithEmail(authEmail, authPassword, authDisplayName, firebaseEnv)
          : await signInWithEmail(authEmail, authPassword, firebaseEnv);
      store.saveAuthSession(session);
      setAuthSession(session);
      setAuthPassword('');
      setSyncState(store.loadSyncMeta(session.uid));
      const remote = await pullWorkspaceFromFirestore(session, firebaseEnv);
      mergeRemoteWorkspace(remote);
      setBanner(`Signed in as ${session.email}`);
    } catch (err) {
      setBanner(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setAuthBusy(false);
    }
  };

  const handleSignOut = () => {
    store.saveAuthSession(null);
    secretStore.clearAll();
    setGeminiKeyInput('');
    setAuthSession(null);
    setDeleteConfirmText('');
    setSyncState(store.loadSyncMeta('guest'));
    setBanner('Signed out. Ephemeral session secrets cleared.');
  };

  const handleDeleteAccount = async () => {
    if (!authSession || deleteConfirmText.trim() !== 'DELETE') return;
    setAuthBusy(true);
    try {
      const fresh = await ensureFreshSession(authSession, firebaseEnv);
      const wipe = await deleteAllCloudUserData(fresh, firebaseEnv);
      if (!wipe.ok) {
        setBanner(`Account deletion aborted; cloud cleanup incomplete: ${wipe.errors[0] ?? 'unknown error'}`);
        return;
      }
      await deleteFirebaseAccount(fresh, firebaseEnv);
      store.clearAll(fresh.uid);
      secretStore.clearAll();
      setGeminiKeyInput('');
      setAuthSession(null);
      setDeleteConfirmText('');
      setWorkspace(store.loadWorkspace());
      setSyncState(store.loadSyncMeta('guest'));
      setSelectedBookId('');
      setBanner('Account and all cloud & local data permanently deleted.');
    } catch (err) {
      if (err instanceof FirebaseSyncError && err.code === 'REAUTH_REQUIRED') {
        setBanner(err.message);
      } else {
        setBanner(err instanceof Error ? err.message : 'Failed to delete account');
      }
    } finally {
      setAuthBusy(false);
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!uploadTitle) setUploadTitle(file.name.replace(/\.[^.]+$/, ''));

    if (file.name.toLowerCase().endsWith('.pdf')) {
      setPdfProgress({ processed: 0, total: 1 });
      try {
        const buf = await file.arrayBuffer();
        const res = await extractPdfTextFromArrayBuffer(buf, (processed, total) => {
          setPdfProgress({ processed, total });
        });
        if (!res.ok) {
          setExtractedPdfPages(undefined);
          const msg =
            res.errorCode === 'ENCRYPTED_PDF'
              ? t(lang, 'pdfErrorEncrypted')
              : res.errorCode === 'EMPTY_TEXT_LAYER'
                ? t(lang, 'pdfErrorEmptyText')
                : res.errorCode === 'FILE_TOO_LARGE'
                  ? t(lang, 'pdfErrorTooLarge')
                  : t(lang, 'pdfErrorCorrupt');
          setBanner(msg);
          return;
        }
        setExtractedPdfPages(res.pages);
        setUploadRawText(res.fullText.slice(0, 300_000));
        setBanner(`Extracted ${res.pageCount} PDF page(s) cleanly.`);
      } finally {
        setPdfProgress(null);
      }
    } else {
      const rawText = await file.text();
      setExtractedPdfPages(undefined);
      setUploadRawText(rawText.slice(0, 300_000));
    }
  };

  const handleImportBook = () => {
    if (!uploadRawText.trim()) return;
    const { book, starterQuestions, starterCards } = parseUploadedTextBook(
      uploadTitle.trim() || 'Study Textbook',
      uploadSubject.trim() || 'General',
      uploadRawText,
      extractedPdfPages
    );
    setWorkspace((prev) => ({
      ...prev,
      books: [book, ...prev.books],
      questions: [...starterQuestions, ...prev.questions],
      flashcards: [...starterCards, ...prev.flashcards]
    }));
    setSelectedBookId(book.id);
    enqueueSync('UPSERT_BOOK', book.id, {
      title: book.title,
      subjectName: book.subjectName,
      totalPages: book.totalPages,
      processedPages: book.totalPages,
      currentPage: book.currentPage,
      overview: book.overview
    });
    enqueueSync('SYNC_BOOK_PAGES', book.id, {
      pages: book.pages.slice(0, 200),
      extractionMethod: extractedPdfPages ? 'PDFJS_TEXT' : 'PLAIN_TEXT',
      clientUpdatedAtMillis: Date.now()
    });
    setUploadTitle('');
    setUploadRawText('');
    setExtractedPdfPages(undefined);
    setActiveTab('reader');
  };

  const handleDeleteBook = (bookId: string) => {
    setWorkspace((prev) => {
      const remainingBooks = prev.books.filter((b) => b.id !== bookId);
      if (selectedBookId === bookId) {
        setSelectedBookId(remainingBooks[0]?.id ?? '');
      }
      return {
        ...prev,
        books: remainingBooks,
        questions: prev.questions.filter((q) => q.bookId !== bookId),
        flashcards: prev.flashcards.filter((c) => c.bookId !== bookId),
        notes: prev.notes.filter((n) => n.bookId !== bookId)
      };
    });
    enqueueSync('DELETE_BOOK', bookId, { deletedAtMillis: Date.now() });
    setBanner('Textbook deleted locally and queued for cloud deletion.');
  };

  const updateCurrentBook = (mutator: (b: Book) => Book) => {
    if (!selectedBook) return;
    const updated = mutator(selectedBook);
    setWorkspace((prev) => ({
      ...prev,
      books: prev.books.map((b) => (b.id === updated.id ? ConflictResolver.mergeBook(b, updated).book : b))
    }));
    enqueueSync('UPSERT_BOOK', updated.id, {
      title: updated.title,
      subjectName: updated.subjectName,
      totalPages: updated.totalPages,
      processedPages: updated.totalPages,
      currentPage: updated.currentPage,
      bookmarkedPages: updated.bookmarkedPages,
      highlightsJson: JSON.stringify(updated.highlightsByPage),
      overview: updated.overview
    });
  };

  const handleAskBook = async () => {
    if (!selectedBook || !qaQuery.trim()) return;
    setQaBusy(true);
    const hits = retrievePassages(qaQuery, selectedBook.pages, selectedBook.chapters, 5, 6000);
    setQaPassages(hits);
    const groundedContext = hits
      .map((p) => `[Ch.${p.chapterNumber || 1}, p.${p.pageNumber}] ${p.excerpt}`)
      .join('\n\n');
    try {
      const reply = await generateWithRouter(
        `Textbook: ${selectedBook.title}\nQuestion: ${qaQuery}\n\nRetrieved Passages:\n${groundedContext || selectedBook.pages[selectedBook.currentPage - 1]?.text || ''}`,
        `You are SŌRA AI Tutor. Answer accurately using the cited passages in ${lang === 'ar' ? 'Arabic' : 'English'} and cite [p.X].`,
        workspace.settings.aiConfig,
        fetch,
        {
          localOnlyPrivacyMode: workspace.settings.localOnlyPrivacyMode,
          runtimeGeminiApiKey: geminiKeyInput
        }
      );
      setQaReply(`${reply.text}\n\n(${reply.provider} • ${reply.model})`);
    } catch (err) {
      const reason = err instanceof Error ? err.message : '';
      setQaReply(
        hits.length > 0
          ? `${reason ? `[${reason}]\n\n` : ''}${hits.map((h) => `• [Ch.${h.chapterNumber || 1}, p.${h.pageNumber}] ${h.excerpt.slice(0, 220)}…`).join('\n\n')}`
          : reason || 'Configure local Ollama or a session Gemini key in Sync & Security.'
      );
    } finally {
      setQaBusy(false);
    }
  };

  const handleAnalyzeAllChapters = async () => {
    if (!selectedBook || fullAnalysisBusy) return;
    fullAnalysisCancelledRef.current = false;
    setFullAnalysisBusy(true);
    try {
      const updatedChapters = [...selectedBook.chapters];
      for (let i = 0; i < updatedChapters.length; i++) {
        if (fullAnalysisCancelledRef.current) break;
        const ch = updatedChapters[i];
        const chText = selectedBook.pages
          .filter((p) => p.pageNumber >= ch.startPage && p.pageNumber <= ch.endPage)
          .map((p) => `[p.${p.pageNumber}] ${p.text}`)
          .join('\n')
          .slice(0, 6000);
        try {
          const res = await generateWithRouter(
            `Summarize Chapter ${ch.chapterNumber} (${ch.title}) concisely with key concepts and formulas:\n\n${chText}`,
            `Respond in ${lang === 'ar' ? 'Arabic' : 'English'} with bullet takeaways.`,
            workspace.settings.aiConfig,
            fetch,
            {
              localOnlyPrivacyMode: workspace.settings.localOnlyPrivacyMode,
              runtimeGeminiApiKey: geminiKeyInput
            }
          );
          updatedChapters[i] = { ...ch, summary: res.text.slice(0, 1200) };
        } catch (err) {
          setBanner(err instanceof Error ? err.message : 'Chapter analysis failed');
          break;
        }
      }
      const nextOverview = updatedChapters
        .map((c) => `Ch.${c.chapterNumber} (${c.title}): ${c.summary.slice(0, 180)}`)
        .join('\n');
      updateCurrentBook((b) => ({
        ...b,
        chapters: updatedChapters,
        overview: nextOverview || b.overview,
        lastUpdatedMillis: Date.now()
      }));
    } finally {
      setFullAnalysisBusy(false);
    }
  };

  const bookQuestions = useMemo(
    () => workspace.questions.filter((q) => !selectedBook || q.bookId === selectedBook.id),
    [workspace.questions, selectedBook]
  );

  const filteredExamPool = useMemo(
    () =>
      bookQuestions.filter(
        (q) =>
          (examChapters.length === 0 || examChapters.includes(q.chapterNumber)) &&
          (examTypes.length === 0 || examTypes.includes(q.questionType))
      ),
    [bookQuestions, examChapters, examTypes]
  );

  const startExamFromPool = (pool: QuestionItem[], title: string) => {
    if (pool.length === 0) return;
    const exam: ExamRecord = {
      id: `exam_${Date.now()}`,
      title,
      bookId: selectedBook?.id ?? 'all',
      questionIds: pool.map((q) => q.id),
      answersByQuestionId: {},
      flaggedQuestionIds: [],
      evaluationsByQuestionId: {},
      score: 0,
      totalMarks: pool.reduce((acc, q) => acc + Math.max(1, q.marks), 0),
      percentage: 0,
      createdAtMillis: Date.now(),
      status: 'IN_PROGRESS'
    };
    setExamAnswers({});
    setWorkspace((prev) => ({ ...prev, exams: [exam, ...prev.exams] }));
    setActiveExamId(exam.id);
  };

  const activeExam = workspace.exams.find((e) => e.id === activeExamId) ?? null;

  const submitActiveExam = () => {
    if (!activeExam) return;
    const items = activeExam.questionIds
      .map((id) => workspace.questions.find((q) => q.id === id))
      .filter((q): q is QuestionItem => Boolean(q));
    const evals: Record<string, AnswerEvaluation> = {};
    let score = 0;
    let totalMarks = 0;
    let correctCount = 0;
    let incorrectCount = 0;
    let unansweredCount = 0;

    items.forEach((q) => {
      const m = Math.max(1, q.marks);
      totalMarks += m;
      const ans = examAnswers[q.id] ?? '';
      if (!ans.trim()) unansweredCount++;
      const ev = evaluateAnswerOffline(q, ans);
      evals[q.id] = ev;
      score += ev.awardedMarks;
      if (ev.isCorrect) correctCount++;
      else if (ans.trim()) incorrectCount++;
    });
    const pct = totalMarks > 0 ? Math.round((score / totalMarks) * 100) : 0;
    const updated: ExamRecord = {
      ...activeExam,
      answersByQuestionId: examAnswers,
      evaluationsByQuestionId: evals,
      score,
      totalMarks,
      percentage: pct,
      status: 'SUBMITTED'
    };
    setWorkspace((prev) => ({
      ...prev,
      exams: prev.exams.map((ex) => (ex.id === updated.id ? updated : ex))
    }));
    enqueueSync('SUBMIT_EXAM', updated.id, {
      title: updated.title,
      bookId: updated.bookId,
      questionIds: updated.questionIds,
      answersJson: JSON.stringify(examAnswers),
      score,
      totalMarks,
      percentage: pct,
      correctCount,
      incorrectCount,
      unansweredCount
    });
  };

  const setLanguage = (nextLang: AppLanguage) => {
    setWorkspace((prev) => ({
      ...prev,
      settings: { ...prev.settings, language: nextLang, updatedAtMillis: Date.now() }
    }));
    enqueueSync('SYNC_PREFS', 'app', {
      languageCode: nextLang,
      aiStrategy: workspace.settings.aiConfig.strategy,
      ollamaModel: workspace.settings.aiConfig.ollamaModel,
      geminiModel: workspace.settings.aiConfig.geminiModel,
      aiTimeoutMs: workspace.settings.aiConfig.timeoutMs,
      clientUpdatedAtMillis: Date.now()
    });
  };

  const bookCards = workspace.flashcards.filter((c) => !selectedBook || c.bookId === selectedBook.id);
  const currentCard = bookCards[cardIdx % Math.max(1, bookCards.length)];

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <h1 className="brand-title">{t(lang, 'appName')}</h1>
          <p className="brand-sub">{t(lang, 'tagline')}</p>
        </div>
        <div className="row-wrap">
          {authSession && <span className="badge ok">{authSession.email}</span>}
          <span className={`badge ${syncState.status === 'SYNCED' ? 'ok' : 'warn'}`}>
            {syncState.status} ({syncState.pendingCount})
          </span>
          <button
            className={`tab-btn ${lang === 'en' ? 'active' : ''}`}
            onClick={() => setLanguage('en')}
            type="button"
          >
            EN
          </button>
          <button
            className={`tab-btn ${lang === 'ar' ? 'active' : ''}`}
            onClick={() => setLanguage('ar')}
            type="button"
          >
            عربي
          </button>
        </div>
      </header>

      <nav className="nav-row" aria-label="Primary navigation">
        {(
          [
            ['dashboard', 'navDashboard'],
            ['library', 'navLibrary'],
            ['reader', 'navReader'],
            ['exams', 'navExams'],
            ['flashcards', 'navFlashcards'],
            ['notes', 'navNotes'],
            ['toolbox', 'navToolbox'],
            ['security', 'navSecurity']
          ] as Array<[NavTab, keyof typeof import('./i18n/translations').TRANSLATIONS.en]>
        ).map(([tabId, labelKey]) => (
          <button
            key={tabId}
            type="button"
            className={`tab-btn ${activeTab === tabId ? 'active' : ''}`}
            onClick={() => setActiveTab(tabId)}
          >
            {t(lang, labelKey)}
          </button>
        ))}
      </nav>

      {banner && (
        <div className="card">
          <div className="row-wrap" style={{ justifyContent: 'space-between' }}>
            <span>{banner}</span>
            <button type="button" className="tab-btn" onClick={() => setBanner('')}>
              ×
            </button>
          </div>
        </div>
      )}

      {activeTab === 'dashboard' && (
        <section className="grid-2">
          <div className="card">
            <h3>{t(lang, 'navLibrary')}</h3>
            <p className="muted">
              {workspace.books.length} books • {workspace.questions.length} questions • {workspace.flashcards.length} flashcards • {workspace.notes.length} notes
            </p>
            {workspace.books.length === 0 ? (
              <p>{t(lang, 'noBooksEmpty')}</p>
            ) : (
              workspace.books.slice(0, 6).map((b) => (
                <button
                  key={b.id}
                  type="button"
                  className="tab-btn"
                  onClick={() => {
                    setSelectedBookId(b.id);
                    setActiveTab('reader');
                  }}
                >
                  {b.title} — p.{b.currentPage}/{b.totalPages}
                </button>
              ))
            )}
          </div>
          <div className="card">
            <h3>{t(lang, 'uploadTitle')}</h3>
            <p className="muted">{t(lang, 'uploadSub')}</p>
            <button type="button" className="btn" onClick={() => setActiveTab('library')}>
              {t(lang, 'navLibrary')}
            </button>
          </div>
        </section>
      )}

      {activeTab === 'library' && (
        <section className="grid-2">
          <div className="card">
            <h2>{t(lang, 'uploadTitle')}</h2>
            <p className="muted">{t(lang, 'uploadSub')}</p>
            <div className="grid-2">
              <div>
                <label>{t(lang, 'uploadBookTitle')}</label>
                <input value={uploadTitle} onChange={(e) => setUploadTitle(e.target.value)} placeholder="e.g. Thermodynamics" />
              </div>
              <div>
                <label>{t(lang, 'uploadSubject')}</label>
                <input value={uploadSubject} onChange={(e) => setUploadSubject(e.target.value)} />
              </div>
            </div>
            <div>
              <label>{t(lang, 'uploadButton')}</label>
              <input type="file" accept=".txt,.md,.pdf" onChange={handleFileUpload} />
              {pdfProgress && (
                <p className="muted">
                  {t(lang, 'pdfExtracting')} ({pdfProgress.processed}/{pdfProgress.total})
                </p>
              )}
            </div>
            <div>
              <label>{t(lang, 'uploadPasteLabel')}</label>
              <textarea rows={7} value={uploadRawText} onChange={(e) => setUploadRawText(e.target.value)} />
            </div>
            <button type="button" className="btn" disabled={Boolean(pdfProgress)} onClick={handleImportBook}>
              {t(lang, 'importSubmit')}
            </button>
          </div>

          <div className="card">
            <h3>{t(lang, 'libraryBooksTitle')}</h3>
            {workspace.books.length === 0 ? (
              <p className="muted">{t(lang, 'noBooksEmpty')}</p>
            ) : (
              workspace.books.map((b) => (
                <div key={b.id} className="passage-card">
                  <div className="row-wrap" style={{ justifyContent: 'space-between' }}>
                    <button
                      type="button"
                      className="tab-btn"
                      onClick={() => {
                        setSelectedBookId(b.id);
                        setActiveTab('reader');
                      }}
                    >
                      {b.title} ({b.totalPages}p)
                    </button>
                    <button type="button" className="btn danger" onClick={() => handleDeleteBook(b.id)}>
                      {t(lang, 'deleteBook')}
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      )}

      {activeTab === 'reader' && (
        <section className="grid-2">
          <div className="card">
            {!selectedBook ? (
              <p className="muted">{t(lang, 'noBooksEmpty')}</p>
            ) : (
              <>
                <div className="row-wrap" style={{ justifyContent: 'space-between' }}>
                  <strong>
                    {selectedBook.title} — p.{selectedBook.currentPage}/{selectedBook.totalPages}
                  </strong>
                  <button
                    type="button"
                    className="tab-btn"
                    onClick={() => {
                      const exists = selectedBook.bookmarkedPages.includes(selectedBook.currentPage);
                      updateCurrentBook((b) => ({
                        ...b,
                        lastUpdatedMillis: Date.now(),
                        bookmarkedPages: exists
                          ? b.bookmarkedPages.filter((n) => n !== b.currentPage)
                          : [...b.bookmarkedPages, b.currentPage].sort((a, c) => a - c)
                      }));
                    }}
                  >
                    {selectedBook.bookmarkedPages.includes(selectedBook.currentPage)
                      ? t(lang, 'bookmarked')
                      : t(lang, 'bookmarkPage')}
                  </button>
                </div>
                <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.65 }}>
                  {selectedBook.pages[selectedBook.currentPage - 1]?.text ?? ''}
                </p>
                <div className="row-wrap">
                  <button
                    type="button"
                    className="tab-btn"
                    disabled={selectedBook.currentPage <= 1}
                    onClick={() => updateCurrentBook((b) => ({ ...b, currentPage: Math.max(1, b.currentPage - 1), lastUpdatedMillis: Date.now() }))}
                  >
                    ‹ Prev
                  </button>
                  <button
                    type="button"
                    className="tab-btn"
                    disabled={selectedBook.currentPage >= selectedBook.totalPages}
                    onClick={() => updateCurrentBook((b) => ({ ...b, currentPage: Math.min(b.totalPages, b.currentPage + 1), lastUpdatedMillis: Date.now() }))}
                  >
                    Next ›
                  </button>
                  <button
                    type="button"
                    className="btn secondary"
                    disabled={fullAnalysisBusy}
                    onClick={handleAnalyzeAllChapters}
                  >
                    {fullAnalysisBusy ? t(lang, 'fullBookAnalyzing') : t(lang, 'fullBookAnalyze')}
                  </button>
                </div>
                <div className="row-wrap">
                  <input
                    value={highlightInput}
                    onChange={(e) => setHighlightInput(e.target.value)}
                    placeholder={t(lang, 'highlightPlaceholder')}
                  />
                  <button
                    type="button"
                    className="btn secondary"
                    onClick={() => {
                      if (!highlightInput.trim()) return;
                      const txt = highlightInput.trim();
                      setHighlightInput('');
                      updateCurrentBook((b) => ({
                        ...b,
                        lastUpdatedMillis: Date.now(),
                        highlightsByPage: {
                          ...b.highlightsByPage,
                          [b.currentPage]: [...(b.highlightsByPage[b.currentPage] ?? []), txt]
                        }
                      }));
                    }}
                  >
                    {t(lang, 'addHighlight')}
                  </button>
                </div>
                {(selectedBook.highlightsByPage[selectedBook.currentPage] ?? []).map((h, i) => (
                  <div key={i} className="passage-card">
                    {h}
                  </div>
                ))}
                <div>
                  <h4>{t(lang, 'chapterSummariesTitle')}</h4>
                  {selectedBook.chapters.map((ch) => (
                    <div key={ch.id} className="passage-card" style={{ marginBottom: 6 }}>
                      <strong>
                        Ch.{ch.chapterNumber}: {ch.title}
                      </strong>
                      <p className="muted" style={{ margin: '0.25rem 0 0', whiteSpace: 'pre-wrap' }}>
                        {ch.summary}
                      </p>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          <div className="card">
            <h3>{t(lang, 'navReader')}</h3>
            <textarea
              rows={3}
              value={qaQuery}
              onChange={(e) => setQaQuery(e.target.value)}
              placeholder={t(lang, 'askBookPlaceholder')}
            />
            <button type="button" className="btn" disabled={qaBusy || !selectedBook} onClick={handleAskBook}>
              {qaBusy ? '...' : t(lang, 'askBookBtn')}
            </button>
            {qaPassages.length > 0 && (
              <div>
                <p className="muted">
                  {t(lang, 'citedPassages')} ({qaPassages.length})
                </p>
                <div className="row-wrap">
                  {qaPassages.map((p, idx) => (
                    <button
                      key={idx}
                      type="button"
                      className="tab-btn"
                      onClick={() => updateCurrentBook((b) => ({ ...b, currentPage: p.pageNumber, lastUpdatedMillis: Date.now() }))}
                    >
                      Ch.{p.chapterNumber || 1} • p.{p.pageNumber}
                    </button>
                  ))}
                </div>
              </div>
            )}
            {qaReply && <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontFamily: 'inherit' }}>{qaReply}</pre>}
          </div>
        </section>
      )}

      {activeTab === 'exams' && (
        <section className="card">
          {!activeExam ? (
            <>
              <h2>{t(lang, 'navExams')}</h2>
              <p className="muted">{t(lang, 'examChapterFilter')}</p>
              <div className="row-wrap">
                {Array.from(new Set(bookQuestions.map((q) => q.chapterNumber))).map((ch) => (
                  <button
                    key={ch}
                    type="button"
                    className={`tab-btn ${examChapters.includes(ch) ? 'active' : ''}`}
                    onClick={() =>
                      setExamChapters((prev) => (prev.includes(ch) ? prev.filter((x) => x !== ch) : [...prev, ch]))
                    }
                  >
                    Ch.{ch}
                  </button>
                ))}
              </div>
              <p className="muted">{t(lang, 'examTypeFilter')}</p>
              <div className="row-wrap">
                {Array.from(new Set(bookQuestions.map((q) => q.questionType))).map((qt) => (
                  <button
                    key={qt}
                    type="button"
                    className={`tab-btn ${examTypes.includes(qt) ? 'active' : ''}`}
                    onClick={() =>
                      setExamTypes((prev) => (prev.includes(qt) ? prev.filter((x) => x !== qt) : [...prev, qt]))
                    }
                  >
                    {qt}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="btn"
                disabled={filteredExamPool.length === 0}
                onClick={() => startExamFromPool(filteredExamPool, `${selectedBook?.title ?? 'Study'} Exam`)}
              >
                {t(lang, 'startExam')} ({filteredExamPool.length})
              </button>
              {workspace.exams.length > 0 && (
                <div>
                  <h4>{t(lang, 'examHistoryTitle')}</h4>
                  {workspace.exams.slice(0, 5).map((ex) => (
                    <div key={ex.id} className="passage-card" style={{ marginBottom: 6 }}>
                      <strong>{ex.title}</strong> — {ex.status === 'SUBMITTED' ? `${ex.percentage}% (${ex.score}/${ex.totalMarks})` : ex.status}
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : (
            <>
              <div className="row-wrap" style={{ justifyContent: 'space-between' }}>
                <strong>
                  {activeExam.title} {activeExam.status === 'SUBMITTED' ? `— ${activeExam.percentage}%` : ''}
                </strong>
                <button type="button" className="tab-btn" onClick={() => setActiveExamId(null)}>
                  ×
                </button>
              </div>
              {activeExam.questionIds
                .map((id) => workspace.questions.find((q) => q.id === id))
                .filter((q): q is QuestionItem => Boolean(q))
                .map((q, idx) => {
                  const ev = activeExam.evaluationsByQuestionId[q.id];
                  return (
                    <div key={q.id} className="passage-card">
                      <div>
                        <strong>
                          {idx + 1}. [{q.questionType}] {q.questionText}
                        </strong>
                      </div>
                      {q.questionType === 'MATCHING' && <p className="muted">{t(lang, 'matchingHint')}</p>}
                      {q.questionType === 'ORDERING' && <p className="muted">{t(lang, 'orderingHint')}</p>}
                      {q.options.length > 0 ? (
                        <div className="row-wrap" style={{ marginTop: 6 }}>
                          {q.options.map((opt) => (
                            <button
                              key={opt}
                              type="button"
                              disabled={activeExam.status === 'SUBMITTED'}
                              className={`tab-btn ${examAnswers[q.id] === opt ? 'active' : ''}`}
                              onClick={() => setExamAnswers((p) => ({ ...p, [q.id]: opt }))}
                            >
                              {opt}
                            </button>
                          ))}
                        </div>
                      ) : (
                        <input
                          style={{ marginTop: 6 }}
                          disabled={activeExam.status === 'SUBMITTED'}
                          value={examAnswers[q.id] ?? ''}
                          onChange={(e) => setExamAnswers((p) => ({ ...p, [q.id]: e.target.value }))}
                        />
                      )}
                      {activeExam.status === 'SUBMITTED' && ev && (
                        <div className="muted" style={{ marginTop: 6 }}>
                          {t(lang, 'aiFeedbackPrefix')}: {ev.isCorrect ? '✓' : '✗'} ({ev.awardedMarks}/{q.marks}) — {ev.feedback} (Key: {q.correctAnswer})
                        </div>
                      )}
                    </div>
                  );
                })}
              {activeExam.status === 'IN_PROGRESS' ? (
                <button type="button" className="btn" onClick={submitActiveExam}>
                  {t(lang, 'submitExam')}
                </button>
              ) : (
                <button
                  type="button"
                  className="btn secondary"
                  onClick={() => {
                    const missed = activeExam.questionIds
                      .filter((id) => activeExam.evaluationsByQuestionId[id]?.isCorrect === false)
                      .map((id) => workspace.questions.find((q) => q.id === id))
                      .filter((q): q is QuestionItem => Boolean(q));
                    if (missed.length > 0) startExamFromPool(missed, 'Retake Missed');
                  }}
                >
                  {t(lang, 'retakeMissed')}
                </button>
              )}
            </>
          )}
        </section>
      )}

      {activeTab === 'flashcards' && (
        <section className="grid-2">
          <div className="card">
            <h2>{t(lang, 'navFlashcards')}</h2>
            {!currentCard ? (
              <p className="muted">{t(lang, 'noBooksEmpty')}</p>
            ) : (
              <>
                <div className="passage-card" style={{ padding: '1.25rem' }}>
                  <div className="muted">Ch.{currentCard.chapterNumber} • {currentCard.intervalDays}d interval</div>
                  <h3>{cardFlipped ? currentCard.back : currentCard.front}</h3>
                </div>
                <div className="row-wrap">
                  <button type="button" className="btn secondary" onClick={() => setCardFlipped((f) => !f)}>
                    {t(lang, 'flashcardFlip')}
                  </button>
                  {(['HARD', 'MEDIUM', 'EASY'] as const).map((rating) => (
                    <button
                      key={rating}
                      type="button"
                      className="btn"
                      onClick={() => {
                        const nextCard = nextSm2Schedule(currentCard, rating);
                        setWorkspace((prev) => ({
                          ...prev,
                          flashcards: prev.flashcards.map((c) => (c.id === nextCard.id ? nextCard : c))
                        }));
                        setCardFlipped(false);
                        setCardIdx((i) => i + 1);
                      }}
                    >
                      {rating === 'HARD' ? t(lang, 'rateHard') : rating === 'MEDIUM' ? t(lang, 'rateMedium') : t(lang, 'rateEasy')}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
          <div className="card">
            <h3>{t(lang, 'addCardBtn')}</h3>
            <input value={newCardFront} onChange={(e) => setNewCardFront(e.target.value)} placeholder={t(lang, 'newCardFront')} />
            <input value={newCardBack} onChange={(e) => setNewCardBack(e.target.value)} placeholder={t(lang, 'newCardBack')} />
            <button
              type="button"
              className="btn"
              onClick={() => {
                if (!newCardFront.trim() || !newCardBack.trim()) return;
                const card: Flashcard = {
                  id: `fc_${Date.now()}`,
                  bookId: selectedBook?.id ?? 'general',
                  chapterNumber: 1,
                  front: newCardFront.trim(),
                  back: newCardBack.trim(),
                  easeFactor: 2.5,
                  intervalDays: 1,
                  repetitions: 0,
                  nextReviewAtMillis: Date.now()
                };
                setWorkspace((prev) => ({ ...prev, flashcards: [card, ...prev.flashcards] }));
                setNewCardFront('');
                setNewCardBack('');
              }}
            >
              {t(lang, 'addCardBtn')}
            </button>
          </div>
        </section>
      )}

      {activeTab === 'notes' && (
        <section className="card">
          <h2>{t(lang, 'navNotes')}</h2>
          <input value={noteTitle} onChange={(e) => setNoteTitle(e.target.value)} placeholder={t(lang, 'newNoteTitle')} />
          <textarea rows={4} value={noteBody} onChange={(e) => setNoteBody(e.target.value)} placeholder={t(lang, 'newNoteBody')} />
          <button
            type="button"
            className="btn"
            onClick={() => {
              if (!noteTitle.trim()) return;
              const note = {
                id: `note_${Date.now()}`,
                bookId: selectedBook?.id ?? 'general',
                chapterNumber: 1,
                linkedPage: selectedBook?.currentPage ?? 1,
                title: noteTitle.trim(),
                contentMarkdown: noteBody,
                updatedAtMillis: Date.now()
              };
              setWorkspace((prev) => ({ ...prev, notes: [note, ...prev.notes] }));
              enqueueSync('UPSERT_NOTE', note.id, note);
              setNoteTitle('');
              setNoteBody('');
            }}
          >
            {t(lang, 'saveNote')}
          </button>
          {workspace.notes.map((n) => (
            <div key={n.id} className="passage-card">
              <div className="row-wrap" style={{ justifyContent: 'space-between' }}>
                <div>
                  <strong>{n.title}</strong> <span className="muted">(p.{n.linkedPage})</span>
                </div>
                <button
                  type="button"
                  className="btn danger"
                  onClick={() => {
                    setWorkspace((prev) => ({ ...prev, notes: prev.notes.filter((x) => x.id !== n.id) }));
                    enqueueSync('DELETE_NOTE', n.id, { deletedAtMillis: Date.now() });
                  }}
                >
                  {t(lang, 'deleteNote')}
                </button>
              </div>
              <p style={{ margin: '0.35rem 0 0', whiteSpace: 'pre-wrap' }}>{n.contentMarkdown}</p>
            </div>
          ))}
        </section>
      )}

      {activeTab === 'toolbox' && (
        <section className="card">
          <h2>{t(lang, 'navToolbox')}</h2>
          <input value={calcExpr} onChange={(e) => setCalcExpr(e.target.value)} placeholder={t(lang, 'calcExpression')} />
          <div className="row-wrap">
            <button type="button" className="btn" onClick={() => setCalcOutput(evaluateMathExpression(calcExpr))}>
              {t(lang, 'calcEval')}
            </button>
            <span className="badge ok">{calcOutput}</span>
          </div>
        </section>
      )}

      {activeTab === 'security' && (
        <section className="grid-2">
          <div className="card">
            <h3>{t(lang, 'authSectionTitle')}</h3>
            {!firebaseConfigured ? (
              <p className="muted">{t(lang, 'authNotConfigured')}</p>
            ) : !authSession ? (
              <>
                <input value={authEmail} onChange={(e) => setAuthEmail(e.target.value)} placeholder={t(lang, 'authEmail')} type="email" />
                <input value={authPassword} onChange={(e) => setAuthPassword(e.target.value)} placeholder={t(lang, 'authPassword')} type="password" />
                <input value={authDisplayName} onChange={(e) => setAuthDisplayName(e.target.value)} placeholder={t(lang, 'authDisplayName')} />
                <div className="row-wrap">
                  <button type="button" className="btn" disabled={authBusy} onClick={() => handleAuthAction('signin')}>
                    {t(lang, 'authSignIn')}
                  </button>
                  <button type="button" className="btn secondary" disabled={authBusy} onClick={() => handleAuthAction('signup')}>
                    {t(lang, 'authSignUp')}
                  </button>
                </div>
              </>
            ) : (
              <>
                <p>
                  {t(lang, 'authSignedInAs')} <strong>{authSession.displayName || authSession.email}</strong> ({authSession.email})
                </p>
                <button type="button" className="btn secondary" onClick={handleSignOut}>
                  {t(lang, 'authSignOut')}
                </button>
                <div className="passage-card" style={{ borderColor: 'var(--danger)' }}>
                  <strong>{t(lang, 'deleteAccountTitle')}</strong>
                  <p className="muted">{t(lang, 'deleteAccountHint')}</p>
                  <input
                    value={deleteConfirmText}
                    onChange={(e) => setDeleteConfirmText(e.target.value)}
                    placeholder={t(lang, 'deleteAccountConfirmPlaceholder')}
                  />
                  <button
                    type="button"
                    className="btn danger"
                    disabled={authBusy || deleteConfirmText.trim() !== 'DELETE'}
                    onClick={handleDeleteAccount}
                    style={{ marginTop: 8 }}
                  >
                    {t(lang, 'deleteAccountBtn')}
                  </button>
                </div>
              </>
            )}

            <h3 style={{ marginTop: 12 }}>{t(lang, 'syncCenterTitle')}</h3>
            <label className="row-wrap">
              <input
                type="checkbox"
                style={{ width: 'auto' }}
                checked={workspace.settings.localOnlyPrivacyMode}
                onChange={(e) => {
                  const privacy = e.target.checked;
                  setWorkspace((prev) => ({
                    ...prev,
                    settings: { ...prev.settings, localOnlyPrivacyMode: privacy, updatedAtMillis: Date.now() }
                  }));
                  setSyncState((s) => ({ ...s, localOnlyMode: privacy }));
                }}
              />
              <span>{t(lang, 'privacyMode')}</span>
            </label>
            <p className="muted">{t(lang, 'privacyModeDesc')}</p>
            {(syncState.deadLetterCount ?? 0) > 0 && (
              <div className="row-wrap">
                <span className="badge warn">
                  {t(lang, 'deadLetterNotice')}: {syncState.deadLetterCount}
                </span>
                <button
                  type="button"
                  className="btn secondary"
                  onClick={() => {
                    const count = store.requeueDeadLetters(currentUid);
                    setSyncState(store.loadSyncMeta(currentUid));
                    setBanner(`Requeued ${count} failed sync item(s).`);
                  }}
                >
                  {t(lang, 'retryDeadLetters')}
                </button>
              </div>
            )}
            <div className="row-wrap">
              <button
                type="button"
                className="btn"
                disabled={workspace.settings.localOnlyPrivacyMode}
                onClick={handleSyncNow}
              >
                {t(lang, 'syncNow')}
              </button>
              <button
                type="button"
                className="btn secondary"
                onClick={() => {
                  const json = store.exportNonSecretJson(workspace);
                  const blob = new Blob([json], { type: 'application/json' });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = 'sora_workspace_export.json';
                  a.click();
                  URL.revokeObjectURL(url);
                }}
              >
                {t(lang, 'exportJson')}
              </button>
              <button
                type="button"
                className="btn danger"
                onClick={() => {
                  store.clearAll(currentUid);
                  secretStore.clearAll();
                  setGeminiKeyInput('');
                  setAuthSession(null);
                  setWorkspace(store.loadWorkspace());
                  setSyncState(store.loadSyncMeta('guest'));
                  setSelectedBookId('');
                }}
              >
                {t(lang, 'clearData')}
              </button>
            </div>
          </div>

          <div className="card">
            <h3>{t(lang, 'aiStrategy')}</h3>
            <select
              value={workspace.settings.aiConfig.strategy}
              onChange={(e) =>
                setWorkspace((p) => ({
                  ...p,
                  settings: {
                    ...p.settings,
                    aiConfig: { ...p.settings.aiConfig, strategy: e.target.value as AiStrategy }
                  }
                }))
              }
            >
              <option value="AUTO">AUTO</option>
              <option value="OLLAMA_FIRST">OLLAMA_FIRST</option>
              <option value="GEMINI_FIRST">GEMINI_FIRST</option>
              <option value="OLLAMA_ONLY">OLLAMA_ONLY</option>
              <option value="GEMINI_ONLY">GEMINI_ONLY</option>
            </select>
            <label>{t(lang, 'ollamaEndpoint')}</label>
            <input
              value={workspace.settings.aiConfig.ollamaEndpoint}
              onChange={(e) =>
                setWorkspace((p) => ({
                  ...p,
                  settings: {
                    ...p.settings,
                    aiConfig: { ...p.settings.aiConfig, ollamaEndpoint: e.target.value }
                  }
                }))
              }
              onBlur={() => {
                const check = validateOllamaEndpoint(workspace.settings.aiConfig.ollamaEndpoint);
                if (!check.valid) setBanner(check.error ?? 'Invalid Ollama URL');
              }}
            />
            <label>{t(lang, 'ollamaModel')}</label>
            <input
              value={workspace.settings.aiConfig.ollamaModel}
              onChange={(e) =>
                setWorkspace((p) => ({
                  ...p,
                  settings: { ...p.settings, aiConfig: { ...p.settings.aiConfig, ollamaModel: e.target.value } }
                }))
              }
            />
            <label>{t(lang, 'geminiModel')}</label>
            <input
              value={workspace.settings.aiConfig.geminiModel}
              onChange={(e) =>
                setWorkspace((p) => ({
                  ...p,
                  settings: { ...p.settings, aiConfig: { ...p.settings.aiConfig, geminiModel: e.target.value } }
                }))
              }
            />
            <label>{t(lang, 'geminiApiKey')}</label>
            <div className="row-wrap">
              <input
                style={{ flex: 1 }}
                type="password"
                value={geminiKeyInput}
                onChange={(e) => {
                  const val = e.target.value;
                  setGeminiKeyInput(val);
                  void secretStore.setEphemeralSessionSecret('gemini_api_key', val);
                }}
              />
              <button
                type="button"
                className="btn secondary"
                onClick={() => {
                  setGeminiKeyInput('');
                  void secretStore.setEphemeralSessionSecret('gemini_api_key', '');
                }}
              >
                {t(lang, 'clearGeminiKey')}
              </button>
            </div>
            <p className="muted">{t(lang, 'geminiKeySessionNotice')}</p>
            <div className="row-wrap">
              <button
                type="button"
                className="btn secondary"
                onClick={async () => {
                  const res = await testOllamaConnection(workspace.settings.aiConfig);
                  setAiStatusMsg(res.message);
                }}
              >
                {t(lang, 'testOllamaBtn')}
              </button>
              <button
                type="button"
                className="btn secondary"
                disabled={workspace.settings.localOnlyPrivacyMode}
                onClick={async () => {
                  const res = await testGeminiConnection(
                    workspace.settings.aiConfig.geminiModel,
                    geminiKeyInput,
                    workspace.settings.aiConfig.timeoutMs,
                    workspace.settings.localOnlyPrivacyMode
                  );
                  setAiStatusMsg(res.message);
                }}
              >
                {t(lang, 'testGeminiBtn')}
              </button>
            </div>
            {aiStatusMsg && <p className="badge ok">{aiStatusMsg}</p>}
          </div>
        </section>
      )}
    </div>
  );
}
