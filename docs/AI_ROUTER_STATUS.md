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
- Unit tests for fallback, non-fallback failures, and all-provider outage.
- OkHttp dependency enabled for the Ollama adapter.

## Not integrated yet
- No secured remote AI gateway/server has been deployed. A shared remote Ollama endpoint must not be exposed without TLS, authentication, quotas, and abuse protection.
- Structured generation (book structure, chapter intelligence, question generation, flashcards, teach-back) still uses Firebase AI Logic response schemas.
- OCR/image generation and tutor streaming still use Firebase AI Logic.
- Runtime provider settings UI is not implemented; fallback configuration is build-time only.
- Build/test execution has not been performed in this environment. The branch is not yet verified to compile.

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
