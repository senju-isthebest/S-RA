# Security and account-data review

## Authentication

- Google Identity credentials are exchanged with Firebase Authentication.
- The app observes Firebase Auth state and scopes the authenticated UI/ViewModel to the current UID.
- Sign-out clears Credential Manager state and signs out of Firebase Authentication.
- An account sign-out is not an account deletion.

## Firestore and Storage isolation

- Firestore denies access by default and each user document tree checks that the authenticated UID matches the path UID.
- Document create/update rules validate allowed fields, bounded values, immutable creation timestamps, and terminal submitted-exam state.
- Storage only exposes `users/{uid}/books/{bookId}/source` to the matching signed-in user. Upload size, MIME type, owner metadata, and book metadata are checked.
- Emulator tests cover unauthenticated access, cross-user reads/writes, Storage ownership, metadata mismatch, and unsupported MIME types.

## App Check

- Debug builds use the Firebase App Check debug provider.
- Release builds use the Android reCAPTCHA Enterprise provider.
- The reCAPTCHA site key is a public site identifier, not a private API credential.
- App Check enforcement is a Firebase Console setting. The repository configures the client provider but cannot prove that Firestore, Storage, and Firebase AI Logic enforcement is enabled in the project. Monitor metrics before enabling enforcement.

## Local secrets and device backups

- `.env`, `local.properties`, debug keystores, and common release signing-key formats are ignored by Git.
- Release signing passwords are read from environment variables; no signing key or password is stored in source.
- Android automatic backup is disabled so private local study data and cached account data are not copied through device backup.

## User data export

The dashboard provides an explicit JSON export action. It includes the profile, Firestore collections, and nested book pages, chapters, and glossary entries. It does not bundle the original binary textbook files stored in Firebase Storage. The file is written to the app cache and shared through the non-exported FileProvider.

## Account deletion limitation

Automated account deletion is not implemented yet. A correct deletion flow must require recent Google reauthentication, delete original files from Storage, delete nested Firestore collections (Firestore does not cascade deletes), delete the user profile, and only then delete the Firebase Auth account. Until that workflow is implemented and tested, users must not be told that signing out deletes their account or data.
