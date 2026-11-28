export type AppLanguage = 'en' | 'ar';

export type AiStrategy = 'AUTO' | 'OLLAMA_FIRST' | 'GEMINI_FIRST' | 'OLLAMA_ONLY' | 'GEMINI_ONLY';

export type QuestionType =
  | 'MCQ'
  | 'TRUE_FALSE'
  | 'FILL_BLANK'
  | 'MATCHING'
  | 'ORDERING'
  | 'SHORT_ANSWER'
  | 'LONG_ANSWER'
  | 'ESSAY'
  | 'DEFINITION'
  | 'EXPLAIN_JUSTIFY'
  | 'COMPARE_CONTRAST'
  | 'TERMINOLOGY'
  | 'CALCULATION'
  | 'WORD_PROBLEM'
  | 'CASE_STUDY'
  | 'APPLICATION'
  | 'COMPREHENSION'
  | 'DIAGRAM_ID'
  | 'TABLE_INTERP'
  | 'FORMULA_BASED';

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
  summary: string;
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
  overview: string;
  formulaSheet: string;
  chapters: Chapter[];
  pages: BookPage[];
}

export interface QuestionItem {
  id: string;
  bookId: string;
  chapterNumber: number;
  sourcePage: number;
  questionType: QuestionType;
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

export interface ExamRecord {
  id: string;
  title: string;
  bookId: string;
  questionIds: string[];
  answersByQuestionId: Record<string, string>;
  flaggedQuestionIds: string[];
  evaluationsByQuestionId: Record<string, AnswerEvaluation>;
  score: number;
  totalMarks: number;
  percentage: number;
  createdAtMillis: number;
  status: 'IN_PROGRESS' | 'SUBMITTED';
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
  chapterNumber: number;
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
  geminiApiKey: string;
  timeoutMs: number;
}

export interface UserSettings {
  language: AppLanguage;
  localOnlyPrivacyMode: boolean;
  aiConfig: AiProviderConfig;
  updatedAtMillis: number;
}

export type SyncStatus = 'IDLE' | 'SYNCING' | 'SYNCED' | 'OFFLINE_QUEUED' | 'CONFLICT_RESOLVED' | 'ERROR';

export interface SyncOperation {
  id: string;
  opType: 'UPSERT_BOOK' | 'UPSERT_NOTE' | 'SUBMIT_EXAM' | 'SYNC_PREFS';
  entityId: string;
  payloadJson: string;
  createdAtMillis: number;
  attempts: number;
  lastError: string;
}

export interface SyncState {
  status: SyncStatus;
  pendingCount: number;
  conflictsResolved: number;
  lastSyncedAtMillis: number;
  lastError: string;
  localOnlyMode: boolean;
}

export interface SoraWorkspaceState {
  books: Book[];
  questions: QuestionItem[];
  flashcards: Flashcard[];
  exams: ExamRecord[];
  notes: StudyNote[];
  settings: UserSettings;
}
