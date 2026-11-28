import * as assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { FirebaseDesktopClient, DesktopFirebaseError } from '../main/firebaseSyncClient.js';
import {
  validateAskBookArgs,
  validateEvaluateAnswerArgs,
  validateSecretKeyName,
  validateWorkspaceStateInput
} from '../main/ipcValidators.js';
import { extractPdfFromBuffer } from '../main/pdfExtractor.js';
import { DesktopVaultStore, assertSafeChildPath } from '../main/vaultStore.js';
import { redactSecrets, routeAiRequest, validateOllamaEndpoint } from '../shared/aiRouter.js';
import { buildDesktopBook, retrievePassages } from '../shared/bookRetriever.js';
import { ConflictResolver, processSyncQueue } from '../shared/conflictResolver.js';
import { evaluateAnswerOffline } from '../shared/examEngine.js';
import { DESKTOP_STRINGS } from '../shared/i18n.js';
import type { QuestionItem } from '../shared/models.js';

function buildMinimalPdf(pageTexts: string[]): Uint8Array {
  const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const objs: string[] = [];
  const pNums: number[] = [];
  let next = 4;
  for (let i = 0; i < pageTexts.length; i++) {
    pNums.push(next);
    next += 2;
  }
  objs.push('1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n');
  objs.push(`2 0 obj\n<< /Type /Pages /Kids [${pNums.map((n) => `${n} 0 R`).join(' ')}] /Count ${pageTexts.length} >>\nendobj\n`);
  objs.push('3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n');

  pageTexts.forEach((txt, idx) => {
    const pObj = pNums[idx];
    const cObj = pObj + 1;
    const stream = `BT /F1 12 Tf 72 720 Td (${esc(txt)}) Tj ET`;
    objs.push(`${pObj} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${cObj} 0 R >>\nendobj\n`);
    objs.push(`${cObj} 0 obj\n<< /Length ${Buffer.byteLength(stream, 'utf8')} >>\nstream\n${stream}\nendstream\nendobj\n`);
  });

  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  for (const o of objs) {
    offsets.push(Buffer.byteLength(pdf, 'utf8'));
    pdf += o;
  }
  const xref = Buffer.byteLength(pdf, 'utf8');
  pdf += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  for (let i = 1; i < offsets.length; i++) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Uint8Array.from(Buffer.from(pdf, 'utf8'));
}

test('i18n EN and AR keys match with zero blank entries', () => {
  const enKeys = Object.keys(DESKTOP_STRINGS.en).sort();
  const arKeys = Object.keys(DESKTOP_STRINGS.ar).sort();
  assert.deepEqual(arKeys, enKeys);
  for (const k of arKeys) {
    const val = DESKTOP_STRINGS.ar[k as keyof typeof DESKTOP_STRINGS.ar];
    assert.ok(val.trim().length > 0);
  }
});

test('DesktopVaultStore encrypts secrets, isolates UID queues, quarantines corrupt queues, and blocks path traversal', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sora-win-test-'));
  try {
    assert.throws(() => assertSafeChildPath(dir, '../../outside.json'), /Path traversal rejected/);

    const vault = new DesktopVaultStore(dir);
    vault.setSecret('gemini_api_key', 'AIzaWinVaultSecret888');
    assert.equal(vault.getSecret('gemini_api_key'), 'AIzaWinVaultSecret888');

    const rawVault = fs.readFileSync(path.join(dir, 'vault.enc.json'), 'utf8');
    assert.equal(rawVault.includes('AIzaWinVaultSecret888'), false);
    assert.ok(rawVault.includes('gcm:'));

    const exported = vault.exportNonSecretJson();
    assert.equal(exported.includes('AIzaWinVaultSecret888'), false);

    // Per-UID queue isolation & coalescing
    vault.enqueueOperation(
      { id: 'opA', opType: 'UPSERT_NOTE', entityId: 'n1', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' },
      'uid_A'
    );
    vault.enqueueOperation(
      { id: 'opB', opType: 'UPSERT_NOTE', entityId: 'n2', payloadJson: '{}', createdAtMillis: 2, attempts: 0, lastError: '' },
      'uid_B'
    );
    assert.deepEqual(vault.loadQueue('uid_A').map((o) => o.id), ['opA']);
    assert.deepEqual(vault.loadQueue('uid_B').map((o) => o.id), ['opB']);

    // Corrupt queue quarantine
    fs.writeFileSync(path.join(dir, 'sync_queue_uid_C.json'), '{broken', 'utf8');
    assert.deepEqual(vault.loadQueue('uid_C'), []);
    const files = fs.readdirSync(dir);
    assert.ok(files.some((f) => f.startsWith('sync_queue_uid_C.corrupt.')));
    assert.equal(vault.loadSyncMeta('uid_C').status, 'ERROR');

    // Auth tokens stored encrypted, never plaintext in auth_profile.json
    vault.saveAuthSession({
      uid: 'u_enc',
      email: 'student@sora.edu',
      displayName: 'Student',
      idToken: 'secret-id-tok',
      refreshToken: 'secret-ref-tok',
      expiresAtMillis: Date.now() + 60_000
    });
    const profRaw = fs.readFileSync(path.join(dir, 'auth_profile.json'), 'utf8');
    assert.equal(profRaw.includes('secret-id-tok'), false);
    assert.equal(profRaw.includes('secret-ref-tok'), false);
    assert.equal(vault.loadAuthSession()?.idToken, 'secret-id-tok');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Electron BrowserWindow enforces security flags, will-navigate block, and IPC validators reject unauthorized inputs', () => {
  const mainSource = fs.readFileSync(path.join(__dirname, '../../src/main/main.ts'), 'utf8');
  assert.ok(mainSource.includes('contextIsolation: true'));
  assert.ok(mainSource.includes('nodeIntegration: false'));
  assert.ok(mainSource.includes('sandbox: true'));
  assert.ok(mainSource.includes('webSecurity: true'));
  assert.ok(mainSource.includes("'will-navigate'"));
  assert.ok(mainSource.includes('setWindowOpenHandler'));

  assert.equal(validateSecretKeyName('gemini_api_key'), 'gemini_api_key');
  assert.throws(() => validateSecretKeyName('arbitrary_key'), /Unauthorized secret key name/);
  assert.throws(() => validateWorkspaceStateInput({ language: 'fr' }), /Invalid language/);
  assert.throws(() => validateAskBookArgs('', 'hello'), /Invalid bookId/);
  assert.throws(() => validateEvaluateAnswerArgs({}, 'ans'), /Malformed question/);
});

test('pdfExtractor extracts multi-page PDF buffers via pdfjs-dist and rejects corrupt or empty PDFs', async () => {
  const pdf = buildMinimalPdf(['Chapter 1 Kinematics Equations', 'Chapter 2 Thermal Equilibrium']);
  const res = await extractPdfFromBuffer(pdf);
  assert.equal(res.ok, true);
  assert.equal(res.pageCount, 2);
  assert.ok(res.pages[0].text.includes('Kinematics'));
  assert.ok(res.pages[1].text.includes('Thermal Equilibrium'));

  const corrupt = await extractPdfFromBuffer(new TextEncoder().encode('garbage'));
  assert.equal(corrupt.ok, false);
  assert.equal(corrupt.errorCode, 'CORRUPTED_PDF');

  const emptyPdf = buildMinimalPdf(['  ']);
  const emptyRes = await extractPdfFromBuffer(emptyPdf);
  assert.equal(emptyRes.ok, false);
  assert.equal(emptyRes.errorCode, 'EMPTY_TEXT_LAYER');

  const tooBig = await extractPdfFromBuffer(new Uint8Array(64), 32);
  assert.equal(tooBig.ok, false);
  assert.equal(tooBig.errorCode, 'FILE_TOO_LARGE');
});

test('Bilingual BM25 retriever indexes English and Arabic textbook chapters accurately', () => {
  const { book } = buildDesktopBook(
    'Physics Desk',
    'Physics',
    [
      'Newton second law: F = m * a.',
      'الفصل الثاني: الديناميكا الحرارية وإنتروبيا النظام المغلق.'
    ].join('\n\n')
  );
  const en = retrievePassages('Newton second law acceleration', book.pages, book.chapters, 2, 2000);
  assert.ok(en.length >= 1);
  assert.ok(en[0].excerpt.includes('Newton'));

  const ar = retrievePassages('الديناميكا الحراريه انتروبيا', book.pages, book.chapters, 2, 2000);
  assert.ok(ar.length >= 1);
  assert.ok(ar[0].excerpt.includes('الديناميكا'));
});

test('ExamEngine evaluates numeric tolerance, EN/AR booleans, open-ended, and adversarial MATCHING/ORDERING fixtures', () => {
  const base: QuestionItem = {
    id: 'q1',
    bookId: 'b1',
    chapterNumber: 1,
    sourcePage: 1,
    questionType: 'CALCULATION',
    difficulty: 'MEDIUM',
    marks: 4,
    questionText: 'Calculate g',
    options: [],
    correctAnswer: '9.81',
    explanation: ''
  };
  assert.equal(evaluateAnswerOffline(base, '9.8').isCorrect, true);
  assert.equal(evaluateAnswerOffline({ ...base, questionType: 'TRUE_FALSE', correctAnswer: 'True' }, 'صح').isCorrect, true);
  assert.equal(
    evaluateAnswerOffline(
      { ...base, questionType: 'EXPLAIN_JUSTIFY', correctAnswer: 'Entropy increases toward thermodynamic equilibrium' },
      'Entropy increases toward equilibrium'
    ).isCorrect,
    true
  );

  const matchQ: QuestionItem = {
    ...base,
    questionType: 'MATCHING',
    correctAnswer: 'Newton=Force; Joule=Energy; Watt=Power; Pascal=Pressure'
  };
  assert.equal(evaluateAnswerOffline(matchQ, 'Watt=Power\nNewton=Force\nJoule=Energy\nPascal=Pressure').isCorrect, true);
  assert.equal(
    evaluateAnswerOffline(matchQ, 'Newton=Force; Newton=Energy; Newton=Power; Newton=Pressure; Newton=Wrong').isCorrect,
    false
  );

  const orderQ: QuestionItem = {
    ...base,
    questionType: 'ORDERING',
    correctAnswer: '1995 -> 2005 -> 2015'
  };
  assert.equal(evaluateAnswerOffline(orderQ, '1. 1995 -> 2. 2005 -> 3. 2015').isCorrect, true);
  assert.equal(evaluateAnswerOffline(orderQ, '2015 -> 2005 -> 1995').isCorrect, false);
});

test('FirebaseDesktopClient fails closed without auth/config and supports real REST auth, commit, pull, and delete', async () => {
  const op = { id: 'o1', opType: 'UPSERT_NOTE' as const, entityId: 'n1', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' };
  const unconfigured = new FirebaseDesktopClient({ apiKey: '', projectId: '', databaseId: 'db1' });
  await assert.rejects(() => unconfigured.pushOperation(op, null), DesktopFirebaseError);

  const calls: string[] = [];
  const client = new FirebaseDesktopClient(
    { apiKey: 'desk-key', projectId: 'demo-proj', databaseId: 'db1' },
    async (url, init) => {
      const s = String(url);
      calls.push(`${init?.method ?? 'GET'} ${s}`);
      if (s.includes('accounts:signInWithPassword')) {
        return new Response(JSON.stringify({ localId: 'u_desk', email: 'd@sora.edu', idToken: 'tok1', refreshToken: 'ref1', expiresIn: '3600' }), { status: 200 });
      }
      if (s.includes('/documents/users/u_desk') && (init?.method ?? 'GET') === 'GET' && !s.includes('/books') && !s.includes('/notes')) {
        return new Response('missing', { status: 404 });
      }
      if (s.includes(':commit')) {
        return new Response(JSON.stringify({ writeResults: [{}] }), { status: 200 });
      }
      return new Response(JSON.stringify({ documents: [] }), { status: 200 });
    }
  );

  const session = await client.signIn('d@sora.edu', 'pw1234');
  assert.equal(session.uid, 'u_desk');
  assert.ok(calls.some((c) => c.includes(':commit')));

  await client.pushOperation({ ...op, payloadJson: JSON.stringify({ title: 'Note 1', contentMarkdown: 'Hi' }) }, session);
  const pulled = await client.pullWorkspace(session);
  assert.deepEqual(pulled.books, []);
  const del = await client.deleteAllCloudUserData(session);
  assert.equal(del.ok, true);
});

test('ConflictResolver, queue retries, SSRF guards, header-only Gemini key, and privacy mode enforce security', async () => {
  const merged = ConflictResolver.mergeReadingPage(8, 15, 2000, 2500);
  assert.equal(merged.page, 15);

  let attempt = 0;
  const res = await processSyncQueue(
    [{ id: 'op1', opType: 'UPSERT_BOOK', entityId: 'b1', payloadJson: '{}', createdAtMillis: 1, attempts: 0, lastError: '' }],
    async () => {
      attempt++;
      if (attempt === 1) throw new Error('Network glitch');
    }
  );
  assert.equal(res.remaining.length, 0);
  assert.equal(res.processedCount, 1);

  assert.equal(validateOllamaEndpoint('http://127.0.0.1:11434').valid, true);
  assert.equal(validateOllamaEndpoint('http://0.0.0.0:11434').valid, false);
  assert.equal(validateOllamaEndpoint('http://169.254.169.254').valid, false);
  assert.equal(validateOllamaEndpoint('http://metadata.google.internal').valid, false);
  assert.equal(validateOllamaEndpoint('http://user:pass@localhost:11434').valid, false);
  assert.equal(validateOllamaEndpoint('http://remote-server.example.com:11434').valid, false);
  assert.equal(redactSecrets('err key=AIzaSecret123456789012').includes('AIzaSecret'), false);

  let seenUrl = '';
  let seenHeader = '';
  const routed = await routeAiRequest(
    'Hi',
    'Sys',
    {
      strategy: 'OLLAMA_FIRST',
      fallbackEnabled: true,
      ollamaEndpoint: 'http://127.0.0.1:11434',
      ollamaModel: 'llama3.2:3b',
      geminiModel: 'gemini-3-flash-preview',
      timeoutMs: 5000
    },
    'key-123',
    async (url, init) => {
      seenUrl = String(url);
      if (seenUrl.includes('11434')) return new Response('err', { status: 502 });
      seenHeader = (init?.headers as Record<string, string>)?.['x-goog-api-key'] ?? '';
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Gemini OK' }] } }] }), { status: 200 });
    }
  );
  assert.equal(routed.provider, 'GEMINI');
  assert.equal(seenUrl.includes('key='), false);
  assert.equal(seenHeader, 'key-123');

  await assert.rejects(
    () =>
      routeAiRequest(
        'Hi',
        'Sys',
        {
          strategy: 'GEMINI_ONLY',
          fallbackEnabled: false,
          ollamaEndpoint: 'http://127.0.0.1:11434',
          ollamaModel: 'llama3.2:3b',
          geminiModel: 'gemini-3-flash-preview',
          timeoutMs: 5000
        },
        'key-123',
        fetch,
        { localOnlyPrivacyMode: true }
      ),
    /Local-Only Privacy Mode/
  );
});
