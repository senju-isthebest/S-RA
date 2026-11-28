import type {
  AuthSession,
  Book,
  SoraWorkspaceState,
  SyncOperation,
  SyncState,
  UserSettings
} from '../types/sora';

const STATE_KEY = 'sora_web_workspace_v1';
const QUEUE_KEY_PREFIX = 'sora_web_sync_queue_v2_';
const DEAD_KEY_PREFIX = 'sora_web_sync_dead_v2_';
const META_KEY_PREFIX = 'sora_web_sync_meta_v2_';
const SESSION_AUTH_KEY = 'sora_web_auth_session_v1';
const SECRET_MEM_PLACEHOLDER = '__mem__';

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

function normalizeScopeUid(uid?: string): string {
  const clean = (uid ?? '').trim().replace(/[^a-zA-Z0-9_-]/g, '_');
  return clean || 'guest';
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

export function createSessionStorage(): StorageBackend {
  if (typeof window !== 'undefined' && window.sessionStorage) {
    return window.sessionStorage;
  }
  return new MemoryStorage();
}

/**
 * Holds optional runtime Gemini API keys in JS memory ONLY (never localStorage, never workspace JSON, never synced).
 * Users who explicitly enter a key may optionally mirror it to ephemeral sessionStorage wrapped with a per-tab
 * non-extractable WebCrypto AES-GCM key (subject to standard browser XSS threat model; cleared on sign-out/tab close).
 */
export class WebSessionSecretStore {
  private readonly mem = new Map<string, string>();
  private wrapKey: CryptoKey | null = null;

  constructor(private readonly sessionStore: StorageBackend = createSessionStorage()) {}

  setMemorySecret(name: string, value: string): void {
    const clean = value.trim();
    if (!clean) this.mem.delete(name);
    else this.mem.set(name, clean);
  }

  getMemorySecret(name: string): string {
    return this.mem.get(name) ?? '';
  }

  async setEphemeralSessionSecret(name: string, value: string): Promise<void> {
    this.setMemorySecret(name, value);
    const clean = value.trim();
    if (!clean) {
      this.sessionStore.removeItem(`sora_ephemeral_${name}`);
      return;
    }
    if (typeof globalThis.crypto?.subtle === 'undefined') {
      return;
    }
    try {
      if (!this.wrapKey) {
        this.wrapKey = await globalThis.crypto.subtle.generateKey(
          { name: 'AES-GCM', length: 256 },
          false,
          ['encrypt', 'decrypt']
        );
      }
      const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
      const encoded = new TextEncoder().encode(clean);
      const cipher = new Uint8Array(
        await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, this.wrapKey, encoded)
      );
      const combined = new Uint8Array(iv.length + cipher.length);
      combined.set(iv, 0);
      combined.set(cipher, iv.length);
      const b64 = btoa(String.fromCharCode(...combined));
      this.sessionStore.setItem(`sora_ephemeral_${name}`, `${SECRET_MEM_PLACEHOLDER}:${b64}`);
    } catch {
      // Memory-only remains active cleanly
    }
  }

  clearAll(): void {
    this.mem.clear();
    this.wrapKey = null;
    this.sessionStore.removeItem('sora_ephemeral_gemini_api_key');
  }
}

export class OfflineWorkspaceStore {
  private flushLocked = false;

  constructor(
    private readonly storage: StorageBackend = createBrowserStorage(),
    private readonly sessionStorage: StorageBackend = createSessionStorage()
  ) {}

  private queueKey(uid?: string): string {
    return `${QUEUE_KEY_PREFIX}${normalizeScopeUid(uid)}`;
  }

  private deadKey(uid?: string): string {
    return `${DEAD_KEY_PREFIX}${normalizeScopeUid(uid)}`;
  }

  private metaKey(uid?: string): string {
    return `${META_KEY_PREFIX}${normalizeScopeUid(uid)}`;
  }

  loadWorkspace(): SoraWorkspaceState {
    try {
      const raw = this.storage.getItem(STATE_KEY);
      if (!raw) return defaultWorkspaceState();
      const parsed = JSON.parse(raw) as Partial<SoraWorkspaceState>;
      const def = defaultWorkspaceState();
      // Always strip any legacy plaintext geminiApiKey from localStorage
      const hadLegacyKey = Boolean(parsed.settings?.aiConfig?.geminiApiKey);
      const loaded: SoraWorkspaceState = {
        books: Array.isArray(parsed.books) ? parsed.books : [],
        questions: Array.isArray(parsed.questions) ? parsed.questions : [],
        flashcards: Array.isArray(parsed.flashcards) ? parsed.flashcards : [],
        exams: Array.isArray(parsed.exams) ? parsed.exams : [],
        notes: Array.isArray(parsed.notes) ? parsed.notes : [],
        settings: {
          ...def.settings,
          ...(parsed.settings ?? {}),
          aiConfig: {
            ...def.settings.aiConfig,
            ...(parsed.settings?.aiConfig ?? {}),
            geminiApiKey: ''
          }
        }
      };
      if (hadLegacyKey) {
        this.saveWorkspace(loaded);
      }
      return loaded;
    } catch {
      return defaultWorkspaceState();
    }
  }

  saveWorkspace(state: SoraWorkspaceState): void {
    // Strip runtime secrets before persisting to localStorage
    const sanitized: SoraWorkspaceState = {
      ...state,
      settings: {
        ...state.settings,
        aiConfig: {
          ...state.settings.aiConfig,
          geminiApiKey: ''
        }
      }
    };
    this.storage.setItem(STATE_KEY, JSON.stringify(sanitized));
  }

  loadAuthSession(): AuthSession | null {
    try {
      const raw = this.sessionStorage.getItem(SESSION_AUTH_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as Partial<AuthSession>;
      if (!parsed.uid || !parsed.idToken) return null;
      return {
        uid: parsed.uid,
        email: parsed.email ?? '',
        displayName: parsed.displayName ?? '',
        idToken: parsed.idToken,
        refreshToken: parsed.refreshToken ?? '',
        expiresAtMillis: parsed.expiresAtMillis ?? 0
      };
    } catch {
      return null;
    }
  }

  saveAuthSession(session: AuthSession | null): void {
    if (!session) {
      this.sessionStorage.removeItem(SESSION_AUTH_KEY);
      return;
    }
    this.sessionStorage.setItem(SESSION_AUTH_KEY, JSON.stringify(session));
  }

  loadQueue(uid?: string): SyncOperation[] {
    const key = this.queueKey(uid);
    const raw = this.storage.getItem(key);
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) throw new Error('Queue is not an array');
      return parsed as SyncOperation[];
    } catch {
      // Preserve corrupt queue payload for diagnostics rather than silently dropping user edits
      const corruptKey = `sora_web_sync_corrupt_${normalizeScopeUid(uid)}_${Date.now()}`;
      this.storage.setItem(corruptKey, raw);
      this.storage.removeItem(key);
      const prevMeta = this.loadSyncMeta(uid);
      this.saveSyncMeta(
        {
          ...prevMeta,
          status: 'ERROR',
          lastError: `Sync queue JSON was corrupted and quarantined (${corruptKey}).`
        },
        uid
      );
      return [];
    }
  }

  saveQueue(queue: SyncOperation[], uid?: string): void {
    this.storage.setItem(this.queueKey(uid), JSON.stringify(queue));
  }

  enqueueOperation(op: SyncOperation, uid?: string): SyncOperation[] {
    const queue = this.loadQueue(uid).filter((existing) => {
      if (op.opType === 'SYNC_PREFS' && existing.opType === 'SYNC_PREFS') return false;
      if (op.opType === 'UPSERT_BOOK' && existing.opType === 'UPSERT_BOOK' && existing.entityId === op.entityId) return false;
      if (op.opType === 'SYNC_BOOK_PAGES' && existing.opType === 'SYNC_BOOK_PAGES' && existing.entityId === op.entityId) return false;
      if (op.opType === 'DELETE_BOOK' && existing.entityId === op.entityId && (existing.opType === 'UPSERT_BOOK' || existing.opType === 'SYNC_BOOK_PAGES')) {
        return false;
      }
      if (op.opType === 'DELETE_NOTE' && existing.entityId === op.entityId && existing.opType === 'UPSERT_NOTE') {
        return false;
      }
      return true;
    });
    queue.push(op);
    this.saveQueue(queue, uid);
    return queue;
  }

  loadDeadLetterQueue(uid?: string): SyncOperation[] {
    const raw = this.storage.getItem(this.deadKey(uid));
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as SyncOperation[]) : [];
    } catch {
      return [];
    }
  }

  saveDeadLetterQueue(ops: SyncOperation[], uid?: string): void {
    if (ops.length === 0) this.storage.removeItem(this.deadKey(uid));
    else this.storage.setItem(this.deadKey(uid), JSON.stringify(ops));
  }

  requeueDeadLetters(uid?: string): number {
    const dead = this.loadDeadLetterQueue(uid);
    if (dead.length === 0) return 0;
    const current = this.loadQueue(uid);
    const reset = dead.map((op) => ({ ...op, attempts: 0 }));
    this.saveQueue([...current, ...reset], uid);
    this.saveDeadLetterQueue([], uid);
    return reset.length;
  }

  loadSyncMeta(uid?: string): SyncState {
    const queueRaw = this.storage.getItem(this.queueKey(uid));
    let queueCount = 0;
    if (queueRaw) {
      try {
        const arr = JSON.parse(queueRaw);
        if (Array.isArray(arr)) queueCount = arr.length;
      } catch {
        queueCount = 0;
      }
    }
    const deadCount = this.loadDeadLetterQueue(uid).length;
    try {
      const raw = this.storage.getItem(this.metaKey(uid));
      const parsed = raw ? (JSON.parse(raw) as Partial<SyncState>) : {};
      const status =
        deadCount > 0 ? 'ERROR' : queueCount > 0 ? 'OFFLINE_QUEUED' : (parsed.status ?? 'IDLE');
      return {
        status,
        pendingCount: queueCount + deadCount,
        deadLetterCount: deadCount,
        conflictsResolved: parsed.conflictsResolved ?? 0,
        lastSyncedAtMillis: parsed.lastSyncedAtMillis ?? 0,
        lastError: parsed.lastError ?? '',
        localOnlyMode: parsed.localOnlyMode ?? false
      };
    } catch {
      return {
        status: deadCount > 0 ? 'ERROR' : queueCount > 0 ? 'OFFLINE_QUEUED' : 'IDLE',
        pendingCount: queueCount + deadCount,
        deadLetterCount: deadCount,
        conflictsResolved: 0,
        lastSyncedAtMillis: 0,
        lastError: '',
        localOnlyMode: false
      };
    }
  }

  saveSyncMeta(meta: SyncState, uid?: string): void {
    this.storage.setItem(this.metaKey(uid), JSON.stringify(meta));
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

  clearAccountQueues(uid?: string): void {
    this.storage.removeItem(this.queueKey(uid));
    this.storage.removeItem(this.deadKey(uid));
    this.storage.removeItem(this.metaKey(uid));
  }

  clearAll(uid?: string): void {
    this.storage.removeItem(STATE_KEY);
    this.clearAccountQueues(uid);
    this.clearAccountQueues('guest');
    this.saveAuthSession(null);
  }

  tryAcquireFlushLock(): boolean {
    if (this.flushLocked) return false;
    this.flushLocked = true;
    return true;
  }

  releaseFlushLock(): void {
    this.flushLocked = false;
  }
}

export async function flushSyncQueueWithRetry(
  store: OfflineWorkspaceStore,
  executor: (op: SyncOperation) => Promise<void>,
  isOnline: boolean,
  localOnlyPrivacyMode: boolean,
  maxAttempts = 4,
  uid?: string
): Promise<SyncState> {
  if (!store.tryAcquireFlushLock()) {
    return store.loadSyncMeta(uid);
  }
  try {
    const queue = store.loadQueue(uid);
    const prevDead = store.loadDeadLetterQueue(uid);
    const prevMeta = store.loadSyncMeta(uid);

    if (localOnlyPrivacyMode || !isOnline) {
      const paused: SyncState = {
        ...prevMeta,
        status: queue.length + prevDead.length > 0 ? 'OFFLINE_QUEUED' : 'IDLE',
        pendingCount: queue.length + prevDead.length,
        deadLetterCount: prevDead.length,
        localOnlyMode: localOnlyPrivacyMode
      };
      store.saveSyncMeta(paused, uid);
      return paused;
    }

    const activeRemaining: SyncOperation[] = [];
    const newlyDead: SyncOperation[] = [...prevDead];
    let lastError = prevMeta.lastError ?? '';

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
        const failedOp: SyncOperation = { ...op, attempts: attempt, lastError: errMsg };
        if (attempt >= maxAttempts) {
          newlyDead.push(failedOp);
        } else {
          activeRemaining.push(failedOp);
        }
      }
    }

    store.saveQueue(activeRemaining, uid);
    store.saveDeadLetterQueue(newlyDead, uid);

    const totalPending = activeRemaining.length + newlyDead.length;
    const next: SyncState = {
      ...prevMeta,
      status: totalPending === 0 ? 'SYNCED' : 'ERROR',
      pendingCount: totalPending,
      deadLetterCount: newlyDead.length,
      lastSyncedAtMillis: totalPending === 0 ? Date.now() : prevMeta.lastSyncedAtMillis,
      lastError: totalPending === 0 ? '' : lastError,
      localOnlyMode: false
    };
    store.saveSyncMeta(next, uid);
    return next;
  } finally {
    store.releaseFlushLock();
  }
}
