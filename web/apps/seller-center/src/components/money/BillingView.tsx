'use client';

import { type BillingPaymentMethod, type Cadence, type Invoice, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Badge, Button, ChipGroup, cx, useToast } from '@afrotransact/ui';
import { Bank, CheckCircle, CreditCard } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

const BRAND: Record<string, string> = { visa: 'Visa', mastercard: 'Mastercard', amex: 'American Express', discover: 'Discover' };
const CADENCES: { value: Cadence; label: string }[] = [
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly · save 5%' },
  { value: 'ANNUAL', label: 'Annually · 2 months free' }
];

function MethodCard({ m, canManage, onDefault, onRemove, busy }: { m: BillingPaymentMethod; canManage: boolean; onDefault: () => void; onRemove: () => void; busy: boolean }) {
  const card = m.kind === 'card';
  return (
    <div className={cx('flex min-w-0 flex-col gap-3 rounded-lg border p-4', m.isDefault ? 'border-gold-500 bg-gold-50' : 'border-border')}>
      <div className="flex items-center gap-3">
        <span aria-hidden className="grid size-10 place-items-center rounded-md bg-ink-1000 text-gold-500">
          {card ? <CreditCard size={22} /> : <Bank size={22} />}
        </span>
        <span className="flex min-w-0 flex-1 flex-col">
          <strong>{card ? (BRAND[m.brand ?? ''] ?? 'Card') : (m.bankName ?? 'Bank account')}</strong>
          <span className="text-sm text-muted">
            {card ? `•••• ${m.last4} · expires ${String(m.expMonth ?? '').padStart(2, '0')}/${String(m.expYear ?? '').slice(-2)}` : `${m.accountType ?? 'Account'} •••• ${m.last4}`}
          </span>
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 text-xs">
        {m.isDefault ? <Badge tone="brand">Default</Badge> : null}
        {m.verified ? (
          <span className="flex items-center gap-1 text-success">
            <CheckCircle size={14} weight="fill" aria-hidden /> Verified
          </span>
        ) : (
          <span className="text-warning">Verification pending</span>
        )}
        {m.receivesPayouts ? <span className="text-muted">Receives payouts</span> : null}
      </div>
      {canManage && !m.isDefault ? (
        <div className="flex gap-2">
          <Button size="sm" variant="neutral" disabled={busy} onClick={onDefault}>Make default</Button>
          <Button size="sm" variant="link" disabled={busy} onClick={onRemove}>Remove</Button>
        </div>
      ) : null}
    </div>
  );
}

const invoiceColumns: ColumnDef<Invoice>[] = [
  { id: 'number', accessorFn: (i) => i.number, header: 'Invoice', size: 160, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.number}</span> },
  { id: 'periodStart', accessorFn: (i) => i.periodStart, header: 'Period', size: 240, meta: { kind: 'date', exportValue: (i) => `${i.periodStart} – ${i.periodEnd}` }, cell: ({ row }) => `${shortDate(row.original.periodStart)} – ${shortDate(row.original.periodEnd)}` },
  { id: 'amountDueMinor', accessorFn: (i) => i.amountDueMinor, header: 'Amount', size: 130, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountDueMinor, row.original.currency) },
  { id: 'status', accessorFn: (i) => i.status, header: 'Status', size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.status.toLowerCase()}</StatusPill> },
  { id: 'pdf', header: () => <span className="at-visually-hidden">Download</span>, size: 150, enableSorting: false, meta: { export: false, label: 'Download' }, cell: ({ row }) => (row.original.pdfUrl ? <a href={row.original.pdfUrl} target="_blank" rel="noreferrer">Download PDF</a> : null) }
];

/** Plan & billing: plan and cadence (monthly, quarterly, annual), cards and bank accounts, invoices. Owner-only changes. */
export function BillingView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.billing(api, orgId));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'invoices' });
  const [cadence, setCadence] = useState<Cadence | ''>('');
  const plan = useMutation({
    mutationFn: (v: { code: string; cadence: Cadence }) => api.changePlan(v.code, v.cadence),
    onSuccess: (r) => {
      qc.setQueryData(sellerKeys.billing(orgId), r.billing);
      toast.show(r.applied === 'IMMEDIATE_UPGRADE' ? 'Upgraded — the difference is prorated today' : r.applied === 'AT_RENEWAL' ? 'Change scheduled for your next renewal' : 'That’s already your plan');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const pm = useMutation({
    mutationFn: (v: { id: string; op: 'default' | 'remove' }) => (v.op === 'default' ? api.makeDefaultPaymentMethod(v.id) : api.removePaymentMethod(v.id)),
    onSuccess: () => void qc.invalidateQueries({ queryKey: sellerKeys.billing(orgId) }),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  if (q.isPending) return <ModulePage module="billing"><Spinner /></ModulePage>;
  if (q.isError) return <ModulePage module="billing"><ErrorState error={q.error} onRetry={() => void q.refetch()} /></ModulePage>;
  const { subscription: sub, plans, paymentMethods, invoices, canManage } = q.data;
  const shown = cadence || sub.cadence;
  return (
    <ModulePage module="billing" readOnlyNote={false}>
      {!canManage ? <Notice tone="info" icon="eye" title="Only the owner can change billing.">You can see the plan, payment methods and invoices.</Notice> : null}
      <Card title="Your plan">
        <div className="flex flex-wrap items-end gap-x-8 gap-y-2">
          <div className="flex flex-col">
            <strong className="text-2xl">{sub.planName}</strong>
            <span className="text-sm text-muted">{sub.priceLabel} · {sub.billedLabel}</span>
          </div>
          <div className="flex flex-col text-sm">
            <span className="text-xs font-semibold text-muted">{sub.trialEndsAt ? 'Trial' : 'Next charge'}</span>
            <span>{sub.nextChargeLabel}</span>
          </div>
          <StatusPill tone={toneOf(sub.status)}>{sub.status.toLowerCase()}</StatusPill>
        </div>
        {sub.pendingChange ? (
          <p className="m-0 text-sm text-muted">
            Changing to <strong>{sub.pendingChange.planName}</strong> ({sub.pendingChange.cadence.toLowerCase()}) on {shortDate(sub.pendingChange.effectiveAt)}.
          </p>
        ) : null}
        {sub.dunning ? <Notice tone="danger" title="Payment failed.">{sub.dunning}</Notice> : null}
      </Card>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 text-lg font-semibold">Plans</h2>
        <ChipGroup label="Billing frequency" options={CADENCES} value={shown} onChange={(v) => setCadence(v)} />
      </div>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-3">
        {plans.map((p) => {
          const price = p.prices.find((x) => x.cadence === shown) ?? p.prices[0];
          const current = p.current && sub.cadence === shown;
          return (
            <div key={p.code} className={cx('flex flex-col gap-2 rounded-lg border p-4', p.current ? 'border-gold-500' : 'border-border')}>
              <span className="text-xs font-bold text-muted uppercase">{p.tagline}</span>
              <strong className="text-lg">{p.name}</strong>
              <strong className="text-2xl">{price?.perMonthLabel}</strong>
              <span className="text-xs text-muted">{price?.billedLabel}</span>
              {price?.savingLabel ? <span className="text-xs font-semibold text-success">{price.savingLabel}</span> : null}
              <ul className="m-0 flex flex-col gap-1 pl-4 text-sm">
                {p.features.map((f) => <li key={f}>{f}</li>)}
              </ul>
              {p.trialDays && !p.current ? <span className="text-xs text-muted">{p.trialDays}-day free trial</span> : null}
              <div className="mt-auto pt-2">
                {current ? (
                  <Badge tone="brand">Current plan</Badge>
                ) : canManage ? (
                  <Button size="sm" variant={p.current ? 'neutral' : 'primary'} loading={plan.isPending && plan.variables?.code === p.code} onClick={() => plan.mutate({ code: p.code, cadence: shown })}>
                    {p.current ? `Switch to ${shown.toLowerCase()}` : `Choose ${p.name}`}
                  </Button>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
      <h2 className="m-0 text-lg font-semibold">Payment methods</h2>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,280px),1fr))] gap-3">
        {paymentMethods.map((m) => (
          <MethodCard key={m.id} m={m} canManage={canManage} busy={pm.isPending} onDefault={() => pm.mutate({ id: m.id, op: 'default' })} onRemove={() => pm.mutate({ id: m.id, op: 'remove' })} />
        ))}
      </div>
      <p className="m-0 text-xs text-muted">The default method can’t be removed until another one is the default.</p>
      <h2 className="m-0 text-lg font-semibold">Invoices</h2>
      <DataTable<Invoice> id="seller.invoices" label="Invoices" columns={invoiceColumns} data={invoices} getRowId={(i) => i.id} rowLabel={(i) => i.number}
        state={state} mode="client" fetching={q.isFetching} empty="No invoices yet." onExport={exportDataset} pollExport={pollExport} />
    </ModulePage>
  );
}
