# SŌRA — AI-Powered Student Learning Platform

**SŌRA** (`com.aistudio.sora.kxmzqr`) is an Android learning environment built with Kotlin, Jetpack Compose, Material Design 3, and Firebase. It transforms uploaded textbooks, lecture notes, presentations, and scanned pages into structured study workspaces featuring chapter intelligence, interactive mind maps, spaced-repetition flashcards, a multi-format question bank, timed exam simulation, mistake tracking, a study planner, focus timers, mastery analytics, and an integrated student toolbox.

---

## Project Roadmap Status

1. **Task 1 — AI Infrastructure** *(Completed — including full English/Arabic localization & RTL switcher)*
2. **Task 2 — Cloud Book Sync** *(Next)*
3. Full-Book Intelligence
4. Exam Engine
5. Security & Accounts
6. Testing & Bug Fixes
7. Android Release
8. Web / PWA
9. Windows App

---

## Task 1 — AI Infrastructure Architecture

SŌRA separates domain study workflows from provider transport details through a modular AI layer under `app/src/main/java/com/example/data/ai/`:

| Component | Responsibility |
| :--- | :--- |
| `AiModels.kt` | Typed request/response contracts (`AiTextRequest`, `AiVisionRequest`, `AiResponse`), routing strategies (`AiRoutingStrategy`), capability flags (`AiCapability`), and typed failure hierarchy (`AiError`) |
| `AiProviderAdapter` (`OllamaAdapter.kt`, `GeminiAdapter.kt`) | Unified provider interface supporting text generation, streaming, JSON mode, vision, and connection testing |
| `AiProviderRouter.kt` | Deterministic provider ordering (`GEMINI_FIRST`, `OLLAMA_FIRST`, `OLLAMA_ONLY`, `GEMINI_ONLY`), bounded transient retries (`ServerError`, `TimeoutError`, `ConnectionError`), error-aware fallback, cancellation propagation, and mid-stream fallback protection |
| `OllamaAdapter.kt` | Local Ollama `/api/generate` (text, streaming NDJSON, JSON `format: "json"`, Base64 vision `images`) and `/api/tags` model discovery with vision-model hints |
| `GeminiAdapter.kt` | Cloud Gemini via Firebase AI Logic (`Firebase.ai`) by default, plus optional Gemini REST mode (`x-goog-api-key` header) when a user-supplied key is configured |
| `AiHttpClient.kt` | `HttpUrlConnectionTransport` with strict connect/read timeouts, cooperative coroutine cancellation, and bounded response reading (4 MB cap) |
| `EndpointValidator.kt` | Strict Ollama endpoint validation (blocks remote cleartext `http://`, embedded credentials, query/fragment injection; allows HTTPS and local/private LAN/emulator bridge hosts) + `SecretRedactor` |
| `SoraJson.kt` & `Base64Codec.kt` | Pure-Kotlin RFC-compliant JSON parser/builder and Base64 encoder shared identically across Android and plain-JVM tests |
| `SoraAiService.kt` | High-level academic facade (book structure detection, chapter summaries/concept maps/glossary, questions, flashcards, streaming tutor, Teach-Back grading, equation solver, OCR) |
| `AiSettingsSheet.kt` | In-app AI settings sheet for routing strategy, fallback toggle, Ollama URL/model/vision model, live `/api/tags` model picker, Gemini model/API key management, and timeout slider |

### Security & Network Hygiene
- **Network Security Config (`res/xml/network_security_config.xml`)**: Global cleartext HTTP is disabled (`cleartextTrafficPermitted="false"`). Cleartext traffic is explicitly scoped only to `localhost`, `127.0.0.1`, and `10.0.2.2`.
- **Backup Exclusion (`backup_rules.xml`, `data_extraction_rules.xml`)**: `datastore/sora_prefs.preferences_pb` (which may hold a user-entered Gemini REST key) is excluded from cloud backups and device transfers.
- **Zero-Trust Auth**: Cloud data access uses Google Sign-In via Jetpack Credential Manager + Firebase Auth + Firestore security rules.

### Connecting Local Ollama to Android
- **Android Emulator**: Use `http://10.0.2.2:11434` (preset button in **AI & Language Settings**), which routes from the emulator to `localhost:11434` on the host machine.
- **Physical Device (USB)**: Run `adb reverse tcp:11434 tcp:11434` and keep the default `http://localhost:11434`.
- **Local Wi-Fi LAN**: Configure Ollama (`OLLAMA_HOST=0.0.0.0:11434`) and connect via your workstation's private LAN IP (e.g., `http://192.168.1.x:11434`) or HTTPS reverse proxy.

---

## English & Arabic Localization + Live RTL Switcher

- **Complete String Parity**: All user-facing UI strings, accessibility descriptions, errors, and pluralized counts are externalized in `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-ar/strings.xml` (Arabic).
- **Live Segmented Language Pill (`SoraLanguageSwitcher.kt`)**: Compact `EN | عربي` segmented pill accessible directly from the Sign-In hero, Home Dashboard header, and AI & Language Settings sheet.
- **Instant Runtime Switching (`AppLocaleUtils.kt`)**: Language preference is persisted in DataStore (`UserPreferences`) and applied immediately via `createLocalizedContext`, `LocalConfiguration`, `LocalLayoutDirection` (`LayoutDirection.Rtl` in Arabic, `LayoutDirection.Ltr` in English), and `LocalAppLanguage` without losing navigation, auth, or ViewModel state.
- **Typography & Directional Polish**:
  - English uses `SpaceGrotesk` + `Inter`; Arabic switches dynamically to bundled `Cairo` (`res/font/cairo.ttf`) in `SoraTheme` (`Type.kt`).
  - Technical tokens, URLs, equations, countdown clocks, and calculator expressions are wrapped in `LayoutDirection.Ltr` / Unicode directional isolates (`SoraFormatters.ltrIsolate`).
  - Directional navigation icons use `Icons.AutoMirrored.Rounded.*`.

---

## Build & Local Verification

Run the local unit, HTTP loopback integration, and Robolectric UI/localization test suite with Gradle:

```bash
gradle :app:testDebugUnitTest
```

Build the debug APK:

```bash
gradle assembleDebug
```

Test suites in `app/src/test/java/com/example/`:
- `AiProviderRouterTest.kt` — endpoint validation, secret redaction, Ollama/Gemini adapters, deterministic routing, transient retry bounds, typed error classification, streaming mid-stream protection, cancellation propagation, and real `HttpServer` loopback integration tests.
- `OllamaVisionAdapterTest.kt` — Base64 encoding, Ollama vision payload structure, model overrides, payload size guards, and vision fallback.
- `LocalizationAndRegressionTest.kt` — automated XML key & format-specifier parity between `values/strings.xml` and `values-ar/strings.xml`, DataStore persistence, live Compose `SoraLanguageSwitcher` + RTL verification, `AiSettingsSheet` validation, and `StudentToolboxEngine` / `SoraJson` regression checks.
- `ExampleRobolectricTest.kt` — app branding (`SŌRA`) and localized context verification.
