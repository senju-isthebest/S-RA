# SŌRA — AI-Powered Student Learning Platform

**SŌRA** (`com.aistudio.sora.kxmzqr`) is a cross-platform educational learning environment across **Android (Kotlin + Jetpack Compose + Firebase)**, **Web / PWA (`web/`)**, and **Windows Desktop (`windows/`)**. It transforms uploaded textbooks, lecture notes, presentations, and scanned pages into structured study workspaces featuring full-book intelligence, chapter summaries, mind maps, spaced-repetition flashcards, a 20-format exam engine, mistake tracking, a study planner, focus timers, mastery analytics, and encrypted secret/account controls.

---

## Project Roadmap Status (All 9 Tasks Completed)

1. **Task 1 — AI Infrastructure**: Unified `AiProviderRouter` (Ollama text/streaming/JSON/vision + Gemini Firebase AI Logic / REST), `/api/tags` discovery, SSRF/endpoint validation, EN + AR localization, and live RTL switcher.
2. **Task 2 — Cloud Book Sync**: Offline-first `CloudSyncManager`, persistent disk-backed sync queue, exponential backoff retries, field-level `ConflictResolver`, local-only privacy toggle, and Firestore security rules (`docs/CLOUD_SYNC.md`).
3. **Task 3 — Full-Book Intelligence**: `BookPassageRetriever` (bilingual EN/AR BM25 + token-budget packing), `FullBookIntelligenceEngine`, live tutor citation jumps, and resilient chapter-by-chapter analysis.
4. **Task 4 — Exam Engine**: `ExamEngine` covering all 20 `QuestionType`s, deterministic offline grading (EN/AR booleans, numeric tolerance + fractions, rubric matching), AI rubric grading fallback, `OfflineExamCache`, and "Retake Missed" flow.
5. **Task 5 — Security & Accounts**: `EncryptedSecretBox` (AES-256-GCM + Android Keystore), `InputValidator`, `AccountSecuritySheet`, non-secret JSON export, cache clearing, and permanent account deletion.
6. **Task 6 — Testing & Bug Fixes**: Unit, loopback HTTP, Robolectric Compose UI, Web Vitest, and Windows Node test suites across core engines, localization parity, security, and sync.
7. **Task 7 — Android Release**: R8 minification & resource shrinking (`app/build.gradle.kts`, `app/proguard-rules.pro`), network & backup security hardening, and release checklist (`docs/ANDROID_RELEASE.md`).
8. **Task 8 — Web / PWA (`web/`)**: React + TypeScript + Vite PWA with IndexedDB offline persistence, Web Speech/OCR/textbook ingestion, BM25 retrieval, exam engine, EN/AR RTL UI, and service worker.
9. **Task 9 — Windows Desktop (`windows/`) & CI (`.github/workflows/ci.yml`)**: Electron + TypeScript desktop app with context-isolated IPC, DPAPI/AES-256-GCM vault (`DesktopVaultStore`), shared offline engines, NSIS/MSI packaging config, and multi-OS GitHub Actions CI.

---

## Repository Layout & Documentation

- **Android App**: `app/` (`com.example` / `com.aistudio.sora.kxmzqr`)
- **Cloud Sync Architecture**: [`docs/CLOUD_SYNC.md`](docs/CLOUD_SYNC.md)
- **Android Release Checklist**: [`docs/ANDROID_RELEASE.md`](docs/ANDROID_RELEASE.md)
- **Web / PWA App**: [`web/README.md`](web/README.md)
- **Windows Desktop App**: [`windows/README.md`](windows/README.md)
- **CI Workflow**: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

---

## Local Verification Commands

```bash
# Android unit, integration & Robolectric UI tests
gradle :app:testDebugUnitTest --no-daemon

# Android debug APK
gradle assembleDebug

# Web / PWA checks
npm --prefix web run typecheck && npm --prefix web test && npm --prefix web run build

# Windows Desktop checks
npm --prefix windows run typecheck && npm --prefix windows test
```
