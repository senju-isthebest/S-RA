import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import type { AuthSession, DesktopWorkspaceState, SyncOperation, SyncState } from '../shared/models.js';

export interface SafeStorageLike {
  isEncryptionAvailable(): boolean;
  encryptString(plainText: string): Buffer;
  decryptString(encrypted: Buffer): string;
}

export function assertSafeChildPath(baseDir: string, relativeTarget: string): string {
  const cleanName = relativeTarget.replace(/[<>:"|?*\x00-\x1F]/g, '_');
  const resolvedBase = path.resolve(baseDir);
  const resolvedTarget = path.resolve(resolvedBase, cleanName);
  const prefix = resolvedBase.endsWith(path.sep) ? resolvedBase : resolvedBase + path.sep;
  if (resolvedTarget !== resolvedBase && !resolvedTarget.startsWith(prefix)) {
    throw new Error(`Path traversal rejected: ${relativeTarget}`);
  }
  return resolvedTarget;
}

function normalizeScopeUid(uid?: string): string {
  const clean = (uid ?? '').trim().replace(/[^a-zA-Z0-9_-]/g, '_');
  return clean || 'guest';
}

function atomicWriteFile(targetPath: string, data: string, mode?: number): void {
  const tmpPath = `${targetPath}.tmp.${process.pid}.${Date.now()}`;
  fs.writeFileSync(tmpPath, data, mode !== undefined ? { encoding: 'utf8', mode } : 'utf8');
  fs.renameSync(tmpPath, targetPath);
}

export function defaultDesktopWorkspace(): DesktopWorkspaceState {
  return {
    language: 'en',
    localOnlyPrivacyMode: false,
    aiConfig: {
      strategy: 'AUTO',
      fallbackEnabled: true,
      ollamaEndpoint: 'http://127.0.0.1:11434',
      ollamaModel: 'llama3.2:3b',
      geminiModel: 'gemini-3-flash-preview',
      timeoutMs: 30_000
    },
    books: [],
    questions: [],
    flashcards: [],
    notes: []
  };
}

export interface StoredAuthProfile {
  uid: string;
  email: string;
  displayName: string;
  expiresAtMillis: number;
}

export class DesktopVaultStore {
  private readonly workspaceFile: string;
  private readonly authProfileFile: string;
  private readonly secretsFile: string;
  private readonly fallbackKeyFile: string;

  constructor(
    private readonly baseDir: string,
    private readonly safeStorage?: SafeStorageLike
  ) {
    fs.mkdirSync(baseDir, { recursive: true });
    this.workspaceFile = assertSafeChildPath(baseDir, 'workspace.json');
    this.authProfileFile = assertSafeChildPath(baseDir, 'auth_profile.json');
    this.secretsFile = assertSafeChildPath(baseDir, 'vault.enc.json');
    this.fallbackKeyFile = assertSafeChildPath(baseDir, 'vault.key');
  }

  private queueFileFor(uid?: string): string {
    return assertSafeChildPath(this.baseDir, `sync_queue_${normalizeScopeUid(uid)}.json`);
  }

  private deadFileFor(uid?: string): string {
    return assertSafeChildPath(this.baseDir, `sync_dead_${normalizeScopeUid(uid)}.json`);
  }

  private metaFileFor(uid?: string): string {
    return assertSafeChildPath(this.baseDir, `sync_meta_${normalizeScopeUid(uid)}.json`);
  }

  loadWorkspace(): DesktopWorkspaceState {
    if (!fs.existsSync(this.workspaceFile)) return defaultDesktopWorkspace();
    try {
      const parsed = JSON.parse(fs.readFileSync(this.workspaceFile, 'utf8')) as Partial<DesktopWorkspaceState>;
      const def = defaultDesktopWorkspace();
      return {
        ...def,
        ...parsed,
        aiConfig: { ...def.aiConfig, ...(parsed.aiConfig ?? {}) }
      };
    } catch {
      return defaultDesktopWorkspace();
    }
  }

  saveWorkspace(state: DesktopWorkspaceState): void {
    atomicWriteFile(this.workspaceFile, JSON.stringify(state, null, 2));
  }

  loadAuthSession(): AuthSession | null {
    if (!fs.existsSync(this.authProfileFile)) return null;
    try {
      const profile = JSON.parse(fs.readFileSync(this.authProfileFile, 'utf8')) as Partial<StoredAuthProfile>;
      if (!profile.uid) return null;
      const idToken = this.getSecret('firebase_id_token');
      const refreshToken = this.getSecret('firebase_refresh_token');
      if (!idToken && !refreshToken) return null;
      return {
        uid: profile.uid,
        email: profile.email ?? '',
        displayName: profile.displayName ?? '',
        idToken,
        refreshToken,
        expiresAtMillis: profile.expiresAtMillis ?? 0
      };
    } catch {
      return null;
    }
  }

  saveAuthSession(session: AuthSession | null): void {
    if (!session) {
      if (fs.existsSync(this.authProfileFile)) fs.unlinkSync(this.authProfileFile);
      this.setSecret('firebase_id_token', '');
      this.setSecret('firebase_refresh_token', '');
      return;
    }
    const profile: StoredAuthProfile = {
      uid: session.uid,
      email: session.email,
      displayName: session.displayName,
      expiresAtMillis: session.expiresAtMillis
    };
    atomicWriteFile(this.authProfileFile, JSON.stringify(profile, null, 2));
    this.setSecret('firebase_id_token', session.idToken);
    this.setSecret('firebase_refresh_token', session.refreshToken);
  }

  loadQueue(uid?: string): SyncOperation[] {
    const qPath = this.queueFileFor(uid);
    if (!fs.existsSync(qPath)) return [];
    const raw = fs.readFileSync(qPath, 'utf8');
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) throw new Error('Queue is not an array');
      return parsed as SyncOperation[];
    } catch {
      // Quarantine corrupted queue rather than silently losing pending edits
      const corruptPath = assertSafeChildPath(
        this.baseDir,
        `sync_queue_${normalizeScopeUid(uid)}.corrupt.${Date.now()}.json`
      );
      atomicWriteFile(corruptPath, raw);
      if (fs.existsSync(qPath)) fs.unlinkSync(qPath);
      const prev = this.loadSyncMeta(uid);
      this.saveSyncMeta(
        {
          ...prev,
          status: 'ERROR',
          lastError: `Sync queue JSON was corrupted and quarantined to ${path.basename(corruptPath)}.`
        },
        uid
      );
      return [];
    }
  }

  saveQueue(ops: SyncOperation[], uid?: string): void {
    atomicWriteFile(this.queueFileFor(uid), JSON.stringify(ops, null, 2));
  }

  loadDeadLetters(uid?: string): SyncOperation[] {
    const dPath = this.deadFileFor(uid);
    if (!fs.existsSync(dPath)) return [];
    try {
      const parsed = JSON.parse(fs.readFileSync(dPath, 'utf8'));
      return Array.isArray(parsed) ? (parsed as SyncOperation[]) : [];
    } catch {
      return [];
    }
  }

  saveDeadLetters(ops: SyncOperation[], uid?: string): void {
    const dPath = this.deadFileFor(uid);
    if (ops.length === 0) {
      if (fs.existsSync(dPath)) fs.unlinkSync(dPath);
    } else {
      atomicWriteFile(dPath, JSON.stringify(ops, null, 2));
    }
  }

  requeueDeadLetters(uid?: string): number {
    const dead = this.loadDeadLetters(uid);
    if (dead.length === 0) return 0;
    const queue = this.loadQueue(uid);
    const reset = dead.map((op) => ({ ...op, attempts: 0 }));
    this.saveQueue([...queue, ...reset], uid);
    this.saveDeadLetters([], uid);
    return reset.length;
  }

  loadSyncMeta(uid?: string): SyncState {
    const qPath = this.queueFileFor(uid);
    let qCount = 0;
    if (fs.existsSync(qPath)) {
      try {
        const arr = JSON.parse(fs.readFileSync(qPath, 'utf8'));
        if (Array.isArray(arr)) qCount = arr.length;
      } catch {
        qCount = 0;
      }
    }
    const deadCount = this.loadDeadLetters(uid).length;
    const mPath = this.metaFileFor(uid);
    const ws = this.loadWorkspace();
    let parsed: Partial<SyncState> = {};
    if (fs.existsSync(mPath)) {
      try {
        parsed = JSON.parse(fs.readFileSync(mPath, 'utf8')) as Partial<SyncState>;
      } catch {
        parsed = {};
      }
    }
    const status =
      deadCount > 0 ? 'ERROR' : qCount > 0 ? 'OFFLINE_QUEUED' : (parsed.status ?? 'IDLE');
    return {
      status,
      pendingCount: qCount + deadCount,
      deadLetterCount: deadCount,
      conflictsResolved: parsed.conflictsResolved ?? 0,
      lastSyncedAtMillis: parsed.lastSyncedAtMillis ?? 0,
      lastError: parsed.lastError ?? '',
      localOnlyMode: ws.localOnlyPrivacyMode
    };
  }

  saveSyncMeta(meta: SyncState, uid?: string): void {
    atomicWriteFile(this.metaFileFor(uid), JSON.stringify(meta, null, 2));
  }

  enqueueOperation(op: SyncOperation, uid?: string): SyncState {
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
    const deadCount = this.loadDeadLetters(uid).length;
    const ws = this.loadWorkspace();
    const meta: SyncState = {
      ...this.loadSyncMeta(uid),
      status: deadCount > 0 ? 'ERROR' : 'OFFLINE_QUEUED',
      pendingCount: queue.length + deadCount,
      deadLetterCount: deadCount,
      localOnlyMode: ws.localOnlyPrivacyMode
    };
    this.saveSyncMeta(meta, uid);
    return meta;
  }

  clearAccountData(uid?: string): void {
    for (const f of [this.queueFileFor(uid), this.deadFileFor(uid), this.metaFileFor(uid)]) {
      if (fs.existsSync(f)) fs.unlinkSync(f);
    }
    this.saveAuthSession(null);
  }

  setSecret(keyName: string, plaintextValue: string): void {
    const cleanKey = keyName.replace(/[^a-zA-Z0-9_.-]/g, '_');
    const vault = this.readVaultMap();
    const val = plaintextValue.trim();
    if (!val) {
      delete vault[cleanKey];
    } else if (this.safeStorage?.isEncryptionAvailable()) {
      vault[cleanKey] = `dpapi:${this.safeStorage.encryptString(val).toString('base64')}`;
    } else {
      vault[cleanKey] = `gcm:${this.encryptGcm(val)}`;
    }
    atomicWriteFile(this.secretsFile, JSON.stringify(vault, null, 2), 0o600);
  }

  getSecret(keyName: string): string {
    const cleanKey = keyName.replace(/[^a-zA-Z0-9_.-]/g, '_');
    const raw = this.readVaultMap()[cleanKey];
    if (!raw) return '';
    try {
      if (raw.startsWith('dpapi:') && this.safeStorage?.isEncryptionAvailable()) {
        return this.safeStorage.decryptString(Buffer.from(raw.slice(6), 'base64'));
      }
      if (raw.startsWith('gcm:')) {
        return this.decryptGcm(raw.slice(4));
      }
      return '';
    } catch {
      return '';
    }
  }

  exportNonSecretJson(): string {
    const ws = this.loadWorkspace();
    return JSON.stringify(
      {
        exportedAtMillis: Date.now(),
        language: ws.language,
        localOnlyPrivacyMode: ws.localOnlyPrivacyMode,
        books: ws.books.map((b) => ({
          id: b.id,
          title: b.title,
          subjectName: b.subjectName,
          totalPages: b.totalPages,
          currentPage: b.currentPage,
          bookmarkedPages: b.bookmarkedPages,
          highlightsByPage: b.highlightsByPage
        })),
        notes: ws.notes,
        flashcards: ws.flashcards,
        questionsCount: ws.questions.length
      },
      null,
      2
    );
  }

  private readVaultMap(): Record<string, string> {
    if (!fs.existsSync(this.secretsFile)) return {};
    try {
      return JSON.parse(fs.readFileSync(this.secretsFile, 'utf8')) as Record<string, string>;
    } catch {
      return {};
    }
  }

  private getOrCreateFallbackKey(): Buffer {
    if (fs.existsSync(this.fallbackKeyFile)) {
      const b64 = fs.readFileSync(this.fallbackKeyFile, 'utf8').trim();
      const buf = Buffer.from(b64, 'base64');
      if (buf.length === 32) return buf;
    }
    const key = crypto.randomBytes(32);
    atomicWriteFile(this.fallbackKeyFile, key.toString('base64'), 0o600);
    return key;
  }

  private encryptGcm(plaintext: string): string {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.getOrCreateFallbackKey(), iv);
    const enc = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return Buffer.concat([iv, tag, enc]).toString('base64');
  }

  private decryptGcm(payloadB64: string): string {
    const buf = Buffer.from(payloadB64, 'base64');
    if (buf.length <= 28) return '';
    const iv = buf.subarray(0, 12);
    const tag = buf.subarray(12, 28);
    const enc = buf.subarray(28);
    const decipher = crypto.createDecipheriv('aes-256-gcm', this.getOrCreateFallbackKey(), iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(enc), decipher.final()]).toString('utf8');
  }
}
