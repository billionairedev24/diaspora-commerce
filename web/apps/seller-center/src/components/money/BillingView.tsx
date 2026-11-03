'use client';

import {
  type BillingPaymentMethod,
  type BillingSetupIntent,
  type BillingView as BillingViewData,
  type Cadence,
  type ChargeGate,
  type EarnedReward,
  type Invoice,
  type Plan,
  type PlanEntitlements,
  type PlanFeeCollectionMode,
  type PlanFeeCollectionView,
  type RewardProgress,
  sellerKeys,
  sellerQueries,
  type Subscription
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Badge, Button, ChipGroup, cx, Dialog, SavedPaymentCard, TextField, useToast } from '@afrotransact/ui';
import { CheckCircle, Flask, Gift, Plus } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef, useState } from 'react';

import { PlanFeeChoice } from '@/components/money/PlanFeeChoice';
import { luhn } from '@/components/onboarding/steps/PlanStep';
import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { download } from '@/lib/export/table';
import { dollars, money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { appearance, getStripePayments, hasStripeKey, type PaymentElement, type StripePaymentElements, type StripePaymentInstance } from '@/lib/stripe';
import { exportDataset, pollExport } from '@/lib/table-export';

type T = Translator<MessageKey>;

const CADENCE_KEYS: Record<Cadence, MessageKey> = { MONTHLY: 'sc.billing.cadence.MONTHLY', QUARTERLY: 'sc.billing.cadence.QUARTERLY', ANNUAL: 'sc.billing.cadence.ANNUAL' };
const TRIAL_SOURCE_KEYS: Record<string, MessageKey> = {
  PLAN: 'sc.billing.trialSource.PLAN',
  CAMPAIGN: 'sc.billing.trialSource.CAMPAIGN',
  OVERRIDE: 'sc.billing.trialSource.OVERRIDE'
};

function MethodCard({ m, canManage, onDefault, onRemove, busy, t }: { m: BillingPaymentMethod; canManage: boolean; onDefault: () => void; onRemove: () => void; busy: boolean; t: T }) {
  const card = m.kind === 'card';
  return (
    <SavedPaymentCard
      type={card ? 'card' : 'bank'}
      brand={m.brand}
      last4={m.last4}
      expires={card && m.expMonth && m.expYear ? `${String(m.expMonth).padStart(2, '0')}/${String(m.expYear).slice(-2)}` : null}
      title={card ? undefined : (m.bankName ?? t('sc.billing.bankAccount'))}
      detail={card ? undefined : (m.accountType ?? t('sc.billing.account'))}
      isDefault={m.isDefault}
      labels={{ defaultTag: t('sc.billing.default'), expires: (date) => t('sc.billing.expires', { date }), expired: t('sc.billing.expired') }}
      status={
        <>
          {m.verified ? (
            <span className="flex items-center gap-1 text-success">
              <CheckCircle size={14} weight="fill" aria-hidden /> {t('sc.billing.verified')}
            </span>
          ) : (
            <span className="text-warning">{t('sc.billing.verificationPending')}</span>
          )}
          {m.receivesPayouts ? <span className="text-muted">{t('sc.billing.receivesPayouts')}</span> : null}
        </>
      }
      actions={canManage && !m.isDefault ? (
        <>
          <Button size="sm" variant="neutral" disabled={busy} onClick={onDefault}>{t('sc.billing.makeDefault')}</Button>
          <Button size="sm" variant="link" disabled={busy} onClick={onRemove}>{t('sc.billing.remove')}</Button>
        </>
      ) : null}
    />
  );
}

/** Plan, cadence, trial end, next charge and credit balance. */
function PlanSummary({ sub, t }: { sub: Subscription; t: T }) {
  const source = sub.trialSource ? TRIAL_SOURCE_KEYS[sub.trialSource] : undefined;
  return (
    <Card title={t('sc.billing.yourPlan')}>
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <div className="flex flex-col">
          <strong className="text-2xl">{sub.planName}</strong>
          <span className="text-sm text-muted">{sub.priceLabel} · {sub.billedLabel}</span>
          <span className="text-xs text-muted">{t('sc.billing.cadenceVersion', { cadence: t(CADENCE_KEYS[sub.cadence]), version: sub.planVersion })}</span>
        </div>
        {sub.trialEndsAt ? (
          <div className="flex flex-col text-sm">
            <span className="text-xs font-semibold text-muted">{t('sc.billing.trialEnds')}</span>
            <span>{shortDate(sub.trialEndsAt)}</span>
            {source ? <span className="text-xs text-muted">{t(source)}</span> : null}
          </div>
        ) : null}
        <div className="flex flex-col text-sm">
          <span className="text-xs font-semibold text-muted">{t('sc.billing.nextCharge')}</span>
          <span>{sub.nextChargeLabel}</span>
        </div>
        <div className="flex flex-col text-sm" data-testid="credit-balance">
          <span className="text-xs font-semibold text-muted">{t('sc.billing.creditBalance')}</span>
          <span className={cx(sub.creditBalanceMinor > 0 && 'font-semibold text-success')}>{money(sub.creditBalanceMinor)}</span>
          {sub.creditBalanceMinor > 0 ? <span className="text-xs text-muted">{t('sc.billing.creditBalanceHelp')}</span> : null}
        </div>
        <StatusPill tone={toneOf(sub.status)}>{sub.status.toLowerCase()}</StatusPill>
      </div>
      {sub.pendingChange ? (
        <p className="m-0 text-sm text-muted">
          {t('sc.billing.pendingChange', { plan: sub.pendingChange.planName, cadence: t(CADENCE_KEYS[sub.pendingChange.cadence]), date: shortDate(sub.pendingChange.effectiveAt) })}
        </p>
      ) : null}
      {sub.chargeGate ? <ChargeGateNote gate={sub.chargeGate} t={t} /> : null}
      {sub.entitlements ? <EntitlementsLine e={sub.entitlements} t={t} /> : null}
      {sub.dunning ? <Notice tone="danger" title={t('sc.billing.paymentFailed')}>{sub.dunning}</Notice> : null}
    </Card>
  );
}

/** What the plan allows: only live listings are capped (no limit on shops or users). */
export function EntitlementsLine({ e, t }: { e: PlanEntitlements; t: T }) {
  const parts = [
    e.listingsUnlimited || e.listings === null ? t('sc.billing.ent.listingsUnlimited') : t('sc.billing.ent.listings', { count: e.listings.toLocaleString('en-US') }),
    e.aiListingWriter ? t('sc.billing.ent.aiWriter') : t('sc.billing.ent.noAiWriter'),
    t(`sc.billing.ent.lola.${e.lolaTier}` as MessageKey)
  ];
  return <p className="m-0 text-sm text-muted" data-testid="entitlements">{parts.join(' · ')}</p>;
}

/** A condition AfroTransact put on the plan charges ("Charging starts after 10 bookings — you're at 4"), with progress. */
function ChargeGateNote({ gate, t }: { gate: ChargeGate; t: T }) {
  const pct = gate.threshold && gate.mode !== 'WAIVE_UNTIL' ? Math.min(100, Math.round((gate.progress / gate.threshold) * 100)) : null;
  return (
    <div className="flex flex-col gap-1.5 rounded-md bg-info-bg p-3 text-sm" data-testid="charge-gate">
      <strong>{gate.label}</strong>
      {pct !== null && !gate.metAt ? (
        <div role="progressbar" aria-label={t('sc.billing.gate.progress')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} className="h-2 overflow-hidden rounded-pill bg-surface">
          <div className="h-full rounded-pill bg-gold-500" style={{ width: `${pct}%` }} />
        </div>
      ) : null}
      {gate.metAt ? <span className="text-xs text-muted">{t('sc.billing.gate.met', { date: shortDate(gate.metAt) })}</span> : null}
    </div>
  );
}

/**
 * S299: low-sales relief (a LESS_THAN rule) has no progress to chase: a neutral note says what happens and when it is
 * checked, with the sales so far.
 */
function ReliefRow({ r, t }: { r: RewardProgress; t: T }) {
  const threshold = r.metric === 'GROSS_SALES' ? dollars(r.threshold / 100) : String(r.threshold);
  const soFar = r.metric === 'GROSS_SALES' ? money(r.value) : String(r.value);
  const what = t(`sc.billing.reliefMetric.${r.metric}` as MessageKey);
  const days = r.windowDays ?? 30;
  return (
    <li className="flex flex-col gap-1.5" data-testid="low-sales-relief">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <strong className="text-sm">{r.rewardLabel}</strong>
        <span className="text-xs text-muted">{r.conditionLabel}</span>
      </div>
      <p className="m-0 text-sm">
        {r.checkAt
          ? t('sc.billing.reliefNote', { what, days, threshold, reward: r.rewardLabel.toLowerCase(), date: shortDate(r.checkAt) })
          : t('sc.billing.reliefNoteNoDate', { what, days, threshold, reward: r.rewardLabel.toLowerCase() })}
      </p>
      <span className="text-xs text-muted">{t('sc.billing.reliefSoFar', { what, value: soFar })}</span>
    </li>
  );
}

/** One reward the organisation can earn, with its progress. */
function RewardRow({ r, t }: { r: RewardProgress; t: T }) {
  if (r.comparison === 'LESS_THAN') return <ReliefRow r={r} t={t} />;
  return (
    <li className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <strong className="text-sm">{r.rewardLabel}</strong>
        <span className="text-xs text-muted">{r.conditionLabel}{r.endsAt ? ` · ${t('sc.billing.rewardEnds', { date: shortDate(r.endsAt) })}` : ''}</span>
      </div>
      <div role="progressbar" aria-label={r.name} aria-valuemin={0} aria-valuemax={100} aria-valuenow={r.percent} aria-valuetext={r.label} className="h-2 overflow-hidden rounded-pill bg-surface-muted">
        <div className={cx('h-full rounded-pill', r.qualifies ? 'bg-success' : 'bg-gold-500')} style={{ width: `${r.percent}%` }} />
      </div>
      <span className="text-xs text-muted">{r.qualifies ? t('sc.billing.rewardQualified') : r.label}</span>
    </li>
  );
}

const earnedColumns = (t: T): ColumnDef<EarnedReward>[] => [
  { id: 'summary', accessorFn: (r) => r.summary, header: t('sc.billing.col.reward'), size: 200 },
  { id: 'reason', accessorFn: (r) => r.reason ?? '', header: t('sc.billing.col.why'), size: 220, cell: ({ row }) => row.original.reason ?? (row.original.source === 'MANUAL' ? t('sc.billing.fromAfrotransact') : '') },
  { id: 'createdAt', accessorFn: (r) => r.createdAt, header: t('sc.billing.col.date'), size: 140, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.createdAt) },
  { id: 'status', accessorFn: (r) => r.status, header: t('sc.billing.col.status'), size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.status.toLowerCase()}</StatusPill> }
];

interface InvoiceActions {
  canManage: boolean;
  busy: string | null;
  onDownload: (i: Invoice) => void;
  onRetry: (i: Invoice) => void;
}

const invoiceColumns = (t: T, a: InvoiceActions): ColumnDef<Invoice>[] => [
  { id: 'number', accessorFn: (i) => i.number, header: t('sc.billing.col.invoice'), size: 160, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.number}</span> },
  { id: 'periodStart', accessorFn: (i) => i.periodStart, header: t('sc.billing.col.period'), size: 240, meta: { kind: 'date', exportValue: (i) => `${i.periodStart} – ${i.periodEnd}` }, cell: ({ row }) => `${shortDate(row.original.periodStart)} – ${shortDate(row.original.periodEnd)}` },
  { id: 'amountDueMinor', accessorFn: (i) => i.amountDueMinor, header: t('sc.billing.col.amount'), size: 130, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountDueMinor, row.original.currency) },
  {
    // What settled the invoice, in order: credit, the seller's sales (only if they chose that), the card, a waiver.
    id: 'paidWith', accessorFn: (i) => paidWith(i, t), header: t('sc.billing.col.paidWith'), size: 300, enableSorting: false,
    cell: ({ row }) => <span className={cx('text-sm', row.original.paidFromSalesMinor > 0 && 'font-semibold')}>{paidWith(row.original, t)}</span>
  },
  { id: 'status', accessorFn: (i) => i.status, header: t('sc.billing.col.status'), size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.status.toLowerCase()}</StatusPill> },
  {
    id: 'actions', header: () => <span className="at-visually-hidden">{t('sc.billing.col.download')}</span>, size: 220, enableSorting: false, meta: { export: false, label: t('sc.billing.col.download') },
    cell: ({ row }) => (
      <span className="flex items-center gap-2">
        <Button size="sm" variant="link" disabled={a.busy === row.original.id} onClick={() => a.onDownload(row.original)}>{t('sc.billing.downloadInvoice')}</Button>
        {row.original.canRetry && a.canManage ? <Button size="sm" variant="neutral" loading={a.busy === `retry:${row.original.id}`} onClick={() => a.onRetry(row.original)}>{t('sc.billing.retryPayment')}</Button> : null}
      </span>
    )
  }
];

/** "Credit $5.00 · Your sales $90.00 · Card $21.15": what settled the invoice, in the order it is taken (S298). */
export function paidWith(i: Invoice, t: T): string {
  const parts: string[] = [];
  if (i.paidFromCreditMinor > 0) parts.push(t('sc.billing.paid.credit', { amount: money(i.paidFromCreditMinor, i.currency) }));
  if (i.paidFromSalesMinor > 0) parts.push(t('sc.billing.paid.sales', { amount: money(i.paidFromSalesMinor, i.currency) }));
  if (i.paidByCardMinor > 0) parts.push(t(i.status === 'OPEN' ? 'sc.billing.paid.cardDue' : 'sc.billing.paid.card', { amount: money(i.paidByCardMinor, i.currency) }));
  if (i.waivedMinor > 0) parts.push(t('sc.billing.paid.waived', { amount: money(i.waivedMinor, i.currency) }));
  return parts.length ? parts.join(' · ') : i.discountMinor > 0 ? t('sc.billing.paid.free') : t('sc.billing.paid.none');
}

/**
 * How the plan is paid (S298): the seller chooses whether earnings pay the plan (after credit), or the card always does. Owner and
 * finance change it; it applies from the next invoice. Hidden while the platform doesn't offer it.
 */
function PlanFeeCollectionCard({ v, t }: { v: PlanFeeCollectionView; t: T }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const save = useMutation({
    mutationFn: (mode: PlanFeeCollectionMode) => api.setPlanFeeCollection(mode),
    onSuccess: (next) => {
      qc.setQueryData(sellerKeys.billing(orgId), (old: BillingViewData | undefined) => (old ? { ...old, planFeeCollection: next } : old));
      toast.show(t('sc.billing.planFee.saved'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  if (!v.available) return null;
  return (
    <Card title={t('sc.billing.planFee.title')}>
      <div data-testid="plan-fee-collection" className="flex flex-col gap-3">
        <PlanFeeChoice value={save.isPending && save.variables ? save.variables : v.mode} disabled={!v.canChange || save.isPending}
          onChange={(m) => m !== v.mode && save.mutate(m)} t={t} />
        <p className="m-0 text-sm text-muted">
          {v.appliesFromInvoiceAt ? t('sc.billing.planFee.appliesFrom', { date: shortDate(v.appliesFromInvoiceAt) }) : t('sc.billing.planFee.appliesFirst')}
          {v.inProgressInvoiceId ? ` ${t('sc.billing.planFee.inProgress')}` : ''}
          {v.source === 'STAFF' ? ` ${t('sc.billing.planFee.setByStaff')}` : ''}
        </p>
        {!v.canChange ? <p className="m-0 text-xs text-muted">{t('sc.billing.planFee.readOnly')}</p> : null}
      </div>
    </Card>
  );
}

/** Change plan or cadence: the preview (the proration for an upgrade) before anything changes. */
function ChangePlanDialog({ target, onClose, t }: { target: { plan: Plan; cadence: Cadence } | null; onClose: () => void; t: T }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const preview = useQuery({ ...sellerQueries.planChangePreview(api, orgId, target?.plan.code ?? '', target?.cadence ?? 'MONTHLY'), enabled: !!target });
  const change = useMutation({
    mutationFn: (v: { code: string; cadence: Cadence }) => api.changePlan(v.code, v.cadence),
    onSuccess: (r) => {
      qc.setQueryData(sellerKeys.billing(orgId), r.billing);
      void qc.invalidateQueries({ queryKey: sellerKeys.billingRewards(orgId) });
      toast.show(r.applied === 'IMMEDIATE_UPGRADE' ? t('sc.billing.toast.upgraded') : r.applied === 'AT_RENEWAL' ? t('sc.billing.toast.scheduled') : t('sc.billing.toast.same'));
      onClose();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const p = preview.data;
  return (
    <Dialog open={!!target} onOpenChange={(o) => !o && onClose()} title={target ? t('sc.billing.changeTitle', { plan: target.plan.name }) : ''} width={460} closeLabel={t('sc.billing.close')}>
      {preview.isPending ? <Spinner /> : preview.isError ? <ErrorState error={preview.error} onRetry={() => void preview.refetch()} /> : p ? (
        <div className="flex flex-col gap-3 text-sm">
          <p className="m-0">
            {p.change === 'IMMEDIATE_UPGRADE'
              ? p.trialing ? t('sc.billing.preview.upgradeTrial') : t('sc.billing.preview.upgrade')
              : p.change === 'AT_RENEWAL' ? t('sc.billing.preview.atRenewal', { date: shortDate(p.effectiveAt) }) : t('sc.billing.preview.same')}
          </p>
          <dl className="m-0 grid grid-cols-[1fr_auto] gap-x-4 gap-y-1">
            <dt className="text-muted">{t('sc.billing.preview.dueNow')}</dt>
            <dd className="m-0 text-end font-semibold" data-testid="due-now">{money(p.amountDueNowMinor)}</dd>
            <dt className="text-muted">{t('sc.billing.preview.then', { cadence: t(CADENCE_KEYS[p.cadence]) })}</dt>
            <dd className="m-0 text-end">{money(p.nextChargeMinor)}</dd>
            {p.nextChargeAt ? (
              <>
                <dt className="text-muted">{t('sc.billing.preview.nextChargeOn')}</dt>
                <dd className="m-0 text-end">{shortDate(p.nextChargeAt)}</dd>
              </>
            ) : null}
          </dl>
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={onClose}>{t('sc.billing.cancel')}</Button>
            <Button disabled={p.change === 'NO_CHANGE'} loading={change.isPending} onClick={() => target && change.mutate({ code: target.plan.code, cadence: target.cadence })}>
              {t('sc.billing.confirmChange')}
            </Button>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}

interface CollectedMethod {
  stripePaymentMethodId: string;
  kind: 'CARD' | 'US_BANK_ACCOUNT';
  brand?: string;
  last4: string;
  expMonth?: number;
  expYear?: number;
  bankName?: string;
  accountType?: string;
}

const brandOf = (n: string): string => (/^4/.test(n) ? 'visa' : /^(5[1-5]|2[2-7])/.test(n) ? 'mastercard' : /^3[47]/.test(n) ? 'amex' : /^6(011|5)/.test(n) ? 'discover' : '');

/** Adds a card or US bank account: Stripe's Payment Element with a SetupIntent, or the labelled local stand-in without a Stripe key. */
function AddPaymentMethodDialog({ open, onClose, t }: { open: boolean; onClose: () => void; t: T }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [intent, setIntent] = useState<BillingSetupIntent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [makeDefault, setMakeDefault] = useState(true);
  const [local, setLocal] = useState({ number: '', exp: '' });
  const box = useRef<HTMLDivElement>(null);
  const stripe = useRef<{ s: StripePaymentInstance; elements: StripePaymentElements; el: PaymentElement } | null>(null);
  const start = useMutation({ mutationFn: () => api.createBillingSetupIntent(), onSuccess: setIntent, onError: (e) => setError(problemMessage(e)) });
  const startIntent = start.mutate;
  useEffect(() => {
    if (!open) {
      setIntent(null);
      setError(null);
      setLocal({ number: '', exp: '' });
      return;
    }
    startIntent();
  }, [open, startIntent]);
  const live = !!intent && !intent.localTest && hasStripeKey(intent.publishableKey);
  useEffect(() => {
    if (!live || !intent) return;
    let cancelled = false;
    getStripePayments(intent.publishableKey)
      .then((s) => {
        if (cancelled || !box.current) return;
        const elements = s.elements({ mode: 'setup', currency: 'usd', paymentMethodCreation: 'manual', appearance: appearance() });
        const el = elements.create('payment', { paymentMethodOrder: ['card', 'us_bank_account'] });
        el.mount(box.current);
        stripe.current = { s, elements, el };
      })
      .catch(() => setError(t('sc.billing.add.loadFailed')));
    return () => {
      cancelled = true;
      stripe.current?.el.destroy();
      stripe.current = null;
    };
  }, [live, intent, t]);

  const collect = async (): Promise<CollectedMethod | string> => {
    if (!intent) return t('sc.billing.add.notReady');
    if (!live) {
      const n = local.number.replace(/\D/g, '');
      const m = /^(\d{2})\/(\d{2})$/.exec(local.exp);
      if (!luhn(n) || !brandOf(n)) return t('sc.billing.add.badNumber');
      if (!m) return t('sc.billing.add.badExpiry');
      return { stripePaymentMethodId: `pm_local_${brandOf(n)}_${n.slice(-4)}`, kind: 'CARD', brand: brandOf(n), last4: n.slice(-4), expMonth: Number(m[1]), expYear: 2000 + Number(m[2]) };
    }
    const cur = stripe.current;
    if (!cur) return t('sc.billing.add.notReady');
    const submitted = await cur.elements.submit();
    if (submitted.error) return submitted.error.message ?? t('sc.billing.add.failed');
    const r = await cur.s.createPaymentMethod({ elements: cur.elements });
    const pm = r.paymentMethod;
    if (r.error || !pm) return r.error?.message ?? t('sc.billing.add.failed');
    const confirmed = await cur.s.confirmSetup({ clientSecret: intent.clientSecret, confirmParams: { payment_method: pm.id, return_url: window.location.href }, redirect: 'if_required' });
    if (confirmed.error) return confirmed.error.message ?? t('sc.billing.add.failed');
    if (pm.us_bank_account) {
      return { stripePaymentMethodId: pm.id, kind: 'US_BANK_ACCOUNT', last4: pm.us_bank_account.last4 ?? '0000', bankName: pm.us_bank_account.bank_name, accountType: pm.us_bank_account.account_type };
    }
    return { stripePaymentMethodId: pm.id, kind: 'CARD', brand: pm.card?.brand, last4: pm.card?.last4 ?? '0000', expMonth: pm.card?.exp_month, expYear: pm.card?.exp_year };
  };
  const save = useMutation({
    mutationFn: async () => {
      const m = await collect();
      if (typeof m === 'string') throw new Error(m);
      return api.addBillingPaymentMethod({ ...m, makeDefault });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sellerKeys.billing(orgId) });
      toast.show(t('sc.billing.add.added'));
      onClose();
    },
    onError: (e) => setError(e instanceof Error && !('status' in e) ? e.message : problemMessage(e))
  });

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={t('sc.billing.add.title')} width={480} closeLabel={t('sc.billing.close')}>
      <div className="flex flex-col gap-3">
        {!intent && !error ? <Spinner /> : null}
        {intent && live ? <div ref={box} data-testid="payment-element" /> : null}
        {intent && !live ? (
          <div className="flex flex-col gap-2 rounded-md border border-dashed border-gold-500 bg-gold-50 p-4" role="group" aria-label={t('sc.billing.add.localTitle')}>
            <p className="m-0 inline-flex items-center gap-2 text-sm">
              <Flask size={18} aria-hidden />
              <span>{t('sc.billing.add.localNote')}</span>
            </p>
            <div className="grid grid-cols-1 gap-x-4 md:grid-cols-3">
              <TextField className="md:col-span-2" label={t('sc.billing.add.cardNumber')} inputMode="numeric" autoComplete="cc-number" maxLength={23} value={local.number}
                onChange={(e) => setLocal((l) => ({ ...l, number: e.target.value.replace(/[^\d ]/g, '') }))} />
              <TextField label={t('sc.billing.add.expiry')} placeholder="MM/YY" autoComplete="cc-exp" maxLength={5} value={local.exp}
                onChange={(e) => {
                  const d = e.target.value.replace(/\D/g, '').slice(0, 4);
                  setLocal((l) => ({ ...l, exp: d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d }));
                }} />
            </div>
          </div>
        ) : null}
        <label className="inline-flex items-center gap-2 text-sm">
          <input type="checkbox" checked={makeDefault} onChange={(e) => setMakeDefault(e.target.checked)} />
          {t('sc.billing.add.makeDefault')}
        </label>
        {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('sc.billing.cancel')}</Button>
          <Button disabled={!intent} loading={save.isPending} onClick={() => { setError(null); save.mutate(); }}>{t('sc.billing.add.save')}</Button>
        </div>
      </div>
    </Dialog>
  );
}

/**
 * Plan & billing (S292–S297): plan and cadence, trial end, credit balance, rewards the organisation can earn with their
 * progress and the ones it earned, plan changes with a proration preview, payment methods through the Payment Element
 * (SetupIntent), and AfroTransact's own invoices (printable, retryable). Stripe only moves card money.
 */
export function BillingView() {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.billing(api, orgId));
  const rewards = useQuery(sellerQueries.billingRewards(api, orgId));
  const params = useSearchParams();
  const invoiceState = useTableState({ searchParams: params, prefix: 'invoices' });
  const earnedState = useTableState({ searchParams: params, prefix: 'rewards' });
  const [cadence, setCadence] = useState<Cadence | ''>('');
  const [target, setTarget] = useState<{ plan: Plan; cadence: Cadence } | null>(null);
  const [adding, setAdding] = useState(false);
  const cadences = useMemo(
    () => (['MONTHLY', 'QUARTERLY', 'ANNUAL'] as const).map((c) => ({ value: c, label: t(`sc.billing.cadenceChip.${c}`) })),
    [t]
  );
  const [invoiceBusy, setInvoiceBusy] = useState<string | null>(null);
  const retry = useMutation({
    mutationFn: (i: Invoice) => api.retryInvoice(i.id),
    onMutate: (i) => setInvoiceBusy(`retry:${i.id}`),
    onSettled: () => setInvoiceBusy(null),
    onSuccess: (inv) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.billing(orgId) });
      toast.show(inv.status === 'PAID' ? t('sc.billing.retry.paid') : inv.lastError ?? t('sc.billing.retry.failed'), inv.status === 'PAID' ? 'info' : 'error');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const downloadInvoice = useMutation({
    mutationFn: (i: Invoice) => api.downloadInvoice(i.id),
    onMutate: (i) => setInvoiceBusy(i.id),
    onSettled: () => setInvoiceBusy(null),
    onSuccess: (f, i) => download(f.blob, f.fileName === 'download' ? `invoice-${i.number}.html` : f.fileName),
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
  const available = rewards.data?.available ?? [];
  const earned = rewards.data?.earned ?? [];
  // S299: applied low-sales relief moved the next charge.
  const unpaid = invoices.find((i) => i.canRetry);
  const relief = earned.find((e) => e.comparison === 'LESS_THAN' && e.status === 'APPLIED' && e.nextChargeAt);
  return (
    <ModulePage module="billing" readOnlyNote={false}>
      {!canManage ? <Notice tone="info" icon="eye" title={t('sc.billing.ownerOnly')}>{t('sc.billing.ownerOnlyDetail')}</Notice> : null}
      <PlanSummary sub={sub} t={t} />
      {q.data.planFeeCollection ? <PlanFeeCollectionCard v={q.data.planFeeCollection} t={t} /> : null}

      <Card title={t('sc.billing.rewardsTitle')}>
        {relief?.nextChargeAt ? (
          <Notice tone="success" title={t('sc.billing.reliefApplied')}>{t('sc.billing.reliefAppliedDetail', { date: shortDate(relief.nextChargeAt) })}</Notice>
        ) : null}
        {rewards.isError ? <ErrorState error={rewards.error} onRetry={() => void rewards.refetch()} /> : available.length ? (
          <ul aria-label={t('sc.billing.rewardsTitle')} className="m-0 flex list-none flex-col gap-4 p-0">
            {available.map((r) => <RewardRow key={r.ruleId} r={r} t={t} />)}
          </ul>
        ) : (
          <p className="m-0 inline-flex items-center gap-2 text-sm text-muted"><Gift size={16} aria-hidden /> {t('sc.billing.noRewards')}</p>
        )}
      </Card>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 text-lg font-semibold">{t('sc.billing.plans')}</h2>
        <ChipGroup label={t('sc.billing.frequency')} options={cadences} value={shown} onChange={(v) => setCadence(v)} />
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
              {p.chargeTerms ? <span className="text-xs text-muted" data-testid="plan-charge-terms">{p.chargeTerms}</span> : null}
              <ul className="m-0 flex flex-col gap-1 pl-4 text-sm">
                {p.features.map((f) => <li key={f}>{f}</li>)}
              </ul>
              {p.trialDays && !p.current ? <span className="text-xs text-muted">{t('sc.billing.trialDays', { days: p.trialDays })}</span> : null}
              <div className="mt-auto pt-2">
                {current ? (
                  <Badge tone="brand">{t('sc.billing.currentPlan')}</Badge>
                ) : canManage ? (
                  <Button size="sm" variant={p.current ? 'neutral' : 'primary'} onClick={() => setTarget({ plan: p, cadence: shown })}>
                    {p.current ? t('sc.billing.switchTo', { cadence: t(CADENCE_KEYS[shown]) }) : t('sc.billing.choose', { plan: p.name })}
                  </Button>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 text-lg font-semibold">{t('sc.billing.paymentMethods')}</h2>
        {canManage ? <Button size="sm" variant="neutral" onClick={() => setAdding(true)}><Plus size={16} aria-hidden /> {t('sc.billing.addMethod')}</Button> : null}
      </div>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-4">
        {paymentMethods.map((m) => (
          <MethodCard key={m.id} m={m} t={t} canManage={canManage} busy={pm.isPending} onDefault={() => pm.mutate({ id: m.id, op: 'default' })} onRemove={() => pm.mutate({ id: m.id, op: 'remove' })} />
        ))}
      </div>
      <p className="m-0 text-xs text-muted">{t('sc.billing.defaultNote')}</p>

      <h2 className="m-0 text-lg font-semibold">{t('sc.billing.earnedTitle')}</h2>
      <DataTable<EarnedReward> id="seller.rewards-earned" label={t('sc.billing.earnedTitle')} columns={earnedColumns(t)} data={earned} getRowId={(r) => r.id} rowLabel={(r) => r.summary}
        state={earnedState} mode="client" loading={rewards.isPending} fetching={rewards.isFetching} empty={t('sc.billing.noEarned')} />

      <h2 className="m-0 text-lg font-semibold">{t('sc.billing.invoices')}</h2>
      {unpaid ? (
        <Notice tone="danger" title={t('sc.billing.unpaidTitle', { number: unpaid.number })}>
          {unpaid.lastError ?? t('sc.billing.unpaidDetail')}{' '}
          {unpaid.nextAttemptAt ? t('sc.billing.unpaidNext', { date: shortDate(unpaid.nextAttemptAt) }) : ''}
        </Notice>
      ) : null}
      <p className="m-0 text-xs text-muted">{t('sc.billing.invoiceOrder')}</p>
      <DataTable<Invoice> id="seller.invoices" label={t('sc.billing.invoices')} columns={invoiceColumns(t, { canManage, busy: invoiceBusy, onDownload: (i) => downloadInvoice.mutate(i), onRetry: (i) => retry.mutate(i) })} data={invoices} getRowId={(i) => i.id} rowLabel={(i) => i.number}
        state={invoiceState} mode="client" fetching={q.isFetching} empty={t('sc.billing.noInvoices')} onExport={exportDataset} pollExport={pollExport} />

      <ChangePlanDialog target={target} onClose={() => setTarget(null)} t={t} />
      <AddPaymentMethodDialog open={adding} onClose={() => setAdding(false)} t={t} />
    </ModulePage>
  );
}
