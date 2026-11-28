import { normalizeBilingual, tokenizeBilingual } from './bookRetriever';
import type { AnswerEvaluation, QuestionItem } from './models';

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
    case 'ORDERING': {
      const tPairs = normTarget.split(/[,;\n]/).map((s) => s.trim()).filter(Boolean);
      const gPairs = normGiven.split(/[,;\n]/).map((s) => s.trim()).filter(Boolean);
      const matched = tPairs.filter((p) => gPairs.includes(p)).length;
      const ratio = tPairs.length ? matched / tPairs.length : 0;
      return { isCorrect: ratio >= 0.6, scoreRatio: ratio, awardedMarks: Math.round(marks * ratio), feedback: `Matched ${matched}/${tPairs.length}`, gradedByAi: false };
    }
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
