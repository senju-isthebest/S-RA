# SŌRA Windows Desktop App (`/windows`)

Standalone Electron + TypeScript desktop companion for Windows 10/11 (`x64`).

## Security & Architecture
- **Process Isolation**: `BrowserWindow` enforces `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, and `webSecurity: true`.
- **Strict ContextBridge (`src/main/preload.ts`)**: Renderer communicates only over vetted IPC channels (`sora:loadState`, `sora:saveState`, `sora:openBookFile`, `sora:setSecret`, `sora:getSecret`, `sora:exportJson`, `sora:aiAsk`, `sora:syncNow`).
- **Encrypted Credential Vault (`src/main/vaultStore.ts`)**: Uses Windows DPAPI via Electron `safeStorage` when available, with an AES-256-GCM device-scoped cipher fallback and strict path-traversal sanitization (`assertSafeChildPath`). Secrets are excluded from JSON exports and cloud sync.
- **Full Study Parity**: Bilingual EN | عربي (`dir="rtl"|"ltr"`), textbook import (`.txt`, `.md`, readable `.pdf` text), BM25 full-book passage retrieval with page citations, Exam Engine (all 20 question types, calculation tolerance, keyword rubric + optional AI grading), SM-2 flashcards, notes, and offline sync queue.

## Local Build & Verification

```bash
cd windows
npm install
npm run typecheck
npm test
```

## Windows Packaging (Run on Windows Host or `windows-latest` CI)

```bash
npm run package:win   # Generates NSIS .exe & .msi under windows/release/
```
