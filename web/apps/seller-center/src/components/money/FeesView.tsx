'use client';

import { applyBps, formatBps, type SellerFeeCategory, type SellerFeeVersion, sellerQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

const th = 'px-3 py-2 text-left text-xs font-semibold text-muted';
const td = 'px-3 py-2 text-sm';
const EXAMPLE_SALE = 10_000;

const SOURCE: Record<SellerFeeCategory['source'], string> = {
  OVERRIDE: 'Your negotiated rate',
  CATEGORY: 'Category rate',
  DEFAULT: 'Standard rate',
  RETAIL: 'No commission'
};

function FeeTiles({ v }: { v: SellerFeeVersion }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
      {[
        ['Card fee estimate', `${formatBps(v.cardPctBps)} + ${money(v.cardFixedMinor)}`, 'Per charge, until Stripe reports the actual fee'],
        ['FBA fee', `${money(v.fbaPerUnitMinor)} / unit`, 'Pick, pack and ship for fulfilled-by-AfroTransact orders'],
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
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.fees(sellerApi(), orgId));
  if (q.isPending) return <ModulePage module="fees" readOnlyNote={false}><Spinner /></ModulePage>;
  if (q.isError) return <ModulePage module="fees" readOnlyNote={false}><ErrorState error={q.error} onRetry={() => void q.refetch()} /></ModulePage>;
  const f = q.data;
  const cur = f.current;
  const next = f.upcoming;
  const nextBy = new Map((next?.categories ?? []).map((c) => [c.category, c]));
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
      <Card title="Commission by category" actions={<span className="text-xs text-muted">Fee schedule v{cur.version} · since {shortDate(cur.effectiveFrom)}</span>}>
                  <table className="w-full border-collapse" aria-label="Commission by category">
            <thead>
              <tr className="border-b border-border">
                <th className={th}>Category</th>
                <th className={th}>Commission</th>
                <th className={th}>On a {money(EXAMPLE_SALE)} sale</th>
                <th className={th}>Rate</th>
                {next ? <th className={th}>From {shortDate(next.effectiveFrom)}</th> : null}
              </tr>
            </thead>
            <tbody>
              {cur.categories.map((c) => {
                const n = nextBy.get(c.category);
                return (
                  <tr key={c.category} className="border-b border-border">
                    <td className={`${td} font-semibold`}>{c.label}</td>
                    <td className={td}>{formatBps(c.commissionBps)}</td>
                    <td className={td}>{money(applyBps(EXAMPLE_SALE, c.commissionBps))}</td>
                    <td className={td}>
                      <StatusPill tone={c.source === 'OVERRIDE' ? 'good' : 'info'}>{SOURCE[c.source]}</StatusPill>
                    </td>
                    {next ? (
                      <td className={`${td} ${n && n.commissionBps !== c.commissionBps ? 'font-semibold' : 'text-muted'}`}>
                        {n ? (n.commissionBps === c.commissionBps ? 'No change' : formatBps(n.commissionBps)) : formatBps(next.defaultCommissionBps)}
                      </td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
        <p className="m-0 text-xs text-muted">Commission is charged on item prices (not shipping or tax), rounded to the cent per line.</p>
      </Card>
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
