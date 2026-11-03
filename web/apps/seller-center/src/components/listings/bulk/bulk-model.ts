import { type ImportRowMessage, type ListingContext, type ProductImport, type ProductImportRow } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

/* Pure rules of the bulk upload screens (S308): file checks before uploading, per-cell problems of a preview row,
   which header columns the preview shows first, and what the import can do next. catalog-service is the authority:
   these checks only spare a round trip. */

export const DEFAULT_LIMITS: ListingContext['importLimits'] = { maxFileBytes: 10 * 1024 * 1024, maxRows: 5000, maxImagesPerProduct: 10, maxVariantsPerProduct: 200 };

/** Problem with a chosen file before it is uploaded (null = fine). */
export function fileProblem(file: { name: string; size: number }, limits = DEFAULT_LIMITS): { key: MessageKey; vars?: Record<string, string | number> } | null {
  const ext = file.name.toLowerCase().split('.').pop() ?? '';
  if (ext === 'xls') return { key: 'sc.bulk.err.xls' };
  if (ext !== 'xlsx' && ext !== 'csv') return { key: 'sc.bulk.err.type' };
  if (file.size === 0) return { key: 'sc.bulk.err.empty' };
  if (file.size > limits.maxFileBytes) return { key: 'sc.bulk.err.size', vars: { mb: Math.round(limits.maxFileBytes / 1024 / 1024) } };
  return null;
}

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

const FIRST = ['sku', 'title', 'price (usd)', 'quantity', 'category'];

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

export function phaseOf(i: ProductImport): Phase {
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
