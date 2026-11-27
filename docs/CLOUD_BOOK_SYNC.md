# Cloud textbook sync (Task 2)

## Data layout
- Original file: Firebase Storage `users/{uid}/books/{bookId}/source`
- Metadata: Firestore `users/{uid}/books/{bookId}`
- Extracted page text: Firestore `users/{uid}/books/{bookId}/pages/p00001_c000` (page/chunk ids are deterministic)

The app retains the original and `pages.json` locally for offline reading. Cloud pages are split into 8,000-character chunks to stay comfortably below Firestore document limits. Each page is capped at 100,000 characters, and the whole book at 25 million extracted characters. Upload batches are capped at 450 operations.

## Sync behavior
- Import creates the book metadata, then uploads the source and extracted text.
- Source files are limited to 80 MiB and allow-listed MIME types.
- Upload metadata includes SHA-256, byte size, and original file name.
- Opening a book restores pages from Firestore if the local page cache is empty and downloads the source file if it is missing.
- Workspace cloud icon retries syncing the local source/pages.
- Source and page sync states are recorded as `LOCAL`, `UPLOADING`, `SYNCED`, or `FAILED`.
- Same-book re-sync overwrites deterministic page ids and removes obsolete chunks. Conflict policy is last-write-wins; no merge/version history is provided yet.
- Deleting a book attempts to remove cloud pages and source before deleting Firestore metadata/local files. If cloud cleanup fails, the app keeps the book record so cleanup can be retried.

## Rules
Storage rules restrict reads/writes/deletes to the authenticated UID's exact book path, enforce file size and MIME type, and deny all other paths. Firestore page rules are UID-scoped, bound text size, and validate page/chunk ids and metadata.

Deploy from the Firebase project configured for SŌRA:
```sh
firebase deploy --only firestore:rules,storage
```

Enable Firebase Storage in the Firebase Console and ensure Authentication is enabled before cloud sync is used.

## Emulator tests
Install Node dependencies, then run:
```sh
npm install
firebase emulators:exec --only firestore,storage --project demo-no-project "npm test"
```
CI runs the emulator-based rules tests separately from the Android build/test job.

## Current limitations
- Sync is initiated at import or manually from the workspace; there is no background WorkManager retry queue yet.
- The local cache is the current offline source of truth. If cloud upload fails, the book stays usable locally and can be retried.
- A book can be synced only while the user is authenticated and Firebase Storage is enabled.
