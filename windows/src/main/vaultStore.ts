import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import type { DesktopWorkspaceState, SyncOperation, SyncState } from '../shared/models';

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

export class DesktopVaultStore {
  private readonly workspaceFile: string;
  private readonly queueFile: string;
  private readonly secretsFile: string;
  private readonly fallbackKeyFile: string;

  constructor(
    baseDir: string,
    private readonly safeStorage?: SafeStorageLike
  ) {
    fs.mkdirSync(baseDir, { recursive: true });
    this.workspaceFile = assertSafeChildPath(baseDir, 'workspace.json');
    this.queueFile = assertSafeChildPath(baseDir, 'sync_queue.json');
    this.secretsFile = assertSafeChildPath(baseDir, 'vault.enc.json');
    this.fallbackKeyFile = assertSafeChildPath(baseDir, 'vault.key');
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
    fs.writeFileSync(this.workspaceFile, JSON.stringify(state, null, 2), 'utf8');
  }

  loadQueue(): SyncOperation[] {
    if (!fs.existsSync(this.queueFile)) return [];
    try {
      return JSON.parse(fs.readFileSync(this.queueFile, 'utf8')) as SyncOperation[];
    } catch {
      return [];
    }
  }

  saveQueue(ops: SyncOperation[]): void {
    fs.writeFileSync(this.queueFile, JSON.stringify(ops, null, 2), 'utf8');
  }

  enqueueOperation(op: SyncOperation): SyncState {
    const queue = this.loadQueue();
    queue.push(op);
    this.saveQueue(queue);
    const ws = this.loadWorkspace();
    return {
      status: 'OFFLINE_QUEUED',
      pendingCount: queue.length,
      conflictsResolved: 0,
      lastSyncedAtMillis: 0,
      lastError: '',
      localOnlyMode: ws.localOnlyPrivacyMode
    };
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
    fs.writeFileSync(this.secretsFile, JSON.stringify(vault, null, 2), { encoding: 'utf8', mode: 0o600 });
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
    fs.writeFileSync(this.fallbackKeyFile, key.toString('base64'), { encoding: 'utf8', mode: 0o600 });
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
