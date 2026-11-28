import type { AiProviderConfig } from './models';

export function validateOllamaEndpoint(raw: string): { valid: boolean; normalized: string; error?: string } {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) return { valid: false, normalized: '', error: 'Endpoint URL is required' };
  if (/[\u0000-\u001F\s]/.test(trimmed)) return { valid: false, normalized: '', error: 'Invalid whitespace or control characters' };
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { valid: false, normalized: '', error: 'Only http:// and https:// endpoints allowed' };
    }
    if (parsed.username || parsed.password) {
      return { valid: false, normalized: '', error: 'Credentials inside URL are forbidden' };
    }
    return { valid: true, normalized: parsed.origin + (parsed.pathname === '/' ? '' : parsed.pathname) };
  } catch {
    return { valid: false, normalized: '', error: 'Malformed Ollama URL' };
  }
}

export async function routeAiRequest(
  prompt: string,
  systemPrompt: string,
  config: AiProviderConfig,
  geminiApiKey: string,
  fetchImpl: typeof fetch = fetch
): Promise<{ text: string; provider: 'OLLAMA' | 'GEMINI'; model: string }> {
  const order: Array<'OLLAMA' | 'GEMINI'> = (() => {
    switch (config.strategy) {
      case 'OLLAMA_ONLY':
        return ['OLLAMA'];
      case 'GEMINI_ONLY':
        return ['GEMINI'];
      case 'GEMINI_FIRST':
        return config.fallbackEnabled ? ['GEMINI', 'OLLAMA'] : ['GEMINI'];
      default:
        return config.fallbackEnabled ? ['OLLAMA', 'GEMINI'] : ['OLLAMA'];
    }
  })();

  let lastErr = 'All AI providers failed';
  for (const p of order) {
    try {
      if (p === 'OLLAMA') {
        const check = validateOllamaEndpoint(config.ollamaEndpoint);
        if (!check.valid) throw new Error(check.error);
        const res = await fetchImpl(`${check.normalized}/api/chat`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            model: config.ollamaModel || 'llama3.2:3b',
            stream: false,
            messages: [
              { role: 'system', content: systemPrompt },
              { role: 'user', content: prompt }
            ]
          })
        });
        if (!res.ok) throw new Error(`Ollama HTTP ${res.status}`);
        const json = (await res.json()) as { message?: { content?: string } };
        const txt = json.message?.content?.trim() ?? '';
        if (!txt) throw new Error('Empty Ollama response');
        return { text: txt, provider: 'OLLAMA', model: config.ollamaModel };
      } else {
        const key = geminiApiKey.trim();
        if (!key) throw new Error('Gemini API key not configured');
        const model = config.geminiModel || 'gemini-3-flash-preview';
        const res = await fetchImpl(
          `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              systemInstruction: { parts: [{ text: systemPrompt }] },
              contents: [{ role: 'user', parts: [{ text: prompt }] }]
            })
          }
        );
        if (!res.ok) throw new Error(`Gemini HTTP ${res.status}`);
        const json = (await res.json()) as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> };
        const txt = json.candidates?.[0]?.content?.parts?.[0]?.text?.trim() ?? '';
        if (!txt) throw new Error('Empty Gemini response');
        return { text: txt, provider: 'GEMINI', model };
      }
    } catch (err) {
      lastErr = err instanceof Error ? err.message : 'Provider call failed';
    }
  }
  throw new Error(lastErr);
}
