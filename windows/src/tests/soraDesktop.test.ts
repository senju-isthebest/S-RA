import * as assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { DesktopVaultStore, assertSafeChildPath } from '../main/vaultStore';
import { routeAiRequest, validateOllamaEndpoint } from '../shared/aiRouter';
import { buildDesktopBook, retrievePassages } from '../shared/bookRetriever';
import { ConflictResolver, processSyncQueue } from '../shared/conflictResolver';
import { evaluateAnswerOffline } from '../shared/examEngine';
import { DESKTOP_STRINGS } from '../shared/i18n';
import type { QuestionItem } from '../shared/models';

test('i18n EN and AR keys match with zero blank entries', () => {
  const enKeys = Object.keys(DESKTOP_STRINGS.en).sort();
  const arKeys = Object.keys(DESKTOP_STRINGS.ar).sort();
  assert.deepEqual(arKeys, enKeys);
  for (const k of arKeys) {
    const val = DESKTOP_STRINGS.ar[k as keyof typeof DESKTOP_STRINGS.ar];
    assert.ok(val.trim().length > 0);
  }
});

test('DesktopVaultStore encrypts secrets, blocks path traversal, and strips secrets from JSON export', () => {
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
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Electron BrowserWindow enforces contextIsolation, sandbox, and disabled nodeIntegration', () => {
  const mainSource = fs.readFileSync(path.join(__dirname, '../../src/main/main.ts'), 'utf8');
  assert.ok(mainSource.includes('contextIsolation: true'));
  assert.ok(mainSource.includes('nodeIntegration: false'));
  assert.ok(mainSource.includes('sandbox: true'));
  assert.ok(mainSource.includes('webSecurity: true'));
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

test('ExamEngine evaluates numeric tolerance, EN/AR booleans, and open-ended key terms', () => {
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
});

test('ConflictResolver and offline sync queue handle retries and endpoint validation safely', async () => {
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
  assert.equal(validateOllamaEndpoint('http://user:pass@localhost:11434').valid, false);

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
    async (url) => {
      if (String(url).includes('11434')) return new Response('err', { status: 502 });
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Gemini OK' }] } }] }), { status: 200 });
    }
  );
  assert.equal(routed.provider, 'GEMINI');
  assert.equal(routed.text, 'Gemini OK');
});
