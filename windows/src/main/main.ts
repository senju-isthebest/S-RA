import * as fs from 'node:fs';
import * as path from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, safeStorage } from 'electron';
import { routeAiRequest, testOllamaConnection } from '../shared/aiRouter.js';
import { buildDesktopBook, retrievePassages } from '../shared/bookRetriever.js';
import { ConflictResolver, processSyncQueue } from '../shared/conflictResolver.js';
import { evaluateAnswerOffline } from '../shared/examEngine.js';
import {
  FirebaseDesktopClient,
  isFirebaseDesktopConfigured,
  readFirebaseDesktopConfig
} from './firebaseSyncClient.js';
import {
  validateAskBookArgs,
  validateEntityId,
  validateEvaluateAnswerArgs,
  validateSecretKeyName,
  validateSecretValue,
  validateWorkspaceStateInput
} from './ipcValidators.js';
import { extractPdfFromFile } from './pdfExtractor.js';
import { DesktopVaultStore, defaultDesktopWorkspace } from './vaultStore.js';

let vault: DesktopVaultStore;

export function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1220,
    height: 820,
    minWidth: 880,
    minHeight: 620,
    title: 'SŌRA',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event, targetUrl) => {
    // Allow only local packaged renderer HTML file
    if (!targetUrl.startsWith('file://')) {
      event.preventDefault();
    }
  });
  void win.loadFile(path.join(__dirname, '../../src/renderer/index.html'));
  return win;
}

function registerIpcHandlers(): void {
  const fbCfg = readFirebaseDesktopConfig();
  const fbClient = new FirebaseDesktopClient(fbCfg);

  ipcMain.handle('sora:loadState', () => vault.loadWorkspace());

  ipcMain.handle('sora:saveState', (_evt, nextState: unknown) => {
    const validated = validateWorkspaceStateInput(nextState);
    const session = vault.loadAuthSession();
    vault.saveWorkspace(validated);
    vault.enqueueOperation(
      {
        id: `op_${Date.now()}`,
        opType: 'SYNC_PREFS',
        entityId: 'app',
        payloadJson: JSON.stringify({
          languageCode: validated.language,
          aiStrategy: validated.aiConfig.strategy,
          ollamaModel: validated.aiConfig.ollamaModel,
          geminiModel: validated.aiConfig.geminiModel,
          aiTimeoutMs: validated.aiConfig.timeoutMs,
          clientUpdatedAtMillis: Date.now()
        }),
        createdAtMillis: Date.now(),
        attempts: 0,
        lastError: ''
      },
      session?.uid
    );
    return true;
  });

  ipcMain.handle('sora:openBookFile', async () => {
    const picked = await dialog.showOpenDialog({
      properties: ['openFile'],
      filters: [{ name: 'Study Materials', extensions: ['txt', 'md', 'pdf'] }]
    });
    if (picked.canceled || picked.filePaths.length === 0) return { book: null, error: null };
    const filePath = picked.filePaths[0];
    const ext = path.extname(filePath).toLowerCase();
    const title = path.basename(filePath, ext);
    const session = vault.loadAuthSession();

    let built: ReturnType<typeof buildDesktopBook>;
    if (ext === '.pdf') {
      const pdfRes = await extractPdfFromFile(filePath);
      if (!pdfRes.ok) {
        return { book: null, error: pdfRes.errorMessage ?? pdfRes.errorCode ?? 'PDF extraction failed' };
      }
      built = buildDesktopBook(title, 'General', pdfRes.fullText.slice(0, 300_000), pdfRes.pages);
    } else {
      const stat = fs.statSync(filePath);
      if (stat.size > 50 * 1024 * 1024) {
        return { book: null, error: 'Text file exceeds 50 MB size limit.' };
      }
      const decoded = fs.readFileSync(filePath, 'utf8');
      built = buildDesktopBook(title, 'General', decoded.slice(0, 300_000));
    }

    const ws = vault.loadWorkspace();
    ws.books.unshift(built.book);
    ws.questions.unshift(...built.questions);
    ws.flashcards.unshift(...built.flashcards);
    vault.saveWorkspace(ws);
    vault.enqueueOperation(
      {
        id: `op_${Date.now()}_b`,
        opType: 'UPSERT_BOOK',
        entityId: built.book.id,
        payloadJson: JSON.stringify({
          title: built.book.title,
          subjectName: built.book.subjectName,
          totalPages: built.book.totalPages,
          processedPages: built.book.totalPages,
          currentPage: built.book.currentPage
        }),
        createdAtMillis: Date.now(),
        attempts: 0,
        lastError: ''
      },
      session?.uid
    );
    return { book: built.book, error: null };
  });

  ipcMain.handle('sora:deleteBook', (_evt, rawBookId: unknown) => {
    const bookId = validateEntityId(rawBookId);
    const session = vault.loadAuthSession();
    const ws = vault.loadWorkspace();
    ws.books = ws.books.filter((b) => b.id !== bookId);
    ws.questions = ws.questions.filter((q) => q.bookId !== bookId);
    ws.flashcards = ws.flashcards.filter((c) => c.bookId !== bookId);
    ws.notes = ws.notes.filter((n) => n.bookId !== bookId);
    vault.saveWorkspace(ws);
    vault.enqueueOperation(
      {
        id: `op_${Date.now()}_delb`,
        opType: 'DELETE_BOOK',
        entityId: bookId,
        payloadJson: JSON.stringify({ deletedAtMillis: Date.now() }),
        createdAtMillis: Date.now(),
        attempts: 0,
        lastError: ''
      },
      session?.uid
    );
    return true;
  });

  ipcMain.handle('sora:askBook', async (_evt, rawBookId: unknown, rawQuery: unknown) => {
    const { bookId, query } = validateAskBookArgs(rawBookId, rawQuery);
    const ws = vault.loadWorkspace();
    const book = ws.books.find((b) => b.id === bookId);
    if (!book) return { passages: [], answer: 'Book not found.' };
    const passages = retrievePassages(query, book.pages, book.chapters, 5, 6000);
    const contextText = passages.map((p) => `[Ch.${p.chapterNumber || 1}, p.${p.pageNumber}] ${p.excerpt}`).join('\n\n');
    const geminiKey = vault.getSecret('gemini_api_key');
    try {
      const res = await routeAiRequest(
        `Textbook: ${book.title}\nQuestion: ${query}\n\nPassages:\n${contextText}`,
        `Answer accurately using textbook citations in ${ws.language === 'ar' ? 'Arabic' : 'English'}.`,
        ws.aiConfig,
        geminiKey,
        fetch,
        { localOnlyPrivacyMode: ws.localOnlyPrivacyMode }
      );
      return { passages, answer: `${res.text}\n\n(${res.provider} • ${res.model})` };
    } catch (err) {
      const msg = err instanceof Error ? err.message : '';
      return {
        passages,
        answer:
          passages.length > 0
            ? `${msg ? `[${msg}]\n\n` : ''}${passages.map((p) => `• [Ch.${p.chapterNumber || 1}, p.${p.pageNumber}] ${p.excerpt.slice(0, 220)}…`).join('\n\n')}`
            : msg || 'Configure Ollama or encrypted Gemini key.'
      };
    }
  });

  ipcMain.handle('sora:analyzeChapters', async (_evt, rawBookId: unknown) => {
    const bookId = validateEntityId(rawBookId);
    const ws = vault.loadWorkspace();
    const idx = ws.books.findIndex((b) => b.id === bookId);
    if (idx < 0) throw new Error('Book not found');
    const book = ws.books[idx];
    const geminiKey = vault.getSecret('gemini_api_key');
    const updatedChapters = [...book.chapters];
    for (let i = 0; i < updatedChapters.length; i++) {
      const ch = updatedChapters[i];
      const text = book.pages
        .filter((p) => p.pageNumber >= ch.startPage && p.pageNumber <= ch.endPage)
        .map((p) => `[p.${p.pageNumber}] ${p.text}`)
        .join('\n')
        .slice(0, 6000);
      const res = await routeAiRequest(
        `Summarize Chapter ${ch.chapterNumber} (${ch.title}) concisely:\n\n${text}`,
        `Respond in ${ws.language === 'ar' ? 'Arabic' : 'English'} with bullet takeaways.`,
        ws.aiConfig,
        geminiKey,
        fetch,
        { localOnlyPrivacyMode: ws.localOnlyPrivacyMode }
      );
      updatedChapters[i] = { ...ch, summary: res.text.slice(0, 1200) };
    }
    ws.books[idx] = { ...book, chapters: updatedChapters, lastUpdatedMillis: Date.now() };
    vault.saveWorkspace(ws);
    return ws.books[idx];
  });

  ipcMain.handle('sora:testOllama', async () => {
    const ws = vault.loadWorkspace();
    return testOllamaConnection(ws.aiConfig);
  });

  ipcMain.handle('sora:evaluateExamAnswer', (_evt, rawQuestion: unknown, rawAnswer: unknown) => {
    const { question, answer } = validateEvaluateAnswerArgs(rawQuestion, rawAnswer);
    return evaluateAnswerOffline(question, answer);
  });

  ipcMain.handle('sora:setSecret', (_evt, rawKeyName: unknown, rawValue: unknown) => {
    const keyName = validateSecretKeyName(rawKeyName);
    const value = validateSecretValue(rawValue);
    vault.setSecret(keyName, value);
    return true;
  });

  ipcMain.handle('sora:getSecretStatus', (_evt, rawKeyName: unknown) => {
    const keyName = validateSecretKeyName(rawKeyName);
    return vault.getSecret(keyName).length > 0;
  });

  ipcMain.handle('sora:exportJson', async () => {
    const json = vault.exportNonSecretJson();
    const saved = await dialog.showSaveDialog({
      defaultPath: 'sora_desktop_backup.json',
      filters: [{ name: 'JSON', extensions: ['json'] }]
    });
    if (!saved.canceled && saved.filePath) {
      fs.writeFileSync(saved.filePath, json, 'utf8');
      return saved.filePath;
    }
    return null;
  });

  ipcMain.handle('sora:authStatus', () => {
    const profile = vault.loadAuthSession();
    return {
      configured: isFirebaseDesktopConfigured(fbCfg),
      signedIn: Boolean(profile),
      uid: profile?.uid ?? '',
      email: profile?.email ?? '',
      displayName: profile?.displayName ?? ''
    };
  });

  ipcMain.handle('sora:authSignIn', async (_evt, email: unknown, password: unknown) => {
    if (typeof email !== 'string' || typeof password !== 'string') throw new Error('Invalid credentials');
    const session = await fbClient.signIn(email, password);
    vault.saveAuthSession(session);
    return { uid: session.uid, email: session.email, displayName: session.displayName };
  });

  ipcMain.handle('sora:authSignUp', async (_evt, email: unknown, password: unknown, displayName: unknown) => {
    if (typeof email !== 'string' || typeof password !== 'string') throw new Error('Invalid credentials');
    const session = await fbClient.signUp(email, password, typeof displayName === 'string' ? displayName : '');
    vault.saveAuthSession(session);
    return { uid: session.uid, email: session.email, displayName: session.displayName };
  });

  ipcMain.handle('sora:authSignOut', () => {
    vault.saveAuthSession(null);
    return true;
  });

  ipcMain.handle('sora:authDeleteAccount', async (_evt, confirmText: unknown) => {
    if (confirmText !== 'DELETE') throw new Error('Confirmation text DELETE required.');
    const session = vault.loadAuthSession();
    if (!session) throw new Error('Not signed in.');
    const fresh = await fbClient.ensureFreshSession(session);
    const wipe = await fbClient.deleteAllCloudUserData(fresh);
    if (!wipe.ok) {
      throw new Error(`Cloud data wipe incomplete: ${wipe.errors[0] ?? 'unknown'}`);
    }
    await fbClient.deleteAccount(fresh);
    vault.clearAccountData(fresh.uid);
    vault.saveWorkspace(defaultDesktopWorkspace());
    return true;
  });

  ipcMain.handle('sora:retryDeadLetters', () => {
    const session = vault.loadAuthSession();
    return vault.requeueDeadLetters(session?.uid);
  });

  ipcMain.handle('sora:syncNow', async () => {
    const ws = vault.loadWorkspace();
    const session = vault.loadAuthSession();
    const uid = session?.uid;
    const queue = vault.loadQueue(uid);

    if (ws.localOnlyPrivacyMode) {
      const meta = {
        ...vault.loadSyncMeta(uid),
        status: queue.length > 0 ? ('OFFLINE_QUEUED' as const) : ('IDLE' as const),
        pendingCount: queue.length,
        localOnlyMode: true
      };
      vault.saveSyncMeta(meta, uid);
      return meta;
    }

    if (!isFirebaseDesktopConfigured(fbCfg)) {
      const errMeta = {
        ...vault.loadSyncMeta(uid),
        status: 'ERROR' as const,
        pendingCount: queue.length + vault.loadDeadLetters(uid).length,
        lastError: 'CLOUD_NOT_CONFIGURED: Firebase desktop env vars missing.'
      };
      vault.saveSyncMeta(errMeta, uid);
      return errMeta;
    }

    if (!session) {
      const errMeta = {
        ...vault.loadSyncMeta(uid),
        status: 'ERROR' as const,
        pendingCount: queue.length + vault.loadDeadLetters(uid).length,
        lastError: 'AUTH_REQUIRED: Sign in to sync with SŌRA Cloud.'
      };
      vault.saveSyncMeta(errMeta, uid);
      return errMeta;
    }

    try {
      const fresh = await fbClient.ensureFreshSession(session);
      if (fresh.idToken !== session.idToken) vault.saveAuthSession(fresh);
      await fbClient.ensureUserProfile(fresh);

      const res = await processSyncQueue(queue, (op) => fbClient.pushOperation(op, fresh));
      const maxAttempts = 4;
      const dead = [...vault.loadDeadLetters(fresh.uid)];
      const activeRemaining = res.remaining.filter((op) => {
        if (op.attempts >= maxAttempts) {
          dead.push(op);
          return false;
        }
        return true;
      });
      vault.saveQueue(activeRemaining, fresh.uid);
      vault.saveDeadLetters(dead, fresh.uid);

      const pulled = await fbClient.pullWorkspace(fresh);
      let conflicts = 0;
      const mergedWs = vault.loadWorkspace();
      const bMap = new Map(mergedWs.books.map((b) => [b.id, b]));
      for (const rb of pulled.books) {
        const lb = bMap.get(rb.id);
        if (!lb) bMap.set(rb.id, rb);
        else {
          const m = ConflictResolver.mergeBook(lb, rb);
          if (m.hadConflict) conflicts++;
          bMap.set(rb.id, m.book);
        }
      }
      const nMap = new Map(mergedWs.notes.map((n) => [n.id, n]));
      for (const rn of pulled.notes) {
        const ln = nMap.get(rn.id);
        if (!ln || rn.updatedAtMillis > ln.updatedAtMillis) {
          nMap.set(rn.id, rn);
        }
      }
      mergedWs.books = Array.from(bMap.values());
      mergedWs.notes = Array.from(nMap.values());
      vault.saveWorkspace(mergedWs);

      const pendingCount = activeRemaining.length + dead.length;
      const finalMeta = {
        status:
          pendingCount > 0
            ? ('ERROR' as const)
            : conflicts > 0
              ? ('CONFLICT_RESOLVED' as const)
              : ('SYNCED' as const),
        pendingCount,
        deadLetterCount: dead.length,
        conflictsResolved: conflicts,
        lastSyncedAtMillis: pendingCount === 0 ? Date.now() : vault.loadSyncMeta(fresh.uid).lastSyncedAtMillis,
        lastError: pendingCount === 0 ? '' : (activeRemaining[0]?.lastError ?? dead[0]?.lastError ?? 'Sync failed'),
        localOnlyMode: false
      };
      vault.saveSyncMeta(finalMeta, fresh.uid);
      return finalMeta;
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Cloud sync failed';
      const errMeta = {
        ...vault.loadSyncMeta(uid),
        status: 'ERROR' as const,
        lastError: msg
      };
      vault.saveSyncMeta(errMeta, uid);
      return errMeta;
    }
  });
}

void app.whenReady().then(() => {
  vault = new DesktopVaultStore(path.join(app.getPath('userData'), 'sora-vault'), safeStorage);
  registerIpcHandlers();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
