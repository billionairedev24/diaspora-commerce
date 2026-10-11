import { type Row } from '@tanstack/react-table';

import { type ColumnKind } from './types';

/* The client-side twin of the BFF's one search (go/.../domain/table_query.go): every term must be found in some
   column, as the table shows it — money (minor units) as 1234.50, 1,234.50 and $1,234.50, dates as ISO, "Oct 11, 2026"
   and "10/11/2026", statuses humanised. Used for small lists the page holds in full. */

const humanize = (s: string) =>
  s
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .toLowerCase()
    .trim();

const grouped = (n: number, digits?: number) =>
  n.toLocaleString('en-US', digits === undefined ? { maximumFractionDigits: 6 } : { minimumFractionDigits: digits, maximumFractionDigits: digits });

/** The texts a value can be found by. */
export function searchTexts(kind: ColumnKind | undefined, v: unknown): string[] {
  if (v === null || v === undefined || v === '') return [];
  if (typeof v === 'boolean') return v ? ['yes', 'true'] : ['no', 'false'];
  if (typeof v === 'number') {
    if (kind === 'money') return [(v / 100).toFixed(2), grouped(v / 100, 2), `$${grouped(v / 100, 2)}`];
    if (kind === 'percent') return [`${v * 100}%`, String(v * 100), String(v)];
    return [String(v), grouped(v)];
  }
  if (Array.isArray(v)) return v.flatMap((x) => searchTexts(kind, x));
  const s = String(v);
  const out = [s.toLowerCase()];
  if (kind === 'status') out.push(humanize(s));
  if (kind === 'date' || kind === 'datetime') {
    const d = new Date(s.length === 10 ? `${s}T12:00:00Z` : s);
    if (!Number.isNaN(d.getTime())) {
      const tz = { timeZone: 'UTC' } as const;
      out.push(
        d.toISOString().slice(0, 10),
        d.toLocaleDateString('en-US', { ...tz, month: 'short', day: 'numeric', year: 'numeric' }).toLowerCase(),
        d.toLocaleDateString('en-GB', { ...tz, day: 'numeric', month: 'short', year: 'numeric' }).toLowerCase(),
        d.toLocaleDateString('en-US', { ...tz, month: '2-digit', day: '2-digit', year: 'numeric' }),
        d.toLocaleDateString('en-US', { ...tz, month: 'long', year: 'numeric' }).toLowerCase()
      );
    }
  }
  return out;
}

export const searchTerms = (q: string) => q.trim().toLowerCase().split(/\s+/).filter(Boolean);

const rowTexts = new WeakMap<object, string[]>();

function textsOf<T>(row: Row<T>): string[] {
  const key = row as unknown as object;
  const cached = rowTexts.get(key);
  if (cached) return cached;
  const texts = [row.id.toLowerCase()];
  row.getAllCells().forEach((cell) => {
    if (cell.column.columnDef.meta?.export === false && cell.column.columnDef.meta.kind === undefined) return;
    texts.push(...searchTexts(cell.column.columnDef.meta?.kind, cell.getValue()));
  });
  rowTexts.set(key, texts);
  return texts;
}

/** Global filter over every column: the row passes when each term is in one of its cells. */
export function everyColumnFilter<T>(row: Row<T>, _columnId: string, value: unknown): boolean {
  const terms = searchTerms(String(value ?? ''));
  if (!terms.length) return true;
  const texts = textsOf(row);
  return terms.every((t) => texts.some((x) => x.includes(t)));
}

const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

/** Sorts numbers numerically, dates chronologically and text naturally ("AT-9" before "AT-10"). */
export function kindSortingFn<T>(a: Row<T>, b: Row<T>, columnId: string): number {
  const x = a.getValue(columnId);
  const y = b.getValue(columnId);
  if (typeof x === 'number' && typeof y === 'number') return x === y ? 0 : x < y ? -1 : 1;
  const kind = a.getAllCells().find((c) => c.column.id === columnId)?.column.columnDef.meta?.kind;
  if ((kind === 'date' || kind === 'datetime') && typeof x === 'string' && typeof y === 'string') return Math.sign(Date.parse(x) - Date.parse(y));
  const sx = kind === 'status' ? humanize(String(x)) : String(x);
  const sy = kind === 'status' ? humanize(String(y)) : String(y);
  return collator.compare(sx, sy);
}
