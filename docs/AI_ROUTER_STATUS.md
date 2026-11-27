# AI Router implementation status

## Implemented in this branch
- Provider-neutral text request/response contracts.
- Typed provider failure classification.
- Router that falls back only on transient provider errors.
- Gemini text adapter with timeout and basic error classification.
- Ollama non-streaming text adapter with explicit endpoint/model configuration.
- Three plain-text SŌRA features now use the router: Reading Assistant, Equation Solver, and Chapter Comparison/Gap Analysis.
- Unit tests for fallback, non-fallback failures, and all-provider outage.
- OkHttp dependency enabled for the Ollama adapter.

## Not integrated yet
- Router currently has Gemini as its only provider when instantiated by SoraAiService. Fallback infrastructure is tested, but production fallback is not active yet.
- Existing structured generation (book structure, chapter intelligence, question generation, flashcards, teach-back) still uses Firebase AI Logic response schemas.
- OCR/image generation and Tutor streaming still use Firebase AI Logic.
- App settings for configuring a trusted Ollama endpoint/model are not implemented.
- No secured remote AI gateway/server has been deployed.
- Build/test execution has not been performed in this environment.

## Important security and reliability notes
- The Android app must not contain third-party cloud API secrets.
- Public Ollama instances need access control, TLS, rate limiting, and abuse protection. Never expose a raw Ollama server publicly.
- This first router is text-only; image and schema-constrained requests need provider capability negotiation and separate adapters.
- Fallback does not guarantee service availability if all providers are offline, out of quota, or unreachable.
