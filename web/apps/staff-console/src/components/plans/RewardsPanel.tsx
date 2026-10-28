'use client';

import {
  type RewardComparison,
  type RewardMetric,
  type RewardType,
  type RewardWindowAnchor,
  staffPlanKeys,
  staffPlanQueries,
  type StaffRewardPreviewRow,
  type StaffRewardRule,
  type StaffRewardRuleInput
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { dollars, humanize, money, shortDate } from '@/lib/format';
import { sellerName } from '@/lib/names';

import { centsOf, dollarsOf, errorsOf, intOf, plansApi, STATUS_TONE, type T, usePlanRights } from './kit';
import { PlanPicker } from './TrialsPanel';

const METRICS: RewardMetric[] = ['GROSS_SALES', 'ACTIVE_LISTINGS', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'DAYS_SINCE_APPROVAL'];
const TYPES: RewardType[] = ['FREE_DAYS', 'CREDIT', 'PERCENT_OFF'];
const COMPARISONS: RewardComparison[] = ['AT_LEAST', 'LESS_THAN'];
const ANCHORS: RewardWindowAnchor[] = ['APPROVAL', 'SUBSCRIPTION_START'];

interface RuleForm {
  name: string;
  description: string;
  kind: string;
  metric: RewardMetric;
  threshold: string;
  windowDays: string;
  withinDaysOfApproval: string;
  /** S299: reach the threshold or stay under it; where the window starts; decided once at window end. */
  comparison: RewardComparison;
  windowAnchor: RewardWindowAnchor;
  checkAtWindowEnd: boolean;
  checkLeadHours: string;
  rewardType: RewardType;
  freeDays: string;
  credit: string;
  percentOff: string;
  durationInvoices: string;
  eligiblePlans: string[];
  maxGrants: string;
}

const formOf = (r: StaffRewardRule | null): RuleForm => ({
  name: r?.name ?? '', description: r?.description ?? '', kind: r?.kind ?? '', metric: r?.metric ?? 'GROSS_SALES',
  threshold: r ? (r.metric === 'GROSS_SALES' ? dollarsOf(r.threshold) : String(r.threshold)) : '', windowDays: r?.windowDays == null ? '' : String(r.windowDays),
  withinDaysOfApproval: r?.withinDaysOfApproval == null ? '' : String(r.withinDaysOfApproval), comparison: r?.comparison ?? 'AT_LEAST',
  windowAnchor: r?.windowAnchor ?? 'APPROVAL', checkAtWindowEnd: r ? r.checkAtWindowEnd && r.comparison === 'AT_LEAST' : false,
  checkLeadHours: String(r?.checkLeadHours ?? 24), rewardType: r?.rewardType ?? 'FREE_DAYS', freeDays: r?.freeDays == null ? '30' : String(r.freeDays),
  credit: dollarsOf(r?.creditCents), percentOff: r?.percentOff == null ? '' : String(r.percentOff), durationInvoices: r?.durationInvoices == null ? '' : String(r.durationInvoices),
  eligiblePlans: r?.eligiblePlans ?? [], maxGrants: r?.maxGrants == null ? '' : String(r.maxGrants)
});

/** The rule as the seller service takes it (gross sales are typed in dollars, stored in cents). */
export function inputOf(f: RuleForm, rule: StaffRewardRule | null): StaffRewardRuleInput {
  const threshold = f.metric === 'GROSS_SALES' ? centsOf(f.threshold) : intOf(f.threshold);
  return {
    name: f.name.trim(), description: f.description.trim() || null, kind: (f.kind || null) as 'SELLER' | 'PROVIDER' | null, metric: f.metric, threshold: threshold ?? 0,
    windowDays: intOf(f.windowDays), withinDaysOfApproval: intOf(f.withinDaysOfApproval), comparison: f.comparison, windowAnchor: f.windowAnchor,
    checkAtWindowEnd: f.comparison === 'LESS_THAN' || f.checkAtWindowEnd, checkLeadHours: intOf(f.checkLeadHours), rewardType: f.rewardType,
    freeDays: f.rewardType === 'FREE_DAYS' ? intOf(f.freeDays) : null, creditCents: f.rewardType === 'CREDIT' ? centsOf(f.credit) : null,
    percentOff: f.rewardType === 'PERCENT_OFF' && f.percentOff ? Number(f.percentOff) : null, durationInvoices: f.rewardType === 'PERCENT_OFF' ? intOf(f.durationInvoices) : null,
    eligiblePlans: f.eligiblePlans, maxGrants: intOf(f.maxGrants), expectedVersion: rule?.version ?? null, ruleId: rule?.id ?? null
  };
}

const decidedAtWindowEnd = (i: StaffRewardRuleInput) => i.comparison === 'LESS_THAN' || !!i.checkAtWindowEnd;

/**
 * S299: the rule in plain words, e.g. "Sellers on Seller Growth who sell less than $1,000 in the first 30 days of their
 * subscription get 30 free days (checked 24 h before the trial ends)."
 */
export function ruleSentence(i: StaffRewardRuleInput, plans: { code: string; name: string; trialDays: number }[], t: T): string {
  const amount = i.metric === 'GROSS_SALES' ? dollars(i.threshold / 100) : String(i.threshold);
  const cmp = t(`st.plans.s.cmp.${i.comparison ?? 'AT_LEAST'}` as MessageKey);
  const chosen = plans.filter((p) => i.eligiblePlans.includes(p.code));
  const parts = [t(`st.plans.s.who.${i.kind ?? 'ANY'}` as MessageKey)];
  if (chosen.length) parts.push(t('st.plans.s.on', { plans: chosen.map((p) => p.name).join(t('st.plans.s.or')) }));
  parts.push(t(`st.plans.s.metric.${i.metric}` as MessageKey, { cmp, amount }));
  const anchor = i.windowAnchor ?? 'APPROVAL';
  if (i.withinDaysOfApproval) parts.push(t(`st.plans.s.window.${anchor}` as MessageKey, { days: i.withinDaysOfApproval }));
  if (i.windowDays) parts.push(t('st.plans.s.rolling', { days: i.windowDays }));
  const reward = i.rewardType === 'FREE_DAYS' ? t('st.plans.s.reward.FREE_DAYS', { n: i.freeDays ?? 0 })
    : i.rewardType === 'CREDIT' ? t('st.plans.s.reward.CREDIT', { amount: dollars((i.creditCents ?? 0) / 100) })
      : t('st.plans.s.reward.PERCENT_OFF', { pct: i.percentOff ?? 0, n: i.durationInvoices ?? 1 });
  parts.push(t('st.plans.s.get', { reward }));
  if (decidedAtWindowEnd(i)) {
    const hours = i.checkLeadHours ?? 24;
    // The window ends with the trial when it starts with the subscription and lasts as long as every chosen plan's trial.
    const withTrial = anchor === 'SUBSCRIPTION_START' && chosen.length > 0 && chosen.every((p) => p.trialDays === i.withinDaysOfApproval);
    parts.push(t(withTrial ? 'st.plans.s.checkedTrial' : 'st.plans.s.checkedWindow', { hours }));
  } else {
    parts.push(t('st.plans.s.asReached'));
  }
  return `${parts.join(' ')}.`;
}

/** Who qualifies now: the unsaved rule evaluated over every organisation (nothing is granted). */
function Preview({ input, t }: { input: StaffRewardRuleInput; t: T }) {
  const [asked, setAsked] = useState<StaffRewardRuleInput | null>(null);
  const preview = useQuery(staffPlanQueries.rulePreview(plansApi(), asked));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'preview', syncUrl: false });
  const gross = asked?.metric === 'GROSS_SALES';
  const less = asked?.comparison === 'LESS_THAN';
  const columns = useMemo<ColumnDef<StaffRewardPreviewRow>[]>(() => {
    const fmt = (v: number) => (gross ? money(v) : String(v));
    return [
      { id: 'org', accessorFn: (r) => sellerName(t, r.orgName), header: t('st.plans.col.org'), size: 200 },
      { id: 'plan', accessorFn: (r) => r.planCode ?? '', header: t('st.plans.col.plan'), size: 140 },
      { id: 'value', accessorFn: (r) => r.value, header: less ? t('st.plans.col.soFar') : t('st.plans.col.progress'), size: 200,
        // A "less than" rule has no progress to show: the value so far against the line it must stay under.
        cell: ({ row }) => (less ? t('st.plans.soFar', { value: fmt(row.original.value), threshold: fmt(row.original.threshold) })
          : `${fmt(row.original.value)} / ${fmt(row.original.threshold)} (${row.original.percent}%)`) },
      { id: 'qualifies', accessorFn: (r) => r.status, header: t('st.plans.col.qualifies'), size: 220,
        cell: ({ row }) => {
          const r = row.original;
          if (r.qualifies) return <StatusPill tone="good">{t('st.plans.qualifies')}</StatusPill>;
          if (r.status === 'PENDING') return <StatusPill tone="info">{r.checkAt ? t('st.plans.pendingUntil', { date: shortDate(r.checkAt) }) : t('st.plans.pending')}</StatusPill>;
          if (r.status === 'GRANTED') return <span className="text-xs text-muted">{t('st.plans.granted')}</span>;
          if (r.status === 'CHECKED') return <span className="text-xs text-muted">{t('st.plans.checked')}</span>;
          return <span className="text-xs text-muted">{r.reason ?? t('st.plans.notYet')}</span>;
        } }
    ];
  }, [t, gross, less]);
  return (
    <section aria-label={t('st.plans.whoQualifies')} className="flex flex-col gap-2 rounded-md bg-surface-muted p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <strong className="text-sm">{t('st.plans.whoQualifies')}</strong>
        <Button size="sm" variant="neutral" loading={preview.isFetching} onClick={() => setAsked({ ...input })}>{t('st.plans.runPreview')}</Button>
      </div>
      {preview.isError ? <p role="alert" className="m-0 text-sm text-danger">{problemMessage(preview.error)}</p> : null}
      {preview.data ? (
        <>
          <p className="m-0 text-sm">
            {t('st.plans.previewSummary', { qualifying: preview.data.qualifying, evaluated: preview.data.evaluated })}
            {preview.data.pending ? ` · ${t('st.plans.previewPending', { pending: preview.data.pending })}` : ''}
          </p>
          <DataTable<StaffRewardPreviewRow> id="staff.plans.rule-preview" label={t('st.plans.whoQualifies')} columns={columns} data={preview.data.rows} getRowId={(r) => r.orgId} state={state} mode="client" />
        </>
      ) : null}
    </section>
  );
}

function RuleDialog({ rule, open, onClose, t }: { rule: StaffRewardRule | null; open: boolean; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const plans = useQuery(staffPlanQueries.plans(plansApi()));
  const [f, setF] = useState<RuleForm>(() => formOf(rule));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const input = inputOf(f, rule);
  const save = useMutation({
    mutationFn: (status: 'DRAFT' | 'ACTIVE' | null) => {
      const body = { ...input, status: status ?? rule?.status ?? 'DRAFT' };
      return rule ? plansApi().updateRule(rule.id, body) : plansApi().createRule(body);
    },
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: staffPlanKeys.rules() });
      toast.show(t('st.plans.ruleSaved', { name: r.name }));
      onClose();
    },
    onError: (e) => setErrors(errorsOf(e))
  });
  const planList = (plans.data ?? []).map((p) => ({ code: p.code, name: p.name, trialDays: p.trialDays }));
  const atEnd = decidedAtWindowEnd(input);
  const set = (k: keyof RuleForm) => (e: { currentTarget: { value: string } }) => {
    const v = e.currentTarget.value;
    setF((x) => ({ ...x, [k]: v }));
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    save.mutate(null);
  };
  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()} title={rule ? t('st.plans.editRule') : t('st.plans.newRule')} description={t('st.plans.ruleHelp')} width={720} closeLabel={t('st.plans.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-2">
        <TextField id="rr-name" label={t('st.plans.name')} value={f.name} error={errors.name} onChange={set('name')} />
        <TextField id="rr-desc" label={t('st.plans.description')} value={f.description} onChange={set('description')} />
        <div className="grid grid-cols-1 gap-x-3 md:grid-cols-3">
          <Select id="rr-metric" label={t('st.plans.metric')} value={f.metric} onChange={set('metric')} options={METRICS.map((m) => ({ value: m, label: t(`st.plans.metric.${m}`) }))} />
          <TextField id="rr-threshold" label={f.metric === 'GROSS_SALES' ? t('st.plans.thresholdDollars') : t('st.plans.threshold')} inputMode="decimal" value={f.threshold} error={errors.threshold} onChange={set('threshold')} />
          <Select id="rr-kind" label={t('st.plans.kind')} value={f.kind} onChange={set('kind')}
            options={[{ value: '', label: t('st.plans.kind.ANY') }, { value: 'SELLER', label: t('st.plans.kind.SELLER') }, { value: 'PROVIDER', label: t('st.plans.kind.PROVIDER') }]} />
          <Select id="rr-comparison" label={t('st.plans.comparison')} value={f.comparison} onChange={set('comparison')} error={errors.comparison}
            options={COMPARISONS.map((c) => ({ value: c, label: t(`st.plans.comparison.${c}`) }))} />
          <Select id="rr-anchor" label={t('st.plans.windowAnchor')} value={f.windowAnchor} onChange={set('windowAnchor')} error={errors.windowAnchor}
            options={ANCHORS.map((a) => ({ value: a, label: t(`st.plans.windowAnchor.${a}`) }))} />
          <TextField id="rr-within" label={f.windowAnchor === 'SUBSCRIPTION_START' ? t('st.plans.withinDaysOfStart') : t('st.plans.withinDaysOfApproval')} inputMode="numeric"
            value={f.withinDaysOfApproval} error={errors.withinDaysOfApproval} onChange={set('withinDaysOfApproval')} />
          <TextField id="rr-window" label={t('st.plans.windowDays')} inputMode="numeric" value={f.windowDays} error={errors.windowDays} onChange={set('windowDays')} />
          {atEnd ? <TextField id="rr-lead" label={t('st.plans.checkLeadHours')} inputMode="numeric" value={f.checkLeadHours} error={errors.checkLeadHours} onChange={set('checkLeadHours')} /> : null}
          <TextField id="rr-max" label={t('st.plans.maxGrants')} inputMode="numeric" value={f.maxGrants} error={errors.maxGrants} onChange={set('maxGrants')} />
        </div>
        {f.comparison === 'AT_LEAST' ? (
          <label className="inline-flex items-center gap-1.5 text-sm">
            <input type="checkbox" checked={f.checkAtWindowEnd} onChange={(e) => { const v = e.currentTarget.checked; setF((x) => ({ ...x, checkAtWindowEnd: v })); }} />
            {t('st.plans.checkAtWindowEnd')}
          </label>
        ) : null}
        {atEnd ? <p className="m-0 text-xs text-muted">{t('st.plans.windowEndHelp', { hours: input.checkLeadHours ?? 24 })}</p> : null}
        <div className="grid grid-cols-1 gap-x-3 md:grid-cols-3">
          <Select id="rr-type" label={t('st.plans.rewardType')} value={f.rewardType} onChange={set('rewardType')} options={TYPES.map((x) => ({ value: x, label: t(`st.plans.rewardType.${x}`) }))} />
          {f.rewardType === 'FREE_DAYS' ? <TextField id="rr-days" label={t('st.plans.freeDays')} inputMode="numeric" value={f.freeDays} error={errors.freeDays} onChange={set('freeDays')} /> : null}
          {f.rewardType === 'CREDIT' ? <TextField id="rr-credit" label={t('st.plans.creditDollars')} inputMode="decimal" value={f.credit} error={errors.creditCents} onChange={set('credit')} /> : null}
          {f.rewardType === 'PERCENT_OFF' ? (
            <>
              <TextField id="rr-pct" label={t('st.plans.percentOff')} inputMode="decimal" value={f.percentOff} error={errors.percentOff} onChange={set('percentOff')} />
              <TextField id="rr-invoices" label={t('st.plans.durationInvoices')} inputMode="numeric" value={f.durationInvoices} error={errors.durationInvoices} onChange={set('durationInvoices')} />
            </>
          ) : null}
        </div>
        <PlanPicker selected={f.eligiblePlans} onChange={(eligiblePlans) => setF((x) => ({ ...x, eligiblePlans }))} plans={planList} t={t} />
        <p className="m-0 rounded-md border border-border p-3 text-sm" data-testid="rule-sentence" aria-live="polite">
          <strong>{t('st.plans.inPlainWords')}</strong> {ruleSentence(input, planList, t)}
        </p>
        <Preview input={input} t={t} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.plans.cancel')}</Button>
          <Button type="submit" variant="neutral" loading={save.isPending && save.variables === null}>{t('st.plans.save')}</Button>
          {rule?.status !== 'ACTIVE' ? <Button loading={save.isPending && save.variables === 'ACTIVE'} onClick={() => { setErrors({}); save.mutate('ACTIVE'); }}>{t('st.plans.saveAndActivate')}</Button> : null}
        </div>
      </form>
    </Sheet>
  );
}

/** Rewards: rules that grant free days, a credit or a discount when a seller reaches a threshold. */
export function RewardsPanel() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { manage } = usePlanRights();
  const rules = useQuery(staffPlanQueries.rules(plansApi()));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'rules' });
  const [editing, setEditing] = useState<{ rule: StaffRewardRule | null } | null>(null);
  const status = useMutation({
    mutationFn: (v: { id: string; status: 'ACTIVE' | 'PAUSED' | 'ENDED' }) => plansApi().setRuleStatus(v.id, v.status),
    onSuccess: () => void qc.invalidateQueries({ queryKey: staffPlanKeys.rules() }),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const columns = useMemo<ColumnDef<StaffRewardRule>[]>(() => [
    { id: 'name', accessorFn: (r) => r.name, header: t('st.plans.name'), size: 220, meta: { kind: 'strong' } },
    { id: 'condition', accessorFn: (r) => r.conditionLabel, header: t('st.plans.col.condition'), size: 280 },
    { id: 'reward', accessorFn: (r) => r.rewardLabel, header: t('st.plans.col.reward'), size: 160 },
    { id: 'grants', accessorFn: (r) => r.grantsCount, header: t('st.plans.col.grants'), size: 110, meta: { kind: 'number', align: 'end' },
      cell: ({ row }) => (row.original.maxGrants ? `${row.original.grantsCount} / ${row.original.maxGrants}` : String(row.original.grantsCount)) },
    { id: 'status', accessorFn: (r) => r.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
    {
      id: 'actions', header: () => <span className="at-visually-hidden">{t('st.plans.col.actions')}</span>, size: 240, enableSorting: false, meta: { export: false, label: t('st.plans.col.actions') },
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-1">
          <Button size="sm" variant="link" onClick={() => setEditing({ rule: row.original })}>{manage ? t('st.plans.edit') : t('st.plans.view')}</Button>
          {manage && row.original.status !== 'ACTIVE' && row.original.status !== 'ENDED' ? <Button size="sm" variant="link" onClick={() => status.mutate({ id: row.original.id, status: 'ACTIVE' })}>{t('st.plans.activate')}</Button> : null}
          {manage && row.original.status === 'ACTIVE' ? <Button size="sm" variant="link" onClick={() => status.mutate({ id: row.original.id, status: 'PAUSED' })}>{t('st.plans.pause')}</Button> : null}
          {manage && row.original.status !== 'ENDED' ? <Button size="sm" variant="link" className="text-danger" onClick={() => status.mutate({ id: row.original.id, status: 'ENDED' })}>{t('st.plans.end')}</Button> : null}
        </div>
      )
    }
  ], [t, manage, status]);
  return (
    <>
      <DataTable<StaffRewardRule> id="staff.plans.rules" label={t('st.plans.tab.rewards')} columns={columns} data={rules.data ?? []} getRowId={(r) => r.id} rowLabel={(r) => r.name}
        state={state} mode="client" loading={rules.isPending} fetching={rules.isFetching} error={rules.isError ? <ErrorState error={rules.error} onRetry={() => void rules.refetch()} /> : undefined}
        toolbar={manage ? <Button size="sm" onClick={() => setEditing({ rule: null })}>{t('st.plans.newRule')}</Button> : null} empty={t('st.plans.noRules')} />
      <RuleDialog key={editing?.rule?.id ?? (editing ? 'new' : 'none')} rule={editing?.rule ?? null} open={!!editing} onClose={() => setEditing(null)} t={t} />
    </>
  );
}
