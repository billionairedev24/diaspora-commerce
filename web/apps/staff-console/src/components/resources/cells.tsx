import { type CellValue, type ColumnKind, type RecordTone } from '@afrotransact/api-client';

import { StatusPill } from '@/components/ui/StatusPill';
import { dateTime, humanize, money, percent, shortDate } from '@/lib/format';

/** Renders one server-driven cell by its column kind (money in minor units, ISO dates, status chips…). */
export function Cell({ kind, value, tone }: { kind: ColumnKind; value: CellValue | undefined; tone?: RecordTone | null }) {
  if (value === null || value === undefined || value === '') return <span className="text-subtle">—</span>;
  switch (kind) {
    case 'strong':
      return <strong className="font-semibold">{String(value)}</strong>;
    case 'mono':
      return <span className="font-mono text-xs">{String(value)}</span>;
    case 'money':
      return <span className="tabular-nums">{typeof value === 'number' ? money(value) : String(value)}</span>;
    case 'number':
      return <span className="tabular-nums">{typeof value === 'number' ? value.toLocaleString('en-US') : String(value)}</span>;
    case 'percent':
      return <span className="tabular-nums">{typeof value === 'number' ? percent(value) : String(value)}</span>;
    case 'date':
      return <span>{shortDate(String(value))}</span>;
    case 'datetime':
      return <span>{dateTime(String(value))}</span>;
    case 'status':
      return <StatusPill tone={tone ?? null}>{humanize(String(value))}</StatusPill>;
    case 'text':
      return <span>{String(value)}</span>;
  }
}

/** A sheet field value formatted by kind. */
export function fieldText(kind: string, value: string): string {
  if (value === '—' || value === '') return '—';
  switch (kind) {
    case 'money':
      return Number.isFinite(Number(value)) ? money(Number(value)) : value;
    case 'date':
      return shortDate(value);
    case 'datetime':
      return dateTime(value);
    case 'status':
      return humanize(value);
    default:
      return value;
  }
}
