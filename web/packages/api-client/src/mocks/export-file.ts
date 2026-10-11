import { type ExportCell, type ExportColumnKind } from '../schemas/exports';

/* The mock BFFs build every export as CSV text (the Go BFF writes real XLSX with a Summary sheet of native charts and
   PDF with a summary page); the job and its download behave the same. */

export interface MockExportColumn {
  key: string;
  label: string;
  kind: ExportColumnKind | string;
}

const cell = (v: ExportCell | undefined) => {
  const s = v === null || v === undefined ? '' : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v);
  return /[",\n\r]/.test(s) || /^[=+\-@]/.test(s) ? `"${s.replace(/^([=+\-@])/, "'$1").replace(/"/g, '""')}"` : s;
};

/** CSV of the columns and rows (money already in currency units), with a BOM so Excel reads accents. */
export function exportFileOf(columns: readonly MockExportColumn[], rows: readonly (readonly (ExportCell | undefined)[])[]): { body: string; rows: number } {
  const lines = [columns.map((c) => cell(c.label)).join(','), ...rows.map((r) => r.map(cell).join(','))];
  return { body: `\uFEFF${lines.join('\r\n')}`, rows: rows.length };
}
