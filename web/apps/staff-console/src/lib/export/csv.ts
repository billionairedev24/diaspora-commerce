import { type Tabular } from './table';

const cell = (v: string | number): string => {
  const s = String(v);
  return /[",\n\r]/.test(s) || /^[=+\-@]/.test(s) ? `"${s.replace(/^([=+\-@])/, "'$1").replace(/"/g, '""')}"` : s;
};

/** RFC 4180 CSV with a UTF-8 BOM (Excel opens accents correctly); formula-like cells are neutralised. */
export function toCsv(t: Tabular): string {
  return '﻿' + [t.headers, ...t.rows].map((r) => r.map(cell).join(',')).join('\r\n');
}
