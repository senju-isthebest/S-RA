# SŌRA AI Provider Router

## Goal
Keep AI features resilient when one provider is unavailable or rate-limited, without exposing provider secrets in the Android APK.

## Current state
- Gemini is primary for existing Firebase AI Logic calls.
- Text routing is integrated for Reading Assistant, Equation Solver, and Chapter Comparison/Gap Analysis.
- Optional Ollama text fallback is build-configured and disabled unless an endpoint is supplied.
- Structured generation has JSON-mode fallback, validation, and one repair attempt.
- OCR image fallback uses a separately configured vision model.
- Non-image tutor streaming can fall back to Ollama if Gemini fails before emitting any content.
- Image-context tutor fallback uses the configured Ollama vision model if Gemini fails before emitting any streamed text.

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
5. Preserve JSON validation for structured outputs; reject invalid JSON and retry once with a repair prompt (implemented for Ollama structured fallback).
6. Cache successful generated resources by content/task/version to avoid duplicate generation (not implemented yet).

## Security requirements
- Never ship cloud API keys, gateway secrets, or Ollama Cloud credentials in the APK or repository.
- Gateway requests must validate Firebase Auth and App Check.
- Apply per-user quotas, request-size limits, concurrency controls, timeouts, and audit-safe error logging.
- Do not log book contents or prompts by default.
- Require HTTPS for remotely hosted provider endpoints. Plain HTTP is allowed only for loopback or private IPv4 addresses.
- Current Ollama adapter does not support authentication headers; do not use an exposed public endpoint. An authenticated gateway is a future server-side integration.

## Ollama note
Local Ollama is free to run but uses the host's hardware and must be reachable to the app. The endpoint can be configured with Gradle property/environment variable `OLLAMA_BASE_URL`; model via `OLLAMA_MODEL`. A public shared Ollama endpoint must be secured and rate-limited before use.

## Implementation stages
1. Provider contracts and typed failures. (implemented)
2. Gemini and Ollama text/vision adapter. (implemented)
3. Router and integration for text-only features. (implemented)
4. Build-time optional fallback configuration. (implemented)
5. Structured JSON fallback, OCR fallback, and tutor text/vision fallback before any partial stream. (implemented)
6. Structured-response validation and one repair attempt plus adapter/router tests. (implemented; retry/backoff and caching pending)
7. Build/test verification in a reproducible environment. (pending)
