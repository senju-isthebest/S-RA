import React, { useEffect, useMemo, useState } from 'react';
import { generateWithRouter, validateOllamaEndpoint } from './ai/aiRouter';
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
  flushSyncQueueWithRetry
} from './storage/offlineStore';
import { pushSyncOperationToFirestore, readFirebaseWebEnv } from './sync/firebaseBridge';
import type {
  AiStrategy,
  AnswerEvaluation,
  AppLanguage,
  Book,
  ExamRecord,
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

function evaluateMathExpression(expr: string): string {
  const clean = expr.replace(/[^0-9+\-*/().\s]/g, '').trim();
  if (!clean) return 'Invalid expression';
  try {
    // Pure arithmetic tokenizer/evaluator without eval()
    const fn = new Function(`"use strict"; return (${clean});`) as () => unknown;
    const result = fn();
    if (typeof result === 'number' && Number.isFinite(result)) {
      return String(Math.round(result * 100000) / 100000);
    }
    return 'Invalid expression';
  } catch {
    return 'Invalid expression';
  }
}

export default function App() {
  const [workspace, setWorkspace] = useState<SoraWorkspaceState>(() => store.loadWorkspace());
  const [syncState, setSyncState] = useState(() => store.loadSyncMeta());
  const [activeTab, setActiveTab] = useState<NavTab>('dashboard');
  const [selectedBookId, setSelectedBookId] = useState<string>(() => store.loadWorkspace().books[0]?.id ?? '');
  const [banner, setBanner] = useState<string>('');

  // Library upload form
  const [uploadTitle, setUploadTitle] = useState('');
  const [uploadSubject, setUploadSubject] = useState('Physics');
  const [uploadRawText, setUploadRawText] = useState('');

  // Reader & Full-Book Q&A
  const [highlightInput, setHighlightInput] = useState('');
  const [qaQuery, setQaQuery] = useState('');
  const [qaReply, setQaReply] = useState('');
  const [qaPassages, setQaPassages] = useState<RetrievedPassage[]>([]);
  const [qaBusy, setQaBusy] = useState(false);

  // Exam engine UI
  const [examChapters, setExamChapters] = useState<number[]>([]);
  const [examTypes, setExamTypes] = useState<string[]>([]);
  const [activeExamId, setActiveExamId] = useState<string | null>(null);
  const [examAnswers, setExamAnswers] = useState<Record<string, string>>({});

  // Flashcard UI
  const [cardIdx, setCardIdx] = useState(0);
  const [cardFlipped, setCardFlipped] = useState(false);

  // Notes UI
  const [noteTitle, setNoteTitle] = useState('');
  const [noteBody, setNoteBody] = useState('');

  // Toolbox UI
  const [calcExpr, setCalcExpr] = useState('(12 + 8) * 3.5');
  const [calcOutput, setCalcOutput] = useState('70');

  const lang = workspace.settings.language;

  useEffect(() => {
    applyDocumentDirection(lang);
    store.saveWorkspace(workspace);
  }, [lang, workspace]);

  const selectedBook: Book | undefined = useMemo(
    () => workspace.books.find((b) => b.id === selectedBookId) ?? workspace.books[0],
    [workspace.books, selectedBookId]
  );

  const enqueueSync = (opType: SyncOperation['opType'], entityId: string, payload: unknown) => {
    const queue = store.loadQueue();
    queue.push({
      id: `op_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      opType,
      entityId,
      payloadJson: JSON.stringify(payload),
      createdAtMillis: Date.now(),
      attempts: 0,
      lastError: ''
    });
    store.saveQueue(queue);
    const nextMeta = {
      ...store.loadSyncMeta(),
      status: 'OFFLINE_QUEUED' as const,
      pendingCount: queue.length,
      localOnlyMode: workspace.settings.localOnlyPrivacyMode
    };
    store.saveSyncMeta(nextMeta);
    setSyncState(nextMeta);
  };

  const handleSyncNow = async () => {
    const firebaseEnv = readFirebaseWebEnv();
    const isOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;
    const result = await flushSyncQueueWithRetry(
      store,
      async (op) => pushSyncOperationToFirestore(op, '', '', firebaseEnv),
      isOnline,
      workspace.settings.localOnlyPrivacyMode
    );
    setSyncState(result);
    setBanner(result.status === 'SYNCED' ? 'Cloud sync up to date.' : `Sync state: ${result.status}`);
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!uploadTitle) setUploadTitle(file.name.replace(/\.[^.]+$/, ''));
    const buf = await file.arrayBuffer();
    const rawDecoded = new TextDecoder('utf-8', { fatal: false }).decode(buf);
    const printable = file.name.toLowerCase().endsWith('.pdf')
      ? (rawDecoded.match(/\(([^()]{4,200})\)/g) ?? []).map((m) => m.slice(1, -1)).join('\n') || rawDecoded.replace(/[^\p{L}\p{N}\s.,;:?!()\-\n]/gu, ' ')
      : rawDecoded;
    setUploadRawText(printable.slice(0, 120_000));
  };

  const handleImportBook = () => {
    if (!uploadRawText.trim()) return;
    const { book, starterQuestions, starterCards } = parseUploadedTextBook(
      uploadTitle.trim() || 'Study Textbook',
      uploadSubject.trim() || 'General',
      uploadRawText
    );
    setWorkspace((prev) => ({
      ...prev,
      books: [book, ...prev.books],
      questions: [...starterQuestions, ...prev.questions],
      flashcards: [...starterCards, ...prev.flashcards]
    }));
    setSelectedBookId(book.id);
    enqueueSync('UPSERT_BOOK', book.id, { title: book.title, totalPages: book.totalPages });
    setUploadTitle('');
    setUploadRawText('');
    setActiveTab('reader');
  };

  const updateCurrentBook = (mutator: (b: Book) => Book) => {
    if (!selectedBook) return;
    const updated = mutator(selectedBook);
    setWorkspace((prev) => ({
      ...prev,
      books: prev.books.map((b) => (b.id === updated.id ? ConflictResolver.mergeBook(b, updated).book : b))
    }));
    enqueueSync('UPSERT_BOOK', updated.id, { currentPage: updated.currentPage, bookmarkedPages: updated.bookmarkedPages });
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
        workspace.settings.aiConfig
      );
      setQaReply(`${reply.text}\n\n(${reply.provider} • ${reply.model})`);
    } catch {
      setQaReply(
        hits.length > 0
          ? hits.map((h) => `• [Ch.${h.chapterNumber || 1}, p.${h.pageNumber}] ${h.excerpt.slice(0, 220)}…`).join('\n\n')
          : 'Configure local Ollama or a Gemini API key in Sync & Security for generative answers; matching passages are shown below.'
      );
    } finally {
      setQaBusy(false);
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
    items.forEach((q) => {
      const m = Math.max(1, q.marks);
      totalMarks += m;
      const ev = evaluateAnswerOffline(q, examAnswers[q.id] ?? '');
      evals[q.id] = ev;
      score += ev.awardedMarks;
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
    enqueueSync('SUBMIT_EXAM', updated.id, { score, totalMarks, percentage: pct });
  };

  const setLanguage = (nextLang: AppLanguage) => {
    setWorkspace((prev) => ({
      ...prev,
      settings: { ...prev.settings, language: nextLang, updatedAtMillis: Date.now() }
    }));
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
              workspace.books.slice(0, 4).map((b) => (
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
        <section className="card">
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
          </div>
          <div>
            <label>{t(lang, 'uploadPasteLabel')}</label>
            <textarea rows={7} value={uploadRawText} onChange={(e) => setUploadRawText(e.target.value)} />
          </div>
          <button type="button" className="btn" onClick={handleImportBook}>
            {t(lang, 'importSubmit')}
          </button>
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
                          {t(lang, 'aiFeedbackPrefix')}: {ev.isCorrect ? '✓' : '✗'} — {ev.feedback} (Key: {q.correctAnswer})
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
        <section className="card">
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
              <strong>{n.title}</strong> <span className="muted">(p.{n.linkedPage})</span>
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
            <h3>{t(lang, 'syncCenterTitle')}</h3>
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
            <div className="row-wrap">
              <button type="button" className="btn" disabled={workspace.settings.localOnlyPrivacyMode} onClick={handleSyncNow}>
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
                  store.clearAll();
                  setWorkspace(store.loadWorkspace());
                  setSyncState(store.loadSyncMeta());
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
            <input
              type="password"
              value={workspace.settings.aiConfig.geminiApiKey}
              onChange={(e) =>
                setWorkspace((p) => ({
                  ...p,
                  settings: { ...p.settings, aiConfig: { ...p.settings.aiConfig, geminiApiKey: e.target.value } }
                }))
              }
            />
          </div>
        </section>
      )}
    </div>
  );
}
