import type { Book, BookPage, Chapter, QuestionItem, Flashcard, RetrievedPassage } from './models.js';

const AR_DIACRITICS_RE = /[\u064B-\u065F\u0670\u0640]/g;
const STOPWORDS = new Set([
  'the', 'and', 'for', 'that', 'this', 'with', 'from', 'are', 'was', 'were', 'have', 'has', 'had',
  'في', 'من', 'على', 'الى', 'عن', 'مع', 'هذا', 'هذه', 'التي', 'الذي', 'ما', 'كيف', 'لماذا', 'كان'
]);

export function normalizeBilingual(raw: string): string {
  return raw
    .replace(AR_DIACRITICS_RE, '')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .toLowerCase();
}

export function tokenizeBilingual(raw: string): string[] {
  const matches = normalizeBilingual(raw).match(/[\p{L}\p{N}]+/gu) ?? [];
  return matches.filter((w) => w.length >= 2 && !STOPWORDS.has(w));
}

export function retrievePassages(
  query: string,
  pages: BookPage[],
  chapters: Chapter[],
  topK = 5,
  maxTotalChars = 6500
): RetrievedPassage[] {
  const qUnique = Array.from(new Set(tokenizeBilingual(query)));
  if (qUnique.length === 0 || pages.length === 0) return [];

  const chunks: Array<{ chapterNumber: number; pageNumber: number; excerpt: string; tf: Map<string, number>; len: number }> = [];
  for (const page of pages) {
    const ch = chapters.find((c) => page.pageNumber >= c.startPage && page.pageNumber <= c.endPage);
    const chNum = ch?.chapterNumber ?? 0;
    const text = page.text.trim();
    if (!text) continue;
    for (let i = 0; i < text.length; i += 780) {
      const excerpt = text.slice(i, i + 950).trim();
      const tokens = tokenizeBilingual(excerpt);
      if (tokens.length === 0) continue;
      const tf = new Map<string, number>();
      for (const tok of tokens) tf.set(tok, (tf.get(tok) ?? 0) + 1);
      chunks.push({ chapterNumber: chNum, pageNumber: page.pageNumber, excerpt, tf, len: tokens.length });
    }
  }
  if (chunks.length === 0) return [];

  const avgLen = chunks.reduce((acc, c) => acc + c.len, 0) / chunks.length;
  const k1 = 1.5;
  const b = 0.75;
  const normQuery = normalizeBilingual(query.trim());

  const scored: RetrievedPassage[] = [];
  for (const chunk of chunks) {
    let score = 0;
    for (const term of qUnique) {
      const f = chunk.tf.get(term) ?? 0;
      if (!f) continue;
      const df = chunks.filter((c) => c.tf.has(term)).length;
      const idf = Math.log(1 + (chunks.length - df + 0.5) / (df + 0.5));
      score += idf * ((f * (k1 + 1)) / (f + k1 * (1 - b + (b * chunk.len) / Math.max(1, avgLen))));
    }
    if (normQuery.length >= 4 && normalizeBilingual(chunk.excerpt).includes(normQuery)) score += 3.0;
    if (score > 0) scored.push({ chapterNumber: chunk.chapterNumber, pageNumber: chunk.pageNumber, score, excerpt: chunk.excerpt });
  }

  scored.sort((a, z) => z.score - a.score);
  const out: RetrievedPassage[] = [];
  let used = 0;
  for (const item of scored) {
    if (out.length >= topK) break;
    const rem = maxTotalChars - used;
    if (rem <= 80) break;
    const clip = item.excerpt.length <= rem ? item.excerpt : `${item.excerpt.slice(0, rem - 1)}…`;
    out.push({ ...item, excerpt: clip });
    used += clip.length;
  }
  return out;
}

export function buildDesktopBook(
  title: string,
  subjectName: string,
  rawText: string,
  explicitPages?: BookPage[]
): { book: Book; questions: QuestionItem[]; flashcards: Flashcard[] } {
  const pages: BookPage[] = [];
  if (explicitPages && explicitPages.length > 0) {
    for (const ep of explicitPages) {
      pages.push({ pageNumber: ep.pageNumber, text: ep.text.trim() || `[Page ${ep.pageNumber}]` });
    }
  } else {
    const paras = rawText.replace(/\r\n/g, '\n').split(/\n{2,}/).filter((p) => p.trim());
    let buf = '';
    let pNum = 1;
    for (const p of paras) {
      if (buf.length + p.length > 1400 && buf) {
        pages.push({ pageNumber: pNum++, text: buf.trim() });
        buf = p;
      } else {
        buf = buf ? `${buf}\n\n${p}` : p;
      }
    }
    if (buf.trim()) pages.push({ pageNumber: pNum, text: buf.trim() });
    if (pages.length === 0) pages.push({ pageNumber: 1, text: rawText || title });
  }

  const chapters: Chapter[] = [];
  const step = Math.max(1, Math.ceil(pages.length / Math.min(4, pages.length)));
  for (let i = 0; i < pages.length; i += step) {
    const chNum = chapters.length + 1;
    chapters.push({
      id: `ch_${chNum}`,
      chapterNumber: chNum,
      title: pages[i].text.split('\n')[0]?.trim().slice(0, 60) || `Chapter ${chNum}`,
      startPage: i + 1,
      endPage: Math.min(pages.length, i + step)
    });
  }
  const bookId = `book_${Date.now()}`;
  const book: Book = {
    id: bookId,
    title: title.trim() || 'Desktop Textbook',
    subjectName: subjectName.trim() || 'General',
    totalPages: pages.length,
    currentPage: 1,
    lastUpdatedMillis: Date.now(),
    bookmarkedPages: [],
    highlightsByPage: {},
    chapters,
    pages
  };
  const questions: QuestionItem[] = chapters.map((ch, idx) => {
    const keyLine = pages[ch.startPage - 1].text.split(/[.!\n]/).find((s) => s.trim().length > 18)?.trim() ?? ch.title;
    return {
      id: `${bookId}_q_${idx + 1}`,
      bookId,
      chapterNumber: ch.chapterNumber,
      sourcePage: ch.startPage,
      questionType: idx % 2 === 0 ? 'SHORT_ANSWER' : 'TRUE_FALSE',
      difficulty: 'MEDIUM',
      marks: 2,
      questionText: idx % 2 === 0 ? `Explain key concept from ${ch.title}` : `True or False: "${keyLine.slice(0, 80)}" belongs to Ch.${ch.chapterNumber}.`,
      options: idx % 2 === 0 ? [] : ['True', 'False'],
      correctAnswer: idx % 2 === 0 ? keyLine : 'True',
      explanation: `p.${ch.startPage}`
    };
  });
  const flashcards: Flashcard[] = chapters.map((ch, idx) => ({
    id: `${bookId}_fc_${idx + 1}`,
    bookId,
    chapterNumber: ch.chapterNumber,
    front: `Ch.${ch.chapterNumber}: ${ch.title}`,
    back: pages[ch.startPage - 1].text.slice(0, 180),
    easeFactor: 2.5,
    intervalDays: 1,
    repetitions: 0,
    nextReviewAtMillis: Date.now()
  }));
  return { book, questions, flashcards };
}
