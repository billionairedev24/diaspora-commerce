'use client';

import { type RecordTone, TAX_SHADOW_STATUSES, type TaxShadow, type TaxShadowCell, type TaxShadowRow, type TaxShadowStatus } from '@afrotransact/api-client';
import { CellText, DataTable, type TableState } from '@afrotransact/data-table';
import { Select, Toolbar } from '@afrotransact/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMemo } from 'react';

import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime } from '@/lib/format';

import { Section } from './shared';

const STATE_TONE: Record<TaxShadowCell['state'], RecordTone> = { ANSWERED: 'good', NOT_CONFIGURED: 'bad', TIMEOUT: 'bad', ERROR: 'bad', NO_CODE: 'warn', NO_DECISION: 'info', NOT_ASKED: 'info' };

/** One quote: what the provider said and how far it is from ours, or the state it is in and why (never a $0.00). */
function QuoteCell({ cell, same, vs }: { cell: TaxShadowCell; same: string; vs: (diff: string) => string }) {
  if (cell.state === 'ANSWERED') {
    const note = cell.differenceMinor === 0 ? same : cell.differenceText ? vs(cell.differenceText) : '';
    return (
      <span className="flex min-w-0 flex-col gap-0.5 whitespace-normal">
        <CellText title={cell.text}>{cell.text}</CellText>
        {note ? <CellText muted>{note}</CellText> : null}
      </span>
    );
  }
  return (
    <span className="flex min-w-0 flex-col items-start gap-0.5 whitespace-normal">
      <StatusPill tone={STATE_TONE[cell.state]}>{cell.stateLabel}</StatusPill>
      {cell.reason ? (
        <CellText muted lines={2}>
          {cell.reason}
        </CellText>
      ) : null}
    </span>
  );
}

/**
 * Our quote next to every provider's, one order line per row (TanStack Table through the shared DataTable, server-driven:
 * the BFF filters, searches and pages). A provider column names the provider and each cell says whether it answered or
 * why it did not (not configured, timed out, provider error, no code mapped).
 */
export function TaxShadowLines({ d, state, status, onStatus, loading, fetching }: { d: TaxShadow; state: TableState; status: TaxShadowStatus; onStatus: (s: TaxShadowStatus) => void; loading: boolean; fetching: boolean }) {
  const t = useT();
  const columns = useMemo<ColumnDef<TaxShadowRow>[]>(() => {
    const providerColumns = d.providers.map<ColumnDef<TaxShadowRow>>((p) => ({
      id: `quote-${p.provider}`,
      header: p.label,
      size: 250,
      enableSorting: false,
      accessorFn: (r) => r.quotes.find((q) => q.provider === p.provider)?.text ?? '',
      cell: ({ row }) => {
        const cell = row.original.quotes.find((q) => q.provider === p.provider);
        return cell ? <QuoteCell cell={cell} same={t('st.tax.shadow.sameAsOurs')} vs={(diff) => t('st.tax.shadow.vsOurs', { diff })} /> : null;
      },
      meta: { label: p.label, kind: 'text' }
    }));
    return [
      {
        id: 'orderNumber',
        header: t('st.tax.shadow.col.order'),
        size: 170,
        enableSorting: false,
        accessorFn: (r) => r.orderNumber,
        cell: ({ row }) => (
          <span className="flex min-w-0 flex-col gap-0.5">
            <strong className="block truncate font-semibold">{row.original.orderNumber || t('st.tax.shadow.col.orderFallback')}</strong>
            <CellText muted>{row.original.quotedAt ? dateTime(row.original.quotedAt) : ''}</CellText>
          </span>
        ),
        meta: { label: t('st.tax.shadow.col.order'), kind: 'text' }
      },
      { id: 'categoryName', header: t('st.tax.shadow.col.category'), size: 170, enableSorting: false, accessorFn: (r) => r.categoryName, meta: { label: t('st.tax.shadow.col.category'), kind: 'text' } },
      {
        id: 'inHouse',
        header: t('st.tax.shadow.col.ours'),
        size: 230,
        enableSorting: false,
        accessorFn: (r) => r.inHouse.text,
        cell: ({ row }) => (
          <span className="flex min-w-0 flex-col items-start gap-0.5 whitespace-normal">
            {row.original.inHouse.state === 'ANSWERED' ? <CellText>{row.original.inHouse.text}</CellText> : <StatusPill tone={STATE_TONE[row.original.inHouse.state]}>{row.original.inHouse.stateLabel}</StatusPill>}
          </span>
        ),
        meta: { label: t('st.tax.shadow.col.ours'), kind: 'text' }
      },
      ...providerColumns
    ];
  }, [d.providers, t]);

  const options = TAX_SHADOW_STATUSES.map((s) => ({ value: s, label: t(`st.tax.shadow.filter.${s}`) }));
  return (
    <Section title={t('st.tax.shadow.lines.title')}>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.tax.shadow.lines.intro')}</p>
      <Toolbar>
        <Select label={t('st.tax.shadow.filter.show')} value={status} options={options} onChange={(e) => onStatus(e.currentTarget.value as TaxShadowStatus)} className="min-w-64" />
      </Toolbar>
      <DataTable<TaxShadowRow>
        rowHeight={72}
        id="staff.tax.shadow.lines"
        label={t('st.tax.shadow.lines.table')}
        columns={columns}
        data={d.items}
        getRowId={(r) => `${r.orderId}-${r.lineId}`}
        rowLabel={(r) => r.orderNumber || r.lineId}
        state={state}
        mode="server"
        total={d.total}
        loading={loading}
        fetching={fetching}
        partial={d.comparisonPartial}
        searchPlaceholder={t('st.tax.shadow.search')}
        enableSelection={false}
        empty={d.linesInPeriod === 0 ? t('st.tax.shadow.empty') : t('st.tax.shadow.noMatches')}
        noMatches={t('st.tax.shadow.noMatches')}
      />
    </Section>
  );
}

/** One card per provider: whether it is in the comparison and how its recorded lines came out. */
export function TaxShadowProviders({ d }: { d: TaxShadow }) {
  const t = useT();
  return (
    <Section title={t('st.tax.shadow.providers.title')}>
      <ul className="m-0 grid list-none gap-3 p-0 sm:grid-cols-2">
        {d.providers.map((p) => (
          <li key={p.provider} data-testid={`shadow-provider-${p.provider}`} className="flex flex-col gap-1 rounded-md border border-border p-3">
            <span className="flex items-center gap-2">
              <strong>{p.label}</strong>
              <StatusPill tone={p.state === 'ASKING' ? 'good' : p.state === 'CHARGING' ? 'info' : 'bad'}>{p.stateLabel}</StatusPill>
            </span>
            <span className="text-sm text-muted">{p.note}</span>
            {p.state === 'ASKING' ? <span className="text-sm">{t('st.tax.shadow.providers.tally', { lines: p.lines, answered: p.answered, differs: p.differs, noAnswer: p.noAnswer, noCode: p.noCode })}</span> : null}
          </li>
        ))}
      </ul>
    </Section>
  );
}
