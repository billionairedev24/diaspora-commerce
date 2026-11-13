import { type ImageCheck, type ImportMode, type ImportRowMessage, type ListingContext, type ProductImport, type ProductImportRow } from '@afrotransact/api-client';

import { type MessageKey, type Translator } from '@/i18n/t';

import { checkVars } from '../form/image-rules';

/* Pure rules of the bulk upload screens (S308): file checks before uploading, per-cell problems of a preview row,
   which header columns the preview shows first, and what the import can do next. catalog-service is the authority:
   these checks only spare a round trip. */

export const DEFAULT_LIMITS: ListingContext['importLimits'] = { maxFileBytes: 10 * 1024 * 1024, maxRows: 5000, maxImagesPerProduct: 10, maxVariantsPerProduct: 200, maxFeedFileBytes: 25 * 1024 * 1024, maxFeedRows: 50_000 };

/** What a seller can upload: product files (CREATE, UPDATE), offers on existing pages (OFFER), the price and quantity feed (PRICE_QTY) and stock counts (STOCK, inventory). */
export type UploadKind = ImportMode | 'STOCK';

/** The upload types in the order they are offered, with the access each needs: listings (and inventory for what changes stock). */
export const UPLOAD_KINDS: { kind: UploadKind; modules: ('listings' | 'inventory')[] }[] = [
  { kind: 'CREATE', modules: ['listings'] },
  { kind: 'OFFER', modules: ['listings'] },
  { kind: 'PRICE_QTY', modules: ['listings', 'inventory'] },
  { kind: 'STOCK', modules: ['inventory'] },
  { kind: 'UPDATE', modules: ['listings'] }
];

/** The largest file and the most rows of an upload type: 10 MB and 5,000 rows, but 25 MB and 50,000 for the price and quantity feed and the stock file. */
export function limitsFor(kind: UploadKind, limits = DEFAULT_LIMITS): { maxFileBytes: number; maxRows: number } {
  return kind === 'PRICE_QTY' || kind === 'STOCK'
    ? { maxFileBytes: limits.maxFeedFileBytes, maxRows: limits.maxFeedRows }
    : { maxFileBytes: limits.maxFileBytes, maxRows: limits.maxRows };
}

/** Problem with a chosen file before it is uploaded (null = fine). A stock file is CSV only. */
export function fileProblem(file: { name: string; size: number }, limits = DEFAULT_LIMITS, kind: UploadKind = 'CREATE'): { key: MessageKey; vars?: Record<string, string | number> } | null {
  const ext = file.name.toLowerCase().split('.').pop() ?? '';
  if (ext === 'xls') return { key: 'sc.bulk.err.xls' };
  if (kind === 'STOCK' ? ext !== 'csv' : ext !== 'xlsx' && ext !== 'csv') return { key: kind === 'STOCK' ? 'sc.bulk.err.csvOnly' : 'sc.bulk.err.type' };
  if (file.size === 0) return { key: 'sc.bulk.err.empty' };
  const max = limitsFor(kind, limits).maxFileBytes;
  if (file.size > max) return { key: 'sc.bulk.err.size', vars: { mb: Math.round(max / 1024 / 1024) } };
  return null;
}

/** The message of a preview image finding: the same words as the product form's image cards (sc.img.check.<CODE>); the English message only for a code the catalogue does not have. */
export function imageCheckText(t: Translator<MessageKey>, check: ImageCheck): string {
  const key = `sc.img.check.${check.code}` as MessageKey;
  const text = t(key, checkVars(check));
  return text === key || !text ? check.message : text;
}

/** Why a held row waits, in plain words: the tax details, the seller's selling approval, or a photo in review. */
export function heldWhyKey(note: string | null | undefined): MessageKey {
  const n = (note ?? '').toLowerCase();
  if (n.includes('tax')) return 'sc.bulk.heldWhy.tax';
  if (n.includes('approval') || n.includes('approve')) return 'sc.bulk.heldWhy.approval';
  if (n.includes('photo') || n.includes('image')) return 'sc.bulk.heldWhy.photo';
  return 'sc.bulk.heldWhy.generic';
}

/** The label key of an upload's mode (the history and the preview header). */
export const modeLabelKey = (mode: UploadKind): MessageKey => `sc.bulk.mode.${mode}` as MessageKey;

/** "Price (USD) *" → "Price (USD)". */
export const headerLabel = (h: string) => h.replace(/\s*\*\s*$/, '').trim();

/** Messages of a row by column index (-1: about the whole row). */
export function cellProblems(row: ProductImportRow): Map<number, ImportRowMessage[]> {
  const out = new Map<number, ImportRowMessage[]>();
  row.messages.forEach((m) => {
    const list = out.get(m.columnIndex) ?? [];
    list.push(m);
    out.set(m.columnIndex, list);
  });
  return out;
}

const FIRST = ['barcode (upc, ean, isbn)', 'seller sku', 'sku', 'title', 'price (usd)', 'price', 'quantity', 'category'];

/** Header columns in preview order: SKU, title, price, quantity and category first, then the rest as uploaded. */
export function previewColumns(header: string[]): { index: number; label: string }[] {
  const cols = header.map((h, index) => ({ index, label: headerLabel(h) })).filter((c) => c.label !== '');
  const rank = (l: string) => {
    const i = FIRST.indexOf(l.toLowerCase());
    return i < 0 ? FIRST.length : i;
  };
  return [...cols].sort((a, b) => rank(a.label) - rank(b.label) || a.index - b.index);
}

export type Phase = 'review' | 'running' | 'done' | 'failed';

export function phaseOf(i: Pick<ProductImport, 'status'>): Phase {
  if (i.status === 'VALIDATED') return 'review';
  if (i.status === 'QUEUED' || i.status === 'RUNNING') return 'running';
  if (i.status === 'FAILED') return 'failed';
  return 'done';
}

/** Rows the import would bring in (valid + warnings). */
export const importable = (i: ProductImport) => i.validRows + i.warningRows;

/** Progress of a running import, 0–100. */
export const progress = (i: ProductImport) => {
  const total = i.validOnly ? importable(i) : i.totalRows;
  if (!total) return 100;
  return Math.min(100, Math.round((Math.min(i.processedRows, total) / total) * 100));
};

/** Rows of an upload with problems, then the others; stable by row number. */
export const byProblems = (rows: ProductImportRow[]) =>
  [...rows].sort((a, b) => Number(b.status === 'ERROR') - Number(a.status === 'ERROR') || a.rowNumber - b.rowNumber);
