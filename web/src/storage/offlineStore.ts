import type {
  Book,
  SoraWorkspaceState,
  SyncOperation,
  SyncState,
  UserSettings
} from '../types/sora';

const STATE_KEY = 'sora_web_workspace_v1';
const QUEUE_KEY = 'sora_web_sync_queue_v1';
const SYNC_META_KEY = 'sora_web_sync_meta_v1';

export interface StorageBackend {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

class MemoryStorage implements StorageBackend {
  private map = new Map<string, string>();
  getItem(key: string): string | null {
    return this.map.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
}

export function defaultSettings(): UserSettings {
  return {
    language: 'en',
    localOnlyPrivacyMode: false,
    updatedAtMillis: 0,
    aiConfig: {
      strategy: 'AUTO',
      fallbackEnabled: true,
      ollamaEndpoint: 'http://localhost:11434',
      ollamaModel: 'llama3.2:3b',
      geminiModel: 'gemini-3-flash-preview',
      geminiApiKey: '',
      timeoutMs: 30_000
    }
  };
}

export function defaultWorkspaceState(): SoraWorkspaceState {
  return {
    books: [],
    questions: [],
    flashcards: [],
    exams: [],
    notes: [],
    settings: defaultSettings()
  };
}

export const ConflictResolver = {
  mergeReadingPage(localPage: number, remotePage: number, localUpdated: number, remoteUpdated: number): { page: number; hadConflict: boolean } {
    const l = Math.max(1, localPage);
    const r = Math.max(1, remotePage);
    if (l === r) return { page: l, hadConflict: false };
    if (Math.abs(localUpdated - remoteUpdated) <= 1500) {
      return { page: Math.max(l, r), hadConflict: true };
    }
    return { page: remoteUpdated > localUpdated ? r : l, hadConflict: true };
  },

  mergeBookmarks(local: number[], remote: number[]): { merged: number[]; hadConflict: boolean } {
    const merged = Array.from(new Set([...local, ...remote].filter((n) => n >= 1))).sort((a, b) => a - b).slice(0, 500);
    const hadConflict = JSON.stringify(local.slice().sort()) !== JSON.stringify(remote.slice().sort());
    return { merged, hadConflict };
  },

  mergeBook(local: Book, remote: Book): { book: Book; hadConflict: boolean } {
    const p = this.mergeReadingPage(local.currentPage, remote.currentPage, local.lastUpdatedMillis, remote.lastUpdatedMillis);
    const bm = this.mergeBookmarks(local.bookmarkedPages, remote.bookmarkedPages);
    const hlMerged: Record<number, string[]> = { ...local.highlightsByPage };
    let hlConflict = false;
    for (const [k, vals] of Object.entries(remote.highlightsByPage)) {
      const pageNum = Number(k);
      const prev = hlMerged[pageNum] ?? [];
      const union = Array.from(new Set([...prev, ...vals]));
      if (union.length !== prev.length && prev.length > 0) hlConflict = true;
      hlMerged[pageNum] = union;
    }
    return {
      book: {
        ...local,
        currentPage: p.page,
        lastUpdatedMillis: Math.max(local.lastUpdatedMillis, remote.lastUpdatedMillis),
        bookmarkedPages: bm.merged,
        highlightsByPage: hlMerged
      },
      hadConflict: p.hadConflict || bm.hadConflict || hlConflict
    };
  }
};

export function createBrowserStorage(): StorageBackend {
  if (typeof window !== 'undefined' && window.localStorage) {
    return window.localStorage;
  }
  return new MemoryStorage();
}

export class OfflineWorkspaceStore {
  constructor(private readonly storage: StorageBackend = createBrowserStorage()) {}

  loadWorkspace(): SoraWorkspaceState {
    try {
      const raw = this.storage.getItem(STATE_KEY);
      if (!raw) return defaultWorkspaceState();
      const parsed = JSON.parse(raw) as Partial<SoraWorkspaceState>;
      const def = defaultWorkspaceState();
      return {
        books: parsed.books ?? [],
        questions: parsed.questions ?? [],
        flashcards: parsed.flashcards ?? [],
        exams: parsed.exams ?? [],
        notes: parsed.notes ?? [],
        settings: {
          ...def.settings,
          ...(parsed.settings ?? {}),
          aiConfig: {
            ...def.settings.aiConfig,
            ...(parsed.settings?.aiConfig ?? {})
          }
        }
      };
    } catch {
      return defaultWorkspaceState();
    }
  }

  saveWorkspace(state: SoraWorkspaceState): void {
    this.storage.setItem(STATE_KEY, JSON.stringify(state));
  }

  loadQueue(): SyncOperation[] {
    try {
      const raw = this.storage.getItem(QUEUE_KEY);
      return raw ? (JSON.parse(raw) as SyncOperation[]) : [];
    } catch {
      return [];
    }
  }

  saveQueue(queue: SyncOperation[]): void {
    this.storage.setItem(QUEUE_KEY, JSON.stringify(queue));
  }

  loadSyncMeta(): SyncState {
    const queue = this.loadQueue();
    try {
      const raw = this.storage.getItem(SYNC_META_KEY);
      const parsed = raw ? (JSON.parse(raw) as Partial<SyncState>) : {};
      return {
        status: queue.length > 0 ? 'OFFLINE_QUEUED' : (parsed.status ?? 'IDLE'),
        pendingCount: queue.length,
        conflictsResolved: parsed.conflictsResolved ?? 0,
        lastSyncedAtMillis: parsed.lastSyncedAtMillis ?? 0,
        lastError: parsed.lastError ?? '',
        localOnlyMode: parsed.localOnlyMode ?? false
      };
    } catch {
      return {
        status: 'IDLE',
        pendingCount: queue.length,
        conflictsResolved: 0,
        lastSyncedAtMillis: 0,
        lastError: '',
        localOnlyMode: false
      };
    }
  }

  saveSyncMeta(meta: SyncState): void {
    this.storage.setItem(SYNC_META_KEY, JSON.stringify(meta));
  }

  exportNonSecretJson(state: SoraWorkspaceState): string {
    const sanitized = {
      exportedAtMillis: Date.now(),
      language: state.settings.language,
      localOnlyPrivacyMode: state.settings.localOnlyPrivacyMode,
      books: state.books.map((b) => ({
        id: b.id,
        title: b.title,
        subjectName: b.subjectName,
        totalPages: b.totalPages,
        currentPage: b.currentPage,
        bookmarkedPages: b.bookmarkedPages,
        highlightsByPage: b.highlightsByPage
      })),
      notes: state.notes,
      flashcards: state.flashcards,
      exams: state.exams
    };
    return JSON.stringify(sanitized, null, 2);
  }

  clearAll(): void {
    this.storage.removeItem(STATE_KEY);
    this.storage.removeItem(QUEUE_KEY);
    this.storage.removeItem(SYNC_META_KEY);
  }
}

export async function flushSyncQueueWithRetry(
  store: OfflineWorkspaceStore,
  executor: (op: SyncOperation) => Promise<void>,
  isOnline: boolean,
  localOnlyPrivacyMode: boolean,
  maxAttempts = 4
): Promise<SyncState> {
  const queue = store.loadQueue();
  const prevMeta = store.loadSyncMeta();
  if (localOnlyPrivacyMode || !isOnline) {
    const paused: SyncState = {
      ...prevMeta,
      status: queue.length > 0 ? 'OFFLINE_QUEUED' : 'IDLE',
      pendingCount: queue.length,
      localOnlyMode: localOnlyPrivacyMode
    };
    store.saveSyncMeta(paused);
    return paused;
  }

  const remaining: SyncOperation[] = [];
  let lastError = '';
  for (const op of queue) {
    let sent = false;
    let attempt = op.attempts;
    let errMsg = '';
    while (attempt < maxAttempts && !sent) {
      try {
        await executor(op);
        sent = true;
      } catch (err) {
        attempt++;
        errMsg = err instanceof Error ? err.message : 'Sync retry failed';
      }
    }
    if (!sent) {
      lastError = errMsg;
      remaining.push({ ...op, attempts: attempt, lastError: errMsg });
    }
  }
  store.saveQueue(remaining);
  const next: SyncState = {
    ...prevMeta,
    status: remaining.length === 0 ? 'SYNCED' : 'ERROR',
    pendingCount: remaining.length,
    lastSyncedAtMillis: remaining.length === 0 ? Date.now() : prevMeta.lastSyncedAtMillis,
    lastError,
    localOnlyMode: false
  };
  store.saveSyncMeta(next);
  return next;
}
