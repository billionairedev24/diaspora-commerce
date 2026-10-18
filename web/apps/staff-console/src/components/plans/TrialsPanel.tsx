'use client';

import { staffPlanKeys, staffPlanQueries,type StaffTrialCampaign } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { humanize, shortDate } from '@/lib/format';

import { errorsOf, intOf, plansApi, STATUS_TONE, type T, usePlanRights } from './kit';

const dayOf = (iso: string | null) => (iso ? iso.slice(0, 10) : '');
const isoOf = (day: string, end = false) => (day ? `${day}T${end ? '23:59:59' : '00:00:00'}Z` : null);

/** A campaign matches by promo code (entered on the onboarding Plan step) or by sign-up window; the longest trial wins. */
function CampaignDialog({ campaign, open, onClose, t }: { campaign: StaffTrialCampaign | null; open: boolean; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const plans = useQuery(staffPlanQueries.plans(plansApi()));
  const [f, setF] = useState(() => ({
    name: campaign?.name ?? '', promoCode: campaign?.promoCode ?? '', trialDays: String(campaign?.trialDays ?? 60), signupFrom: dayOf(campaign?.signupFrom ?? null),
    signupTo: dayOf(campaign?.signupTo ?? null), kind: campaign?.kind ?? '', eligiblePlans: campaign?.eligiblePlans ?? [], maxRedemptions: campaign?.maxRedemptions == null ? '' : String(campaign.maxRedemptions)
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: f.name.trim(), promoCode: f.promoCode.trim() || null, trialDays: intOf(f.trialDays) ?? 0, signupFrom: isoOf(f.signupFrom), signupTo: isoOf(f.signupTo, true),
        kind: (f.kind || null) as 'SELLER' | 'PROVIDER' | null, eligiblePlans: f.eligiblePlans, maxRedemptions: intOf(f.maxRedemptions), expectedVersion: campaign?.version ?? null
      };
      return campaign ? plansApi().updateCampaign(campaign.id, body) : plansApi().createCampaign(body);
    },
    onSuccess: (c) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.campaigns() });
      toast.show(t('st.plans.campaignSaved', { name: c.name }));
      onClose();
    },
    onError: (e) => setErrors(errorsOf(e))
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
    <Sheet open={open} onOpenChange={(o) => !o && onClose()} title={campaign ? t('st.plans.editCampaign') : t('st.plans.newCampaign')} description={t('st.plans.campaignHelp')} width={560} closeLabel={t('st.plans.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-2">
        <TextField id="tc-name" label={t('st.plans.name')} value={f.name} error={errors.name} onChange={set('name')} />
        <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
          <TextField id="tc-code" label={t('st.plans.promoCode')} value={f.promoCode} error={errors.promoCode} onChange={(e) => {
            const v = e.currentTarget.value.toUpperCase();
            setF((x) => ({ ...x, promoCode: v }));
          }} />
          <TextField id="tc-days" label={t('st.plans.trialDays')} inputMode="numeric" value={f.trialDays} error={errors.trialDays} onChange={set('trialDays')} />
          <TextField id="tc-from" type="date" label={t('st.plans.signupFrom')} value={f.signupFrom} error={errors.signupFrom} onChange={set('signupFrom')} />
          <TextField id="tc-to" type="date" label={t('st.plans.signupTo')} value={f.signupTo} error={errors.signupTo} onChange={set('signupTo')} />
          <Select id="tc-kind" label={t('st.plans.kind')} value={f.kind} onChange={set('kind')}
            options={[{ value: '', label: t('st.plans.kind.ANY') }, { value: 'SELLER', label: t('st.plans.kind.SELLER') }, { value: 'PROVIDER', label: t('st.plans.kind.PROVIDER') }]} />
          <TextField id="tc-max" label={t('st.plans.maxRedemptions')} inputMode="numeric" value={f.maxRedemptions} error={errors.maxRedemptions} onChange={set('maxRedemptions')} />
        </div>
        <PlanPicker selected={f.eligiblePlans} onChange={(eligiblePlans) => setF((x) => ({ ...x, eligiblePlans }))} plans={(plans.data ?? []).map((p) => ({ code: p.code, name: p.name }))} t={t} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button type="submit" loading={save.isPending}>{t('st.plans.save')}</Button>
        </div>
      </form>
    </Sheet>
  );
}

/** Eligible plans (none ticked = every plan). */
export function PlanPicker({ selected, onChange, plans, t }: { selected: string[]; onChange: (codes: string[]) => void; plans: { code: string; name: string }[]; t: T }) {
  return (
    <fieldset className="m-0 flex flex-wrap gap-x-4 gap-y-1 border-0 p-0">
      <legend className="mb-1 text-sm font-semibold">{t('st.plans.eligiblePlans')}</legend>
      {plans.map((p) => (
        <label key={p.code} className="inline-flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={selected.includes(p.code)} onChange={(e) => onChange(e.currentTarget.checked ? [...selected, p.code] : selected.filter((c) => c !== p.code))} />
          {p.name}
        </label>
      ))}
    </fieldset>
  );
}

/** Trials & promotions: campaigns that lengthen the plan's default trial (sent to Stripe as the subscription's trial_end). */
export function TrialsPanel() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { manage } = usePlanRights();
  const campaigns = useQuery(staffPlanQueries.campaigns(plansApi()));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'trials' });
  const [editing, setEditing] = useState<{ campaign: StaffTrialCampaign | null } | null>(null);
  const status = useMutation({
    mutationFn: (v: { id: string; status: 'ACTIVE' | 'PAUSED' | 'ENDED' }) => plansApi().setCampaignStatus(v.id, v.status),
    onSuccess: () => void qc.invalidateQueries({ queryKey: staffPlanKeys.campaigns() }),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const columns = useMemo<ColumnDef<StaffTrialCampaign>[]>(() => [
    { id: 'name', accessorFn: (c) => c.name, header: t('st.plans.name'), size: 200, meta: { kind: 'strong' } },
    { id: 'promoCode', accessorFn: (c) => c.promoCode ?? '', header: t('st.plans.promoCode'), size: 130, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.promoCode ?? '—'}</span> },
    { id: 'trialDays', accessorFn: (c) => c.trialDays, header: t('st.plans.trialDays'), size: 90, meta: { kind: 'number', align: 'end' } },
    { id: 'window', accessorFn: (c) => c.signupFrom ?? '', header: t('st.plans.signupWindow'), size: 200,
      cell: ({ row }) => (row.original.signupFrom || row.original.signupTo ? `${shortDate(row.original.signupFrom)} – ${shortDate(row.original.signupTo)}` : '—') },
    { id: 'eligiblePlans', accessorFn: (c) => c.eligiblePlans.join(', '), header: t('st.plans.eligiblePlans'), size: 200, cell: ({ row }) => row.original.eligiblePlans.join(', ') || t('st.plans.allPlans') },
    { id: 'redemptions', accessorFn: (c) => c.redemptions, header: t('st.plans.redemptions'), size: 120, meta: { kind: 'number', align: 'end' },
      cell: ({ row }) => (row.original.maxRedemptions ? `${row.original.redemptions} / ${row.original.maxRedemptions}` : String(row.original.redemptions)) },
    { id: 'status', accessorFn: (c) => c.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
    {
      id: 'actions', header: () => <span className="at-visually-hidden">{t('st.plans.col.actions')}</span>, size: 220, enableSorting: false, meta: { export: false, label: t('st.plans.col.actions') },
      cell: ({ row }) => manage ? (
        <div className="flex flex-wrap gap-1">
          <Button size="sm" variant="link" onClick={() => setEditing({ campaign: row.original })}>{t('st.plans.edit')}</Button>
          {row.original.status === 'ACTIVE' ? <Button size="sm" variant="link" onClick={() => status.mutate({ id: row.original.id, status: 'PAUSED' })}>{t('st.plans.pause')}</Button> : null}
          {row.original.status === 'PAUSED' ? <Button size="sm" variant="link" onClick={() => status.mutate({ id: row.original.id, status: 'ACTIVE' })}>{t('st.plans.resume')}</Button> : null}
          {row.original.status !== 'ENDED' ? <Button size="sm" variant="link" className="text-danger" onClick={() => status.mutate({ id: row.original.id, status: 'ENDED' })}>{t('st.plans.end')}</Button> : null}
        </div>
      ) : null
    }
  ], [t, manage, status]);
  return (
    <>
      <DataTable<StaffTrialCampaign> id="staff.plans.trials" label={t('st.plans.tab.trials')} columns={columns} data={campaigns.data ?? []} getRowId={(c) => c.id} rowLabel={(c) => c.name}
        state={state} mode="client" loading={campaigns.isPending} fetching={campaigns.isFetching} error={campaigns.isError ? <ErrorState error={campaigns.error} onRetry={() => void campaigns.refetch()} /> : undefined}
        toolbar={manage ? <Button size="sm" onClick={() => setEditing({ campaign: null })}>{t('st.plans.newCampaign')}</Button> : null} empty={t('st.plans.noCampaigns')} />
      <CampaignDialog key={editing?.campaign?.id ?? (editing ? 'new' : 'none')} campaign={editing?.campaign ?? null} open={!!editing} onClose={() => setEditing(null)} t={t} />
    </>
  );
}
