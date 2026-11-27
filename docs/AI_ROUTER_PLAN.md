# SŌRA AI Provider Router

## Goal
Keep AI features resilient when one provider is unavailable or rate-limited, without exposing provider secrets in the Android APK.

## Current state
- Gemini is the primary provider for existing Firebase AI Logic calls.
- Text-only provider abstraction is integrated for Reading Assistant, Equation Solver, and Chapter Comparison/Gap Analysis.
- Optional text-only Ollama fallback is build-configured. It is disabled unless an endpoint is supplied.
- Structured outputs, image/OCR, and tutor streaming remain on Firebase AI Logic and do not currently use fallback.

## Target architecture
Android app -> authenticated AI Gateway -> provider adapters
- Primary: Firebase AI Logic / Gemini (existing client integration retained until gateway migration is validated).
- Secondary: a server-configured provider with credentials stored as server secrets.
- Optional local Ollama: developer/local testing only, unless a secured, reachable inference host is provisioned.

## Routing policy
1. Use the preferred provider.
2. Fall back only on rate-limit, service-unavailable, timeout, or network failures.
3. Do not fallback for malformed user input, blocked requests, authentication failures, or cancellations.
4. Return a clear temporary-unavailable state if all providers fail.
5. Preserve JSON validation for structured outputs; reject invalid JSON and retry once with a repair prompt (not implemented yet).
6. Cache successful generated resources by content/task/version to avoid duplicate generation (not implemented yet).

## Security requirements
- Never ship cloud API keys, gateway secrets, or Ollama Cloud credentials in the APK or repository.
- Gateway requests must validate Firebase Auth and App Check.
- Apply per-user quotas, request-size limits, concurrency controls, timeouts, and audit-safe error logging.
- Do not log book contents or prompts by default.
- Require HTTPS for remotely hosted provider endpoints.

## Ollama note
Local Ollama is free to run but uses the host's hardware and must be reachable to the app. The endpoint can be configured with Gradle property/environment variable `OLLAMA_BASE_URL`; model via `OLLAMA_MODEL`. A public shared Ollama endpoint must be secured and rate-limited before use.

## Implementation stages
1. Provider contracts and typed failures. (implemented)
2. Gemini and Ollama text adapters. (implemented)
3. Router and integration for text-only features. (implemented)
4. Build-time optional fallback configuration. (implemented)
5. Structured, image, and streaming provider capabilities. (pending)
6. Retry/backoff, structured-response validation, caching, and tests. (pending)
7. Build/test verification in a reproducible environment. (pending)
