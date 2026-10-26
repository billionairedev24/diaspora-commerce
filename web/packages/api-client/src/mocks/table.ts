/* The BFF's table query model for the mock BFF (mirrors go/services/gateway-bff/internal/domain/table_query.go): one
   search matched against every column as shown (money in currency units, dates as ISO and "Oct 11, 2026", statuses
   humanised), sort by any column (`<column>,asc|desc`, empty cells last), page numbers. */

export type TableCell = string | number | boolean | null | undefined;
export interface TableColumnLike {
  key: string;
  kind: string;
}
export interface TableRowLike {
  id: string;
  cells: Record<string, TableCell>;
  /** Text the table does not show but its search finds (the ids behind names), like the BFF's Row.Search. */
  search?: readonly string[];
}

const humanize = (s: string) =>
  s
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .toLowerCase()
    .trim();

const grouped = (n: number, digits?: number) => n.toLocaleString('en-US', digits === undefined ? { maximumFractionDigits: 6 } : { minimumFractionDigits: digits, maximumFractionDigits: digits });

/** The texts a cell can be found by. */
export function cellTexts(kind: string, v: TableCell): string[] {
  if (v === null || v === undefined || v === '') return [];
  if (typeof v === 'boolean') return v ? ['yes', 'true'] : ['no', 'false'];
  if (typeof v === 'number') {
    if (kind === 'money') return [(v / 100).toFixed(2), grouped(v / 100, 2), `$${grouped(v / 100, 2)}`];
    if (kind === 'percent') return [`${v * 100}%`, String(v * 100), String(v)];
    return [String(v), grouped(v)];
  }
  const out = [v.toLowerCase()];
  if (kind === 'status') out.push(humanize(v));
  if (kind === 'date' || kind === 'datetime') {
    const d = new Date(v.length === 10 ? `${v}T12:00:00Z` : v);
    if (!Number.isNaN(d.getTime())) {
      const opts = { timeZone: 'UTC' } as const;
      out.push(
        d.toISOString().slice(0, 10),
        d.toLocaleDateString('en-US', { ...opts, month: 'short', day: 'numeric', year: 'numeric' }).toLowerCase(),
        d.toLocaleDateString('en-GB', { ...opts, day: 'numeric', month: 'short', year: 'numeric' }).toLowerCase(),
        d.toLocaleDateString('en-US', { ...opts, month: '2-digit', day: '2-digit', year: 'numeric' }),
        d.toLocaleDateString('en-US', { ...opts, month: 'long', year: 'numeric' }).toLowerCase()
      );
    }
  }
  return out;
}

export const searchTerms = (q: string | null | undefined) => (q ?? '').trim().toLowerCase().split(/\s+/).filter(Boolean);

/** Every term must be found in some cell (or the id, or a hidden id the row carries). */
export function matchesSearch(cols: readonly TableColumnLike[], row: TableRowLike, terms: readonly string[]): boolean {
  if (!terms.length) return true;
  const texts = [row.id.toLowerCase(), ...(row.search ?? []).map((s) => s.toLowerCase()), ...cols.flatMap((c) => cellTexts(c.kind, row.cells[c.key]))];
  return terms.every((t) => texts.some((x) => x.includes(t)));
}

export interface SortKey {
  key: string;
  desc: boolean;
}

/** Parses `sort` values; null when a column is unknown or the direction is not asc/desc. */
export function parseSort(values: readonly string[], known: (key: string) => boolean): SortKey[] | null {
  const out: SortKey[] = [];
  for (const v of values.flatMap((x) => x.split(';')).map((x) => x.trim()).filter(Boolean)) {
    const [key = '', dir = 'asc'] = v.split(',').map((x) => x.trim());
    if (!known(key) || !['asc', 'desc'].includes(dir.toLowerCase())) return null;
    if (!out.some((o) => o.key === key)) out.push({ key, desc: dir.toLowerCase() === 'desc' });
  }
  return out.length > 3 ? null : out;
}

const empty = (v: TableCell) => v === null || v === undefined || v === '';
const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

function compare(kind: string, a: TableCell, b: TableCell): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if ((kind === 'date' || kind === 'datetime') && typeof a === 'string' && typeof b === 'string') return Date.parse(a) - Date.parse(b);
  const sa = kind === 'status' ? humanize(String(a)) : String(a);
  const sb = kind === 'status' ? humanize(String(b)) : String(b);
  return collator.compare(sa, sb);
}

/** Sorts a copy of the rows; empty cells last in both directions. */
export function sortRows<R extends TableRowLike>(rows: readonly R[], cols: readonly TableColumnLike[], keys: readonly SortKey[]): R[] {
  const kinds = new Map(cols.map((c) => [c.key, c.kind]));
  return [...rows].sort((x, y) => {
    for (const k of keys) {
      const a = x.cells[k.key];
      const b = y.cells[k.key];
      if (empty(a) && empty(b)) continue;
      if (empty(a)) return 1;
      if (empty(b)) return -1;
      const c = compare(kinds.get(k.key) ?? 'text', a, b);
      if (c !== 0) return k.desc ? -c : c;
    }
    return 0;
  });
}

/** Search + sort + selection in one go. */
export function queryRows<R extends TableRowLike>(rows: readonly R[], cols: readonly TableColumnLike[], q: string | null | undefined, sort: readonly SortKey[], ids?: readonly string[]): R[] {
  const terms = searchTerms(q);
  const want = ids?.length ? new Set(ids) : null;
  return sortRows(
    rows.filter((r) => (!want || want.has(r.id)) && matchesSearch(cols, r, terms)),
    cols,
    sort
  );
}
