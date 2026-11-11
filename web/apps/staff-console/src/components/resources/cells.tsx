import { type CellValue, type ColumnKind, type RecordTone } from '@afrotransact/api-client';

import { StatusPill } from '@/components/ui/StatusPill';
import { EN_MESSAGES, type MessageKey, type Translator, useT } from '@/i18n/t';
import { dateTime, duration, humanize, money, percent, shortDate } from '@/lib/format';

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
    case 'duration':
      return <span className="tabular-nums">{typeof value === 'number' ? duration(value) : String(value)}</span>;
    case 'status':
      return <StatusPill tone={tone ?? null}>{humanize(String(value))}</StatusPill>;
    case 'text':
      return <span>{String(value)}</span>;
  }
}

/** A column header: the BFF's English label, or the catalogue's translation when `st.col.<key>` exists. */
export function columnLabel(t: Translator<MessageKey>, c: { key: string; label: string }): string {
  const key = `st.col.${c.key}`;
  return key in EN_MESSAGES ? t(key as MessageKey) : c.label;
}

/** "Chrome on macOS" from the BFF, with the joining word in the active language ("Chrome sur macOS"). */
export function DeviceCell({ value }: { value: CellValue | undefined }) {
  const t = useT();
  if (value === null || value === undefined || value === '') return <span className="text-subtle">—</span>;
  const text = String(value);
  const m = /^(.+) on (.+)$/.exec(text);
  return <span>{m ? t('st.device.on', { browser: m[1] ?? '', os: m[2] ?? '' }) : text}</span>;
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
