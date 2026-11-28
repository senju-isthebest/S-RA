/// <reference types="vite/client" />
import * as pdfjsLib from 'pdfjs-dist/legacy/build/pdf.mjs';

export const MAX_PDF_BYTES = 50 * 1024 * 1024; // 50 MB
export const MAX_PDF_PAGES = 5000;

export type PdfExtractionErrorCode =
  | 'FILE_TOO_LARGE'
  | 'ENCRYPTED_PDF'
  | 'CORRUPTED_PDF'
  | 'EMPTY_TEXT_LAYER'
  | 'EXTRACTION_FAILED';

export interface PdfExtractedPage {
  pageNumber: number;
  text: string;
}

export interface PdfExtractionResult {
  ok: boolean;
  pageCount: number;
  pages: PdfExtractedPage[];
  fullText: string;
  errorCode?: PdfExtractionErrorCode;
  errorMessage?: string;
}

let workerInitialized = false;

async function ensurePdfWorkerConfigured(): Promise<void> {
  if (workerInitialized) return;
  workerInitialized = true;
  if (typeof window !== 'undefined') {
    try {
      const workerMod = await import('pdfjs-dist/legacy/build/pdf.worker.mjs?url');
      if (workerMod?.default) {
        pdfjsLib.GlobalWorkerOptions.workerSrc = workerMod.default;
      }
    } catch {
      // Fallback to built-in fake worker if worker URL resolution is unavailable
    }
  }
}

interface TextItemLike {
  str?: string;
  hasEOL?: boolean;
  transform?: number[];
}

function formatPageTextItems(items: Array<TextItemLike | Record<string, unknown>>): string {
  const positioned = items
    .map((raw) => raw as TextItemLike)
    .filter((item) => typeof item.str === 'string' && item.str.length > 0);

  if (positioned.length === 0) return '';

  // Group into visual lines using Y-coordinate proximity (PDF origin bottom-left)
  const lines: Array<{ y: number; entries: Array<{ x: number; str: string }> }> = [];
  for (const item of positioned) {
    const tr = Array.isArray(item.transform) && item.transform.length >= 6 ? item.transform : [1, 0, 0, 1, 0, 0];
    const x = Number(tr[4] ?? 0);
    const y = Number(tr[5] ?? 0);
    let line = lines.find((l) => Math.abs(l.y - y) <= 3.5);
    if (!line) {
      line = { y, entries: [] };
      lines.push(line);
    }
    line.entries.push({ x, str: item.str ?? '' });
  }

  lines.sort((a, b) => b.y - a.y);

  const renderedLines = lines.map((line) => {
    const joined = line.entries.map((e) => e.str).join(' ');
    return joined.replace(/[ \t]{2,}/g, ' ').trim();
  });

  return renderedLines.filter((l) => l.length > 0).join('\n').trim();
}

export async function extractPdfTextFromArrayBuffer(
  data: ArrayBuffer | Uint8Array,
  onProgress?: (processed: number, total: number) => void,
  maxBytes = MAX_PDF_BYTES,
  maxPages = MAX_PDF_PAGES
): Promise<PdfExtractionResult> {
  const bytes = data instanceof Uint8Array ? new Uint8Array(data) : new Uint8Array(data);
  if (bytes.byteLength === 0) {
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'CORRUPTED_PDF',
      errorMessage: 'The uploaded PDF file is empty (0 bytes).'
    };
  }
  if (bytes.byteLength > maxBytes) {
    return {
      ok: false,
      pageCount: 0,
      pages: [],
      fullText: '',
      errorCode: 'FILE_TOO_LARGE',
      errorMessage: `PDF exceeds maximum ${Math.round(maxBytes / (1024 * 1024))} MB size limit.`
    };
  }

  await ensurePdfWorkerConfigured();

  let loadingTask: ReturnType<typeof pdfjsLib.getDocument> | null = null;
  let doc: Awaited<ReturnType<typeof pdfjsLib.getDocument>['promise']> | null = null;
  try {
    loadingTask = pdfjsLib.getDocument({
      data: bytes,
      useSystemFonts: false,
      verbosity: 0
    });
    doc = await loadingTask.promise;
    const total = Math.min(doc.numPages, Math.max(1, maxPages));
    const pages: PdfExtractedPage[] = [];

    for (let pageNumber = 1; pageNumber <= total; pageNumber++) {
      onProgress?.(pageNumber, total);
      const page = await doc.getPage(pageNumber);
      try {
        const textContent = await page.getTextContent();
        const pageText = formatPageTextItems(textContent.items as Array<Record<string, unknown>>);
        pages.push({ pageNumber, text: pageText });
      } finally {
        page.cleanup();
      }
    }

    const nonEmptyPages = pages.filter((p) => p.text.trim().length > 0);
    if (nonEmptyPages.length === 0) {
      return {
        ok: false,
        pageCount: total,
        pages: [],
        fullText: '',
        errorCode: 'EMPTY_TEXT_LAYER',
        errorMessage:
          'No selectable text layer was found in this PDF (scanned/image-only PDF). Please use OCR on mobile or paste text directly.'
      };
    }

    const fullText = pages
      .map((p) => (p.text.trim().length > 0 ? p.text : `[Page ${p.pageNumber}: no selectable text]`))
      .join('\n\n\f\n\n');

    return {
      ok: true,
      pageCount: total,
      pages,
      fullText
    };
  } catch (err) {
    const name = err instanceof Error ? err.name : '';
    const msg = err instanceof Error ? err.message : String(err);
    const lower = `${name} ${msg}`.toLowerCase();

    if (lower.includes('password') || lower.includes('encrypted')) {
      return {
        ok: false,
        pageCount: 0,
        pages: [],
        fullText: '',
        errorCode: 'ENCRYPTED_PDF',
        errorMessage: 'This PDF is password-protected or encrypted and cannot be extracted without unlocking it first.'
      };
    }
    if (lower.includes('invalid pdf') || lower.includes('unexpected') || lower.includes('corrupt') || lower.includes('header')) {
      return {
        ok: false,
        pageCount: 0,
        pages: [],
        fullText: '',
        errorCode: 'CORRUPTED_PDF',
        errorMessage: `Invalid or corrupted PDF structure: ${msg}`
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
      try {
        await doc.cleanup();
      } catch {
        // Ignore cleanup errors
      }
    }
    if (loadingTask) {
      try {
        await loadingTask.destroy();
      } catch {
        // Ignore destroy errors
      }
    }
  }
}
