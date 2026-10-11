'use client';

import { inventoryHubQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { humanize, money } from '@/lib/format';

import { useHub } from './shared';

/** Overview: value and units by lot status, what is inbound, per-FC figures and what needs attention. */
export function OverviewPanel() {
  const api = useHub();
  const q = useQuery(inventoryHubQueries.overview(api));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const o = q.data;
  const by = (s: string) => o.byStatus.find((x) => x.status === s);
  const kpis: [string, string, string][] = [
    ['Sellable stock', money(by('SELLABLE')?.valueMinor ?? 0), `${by('SELLABLE')?.units ?? 0} units · ${by('SELLABLE')?.lots ?? 0} lots`],
    ['Quarantine & damaged', money((by('QUARANTINE')?.valueMinor ?? 0) + (by('DAMAGED')?.valueMinor ?? 0)), `${(by('QUARANTINE')?.units ?? 0) + (by('DAMAGED')?.units ?? 0)} units held`],
    ['Expired (to write off)', money(by('EXPIRED')?.valueMinor ?? 0), `${by('EXPIRED')?.units ?? 0} units`],
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
                {['FC', 'Lots', 'Units', 'Sellable', 'Held', 'Reserved', 'Inbound', 'Value'].map((h) => (
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
                  <td className="px-3 py-2">{money(n.valueMinor, n.currency ?? 'USD')}</td>
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
              {a.lotId ? <Link className="text-link" href={`/inventory?tab=stock&lot=${a.lotId}`}>Open lot</Link> : null}
              {a.shipmentId ? <Link className="text-link" href={`/inventory?tab=inbound&id=${a.shipmentId}`}>Open shipment</Link> : null}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
