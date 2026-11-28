import * as fs from 'node:fs';
import type { BookPage } from '../shared/models.js';

export const MAX_DESKTOP_PDF_BYTES = 50 * 1024 * 1024;
export const MAX_DESKTOP_PDF_PAGES = 5000;

export type DesktopPdfErrorCode =
  | 'FILE_TOO_LARGE'
  | 'ENCRYPTED_PDF'
  | 'CORRUPTED_PDF'
  | 'EMPTY_TEXT_LAYER'
  | 'EXTRACTION_FAILED';

export interface DesktopPdfExtractionResult {
  ok: boolean;
  pageCount: number;
  pages: BookPage[];
  fullText: string;
  errorCode?: DesktopPdfErrorCode;
  errorMessage?: string;
}

interface TextItemLike {
  str?: string;
  transform?: number[];
}

function formatTextItems(items: Array<TextItemLike | Record<string, unknown>>): string {
  const positioned = items
    .map((x) => x as TextItemLike)
    .filter((i) => typeof i.str === 'string' && i.str.length > 0);
  if (positioned.length === 0) return '';

  const lines: Array<{ y: number; strs: string[] }> = [];
  for (const item of positioned) {
    const tr = Array.isArray(item.transform) && item.transform.length >= 6 ? item.transform : [1, 0, 0, 1, 0, 0];
    const y = Number(tr[5] ?? 0);
    let line = lines.find((l) => Math.abs(l.y - y) <= 3.5);
    if (!line) {
      line = { y, strs: [] };
      lines.push(line);
    }
    line.strs.push(item.str ?? '');
  }
  lines.sort((a, b) => b.y - a.y);
  return lines
    .map((l) => l.strs.join(' ').replace(/[ \t]{2,}/g, ' ').trim())
    .filter(Boolean)
    .join('\n')
    .trim();
}

export async function extractPdfFromBuffer(
  buffer: Uint8Array | Buffer,
  maxBytes = MAX_DESKTOP_PDF_BYTES,
  maxPages = MAX_DESKTOP_PDF_PAGES
): Promise<DesktopPdfExtractionResult> {
  const bytes = buffer instanceof Uint8Array ? new Uint8Array(buffer) : new Uint8Array(buffer);
  if (bytes.byteLength === 0) {
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'CORRUPTED_PDF',
      errorMessage: 'The PDF file is empty (0 bytes).'
    };
  }
  if (bytes.byteLength > maxBytes) {
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'FILE_TOO_LARGE',
      errorMessage: `PDF exceeds ${Math.round(maxBytes / (1024 * 1024))} MB size limit.`
    };
  }

  interface PdfPageLike {
    getTextContent(): Promise<{ items: Array<Record<string, unknown>> }>;
    cleanup(): void;
  }
  interface PdfDocLike {
    numPages: number;
    getPage(n: number): Promise<PdfPageLike>;
    cleanup(): Promise<void>;
  }
  interface PdfTaskLike {
    promise: Promise<PdfDocLike>;
    destroy(): Promise<void>;
  }

  let loadingTask: PdfTaskLike | null = null;
  let doc: PdfDocLike | null = null;

  try {
    const pdfjsLib = await import('pdfjs-dist/legacy/build/pdf.mjs');
    const task = pdfjsLib.getDocument({
      data: bytes,
      useSystemFonts: false,
      verbosity: 0
    }) as unknown as PdfTaskLike;
    loadingTask = task;
    doc = await task.promise;

    const total = Math.min(doc.numPages, Math.max(1, maxPages));
    const pages: BookPage[] = [];
    for (let pageNumber = 1; pageNumber <= total; pageNumber++) {
      const page = await doc.getPage(pageNumber);
      try {
        const tc = await page.getTextContent();
        pages.push({ pageNumber, text: formatTextItems(tc.items) });
      } finally {
        page.cleanup();
      }
    }

    const nonEmpty = pages.filter((p) => p.text.trim().length > 0);
    if (nonEmpty.length === 0) {
      return {
        ok: false,
        pageCount: total,
        pages: [],
        fullText: '',
        errorCode: 'EMPTY_TEXT_LAYER',
        errorMessage: 'No selectable text layer found in this PDF (image/scanned PDF).'
      };
    }

    return {
      ok: true,
      pageCount: total,
      pages,
      fullText: pages.map((p) => p.text || `[Page ${p.pageNumber}]`).join('\n\n')
    };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    const lower = `${err instanceof Error ? err.name : ''} ${msg}`.toLowerCase();
    if (lower.includes('password') || lower.includes('encrypted')) {
      return {
        ok: false,
        pageCount: 0,
        pages: [],
        fullText: '',
        errorCode: 'ENCRYPTED_PDF',
        errorMessage: 'This PDF is password-protected or encrypted.'
      };
    }
    if (lower.includes('invalid pdf') || lower.includes('unexpected') || lower.includes('corrupt') || lower.includes('header')) {
      return {
        ok: false,
        pageCount: 0,
        pages: [],
        fullText: '',
        errorCode: 'CORRUPTED_PDF',
        errorMessage: `Corrupted or invalid PDF: ${msg}`
      };
    }
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'EXTRACTION_FAILED',
      errorMessage: `PDF extraction failed: ${msg}`
    };
  } finally {
    if (doc) {
      await doc.cleanup().catch(() => {});
    }
    if (loadingTask) {
      await loadingTask.destroy().catch(() => {});
    }
  }
}

export async function extractPdfFromFile(filePath: string): Promise<DesktopPdfExtractionResult> {
  const stat = fs.statSync(filePath);
  if (stat.size > MAX_DESKTOP_PDF_BYTES) {
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'FILE_TOO_LARGE',
      errorMessage: `PDF exceeds ${Math.round(MAX_DESKTOP_PDF_BYTES / (1024 * 1024))} MB size limit.`
    };
  }
  const buf = fs.readFileSync(filePath);
  return extractPdfFromBuffer(buf);
}
