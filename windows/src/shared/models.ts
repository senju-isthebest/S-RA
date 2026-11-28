export type AppLanguage = 'en' | 'ar';
export type AiStrategy = 'AUTO' | 'OLLAMA_FIRST' | 'GEMINI_FIRST' | 'OLLAMA_ONLY' | 'GEMINI_ONLY';

export interface BookPage {
  pageNumber: number;
  text: string;
}

export interface Chapter {
  id: string;
  chapterNumber: number;
  title: string;
  startPage: number;
  endPage: number;
  summary?: string;
}

export interface Book {
  id: string;
  title: string;
  subjectName: string;
  totalPages: number;
  currentPage: number;
  lastUpdatedMillis: number;
  bookmarkedPages: number[];
  highlightsByPage: Record<number, string[]>;
  chapters: Chapter[];
  pages: BookPage[];
}

export interface QuestionItem {
  id: string;
  bookId: string;
  chapterNumber: number;
  sourcePage: number;
  questionType: string;
  difficulty: 'EASY' | 'MEDIUM' | 'HARD';
  marks: number;
  questionText: string;
  options: string[];
  correctAnswer: string;
  explanation: string;
}

export interface AnswerEvaluation {
  isCorrect: boolean;
  scoreRatio: number;
  awardedMarks: number;
  feedback: string;
  gradedByAi: boolean;
}

export interface Flashcard {
  id: string;
  bookId: string;
  chapterNumber: number;
  front: string;
  back: string;
  easeFactor: number;
  intervalDays: number;
  repetitions: number;
  nextReviewAtMillis: number;
}

export interface StudyNote {
  id: string;
  bookId: string;
  linkedPage: number;
  title: string;
  contentMarkdown: string;
  updatedAtMillis: number;
}

export interface RetrievedPassage {
  chapterNumber: number;
  pageNumber: number;
  score: number;
  excerpt: string;
}

export interface AiProviderConfig {
  strategy: AiStrategy;
  fallbackEnabled: boolean;
  ollamaEndpoint: string;
  ollamaModel: string;
  geminiModel: string;
  timeoutMs: number;
}

export interface SyncOperation {
  id: string;
  opType:
    | 'UPSERT_BOOK'
    | 'SYNC_BOOK_PAGES'
    | 'DELETE_BOOK'
    | 'UPSERT_NOTE'
    | 'DELETE_NOTE'
    | 'SUBMIT_EXAM'
    | 'SYNC_PREFS';
  entityId: string;
  payloadJson: string;
  createdAtMillis: number;
  attempts: number;
  lastError: string;
}

export interface SyncState {
  status: 'IDLE' | 'SYNCING' | 'SYNCED' | 'OFFLINE_QUEUED' | 'CONFLICT_RESOLVED' | 'ERROR';
  pendingCount: number;
  deadLetterCount?: number;
  conflictsResolved: number;
  lastSyncedAtMillis: number;
  lastError: string;
  localOnlyMode: boolean;
}

export interface AuthSession {
  uid: string;
  email: string;
  displayName: string;
  idToken: string;
  refreshToken: string;
  expiresAtMillis: number;
}

export interface FirebaseDesktopConfig {
  apiKey: string;
  projectId: string;
  databaseId: string;
}

export interface DesktopWorkspaceState {
  language: AppLanguage;
  localOnlyPrivacyMode: boolean;
  aiConfig: AiProviderConfig;
  books: Book[];
  questions: QuestionItem[];
  flashcards: Flashcard[];
  notes: StudyNote[];
}
