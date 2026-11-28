import * as fs from 'node:fs';
import * as path from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, safeStorage } from 'electron';
import { routeAiRequest } from '../shared/aiRouter';
import { buildDesktopBook, retrievePassages } from '../shared/bookRetriever';
import { processSyncQueue } from '../shared/conflictResolver';
import { evaluateAnswerOffline } from '../shared/examEngine';
import type { DesktopWorkspaceState, QuestionItem } from '../shared/models';
import { DesktopVaultStore } from './vaultStore';

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
  void win.loadFile(path.join(__dirname, '../../src/renderer/index.html'));
  return win;
}

function registerIpcHandlers(): void {
  ipcMain.handle('sora:loadState', () => vault.loadWorkspace());

  ipcMain.handle('sora:saveState', (_evt, nextState: unknown) => {
    const state = nextState as DesktopWorkspaceState;
    vault.saveWorkspace(state);
    vault.enqueueOperation({
      id: `op_${Date.now()}`,
      opType: 'SYNC_PREFS',
      entityId: 'workspace',
      payloadJson: JSON.stringify({ language: state.language, booksCount: state.books.length }),
      createdAtMillis: Date.now(),
      attempts: 0,
      lastError: ''
    });
    return true;
  });

  ipcMain.handle('sora:openBookFile', async () => {
    const picked = await dialog.showOpenDialog({
      properties: ['openFile'],
      filters: [{ name: 'Study Materials', extensions: ['txt', 'md', 'pdf'] }]
    });
    if (picked.canceled || picked.filePaths.length === 0) return null;
    const filePath = picked.filePaths[0];
    const ext = path.extname(filePath).toLowerCase();
    const rawBuf = fs.readFileSync(filePath);
    const decoded = rawBuf.toString('utf8');
    const text =
      ext === '.pdf'
        ? (decoded.match(/\(([^()]{4,200})\)/g) ?? []).map((m) => m.slice(1, -1)).join('\n') ||
          decoded.replace(/[^\p{L}\p{N}\s.,;:?!()\-\n]/gu, ' ')
        : decoded;
    const title = path.basename(filePath, ext);
    const built = buildDesktopBook(title, 'General', text.slice(0, 150_000));
    const ws = vault.loadWorkspace();
    ws.books.unshift(built.book);
    ws.questions.unshift(...built.questions);
    ws.flashcards.unshift(...built.flashcards);
    vault.saveWorkspace(ws);
    return built.book;
  });

  ipcMain.handle('sora:askBook', async (_evt, bookId: unknown, query: unknown) => {
    const ws = vault.loadWorkspace();
    const book = ws.books.find((b) => b.id === String(bookId));
    if (!book) return { passages: [], answer: 'Book not found.' };
    const qStr = String(query ?? '').trim();
    const passages = retrievePassages(qStr, book.pages, book.chapters, 5, 6000);
    const contextText = passages.map((p) => `[Ch.${p.chapterNumber || 1}, p.${p.pageNumber}] ${p.excerpt}`).join('\n\n');
    const geminiKey = vault.getSecret('gemini_api_key');
    try {
      const res = await routeAiRequest(
        `Textbook: ${book.title}\nQuestion: ${qStr}\n\nPassages:\n${contextText}`,
        `Answer accurately using textbook citations in ${ws.language === 'ar' ? 'Arabic' : 'English'}.`,
        ws.aiConfig,
        geminiKey
      );
      return { passages, answer: `${res.text}\n\n(${res.provider} • ${res.model})` };
    } catch {
      return {
        passages,
        answer: passages.map((p) => `• [Ch.${p.chapterNumber || 1}, p.${p.pageNumber}] ${p.excerpt.slice(0, 220)}…`).join('\n\n')
      };
    }
  });

  ipcMain.handle('sora:evaluateExamAnswer', (_evt, question: unknown, answer: unknown) => {
    return evaluateAnswerOffline(question as QuestionItem, String(answer ?? ''));
  });

  ipcMain.handle('sora:setSecret', (_evt, keyName: unknown, value: unknown) => {
    vault.setSecret(String(keyName), String(value ?? ''));
    return true;
  });

  ipcMain.handle('sora:getSecretStatus', (_evt, keyName: unknown) => {
    return vault.getSecret(String(keyName)).length > 0;
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

  ipcMain.handle('sora:syncNow', async () => {
    const ws = vault.loadWorkspace();
    const queue = vault.loadQueue();
    if (ws.localOnlyPrivacyMode) {
      return { status: 'OFFLINE_QUEUED', pendingCount: queue.length, localOnlyMode: true };
    }
    const res = await processSyncQueue(queue, async () => {
      // Cloud endpoint hooked here when user links Firebase REST/CLI credentials
    });
    vault.saveQueue(res.remaining);
    return {
      status: res.remaining.length === 0 ? 'SYNCED' : 'ERROR',
      pendingCount: res.remaining.length,
      localOnlyMode: false
    };
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
