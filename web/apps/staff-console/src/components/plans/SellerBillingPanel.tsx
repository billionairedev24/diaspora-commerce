'use client';

import {
  type StaffBillingGrant,
  type StaffBillingInvoice,
  type StaffEarningsCollection,
  type StaffPlanFeeCollection,
  type StaffPlanFeeMode,
  staffPlanKeys,
  staffPlanQueries
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useEffect, useMemo, useState } from 'react';

import { NegotiatedRatesPanel } from '@/components/fees/NegotiatedRatesPanel';
import { NegotiatePlanDialog } from '@/components/fees/NegotiatePlanDialog';
import { Card } from '@/components/ui/Card';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime, humanize, idempotencyKey, money, shortDate } from '@/lib/format';

import { grantColumns } from './GrantsPanel';
import { centsOf, errorsOf, intOf, plansApi, ReasonFields, STATUS_TONE, type T, usePlanRights } from './kit';

type Action = { kind: 'credit' } | { kind: 'freeDays' } | { kind: 'trial' } | { kind: 'waive'; invoice: StaffBillingInvoice };

/** One adjustment, audited with a reason code; the Idempotency-Key is kept for retries of the same click. */
function AdjustDialog({ orgId, action, onClose, t }: { orgId: string; action: Action | null; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [key] = useState(idempotencyKey);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const apply = useMutation({
    mutationFn: (): Promise<StaffBillingGrant> => {
      const adj = { reasonCode: reason, note: note.trim() || null };
      const api = plansApi();
      if (!action) throw new Error('no action');
      if (action.kind === 'waive') return api.waiveInvoice(orgId, action.invoice.id, adj, key);
      const n = action.kind === 'credit' ? centsOf(amount) : intOf(amount);
      if (n === null || Number.isNaN(n)) throw new Error(action.kind === 'credit' ? t('st.plans.badAmount') : t('st.plans.badDays'));
      if (action.kind === 'credit') return api.credit(orgId, n, adj, key);
      return action.kind === 'freeDays' ? api.freeDays(orgId, n, adj, key) : api.overrideTrial(orgId, n, adj, key);
    },
    onSuccess: (g) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.panel(orgId) });
      void qc.invalidateQueries({ queryKey: staffPlanKeys.grantLists() });
      toast.show(g.status === 'APPLIED' ? t('st.plans.applied', { summary: g.summary }) : t('st.plans.recordedRetry', { summary: g.summary }));
      onClose();
    },
    onError: (e) => setErrors(e instanceof Error && !('status' in e) ? { _: e.message } : errorsOf(e))
  });
  const title = !action ? '' : action.kind === 'credit' ? t('st.plans.addCredit') : action.kind === 'freeDays' ? t('st.plans.giveFreeDays') : action.kind === 'trial' ? t('st.plans.changeTrial')
    : t('st.plans.waiveTitle', { number: action.invoice.number ?? action.invoice.id });
  const help = !action ? '' : t(`st.plans.adjustHelp.${action.kind}`);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    apply.mutate();
  };
  return (
    <Dialog open={!!action} onOpenChange={(o) => !o && onClose()} title={title} description={help} width={480} closeLabel={t('st.plans.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-2">
        {action && action.kind !== 'waive' ? (
          <TextField id="adj-amount" label={action.kind === 'credit' ? t('st.plans.creditDollars') : action.kind === 'trial' ? t('st.plans.trialLength') : t('st.plans.freeDays')} inputMode="decimal"
            value={amount} error={errors.amountCents ?? errors.days} onChange={(e) => setAmount(e.currentTarget.value)} />
        ) : null}
        <ReasonFields idPrefix="adj" reason={reason} note={note} onReason={setReason} onNote={setNote} errors={errors} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button type="submit" loading={apply.isPending}>{t('st.plans.apply')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

/**
 * Per-seller override of how the plan is paid (S298), audited with a reason code; applies from the seller's next
 * invoice.
 */
function PlanFeeDialog({ orgId, orgName, current, open, onClose, t }: {
  orgId: string;
  orgName: string;
  current: StaffPlanFeeCollection;
  open: boolean;
  onClose: () => void;
  t: T;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [mode, setMode] = useState<StaffPlanFeeMode>(current.mode === 'CARD' ? 'EARNINGS_FIRST' : 'CARD');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => plansApi().overridePlanFeeCollection(orgId, mode, { reasonCode: reason, note: note.trim() || null }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.panel(orgId) });
      toast.show(t('st.plans.planFee.saved'));
      onClose();
    },
    onError: (e) => setErrors(errorsOf(e))
  });
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={t('st.plans.planFee.overrideTitle', { org: orgName })} description={t('st.plans.planFee.overrideHelp')}
      width={480} closeLabel={t('st.plans.close')}>
      <form noValidate className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); setErrors({}); save.mutate(); }}>
        <Select id="pfc-mode" label={t('st.plans.planFee.mode')} value={mode} error={errors.mode} onChange={(e) => setMode(e.currentTarget.value as StaffPlanFeeMode)}
          options={(['EARNINGS_FIRST', 'CARD'] as const).map((m) => ({ value: m, label: t(`st.plans.planFee.${m}`) }))} />
        <ReasonFields idPrefix="pfc" reason={reason} note={note} onReason={setReason} onNote={setNote} errors={errors} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button type="submit" loading={save.isPending}>{t('st.plans.apply')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

/** How the seller pays its plan, who chose it, from which invoice a change applies, and the audited change log. */
function PlanFeeSummary({ v, adjust, onChange, t }: { v: StaffPlanFeeCollection; adjust: boolean; onChange: () => void; t: T }) {
  return (
    <div className="flex flex-col gap-2" data-testid="panel-plan-fees">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <strong>{t(`st.plans.planFee.${v.mode}`)}</strong>
        <span className="text-muted">· {t(`st.plans.planFee.source.${v.source}`)}</span>
        {adjust && v.available ? <Button size="sm" variant="link" onClick={onChange}>{t('st.plans.planFee.change')}</Button> : null}
      </div>
      {!v.available ? <p className="m-0 text-xs text-muted">{t('st.plans.planFee.offPlatform')}</p>
        : v.appliesFromInvoiceAt ? <p className="m-0 text-xs text-muted">{t('st.plans.planFee.appliesFrom', { date: shortDate(v.appliesFromInvoiceAt) })}</p> : null}
      {v.history.length ? (
        <ul aria-label={t('st.plans.planFee.history')} className="m-0 flex list-none flex-col gap-1 p-0 text-xs text-muted">
          {v.history.map((h) => (
            <li key={h.id}>
              {t('st.plans.planFee.historyLine', { date: dateTime(h.createdAt), mode: t(`st.plans.planFee.${h.mode}`), who: h.actorId })}
              {h.reasonCode ? ` · ${humanize(h.reasonCode)}` : ''}{h.note ? ` — ${h.note}` : ''}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function OrgFinder({ onPick, t }: { onPick: (orgId: string) => void; t: T }) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const h = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(h);
  }, [text]);
  const hits = useQuery(staffPlanQueries.orgs(plansApi(), q));
  return (
    <div className="flex flex-col gap-2">
      <label className="flex h-10 max-w-xl items-center gap-2 rounded-pill border border-border-strong bg-surface px-3">
        <MagnifyingGlass size={16} aria-hidden className="text-muted" />
        <span className="at-visually-hidden">{t('st.plans.findOrg')}</span>
        <input type="search" className="min-w-0 flex-1 bg-transparent text-sm outline-none" placeholder={t('st.plans.findOrgPlaceholder')} value={text} onChange={(e) => setText(e.currentTarget.value)} />
      </label>
      {hits.isError ? <p className="m-0 text-sm text-danger">{problemMessage(hits.error)}</p> : null}
      {hits.data ? (
        hits.data.items.length ? (
          <ul aria-label={t('st.plans.orgsFound')} className="m-0 flex max-w-xl list-none flex-col gap-1 p-0">
            {hits.data.items.map((h) => (
              <li key={h.orgId}>
                <button type="button" className="flex w-full flex-wrap items-center gap-2 rounded-sm px-3 py-2 text-left hover:bg-surface-hover" onClick={() => { onPick(h.orgId); setText(''); setQ(''); }}>
                  <strong className="font-semibold">{h.name}</strong>
                  <span className="text-xs text-muted">{h.kind.toLowerCase()} · {h.orgId}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : <p className="m-0 text-sm text-muted">{t('st.plans.noOrgMatches', { q })}</p>
      ) : null}
    </div>
  );
}

/** Per-seller billing: plan, trial, credit balance, invoices and grants, with audited manual adjustments. */
export function SellerBillingPanel() {
  const t = useT();
  const params = useSearchParams();
  const { adjust, manage } = usePlanRights();
  const [planDealOpen, setPlanDealOpen] = useState(false);
  const [orgId, setOrgId] = useState(params.get('org') ?? '');
  const [action, setAction] = useState<Action | null>(null);
  const [actionSeq, setActionSeq] = useState(0);
  const [planFeeOpen, setPlanFeeOpen] = useState(false);
  const panel = useQuery(staffPlanQueries.panel(plansApi(), orgId));
  const invoiceState = useTableState({ searchParams: params, prefix: 'sinv', syncUrl: false });
  const grantState = useTableState({ searchParams: params, prefix: 'sgr', syncUrl: false });
  const collectionState = useTableState({ searchParams: params, prefix: 'scol', syncUrl: false });
  const open = (a: Action) => {
    setActionSeq((n) => n + 1);
    setAction(a);
  };
  const invoiceColumns = useMemo<ColumnDef<StaffBillingInvoice>[]>(() => [
    { id: 'number', accessorFn: (i) => i.number ?? i.id, header: t('st.plans.col.invoice'), size: 150, meta: { kind: 'mono' } },
    { id: 'createdAt', accessorFn: (i) => i.createdAt, header: t('st.plans.col.date'), size: 130, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.createdAt) },
    { id: 'amountDueCents', accessorFn: (i) => i.amountDueCents, header: t('st.plans.col.amount'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountDueCents) },
    { id: 'paidFromSalesCents', accessorFn: (i) => i.paidFromSalesCents, header: t('st.plans.col.fromSales'), size: 120, meta: { kind: 'money', align: 'end' },
      cell: ({ row }) => (row.original.paidFromSalesCents ? money(row.original.paidFromSalesCents) : '—') },
    { id: 'paidByCardCents', accessorFn: (i) => i.paidByCardCents, header: t('st.plans.col.card'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.paidByCardCents) },
    { id: 'status', accessorFn: (i) => i.status ?? '', header: t('st.plans.col.status'), size: 100, meta: { kind: 'status' },
      cell: ({ row }) => <StatusPill tone={row.original.status === 'paid' ? 'good' : row.original.status === 'open' ? 'warn' : 'brand'}>{row.original.status ?? '—'}</StatusPill> },
    { id: 'actions', header: () => <span className="at-visually-hidden">{t('st.plans.col.actions')}</span>, size: 200, enableSorting: false, meta: { export: false, label: t('st.plans.col.actions') },
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-2">
          {row.original.invoicePdf ? <a href={row.original.invoicePdf} target="_blank" rel="noreferrer">PDF</a> : null}
          {adjust && row.original.status === 'open' ? <Button size="sm" variant="link" onClick={() => open({ kind: 'waive', invoice: row.original })}>{t('st.plans.waive')}</Button> : null}
        </div>
      ) }
  ], [t, adjust]);
  const grantCols = useMemo(() => grantColumns(t, false), [t]);
  const collectionColumns = useMemo<ColumnDef<StaffEarningsCollection>[]>(() => [
    { id: 'invoice', accessorFn: (c) => c.invoiceNumber ?? c.invoiceId, header: t('st.plans.col.invoice'), size: 150, meta: { kind: 'mono' } },
    { id: 'createdAt', accessorFn: (c) => c.createdAt ?? '', header: t('st.plans.col.date'), size: 130, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.createdAt) },
    { id: 'amountCents', accessorFn: (c) => c.amountCents, header: t('st.plans.col.asked'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountCents) },
    { id: 'withheldCents', accessorFn: (c) => c.withheldCents, header: t('st.plans.col.withheld'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.withheldCents) },
    { id: 'releasedCents', accessorFn: (c) => c.releasedCents, header: t('st.plans.col.released'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.releasedCents) },
    { id: 'status', accessorFn: (c) => c.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' },
      cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
    { id: 'reason', accessorFn: (c) => c.lastError ?? c.reason ?? '', header: t('st.plans.col.detail'), size: 260,
      cell: ({ row }) => <span className="text-xs text-muted">{row.original.lastError ?? row.original.reason ?? ''}</span> }
  ], [t]);
  const p = panel.data;
  const b = p?.billing;
  return (
    <div className="flex flex-col gap-4">
      <OrgFinder onPick={setOrgId} t={t} />
      {!orgId ? <p className="m-0 text-sm text-muted">{t('st.plans.pickOrg')}</p> : panel.isPending ? <Spinner /> : panel.isError ? <ErrorState error={panel.error} onRetry={() => void panel.refetch()} /> : p ? (
        <>
          <Card title={p.orgName ?? p.orgId} actions={(adjust && b) || manage ? (
            <div className="flex flex-wrap gap-2">
              {manage ? <Button size="sm" variant="neutral" onClick={() => setPlanDealOpen(true)}>{t('st.rates.negotiatePlan')}</Button> : null}
              {adjust && b ? <Button size="sm" variant="neutral" onClick={() => open({ kind: 'credit' })}>{t('st.plans.addCredit')}</Button> : null}
              {adjust && b ? <Button size="sm" variant="neutral" onClick={() => open({ kind: 'freeDays' })}>{t('st.plans.giveFreeDays')}</Button> : null}
              {adjust && b?.status === 'trialing' ? <Button size="sm" variant="neutral" onClick={() => open({ kind: 'trial' })}>{t('st.plans.changeTrial')}</Button> : null}
            </div>
          ) : null}>
            {b ? (
              <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3 text-sm" aria-label={t('st.plans.subscription')}>
                <div><dt className="text-xs text-muted">{t('st.plans.col.plan')}</dt><dd className="m-0 font-semibold">{b.planName} · v{b.planVersion} · {humanize(b.cadence)}</dd></div>
                <div><dt className="text-xs text-muted">{t('st.plans.col.status')}</dt><dd className="m-0"><StatusPill tone={STATUS_TONE[b.status.toUpperCase()] ?? 'info'}>{b.status}</StatusPill></dd></div>
                <div><dt className="text-xs text-muted">{t('st.plans.trialEnds')}</dt><dd className="m-0">{b.trialEndsAt ? `${dateTime(b.trialEndsAt)}${b.trialSource ? ` · ${humanize(b.trialSource)}` : ''}` : '—'}</dd></div>
                <div><dt className="text-xs text-muted">{t('st.plans.nextCharge')}</dt><dd className="m-0">{b.nextChargeLabel}</dd></div>
                <div><dt className="text-xs text-muted">{t('st.plans.creditBalance')}</dt><dd className="m-0 font-semibold" data-testid="panel-credit">{money(b.creditBalanceCents)}</dd></div>
              </dl>
            ) : <p className="m-0 text-sm text-muted">{t('st.plans.noSubscription')}</p>}
          </Card>
          {p.planFeeCollection ? (
            <Card title={t('st.plans.planFee')}>
              <PlanFeeSummary v={p.planFeeCollection} adjust={adjust} onChange={() => setPlanFeeOpen(true)} t={t} />
            </Card>
          ) : null}
          <h3 className="m-0 text-md font-semibold">{t('st.rates.negotiatedForOrg')}</h3>
          <NegotiatedRatesPanel org={{ orgId: p.orgId, name: p.orgName ?? p.orgId }} />
          <h3 className="m-0 text-md font-semibold">{t('st.plans.invoices')}</h3>
          <DataTable<StaffBillingInvoice> id="staff.plans.seller-invoices" label={t('st.plans.invoices')} columns={invoiceColumns} data={p.invoices} getRowId={(i) => i.id} rowLabel={(i) => i.number ?? i.id}
            state={invoiceState} mode="client" fetching={panel.isFetching} empty={t('st.plans.noInvoices')} />
          <h3 className="m-0 text-md font-semibold">{t('st.plans.collections')}</h3>
          <DataTable<StaffEarningsCollection> id="staff.plans.seller-collections" label={t('st.plans.collections')} columns={collectionColumns} data={p.collections}
            getRowId={(c) => c.invoiceId} rowLabel={(c) => c.invoiceNumber ?? c.invoiceId} state={collectionState} mode="client" fetching={panel.isFetching}
            empty={t('st.plans.noCollections')} />
          <h3 className="m-0 text-md font-semibold">{t('st.plans.grantsForOrg')}</h3>
          <DataTable<StaffBillingGrant> id="staff.plans.seller-grants" label={t('st.plans.grantsForOrg')} columns={grantCols} data={p.grants} getRowId={(g) => g.id} rowLabel={(g) => g.summary}
            state={grantState} mode="client" fetching={panel.isFetching} empty={t('st.plans.noGrants')} />
        </>
      ) : null}
      {p && planDealOpen ? (
        <NegotiatePlanDialog orgId={p.orgId} orgName={p.orgName ?? p.orgId} kind={p.kind} open={planDealOpen} onClose={() => setPlanDealOpen(false)} t={t} />
      ) : null}
      <AdjustDialog key={actionSeq} orgId={orgId} action={action} onClose={() => setAction(null)} t={t} />
      {p?.planFeeCollection && planFeeOpen ? (
        <PlanFeeDialog orgId={orgId} orgName={p.orgName ?? p.orgId} current={p.planFeeCollection} open={planFeeOpen} onClose={() => setPlanFeeOpen(false)} t={t} />
      ) : null}
    </div>
  );
}
