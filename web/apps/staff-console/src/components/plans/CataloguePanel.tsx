'use client';

import { type PlanVisibility, type StaffPlan, staffPlanKeys, staffPlanQueries,type StaffPlanSyncLine } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
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
    sortOrder: String(plan?.sortOrder ?? 100), monthly: '', quarterly: '', annual: '', trialDays: '30'
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const meta = { name: f.name.trim(), tagline: f.tagline.trim() || null, features: f.features.split('\n').map((x) => x.trim()).filter(Boolean),
        visibility: f.visibility as PlanVisibility, sortOrder: intOf(f.sortOrder) ?? 100 };
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

/** Moves subscribers of older versions to the current one at their next renewal (Stripe item swap, no proration). */
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

/** Stripe sync: every published price against Stripe; drift creates a new Price and archives the old one (never edits). */
function SyncCard({ t }: { t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const { manage } = usePlanRights();
  const sync = useQuery(staffPlanQueries.sync(plansApi()));
  const run = useMutation({
    mutationFn: () => plansApi().syncStripe(),
    onSuccess: (r) => {
      qc.setQueryData(staffPlanKeys.sync(), r);
      void qc.invalidateQueries({ queryKey: staffPlanKeys.plans() });
      toast.show(t('st.plans.syncDone'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'sync', syncUrl: false });
  const columns = useMemo<ColumnDef<StaffPlanSyncLine>[]>(() => [
    { id: 'plan', accessorFn: (l) => `${l.planName ?? l.planCode} v${l.version}`, header: t('st.plans.col.plan'), size: 200 },
    { id: 'cadence', accessorFn: (l) => l.cadence, header: t('st.plans.col.cadence'), size: 110, cell: ({ row }) => humanize(row.original.cadence) },
    { id: 'amount', accessorFn: (l) => l.amountCents, header: t('st.plans.col.ours'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountCents) },
    { id: 'stripe', accessorFn: (l) => l.stripeAmountCents ?? -1, header: t('st.plans.col.stripe'), size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => (row.original.stripeAmountCents == null ? '—' : money(row.original.stripeAmountCents)) },
    { id: 'state', accessorFn: (l) => l.state, header: t('st.plans.col.state'), size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.state] ?? 'info'}>{humanize(row.original.state)}</StatusPill> },
    { id: 'price', accessorFn: (l) => l.stripePriceId ?? '', header: t('st.plans.col.priceId'), size: 220, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.stripePriceId ?? row.original.error ?? ''}</span> }
  ], [t]);
  const drift = sync.data?.drifted ?? 0;
  return (
    <Card title={t('st.plans.syncTitle')} actions={manage ? <Button size="sm" variant="neutral" loading={run.isPending} onClick={() => run.mutate()}>{t('st.plans.syncNow')}</Button> : null}>
      {sync.data ? (
        drift ? <Notice tone="warning" title={t('st.plans.drift', { count: drift })}>{t('st.plans.driftHelp')}</Notice>
          : <p className="m-0 text-sm text-success">{t('st.plans.inSync', { at: dateTime(sync.data.checkedAt) })}</p>
      ) : null}
      <DataTable<StaffPlanSyncLine> id="staff.plans.sync" label={t('st.plans.syncTitle')} columns={columns} data={sync.data?.lines ?? []} getRowId={(l) => `${l.planCode}-${l.version}-${l.cadence}`}
        state={state} mode="client" loading={sync.isPending} fetching={sync.isFetching} error={sync.isError ? <ErrorState error={sync.error} onRetry={() => void sync.refetch()} /> : undefined} />
    </Card>
  );
}

/** Plans: the catalogue with versions, publish, retire, migrations and the Stripe sync status. */
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
      <SyncCard t={t} />
      <PlanDialog key={editing?.mode === 'edit' ? editing.plan.code : editing?.mode ?? 'none'} editing={editing} onClose={() => setEditing(null)} t={t} />
      <PricesDialog key={editing?.mode === 'prices' ? `p-${editing.plan.code}` : 'p'} plan={editing?.mode === 'prices' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
      <MigrationDialog plan={editing?.mode === 'migrate' ? editing.plan : null} onClose={() => setEditing(null)} t={t} />
    </div>
  );
}
