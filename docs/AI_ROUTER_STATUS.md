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
- OCR page-image requests can fall back to configured Ollama vision models; the configured model must support images.
- Unit tests for fallback, timeout, cancellation, non-fallback failures, all-provider outage, endpoint validation, and Ollama HTTP request/response/error handling using MockWebServer.
- OkHttp dependency enabled for the Ollama adapter.

## Not integrated yet
- No secured remote AI gateway/server has been deployed. A shared remote Ollama endpoint must not be exposed without TLS, authentication, quotas, and abuse protection.
- Fallback support for structured JSON is implemented, but the Ollama path does not enforce the Firebase response schema itself; application JSON parsing/validation and one repair attempt are used.
- Tutor requests that include a page image remain Gemini-only; image-based tutor fallback is not implemented.
- Runtime provider settings UI is not implemented; fallback configuration is build-time only.
- A CI attempt failed during Android SDK setup because the workflow requested the obsolete SDK package `tools`; the workflow configuration was corrected and expanded tests were added. A successful rerun has not yet been confirmed, so the branch remains unverified to compile.

## Build-time fallback configuration
- Set `OLLAMA_BASE_URL` to a trusted Ollama endpoint.
- Set `OLLAMA_MODEL` to an installed model name; defaults to `qwen2.5:7b`.
- You can provide Gradle properties (`-POLLAMA_BASE_URL=...`, `-POLLAMA_MODEL=...`) or environment variables.
- Leave the URL blank for Gemini-only behavior.
- Do not put cloud API keys or credentials in these values.

## Security and reliability
- Never ship cloud API keys, gateway secrets, or Ollama Cloud credentials in the APK.
- The app currently supports a text-only local/remote Ollama adapter. A remote provider endpoint must be trusted and protected; plain HTTP is not suitable for public internet use.
- This router does not guarantee availability if providers are offline, out of quota, or unreachable.
- Image and schema-constrained requests need capability negotiation and separate adapters before they can safely use fallback.
