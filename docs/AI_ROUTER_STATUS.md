# AI Router implementation status

## Implemented in this branch
- Provider-neutral text request/response contracts.
- Typed provider failure classification.
- Router that falls back only on transient provider errors.
- Gemini text adapter with timeout and basic error classification.
- Ollama non-streaming text adapter with explicit endpoint/model configuration.
- Optional Ollama fallback can now be configured at Android build time using `OLLAMA_BASE_URL` and `OLLAMA_MODEL` Gradle properties or environment variables. The endpoint is not a secret and is not hardcoded.
- Gemini remains primary; Ollama is attempted only after a transient Gemini failure and only when configured.
- Three plain-text features use the router: Reading Assistant, Equation Solver, and Chapter Comparison/Gap Analysis.
- Structured book analysis, chapter intelligence, question generation, flashcards, and teach-back retain Gemini schema-based primary generation and can fall back to configured Ollama JSON mode on transient failures or malformed primary JSON; fallback JSON is validated and repaired once.
- Non-image tutor streaming can fall back to a configured Ollama text request when Gemini fails before emitting content. It does not switch providers after partial output.
- OCR page-image requests and image-context tutor requests can fall back to configured Ollama vision models; the configured model must support images. Tutor fallback is only before any streamed content.
- Unit tests for fallback, timeout, cancellation, non-fallback failures, all-provider outage, endpoint validation, and Ollama HTTP request/response/error handling using MockWebServer.
- OkHttp dependency enabled for the Ollama adapter.

## Deferred / limitations
- No secured remote AI gateway/server has been deployed. This adapter does not support gateway auth headers, so a public Ollama endpoint is not a safe/usable fallback; use local/private-network endpoints or wait for an authenticated gateway.
- Fallback support for structured JSON is implemented, but the Ollama path does not enforce the Firebase response schema itself; application JSON parsing/validation and one repair attempt are used.
- Image-context tutor fallback is implemented through Ollama Vision if Gemini fails before emitting any output; no provider switch occurs after partial output.
- Runtime provider settings UI is not implemented; fallback configuration is build-time only.
- CI failure was traced to `android-actions/setup-android` attempting to install the obsolete SDK package `tools`. The workflow no longer uses `android-actions/setup-android` (which attempted to install obsolete `tools`); it uses the runner's SDK directly, installs only the required Android 36 platform/build-tools/platform-tools packages, and is manual-trigger-only to prevent notification spam. A successful run has not yet been confirmed.

## Build-time fallback configuration
- Set `OLLAMA_BASE_URL` to a trusted Ollama endpoint.
- Set `OLLAMA_MODEL` to an installed text model; defaults to `qwen2.5:7b`.
- Set `OLLAMA_VISION_MODEL` to an installed image-capable model to enable OCR fallback; blank disables OCR fallback.
- You can provide Gradle properties (`-POLLAMA_BASE_URL=...`, `-POLLAMA_MODEL=...`, `-POLLAMA_VISION_MODEL=...`) or environment variables.
- Leave the URL blank for Gemini-only behavior.
- Do not put cloud API keys or credentials in these values.

## Security and reliability
- Never ship cloud API keys, gateway secrets, or Ollama Cloud credentials in the APK.
- Ollama supports text and image-input requests through this adapter. A remote provider endpoint must be trusted and protected; plain HTTP is restricted to localhost, loopback, RFC1918 and link-local IPv4, and private/loopback/link-local IPv6 literals; HTTPS is required for public hosts. Android cleartext is enabled only when the configured Ollama endpoint begins with `http://`. This adapter has no auth-header support, so do not use an exposed public Ollama endpoint; only use endpoints intentionally configured for direct client access.
- This router does not guarantee availability if providers are offline, out of quota, or unreachable.
- Ollama JSON mode does not enforce the full Firebase schema; application-level validation is required.
