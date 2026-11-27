# SŌRA AI Provider Router

## Goal
Keep AI features resilient when one provider is unavailable or rate-limited, without exposing provider secrets in the Android APK.

## Current state
- Android calls Firebase AI Logic directly from `SoraAiService.kt`.
- Gemini is used by all generation methods, including structured outputs and tutor streaming.
- There is no server-side AI gateway in the repository yet.
- A provider fallback cannot safely be implemented by putting cloud API keys in the client app.

## Target architecture
Android app -> authenticated AI Gateway -> provider adapters
- Primary: Firebase AI Logic / Gemini (existing client integration retained until gateway migration is validated).
- Secondary: a server-configured provider with credentials stored as server secrets.
- Optional local Ollama: developer/local testing only, unless a secured, reachable inference host is provisioned.

## Routing policy
1. Use the preferred provider.
2. Retry only transient failures with bounded exponential backoff.
3. Fall back for rate-limit, service-unavailable, timeout, or provider/network failures.
4. Do not fallback for malformed user input, blocked requests, or cancellations.
5. Return a clear temporary-unavailable state if all providers fail.
6. Cache successful generated resources by content/task/version to avoid duplicate generation.
7. Preserve JSON validation for structured outputs; reject invalid JSON and retry once with a repair prompt.

## Security requirements
- Never ship cloud API keys, gateway secrets, or Ollama Cloud credentials in the APK or repository.
- Gateway requests must validate Firebase Auth and App Check.
- Apply per-user quotas, request-size limits, concurrency controls, timeouts, and audit-safe error logging.
- Do not log book contents or prompts by default.
- Require HTTPS for remotely hosted provider endpoints.

## Ollama note
Local Ollama is free to run but uses the host's hardware and must be reachable to the app. Ollama Cloud API requires credentials and is not an unlimited free fallback. A public shared Ollama endpoint must be secured and rate-limited before use.

## Implementation stages
1. Add server-side gateway scaffold and provider contracts.
2. Add provider adapters and secrets configuration.
3. Add client routing integration without breaking existing Firebase AI Logic.
4. Add retries, fallback, structured-response validation, caching, and tests.
5. Verify in a test environment before switching production traffic.
