import type { AiStrategy, DesktopWorkspaceState, QuestionItem } from '../shared/models.js';

export const ALLOWED_SECRET_KEYS = new Set([
  'gemini_api_key',
  'firebase_refresh_token',
  'firebase_id_token'
]);

const VALID_STRATEGIES = new Set<AiStrategy>([
  'AUTO',
  'OLLAMA_FIRST',
  'GEMINI_FIRST',
  'OLLAMA_ONLY',
  'GEMINI_ONLY'
]);

export function validateSecretKeyName(keyName: unknown): string {
  if (typeof keyName !== 'string' || !ALLOWED_SECRET_KEYS.has(keyName)) {
    throw new Error(`Unauthorized secret key name: ${String(keyName)}`);
  }
  return keyName;
}

export function validateSecretValue(val: unknown): string {
  if (typeof val !== 'string') throw new Error('Secret value must be a string');
  if (val.length > 8192) throw new Error('Secret value exceeds maximum allowed length');
  return val.trim();
}

export function validateWorkspaceStateInput(input: unknown): DesktopWorkspaceState {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new Error('Invalid workspace state object');
  }
  const raw = input as Record<string, unknown>;
  const language = raw.language === 'ar' ? 'ar' : raw.language === 'en' ? 'en' : null;
  if (!language) throw new Error('Invalid language value');

  const privacy = typeof raw.localOnlyPrivacyMode === 'boolean' ? raw.localOnlyPrivacyMode : false;
  const rawAi = (raw.aiConfig && typeof raw.aiConfig === 'object' ? raw.aiConfig : {}) as Record<string, unknown>;
  const strategy = typeof rawAi.strategy === 'string' && VALID_STRATEGIES.has(rawAi.strategy as AiStrategy)
    ? (rawAi.strategy as AiStrategy)
    : 'AUTO';

  const books = Array.isArray(raw.books) ? raw.books.slice(0, 200) : null;
  const questions = Array.isArray(raw.questions) ? raw.questions.slice(0, 5000) : null;
  const flashcards = Array.isArray(raw.flashcards) ? raw.flashcards.slice(0, 5000) : null;
  const notes = Array.isArray(raw.notes) ? raw.notes.slice(0, 2000) : null;

  if (!books || !questions || !flashcards || !notes) {
    throw new Error('Workspace collections must be arrays');
  }

  return {
    language,
    localOnlyPrivacyMode: privacy,
    aiConfig: {
      strategy,
      fallbackEnabled: typeof rawAi.fallbackEnabled === 'boolean' ? rawAi.fallbackEnabled : true,
      ollamaEndpoint: typeof rawAi.ollamaEndpoint === 'string' ? rawAi.ollamaEndpoint.slice(0, 512) : 'http://127.0.0.1:11434',
      ollamaModel: typeof rawAi.ollamaModel === 'string' ? rawAi.ollamaModel.slice(0, 128) : 'llama3.2:3b',
      geminiModel: typeof rawAi.geminiModel === 'string' ? rawAi.geminiModel.slice(0, 128) : 'gemini-3-flash-preview',
      timeoutMs: Math.max(5000, Math.min(180_000, Number(rawAi.timeoutMs ?? 30_000) || 30_000))
    },
    books: books as DesktopWorkspaceState['books'],
    questions: questions as DesktopWorkspaceState['questions'],
    flashcards: flashcards as DesktopWorkspaceState['flashcards'],
    notes: notes as DesktopWorkspaceState['notes']
  };
}

export function validateAskBookArgs(bookId: unknown, query: unknown): { bookId: string; query: string } {
  if (typeof bookId !== 'string' || !bookId.trim() || bookId.length > 160) {
    throw new Error('Invalid bookId');
  }
  if (typeof query !== 'string' || !query.trim() || query.length > 4000) {
    throw new Error('Invalid query');
  }
  return { bookId: bookId.trim(), query: query.trim() };
}

export function validateEvaluateAnswerArgs(question: unknown, answer: unknown): { question: QuestionItem; answer: string } {
  if (!question || typeof question !== 'object' || Array.isArray(question)) {
    throw new Error('Invalid question payload');
  }
  const q = question as Record<string, unknown>;
  if (typeof q.id !== 'string' || typeof q.questionType !== 'string' || typeof q.correctAnswer !== 'string') {
    throw new Error('Malformed question fields');
  }
  if (typeof answer !== 'string' || answer.length > 16_000) {
    throw new Error('Invalid answer payload');
  }
  return { question: question as QuestionItem, answer };
}

export function validateEntityId(id: unknown): string {
  if (typeof id !== 'string' || !id.trim() || id.length > 160) {
    throw new Error('Invalid entity ID');
  }
  return id.trim();
}
