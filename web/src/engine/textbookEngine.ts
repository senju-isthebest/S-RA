import type {
  AnswerEvaluation,
  Book,
  BookPage,
  Chapter,
  Flashcard,
  QuestionItem,
  QuestionType,
  RetrievedPassage
} from '../types/sora';

const AR_DIACRITICS_RE = /[\u064B-\u065F\u0670\u0640]/g;

const STOPWORDS = new Set([
  'the', 'and', 'for', 'that', 'this', 'with', 'from', 'are', 'was', 'were',
  'have', 'has', 'had', 'not', 'but', 'what', 'which', 'when', 'where', 'who',
  'why', 'how', 'into', 'about', 'than', 'then', 'also', 'can', 'will', 'their',
  'في', 'من', 'على', 'الى', 'عن', 'مع', 'هذا', 'هذه', 'ذلك', 'تلك',
  'التي', 'الذي', 'ما', 'ماذا', 'كيف', 'لماذا', 'اين', 'متى', 'ان', 'كان', 'او'
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
  const norm = normalizeBilingual(raw);
  const matches = norm.match(/[\p{L}\p{N}]+/gu) ?? [];
  return matches.filter((w) => w.length >= 2 && !STOPWORDS.has(w));
}

export function parseUploadedTextBook(
  title: string,
  subjectName: string,
  rawText: string,
  explicitPages?: BookPage[]
): { book: Book; starterQuestions: QuestionItem[]; starterCards: Flashcard[] } {
  const cleaned = rawText.replace(/\r\n/g, '\n').trim();
  const pages: BookPage[] = [];
  if (explicitPages && explicitPages.length > 0) {
    for (const ep of explicitPages) {
      pages.push({
        pageNumber: ep.pageNumber,
        text: ep.text.trim() || `[Page ${ep.pageNumber}]`
      });
    }
  } else {
    const paragraphs = cleaned.split(/\n{2,}/).filter((p) => p.trim().length > 0);
    let buffer = '';
    let pageNum = 1;

    for (const para of paragraphs) {
      if (buffer.length + para.length > 1400 && buffer.length > 0) {
        pages.push({ pageNumber: pageNum++, text: buffer.trim() });
        buffer = para;
      } else {
        buffer = buffer ? `${buffer}\n\n${para}` : para;
      }
    }
    if (buffer.trim()) {
      pages.push({ pageNumber: pageNum, text: buffer.trim() });
    }
    if (pages.length === 0) {
      pages.push({ pageNumber: 1, text: cleaned || title });
    }
  }

  const chapters: Chapter[] = [];
  const pagesPerChapter = Math.max(1, Math.ceil(pages.length / Math.min(4, pages.length)));
  for (let start = 0; start < pages.length; start += pagesPerChapter) {
    const end = Math.min(pages.length, start + pagesPerChapter);
    const chNum = chapters.length + 1;
    const firstLine = pages[start].text.split('\n')[0]?.trim().slice(0, 60) || `Chapter ${chNum}`;
    chapters.push({
      id: `ch_${chNum}`,
      chapterNumber: chNum,
      title: firstLine,
      startPage: start + 1,
      endPage: end,
      summary: pages.slice(start, end).map((p) => p.text.slice(0, 140)).join(' … ')
    });
  }

  const bookId = `book_${Date.now()}`;
  const book: Book = {
    id: bookId,
    title: title.trim() || 'Untitled Textbook',
    subjectName: subjectName.trim() || 'General',
    totalPages: pages.length,
    currentPage: 1,
    lastUpdatedMillis: Date.now(),
    bookmarkedPages: [],
    highlightsByPage: {},
    overview: `${title} (${pages.length} pages across ${chapters.length} chapters).`,
    formulaSheet: '',
    chapters,
    pages
  };

  const starterQuestions: QuestionItem[] = chapters.map((ch, idx) => {
    const pg = pages[ch.startPage - 1];
    const keyLine = pg.text.split(/[.!\n]/).find((s) => s.trim().length > 20)?.trim() ?? ch.title;
    return {
      id: `${bookId}_q_${idx + 1}`,
      bookId,
      chapterNumber: ch.chapterNumber,
      sourcePage: ch.startPage,
      questionType: idx % 2 === 0 ? 'SHORT_ANSWER' : 'TRUE_FALSE',
      difficulty: 'MEDIUM',
      marks: 2,
      questionText:
        idx % 2 === 0
          ? `Summarize the core concept introduced in ${ch.title}: "${keyLine.slice(0, 80)}"`
          : `True or False: "${keyLine.slice(0, 90)}" is discussed in Chapter ${ch.chapterNumber}.`,
      options: idx % 2 === 0 ? [] : ['True', 'False'],
      correctAnswer: idx % 2 === 0 ? keyLine : 'True',
      explanation: `Grounded on p.${ch.startPage} (Ch.${ch.chapterNumber}).`
    };
  });

  const starterCards: Flashcard[] = chapters.map((ch, idx) => ({
    id: `${bookId}_fc_${idx + 1}`,
    bookId,
    chapterNumber: ch.chapterNumber,
    front: `Ch.${ch.chapterNumber}: ${ch.title}`,
    back: pages[ch.startPage - 1]?.text.slice(0, 180) ?? ch.summary,
    easeFactor: 2.5,
    intervalDays: 1,
    repetitions: 0,
    nextReviewAtMillis: Date.now()
  }));

  return { book, starterQuestions, starterCards };
}

export function retrievePassages(
  query: string,
  pages: BookPage[],
  chapters: Chapter[],
  topK = 5,
  maxTotalChars = 6500
): RetrievedPassage[] {
  const qTokens = tokenizeBilingual(query);
  if (qTokens.length === 0 || pages.length === 0) return [];
  const qUnique = Array.from(new Set(qTokens));

  const chunks: Array<{ chapterNumber: number; pageNumber: number; excerpt: string; tf: Map<string, number>; len: number }> = [];
  for (const page of pages) {
    const ch = chapters.find((c) => page.pageNumber >= c.startPage && page.pageNumber <= c.endPage);
    const chapterNumber = ch?.chapterNumber ?? 0;
    const text = page.text.trim();
    if (!text) continue;
    for (let i = 0; i < text.length; i += 780) {
      const slice = text.slice(i, i + 950).trim();
      const tokens = tokenizeBilingual(slice);
      if (tokens.length === 0) continue;
      const tf = new Map<string, number>();
      for (const tok of tokens) tf.set(tok, (tf.get(tok) ?? 0) + 1);
      chunks.push({ chapterNumber, pageNumber: page.pageNumber, excerpt: slice, tf, len: tokens.length });
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
      if (f === 0) continue;
      const df = chunks.filter((c) => c.tf.has(term)).length;
      const idf = Math.log(1 + (chunks.length - df + 0.5) / (df + 0.5));
      const num = f * (k1 + 1);
      const den = f + k1 * (1 - b + (b * chunk.len) / Math.max(1, avgLen));
      score += idf * (num / den);
    }
    if (normQuery.length >= 4 && normalizeBilingual(chunk.excerpt).includes(normQuery)) {
      score += 3.0;
    }
    if (score > 0) {
      scored.push({
        chapterNumber: chunk.chapterNumber,
        pageNumber: chunk.pageNumber,
        score,
        excerpt: chunk.excerpt
      });
    }
  }

  scored.sort((a, bItem) => bItem.score - a.score);
  const selected: RetrievedPassage[] = [];
  let total = 0;
  for (const item of scored) {
    if (selected.length >= topK) break;
    const remaining = maxTotalChars - total;
    if (remaining <= 80) break;
    const clipped = item.excerpt.length <= remaining ? item.excerpt : `${item.excerpt.slice(0, remaining - 1)}…`;
    selected.push({ ...item, excerpt: clipped });
    total += clipped.length;
  }
  return selected;
}

export function extractNumericValue(raw: string): number | null {
  const clean = raw.trim().replace(/,/g, '');
  const frac = /([-+]?\d+(?:\.\d+)?)\s*\/\s*([-+]?\d+(?:\.\d+)?)/.exec(clean);
  if (frac) {
    const n = Number(frac[1]);
    const d = Number(frac[2]);
    if (Number.isFinite(n) && Number.isFinite(d) && d !== 0) return n / d;
  }
  const num = /[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?/.exec(clean);
  if (!num) return null;
  const parsed = Number(num[0]);
  return Number.isFinite(parsed) ? parsed : null;
}

function approxEqual(a: number, b: number): boolean {
  return Math.abs(a - b) <= Math.max(1e-4, Math.abs(a) * 0.015);
}

function parseBool(norm: string): boolean | null {
  if (['true', 't', 'yes', '1', 'صح', 'صحيح', 'نعم'].includes(norm)) return true;
  if (['false', 'f', 'no', '0', 'خطا', 'خاطئ', 'لا'].includes(norm)) return false;
  return null;
}

export function isOpenEndedQuestion(qType: QuestionType): boolean {
  return !['MCQ', 'TRUE_FALSE', 'FILL_BLANK', 'CALCULATION', 'FORMULA_BASED', 'MATCHING', 'ORDERING'].includes(qType);
}

export function evaluateAnswerOffline(q: QuestionItem, userAnswer: string): AnswerEvaluation {
  const raw = userAnswer.trim();
  const marks = Math.max(1, q.marks);
  if (!raw) return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: 'Unanswered', gradedByAi: false };

  const normTarget = normalizeBilingual(q.correctAnswer).replace(/[^\p{L}\p{N}\s.+\-/*=]/gu, ' ').replace(/\s+/g, ' ').trim();
  const normGiven = normalizeBilingual(raw).replace(/[^\p{L}\p{N}\s.+\-/*=]/gu, ' ').replace(/\s+/g, ' ').trim();
  if (normTarget === normGiven) {
    return { isCorrect: true, scoreRatio: 1, awardedMarks: marks, feedback: 'Exact match', gradedByAi: false };
  }

  switch (q.questionType) {
    case 'MCQ':
      return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: 'Option differs from key', gradedByAi: false };
    case 'TRUE_FALSE': {
      const tb = parseBool(normTarget);
      const gb = parseBool(normGiven);
      const hit = tb !== null && tb === gb;
      return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: hit ? 'Correct boolean' : 'Incorrect boolean', gradedByAi: false };
    }
    case 'FILL_BLANK': {
      const variants = q.correctAnswer.split(/[|/;]/).map((s) => normalizeBilingual(s).trim()).filter(Boolean);
      const tHit = variants.some((v) => v === normGiven);
      const nt = extractNumericValue(q.correctAnswer);
      const ng = extractNumericValue(raw);
      const nHit = nt !== null && ng !== null && approxEqual(nt, ng);
      const hit = tHit || nHit;
      return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: hit ? 'Matched accepted blank' : 'Expected term mismatch', gradedByAi: false };
    }
    case 'CALCULATION':
    case 'FORMULA_BASED': {
      const nt = extractNumericValue(q.correctAnswer);
      const ng = extractNumericValue(raw);
      if (nt !== null && ng !== null) {
        const hit = approxEqual(nt, ng);
        return {
          isCorrect: hit,
          scoreRatio: hit ? 1 : 0,
          awardedMarks: hit ? marks : 0,
          feedback: hit ? 'Within numeric tolerance (±1.5%)' : `Numeric mismatch (expected ~${nt})`,
          gradedByAi: false
        };
      }
      return evaluateOpenEndedRubric(q, raw, marks);
    }
    case 'MATCHING':
      return evaluateMatching(q.correctAnswer, raw, marks);
    case 'ORDERING':
      return evaluateOrdering(q.correctAnswer, raw, marks);
    default:
      return evaluateOpenEndedRubric(q, raw, marks);
  }
}

function normalizeEvalText(raw: string): string {
  return normalizeBilingual(raw)
    .replace(/[^\p{L}\p{N}\s.+\-/*=]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function splitMatchingPair(entry: string): [string, string] | null {
  for (const sep of ['->', '=>', '→', '=', ':', '-']) {
    const idx = entry.indexOf(sep);
    if (idx > 0 && idx + sep.length < entry.length) {
      const left = entry.slice(0, idx).trim();
      const right = entry.slice(idx + sep.length).trim();
      if (left && right) return [left, right];
    }
  }
  return null;
}

function parseMatchingEntries(raw: string): {
  byKey: Map<string, string>;
  ambiguousKeys: Set<string>;
  totalEntries: number;
} {
  const entries = raw.split(/[,;\n]/).map((s) => s.trim()).filter(Boolean);
  const byKey = new Map<string, string>();
  const ambiguousKeys = new Set<string>();
  let parsedCount = 0;
  for (const entry of entries) {
    const split = splitMatchingPair(entry);
    if (!split) continue;
    const left = normalizeEvalText(split[0]);
    const right = normalizeEvalText(split[1]);
    if (!left || !right) continue;
    parsedCount++;
    const prev = byKey.get(left);
    if (prev === undefined) {
      byKey.set(left, right);
    } else if (prev !== right) {
      ambiguousKeys.add(left);
    }
  }
  return { byKey, ambiguousKeys, totalEntries: Math.max(parsedCount, entries.length) };
}

function evaluateMatching(targetRaw: string, givenRaw: string, marks: number): AnswerEvaluation {
  const targetParsed = parseMatchingEntries(targetRaw);
  if (targetParsed.byKey.size === 0) {
    const exact = normalizeEvalText(targetRaw) === normalizeEvalText(givenRaw);
    return {
      isCorrect: exact,
      scoreRatio: exact ? 1 : 0,
      awardedMarks: exact ? marks : 0,
      feedback: exact ? 'Exact match' : 'Matching mismatch',
      gradedByAi: false
    };
  }
  const givenParsed = parseMatchingEntries(givenRaw);
  let matched = 0;
  for (const [k, expectedVal] of targetParsed.byKey.entries()) {
    const actual = givenParsed.byKey.get(k);
    if (actual !== undefined && !givenParsed.ambiguousKeys.has(k) && actual === expectedVal) {
      matched++;
    }
  }
  const denom = Math.max(targetParsed.byKey.size, givenParsed.totalEntries, 1);
  const ratio = Math.max(0, Math.min(1, matched / denom));
  const awarded = Math.min(marks, Math.max(0, Math.round(marks * ratio)));
  return {
    isCorrect: ratio >= 0.75,
    scoreRatio: ratio,
    awardedMarks: awarded,
    feedback: `Matched ${matched}/${targetParsed.byKey.size} pairs`,
    gradedByAi: false
  };
}

function parseOrderingSequence(raw: string): string[] {
  const prefixRe = /^\s*(?:\(?\d{1,2}\)?[.)]\s+|[-•*]\s+)/;
  return raw
    .split(/(?:->|→|>|[,;\n])+/)
    .map((part) => normalizeEvalText(part.replace(prefixRe, '').trim()))
    .filter(Boolean);
}

function evaluateOrdering(targetRaw: string, givenRaw: string, marks: number): AnswerEvaluation {
  const targetSeq = parseOrderingSequence(targetRaw);
  const givenSeq = parseOrderingSequence(givenRaw);
  if (targetSeq.length <= 1) {
    const exact = normalizeEvalText(targetRaw) === normalizeEvalText(givenRaw);
    return {
      isCorrect: exact,
      scoreRatio: exact ? 1 : 0,
      awardedMarks: exact ? marks : 0,
      feedback: exact ? 'Exact order' : 'Incorrect order',
      gradedByAi: false
    };
  }
  if (givenSeq.length === targetSeq.length && targetSeq.every((v, i) => givenSeq[i] === v)) {
    return {
      isCorrect: true,
      scoreRatio: 1,
      awardedMarks: marks,
      feedback: `Exact sequence match (${targetSeq.length}/${targetSeq.length})`,
      gradedByAi: false
    };
  }
  if (givenSeq.length === 0) {
    return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: 'No ordered steps detected', gradedByAi: false };
  }

  const denom = Math.max(targetSeq.length, givenSeq.length);
  let exactPosHits = 0;
  for (let i = 0; i < Math.min(targetSeq.length, givenSeq.length); i++) {
    if (targetSeq[i] === givenSeq[i]) exactPosHits++;
  }
  const exactRatio = exactPosHits / denom;

  const usedGiven = new Array<boolean>(givenSeq.length).fill(false);
  const matchedGivenPos: number[] = [];
  for (const tItem of targetSeq) {
    const gIdx = givenSeq.findIndex((g, idx) => !usedGiven[idx] && g === tItem);
    if (gIdx >= 0) {
      usedGiven[gIdx] = true;
      matchedGivenPos.push(gIdx);
    }
  }
  let concordant = 0;
  for (let i = 0; i < matchedGivenPos.length; i++) {
    for (let j = i + 1; j < matchedGivenPos.length; j++) {
      if (matchedGivenPos[i] < matchedGivenPos[j]) concordant++;
    }
  }
  const totalPairs = (targetSeq.length * (targetSeq.length - 1)) / 2;
  const pairOrderRatio = totalPairs > 0 ? (concordant / totalPairs) * (matchedGivenPos.length / denom) : 0;

  const ratio = Math.max(0, Math.min(0.99, 0.6 * exactRatio + 0.4 * pairOrderRatio));
  const awarded = Math.min(marks, Math.max(0, Math.round(marks * ratio)));
  return {
    isCorrect: ratio >= 0.75,
    scoreRatio: ratio,
    awardedMarks: awarded,
    feedback: `${exactPosHits}/${targetSeq.length} in exact position`,
    gradedByAi: false
  };
}

function evaluateOpenEndedRubric(q: QuestionItem, raw: string, marks: number): AnswerEvaluation {
  const targetTokens = Array.from(new Set(tokenizeBilingual(q.correctAnswer)));
  const givenSet = new Set(tokenizeBilingual(raw));
  if (targetTokens.length === 0) {
    const hit = normalizeBilingual(raw).includes(normalizeBilingual(q.correctAnswer));
    return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: '', gradedByAi: false };
  }
  const hits = targetTokens.filter((t) => givenSet.has(t)).length;
  const recall = hits / targetTokens.length;
  if (recall >= 0.7) {
    return { isCorrect: true, scoreRatio: 1, awardedMarks: marks, feedback: `Strong concept coverage (${hits}/${targetTokens.length})`, gradedByAi: false };
  }
  if (recall >= 0.45) {
    return { isCorrect: true, scoreRatio: 0.5, awardedMarks: Math.max(1, Math.round(marks * 0.5)), feedback: `Partial credit (${hits}/${targetTokens.length} terms)`, gradedByAi: false };
  }
  return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: `Missing key terms (${hits}/${targetTokens.length})`, gradedByAi: false };
}

export function nextSm2Schedule(card: Flashcard, rating: 'HARD' | 'MEDIUM' | 'EASY', nowMillis = Date.now()): Flashcard {
  const quality = rating === 'HARD' ? 3 : rating === 'MEDIUM' ? 4 : 5;
  const reps = quality >= 3 ? card.repetitions + 1 : 0;
  const intervalDays = reps <= 1 ? 1 : reps === 2 ? 6 : Math.round(card.intervalDays * card.easeFactor);
  const nextEase = Math.max(1.3, card.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)));
  return {
    ...card,
    easeFactor: Number(nextEase.toFixed(2)),
    intervalDays,
    repetitions: reps,
    nextReviewAtMillis: nowMillis + intervalDays * 86_400_000
  };
}
