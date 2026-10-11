'use client';

import { type SellerExportRequest } from '@afrotransact/api-client';
import { type ExportOutcome, type TableExportRequest } from '@afrotransact/data-table';

import { sellerApi } from './api';

/** Exports a client-side table (its rows as shown) through the BFF; the file downloads when ready. */
export function exportDataset(req: TableExportRequest): Promise<ExportOutcome> {
  return sellerApi().createExport({ format: req.format, dataset: req.dataset });
}

/** Exports a server table (orders, listings) with its search, sort, visible columns and selection; org-scoped on the BFF. */
export function exportTable(table: NonNullable<SellerExportRequest['table']>, req: TableExportRequest): Promise<ExportOutcome> {
  return sellerApi().createExport({ table, format: req.format, q: req.q || undefined, sort: req.sort, ids: req.ids.length ? req.ids : undefined, columns: req.columns });
}

/** Polls a queued export (Seller Center has no exports list: the table downloads the file when it is ready). */
export const pollExport = (id: string): Promise<ExportOutcome> => sellerApi().getExport(id);

/** Runs one call per selected row and summarises: "3 paused; 1 skipped — Only live listings can be paused." */
export async function runForEach<T>(rows: T[], call: (row: T) => Promise<unknown>, done: string, describe: (e: unknown) => string): Promise<{ text: string; tone: 'info' | 'error' }> {
  let ok = 0;
  const failures: string[] = [];
  for (const r of rows) {
    try {
      await call(r);
      ok++;
    } catch (e) {
      failures.push(describe(e));
    }
  }
  if (!failures.length) return { text: `${ok} ${done}.`, tone: 'info' };
  const why = [...new Set(failures)].slice(0, 2).join(' · ');
  return { text: `${ok} of ${rows.length} ${done}; ${failures.length} skipped — ${why}`, tone: ok ? 'info' : 'error' };
}
