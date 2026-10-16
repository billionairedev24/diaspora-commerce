'use client';

import { type PlanVisibility, type StaffPlan, type StaffPlanEntitlements, staffPlanKeys, staffPlanQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime, humanize, money } from '@/lib/format';

import { centsOf, dollarsOf, errorsOf, intOf, plansApi, STATUS_TONE, type T, usePlanRights } from './kit';

type Editing = { mode: 'create' } | { mode: 'edit'; plan: StaffPlan } | { mode: 'prices'; plan: StaffPlan } | { mode: 'migrate'; plan: StaffPlan } | null;

const VISIBILITY: PlanVisibility[] = ['PUBLIC', 'HIDDEN', 'INVITE_ONLY'];

/** Create a plan, or edit its name, tagline, features, visibility and order (prices and trial are versioned: see PricesDialog). */
function PlanDialog({ editing, onClose, t }: { editing: Editing; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const plan = editing?.mode === 'edit' ? editing.plan : null;
  const open = editing?.mode === 'create' || editing?.mode === 'edit';
  const [f, setF] = useState(() => ({
    code: '', kind: 'SELLER', name: plan?.name ?? '', tagline: plan?.tagline ?? '', features: plan?.features.join('\n') ?? '', visibility: plan?.visibility ?? 'PUBLIC',
    sortOrder: String(plan?.sortOrder ?? 100), monthly: '', quarterly: '', annual: '', trialDays: '30',
    unlimited: plan?.entitlements?.listingsUnlimited ?? false, listings: String(plan?.entitlements?.listings ?? 50), aiWriter: plan?.entitlements?.aiListingWriter ?? false,
    lola: plan?.entitlements?.lolaTier ?? 'ESSENTIALS'
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const cap = intOf(f.listings);
      if (!f.unlimited && (cap == null || Number.isNaN(cap) || cap < 0)) throw new Error(t('st.plans.badListings'));
      // Entitlements live in `limits` (the seller service enforces them); other keys stay as plan information.
      const limits = { ...(plan?.limits ?? {}), listings: f.unlimited ? 'unlimited' : String(cap), aiListingWriter: String(f.aiWriter), lolaTier: f.lola };
      const meta = { name: f.name.trim(), tagline: f.tagline.trim() || null, features: f.features.split('\n').map((x) => x.trim()).filter(Boolean),
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
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={plan ? t('st.plans.editTitle', { name: plan.name }) : t('st.plans.createTitle')} width={560} closeLabel={t('st.plans.close')}>
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
        <label className="flex flex-col gap-1 text-sm font-semibold" htmlFor="plan-features">
          {t('st.plans.features')}
          <textarea id="plan-features" rows={4} className="rounded-sm border border-border-strong bg-surface p-2 font-normal" value={f.features} onChange={set('features')} />
        </label>
        <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
          <Select id="plan-visibility" label={t('st.plans.visibility')} value={f.visibility} onChange={set('visibility')}
            options={VISIBILITY.map((v) => ({ value: v, label: t(`st.plans.visibility.${v}`) }))} />
          <TextField id="plan-sort" label={t('st.plans.sortOrder')} inputMode="numeric" value={f.sortOrder} onChange={set('sortOrder')} />
        </div>
        <fieldset className="m-0 flex flex-col gap-2 rounded-md border border-border p-3">
          <legend className="px-1 text-sm font-semibold">{t('st.plans.allows')}</legend>
          <p className="m-0 text-xs text-muted">{t('st.plans.allowsHelp')}</p>
          <div className="grid grid-cols-1 items-end gap-x-3 md:grid-cols-2">
            <TextField id="plan-listings" label={t('st.plans.listings')} inputMode="numeric" value={f.unlimited ? '' : f.listings} disabled={f.unlimited} error={errors['limits.listings']}
              onChange={set('listings')} />
            <label className="mb-2 inline-flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.unlimited} onChange={(e) => setF((x) => ({ ...x, unlimited: e.target.checked }))} />
              {t('st.plans.listingsUnlimited')}
            </label>
          </div>
          <div className="grid grid-cols-1 items-end gap-x-3 md:grid-cols-2">
            <Select id="plan-lola" label={t('st.plans.lolaTier')} value={f.lola} onChange={set('lola')}
              options={(['ESSENTIALS', 'GROWTH', 'PRO'] as const).map((v) => ({ value: v, label: t(`st.plans.lola.${v}`) }))} />
            <label className="mb-2 inline-flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.aiWriter} onChange={(e) => setF((x) => ({ ...x, aiWriter: e.target.checked }))} />
              {t('st.plans.aiWriter')}
            </label>
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
    </Dialog>
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
export function allowsOf(e: StaffPlanEntitlements | undefined, t: T): string {
  if (!e) return '—';
  return [
    e.listingsUnlimited || e.listings === null ? t('st.plans.listingsUnlimitedShort') : t('st.plans.listingsShort', { count: e.listings.toLocaleString('en-US') }),
    e.aiListingWriter ? t('st.plans.aiWriterShort') : null,
    t(`st.plans.lola.${e.lolaTier}`)
  ].filter(Boolean).join(' · ');
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
    { id: 'kind', accessorFn: (p) => p.kind, header: t('st.plans.kind'), size: 100, cell: ({ row }) => t(`st.plans.kind.${row.original.kind}`) },
    { id: 'monthlyCents', accessorFn: (p) => p.monthlyCents, header: t('st.plans.monthly'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.monthlyCents) },
    { id: 'quarterlyCents', accessorFn: (p) => p.quarterlyCents, header: t('st.plans.quarterly'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.quarterlyCents) },
    { id: 'annualCents', accessorFn: (p) => p.annualCents, header: t('st.plans.annual'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.annualCents) },
    { id: 'trialDays', accessorFn: (p) => p.trialDays, header: t('st.plans.trialDays'), size: 90, meta: { kind: 'number', align: 'end' } },
    { id: 'allows', accessorFn: (p) => allowsOf(p.entitlements, t), header: t('st.plans.allows'), size: 240, enableSorting: false },
    { id: 'version', accessorFn: (p) => p.currentVersion, header: t('st.plans.col.version'), size: 110,
      cell: ({ row }) => `v${row.original.currentVersion}${row.original.versions.some((v) => v.status === 'DRAFT') ? ` · ${t('st.plans.draftPending')}` : ''}` },
    { id: 'visibility', accessorFn: (p) => p.visibility, header: t('st.plans.visibility'), size: 120, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.visibility] ?? 'info'}>{t(`st.plans.visibility.${row.original.visibility}`)}</StatusPill> },
    { id: 'status', accessorFn: (p) => p.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
    { id: 'subscribers', accessorFn: (p) => p.subscribers, header: t('st.plans.col.subscribers'), size: 110, meta: { kind: 'number', align: 'end' } },
    {
      id: 'actions', header: () => <span className="at-visually-hidden">{t('st.plans.col.actions')}</span>, size: 300, enableSorting: false, meta: { export: false, label: t('st.plans.col.actions') },
      cell: ({ row }) => {
        const p = row.original;
        if (!manage) return null;
        const draft = p.versions.some((v) => v.status === 'DRAFT');
        const old = p.versions.some((v) => v.status === 'SUPERSEDED' && v.subscribers > 0);
        return (
          <div className="flex flex-wrap gap-1">
            <Button size="sm" variant="link" onClick={() => setEditing({ mode: 'edit', plan: p })}>{t('st.plans.edit')}</Button>
            <Button size="sm" variant="link" onClick={() => setEditing({ mode: 'prices', plan: p })}>{t('st.plans.newVersion')}</Button>
            {draft ? <Button size="sm" variant="link" onClick={() => act.mutate({ code: p.code, op: 'publish' })}>{t('st.plans.publish')}</Button> : null}
            {old ? <Button size="sm" variant="link" onClick={() => setEditing({ mode: 'migrate', plan: p })}>{t('st.plans.migrate')}</Button> : null}
            {p.status !== 'RETIRED' ? <Button size="sm" variant="link" className="text-danger" onClick={() => act.mutate({ code: p.code, op: 'retire' })}>{t('st.plans.retire')}</Button> : null}
          </div>
        );
      }
    }
  ], [t, manage, act]);
  return (
    <div className="flex flex-col gap-4">
      <DataTable<StaffPlan> id="staff.plans.catalogue" label={t('st.plans.tab.plans')} columns={columns} data={plans.data ?? []} getRowId={(p) => p.code} rowLabel={(p) => p.name}
        state={state} mode="client" loading={plans.isPending} fetching={plans.isFetching} error={plans.isError ? <ErrorState error={plans.error} onRetry={() => void plans.refetch()} /> : undefined}
        toolbar={manage ? <Button size="sm" onClick={() => setEditing({ mode: 'create' })}>{t('st.plans.newPlan')}</Button> : null} empty={t('st.plans.noPlans')} />
      <PlanDialog key={editing?.mode === 'edit' ? editing.plan.code : editing?.mode ?? 'none'} editing={editing} onClose={() => setEditing(null)} t={t} />
      <PricesDialog key={editing?.mode === 'prices' ? `p-${editing.plan.code}` : 'p'} plan={editing?.mode === 'prices' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
      <MigrationDialog plan={editing?.mode === 'migrate' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
    </div>
  );
}
