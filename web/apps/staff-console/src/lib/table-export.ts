'use client';

import { staffKeys } from '@afrotransact/api-client';
import { type ExportOutcome, type TableExportRequest } from '@afrotransact/data-table';
import { type QueryClient } from '@tanstack/react-query';

import { staffApi } from './api';
import { idempotencyKey } from './format';

/** Exports a client-side table (the rows it holds, as shown) through the BFF's export pipeline; listed under Exports. */
export async function exportDataset(qc: QueryClient, req: TableExportRequest): Promise<ExportOutcome> {
  const job = await staffApi().createExport({ format: req.format, dataset: req.dataset }, idempotencyKey());
  void qc.invalidateQueries({ queryKey: staffKeys.exports() });
  return job;
}
