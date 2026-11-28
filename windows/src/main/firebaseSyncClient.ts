import type {
  AuthSession,
  Book,
  FirebaseDesktopConfig,
  StudyNote,
  SyncOperation
} from '../shared/models.js';

export class DesktopFirebaseError extends Error {
  constructor(
    public readonly code:
      | 'CLOUD_NOT_CONFIGURED'
      | 'AUTH_REQUIRED'
      | 'TOKEN_EXPIRED'
      | 'REAUTH_REQUIRED'
      | 'HTTP_ERROR'
      | 'PARTIAL_DELETE_FAILED',
    message: string
  ) {
    super(message);
    this.name = 'DesktopFirebaseError';
  }
}

export function readFirebaseDesktopConfig(): FirebaseDesktopConfig {
  return {
    apiKey: (process.env.SORA_FIREBASE_API_KEY ?? process.env.VITE_FIREBASE_API_KEY ?? '').trim(),
    projectId: (process.env.SORA_FIREBASE_PROJECT_ID ?? process.env.VITE_FIREBASE_PROJECT_ID ?? '').trim(),
    databaseId:
      (process.env.SORA_FIREBASE_DATABASE_ID ?? process.env.VITE_FIREBASE_DATABASE_ID ?? '').trim() ||
      'ai-studio-android-sra-0948eeb3-9b5b-4546-903e-674d8a8de041'
  };
}

export function isFirebaseDesktopConfigured(cfg: FirebaseDesktopConfig): boolean {
  return Boolean(cfg.apiKey && cfg.projectId);
}

function assertReady(session: AuthSession | null, cfg: FirebaseDesktopConfig): asserts session is AuthSession {
  if (!isFirebaseDesktopConfigured(cfg)) {
    throw new DesktopFirebaseError(
      'CLOUD_NOT_CONFIGURED',
      'Firebase desktop config (SORA_FIREBASE_API_KEY / SORA_FIREBASE_PROJECT_ID) is not configured.'
    );
  }
  if (!session || !session.uid.trim() || !session.idToken.trim()) {
    throw new DesktopFirebaseError('AUTH_REQUIRED', 'Sign in first to sync with SŌRA Cloud.');
  }
}

async function callIdentityToolkit(
  endpoint: string,
  body: Record<string, unknown>,
  cfg: FirebaseDesktopConfig,
  fetchImpl: typeof fetch
): Promise<Record<string, unknown>> {
  if (!cfg.apiKey) {
    throw new DesktopFirebaseError('CLOUD_NOT_CONFIGURED', 'Firebase API key is missing.');
  }
  const res = await fetchImpl(
    `https://identitytoolkit.googleapis.com/v1/${endpoint}?key=${encodeURIComponent(cfg.apiKey)}`,
    {
      method: 'POST',
      redirect: 'error',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }
  );
  const json = (await res.json().catch(() => ({}))) as { error?: { message?: string }; [k: string]: unknown };
  if (!res.ok) {
    const code = json.error?.message ?? `HTTP_${res.status}`;
    if (code === 'CREDENTIAL_TOO_OLD_LOGIN_AGAIN') {
      throw new DesktopFirebaseError('REAUTH_REQUIRED', 'Please sign out and sign in again before deleting your account.');
    }
    throw new DesktopFirebaseError('HTTP_ERROR', code);
  }
  return json;
}

type FsValue =
  | { stringValue: string }
  | { integerValue: string }
  | { doubleValue: number }
  | { booleanValue: boolean }
  | { timestampValue: string }
  | { arrayValue: { values?: FsValue[] } }
  | { mapValue: { fields?: Record<string, FsValue> } };

function toFsValue(v: unknown): FsValue {
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map((x) => toFsValue(x)) } };
  if (v && typeof v === 'object') {
    const fields: Record<string, FsValue> = {};
    for (const [k, val] of Object.entries(v)) {
      if (val !== undefined) fields[k] = toFsValue(val);
    }
    return { mapValue: { fields } };
  }
  return { stringValue: '' };
}

function fromFsValue(v: FsValue | undefined): unknown {
  if (!v) return undefined;
  if ('stringValue' in v) return v.stringValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('timestampValue' in v) return Date.parse(v.timestampValue) || 0;
  if ('arrayValue' in v) return (v.arrayValue.values ?? []).map((x) => fromFsValue(x));
  if ('mapValue' in v) {
    const out: Record<string, unknown> = {};
    for (const [k, fv] of Object.entries(v.mapValue.fields ?? {})) {
      out[k] = fromFsValue(fv);
    }
    return out;
  }
  return undefined;
}

function sanitizeDocId(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 128) || `doc_${Date.now()}`;
}

export class FirebaseDesktopClient {
  constructor(
    private readonly cfg: FirebaseDesktopConfig,
    private readonly fetchImpl: typeof fetch = fetch
  ) {}

  private baseDocUrl(): string {
    return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(this.cfg.projectId)}/databases/${encodeURIComponent(this.cfg.databaseId)}/documents`;
  }

  private docName(relPath: string): string {
    return `projects/${encodeURIComponent(this.cfg.projectId)}/databases/${encodeURIComponent(this.cfg.databaseId)}/documents/${relPath}`;
  }

  async signUp(email: string, password: string, displayName: string): Promise<AuthSession> {
    const data = await callIdentityToolkit(
      'accounts:signUp',
      { email: email.trim(), password, returnSecureToken: true },
      this.cfg,
      this.fetchImpl
    );
    const session: AuthSession = {
      uid: String(data.localId ?? ''),
      email: String(data.email ?? email.trim()),
      displayName: displayName.trim() || email.split('@')[0] || 'Student',
      idToken: String(data.idToken ?? ''),
      refreshToken: String(data.refreshToken ?? ''),
      expiresAtMillis: Date.now() + Number(data.expiresIn ?? 3600) * 1000
    };
    await this.ensureUserProfile(session);
    return session;
  }

  async signIn(email: string, password: string): Promise<AuthSession> {
    const data = await callIdentityToolkit(
      'accounts:signInWithPassword',
      { email: email.trim(), password, returnSecureToken: true },
      this.cfg,
      this.fetchImpl
    );
    const session: AuthSession = {
      uid: String(data.localId ?? ''),
      email: String(data.email ?? email.trim()),
      displayName: String(data.displayName ?? '') || email.split('@')[0] || 'Student',
      idToken: String(data.idToken ?? ''),
      refreshToken: String(data.refreshToken ?? ''),
      expiresAtMillis: Date.now() + Number(data.expiresIn ?? 3600) * 1000
    };
    await this.ensureUserProfile(session);
    return session;
  }

  async ensureFreshSession(session: AuthSession): Promise<AuthSession> {
    if (session.expiresAtMillis > Date.now() + 60_000) return session;
    if (!this.cfg.apiKey) {
      throw new DesktopFirebaseError('CLOUD_NOT_CONFIGURED', 'Firebase API key missing.');
    }
    if (!session.refreshToken) {
      throw new DesktopFirebaseError('TOKEN_EXPIRED', 'Refresh token missing. Please sign in again.');
    }
    const res = await this.fetchImpl(
      `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(this.cfg.apiKey)}`,
      {
        method: 'POST',
        redirect: 'error',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(session.refreshToken)}`
      }
    );
    const json = (await res.json().catch(() => ({}))) as Record<string, string | undefined>;
    if (!res.ok || !json.id_token) {
      throw new DesktopFirebaseError('TOKEN_EXPIRED', 'Cloud session expired. Please sign in again.');
    }
    return {
      ...session,
      uid: json.user_id || session.uid,
      idToken: json.id_token,
      refreshToken: json.refresh_token || session.refreshToken,
      expiresAtMillis: Date.now() + Number(json.expires_in ?? 3600) * 1000
    };
  }

  private async commitUpsert(
    idToken: string,
    relDocPath: string,
    scalarFields: Record<string, unknown>
  ): Promise<void> {
    const existing = await this.fetchImpl(`${this.baseDocUrl()}/${relDocPath}`, {
      method: 'GET',
      redirect: 'error',
      headers: { Authorization: `Bearer ${idToken}` }
    });
    const fields: Record<string, FsValue> = {};
    const updatePaths: string[] = [];
    for (const [k, v] of Object.entries(scalarFields)) {
      if (k === 'createdAt' || k === 'updatedAt' || k === 'geminiApiKey') continue;
      fields[k] = toFsValue(v);
      updatePaths.push(k);
    }
    const transforms = existing.ok
      ? [{ fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' }]
      : [
          { fieldPath: 'createdAt', setToServerValue: 'REQUEST_TIME' },
          { fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' }
        ];
    const res = await this.fetchImpl(`${this.baseDocUrl()}:commit`, {
      method: 'POST',
      redirect: 'error',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${idToken}`
      },
      body: JSON.stringify({
        writes: [
          {
            update: { name: this.docName(relDocPath), fields },
            updateMask: { fieldPaths: updatePaths },
            updateTransforms: transforms
          }
        ]
      })
    });
    if (!res.ok) {
      const txt = await res.text().catch(() => '');
      throw new DesktopFirebaseError('HTTP_ERROR', `Firestore HTTP ${res.status}: ${txt.slice(0, 160)}`);
    }
  }

  private async deleteDoc(idToken: string, relDocPath: string): Promise<void> {
    const res = await this.fetchImpl(`${this.baseDocUrl()}/${relDocPath}`, {
      method: 'DELETE',
      redirect: 'error',
      headers: { Authorization: `Bearer ${idToken}` }
    });
    if (!res.ok && res.status !== 404) {
      throw new DesktopFirebaseError('HTTP_ERROR', `Firestore delete HTTP ${res.status}`);
    }
  }

  private async listDocs(idToken: string, relColPath: string): Promise<Array<{ id: string; fields: Record<string, unknown> }>> {
    const out: Array<{ id: string; fields: Record<string, unknown> }> = [];
    let pageToken = '';
    do {
      const qs = new URLSearchParams({ pageSize: '300' });
      if (pageToken) qs.set('pageToken', pageToken);
      const res = await this.fetchImpl(`${this.baseDocUrl()}/${relColPath}?${qs.toString()}`, {
        method: 'GET',
        redirect: 'error',
        headers: { Authorization: `Bearer ${idToken}` }
      });
      if (res.status === 404) break;
      if (!res.ok) throw new DesktopFirebaseError('HTTP_ERROR', `Firestore list HTTP ${res.status}`);
      const json = (await res.json()) as {
        documents?: Array<{ name: string; fields?: Record<string, FsValue> }>;
        nextPageToken?: string;
      };
      for (const d of json.documents ?? []) {
        const id = d.name.split('/').pop() ?? '';
        const fields: Record<string, unknown> = {};
        for (const [k, fv] of Object.entries(d.fields ?? {})) {
          fields[k] = fromFsValue(fv);
        }
        if (id) out.push({ id, fields });
      }
      pageToken = json.nextPageToken ?? '';
    } while (pageToken);
    return out;
  }

  async ensureUserProfile(session: AuthSession): Promise<void> {
    assertReady(session, this.cfg);
    const rel = `users/${encodeURIComponent(session.uid)}`;
    const res = await this.fetchImpl(`${this.baseDocUrl()}/${rel}`, {
      method: 'GET',
      redirect: 'error',
      headers: { Authorization: `Bearer ${session.idToken}` }
    });
    if (res.ok) return;
    await this.commitUpsert(session.idToken, rel, {
      userId: session.uid,
      displayName: (session.displayName || 'Student').slice(0, 100),
      email: (session.email || '').slice(0, 200),
      studyStreakDays: 1,
      weeklyStudyMinutes: 0,
      dailyGoalMinutes: 60,
      dashboardSections: ['continue', 'quick_actions', 'revision_queue', 'tasks', 'exams', 'weak_topics', 'library']
    });
  }

  async pushOperation(op: SyncOperation, session: AuthSession | null): Promise<void> {
    assertReady(session, this.cfg);
    const uid = encodeURIComponent(session.uid);
    const eid = encodeURIComponent(sanitizeDocId(op.entityId));
    let payload: Record<string, unknown> = {};
    try {
      payload = op.payloadJson ? (JSON.parse(op.payloadJson) as Record<string, unknown>) : {};
    } catch {
      payload = {};
    }

    switch (op.opType) {
      case 'UPSERT_BOOK': {
        const totalPages = Math.max(1, Math.min(5000, Number(payload.totalPages ?? 1) || 1));
        const processedPages = Math.max(0, Math.min(totalPages, Number(payload.processedPages ?? totalPages) || 0));
        const currentPage = Math.max(1, Math.min(totalPages, Number(payload.currentPage ?? 1) || 1));
        await this.commitUpsert(session.idToken, `users/${uid}/books/${eid}`, {
          title: String(payload.title ?? 'Textbook').slice(0, 160),
          subjectName: String(payload.subjectName ?? 'General').slice(0, 80),
          sourceType: String(payload.sourceType ?? 'DESKTOP_TEXT').slice(0, 20),
          localBookId: sanitizeDocId(op.entityId),
          totalPages,
          processedPages,
          currentPage,
          extractionStatus: 'COMPLETE',
          overview: String(payload.overview ?? '').slice(0, 4000),
          formulaSheet: String(payload.formulaSheet ?? '').slice(0, 10000),
          archived: false,
          masteryScore: 0,
          bookmarkedPages: Array.isArray(payload.bookmarkedPages) ? payload.bookmarkedPages.slice(0, 500) : [],
          highlightsJson: String(payload.highlightsJson ?? '{}').slice(0, 20000)
        });
        break;
      }
      case 'SYNC_BOOK_PAGES': {
        const pages = Array.isArray(payload.pages) ? (payload.pages as Array<{ pageNumber: number; text: string }>) : [];
        for (const p of pages) {
          const pn = Math.max(1, Math.min(5000, Math.floor(Number(p.pageNumber) || 1)));
          await this.commitUpsert(session.idToken, `users/${uid}/books/${eid}/pages/p_${pn}`, {
            bookId: sanitizeDocId(op.entityId),
            pageNumber: pn,
            text: String(p.text ?? '').slice(0, 12000),
            extractionMethod: String(payload.extractionMethod ?? 'PDF_TEXT').slice(0, 40),
            error: '',
            clientUpdatedAtMillis: Number(payload.clientUpdatedAtMillis ?? Date.now())
          });
        }
        break;
      }
      case 'DELETE_BOOK': {
        for (const sub of ['pages', 'chapters', 'glossary']) {
          const docs = await this.listDocs(session.idToken, `users/${uid}/books/${eid}/${sub}`);
          for (const d of docs) {
            await this.deleteDoc(session.idToken, `users/${uid}/books/${eid}/${sub}/${encodeURIComponent(d.id)}`);
          }
        }
        await this.deleteDoc(session.idToken, `users/${uid}/books/${eid}`);
        break;
      }
      case 'UPSERT_NOTE': {
        await this.commitUpsert(session.idToken, `users/${uid}/notes/${eid}`, {
          bookId: String(payload.bookId ?? 'general').slice(0, 128),
          subjectName: String(payload.subjectName ?? 'General').slice(0, 80),
          chapterNumber: Math.max(0, Math.min(500, Math.floor(Number(payload.chapterNumber ?? 0) || 0))),
          linkedPage: Math.max(0, Math.min(5000, Math.floor(Number(payload.linkedPage ?? 1) || 0))),
          title: String(payload.title ?? 'Note').slice(0, 160),
          contentMarkdown: String(payload.contentMarkdown ?? '').slice(0, 30000),
          bookmarked: false,
          quickNote: false
        });
        break;
      }
      case 'DELETE_NOTE': {
        await this.deleteDoc(session.idToken, `users/${uid}/notes/${eid}`);
        break;
      }
      case 'SUBMIT_EXAM': {
        const totalMarks = Math.max(1, Math.min(1000, Math.round(Number(payload.totalMarks ?? 1) || 1)));
        const score = Math.max(0, Math.min(totalMarks, Math.round(Number(payload.score ?? 0) || 0)));
        await this.commitUpsert(session.idToken, `users/${uid}/exams/${eid}`, {
          title: String(payload.title ?? 'Exam').slice(0, 160),
          preset: 'CUSTOM',
          bookIds: [String(payload.bookId ?? 'general').slice(0, 128)],
          questionIds: ['q_1'],
          durationMinutes: 30,
          totalMarks,
          allowRevisit: true,
          showExplanationEach: false,
          status: 'SUBMITTED',
          answersJson: String(payload.answersJson ?? '{}').slice(0, 50000),
          flaggedQuestionIds: [],
          score,
          percentage: Math.max(0, Math.min(100, Number(payload.percentage ?? 0) || 0)),
          correctCount: Math.max(0, Math.round(Number(payload.correctCount ?? 0) || 0)),
          incorrectCount: 0,
          unansweredCount: 0,
          timeSpentSeconds: 0
        });
        break;
      }
      case 'SYNC_PREFS': {
        await this.commitUpsert(session.idToken, `users/${uid}/preferences/app`, {
          themeMode: 'DARK',
          languageCode: String(payload.languageCode ?? 'en').slice(0, 10),
          pomodoroWorkMinutes: 25,
          pomodoroShortBreakMinutes: 5,
          pomodoroLongBreakMinutes: 15,
          ambientFocusSound: false,
          aiStrategy: String(payload.aiStrategy ?? 'AUTO').slice(0, 30),
          aiFallbackEnabled: true,
          ollamaModel: String(payload.ollamaModel ?? 'llama3.2:3b').slice(0, 100),
          geminiModel: String(payload.geminiModel ?? 'gemini-3-flash-preview').slice(0, 100),
          aiTimeoutMs: Math.max(5000, Math.min(180000, Number(payload.aiTimeoutMs ?? 30000) || 30000)),
          clientUpdatedAtMillis: Number(payload.clientUpdatedAtMillis ?? Date.now())
        });
        break;
      }
    }
  }

  async pullWorkspace(session: AuthSession | null): Promise<{ books: Book[]; notes: StudyNote[] }> {
    assertReady(session, this.cfg);
    const uid = encodeURIComponent(session.uid);
    const [bookDocs, noteDocs] = await Promise.all([
      this.listDocs(session.idToken, `users/${uid}/books`),
      this.listDocs(session.idToken, `users/${uid}/notes`)
    ]);

    const books: Book[] = [];
    for (const bd of bookDocs) {
      const f = bd.fields;
      const pageDocs = await this.listDocs(session.idToken, `users/${uid}/books/${encodeURIComponent(bd.id)}/pages`);
      const pages = pageDocs
        .map((pd) => ({ pageNumber: Math.max(1, Number(pd.fields.pageNumber ?? 1)), text: String(pd.fields.text ?? '') }))
        .sort((a, b) => a.pageNumber - b.pageNumber);
      let hlMap: Record<number, string[]> = {};
      try {
        const parsed = JSON.parse(String(f.highlightsJson ?? '{}')) as Record<string, unknown>;
        for (const [k, v] of Object.entries(parsed)) {
          const pn = Number(k);
          if (Number.isFinite(pn)) hlMap[pn] = Array.isArray(v) ? v.map(String) : [String(v)];
        }
      } catch {
        hlMap = {};
      }
      books.push({
        id: bd.id,
        title: String(f.title ?? 'Textbook'),
        subjectName: String(f.subjectName ?? 'General'),
        totalPages: Math.max(1, Number(f.totalPages ?? pages.length) || 1),
        currentPage: Math.max(1, Number(f.currentPage ?? 1) || 1),
        lastUpdatedMillis: Number(f.updatedAt ?? Date.now()),
        bookmarkedPages: Array.isArray(f.bookmarkedPages) ? f.bookmarkedPages.map(Number) : [],
        highlightsByPage: hlMap,
        chapters: [
          {
            id: `${bd.id}_ch1`,
            chapterNumber: 1,
            title: String(f.title ?? 'Chapter 1'),
            startPage: 1,
            endPage: Math.max(1, Number(f.totalPages ?? pages.length) || 1)
          }
        ],
        pages: pages.length > 0 ? pages : [{ pageNumber: 1, text: String(f.overview ?? '') }]
      });
    }

    const notes: StudyNote[] = noteDocs.map((nd) => ({
      id: nd.id,
      bookId: String(nd.fields.bookId ?? 'general'),
      linkedPage: Number(nd.fields.linkedPage ?? 1),
      title: String(nd.fields.title ?? 'Note'),
      contentMarkdown: String(nd.fields.contentMarkdown ?? ''),
      updatedAtMillis: Number(nd.fields.updatedAt ?? Date.now())
    }));

    return { books, notes };
  }

  async deleteAllCloudUserData(session: AuthSession | null): Promise<{ ok: boolean; errors: string[] }> {
    assertReady(session, this.cfg);
    const uid = encodeURIComponent(session.uid);
    const errors: string[] = [];

    try {
      const books = await this.listDocs(session.idToken, `users/${uid}/books`);
      for (const b of books) {
        const bId = encodeURIComponent(b.id);
        for (const sub of ['chapters', 'glossary', 'pages']) {
          try {
            const subDocs = await this.listDocs(session.idToken, `users/${uid}/books/${bId}/${sub}`);
            for (const sd of subDocs) {
              await this.deleteDoc(session.idToken, `users/${uid}/books/${bId}/${sub}/${encodeURIComponent(sd.id)}`);
            }
          } catch (err) {
            errors.push(`${sub}:${err instanceof Error ? err.message : 'delete failed'}`);
          }
        }
        try {
          await this.deleteDoc(session.idToken, `users/${uid}/books/${bId}`);
        } catch (err) {
          errors.push(`book:${err instanceof Error ? err.message : 'delete failed'}`);
        }
      }
    } catch (err) {
      errors.push(`books:${err instanceof Error ? err.message : 'list failed'}`);
    }

    for (const col of [
      'subjects', 'questions', 'flashcards', 'exams', 'notes',
      'plannerTasks', 'focusSessions', 'tutorMessages', 'citations', 'preferences'
    ]) {
      try {
        const docs = await this.listDocs(session.idToken, `users/${uid}/${col}`);
        for (const d of docs) {
          await this.deleteDoc(session.idToken, `users/${uid}/${col}/${encodeURIComponent(d.id)}`);
        }
      } catch (err) {
        errors.push(`${col}:${err instanceof Error ? err.message : 'delete failed'}`);
      }
    }

    try {
      await this.deleteDoc(session.idToken, `users/${uid}`);
    } catch (err) {
      errors.push(`profile:${err instanceof Error ? err.message : 'delete failed'}`);
    }

    return { ok: errors.length === 0, errors };
  }

  async deleteAccount(session: AuthSession | null): Promise<void> {
    assertReady(session, this.cfg);
    await callIdentityToolkit('accounts:delete', { idToken: session.idToken }, this.cfg, this.fetchImpl);
  }
}
