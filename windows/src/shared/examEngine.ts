import { normalizeBilingual, tokenizeBilingual } from './bookRetriever.js';
import type { AnswerEvaluation, QuestionItem } from './models.js';

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
  const val = Number(num[0]);
  return Number.isFinite(val) ? val : null;
}

function approxEqual(a: number, b: number): boolean {
  return Math.abs(a - b) <= Math.max(1e-4, Math.abs(a) * 0.015);
}

function parseBool(norm: string): boolean | null {
  if (['true', 't', 'yes', '1', 'صح', 'صحيح', 'نعم'].includes(norm)) return true;
  if (['false', 'f', 'no', '0', 'خطا', 'خاطئ', 'لا'].includes(norm)) return false;
  return null;
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

  switch (q.questionType.toUpperCase()) {
    case 'MCQ':
      return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: 'Selected option differs from key', gradedByAi: false };
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
      return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: hit ? 'Matched fill blank' : 'Mismatch', gradedByAi: false };
    }
    case 'CALCULATION':
    case 'FORMULA_BASED': {
      const nt = extractNumericValue(q.correctAnswer);
      const ng = extractNumericValue(raw);
      if (nt !== null && ng !== null) {
        const hit = approxEqual(nt, ng);
        return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: hit ? 'Within ±1.5% tolerance' : `Expected ~${nt}`, gradedByAi: false };
      }
      break;
    }
    case 'MATCHING':
      return evaluateMatching(q.correctAnswer, raw, marks);
    case 'ORDERING':
      return evaluateOrdering(q.correctAnswer, raw, marks);
  }

  const targetTokens = Array.from(new Set(tokenizeBilingual(q.correctAnswer)));
  const givenSet = new Set(tokenizeBilingual(raw));
  if (targetTokens.length === 0) {
    const hit = normalizeBilingual(raw).includes(normalizeBilingual(q.correctAnswer));
    return { isCorrect: hit, scoreRatio: hit ? 1 : 0, awardedMarks: hit ? marks : 0, feedback: '', gradedByAi: false };
  }
  const hits = targetTokens.filter((t) => givenSet.has(t)).length;
  const recall = hits / targetTokens.length;
  if (recall >= 0.7) return { isCorrect: true, scoreRatio: 1, awardedMarks: marks, feedback: `Covered ${hits}/${targetTokens.length} terms`, gradedByAi: false };
  if (recall >= 0.45) return { isCorrect: true, scoreRatio: 0.5, awardedMarks: Math.max(1, Math.round(marks * 0.5)), feedback: `Partial credit (${hits}/${targetTokens.length})`, gradedByAi: false };
  return { isCorrect: false, scoreRatio: 0, awardedMarks: 0, feedback: `Missing terms (${hits}/${targetTokens.length})`, gradedByAi: false };
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
