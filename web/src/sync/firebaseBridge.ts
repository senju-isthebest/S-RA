import type {
  AuthSession,
  Book,
  ExamRecord,
  StudyNote,
  SyncOperation,
  UserSettings
} from '../types/sora';

export interface FirebaseWebEnv {
  apiKey: string;
  projectId: string;
  databaseId: string;
}

export class FirebaseSyncError extends Error {
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
    this.name = 'FirebaseSyncError';
  }
}

export function readFirebaseWebEnv(): FirebaseWebEnv {
  const env = (import.meta as { env?: Record<string, string | undefined> }).env ?? {};
  return {
    apiKey: env.VITE_FIREBASE_API_KEY?.trim() ?? '',
    projectId: env.VITE_FIREBASE_PROJECT_ID?.trim() ?? '',
    databaseId: env.VITE_FIREBASE_DATABASE_ID?.trim() || 'ai-studio-android-sra-0948eeb3-9b5b-4546-903e-674d8a8de041'
  };
}

export function isFirebaseSyncConfigured(cfg: FirebaseWebEnv): boolean {
  return Boolean(cfg.apiKey && cfg.projectId);
}

function assertConfiguredAndAuthenticated(session: AuthSession | null, cfg: FirebaseWebEnv): asserts session is AuthSession {
  if (!isFirebaseSyncConfigured(cfg)) {
    throw new FirebaseSyncError(
      'CLOUD_NOT_CONFIGURED',
      'Firebase web configuration (VITE_FIREBASE_API_KEY / VITE_FIREBASE_PROJECT_ID) is not configured.'
    );
  }
  if (!session || !session.uid.trim() || !session.idToken.trim()) {
    throw new FirebaseSyncError('AUTH_REQUIRED', 'Sign in to sync with SŌRA Cloud.');
  }
}

function mapFirebaseAuthError(rawMsg: string): string {
  switch (rawMsg) {
    case 'EMAIL_EXISTS':
      return 'An account with this email already exists.';
    case 'EMAIL_NOT_FOUND':
    case 'INVALID_PASSWORD':
    case 'INVALID_LOGIN_CREDENTIALS':
      return 'Invalid email or password.';
    case 'USER_DISABLED':
      return 'This account has been disabled.';
    case 'WEAK_PASSWORD : Password should be at least 6 characters':
    case 'WEAK_PASSWORD':
      return 'Password must be at least 6 characters.';
    case 'CREDENTIAL_TOO_OLD_LOGIN_AGAIN':
      return 'Please sign out and sign in again before deleting your account.';
    case 'TOO_MANY_ATTEMPTS_TRY_LATER':
      return 'Too many attempts. Please wait and try again.';
    default:
      return rawMsg || 'Authentication failed.';
  }
}

async function callIdentityToolkit(
  endpoint: string,
  body: Record<string, unknown>,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch
): Promise<Record<string, unknown>> {
  if (!cfg.apiKey) {
    throw new FirebaseSyncError('CLOUD_NOT_CONFIGURED', 'VITE_FIREBASE_API_KEY is missing.');
  }
  const url = `https://identitytoolkit.googleapis.com/v1/${endpoint}?key=${encodeURIComponent(cfg.apiKey)}`;
  const res = await fetchImpl(url, {
    method: 'POST',
    redirect: 'error',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const json = (await res.json().catch(() => ({}))) as { error?: { message?: string }; [k: string]: unknown };
  if (!res.ok) {
    const code = json.error?.message ?? `HTTP_${res.status}`;
    if (code === 'CREDENTIAL_TOO_OLD_LOGIN_AGAIN') {
      throw new FirebaseSyncError('REAUTH_REQUIRED', mapFirebaseAuthError(code));
    }
    throw new FirebaseSyncError('HTTP_ERROR', mapFirebaseAuthError(code));
  }
  return json;
}

export async function signUpWithEmail(
  email: string,
  password: string,
  displayName: string,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<AuthSession> {
  const data = await callIdentityToolkit(
    'accounts:signUp',
    { email: email.trim(), password, returnSecureToken: true },
    cfg,
    fetchImpl
  );
  const idToken = String(data.idToken ?? '');
  if (displayName.trim() && idToken) {
    await callIdentityToolkit(
      'accounts:update',
      { idToken, displayName: displayName.trim().slice(0, 100), returnSecureToken: false },
      cfg,
      fetchImpl
    ).catch(() => {});
  }
  const expiresSec = Number(data.expiresIn ?? 3600);
  const session: AuthSession = {
    uid: String(data.localId ?? ''),
    email: String(data.email ?? email.trim()),
    displayName: displayName.trim() || email.split('@')[0] || 'Student',
    idToken,
    refreshToken: String(data.refreshToken ?? ''),
    expiresAtMillis: Date.now() + expiresSec * 1000
  };
  await ensureUserProfile(session, cfg, fetchImpl);
  return session;
}

export async function signInWithEmail(
  email: string,
  password: string,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<AuthSession> {
  const data = await callIdentityToolkit(
    'accounts:signInWithPassword',
    { email: email.trim(), password, returnSecureToken: true },
    cfg,
    fetchImpl
  );
  const expiresSec = Number(data.expiresIn ?? 3600);
  const session: AuthSession = {
    uid: String(data.localId ?? ''),
    email: String(data.email ?? email.trim()),
    displayName: String(data.displayName ?? '') || email.split('@')[0] || 'Student',
    idToken: String(data.idToken ?? ''),
    refreshToken: String(data.refreshToken ?? ''),
    expiresAtMillis: Date.now() + expiresSec * 1000
  };
  await ensureUserProfile(session, cfg, fetchImpl);
  return session;
}

export async function refreshAuthSession(
  session: AuthSession,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<AuthSession> {
  if (!cfg.apiKey) {
    throw new FirebaseSyncError('CLOUD_NOT_CONFIGURED', 'VITE_FIREBASE_API_KEY is missing.');
  }
  if (!session.refreshToken) {
    throw new FirebaseSyncError('TOKEN_EXPIRED', 'Session refresh token is missing. Please sign in again.');
  }
  const res = await fetchImpl(
    `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(cfg.apiKey)}`,
    {
      method: 'POST',
      redirect: 'error',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(session.refreshToken)}`
    }
  );
  const json = (await res.json().catch(() => ({}))) as Record<string, string | undefined>;
  if (!res.ok || !json.id_token) {
    throw new FirebaseSyncError('TOKEN_EXPIRED', 'Cloud session expired. Please sign in again.');
  }
  return {
    ...session,
    uid: json.user_id || session.uid,
    idToken: json.id_token,
    refreshToken: json.refresh_token || session.refreshToken,
    expiresAtMillis: Date.now() + Number(json.expires_in ?? 3600) * 1000
  };
}

export async function ensureFreshSession(
  session: AuthSession,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<AuthSession> {
  if (session.expiresAtMillis > Date.now() + 60_000) {
    return session;
  }
  return refreshAuthSession(session, cfg, fetchImpl);
}

export async function deleteFirebaseAccount(
  session: AuthSession,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<void> {
  await callIdentityToolkit('accounts:delete', { idToken: session.idToken }, cfg, fetchImpl);
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
  if (typeof v === 'number') {
    if (Number.isInteger(v)) return { integerValue: String(v) };
    return { doubleValue: v };
  }
  if (Array.isArray(v)) {
    return { arrayValue: { values: v.map((item) => toFsValue(item)) } };
  }
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

function docFieldsFromRaw(raw: Record<string, FsValue> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw ?? {})) {
    out[k] = fromFsValue(v);
  }
  return out;
}

function firestoreDocName(cfg: FirebaseWebEnv, relPath: string): string {
  return `projects/${encodeURIComponent(cfg.projectId)}/databases/${encodeURIComponent(cfg.databaseId)}/documents/${relPath}`;
}

function firestoreBaseUrl(cfg: FirebaseWebEnv): string {
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(cfg.projectId)}/databases/${encodeURIComponent(cfg.databaseId)}/documents`;
}

function sanitizeDocId(raw: string): string {
  const clean = raw.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 128);
  return clean || `doc_${Date.now()}`;
}

async function commitUpsertWithServerTimestamps(
  cfg: FirebaseWebEnv,
  idToken: string,
  relDocPath: string,
  scalarFields: Record<string, unknown>,
  createOnly: boolean,
  fetchImpl: typeof fetch
): Promise<void> {
  const docName = firestoreDocName(cfg, relDocPath);
  const fields: Record<string, FsValue> = {};
  const updatePaths: string[] = [];

  for (const [k, v] of Object.entries(scalarFields)) {
    if (k === 'createdAt' || k === 'updatedAt' || k === 'geminiApiKey') continue;
    fields[k] = toFsValue(v);
    updatePaths.push(k);
  }

  const fieldTransforms = [
    ...(createOnly ? [{ fieldPath: 'createdAt', setToServerValue: 'REQUEST_TIME' }] : []),
    { fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' }
  ];

  const write: Record<string, unknown> = {
    update: { name: docName, fields },
    updateMask: { fieldPaths: updatePaths },
    updateTransforms: fieldTransforms
  };
  if (createOnly) {
    // Only set createdAt when creating if doc does not exist, otherwise commit without overwriting createdAt:
    // Use currentDocument: { exists: false } first? No — simpler: check existence once with GET so we match Firestore rules' incoming().createdAt == existing().createdAt!
    const existingRes = await fetchImpl(`${firestoreBaseUrl(cfg)}/${relDocPath}`, {
      method: 'GET',
      redirect: 'error',
      headers: { Authorization: `Bearer ${idToken}` }
    });
    if (existingRes.ok) {
      // Document exists: omit createdAt transform so createdAt remains immutable
      write.updateTransforms = [{ fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' }];
    }
  }

  const commitUrl = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(cfg.projectId)}/databases/${encodeURIComponent(cfg.databaseId)}/documents:commit`;
  const res = await fetchImpl(commitUrl, {
    method: 'POST',
    redirect: 'error',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`
    },
    body: JSON.stringify({ writes: [write] })
  });
  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new FirebaseSyncError('HTTP_ERROR', `Firestore sync HTTP ${res.status}: ${errText.slice(0, 180)}`);
  }
}

async function deleteFirestoreDoc(
  cfg: FirebaseWebEnv,
  idToken: string,
  relDocPath: string,
  fetchImpl: typeof fetch
): Promise<void> {
  const res = await fetchImpl(`${firestoreBaseUrl(cfg)}/${relDocPath}`, {
    method: 'DELETE',
    redirect: 'error',
    headers: { Authorization: `Bearer ${idToken}` }
  });
  if (!res.ok && res.status !== 404) {
    throw new FirebaseSyncError('HTTP_ERROR', `Firestore delete HTTP ${res.status}`);
  }
}

async function listCollectionDocs(
  cfg: FirebaseWebEnv,
  idToken: string,
  relColPath: string,
  fetchImpl: typeof fetch
): Promise<Array<{ id: string; fields: Record<string, unknown> }>> {
  const items: Array<{ id: string; fields: Record<string, unknown> }> = [];
  let pageToken = '';
  do {
    const qs = new URLSearchParams({ pageSize: '300' });
    if (pageToken) qs.set('pageToken', pageToken);
    const res = await fetchImpl(`${firestoreBaseUrl(cfg)}/${relColPath}?${qs.toString()}`, {
      method: 'GET',
      redirect: 'error',
      headers: { Authorization: `Bearer ${idToken}` }
    });
    if (res.status === 404) break;
    if (!res.ok) {
      throw new FirebaseSyncError('HTTP_ERROR', `Firestore list HTTP ${res.status}`);
    }
    const json = (await res.json()) as {
      documents?: Array<{ name: string; fields?: Record<string, FsValue> }>;
      nextPageToken?: string;
    };
    for (const d of json.documents ?? []) {
      const id = d.name.split('/').pop() ?? '';
      if (id) items.push({ id, fields: docFieldsFromRaw(d.fields) });
    }
    pageToken = json.nextPageToken ?? '';
  } while (pageToken);
  return items;
}

export async function ensureUserProfile(
  session: AuthSession,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<void> {
  assertConfiguredAndAuthenticated(session, cfg);
  const relPath = `users/${encodeURIComponent(session.uid)}`;
  const existing = await fetchImpl(`${firestoreBaseUrl(cfg)}/${relPath}`, {
    method: 'GET',
    redirect: 'error',
    headers: { Authorization: `Bearer ${session.idToken}` }
  });
  if (existing.ok) return;

  await commitUpsertWithServerTimestamps(
    cfg,
    session.idToken,
    relPath,
    {
      userId: session.uid,
      displayName: (session.displayName || 'Student').slice(0, 100),
      email: (session.email || '').slice(0, 200),
      studyStreakDays: 1,
      weeklyStudyMinutes: 0,
      dailyGoalMinutes: 60,
      dashboardSections: ['continue', 'quick_actions', 'revision_queue', 'tasks', 'exams', 'weak_topics', 'library']
    },
    true,
    fetchImpl
  );
}

/**
 * Dispatches a queued SyncOperation to Firestore REST API conforming strictly to firestore.rules.
 * Never returns silently when unconfigured or unauthenticated.
 */
export async function pushSyncOperationToFirestore(
  op: SyncOperation,
  session: AuthSession | null,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<void> {
  assertConfiguredAndAuthenticated(session, cfg);
  const uid = encodeURIComponent(session.uid);
  const entityId = encodeURIComponent(sanitizeDocId(op.entityId));
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
      const bookmarkedPages = Array.isArray(payload.bookmarkedPages)
        ? payload.bookmarkedPages.map((n) => Math.max(1, Math.floor(Number(n)))).filter(Number.isFinite).slice(0, 500)
        : [];
      await commitUpsertWithServerTimestamps(
        cfg,
        session.idToken,
        `users/${uid}/books/${entityId}`,
        {
          title: String(payload.title ?? 'Study Textbook').slice(0, 160),
          subjectName: String(payload.subjectName ?? 'General').slice(0, 80),
          sourceType: String(payload.sourceType ?? 'WEB_TEXT').slice(0, 20),
          localBookId: String(payload.localBookId ?? op.entityId).slice(0, 128),
          totalPages,
          processedPages,
          currentPage,
          extractionStatus: String(payload.extractionStatus ?? 'COMPLETE'),
          overview: String(payload.overview ?? '').slice(0, 4000),
          formulaSheet: String(payload.formulaSheet ?? '').slice(0, 10000),
          archived: Boolean(payload.archived ?? false),
          masteryScore: Math.max(0, Math.min(100, Math.round(Number(payload.masteryScore ?? 0) || 0))),
          bookmarkedPages,
          highlightsJson: String(payload.highlightsJson ?? '{}').slice(0, 20000)
        },
        true,
        fetchImpl
      );
      break;
    }

    case 'SYNC_BOOK_PAGES': {
      const pages = Array.isArray(payload.pages)
        ? (payload.pages as Array<{ pageNumber: number; text: string }>)
        : [];
      for (const p of pages) {
        const pNum = Math.max(1, Math.min(5000, Math.floor(Number(p.pageNumber) || 1)));
        await commitUpsertWithServerTimestamps(
          cfg,
          session.idToken,
          `users/${uid}/books/${entityId}/pages/p_${pNum}`,
          {
            bookId: sanitizeDocId(op.entityId),
            pageNumber: pNum,
            text: String(p.text ?? '').slice(0, 12000),
            extractionMethod: String(payload.extractionMethod ?? 'PDF_TEXT').slice(0, 40),
            error: '',
            clientUpdatedAtMillis: Number(payload.clientUpdatedAtMillis ?? Date.now())
          },
          true,
          fetchImpl
        );
      }
      break;
    }

    case 'DELETE_BOOK': {
      for (const sub of ['pages', 'chapters', 'glossary']) {
        const docs = await listCollectionDocs(cfg, session.idToken, `users/${uid}/books/${entityId}/${sub}`, fetchImpl);
        for (const d of docs) {
          await deleteFirestoreDoc(
            cfg,
            session.idToken,
            `users/${uid}/books/${entityId}/${sub}/${encodeURIComponent(d.id)}`,
            fetchImpl
          );
        }
      }
      await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}/books/${entityId}`, fetchImpl);
      break;
    }

    case 'UPSERT_NOTE': {
      await commitUpsertWithServerTimestamps(
        cfg,
        session.idToken,
        `users/${uid}/notes/${entityId}`,
        {
          bookId: String(payload.bookId ?? 'general').slice(0, 128),
          subjectName: String(payload.subjectName ?? 'General').slice(0, 80),
          chapterNumber: Math.max(0, Math.min(500, Math.floor(Number(payload.chapterNumber ?? 0) || 0))),
          linkedPage: Math.max(0, Math.min(5000, Math.floor(Number(payload.linkedPage ?? 1) || 0))),
          title: String(payload.title ?? 'Note').slice(0, 160),
          contentMarkdown: String(payload.contentMarkdown ?? '').slice(0, 30000),
          bookmarked: Boolean(payload.bookmarked ?? false),
          quickNote: Boolean(payload.quickNote ?? false)
        },
        true,
        fetchImpl
      );
      break;
    }

    case 'DELETE_NOTE': {
      await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}/notes/${entityId}`, fetchImpl);
      break;
    }

    case 'SUBMIT_EXAM': {
      const totalMarks = Math.max(1, Math.min(1000, Math.round(Number(payload.totalMarks ?? 1) || 1)));
      const score = Math.max(0, Math.min(totalMarks, Math.round(Number(payload.score ?? 0) || 0)));
      const percentage = Math.max(0, Math.min(100, Number(payload.percentage ?? 0) || 0));
      const qIds = Array.isArray(payload.questionIds)
        ? payload.questionIds.map((x) => String(x).slice(0, 128)).slice(0, 200)
        : ['q_1'];
      const bookIds = Array.isArray(payload.bookIds)
        ? payload.bookIds.map((x) => String(x).slice(0, 128)).slice(0, 20)
        : [String(payload.bookId ?? 'general').slice(0, 128)];
      await commitUpsertWithServerTimestamps(
        cfg,
        session.idToken,
        `users/${uid}/exams/${entityId}`,
        {
          title: String(payload.title ?? 'Exam').slice(0, 160),
          preset: 'CUSTOM',
          bookIds,
          questionIds: qIds.length > 0 ? qIds : ['q_1'],
          durationMinutes: 30,
          totalMarks,
          allowRevisit: true,
          showExplanationEach: false,
          status: 'SUBMITTED',
          answersJson: String(payload.answersJson ?? '{}').slice(0, 50000),
          flaggedQuestionIds: [],
          score,
          percentage,
          correctCount: Math.max(0, Math.round(Number(payload.correctCount ?? 0) || 0)),
          incorrectCount: Math.max(0, Math.round(Number(payload.incorrectCount ?? 0) || 0)),
          unansweredCount: Math.max(0, Math.round(Number(payload.unansweredCount ?? 0) || 0)),
          timeSpentSeconds: Math.max(0, Math.round(Number(payload.timeSpentSeconds ?? 0) || 0))
        },
        true,
        fetchImpl
      );
      break;
    }

    case 'SYNC_PREFS': {
      await commitUpsertWithServerTimestamps(
        cfg,
        session.idToken,
        `users/${uid}/preferences/app`,
        {
          themeMode: String(payload.themeMode ?? 'DARK').slice(0, 20),
          languageCode: String(payload.languageCode ?? 'en').slice(0, 10),
          pomodoroWorkMinutes: 25,
          pomodoroShortBreakMinutes: 5,
          pomodoroLongBreakMinutes: 15,
          ambientFocusSound: false,
          aiStrategy: String(payload.aiStrategy ?? 'AUTO').slice(0, 30),
          aiFallbackEnabled: Boolean(payload.aiFallbackEnabled ?? true),
          ollamaModel: String(payload.ollamaModel ?? 'llama3.2:3b').slice(0, 100),
          geminiModel: String(payload.geminiModel ?? 'gemini-3-flash-preview').slice(0, 100),
          aiTimeoutMs: Math.max(5000, Math.min(180000, Number(payload.aiTimeoutMs ?? 30000) || 30000)),
          clientUpdatedAtMillis: Number(payload.clientUpdatedAtMillis ?? Date.now())
        },
        true,
        fetchImpl
      );
      break;
    }
  }
}

export interface PulledCloudWorkspace {
  books: Book[];
  notes: StudyNote[];
  exams: ExamRecord[];
  preferences?: Partial<UserSettings> & { clientUpdatedAtMillis?: number };
}

export async function pullWorkspaceFromFirestore(
  session: AuthSession | null,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<PulledCloudWorkspace> {
  assertConfiguredAndAuthenticated(session, cfg);
  const uid = encodeURIComponent(session.uid);

  const [bookDocs, noteDocs, examDocs, prefDocs] = await Promise.all([
    listCollectionDocs(cfg, session.idToken, `users/${uid}/books`, fetchImpl),
    listCollectionDocs(cfg, session.idToken, `users/${uid}/notes`, fetchImpl),
    listCollectionDocs(cfg, session.idToken, `users/${uid}/exams`, fetchImpl),
    listCollectionDocs(cfg, session.idToken, `users/${uid}/preferences`, fetchImpl)
  ]);

  const books: Book[] = [];
  for (const bd of bookDocs) {
    const f = bd.fields;
    let hlMap: Record<number, string[]> = {};
    try {
      const parsed = JSON.parse(String(f.highlightsJson ?? '{}')) as Record<string, string | string[]>;
      for (const [k, v] of Object.entries(parsed)) {
        const p = Number(k);
        if (!Number.isFinite(p)) continue;
        hlMap[p] = Array.isArray(v)
          ? v.map(String)
          : String(v)
              .split('\n• ')
              .map((s) => s.replace(/^•\s*/, '').trim())
              .filter(Boolean);
      }
    } catch {
      hlMap = {};
    }

    const pageDocs = await listCollectionDocs(
      cfg,
      session.idToken,
      `users/${uid}/books/${encodeURIComponent(bd.id)}/pages`,
      fetchImpl
    );
    const pages = pageDocs
      .map((pd) => ({
        pageNumber: Math.max(1, Number(pd.fields.pageNumber ?? 1)),
        text: String(pd.fields.text ?? '')
      }))
      .sort((a, b) => a.pageNumber - b.pageNumber);

    books.push({
      id: bd.id,
      title: String(f.title ?? 'Textbook'),
      subjectName: String(f.subjectName ?? 'General'),
      totalPages: Math.max(1, Number(f.totalPages ?? pages.length) || 1),
      currentPage: Math.max(1, Number(f.currentPage ?? 1) || 1),
      lastUpdatedMillis: Number(f.updatedAt ?? Date.now()),
      bookmarkedPages: Array.isArray(f.bookmarkedPages) ? f.bookmarkedPages.map(Number) : [],
      highlightsByPage: hlMap,
      overview: String(f.overview ?? ''),
      formulaSheet: String(f.formulaSheet ?? ''),
      chapters: [
        {
          id: `${bd.id}_ch1`,
          chapterNumber: 1,
          title: String(f.title ?? 'Chapter 1'),
          startPage: 1,
          endPage: Math.max(1, Number(f.totalPages ?? pages.length) || 1),
          summary: String(f.overview ?? '')
        }
      ],
      pages: pages.length > 0 ? pages : [{ pageNumber: 1, text: String(f.overview ?? '') }]
    });
  }

  const notes: StudyNote[] = noteDocs.map((nd) => ({
    id: nd.id,
    bookId: String(nd.fields.bookId ?? 'general'),
    chapterNumber: Number(nd.fields.chapterNumber ?? 1),
    linkedPage: Number(nd.fields.linkedPage ?? 1),
    title: String(nd.fields.title ?? 'Note'),
    contentMarkdown: String(nd.fields.contentMarkdown ?? ''),
    updatedAtMillis: Number(nd.fields.updatedAt ?? Date.now())
  }));

  const exams: ExamRecord[] = examDocs.map((ed) => {
    let answers: Record<string, string> = {};
    try {
      answers = JSON.parse(String(ed.fields.answersJson ?? '{}')) as Record<string, string>;
    } catch {
      answers = {};
    }
    return {
      id: ed.id,
      title: String(ed.fields.title ?? 'Exam'),
      bookId: Array.isArray(ed.fields.bookIds) ? String(ed.fields.bookIds[0] ?? 'all') : 'all',
      questionIds: Array.isArray(ed.fields.questionIds) ? ed.fields.questionIds.map(String) : [],
      answersByQuestionId: answers,
      flaggedQuestionIds: Array.isArray(ed.fields.flaggedQuestionIds) ? ed.fields.flaggedQuestionIds.map(String) : [],
      evaluationsByQuestionId: {},
      score: Number(ed.fields.score ?? 0),
      totalMarks: Number(ed.fields.totalMarks ?? 1),
      percentage: Number(ed.fields.percentage ?? 0),
      createdAtMillis: Number(ed.fields.createdAt ?? Date.now()),
      status: ed.fields.status === 'SUBMITTED' ? 'SUBMITTED' : 'IN_PROGRESS'
    };
  });

  const appPref = prefDocs.find((p) => p.id === 'app')?.fields;
  const preferences = appPref
    ? {
        language: (appPref.languageCode === 'ar' ? 'ar' : 'en') as 'en' | 'ar',
        clientUpdatedAtMillis: Number(appPref.clientUpdatedAtMillis ?? 0),
        aiConfig: {
          strategy: (String(appPref.aiStrategy ?? 'AUTO') as UserSettings['aiConfig']['strategy']),
          fallbackEnabled: Boolean(appPref.aiFallbackEnabled ?? true),
          ollamaEndpoint: 'http://localhost:11434',
          ollamaModel: String(appPref.ollamaModel ?? 'llama3.2:3b'),
          geminiModel: String(appPref.geminiModel ?? 'gemini-3-flash-preview'),
          geminiApiKey: '',
          timeoutMs: Number(appPref.aiTimeoutMs ?? 30000)
        }
      }
    : undefined;

  return { books, notes, exams, preferences };
}

export async function deleteAllCloudUserData(
  session: AuthSession | null,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<{ ok: boolean; errors: string[] }> {
  assertConfiguredAndAuthenticated(session, cfg);
  const uid = encodeURIComponent(session.uid);
  const errors: string[] = [];

  try {
    const books = await listCollectionDocs(cfg, session.idToken, `users/${uid}/books`, fetchImpl);
    for (const b of books) {
      const bId = encodeURIComponent(b.id);
      for (const sub of ['chapters', 'glossary', 'pages']) {
        try {
          const subDocs = await listCollectionDocs(cfg, session.idToken, `users/${uid}/books/${bId}/${sub}`, fetchImpl);
          for (const sd of subDocs) {
            await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}/books/${bId}/${sub}/${encodeURIComponent(sd.id)}`, fetchImpl);
          }
        } catch (err) {
          errors.push(`${sub}:${err instanceof Error ? err.message : 'delete failed'}`);
        }
      }
      try {
        await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}/books/${bId}`, fetchImpl);
      } catch (err) {
        errors.push(`book:${err instanceof Error ? err.message : 'delete failed'}`);
      }
    }
  } catch (err) {
    errors.push(`books:${err instanceof Error ? err.message : 'list failed'}`);
  }

  const topCols = [
    'subjects',
    'questions',
    'flashcards',
    'exams',
    'notes',
    'plannerTasks',
    'focusSessions',
    'tutorMessages',
    'citations',
    'preferences'
  ];
  for (const col of topCols) {
    try {
      const docs = await listCollectionDocs(cfg, session.idToken, `users/${uid}/${col}`, fetchImpl);
      for (const d of docs) {
        await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}/${col}/${encodeURIComponent(d.id)}`, fetchImpl);
      }
    } catch (err) {
      errors.push(`${col}:${err instanceof Error ? err.message : 'delete failed'}`);
    }
  }

  try {
    await deleteFirestoreDoc(cfg, session.idToken, `users/${uid}`, fetchImpl);
  } catch (err) {
    errors.push(`profile:${err instanceof Error ? err.message : 'delete failed'}`);
  }

  return { ok: errors.length === 0, errors };
}
