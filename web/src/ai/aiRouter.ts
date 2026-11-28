import type { AiProviderConfig } from '../types/sora';

export interface AiTurnResult {
  text: string;
  provider: 'OLLAMA' | 'GEMINI';
  model: string;
}

export interface EndpointValidationResult {
  valid: boolean;
  normalized: string;
  isLocal: boolean;
  error?: string;
}

export function redactSecrets(input: string, extraSecrets: string[] = []): string {
  let out = input
    .replace(/AIza[0-9A-Za-z\-_]{16,}/g, '[REDACTED_API_KEY]')
    .replace(/([?&](?:key|api_key|apikey|token|access_token)=)[^&\s]+/gi, '$1[REDACTED]')
    .replace(/(Bearer\s+)[A-Za-z0-9._\-]+/gi, '$1[REDACTED]')
    .replace(/(x-goog-api-key\s*[:=]\s*)\S+/gi, '$1[REDACTED]');
  for (const s of extraSecrets) {
    const clean = s.trim();
    if (clean.length >= 6) {
      out = out.split(clean).join('[REDACTED]');
    }
  }
  return out;
}

function parseIpv4(host: string): number[] | null {
  const parts = host.split('.');
  if (parts.length !== 4) return null;
  const nums = parts.map((p) => (/^\d{1,3}$/.test(p) ? Number(p) : -1));
  if (nums.some((n) => n < 0 || n > 255)) return null;
  return nums;
}

function extractMappedIpv4(ipv6Lower: string): string | null {
  if (ipv6Lower.startsWith('::ffff:')) {
    const tail = ipv6Lower.slice(7);
    if (parseIpv4(tail)) return tail;
    const hexParts = tail.split(':');
    if (hexParts.length === 2 && /^[0-9a-f]{1,4}$/.test(hexParts[0]) && /^[0-9a-f]{1,4}$/.test(hexParts[1])) {
      const hi = parseInt(hexParts[0], 16);
      const lo = parseInt(hexParts[1], 16);
      return `${(hi >> 8) & 0xff}.${hi & 0xff}.${(lo >> 8) & 0xff}.${lo & 0xff}`;
    }
  }
  return null;
}

function classifyHost(rawHost: string): { blockedReason?: string; isLocal: boolean } {
  const h = rawHost.toLowerCase().replace(/^\[|\]$/g, '');
  if (!h) return { blockedReason: 'Endpoint host is required', isLocal: false };

  if (
    h === 'metadata' ||
    h === 'metadata.google.internal' ||
    h.endsWith('.metadata.google.internal') ||
    h === 'instance-data' ||
    h === '0.0.0.0' ||
    h === '::' ||
    h === '0:0:0:0:0:0:0:0'
  ) {
    return { blockedReason: `Blocked unsafe or metadata host: ${h}`, isLocal: false };
  }

  const mappedIpv4 = extractMappedIpv4(h);
  if (mappedIpv4) {
    return classifyHost(mappedIpv4);
  }

  if (h.startsWith('fe80:') || h.startsWith('fe8') || h.startsWith('fe9') || h.startsWith('fea') || h.startsWith('feb')) {
    return { blockedReason: 'Link-local IPv6 addresses are blocked for security', isLocal: false };
  }
  if (h.startsWith('fc') || h.startsWith('fd')) {
    return { blockedReason: 'Unique-local IPv6 addresses are blocked', isLocal: false };
  }

  const ipv4 = parseIpv4(h);
  if (ipv4) {
    const [a, b] = ipv4;
    if (a === 0) return { blockedReason: 'Unspecified 0.0.0.0/8 addresses are blocked', isLocal: false };
    if (a === 169 && b === 254) {
      return { blockedReason: 'Cloud metadata / link-local 169.254.0.0/16 addresses are blocked', isLocal: false };
    }
    const isLocal = a === 127 || a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31);
    return { isLocal };
  }

  const isLocalName = h === 'localhost' || h === '::1' || h.endsWith('.local');
  return { isLocal: isLocalName };
}

export function validateOllamaEndpoint(raw: string): EndpointValidationResult {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) return { valid: false, normalized: '', isLocal: false, error: 'Endpoint URL is required' };
  if (/[\u0000-\u001F\s]/.test(trimmed)) {
    return { valid: false, normalized: '', isLocal: false, error: 'URL contains control characters or spaces' };
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { valid: false, normalized: '', isLocal: false, error: 'Only http:// and https:// URLs are supported' };
    }
    if (parsed.username || parsed.password) {
      return { valid: false, normalized: '', isLocal: false, error: 'Embedded URL credentials are not permitted' };
    }
    if (parsed.search || parsed.hash) {
      return { valid: false, normalized: '', isLocal: false, error: 'Query parameters and fragments are not permitted' };
    }
    const hostInfo = classifyHost(parsed.hostname);
    if (hostInfo.blockedReason) {
      return { valid: false, normalized: '', isLocal: false, error: hostInfo.blockedReason };
    }
    if (parsed.protocol === 'http:' && !hostInfo.isLocal) {
      return {
        valid: false,
        normalized: '',
        isLocal: false,
        error: 'Cleartext HTTP is restricted to local/private hosts; use HTTPS for remote Ollama endpoints.'
      };
    }
    return {
      valid: true,
      normalized: parsed.origin + (parsed.pathname === '/' ? '' : parsed.pathname),
      isLocal: hostInfo.isLocal
    };
  } catch {
    return { valid: false, normalized: '', isLocal: false, error: 'Invalid Ollama endpoint URL' };
  }
}

export async function testOllamaConnection(
  config: AiProviderConfig,
  fetchImpl: typeof fetch = fetch
): Promise<{ ok: boolean; models: string[]; message: string }> {
  const check = validateOllamaEndpoint(config.ollamaEndpoint);
  if (!check.valid) return { ok: false, models: [], message: check.error ?? 'Invalid Ollama endpoint' };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(config.timeoutMs || 15_000, 15_000));
  try {
    const res = await fetchImpl(`${check.normalized}/api/tags`, {
      method: 'GET',
      redirect: 'error',
      signal: controller.signal
    });
    if (!res.ok) return { ok: false, models: [], message: `Ollama HTTP ${res.status}` };
    const data = (await res.json()) as { models?: Array<{ name?: string }> };
    const models = (data.models ?? []).map((m) => m.name?.trim() ?? '').filter(Boolean);
    return {
      ok: true,
      models,
      message: models.length > 0 ? `Connected (${models.length} local model(s))` : 'Connected, no models pulled yet'
    };
  } catch (err) {
    return {
      ok: false,
      models: [],
      message: redactSecrets(err instanceof Error ? err.message : 'Ollama unreachable')
    };
  } finally {
    clearTimeout(timer);
  }
}

export async function testGeminiConnection(
  modelName: string,
  geminiApiKey: string,
  timeoutMs = 15_000,
  localOnlyPrivacyMode = false,
  fetchImpl: typeof fetch = fetch
): Promise<{ ok: boolean; message: string }> {
  if (localOnlyPrivacyMode) {
    return { ok: false, message: 'Blocked by Local-Only Privacy Mode.' };
  }
  const key = geminiApiKey.trim();
  if (!key) return { ok: false, message: 'Gemini API key is required.' };
  const model = modelName.trim() || 'gemini-3-flash-preview';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(timeoutMs, 20_000));
  try {
    const res = await fetchImpl(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      {
        method: 'POST',
        redirect: 'error',
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': key
        },
        signal: controller.signal,
        body: JSON.stringify({
          contents: [{ role: 'user', parts: [{ text: 'Reply OK' }] }]
        })
      }
    );
    if (!res.ok) {
      return { ok: false, message: `Gemini HTTP ${res.status}` };
    }
    return { ok: true, message: `Gemini connected (${model})` };
  } catch (err) {
    return { ok: false, message: redactSecrets(err instanceof Error ? err.message : 'Gemini test failed', [key]) };
  } finally {
    clearTimeout(timer);
  }
}

export async function generateWithRouter(
  prompt: string,
  systemPrompt: string,
  config: AiProviderConfig,
  fetchImpl: typeof fetch = fetch,
  options?: { localOnlyPrivacyMode?: boolean; runtimeGeminiApiKey?: string }
): Promise<AiTurnResult> {
  const privacyMode = Boolean(options?.localOnlyPrivacyMode);
  const resolvedGeminiKey = (options?.runtimeGeminiApiKey ?? config.geminiApiKey ?? '').trim();

  const rawOrder: Array<'OLLAMA' | 'GEMINI'> = (() => {
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

  const order = privacyMode ? rawOrder.filter((p) => p === 'OLLAMA') : rawOrder;
  if (order.length === 0) {
    throw new Error('Local-Only Privacy Mode blocks cloud Gemini requests. Select an Ollama strategy.');
  }

  let lastErr = 'No configured AI provider succeeded';
  for (const provider of order) {
    try {
      if (provider === 'OLLAMA') {
        const check = validateOllamaEndpoint(config.ollamaEndpoint);
        if (!check.valid) throw new Error(check.error);
        if (privacyMode && !check.isLocal) {
          throw new Error('Local-Only Privacy Mode requires a local/private Ollama endpoint.');
        }
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), config.timeoutMs);
        try {
          const res = await fetchImpl(`${check.normalized}/api/chat`, {
            method: 'POST',
            redirect: 'error',
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
        if (!resolvedGeminiKey) throw new Error('Gemini API key not configured');
        const model = config.geminiModel || 'gemini-3-flash-preview';
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), config.timeoutMs);
        try {
          const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
          const res = await fetchImpl(url, {
            method: 'POST',
            redirect: 'error',
            headers: {
              'Content-Type': 'application/json',
              'x-goog-api-key': resolvedGeminiKey
            },
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
      lastErr = redactSecrets(err instanceof Error ? err.message : 'Provider call failed', [resolvedGeminiKey]);
    }
  }
  throw new Error(lastErr);
}
