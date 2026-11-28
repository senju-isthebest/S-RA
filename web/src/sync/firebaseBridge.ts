import type { SyncOperation } from '../types/sora';

export interface FirebaseWebEnv {
  apiKey: string;
  projectId: string;
  databaseId: string;
}

export function readFirebaseWebEnv(): FirebaseWebEnv {
  const env = (import.meta as { env?: Record<string, string | undefined> }).env ?? {};
  return {
    apiKey: env.VITE_FIREBASE_API_KEY?.trim() ?? '',
    projectId: env.VITE_FIREBASE_PROJECT_ID?.trim() ?? '',
    databaseId: env.VITE_FIREBASE_DATABASE_ID?.trim() || 'sora-db'
  };
}

export function isFirebaseSyncConfigured(cfg: FirebaseWebEnv): boolean {
  return Boolean(cfg.apiKey && cfg.projectId);
}

/**
 * Dispatches a queued SyncOperation to Firestore REST API when web Firebase env + idToken
 * are configured; otherwise operates gracefully in local browser storage mode.
 */
export async function pushSyncOperationToFirestore(
  op: SyncOperation,
  uid: string,
  idToken: string,
  cfg: FirebaseWebEnv,
  fetchImpl: typeof fetch = fetch
): Promise<void> {
  if (!isFirebaseSyncConfigured(cfg) || !uid || !idToken) {
    // Graceful local-only noop when cloud Firebase web config is absent
    return;
  }
  const collectionName =
    op.opType === 'UPSERT_BOOK' ? 'books' : op.opType === 'UPSERT_NOTE' ? 'notes' : op.opType === 'SUBMIT_EXAM' ? 'exams' : 'preferences';
  const docPath = `projects/${encodeURIComponent(cfg.projectId)}/databases/${encodeURIComponent(cfg.databaseId)}/documents/users/${encodeURIComponent(uid)}/${collectionName}/${encodeURIComponent(op.entityId)}`;
  const url = `https://firestore.googleapis.com/v1/${docPath}`;

  const res = await fetchImpl(url, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`
    },
    body: JSON.stringify({
      fields: {
        payloadJson: { stringValue: op.payloadJson },
        updatedAtMillis: { integerValue: String(Date.now()) }
      }
    })
  });
  if (!res.ok) {
    throw new Error(`Firestore sync HTTP ${res.status}`);
  }
}
