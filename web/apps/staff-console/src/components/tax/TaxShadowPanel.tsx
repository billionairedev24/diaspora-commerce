'use client';

import { staffTaxQueries, type TaxShadow, type TaxShadowGroup, type TaxShadowRow } from '@afrotransact/api-client';
import { Checklist, Select, StatStrip, TextField } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { mono, RunbookLink, Section } from './shared';

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
  const page = Number(params.get('shadow.page') ?? 0) || 0;
  const order = from && to && from > to ? 'The end date must be after the start.' : undefined;
  const q = useQuery({
    ...staffTaxQueries.shadow(api.tax, { from: order ? undefined : from || undefined, to: order ? undefined : to || undefined, provider: provider || undefined, page, size: 10 }),
    placeholderData: keepPreviousData
  });
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(`shadow.${key}`, value);
    else next.delete(`shadow.${key}`);
    next.delete('shadow.page');
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  const goPage = (n: number) => {
    const next = new URLSearchParams(params.toString());
    next.set('shadow.page', String(n));
    router.replace(`${pathname}?${next.toString()}`, { scroll: false });
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
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <TextField label="From" type="date" value={from || d.from} max={to || d.to} onChange={(e) => set('from', e.currentTarget.value)} />
        <TextField label="To" type="date" value={to || d.to} min={from || d.from} error={order} onChange={(e) => set('to', e.currentTarget.value)} />
        <Select label="Provider" value={provider || d.provider} options={[{ value: 'STRIPE', label: 'Stripe Tax' }, { value: 'AVALARA', label: 'Avalara' }]} placeholder={d.shadowOff ? 'Choose a provider' : undefined} onChange={(e) => set('provider', e.currentTarget.value)} />
      </div>
      {d.shadowOff ? (
        <Notice tone="info" title="Shadow mode is off">
          {d.message || 'Turn it on in Who calculates tax to compare a provider with our own rules on real orders.'}
        </Notice>
      ) : (
        <Report d={d} page={page} onPage={goPage} />
      )}
    </div>
  );
}

function Report({ d, page, onPage }: { d: TaxShadow; page: number; onPage: (n: number) => void }) {
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
      <Samples rows={d.items} total={d.total} page={page} size={d.size} onPage={onPage} />
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

function Samples({ rows, total, page, size, onPage }: { rows: TaxShadowRow[]; total: number; page: number; size: number; onPage: (n: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <Section title="Sample lines with both quotes">
      {rows.length === 0 ? (
        <p className="m-0 text-sm text-muted">No lines differ in this period.</p>
      ) : (
        <>
          <table className="w-full border-collapse text-sm">
            <caption className="at-visually-hidden">Order lines where the two quotes differ</caption>
            <thead>
              <tr className="text-left text-muted">
                <th scope="col" className="py-1 pe-3 font-normal">Order</th>
                <th scope="col" className="py-1 pe-3 font-normal">What differs</th>
                <th scope="col" className="py-1 pe-3 font-normal">Our quote</th>
                <th scope="col" className="py-1 font-normal">Provider’s quote</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.orderId}-${r.lineId}`} className="border-t border-border align-top">
                  <th scope="row" className="py-1.5 pe-3 text-left font-normal">
                    <span className="block font-semibold">{r.orderNumber || 'Order'}</span>
                    <span className="text-xs text-muted">{r.quotedAt ? dateTime(r.quotedAt) : ''}</span>
                  </th>
                  <td className="py-1.5 pe-3">
                    {r.kindLabel}
                    <span className="block text-xs text-muted">{r.categoryName} · {r.differenceText}</span>
                  </td>
                  <td className="py-1.5 pe-3">{r.inHouseText}</td>
                  <td className="py-1.5">{r.providerText}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 ? (
            <nav aria-label="Sample lines pages" className="flex items-center gap-3 text-sm">
              <button type="button" className="underline disabled:no-underline disabled:opacity-50" disabled={page <= 0} onClick={() => onPage(page - 1)}>
                Previous
              </button>
              <span>
                Page {page + 1} of {pages}
              </span>
              <button type="button" className="underline disabled:no-underline disabled:opacity-50" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
                Next
              </button>
            </nav>
          ) : null}
        </>
      )}
    </Section>
  );
}
