'use client';

import {
  applyBps,
  formatBps,
  type SellerAppliedRate,
  type SellerFeeCategory,
  type SellerFeeVersion,
  type SellerNegotiatedRate,
  sellerQueries,
  type SellerRateCard} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

const EXAMPLE_SALE = 10_000;

type T = Translator<MessageKey>;
type Applied = Pick<SellerAppliedRate, 'source' | 'planName' | 'negotiatedUntil'>;

/** Where a rate comes from: "Negotiated rate until 31 Dec 2026", "Your plan: Seller Growth", "Category rate", … */
export function sourceLabel(t: T, r: Applied): string {
  switch (r.source) {
    case 'OVERRIDE':
      return r.negotiatedUntil ? t('sc.fees.source.negotiatedUntil', { date: shortDate(r.negotiatedUntil) }) : t('sc.fees.source.negotiated');
    case 'PLAN':
      return r.planName ? t('sc.fees.source.plan', { plan: r.planName }) : t('sc.fees.source.planUnnamed');
    case 'CATEGORY':
      return t('sc.fees.source.category');
    case 'RETAIL':
      return t('sc.fees.source.retail');
    default:
      return t('sc.fees.source.default');
  }
}

const tone = (r: Applied): Tone => (r.source === 'OVERRIDE' ? 'good' : r.source === 'PLAN' ? 'brand' : 'info');

/** Negotiated commission / connection fees (active and scheduled) of the organisation. */
function NegotiatedList({ rates, t }: { rates: SellerNegotiatedRate[]; t: T }) {
  if (!rates.length) return null;
  return (
    <ul aria-label={t('sc.fees.negotiatedTitle')} className="m-0 flex list-none flex-col divide-y divide-border p-0 text-sm">
      {rates.map((r) => (
        <li key={`${r.type}-${r.category}-${r.startsAt}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
          <span className="min-w-0 flex-1 basis-48">
            <strong>{t(`sc.fees.negotiatedType.${r.type}`)}</strong> <span className="text-muted">· {r.label}</span>
          </span>
          <span className="text-xs text-muted">
            {r.endsAt ? t('sc.fees.negotiatedPeriod', { from: shortDate(r.startsAt), to: shortDate(r.endsAt) }) : t('sc.fees.negotiatedFrom', { from: shortDate(r.startsAt) })}
          </span>
          <StatusPill tone={r.status === 'ACTIVE' ? 'good' : 'info'}>{t(`sc.fees.negotiatedStatus.${r.status}`)}</StatusPill>
          <strong className="w-16 text-right">{formatBps(r.rateBps)}</strong>
        </li>
      ))}
    </ul>
  );
}

/** Connection fees on service bookings (Provider Hub), per service category, with where each rate comes from. */
export function ConnectionFeesCard({ card, t }: { card: SellerRateCard; t: T }) {
  const rows = [...card.categories, card.other];
  return (
    <Card title={t('sc.fees.connectionTitle')} actions={<span className="text-xs text-muted">{t('sc.fees.versionSince', { version: String(card.version), date: shortDate(card.effectiveFrom) })}</span>}>
      <p className="m-0 text-sm text-muted">{t('sc.fees.connectionHelp')}</p>
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="at-table text-sm" aria-label={t('sc.fees.connectionTitle')}>
          <thead className="bg-surface-muted text-left">
            <tr>
              <th scope="col" className="px-3 py-2">{t('sc.fees.col.category')}</th>
              <th scope="col" className="px-3 py-2 text-right">{t('sc.fees.col.connectionFee')}</th>
              <th scope="col" className="px-3 py-2 text-right">{t('sc.fees.col.example', { sale: money(EXAMPLE_SALE) })}</th>
              <th scope="col" className="px-3 py-2">{t('sc.fees.col.source')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.category || 'other'} className="border-t border-border">
                <th scope="row" className="px-3 py-2 text-left font-medium">{r.label}</th>
                <td className="px-3 py-2 text-right font-semibold">{formatBps(r.bps)}</td>
                <td className="px-3 py-2 text-right">{money(applyBps(EXAMPLE_SALE, r.bps))}</td>
                <td className="px-3 py-2"><StatusPill tone={tone(r)}>{sourceLabel(t, r)}</StatusPill></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-xs text-muted">{t('sc.fees.connectionRules')}</p>
    </Card>
  );
}

function FeeTiles({ v }: { v: SellerFeeVersion }) {
  const t = useT();
  const df = v.deliveryFee;
  const delivery: [string, string, string] | null = df
    ? [
        t('sc.fees.delivery'),
        df.enabled ? df.summary : t('sc.fees.deliveryOff'),
        df.enabled ? `${t('sc.fees.deliveryDetail', { miles: df.maxRadiusMiles })} · ${t(`sc.fees.deliverySource.${df.source}`)}` : ''
      ]
    : null;
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
      {[
        ['Card fee estimate', `${formatBps(v.cardPctBps)} + ${money(v.cardFixedMinor)}`, 'Per charge, until Stripe reports the actual fee'],
        ['FBA fee', `${money(v.fbaPerUnitMinor)} / unit`, 'Pick, pack and ship for fulfilled-by-AfroTransact orders'],
        ...(delivery ? [delivery] : []),
        ['Other categories', formatBps(v.defaultCommissionBps), 'Commission where no category rate applies'],
        ['Refunds', v.reverseCommissionOnRefund ? 'Commission returned' : 'Commission kept', 'On the refunded amount']
      ].map(([label, value, detail]) => (
        <div key={label} className="flex flex-col gap-1 rounded-md border border-border p-4">
          <span className="text-xs font-semibold text-muted">{label}</span>
          <strong className="text-xl">{value}</strong>
          <span className="text-xs text-muted">{detail}</span>
        </div>
      ))}
    </div>
  );
}

/** Fees & commission (read-only): what payments charges the active organisation now, and the next scheduled change. */
export function FeesView() {
  const t = useT();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.fees(sellerApi(), orgId));
  const state = useTableState({ searchParams: useSearchParams() });
  const next = q.data?.upcoming ?? null;
  const columns = useMemo<ColumnDef<SellerFeeCategory>[]>(() => {
    const nextBy = new Map((next?.categories ?? []).map((c) => [c.category, c]));
    const cols: ColumnDef<SellerFeeCategory>[] = [
      { id: 'label', accessorFn: (c) => c.label, header: 'Category', size: 220, meta: { kind: 'strong' } },
      { id: 'commission', accessorFn: (c) => formatBps(c.commissionBps), header: 'Commission', size: 130 },
      { id: 'example', accessorFn: (c) => applyBps(EXAMPLE_SALE, c.commissionBps), header: `On a ${money(EXAMPLE_SALE)} sale`, size: 150, meta: { kind: 'money', align: 'end' },
        cell: ({ row }) => money(applyBps(EXAMPLE_SALE, row.original.commissionBps)) },
      { id: 'source', accessorFn: (c) => sourceLabel(t, c), header: t('sc.fees.col.source'), size: 200, meta: { kind: 'status' },
        cell: ({ row }) => <StatusPill tone={tone(row.original)}>{sourceLabel(t, row.original)}</StatusPill> }
    ];
    if (next) {
      cols.push({
        id: 'next',
        accessorFn: (c) => {
          const n = nextBy.get(c.category);
          return n ? (n.commissionBps === c.commissionBps ? 'No change' : formatBps(n.commissionBps)) : formatBps(next.defaultCommissionBps);
        },
        header: `From ${shortDate(next.effectiveFrom)}`,
        size: 150,
        cell: ({ row, getValue }) => <span className={getValue() === 'No change' && nextBy.get(row.original.category) ? 'text-muted' : 'font-semibold'}>{String(getValue())}</span>
      });
    }
    return cols;
  }, [next, t]);
  if (q.isPending) return <ModulePage module="fees" readOnlyNote={false}><Spinner /></ModulePage>;
  if (q.isError) return <ModulePage module="fees" readOnlyNote={false}><ErrorState error={q.error} onRetry={() => void q.refetch()} /></ModulePage>;
  const f = q.data;
  const cur = f.current;
  return (
    <ModulePage module="fees" readOnlyNote={false}>
      {f.retail ? (
        <Notice tone="info" title="AfroTransact Retail:">
          {f.note ?? 'No commission, no fees (merchant of record)'}.
        </Notice>
      ) : null}
      {next ? (
        <Notice tone="warning" title={`Fees change on ${shortDate(next.effectiveFrom)}.`}>
          {f.changes.length ? f.changes.map((c) => `${c.label}: ${c.from} → ${c.to}`).join(' · ') : 'Your rates stay the same.'} Orders placed before then keep today’s fees.
        </Notice>
      ) : null}
      {f.planName ? (
        <p className="m-0 text-sm" data-testid="fees-plan">{t('sc.fees.yourPlan', { plan: f.planName })} <span className="text-muted">{t('sc.fees.precedence')}</span></p>
      ) : null}
      <Card title="Commission by category" actions={<span className="text-xs text-muted">Fee schedule v{cur.version} · since {shortDate(cur.effectiveFrom)}</span>}>
        <DataTable<SellerFeeCategory> id="seller.fees" label="Commission by category" columns={columns} data={cur.categories} getRowId={(c) => c.category} rowLabel={(c) => c.label}
          state={state} mode="client" fetching={q.isFetching} empty="No categories." onExport={exportDataset} pollExport={pollExport} />
        <p className="m-0 text-xs text-muted">Commission is charged on item prices (not shipping or tax), rounded to the cent per line.</p>
      </Card>
      {f.connectionFees ? <ConnectionFeesCard card={f.connectionFees} t={t} /> : null}
      {f.negotiated.length ? (
        <Card title={t('sc.fees.negotiatedTitle')}>
          <p className="m-0 text-sm text-muted">{t('sc.fees.negotiatedHelp')}</p>
          <NegotiatedList rates={f.negotiated} t={t} />
        </Card>
      ) : null}
      <Card title="Fees">
        <FeeTiles v={cur} />
      </Card>
      {next ? (
        <Card title={`Upcoming: fee schedule v${next.version}`} actions={<span className="text-xs text-muted">from {shortDate(next.effectiveFrom)}</span>}>
          <FeeTiles v={next} />
        </Card>
      ) : null}
    </ModulePage>
  );
}
