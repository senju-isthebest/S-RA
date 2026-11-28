import { describe, expect, it } from 'vitest';
import { generateWithRouter, validateOllamaEndpoint } from '../src/ai/aiRouter';
import {
  evaluateAnswerOffline,
  nextSm2Schedule,
  parseUploadedTextBook,
  retrievePassages
} from '../src/engine/textbookEngine';
import { TRANSLATIONS } from '../src/i18n/translations';
import {
  ConflictResolver,
  OfflineWorkspaceStore,
  defaultWorkspaceState,
  flushSyncQueueWithRetry
} from '../src/storage/offlineStore';
import type { Flashcard, QuestionItem } from '../src/types/sora';

describe('SŌRA Web PWA', () => {
  it('has complete EN/AR translation key parity', () => {
    const enKeys = Object.keys(TRANSLATIONS.en).sort();
    const arKeys = Object.keys(TRANSLATIONS.ar).sort();
    expect(arKeys).toEqual(enKeys);
    for (const k of arKeys) {
      expect(TRANSLATIONS.ar[k as keyof typeof TRANSLATIONS.ar].trim().length).toBeGreaterThan(0);
    }
  });

  it('retrieves English and Arabic passages with BM25 and respects character budgets', () => {
    const { book } = parseUploadedTextBook(
      'Bilingual Physics',
      'Physics',
      [
        'Chapter 1 Kinematics describes velocity v = u + at.',
        'Chapter 2 Thermodynamics defines entropy and heat Q = m c deltaT.',
        'الفصل الثالث: الديناميكا الحرارية وإنتروبيا النظام المعزول.'
      ].join('\n\n')
    );
    const enHits = retrievePassages('entropy Thermodynamics heat', book.pages, book.chapters, 3, 2000);
    expect(enHits.length).toBeGreaterThan(0);
    expect(enHits[0].excerpt.toLowerCase()).toContain('thermodynamics');

    const arHits = retrievePassages('الديناميكا الحراريه انتروبيا', book.pages, book.chapters, 3, 2000);
    expect(arHits.length).toBeGreaterThan(0);
    expect(arHits[0].excerpt).toContain('الديناميكا');
  });

  it('evaluates MCQ, True/False, Calculation tolerance, and Open-Ended rubrics', () => {
    const calcQ: QuestionItem = {
      id: 'q1',
      bookId: 'b1',
      chapterNumber: 1,
      sourcePage: 1,
      questionType: 'CALCULATION',
      difficulty: 'MEDIUM',
      marks: 4,
      questionText: 'Compute g',
      options: [],
      correctAnswer: '9.81',
      explanation: ''
    };
    expect(evaluateAnswerOffline(calcQ, '9.8').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(calcQ, '14').isCorrect).toBe(false);

    const tfQ: QuestionItem = { ...calcQ, questionType: 'TRUE_FALSE', correctAnswer: 'True', marks: 1 };
    expect(evaluateAnswerOffline(tfQ, 'صح').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(tfQ, 'خطأ').isCorrect).toBe(false);

    const essayQ: QuestionItem = {
      ...calcQ,
      questionType: 'EXPLAIN_JUSTIFY',
      correctAnswer: 'Entropy measures microscopic thermal disorder and increases toward equilibrium.'
    };
    expect(evaluateAnswerOffline(essayQ, 'Entropy measures microscopic disorder increasing toward equilibrium.').isCorrect).toBe(true);
  });

  it('schedules flashcards with SM-2 and merges conflicts & offline sync retries cleanly', async () => {
    const card: Flashcard = {
      id: 'fc1',
      bookId: 'b1',
      chapterNumber: 1,
      front: 'Force',
      back: 'F = ma',
      easeFactor: 2.5,
      intervalDays: 1,
      repetitions: 1,
      nextReviewAtMillis: 0
    };
    const next = nextSm2Schedule(card, 'EASY', 1_000_000);
    expect(next.intervalDays).toBe(6);
    expect(next.repetitions).toBe(2);

    const merged = ConflictResolver.mergeReadingPage(10, 18, 5000, 5500);
    expect(merged.page).toBe(18);
    expect(merged.hadConflict).toBe(true);

    const store = new OfflineWorkspaceStore();
    const state = defaultWorkspaceState();
    state.settings.aiConfig.geminiApiKey = 'AIzaWebSecretNeverExport';
    const exported = store.exportNonSecretJson(state);
    expect(exported).not.toContain('AIzaWebSecretNeverExport');

    store.saveQueue([
      { id: 'op1', opType: 'UPSERT_NOTE', entityId: 'n1', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' }
    ]);
    let tries = 0;
    const syncRes = await flushSyncQueueWithRetry(
      store,
      async () => {
        tries++;
        if (tries === 1) throw new Error('Transient network error');
      },
      true,
      false
    );
    expect(syncRes.status).toBe('SYNCED');
    expect(syncRes.pendingCount).toBe(0);
  });

  it('validates Ollama endpoints safely and falls back from Ollama to Gemini cleanly', async () => {
    expect(validateOllamaEndpoint('http://localhost:11434/').valid).toBe(true);
    expect(validateOllamaEndpoint('ftp://bad-host').valid).toBe(false);
    expect(validateOllamaEndpoint('http://user:pass@localhost:11434').valid).toBe(false);

    const mockFetch: typeof fetch = async (url) => {
      const target = String(url);
      if (target.includes('11434')) {
        return new Response('down', { status: 503 });
      }
      return new Response(
        JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Fallback Gemini answer' }] } }] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const res = await generateWithRouter(
      'Explain inertia',
      'Be concise',
      {
        strategy: 'OLLAMA_FIRST',
        fallbackEnabled: true,
        ollamaEndpoint: 'http://localhost:11434',
        ollamaModel: 'llama3.2:3b',
        geminiModel: 'gemini-3-flash-preview',
        geminiApiKey: 'test-key',
        timeoutMs: 5000
      },
      mockFetch
    );
    expect(res.provider).toBe('GEMINI');
    expect(res.text).toBe('Fallback Gemini answer');
  });
});
