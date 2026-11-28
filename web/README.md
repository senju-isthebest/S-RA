# SŌRA Web & PWA (`/web`)

Standalone responsive Vite + React + TypeScript Progressive Web App companion to SŌRA Android.

## Features
- **Offline-First Storage & Queue**: Browser persistence with deterministic `ConflictResolver`, persistent offline queue, Local-Only Privacy Mode, and non-secret JSON workspace export.
- **Full-Book Intelligence & Retrieval**: Client textbook upload (`.txt`, `.md`, readable `.pdf` text layer) split into chapters and indexed with bilingual English + Arabic BM25 passage retrieval (`retrievePassages`) + clickable citation page jumps.
- **Exam Engine**: Supports MCQ, True/False (EN/AR), Fill-in-the-Blank variants, Calculation tolerance (±1.5% + fractions), Matching, and open-ended rubric + optional AI semantic grading, plus chapter/type filters and Retake Missed.
- **AI Provider Router**: Supports Local Ollama (`/api/chat`) and Cloud Gemini API with endpoint safety validation and provider fallback. Keys entered in Settings stay strictly in browser session/local storage and are never pushed to Firebase cloud sync.
- **Bilingual EN | عربي**: Instant `dir="rtl"` / `dir="ltr"` layout switching.

## Development & Verification

```bash
cd web
npm install
npm test        # Vitest unit suite
npm run build   # TypeScript check + production Vite bundle in dist/
```
