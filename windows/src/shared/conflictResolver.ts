import type { Book, SyncOperation } from './models';

export const ConflictResolver = {
  mergeReadingPage(localPage: number, remotePage: number, localTs: number, remoteTs: number): { page: number; hadConflict: boolean } {
    const l = Math.max(1, localPage);
    const r = Math.max(1, remotePage);
    if (l === r) return { page: l, hadConflict: false };
    if (Math.abs(localTs - remoteTs) <= 1500) return { page: Math.max(l, r), hadConflict: true };
    return { page: remoteTs > localTs ? r : l, hadConflict: true };
  },

  mergeBookmarks(local: number[], remote: number[]): number[] {
    return Array.from(new Set([...local, ...remote].filter((n) => n >= 1))).sort((a, b) => a - b).slice(0, 500);
  },

  mergeBook(local: Book, remote: Book): { book: Book; hadConflict: boolean } {
    const p = this.mergeReadingPage(local.currentPage, remote.currentPage, local.lastUpdatedMillis, remote.lastUpdatedMillis);
    const mergedBm = this.mergeBookmarks(local.bookmarkedPages, remote.bookmarkedPages);
    const hl: Record<number, string[]> = { ...local.highlightsByPage };
    for (const [k, vals] of Object.entries(remote.highlightsByPage)) {
      const pn = Number(k);
      hl[pn] = Array.from(new Set([...(hl[pn] ?? []), ...vals]));
    }
    return {
      book: {
        ...local,
        currentPage: p.page,
        lastUpdatedMillis: Math.max(local.lastUpdatedMillis, remote.lastUpdatedMillis),
        bookmarkedPages: mergedBm,
        highlightsByPage: hl
      },
      hadConflict: p.hadConflict
    };
  }
};

export async function processSyncQueue(
  queue: SyncOperation[],
  dispatch: (op: SyncOperation) => Promise<void>,
  maxAttempts = 4
): Promise<{ remaining: SyncOperation[]; processedCount: number; lastError: string }> {
  const remaining: SyncOperation[] = [];
  let processedCount = 0;
  let lastError = '';
  for (const op of queue) {
    let ok = false;
    let attempts = op.attempts;
    let errText = '';
    while (attempts < maxAttempts && !ok) {
      try {
        await dispatch(op);
        ok = true;
        processedCount++;
      } catch (err) {
        attempts++;
        errText = err instanceof Error ? err.message : 'Sync retry failed';
      }
    }
    if (!ok) {
      lastError = errText;
      remaining.push({ ...op, attempts, lastError: errText });
    }
  }
  return { remaining, processedCount, lastError };
}
