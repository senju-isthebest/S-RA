# SŌRA Cloud Book Sync Architecture

This document covers the Task 2 cloud synchronization system built for SŌRA across Android, Web, and Windows.

## Overview

SŌRA uses an **offline-first, deterministic synchronization model** backed by Firebase Authentication (Google Sign-In via Jetpack Credential Manager) and Cloud Firestore (`sora-db`). Every user's study artifacts reside under strict zero-trust ownership isolation:

```
users/{uid}/
  profile/meta
  preferences/main            # Non-secret settings (language, theme, AI endpoint prefs, pomodoro)
  books/{bookId}/
    chapters/{chapterId}
    glossary/{entryId}
    pages/{pageId}            # Extracted textbook page chunks (chunked <= 12,000 chars)
  questions/{questionId}
  flashcards/{flashcardId}
  exams/{examId}
  notes/{noteId}
  plannerTasks/{taskId}
  focusSessions/{sessionId}
  tutorMessages/{messageId}
  citations/{citationId}
```

## Security & Secret Isolation

- **Zero Secrets in Cloud Sync**: Sensitive credentials (such as a user-provided optional Gemini API key) are **never** placed in `SyncedUserPreferences` or written to Firestore. Local API keys remain strictly inside device-local encrypted storage (`EncryptedSecretBox` / AES-256-GCM on Android, `safeStorage` on Windows Electron).
- **Local-Only Privacy Mode**: Users can toggle `localOnlyPrivacyMode` inside Account & Security. When enabled:
  - Remote writes from `CloudSyncManager.enqueueOrRun(...)` are blocked and remain local or queued on-device.
  - Exams save locally in `OfflineExamCache` without uploading to Firestore.
- **Firestore Rules (`firestore.rules`)**: Every document and subcollection enforces `request.auth != null && request.auth.uid == userId`, strict schema type validation, and bounded string/array limits (including `pages` and `preferences`).

## Offline-First Queue & Retry

- `CloudSyncManager` persists pending `OfflineSyncOperation` entries atomically into `filesDir/sync/queue_<sanitized_uid>.json`.
- Supported operations: `SYNC_BOOK_PAGES`, `UPDATE_BOOK_FIELDS`, `SYNC_PREFERENCES`, `UPSERT_NOTE`, `DELETE_NOTE`, `SUBMIT_EXAM`.
- When offline (`NetworkConnectivityProvider.isOnline() == false`) or when a transient network error occurs, operations stay queued cleanly with `SyncStatus.OFFLINE_QUEUED` or `SyncStatus.ERROR`.
- `flushPending()` retries queued items with exponential backoff (`baseBackoffMs * 2^attempt`, capped safely) and stops after `maxAttempts` (`5`), surfacing retry errors in `SyncCenterDialog`.

## Conflict Resolution (`ConflictResolver`)

- **Reading Progress (`mergeReadingPage`)**: Last-write-wins by updated timestamp, with a `1,500ms` near-simultaneous tie-breaker picking the furthest page (`max(localPage, remotePage)`).
- **Bookmarks (`mergeBookmarks`)**: Deterministic sorted set union capped at 500 pages so multi-device bookmarks never overwrite one another.
- **Highlights (`mergeHighlightsJson`)**: Per-page bullet deduplication merging annotations added offline across multiple devices.
- **Preferences (`shouldApplyRemotePreferences`)**: Remote preferences override local settings only when `remote.clientUpdatedAtMillis > local.updatedAtMillis`.
- **Page Hydration (`hydrateMissingBookPages`)**: Opening a synced book on a second device with empty local pages automatically restores `BookPageDoc`s from `users/{uid}/books/{bookId}/pages` into local storage.
