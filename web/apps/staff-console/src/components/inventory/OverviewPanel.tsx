'use client';

import { inventoryHubQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { humanize, money } from '@/lib/format';

import { useHub } from './shared';

/** Overview: value and units by lot status, what is inbound, per-FC figures and what needs attention. */
export function OverviewPanel() {
  const t = useT();
  const api = useHub();
  const q = useQuery(inventoryHubQueries.overview(api));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const o = q.data;
  // Stock is received in the currency paid and valued in US dollars: totals are USD, the amounts paid sit alongside.
  const rows = (...statuses: string[]) => o.byStatus.filter((x) => statuses.includes(x.status));
  const usd = (...statuses: string[]) => money(rows(...statuses).reduce((a, x) => a + x.valueUsdMinor, 0));
  const units = (...statuses: string[]) => rows(...statuses).reduce((a, x) => a + x.units, 0);
  const lotCount = (...statuses: string[]) => rows(...statuses).reduce((a, x) => a + x.lots, 0);
  const paid = (...statuses: string[]) => {
    const foreign = rows(...statuses).filter((x) => x.currency !== 'USD' && x.units > 0);
    return foreign.length ? ` · ${t('st.overview.paidIn', { list: foreign.map((x) => money(x.valueMinor, x.currency)).join(', ') })}` : '';
  };
  const kpis: [string, string, string][] = [
    ['Sellable stock', usd('SELLABLE'), `${units('SELLABLE')} units · ${lotCount('SELLABLE')} lots${paid('SELLABLE')}`],
    ['Quarantine & damaged', usd('QUARANTINE', 'DAMAGED'), `${units('QUARANTINE', 'DAMAGED')} units held${paid('QUARANTINE', 'DAMAGED')}`],
    ['Expired (to write off)', usd('EXPIRED'), `${units('EXPIRED')} units${paid('EXPIRED')}`],
    ['Inbound', String(o.inbound?.openShipments ?? 0), `${o.inbound?.expectedUnits ?? 0} units expected · ${o.inbound?.withDiscrepancies ?? 0} with discrepancies`],
    ['Held by checkouts', String(o.reservations?.heldUnits ?? 0), `${o.reservations?.held ?? 0} reservations`]
  ];
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3">
        {kpis.map(([label, value, detail]) => (
          <div key={label} className="flex flex-col gap-1 rounded-md border border-border bg-surface p-4">
            <span className="text-xs font-semibold text-muted">{label}</span>
            <strong className="text-2xl">{value}</strong>
            <span className="text-xs text-muted">{detail}</span>
          </div>
        ))}
      </div>
      <Card title="Fulfilment centres">
        <div className="overflow-x-auto">
          <table className="at-table w-full text-sm" aria-label="Fulfilment centres">
            <thead className="bg-surface-muted text-left text-xs text-muted">
              <tr>
                {['FC', 'Lots', 'Units', 'Sellable', 'Held', 'Reserved', 'Inbound', t('st.overview.valueUsd')].map((h) => (
                  <th key={h} scope="col" className="px-3 py-2">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {o.nodes.map((n) => (
                <tr key={n.nodeId} className="border-t border-border">
                  <td className="px-3 py-2 font-semibold">{n.name ?? n.nodeId}</td>
                  <td className="px-3 py-2">{n.lots}</td>
                  <td className="px-3 py-2">{n.units}</td>
                  <td className="px-3 py-2">{n.sellableUnits}</td>
                  <td className="px-3 py-2">{n.blockedUnits}</td>
                  <td className="px-3 py-2">{n.reserved}</td>
                  <td className="px-3 py-2">{n.inbound}</td>
                  <td className="px-3 py-2">
                    {money(n.valueMinor, n.currency ?? 'USD')}
                    {n.byCurrency.some((c) => c.currency !== 'USD') ? (
                      <span className="block text-xs text-muted">{n.byCurrency.map((c) => money(c.valueMinor, c.currency)).join(' + ')}</span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      <Card title="Needs attention">
        {o.alerts.length === 0 ? <p className="m-0 text-sm text-muted">Nothing needs attention.</p> : null}
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {o.alerts.map((a, i) => (
            <li key={`${a.type}-${a.lotId ?? a.shipmentId ?? i}`} className="flex flex-wrap items-center gap-2 text-sm">
              <StatusPill tone={a.severity === 'HIGH' ? 'bad' : 'warn'}>{humanize(a.type)}</StatusPill>
              <span className="flex-1">{a.message}</span>
              {a.lotId ? <Link className="inline-flex text-link pointer-coarse:min-h-11 pointer-coarse:items-center" href={`/inventory?tab=stock&lot=${a.lotId}`}>Open lot</Link> : null}
              {a.shipmentId ? <Link className="inline-flex text-link pointer-coarse:min-h-11 pointer-coarse:items-center" href={`/inventory?tab=inbound&id=${a.shipmentId}`}>Open shipment</Link> : null}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
