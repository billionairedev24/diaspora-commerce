'use client';

import { staffTaxQueries, TAX_SHADOW_STATUSES, type TaxShadow, type TaxShadowGroup, type TaxShadowStatus } from '@afrotransact/api-client';
import { type TableState, useTableState } from '@afrotransact/data-table';
import { Checklist, Select, StatStrip, TextField, Toolbar } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

import { mono, RunbookLink, Section } from './shared';
import { TaxShadowLines, TaxShadowProviders } from './TaxShadowLines';

const pct = (n: number) => `${n.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;

/**
 * Shadow report: a provider’s quote against ours on real orders (never charged). Says plainly whether it is ready to cut
 * over, where the two disagree (by category, outcome and state), and shows sample lines with both quotes.
 */
export function TaxShadowPanel() {
  const api = staffApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const from = params.get('shadow.from') ?? '';
  const to = params.get('shadow.to') ?? '';
  const provider = params.get('shadow.provider') ?? '';
  const statusParam = params.get('shadow.status') ?? 'all';
  const status: TaxShadowStatus = (TAX_SHADOW_STATUSES as readonly string[]).includes(statusParam) ? (statusParam as TaxShadowStatus) : 'all';
  const table = useTableState({ searchParams: params, prefix: 'shadowlines', defaultPageSize: 10 });
  const order = from && to && from > to ? 'The end date must be after the start.' : undefined;
  const q = useQuery({
    ...staffTaxQueries.shadow(api.tax, { from: order ? undefined : from || undefined, to: order ? undefined : to || undefined, provider: provider || undefined, status, search: table.q || undefined, page: table.pagination.pageIndex, size: table.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(`shadow.${key}`, value);
    else next.delete(`shadow.${key}`);
    next.delete('shadowlines.page');
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const d = q.data;
  return (
    <div className="flex flex-col gap-6">
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        A provider’s tax quote next to ours on real orders. The provider’s figure is logged and compared; it is never charged. Use this to decide whether a provider can take over, following{' '}
        <RunbookLink path={d.runbookPath || 'docs/runbooks/tax-provider-cutover.md'}>the cutover runbook</RunbookLink>
        .
      </p>
      <Toolbar>
        <TextField label="From" type="date" value={from || d.from} max={to || d.to} onChange={(e) => set('from', e.currentTarget.value)} />
        <TextField label="To" type="date" value={to || d.to} min={from || d.from} error={order} onChange={(e) => set('to', e.currentTarget.value)} />
        <Select label="Provider" value={provider || d.provider} options={[{ value: 'STRIPE', label: 'Stripe Tax' }, { value: 'AVALARA', label: 'Avalara' }]} placeholder={d.shadowOff ? 'Choose a provider' : undefined} onChange={(e) => set('provider', e.currentTarget.value)} />
      </Toolbar>
      {d.shadowOff ? (
        <Notice tone="info" title="Shadow mode is off">
          {d.message || 'Turn it on in Who calculates tax to compare a provider with our own rules on real orders.'}
        </Notice>
      ) : (
        <Report d={d} table={table} status={status} onStatus={(v) => set('status', v === 'all' ? '' : v)} loading={q.isPending} fetching={q.isFetching} />
      )}
    </div>
  );
}

function Report({ d, table, status, onStatus, loading, fetching }: { d: TaxShadow; table: TableState; status: TaxShadowStatus; onStatus: (s: TaxShadowStatus) => void; loading: boolean; fetching: boolean }) {
  const r = d.readiness;
  return (
    <>
      {r ? (
        <section aria-label="Ready to cut over?" className="flex flex-col gap-3">
          <Notice tone={r.ready ? 'success' : 'warning'} title={r.ready ? 'Ready to cut over' : 'Not ready to cut over'}>
            {r.headline}
          </Notice>
          <Checklist label="Cutover checks" items={r.checks.map((c) => ({ key: c.key, title: c.title, ok: c.ok, detail: c.detail }))} />
          <p className="m-0 text-xs text-muted">
            {r.thresholdSource === 'DEFAULT' ? 'These thresholds are placeholders until the tax adviser sets the real ones.' : 'Thresholds come from the tax service.'} {r.rateNote}
          </p>
        </section>
      ) : null}
      <TaxShadowProviders d={d} />
      <StatStrip
        label={`${d.providerLabel} against our rules, ${d.from} to ${d.to}`}
        items={[
          { label: 'Lines compared', value: d.linesCompared.toLocaleString() },
          { label: 'Lines that differ', value: d.linesDiffering.toLocaleString(), sub: pct(d.differingPct), tone: d.linesDiffering > 0 ? 'attention' : 'positive' },
          { label: 'Difference in tax', value: d.absoluteDifferenceText || '$0.00', sub: d.netDifferenceText ? `net ${d.netDifferenceText} (provider minus ours)` : undefined },
          ...d.kinds.filter((k) => k.count > 0).map((k) => ({ label: k.label, value: k.count.toLocaleString() }))
        ]}
      />
      {d.partial ? <p className="m-0 text-xs text-muted">There are more differences than this report reads; the tallies by kind cover the first 1,000.</p> : null}
      <Groups title="By category" rows={d.byCategory} />
      <Groups title="By outcome" rows={d.byOutcome} />
      {d.byState.length > 0 ? <Groups title="By state" rows={d.byState} /> : null}
      <Section title="Outcomes that differ">
        {d.byCode.filter((c) => c.differing > 0).length === 0 ? (
          <p className="m-0 text-sm text-muted">No outcome differs.</p>
        ) : (
          <table className="w-full border-collapse text-sm">
            <caption className="at-visually-hidden">Outcomes where the provider and our rules differ</caption>
            <thead>
              <tr className="text-left text-muted">
                <th scope="col" className="py-1 pe-3 font-normal">Outcome</th>
                <th scope="col" className="py-1 pe-3 font-normal">Provider code</th>
                <th scope="col" className="py-1 pe-3 text-right font-normal">Lines</th>
                <th scope="col" className="py-1 text-right font-normal">Differ</th>
              </tr>
            </thead>
            <tbody>
              {d.byCode
                .filter((c) => c.differing > 0)
                .map((c) => (
                  <tr key={c.internalTaxCode} className="border-t border-border align-top">
                    <th scope="row" className="py-1.5 pe-3 text-left font-normal">
                      <span className="block font-semibold">{c.categoryName}</span>
                      <span className={`${mono} text-muted`}>{c.internalTaxCode}</span>
                    </th>
                    <td className={`${mono} py-1.5 pe-3`}>{c.providerCode}</td>
                    <td className="py-1.5 pe-3 text-right tabular-nums">{c.lines}</td>
                    <td className="py-1.5 text-right tabular-nums">
                      {c.differing} ({pct(c.differingPct)})
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        )}
      </Section>
      <TaxShadowLines d={d} state={table} status={status} onStatus={onStatus} loading={loading} fetching={fetching} />
    </>
  );
}

function Groups({ title, rows }: { title: string; rows: TaxShadowGroup[] }) {
  return (
    <Section title={title}>
      {rows.length === 0 ? (
        <p className="m-0 text-sm text-muted">Nothing to compare yet.</p>
      ) : (
        <table className="w-full border-collapse text-sm">
          <caption className="at-visually-hidden">{title}: how often the provider and our rules differ</caption>
          <thead>
            <tr className="text-left text-muted">
              <th scope="col" className="py-1 pe-3 font-normal">{title.replace('By ', '').replace(/^./, (c) => c.toUpperCase())}</th>
              <th scope="col" className="py-1 pe-3 text-right font-normal">Lines</th>
              <th scope="col" className="py-1 pe-3 text-right font-normal">Differ</th>
              <th scope="col" className="py-1 font-normal">Share that differs</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((g) => (
              <tr key={g.key} className="border-t border-border">
                <th scope="row" className="py-1.5 pe-3 text-left font-semibold">{g.label}</th>
                <td className="py-1.5 pe-3 text-right tabular-nums">{g.lines ?? '—'}</td>
                <td className="py-1.5 pe-3 text-right tabular-nums">{g.differing}</td>
                <td className="py-1.5">
                  <span className="flex items-center gap-2">
                    <span aria-hidden className="h-1.5 w-24 overflow-hidden rounded-pill bg-surface-sunken">
                      <span className="block h-full bg-warning" style={{ width: `${Math.min(100, g.differingPct ?? 0)}%` }} />
                    </span>
                    {pct(g.differingPct ?? 0)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  );
}
