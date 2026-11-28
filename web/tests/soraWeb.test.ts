import { describe, expect, it } from 'vitest';
import swSource from '../public/sw.js?raw';
import { evaluateMathExpression } from '../src/App';
import { generateWithRouter, redactSecrets, validateOllamaEndpoint } from '../src/ai/aiRouter';
import { extractPdfTextFromArrayBuffer } from '../src/engine/pdfExtractor';
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
  WebSessionSecretStore,
  defaultWorkspaceState,
  flushSyncQueueWithRetry
} from '../src/storage/offlineStore';
import {
  FirebaseSyncError,
  deleteAllCloudUserData,
  pullWorkspaceFromFirestore,
  pushSyncOperationToFirestore,
  refreshAuthSession,
  signInWithEmail,
  signUpWithEmail
} from '../src/sync/firebaseBridge';
import type { Flashcard, QuestionItem } from '../src/types/sora';

function buildMinimalMultiPagePdf(pageTexts: string[]): Uint8Array {
  const enc = new TextEncoder();
  const escapePdfStr = (s: string) => s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const objects: string[] = [];
  // 1: Catalog, 2: Pages, 3: Font
  const pageObjNums: number[] = [];
  let nextObj = 4;
  for (let i = 0; i < pageTexts.length; i++) {
    pageObjNums.push(nextObj);
    nextObj += 2; // page obj + content stream obj
  }

  objects.push(`1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n`);
  objects.push(`2 0 obj\n<< /Type /Pages /Kids [${pageObjNums.map((n) => `${n} 0 R`).join(' ')}] /Count ${pageTexts.length} >>\nendobj\n`);
  objects.push(`3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n`);

  pageTexts.forEach((txt, idx) => {
    const pObj = pageObjNums[idx];
    const cObj = pObj + 1;
    const streamBody = `BT /F1 12 Tf 72 720 Td (${escapePdfStr(txt)}) Tj ET`;
    objects.push(
      `${pObj} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${cObj} 0 R >>\nendobj\n`
    );
    objects.push(
      `${cObj} 0 obj\n<< /Length ${enc.encode(streamBody).byteLength} >>\nstream\n${streamBody}\nendstream\nendobj\n`
    );
  });

  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [0];
  for (const obj of objects) {
    offsets.push(enc.encode(pdf).byteLength);
    pdf += obj;
  }
  const xrefOffset = enc.encode(pdf).byteLength;
  pdf += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  for (let i = 1; i < offsets.length; i++) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return enc.encode(pdf);
}

function createMemoryStorage() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => {
      m.set(k, v);
    },
    removeItem: (k: string) => {
      m.delete(k);
    },
    dump: () => Object.fromEntries(m.entries())
  };
}

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

  it('extracts multi-page text PDFs cleanly via pdfjs-dist and rejects corrupt or empty-text PDFs', async () => {
    const pdfBytes = buildMinimalMultiPagePdf([
      'Chapter 1 Introduction to Thermodynamics and Entropy',
      'Page 2 Ideal Gas Law PV equals nRT'
    ]);
    const res = await extractPdfTextFromArrayBuffer(pdfBytes);
    expect(res.ok).toBe(true);
    expect(res.pageCount).toBe(2);
    expect(res.pages[0].text).toContain('Thermodynamics');
    expect(res.pages[1].text).toContain('Ideal Gas Law');

    const corrupt = await extractPdfTextFromArrayBuffer(new TextEncoder().encode('not a valid pdf file'));
    expect(corrupt.ok).toBe(false);
    expect(corrupt.errorCode).toBe('CORRUPTED_PDF');

    const emptyLayerPdf = buildMinimalMultiPagePdf(['   ']);
    const emptyRes = await extractPdfTextFromArrayBuffer(emptyLayerPdf);
    expect(emptyRes.ok).toBe(false);
    expect(emptyRes.errorCode).toBe('EMPTY_TEXT_LAYER');

    const tooLarge = await extractPdfTextFromArrayBuffer(new Uint8Array(64), undefined, 32);
    expect(tooLarge.ok).toBe(false);
    expect(tooLarge.errorCode).toBe('FILE_TOO_LARGE');
  });

  it('grades MCQ, TF, Calculation, Open-Ended, and adversarial MATCHING / ORDERING fixtures', () => {
    const base: QuestionItem = {
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
    expect(evaluateAnswerOffline(base, '9.8').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(base, '14').isCorrect).toBe(false);

    const tfQ: QuestionItem = { ...base, questionType: 'TRUE_FALSE', correctAnswer: 'True', marks: 1 };
    expect(evaluateAnswerOffline(tfQ, 'صح').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(tfQ, 'خطأ').isCorrect).toBe(false);

    const matchQ: QuestionItem = {
      ...base,
      questionType: 'MATCHING',
      correctAnswer: 'Newton=Force; Joule=Energy; Watt=Power; Pascal=Pressure'
    };
    expect(evaluateAnswerOffline(matchQ, 'Joule=Energy\nNewton=Force\nWatt=Power\nPascal=Pressure').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(matchQ, 'Newton=Force; Joule=Energy; Watt=Power; Pascal=Wrong').scoreRatio).toBeCloseTo(0.75, 2);
    expect(evaluateAnswerOffline(matchQ, 'Newton=Force; Joule=Wrong; Watt=Wrong; Pascal=Wrong').isCorrect).toBe(false);
    // Duplicate / shotgun poisoning protection
    expect(
      evaluateAnswerOffline(
        matchQ,
        'Newton=Force; Newton=Energy; Newton=Power; Newton=Pressure; Newton=Wrong'
      ).isCorrect
    ).toBe(false);

    const orderQ: QuestionItem = {
      ...base,
      questionType: 'ORDERING',
      correctAnswer: 'Evaporation -> Condensation -> Precipitation -> Collection'
    };
    expect(
      evaluateAnswerOffline(orderQ, '1. Evaporation\n2. Condensation\n3. Precipitation\n4. Collection').isCorrect
    ).toBe(true);
    expect(
      evaluateAnswerOffline(orderQ, 'Collection -> Precipitation -> Condensation -> Evaporation').isCorrect
    ).toBe(false);

    const numericOrderQ: QuestionItem = {
      ...base,
      questionType: 'ORDERING',
      correctAnswer: '1995 -> 2005 -> 2015'
    };
    expect(evaluateAnswerOffline(numericOrderQ, '1. 1995 -> 2. 2005 -> 3. 2015').isCorrect).toBe(true);
    expect(evaluateAnswerOffline(numericOrderQ, '2015 -> 2005 -> 1995').isCorrect).toBe(false);
  });

  it('strips geminiApiKey from localStorage, isolates queues per UID, quarantines corrupt queues, and retries dead letters', async () => {
    const localBackend = createMemoryStorage();
    const sessionBackend = createMemoryStorage();
    const store = new OfflineWorkspaceStore(localBackend, sessionBackend);

    const state = defaultWorkspaceState();
    state.settings.aiConfig.geminiApiKey = 'AIzaWebSecretNeverPersist';
    store.saveWorkspace(state);

    expect(localBackend.dump()['sora_web_workspace_v1']).not.toContain('AIzaWebSecretNeverPersist');
    expect(store.exportNonSecretJson(state)).not.toContain('AIzaWebSecretNeverPersist');
    expect(store.loadWorkspace().settings.aiConfig.geminiApiKey).toBe('');

    const secretStore = new WebSessionSecretStore(sessionBackend);
    secretStore.setMemorySecret('gemini_api_key', 'AIzaRuntimeOnlyKey');
    expect(secretStore.getMemorySecret('gemini_api_key')).toBe('AIzaRuntimeOnlyKey');
    expect(JSON.stringify(localBackend.dump())).not.toContain('AIzaRuntimeOnlyKey');

    // Per-UID isolation
    store.enqueueOperation(
      { id: 'opA', opType: 'UPSERT_NOTE', entityId: 'nA', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' },
      'uid_A'
    );
    store.enqueueOperation(
      { id: 'opB', opType: 'UPSERT_NOTE', entityId: 'nB', payloadJson: '{}', createdAtMillis: 2, attempts: 0, lastError: '' },
      'uid_B'
    );
    expect(store.loadQueue('uid_A').map((o) => o.id)).toEqual(['opA']);
    expect(store.loadQueue('uid_B').map((o) => o.id)).toEqual(['opB']);

    // Corrupt queue quarantine
    localBackend.setItem('sora_web_sync_queue_v2_uid_C', '{not-valid-json');
    const qC = store.loadQueue('uid_C');
    expect(qC).toEqual([]);
    const corruptKeys = Object.keys(localBackend.dump()).filter((k) => k.startsWith('sora_web_sync_corrupt_uid_C_'));
    expect(corruptKeys.length).toBe(1);
    expect(store.loadSyncMeta('uid_C').status).toBe('ERROR');

    // Retry + dead-letter requeue
    const deadRes = await flushSyncQueueWithRetry(
      store,
      async () => {
        throw new Error('500 server down');
      },
      true,
      false,
      2,
      'uid_A'
    );
    expect(deadRes.status).toBe('ERROR');
    expect(deadRes.deadLetterCount).toBe(1);
    expect(store.loadQueue('uid_A')).toHaveLength(0);
    expect(store.loadDeadLetterQueue('uid_A')).toHaveLength(1);

    expect(store.requeueDeadLetters('uid_A')).toBe(1);
    const okRes = await flushSyncQueueWithRetry(store, async () => {}, true, false, 2, 'uid_A');
    expect(okRes.status).toBe('SYNCED');
    expect(okRes.pendingCount).toBe(0);
  });

  it('fails closed when cloud credentials/session are missing and executes real Firebase REST Auth/Sync/Delete flows', async () => {
    const op = { id: 'o1', opType: 'UPSERT_NOTE' as const, entityId: 'n1', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' };
    const env = { apiKey: 'web-key', projectId: 'demo-proj', databaseId: 'db1' };

    await expect(pushSyncOperationToFirestore(op, null, env)).rejects.toThrow(FirebaseSyncError);
    await expect(
      pushSyncOperationToFirestore(op, { uid: '', email: '', displayName: '', idToken: '', refreshToken: '', expiresAtMillis: 0 }, env)
    ).rejects.toThrow(FirebaseSyncError);
    await expect(
      pushSyncOperationToFirestore(
        op,
        { uid: 'u1', email: 'a@b.c', displayName: 'A', idToken: 'tok', refreshToken: 'r', expiresAtMillis: Date.now() + 60_000 },
        { apiKey: '', projectId: '', databaseId: 'db1' }
      )
    ).rejects.toThrow(FirebaseSyncError);

    const calls: Array<{ url: string; method: string; body?: string }> = [];
    const mockFetch: typeof fetch = async (input, init) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
      if (url.includes('accounts:signUp')) {
        return new Response(JSON.stringify({ localId: 'uid_real', email: 's@edu.org', idToken: 'id1', refreshToken: 'ref1', expiresIn: '3600' }), { status: 200 });
      }
      if (url.includes('accounts:signInWithPassword')) {
        return new Response(JSON.stringify({ localId: 'uid_real', email: 's@edu.org', displayName: 'Student', idToken: 'id2', refreshToken: 'ref2', expiresIn: '3600' }), { status: 200 });
      }
      if (url.includes('securetoken.googleapis.com')) {
        return new Response(JSON.stringify({ user_id: 'uid_real', id_token: 'id3', refresh_token: 'ref3', expires_in: '3600' }), { status: 200 });
      }
      if (url.includes('/documents/users/uid_real') && method === 'GET' && !url.includes('/books') && !url.includes('/notes') && !url.includes('/exams') && !url.includes('/preferences')) {
        return new Response('missing', { status: 404 });
      }
      if (url.includes(':commit')) {
        return new Response(JSON.stringify({ writeResults: [{}] }), { status: 200 });
      }
      return new Response(JSON.stringify({ documents: [] }), { status: 200 });
    };

    const s1 = await signUpWithEmail('s@edu.org', 'secret123', 'Nadia', env, mockFetch);
    expect(s1.uid).toBe('uid_real');
    expect(calls.some((c) => c.url.includes(':commit') && c.body?.includes('REQUEST_TIME'))).toBe(true);

    const s2 = await signInWithEmail('s@edu.org', 'secret123', env, mockFetch);
    expect(s2.idToken).toBe('id2');

    const refreshed = await refreshAuthSession(s2, env, mockFetch);
    expect(refreshed.idToken).toBe('id3');

    await pushSyncOperationToFirestore(
      { ...op, payloadJson: JSON.stringify({ title: 'Optics', contentMarkdown: 'Snell law' }) },
      refreshed,
      env,
      mockFetch
    );

    const pulled = await pullWorkspaceFromFirestore(refreshed, env, mockFetch);
    expect(pulled.books).toEqual([]);

    const del = await deleteAllCloudUserData(refreshed, env, mockFetch);
    expect(del.ok).toBe(true);
  });

  it('enforces SSRF guards, header-only Gemini auth, privacy mode, safe math evaluator, and SM-2 schedule', async () => {
    expect(validateOllamaEndpoint('http://localhost:11434/').valid).toBe(true);
    expect(validateOllamaEndpoint('http://0.0.0.0:11434').valid).toBe(false);
    expect(validateOllamaEndpoint('http://169.254.169.254/latest/meta-data').valid).toBe(false);
    expect(validateOllamaEndpoint('http://metadata.google.internal').valid).toBe(false);
    expect(validateOllamaEndpoint('http://[::ffff:169.254.169.254]:80').valid).toBe(false);
    expect(validateOllamaEndpoint('http://remote-ollama.example.com:11434').valid).toBe(false);
    expect(validateOllamaEndpoint('http://user:pass@localhost:11434').valid).toBe(false);

    expect(redactSecrets('fail key=AIzaSy1234567890123456 & Bearer tok123')).not.toContain('AIzaSy1234567890123456');

    let seenUrl = '';
    let seenHeaderKey = '';
    const mockFetch: typeof fetch = async (url, init) => {
      seenUrl = String(url);
      if (seenUrl.includes('11434')) return new Response('down', { status: 503 });
      seenHeaderKey = (init?.headers as Record<string, string>)?.['x-goog-api-key'] ?? '';
      expect(init?.redirect).toBe('error');
      return new Response(
        JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Fallback Gemini answer' }] } }] }),
        { status: 200 }
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
        geminiApiKey: '',
        timeoutMs: 5000
      },
      mockFetch,
      { localOnlyPrivacyMode: false, runtimeGeminiApiKey: 'AIzaTestHeaderKey999' }
    );
    expect(res.provider).toBe('GEMINI');
    expect(seenUrl).not.toContain('key=');
    expect(seenHeaderKey).toBe('AIzaTestHeaderKey999');

    await expect(
      generateWithRouter(
        'Test privacy',
        'Sys',
        {
          strategy: 'GEMINI_ONLY',
          fallbackEnabled: false,
          ollamaEndpoint: 'http://localhost:11434',
          ollamaModel: 'llama3.2:3b',
          geminiModel: 'gemini-3-flash-preview',
          geminiApiKey: '',
          timeoutMs: 5000
        },
        mockFetch,
        { localOnlyPrivacyMode: true, runtimeGeminiApiKey: 'AIzaTestHeaderKey999' }
      )
    ).rejects.toThrow(/Local-Only Privacy Mode/);

    expect(evaluateMathExpression('(12 + 8) * 3.5')).toBe('70');
    expect(evaluateMathExpression('2 ^ 5 - 2')).toBe('30');
    expect(evaluateMathExpression('1 / 0')).toBe('Invalid expression');
    expect(evaluateMathExpression('process.exit(1)')).toBe('Invalid expression');

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
    expect(nextSm2Schedule(card, 'EASY', 1_000_000).intervalDays).toBe(6);

    const merged = ConflictResolver.mergeReadingPage(10, 18, 5000, 5500);
    expect(merged.page).toBe(18);
  });

  it('verifies service worker safety policies (bounded cache, no secrets/cross-origin caching)', () => {
    expect(swSource).toContain('sora-static-v2');
    expect(swSource).toContain('url.origin !== self.location.origin');
    expect(swSource).toContain("url.searchParams.has('token')");
    expect(swSource).toContain('MAX_STATIC_ENTRIES');
  });
});
