import type { AiProviderConfig } from '../types/sora';

export interface AiTurnResult {
  text: string;
  provider: 'OLLAMA' | 'GEMINI';
  model: string;
}

export function validateOllamaEndpoint(raw: string): { valid: boolean; normalized: string; error?: string } {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) return { valid: false, normalized: '', error: 'Endpoint URL is required' };
  if (/[\u0000-\u001F\s]/.test(trimmed)) {
    return { valid: false, normalized: '', error: 'URL contains control characters or spaces' };
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { valid: false, normalized: '', error: 'Only http:// and https:// URLs are supported' };
    }
    if (parsed.username || parsed.password) {
      return { valid: false, normalized: '', error: 'Embedded URL credentials are not permitted' };
    }
    return { valid: true, normalized: parsed.origin + (parsed.pathname === '/' ? '' : parsed.pathname) };
  } catch {
    return { valid: false, normalized: '', error: 'Invalid Ollama endpoint URL' };
  }
}

export async function generateWithRouter(
  prompt: string,
  systemPrompt: string,
  config: AiProviderConfig,
  fetchImpl: typeof fetch = fetch
): Promise<AiTurnResult> {
  const order: Array<'OLLAMA' | 'GEMINI'> = (() => {
    switch (config.strategy) {
      case 'OLLAMA_ONLY':
        return ['OLLAMA'];
      case 'GEMINI_ONLY':
        return ['GEMINI'];
      case 'GEMINI_FIRST':
        return config.fallbackEnabled ? ['GEMINI', 'OLLAMA'] : ['GEMINI'];
      case 'OLLAMA_FIRST':
      case 'AUTO':
      default:
        return config.fallbackEnabled ? ['OLLAMA', 'GEMINI'] : ['OLLAMA'];
    }
  })();

  let lastErr = 'No configured AI provider succeeded';
  for (const provider of order) {
    try {
      if (provider === 'OLLAMA') {
        const check = validateOllamaEndpoint(config.ollamaEndpoint);
        if (!check.valid) throw new Error(check.error);
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), config.timeoutMs);
        try {
          const res = await fetchImpl(`${check.normalized}/api/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            signal: controller.signal,
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
          const text = json.message?.content?.trim() ?? '';
          if (!text) throw new Error('Empty Ollama response');
          return { text, provider: 'OLLAMA', model: config.ollamaModel };
        } finally {
          clearTimeout(timer);
        }
      } else {
        const key = config.geminiApiKey.trim();
        if (!key) throw new Error('Gemini API key not configured');
        const model = config.geminiModel || 'gemini-3-flash-preview';
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), config.timeoutMs);
        try {
          const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`;
          const res = await fetchImpl(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            signal: controller.signal,
            body: JSON.stringify({
              systemInstruction: { parts: [{ text: systemPrompt }] },
              contents: [{ role: 'user', parts: [{ text: prompt }] }]
            })
          });
          if (!res.ok) throw new Error(`Gemini HTTP ${res.status}`);
          const json = (await res.json()) as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> };
          const text = json.candidates?.[0]?.content?.parts?.[0]?.text?.trim() ?? '';
          if (!text) throw new Error('Empty Gemini response');
          return { text, provider: 'GEMINI', model };
        } finally {
          clearTimeout(timer);
        }
      }
    } catch (err) {
      lastErr = err instanceof Error ? err.message : 'Provider call failed';
    }
  }
  throw new Error(lastErr);
}
