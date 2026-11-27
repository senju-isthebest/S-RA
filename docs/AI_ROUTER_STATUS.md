# AI Router implementation status

## Implemented in this branch
- Provider-neutral text request/response contracts.
- Typed provider failure classification.
- Router that falls back only on transient provider errors.
- Ollama non-streaming text adapter with explicit endpoint/model configuration.
- Unit tests for fallback, non-fallback failures, and all-provider outage.

## Not integrated yet
- Existing SoraAiService structured generation, image OCR, and streaming still use Firebase AI Logic.
- Gemini provider adapter is not yet wrapped by the router.
- App settings and secure server-side provider configuration are not implemented.
- Ollama endpoint is not bundled or hosted; users/devices need a reachable secured Ollama service.
- Build/test execution has not been performed in this environment.

## Important safety and reliability notes
- The Android app must not contain third-party cloud API secrets.
- Public Ollama instances need access control, TLS, rate limiting, and abuse protection.
- This first router is text-only; image and schema-constrained requests need provider capability negotiation and separate adapters.
- Fallback does not guarantee service availability if all providers are offline, out of quota, or unreachable.
