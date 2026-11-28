# SŌRA Android Release & Play Readiness Guide

This document summarizes the Android release configuration (Task 7) and the manual verification steps required prior to Google Play submission.

## Release Build Configuration (`app/build.gradle.kts`)

- **Application ID**: `com.aistudio.sora.kxmzqr`
- **Target / Min SDK**: `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`
- **R8 Code & Resource Shrinking**:
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
  - Configured with `proguard-android-optimize.txt` + `app/proguard-rules.pro`.
- **Signing Configuration**:
  - Uses existing AI Studio `signingConfigs.release` reading:
    - `KEYSTORE_PATH` (environment variable)
    - `STORE_PASSWORD` (environment variable)
    - `KEY_PASSWORD` (environment variable)
    - `keyAlias = "upload"`
  - Secrets are never committed to source control.

## ProGuard / R8 Rules (`app/proguard-rules.pro`)

- Retains `@Keep` Firestore data classes (`com.example.data.model.**`) and `@PropertyName` / `@DocumentId` fields for runtime serialization.
- Retains custom `SoraJson` AST classes and `EncryptedSecretBox` cipher paths.
- Retains `Firebase` AI/Auth/Firestore/AppCheck, `CredentialManager`, and Google Identity (`googleid`) classes.
- Strips debug/verbose `android.util.Log.d` and `Log.v` calls in release APKs/AABs while keeping `SourceFile` + `LineNumberTable` mapping metadata for Play Console crash symbolication.

## Google Play Policy Checklist

- **Permissions (`AndroidManifest.xml`)**:
  - Declares only `android.permission.INTERNET` (install-time).
  - Zero broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`); all media/document selection uses `ActivityResultContracts.PickVisualMedia` and `OpenDocument`.
- **Account Deletion**:
  - Users can delete both their Firebase Authentication account and all nested Firestore subcollections (`books`, `pages`, `chapters`, `glossary`, `notes`, `questions`, `flashcards`, `exams`, `plannerTasks`, `focusSessions`, `tutorMessages`, `citations`, `preferences`, `profile`) directly in-app via **Account & Security → Delete Account & Cloud Data**, with re-authentication prompt handling when required by Firebase Auth.
- **Data Portability**:
  - Users can export their full workspace as non-secret JSON via `ExportManager.shareJsonExport`.

## Building Signed Release Artifacts (On Local Machine / CI)

```bash
export KEYSTORE_PATH=/path/to/upload-keystore.jks
export STORE_PASSWORD=***
export KEY_PASSWORD=***

gradle :app:bundleRelease   # Generates signed .aab for Google Play Console
gradle :app:assembleRelease # Generates signed release .apk for sideload verification
```

## Remaining Manual Hardware Verification

1. Install `:app:assembleRelease` APK on a physical Android device or Play Internal Testing track and verify R8 startup, Firestore real-time listeners, and JSON serialization.
2. Verify Google Sign-In + live Firestore sync across two devices and confirm Account Deletion prompts recent re-login cleanly.
3. Verify local Ollama (`http://<LAN-IP>:11434` / `http://10.0.2.2:11434`) connectivity, streaming tutor responses, and multimodal Vision/OCR page capture.
4. Inspect Arabic (`ar`) RTL layout rendering on all screens (`HomeDashboardScreen`, `BookWorkspaceScreen`, `ExamsPracticeScreen`, `SyncCenterDialog`, `AccountSecuritySheet`).
