'use client';

import { CHARGE_KEYS, CHARGE_METRICS, formatBps, type ChargeMetric, type ChargePolicy, chargePolicyLimits, chargePolicyOf, chargeTermsOf, planFeatureList, type PlanVisibility, type StaffPlan, type StaffPlanEntitlements, staffPlanKeys, staffPlanQueries, staffQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, DotsThreeVertical, Menu, MenuItem, MenuSeparator, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, humanize, money } from '@/lib/format';

import { centsOf, dollarsOf, errorsOf, intOf, plansApi, STATUS_TONE, type T, usePlanRights } from './kit';

type Editing = { mode: 'create' } | { mode: 'view'; plan: StaffPlan } | { mode: 'edit'; plan: StaffPlan } | { mode: 'prices'; plan: StaffPlan } | { mode: 'migrate'; plan: StaffPlan } | null;

const VISIBILITY: PlanVisibility[] = ['PUBLIC', 'HIDDEN', 'INVITE_ONLY'];

type ChargeRow = { rowId: number; metric: ChargeMetric; amount: string };
let chargeRowSeq = 0;

/** The plan's charge terms as form text: sales in dollars, counts as they are. */
function chargeFormOf(plan: StaffPlan | null) {
  const p = chargePolicyOf(plan?.limits);
  return {
    freeMonths: String(p.freeMonths), chargeThen: p.then as ChargePolicy['then'], chargeMatch: p.match as ChargePolicy['match'], chargeWindow: p.windowMonths === null ? '' : String(p.windowMonths),
    conditions: p.clauses.map((c): ChargeRow => ({ rowId: ++chargeRowSeq, metric: c.metric, amount: c.metric === 'GROSS_SALES' ? String(c.threshold / 100) : String(c.threshold) }))
  };
}

/** The policy the form describes, or null when a condition amount is not a positive number. */
function chargePolicyFrom(f: { freeMonths: string; chargeThen: ChargePolicy['then']; chargeMatch: ChargePolicy['match']; chargeWindow: string; conditions: ChargeRow[] }): ChargePolicy | null {
  const free = Number.parseInt(f.freeMonths || '0', 10);
  if (!Number.isInteger(free) || free < 0 || free > 36) return null;
  const clauses = f.conditions.map((c) => ({ metric: c.metric, threshold: Math.round(Number(c.amount) * (c.metric === 'GROSS_SALES' ? 100 : 1)) }));
  if (f.chargeThen !== 'CHARGE' && (clauses.length === 0 || clauses.some((c) => !(c.threshold > 0)))) return null;
  const window = f.chargeWindow.trim() ? Number.parseInt(f.chargeWindow, 10) : null;
  if (window !== null && !(window >= 1 && window <= 24)) return null;
  return { freeMonths: free, then: f.chargeThen, match: f.chargeMatch, clauses: f.chargeThen === 'CHARGE' ? [] : clauses, windowMonths: f.chargeThen === 'CHARGE' ? null : window };
}

/** What the plan takes from each sale, read from the current fee schedule (it is edited under Fees, per plan). */
function PlanCommissionSummary({ code, t }: { code: string | null; t: T }) {
  const fees = useQuery(staffQueries.fees(staffApi()));
  const cur = fees.data?.current;
  let text = t('st.plans.commission.createFirst');
  if (code && cur) {
    const own = cur.planRates.filter((r) => r.planCode === code).map((r) => r.bps);
    const range = own.length ? (Math.min(...own) === Math.max(...own) ? formatBps(own[0]!) : `${formatBps(Math.min(...own))} – ${formatBps(Math.max(...own))}`) : `${formatBps(cur.defaultCommissionBps)} (${t('st.plans.commission.default')})`;
    const fbaOwn = cur.planFba.find((f) => f.planCode === code);
    text = t('st.plans.commission.value', { range, fba: money(fbaOwn ? fbaOwn.feeMinor : cur.fbaPerUnitMinor) + (fbaOwn ? '' : ` (${t('st.plans.commission.default')})`) });
  } else if (code && fees.isPending) text = t('st.plans.loading');
  return (
    <div className="rounded-sm bg-surface-muted p-2 text-sm">
      <strong className="block text-xs uppercase text-muted">{t('st.plans.commission.title')}</strong>
      <span>{text}</span>
      {code ? <> <Link href="/fees?tab=commission" className="font-semibold underline">{t('st.plans.commission.change')}</Link></> : null}
    </div>
  );
}

/** The order a period's plan fee is decided in (InvoiceIssuingService), so staff can predict an invoice. */
function ChargeOrder({ t }: { t: T }) {
  return (
    <details className="rounded-sm border border-border p-2 text-sm">
      <summary className="cursor-pointer font-semibold">{t('st.plans.order.title')}</summary>
      <ol className="m-0 mt-2 flex list-decimal flex-col gap-1 pl-5">
        {(['trial', 'free', 'override', 'rule', 'coupons', 'collect'] as const).map((k) => <li key={k}>{t(`st.plans.order.${k}`)}</li>)}
      </ol>
      <p className="m-0 mt-2 text-xs text-muted">{t('st.plans.order.commission')}</p>
    </details>
  );
}

/** Create a plan, or edit its name, tagline, features, visibility and order (prices and trial are versioned: see PricesDialog). */
function PlanDialog({ editing, onClose, t }: { editing: Editing; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const plan = editing?.mode === 'edit' ? editing.plan : null;
  const open = editing?.mode === 'create' || editing?.mode === 'edit';
  const [f, setF] = useState(() => ({
    code: '', kind: plan?.kind ?? 'SELLER', name: plan?.name ?? '', tagline: plan?.tagline ?? '', visibility: plan?.visibility ?? 'PUBLIC',
    sortOrder: String(plan?.sortOrder ?? 100), monthly: '', quarterly: '', annual: '', trialDays: '30',
    unlimited: plan?.entitlements?.listingsUnlimited ?? false, listings: String(plan?.entitlements?.listings ?? 50), aiWriter: plan?.entitlements?.aiListingWriter ?? false,
    lola: plan?.entitlements?.lolaTier ?? 'ESSENTIALS', bulkUpload: plan?.entitlements?.bulkUpload ?? false, imageLibrary: plan?.entitlements?.imageLibrary ?? false,
    coupons: plan?.entitlements?.coupons ?? false,
    ...chargeFormOf(plan)
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const cap = intOf(f.listings);
      if (!f.unlimited && (cap == null || Number.isNaN(cap) || cap < 0)) throw new Error(t('st.plans.badListings'));
      // Entitlements live in `limits` (the seller service enforces them); other keys stay as plan information.
      const seller = f.kind === 'SELLER';
      const policy = chargePolicyFrom(f);
      if (!policy) throw new Error(f.chargeThen !== 'CHARGE' && f.conditions.length === 0 ? t('st.plans.charges.needCondition') : t('st.plans.charges.badFree'));
      const kept = Object.fromEntries(Object.entries(plan?.limits ?? {}).filter(([k]) => !(CHARGE_KEYS as readonly string[]).includes(k)));
      const limits = { ...kept, ...chargePolicyLimits(policy), listings: f.unlimited ? 'unlimited' : String(cap), aiListingWriter: String(f.aiWriter), lolaTier: f.lola,
        bulkUpload: String(seller && f.bulkUpload), imageLibrary: String(seller && f.imageLibrary), coupons: String(seller && f.coupons) };
      // The plan's feature list is derived from what it includes (the server does it too): nothing is typed.
      const meta = { name: f.name.trim(), tagline: f.tagline.trim() || null, features: [] as string[],
        visibility: f.visibility as PlanVisibility, sortOrder: intOf(f.sortOrder) ?? 100, limits };
      if (plan) return plansApi().updatePlan(plan.code, meta, plan.rowVersion);
      const prices = { monthlyCents: centsOf(f.monthly) ?? 0, quarterlyCents: centsOf(f.quarterly) ?? 0, annualCents: centsOf(f.annual) ?? 0, trialDays: intOf(f.trialDays) ?? 0 };
      if ([prices.monthlyCents, prices.quarterlyCents, prices.annualCents, prices.trialDays].some(Number.isNaN)) throw new Error(t('st.plans.badAmount'));
      return plansApi().createPlan({ ...meta, code: f.code.trim(), kind: f.kind as 'SELLER' | 'PROVIDER', prices });
    },
    onSuccess: (p) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.plans() });
      toast.show(plan ? t('st.plans.saved', { name: p.name }) : t('st.plans.created', { name: p.name }));
      onClose();
    },
    onError: (e) => setErrors(e instanceof Error && !('status' in e) ? { _: e.message } : errorsOf(e))
  });
  const set = (k: keyof typeof f) => (e: { currentTarget: { value: string } }) => {
    const v = e.currentTarget.value;
    setF((x) => ({ ...x, [k]: v }));
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    save.mutate();
  };
  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()} title={plan ? t('st.plans.editTitle', { name: plan.name }) : t('st.plans.createTitle')} width={560} closeLabel={t('st.plans.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-2">
        {!plan ? (
          <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
            <TextField id="plan-code" label={t('st.plans.code')} help={t('st.plans.codeHelp')} value={f.code} error={errors.code} onChange={set('code')} />
            <Select id="plan-kind" label={t('st.plans.kind')} value={f.kind} onChange={set('kind')}
              options={[{ value: 'SELLER', label: t('st.plans.kind.SELLER') }, { value: 'PROVIDER', label: t('st.plans.kind.PROVIDER') }]} />
          </div>
        ) : null}
        <TextField id="plan-name" label={t('st.plans.name')} value={f.name} error={errors.name} onChange={set('name')} />
        <TextField id="plan-tagline" label={t('st.plans.tagline')} value={f.tagline} error={errors.tagline} onChange={set('tagline')} />
        <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
          <Select id="plan-visibility" label={t('st.plans.visibility')} value={f.visibility} onChange={set('visibility')}
            options={VISIBILITY.map((v) => ({ value: v, label: t(`st.plans.visibility.${v}`) }))} />
          <TextField id="plan-sort" label={t('st.plans.sortOrder')} inputMode="numeric" value={f.sortOrder} onChange={set('sortOrder')} />
        </div>
        <fieldset className="m-0 flex flex-col gap-3 rounded-md border border-border p-3">
          <legend className="px-1 text-sm font-semibold">{t('st.plans.includes')}</legend>
          <p className="m-0 text-xs text-muted">{t('st.plans.includesHelp')}</p>
          <div className="grid grid-cols-1 at-align gap-x-3 md:grid-cols-2">
            <TextField id="plan-listings" label={t('st.plans.listings')} inputMode="numeric" value={f.unlimited ? '' : f.listings} disabled={f.unlimited} error={errors['limits.listings']}
              onChange={set('listings')} />
            <label className="at-row-box gap-2 text-sm">
              <input type="checkbox" checked={f.unlimited} onChange={(e) => setF((x) => ({ ...x, unlimited: e.target.checked }))} />
              {t('st.plans.listingsUnlimited')}
            </label>
          </div>
          <div className="flex flex-col gap-2">
            {f.kind === 'SELLER' ? (
              <>
                <label className="inline-flex items-center gap-2 text-sm"><input type="checkbox" checked={f.bulkUpload} onChange={(e) => setF((x) => ({ ...x, bulkUpload: e.target.checked }))} />{t('st.plans.cap.bulkUpload')}</label>
                <label className="inline-flex items-center gap-2 text-sm"><input type="checkbox" checked={f.imageLibrary} onChange={(e) => setF((x) => ({ ...x, imageLibrary: e.target.checked }))} />{t('st.plans.cap.imageLibrary')}</label>
                <label className="inline-flex items-center gap-2 text-sm"><input type="checkbox" checked={f.coupons} onChange={(e) => setF((x) => ({ ...x, coupons: e.target.checked }))} />{t('st.plans.cap.coupons')}</label>
              </>
            ) : null}
            <label className="inline-flex items-center gap-2 text-sm"><input type="checkbox" checked={f.aiWriter} onChange={(e) => setF((x) => ({ ...x, aiWriter: e.target.checked }))} />{t('st.plans.aiWriter')}</label>
          </div>
          <Select id="plan-lola" label={t('st.plans.lolaTier')} value={f.lola} onChange={set('lola')}
            options={(['NONE', 'ESSENTIALS', 'GROWTH', 'PRO'] as const).map((v) => ({ value: v, label: t(`st.plans.lola.${v}`) }))} />
          <div className="rounded-sm bg-surface-muted p-2 text-sm" aria-live="polite">
            <strong className="block text-xs uppercase text-muted">{t('st.plans.membersSee')}</strong>
            {planFeatureList({ listings: f.unlimited ? null : intOf(f.listings) ?? 0, listingsUnlimited: f.unlimited, aiListingWriter: f.aiWriter, lolaTier: f.lola,
              bulkUpload: f.kind === 'SELLER' && f.bulkUpload, imageLibrary: f.kind === 'SELLER' && f.imageLibrary, coupons: f.kind === 'SELLER' && f.coupons }).join(' · ')}
          </div>
        </fieldset>
        <fieldset className="m-0 flex flex-col gap-3 rounded-md border border-border p-3">
          <legend className="px-1 text-sm font-semibold">{t('st.plans.charges')}</legend>
          <p className="m-0 text-xs text-muted">{t('st.plans.charges.help')}</p>
          <PlanCommissionSummary code={plan?.code ?? null} t={t} />
          <ChargeOrder t={t} />
          <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
            <TextField id="plan-free-months" label={t('st.plans.charges.free')} inputMode="numeric" value={f.freeMonths} error={errors['chargePolicy.freeMonths']} onChange={set('freeMonths')} />
            <Select id="plan-charge-then" label={t('st.plans.charges.then')} value={f.chargeThen} onChange={set('chargeThen')}
              options={(['CHARGE', 'CHARGE_WHEN', 'WAIVE_WHILE'] as const).map((v) => ({ value: v, label: t(`st.plans.charges.then.${v}`) }))} />
          </div>
          {f.chargeThen !== 'CHARGE' ? (
            <div className="flex flex-col gap-2">
              <Select id="plan-charge-match" label={t('st.plans.charges.match')} value={f.chargeMatch} onChange={set('chargeMatch')}
                options={(['ALL', 'ANY'] as const).map((v) => ({ value: v, label: t(`st.plans.charges.match.${v}`) }))} />
              {f.conditions.map((c, i) => (
                <div key={c.rowId} className="grid grid-cols-[1fr_8rem_auto] at-align gap-x-2">
                  <Select id={`plan-cond-metric-${i}`} label={t('st.plans.charges.metric')} value={c.metric}
                    onChange={(e) => { const v = e.currentTarget.value as ChargeMetric; setF((x) => ({ ...x, conditions: x.conditions.map((r) => (r.rowId === c.rowId ? { ...r, metric: v } : r)) })); }}
                    options={CHARGE_METRICS.map((v) => ({ value: v, label: t(`st.plans.charges.metric.${v}`) }))} />
                  <TextField id={`plan-cond-amount-${i}`} label={t('st.plans.charges.amount')} inputMode="decimal" value={c.amount}
                    onChange={(e) => { const v = e.currentTarget.value; setF((x) => ({ ...x, conditions: x.conditions.map((r) => (r.rowId === c.rowId ? { ...r, amount: v } : r)) })); }} />
                  <Button variant="neutral" size="sm" onClick={() => setF((x) => ({ ...x, conditions: x.conditions.filter((r) => r.rowId !== c.rowId) }))}>{t('st.plans.charges.remove')}</Button>
                </div>
              ))}
              {errors['chargePolicy.clauses'] ? <p role="alert" className="m-0 text-xs text-danger">{errors['chargePolicy.clauses']}</p> : null}
              <div>
                <Button variant="neutral" size="sm" onClick={() => setF((x) => ({ ...x, conditions: [...x.conditions, { rowId: ++chargeRowSeq, metric: 'ACTIVE_LISTINGS', amount: '' }] }))}>{t('st.plans.charges.addCondition')}</Button>
              </div>
              <TextField id="plan-charge-window" label={t('st.plans.charges.window')} inputMode="numeric" value={f.chargeWindow} error={errors['chargePolicy.windowMonths']} onChange={set('chargeWindow')} />
            </div>
          ) : null}
          <div className="rounded-sm bg-surface-muted p-2 text-sm" aria-live="polite">
            <strong className="block text-xs uppercase text-muted">{t('st.plans.charges.preview')}</strong>
            {chargeTermsOf({ ...Object.fromEntries(Object.entries(chargePolicyLimits(chargePolicyFrom(f) ?? { freeMonths: 0, then: 'CHARGE', match: 'ALL', clauses: [], windowMonths: null }))) }, plan?.monthlyCents ?? centsOf(f.monthly) ?? 0)}
          </div>
        </fieldset>
        {!plan ? (
          <div className="grid grid-cols-2 gap-x-3 md:grid-cols-4">
            <TextField id="plan-monthly" label={t('st.plans.monthly')} inputMode="decimal" value={f.monthly} error={errors['prices.monthlyCents']} onChange={set('monthly')} />
            <TextField id="plan-quarterly" label={t('st.plans.quarterly')} inputMode="decimal" value={f.quarterly} error={errors['prices.quarterlyCents']} onChange={set('quarterly')} />
            <TextField id="plan-annual" label={t('st.plans.annual')} inputMode="decimal" value={f.annual} error={errors['prices.annualCents']} onChange={set('annual')} />
            <TextField id="plan-trial" label={t('st.plans.trialDays')} inputMode="numeric" value={f.trialDays} error={errors['prices.trialDays']} onChange={set('trialDays')} />
          </div>
        ) : null}
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button type="submit" loading={save.isPending}>{plan ? t('st.plans.save') : t('st.plans.createDraft')}</Button>
        </div>
      </form>
    </Sheet>
  );
}

/** New prices or trial: a draft version that Publish makes current (existing subscribers stay on theirs). */
function PricesDialog({ plan, onClose, t }: { plan: StaffPlan | null; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [f, setF] = useState(() => ({
    monthly: dollarsOf(plan?.monthlyCents), quarterly: dollarsOf(plan?.quarterlyCents), annual: dollarsOf(plan?.annualCents), trialDays: String(plan?.trialDays ?? 0), note: ''
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: (publish: boolean) => {
      if (!plan) throw new Error('no plan');
      const body = { monthlyCents: centsOf(f.monthly) ?? 0, quarterlyCents: centsOf(f.quarterly) ?? 0, annualCents: centsOf(f.annual) ?? 0, trialDays: intOf(f.trialDays) ?? 0, note: f.note.trim() || null };
      if ([body.monthlyCents, body.quarterlyCents, body.annualCents, body.trialDays].some(Number.isNaN)) throw new Error(t('st.plans.badAmount'));
      return plansApi().saveDraft(plan.code, body).then((p) => (publish ? plansApi().publish(p.code) : p));
    },
    onSuccess: (p, publish) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.all });
      toast.show(publish ? t('st.plans.published', { name: p.name, version: p.currentVersion }) : t('st.plans.draftSaved'));
      onClose();
    },
    onError: (e) => setErrors(e instanceof Error && !('status' in e) ? { _: e.message } : errorsOf(e))
  });
  const set = (k: keyof typeof f) => (e: { currentTarget: { value: string } }) => {
    const v = e.currentTarget.value;
    setF((x) => ({ ...x, [k]: v }));
  };
  return (
    <Dialog open={!!plan} onOpenChange={(o) => !o && onClose()} title={t('st.plans.pricesTitle', { name: plan?.name ?? '' })} description={t('st.plans.pricesHelp')} width={560} closeLabel={t('st.plans.close')}>
      <div className="flex flex-col gap-2">
        <div className="grid grid-cols-2 gap-x-3 md:grid-cols-4">
          <TextField id="draft-monthly" label={t('st.plans.monthly')} inputMode="decimal" value={f.monthly} error={errors.monthlyCents} onChange={set('monthly')} />
          <TextField id="draft-quarterly" label={t('st.plans.quarterly')} inputMode="decimal" value={f.quarterly} error={errors.quarterlyCents} onChange={set('quarterly')} />
          <TextField id="draft-annual" label={t('st.plans.annual')} inputMode="decimal" value={f.annual} error={errors.annualCents} onChange={set('annual')} />
          <TextField id="draft-trial" label={t('st.plans.trialDays')} inputMode="numeric" value={f.trialDays} error={errors.trialDays} onChange={set('trialDays')} />
        </div>
        <TextField id="draft-note" label={t('st.plans.versionNote')} value={f.note} onChange={set('note')} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button variant="neutral" loading={save.isPending && save.variables === false} onClick={() => save.mutate(false)}>{t('st.plans.saveDraft')}</Button>
          <Button loading={save.isPending && save.variables === true} onClick={() => save.mutate(true)}>{t('st.plans.saveAndPublish')}</Button>
        </div>
      </div>
    </Dialog>
  );
}

/** Moves subscribers of older versions to the current one at their next renewal (no proration). */
function MigrationDialog({ plan, onClose, t }: { plan: StaffPlan | null; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const preview = useQuery({ ...staffPlanQueries.migration(plansApi(), plan?.code ?? ''), enabled: !!plan });
  const migrate = useMutation({
    mutationFn: () => plansApi().migrate(plan?.code ?? ''),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.all });
      toast.show(t('st.plans.migrated', { count: r.migrated.length, failed: r.failed.length }));
      onClose();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const rows = preview.data?.rows ?? [];
  return (
    <Dialog open={!!plan} onOpenChange={(o) => !o && onClose()} title={t('st.plans.migrateTitle', { name: plan?.name ?? '', version: plan?.currentVersion ?? '' })} description={t('st.plans.migrateHelp')} width={640} closeLabel={t('st.plans.close')}>
      {preview.isError ? <ErrorState error={preview.error} onRetry={() => void preview.refetch()} /> : (
        <div className="flex flex-col gap-3">
          {rows.length ? (
            <table aria-label={t('st.plans.migrateTitle', { name: plan?.name ?? '', version: plan?.currentVersion ?? '' })} className="w-full text-sm">
              <thead><tr className="text-left text-xs text-muted"><th>{t('st.plans.col.org')}</th><th>{t('st.plans.col.version')}</th><th>{t('st.plans.col.from')}</th><th>{t('st.plans.col.to')}</th><th>{t('st.plans.col.effective')}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.orgId}><td>{r.orgName ?? r.orgId}</td><td>v{r.fromVersion}</td><td>{money(r.fromCents)}</td><td>{money(r.toCents)}</td><td>{dateTime(r.effectiveAt)}</td></tr>
                ))}
              </tbody>
            </table>
          ) : <p className="m-0 text-sm text-muted">{preview.isPending ? t('st.plans.loading') : t('st.plans.nobodyToMigrate')}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
            <Button disabled={!rows.length} loading={migrate.isPending} onClick={() => migrate.mutate()}>{t('st.plans.migrateAll', { count: rows.length })}</Button>
          </div>
        </div>
      )}
    </Dialog>
  );
}

/** "1,000 listings · AI writer · Lola Growth": what the plan allows, from its entitlements. */
export function allowsOf(e: StaffPlanEntitlements | undefined, _t: T): string {
  return e ? planFeatureList(e).join(' · ') : '—';
}

/** Everything about a plan, read only: what it costs, what it allows, its versions and how many subscribe. */
function PlanViewSheet({ plan, onClose, onEdit, canEdit, t }: { plan: StaffPlan | null; onClose: () => void; onEdit: (p: StaffPlan) => void; canEdit: boolean; t: T }) {
  const row = (label: string, value: string) => (
    <div className="flex gap-3 text-sm">
      <dt className="w-36 shrink-0 text-muted">{label}</dt>
      <dd className="m-0 min-w-0 flex-1">{value}</dd>
    </div>
  );
  return (
    <Sheet open={plan !== null} onOpenChange={(o) => (o ? undefined : onClose())} title={plan?.name ?? ''} description={plan ? `${plan.code} · ${t(`st.plans.kind.${plan.kind}`)}` : undefined} width={480}>
      {plan ? (
        <div className="flex flex-col gap-4">
          {plan.tagline ? <p className="m-0 text-sm">{plan.tagline}</p> : null}
          <dl className="m-0 flex flex-col gap-2">
            {row(t('st.plans.col.status'), humanize(plan.status))}
            {row(t('st.plans.visibility'), t(`st.plans.visibility.${plan.visibility}`))}
            {row(t('st.plans.monthly'), money(plan.monthlyCents))}
            {row(t('st.plans.quarterly'), money(plan.quarterlyCents))}
            {row(t('st.plans.annual'), money(plan.annualCents))}
            {row(t('st.plans.trialDays'), String(plan.trialDays))}
            {row(t('st.plans.allows'), allowsOf(plan.entitlements, t))}
            {row(t('st.plans.charges.preview'), chargeTermsOf(plan.limits, plan.monthlyCents))}
            <PlanCommissionSummary code={plan.code} t={t} />
            {row(t('st.plans.col.subscribers'), String(plan.subscribers))}
          </dl>
          {plan.features.length > 0 ? (
            <section>
              <strong className="text-sm">{t('st.plans.features')}</strong>
              <ul className="m-0 mt-1 list-disc pl-5 text-sm">{plan.features.map((f) => <li key={f}>{f}</li>)}</ul>
            </section>
          ) : null}
          <section>
            <strong className="text-sm">{t('st.plans.versions')}</strong>
            <ul className="m-0 mt-1 flex list-none flex-col gap-1 p-0 text-sm">
              {plan.versions.map((v) => (
                <li key={v.version} className="flex gap-3">
                  <span className="w-10 font-semibold">v{v.version}</span>
                  <span className="flex-1">{humanize(v.status)}{v.publishedAt ? ` · ${dateTime(v.publishedAt)}` : ''}{v.note ? ` · ${v.note}` : ''}</span>
                  <span className="text-muted">{t('st.plans.subscribersCount', { count: v.subscribers })}</span>
                </li>
              ))}
            </ul>
          </section>
          {canEdit ? <div className="flex justify-end"><Button size="sm" onClick={() => onEdit(plan)}>{t('st.plans.edit')}</Button></div> : null}
        </div>
      ) : null}
    </Sheet>
  );
}

/** Plans: the catalogue with versions, publish, retire, migrations and what each plan allows. */
export function CataloguePanel() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { manage } = usePlanRights();
  const plans = useQuery(staffPlanQueries.plans(plansApi()));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'plans' });
  const [editing, setEditing] = useState<Editing>(null);
  const act = useMutation({
    mutationFn: (v: { code: string; op: 'publish' | 'retire' }) => (v.op === 'publish' ? plansApi().publish(v.code) : plansApi().retire(v.code)),
    onSuccess: (p, v) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.all });
      toast.show(v.op === 'publish' ? t('st.plans.published', { name: p.name, version: p.currentVersion }) : t('st.plans.retired', { name: p.name }));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const columns = useMemo<ColumnDef<StaffPlan>[]>(() => [
    { id: 'name', accessorFn: (p) => p.name, header: t('st.plans.col.plan'), size: 220, meta: { kind: 'strong' },
      cell: ({ row }) => <><strong className="font-semibold">{row.original.name}</strong><span className="block font-mono text-xs text-muted">{row.original.code}</span></> },
    { id: 'kind', accessorFn: (p) => p.kind, header: t('st.plans.kind'), size: 120, cell: ({ row }) => t(`st.plans.kind.${row.original.kind}`) },
    { id: 'monthlyCents', accessorFn: (p) => p.monthlyCents, header: t('st.plans.monthly'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.monthlyCents) },
    { id: 'quarterlyCents', accessorFn: (p) => p.quarterlyCents, header: t('st.plans.quarterly'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.quarterlyCents) },
    { id: 'annualCents', accessorFn: (p) => p.annualCents, header: t('st.plans.annual'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.annualCents) },
    { id: 'trialDays', accessorFn: (p) => p.trialDays, header: t('st.plans.trialDays'), size: 120, meta: { kind: 'number', align: 'end' } },
    { id: 'allows', accessorFn: (p) => allowsOf(p.entitlements, t), header: t('st.plans.allows'), size: 240, enableSorting: false },
    { id: 'version', accessorFn: (p) => p.currentVersion, header: t('st.plans.col.version'), size: 110,
      cell: ({ row }) => `v${row.original.currentVersion}${row.original.versions.some((v) => v.status === 'DRAFT') ? ` · ${t('st.plans.draftPending')}` : ''}` },
    { id: 'visibility', accessorFn: (p) => p.visibility, header: t('st.plans.visibility'), size: 120, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.visibility] ?? 'info'}>{t(`st.plans.visibility.${row.original.visibility}`)}</StatusPill> },
    { id: 'status', accessorFn: (p) => p.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
    { id: 'subscribers', accessorFn: (p) => p.subscribers, header: t('st.plans.col.subscribers'), size: 130, meta: { kind: 'number', align: 'end' } },
    {
      id: 'actions', header: () => <span className="at-visually-hidden">{t('st.plans.col.actions')}</span>, size: 64, enableSorting: false, meta: { export: false, label: t('st.plans.col.actions') },
      cell: ({ row }) => {
        const p = row.original;
        const draft = p.versions.some((v) => v.status === 'DRAFT');
        const old = p.versions.some((v) => v.status === 'SUPERSEDED' && v.subscribers > 0);
        return (
          <div className="flex items-center justify-end">
            <Menu label={t('st.plans.moreFor', { name: p.name })} width={220}
              trigger={<Button size="sm" variant="neutral" aria-label={t('st.plans.moreFor', { name: p.name })}><DotsThreeVertical size={16} weight="bold" aria-hidden /></Button>}>
              <MenuItem onSelect={() => setEditing({ mode: 'view', plan: p })}>{t('st.plans.view')}</MenuItem>
              {manage ? (
                <>
                  <MenuItem onSelect={() => setEditing({ mode: 'edit', plan: p })}>{t('st.plans.edit')}</MenuItem>
                  <MenuItem onSelect={() => setEditing({ mode: 'prices', plan: p })}>{t('st.plans.newVersion')}</MenuItem>
                  {draft ? <MenuItem onSelect={() => act.mutate({ code: p.code, op: 'publish' })}>{t('st.plans.publish')}</MenuItem> : null}
                  {old ? <MenuItem onSelect={() => setEditing({ mode: 'migrate', plan: p })}>{t('st.plans.migrate')}</MenuItem> : null}
                  {p.status !== 'RETIRED' ? <><MenuSeparator /><MenuItem className="text-danger" onSelect={() => act.mutate({ code: p.code, op: 'retire' })}>{t('st.plans.retire')}</MenuItem></> : null}
                </>
              ) : null}
            </Menu>
          </div>
        );
      }
    }
  ], [t, manage, act]);
  return (
    <div className="flex flex-col gap-4">
      <DataTable<StaffPlan> id="staff.plans.catalogue" label={t('st.plans.tab.plans')} columns={columns} data={plans.data ?? []} getRowId={(p) => p.code} rowLabel={(p) => p.name} onRowClick={(p) => setEditing({ mode: 'view', plan: p })}
        state={state} mode="client" loading={plans.isPending} fetching={plans.isFetching} error={plans.isError ? <ErrorState error={plans.error} onRetry={() => void plans.refetch()} /> : undefined}
        toolbar={manage ? <Button size="sm" onClick={() => setEditing({ mode: 'create' })}>{t('st.plans.newPlan')}</Button> : null} empty={t('st.plans.noPlans')} />
      <PlanViewSheet plan={editing?.mode === 'view' ? editing.plan : null} onClose={() => setEditing(null)} onEdit={(p) => setEditing({ mode: 'edit', plan: p })} canEdit={manage} t={t} />
      <PlanDialog key={editing?.mode === 'edit' ? editing.plan.code : editing?.mode ?? 'none'} editing={editing} onClose={() => setEditing(null)} t={t} />
      <PricesDialog key={editing?.mode === 'prices' ? `p-${editing.plan.code}` : 'p'} plan={editing?.mode === 'prices' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
      <MigrationDialog plan={editing?.mode === 'migrate' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
    </div>
  );
}
