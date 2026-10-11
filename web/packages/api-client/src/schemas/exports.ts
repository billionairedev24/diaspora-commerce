import { z } from 'zod';

const nullableString = z.string().nullable();

/* Table exports shared by the staff console and Seller Center (contracts: ExportFormat, ExportJob, ExportDataset).
   CSV is data only; XLSX adds a Summary sheet of native Excel charts; PDF adds a summary page of charts. */

export const ExportFormatSchema = z.enum(['csv', 'xlsx', 'pdf']);
export type ExportFormat = z.infer<typeof ExportFormatSchema>;
export const ExportStatusSchema = z.enum(['QUEUED', 'RUNNING', 'READY', 'FAILED', 'EXPIRED']);
export type ExportStatus = z.infer<typeof ExportStatusSchema>;
export const ExportJobSchema = z.object({
  id: z.string(),
  /** The table exported: a staff resource key, a Seller Center table or a client-side table's key. */
  resource: z.string(),
  title: z.string().default(''),
  format: ExportFormatSchema,
  status: ExportStatusSchema,
  rows: z.number().int(),
  async: z.boolean(),
  fileName: z.string(),
  createdAt: z.string(),
  finishedAt: nullableString,
  expiresAt: nullableString,
  downloadUrl: nullableString,
  error: nullableString
});
export type ExportJob = z.infer<typeof ExportJobSchema>;
export const ExportJobListSchema = z.object({ items: z.array(ExportJobSchema) });
export type ExportJobList = z.infer<typeof ExportJobListSchema>;

export const ExportColumnKindSchema = z.enum(['text', 'strong', 'mono', 'money', 'number', 'date', 'datetime', 'status', 'percent']);
export type ExportColumnKind = z.infer<typeof ExportColumnKindSchema>;
export interface ExportColumn {
  key: string;
  label: string;
  kind: ExportColumnKind;
}
export type ExportCell = string | number | boolean | null;
/** A client-side table exported as shown: one cell per column, money in currency units, dates as ISO strings. */
export interface ExportDataset {
  table: string;
  title?: string;
  scope?: string;
  columns: ExportColumn[];
  rows: ExportCell[][];
}

/** What every server table export carries: the table's search, sort, visible columns and (optionally) selection. */
export interface TableExportQuery {
  format: ExportFormat;
  q?: string;
  status?: string;
  sort?: string[];
  ids?: string[];
  columns?: string[];
}
