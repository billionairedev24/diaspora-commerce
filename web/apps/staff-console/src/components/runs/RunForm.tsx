'use client';

import {
  isApiError,
  type RunPlanInput,
  type RunScheduleInput,
  runScheduleProblems,
  type StaffRunDetail,
  staffRunKeys,
  type StaffRunLocation,
  staffRunQueries
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useId, useMemo, useState } from 'react';

import { controlClass } from '@/components/preorders/primitives';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { formNumbers, newRunForm, type RunForm, runFormChanged, runFormOf, runInputOf, zoneOf } from './model';
import { useAreaText } from './RunArea';
import { RunBreakEven } from './RunBreakEven';
import { useDebounced, useNow, useRunsApi } from './shared';

type Errors = Partial<Record<string, string>>;

/** The words for a problem the shared checks report on a field. */
function useProblemText() {
  const t = useT();
  return (field: string, problem: string): string => {
    if (problem === 'order') {
      const key = `st.runs.lc.order.${field}` as MessageKey;
      const text = t(key);
      return text === key ? t('st.runs.lc.problem.range') : text;
    }
    return t(`st.runs.lc.problem.${problem}` as MessageKey);
  };
}

/** The checks the form makes before it sends anything: the shared ones, plus the numbers people typed and a cutoff in the past. */
export function formErrors(form: RunForm, tz: string, creating: boolean, nowMs: number, say: (field: string, problem: string) => string, amountText: string): Errors {
  const out: Errors = {};
  const n = formNumbers(form);
  const input = runInputOf(form, tz);
  const typed: [keyof RunForm, number | null][] = [
    ['minOrders', n.minOrders],
    ['capacityOrders', n.capacityOrders],
    ['extendDays', n.extendDays]
  ];
  typed.forEach(([k, v]) => {
    if (v !== null && Number.isNaN(v)) out[k] = say(k, 'whole');
  });
  if (form.feeMode === 'CUSTOM' && (n.feeMinor === null || Number.isNaN(n.feeMinor))) out.fee = amountText;
  // A time that does not convert is reported as missing.
  const timed: [keyof RunForm, keyof RunScheduleInput][] = [
    ['windowStart', 'windowStart'],
    ['windowEnd', 'windowEnd'],
    ['cutoffAt', 'cutoffAt']
  ];
  timed.forEach(([k, f]) => {
    if (!input[f]) out[k] = say(k, 'required');
  });
  if (form.opensAt && !input.opensAt) out.opensAt = say('opensAt', 'required');
  if (form.sellerPickupAt && !input.sellerPickupAt) out.sellerPickupAt = say('sellerPickupAt', 'required');
  const checks = runScheduleProblems(
    {
      ...input,
      minOrders: Number.isNaN(n.minOrders ?? 0) ? null : n.minOrders,
      capacityOrders: Number.isNaN(n.capacityOrders ?? 0) ? null : n.capacityOrders,
      feeMinor: n.feeMinor !== null && Number.isNaN(n.feeMinor) ? null : n.feeMinor
    },
    creating
  );
  Object.entries(checks).forEach(([field, problem]) => {
    const key = field === 'feeMinor' ? 'fee' : field;
    if (!out[key] && problem) out[key] = say(field, problem);
  });
  if (n.extendDays !== null && !Number.isNaN(n.extendDays) && (n.extendDays < 1 || n.extendDays > 30)) out.extendDays = say('extendDays', 'range');
  if (creating && !out.cutoffAt && input.cutoffAt && Date.parse(input.cutoffAt) <= nowMs) out.cutoffAt = say('cutoffAt', 'past');
  return out;
}

function RunFormFields({
  initial,
  run,
  locations,
  onClose,
  onSaved
}: {
  initial: RunForm;
  run: StaffRunDetail | null;
  locations: StaffRunLocation[];
  onClose: () => void;
  onSaved: (run: StaffRunDetail) => void;
}) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const qc = useQueryClient();
  const toast = useToast();
  const areaText = useAreaText();
  const say = useProblemText();
  const creating = run === null;
  const formId = useId();
  const [form, setForm] = useState<RunForm>(initial);
  const [attempted, setAttempted] = useState(false);
  const [server, setServer] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const location = locations.find((l) => l.id === form.locationId);
  const tz = zoneOf(location?.timeZone);
  const set = (patch: Partial<RunForm>) => {
    setForm((f) => ({ ...f, ...patch }));
    setServer({});
    setFailure(null);
  };
  // An open run keeps what customers ordered against: its fee, cutoff and drop-off.
  const lockedOpen = run !== null && (run.status === 'OPEN' || run.status === 'EXTENDED');
  const now = useNow(30_000);
  const errors = formErrors(form, tz, creating, now, say, t('st.runs.lc.problem.amount'));
  const shown: Errors = attempted ? { ...errors, ...server } : server;
  const changed = creating || runFormChanged(form, initial);

  // Break-even beside the minimum: calculated from the form as it stands, after the typing settles.
  const n = formNumbers(form);
  const numbersOk = (v: number | null) => v === null || !Number.isNaN(v);
  const planBody = useMemo<RunPlanInput | null>(() => {
    if (!form.locationId || !numbersOk(n.minOrders) || !numbersOk(n.capacityOrders) || (form.feeMode === 'CUSTOM' && (n.feeMinor === null || Number.isNaN(n.feeMinor)))) return null;
    return {
      locationId: form.locationId,
      feeMinor: form.feeMode === 'CUSTOM' ? n.feeMinor : null,
      minOrders: n.minOrders ?? location?.defaultMinOrders ?? null,
      capacityOrders: n.capacityOrders ?? location?.defaultCapacityOrders ?? null,
      dropMode: form.dropMode,
      expectedOrders: n.minOrders ?? location?.defaultMinOrders ?? null
    };
  }, [form.locationId, form.feeMode, form.dropMode, n.minOrders, n.capacityOrders, n.feeMinor, location?.defaultMinOrders, location?.defaultCapacityOrders]);
  const settledBody = useDebounced(planBody, 400);
  const plan = useQuery({ ...staffRunQueries.runPlan(api, settledBody ?? { locationId: '' }), enabled: settledBody !== null, placeholderData: (prev) => prev });

  const save = useMutation({
    mutationFn: (input: RunScheduleInput) => (run ? api.updateRun(run.id, input) : api.createRun(input)),
    onSuccess: (saved) => {
      qc.setQueryData(staffRunKeys.run(saved.id), saved);
      void qc.invalidateQueries({ queryKey: staffRunKeys.all });
      toast.show(t(creating ? 'st.runs.lc.toast.created' : 'st.runs.lc.toast.saved'));
      onSaved(saved);
    },
    onError: (e) => {
      if (isApiError(e) && Object.keys(e.fieldErrors).length) {
        setServer(Object.fromEntries(Object.entries(e.fieldErrors).map(([k, v]) => [k === 'feeMinor' ? 'fee' : k, v])));
      }
      setFailure(problemMessage(e));
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (save.isPending || !changed) return;
    setAttempted(true);
    if (Object.keys(errors).length > 0) return;
    save.mutate(runInputOf(form, tz));
  };

  const ruleFee = form.feeMode === 'RULE' && plan.data ? fmt.money(plan.data.feeMinor, plan.data.currency) : null;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5" data-testid="run-form" aria-label={t(creating ? 'st.runs.lc.form.createTitle' : 'st.runs.lc.form.editTitle')}>
      {creating ? <p className="m-0 max-w-[70ch] text-sm text-muted">{t('st.runs.lc.form.createHelp')}</p> : null}

      <section className="flex flex-col gap-3">
        {creating ? (
          <Select
            label={t('st.runs.lc.form.location')}
            value={form.locationId}
            placeholder={t('st.runs.lc.form.locationPlaceholder')}
            options={locations.filter((l) => l.active !== false).map((l) => ({ value: l.id, label: l.name }))}
            error={shown.locationId}
            onChange={(e) => {
              const next = locations.find((l) => l.id === e.currentTarget.value);
              setForm((f) => ({ ...newRunForm(next, Date.now()), notes: f.notes }));
              setServer({});
            }}
          />
        ) : (
          <div className="flex flex-col gap-1">
            <span className="text-sm font-semibold">{t('st.runs.lc.form.location')}</span>
            <span className="text-sm">{location?.name ?? run?.locationName ?? ''}</span>
          </div>
        )}
        {location ? (
          <div className="rounded-md border border-border bg-surface-muted p-3 text-sm" data-testid="run-form-area">
            <div className="font-semibold">{t('st.runs.lc.form.area')}</div>
            <div>{areaText(location)}</div>
            <div className="mt-1 text-xs text-muted">{t('st.runs.lc.form.areaHelp')}</div>
            <div className="text-xs text-muted">{t('st.runs.lc.form.tz', { tz })}</div>
          </div>
        ) : null}
      </section>

      <section aria-label={t('st.runs.lc.form.schedule')} className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
        <TextField type="datetime-local" label={t('st.runs.lc.form.windowStart')} value={form.windowStart} error={shown.windowStart} onChange={(e) => set({ windowStart: e.currentTarget.value })} />
        <TextField type="datetime-local" label={t('st.runs.lc.form.windowEnd')} value={form.windowEnd} error={shown.windowEnd} onChange={(e) => set({ windowEnd: e.currentTarget.value })} />
        <TextField
          type="datetime-local"
          label={t('st.runs.lc.form.cutoff')}
          help={lockedOpen ? t('st.runs.lc.form.fixedOnceOpen') : t('st.runs.lc.form.cutoffHelp')}
          value={form.cutoffAt}
          disabled={lockedOpen}
          error={shown.cutoffAt}
          onChange={(e) => set({ cutoffAt: e.currentTarget.value })}
        />
        <TextField
          type="datetime-local"
          label={t('st.runs.lc.form.opensAt')}
          help={t('st.runs.lc.form.opensHelp')}
          value={form.opensAt}
          error={shown.opensAt}
          onChange={(e) => set({ opensAt: e.currentTarget.value })}
        />
        <TextField type="datetime-local" label={t('st.runs.lc.form.sellerPickup')} value={form.sellerPickupAt} error={shown.sellerPickupAt} onChange={(e) => set({ sellerPickupAt: e.currentTarget.value })} />
      </section>

      <section aria-label={t('st.runs.lc.form.limits')} className="flex flex-col gap-3">
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
          <TextField
            label={t('st.runs.lc.form.minOrders')}
            help={t('st.runs.lc.form.minHelp')}
            inputMode="numeric"
            value={form.minOrders}
            error={shown.minOrders}
            onChange={(e) => set({ minOrders: e.currentTarget.value })}
          />
          <TextField
            label={t('st.runs.lc.form.capacity')}
            help={t('st.runs.lc.form.capacityHelp', { n: location?.defaultCapacityOrders ?? 0 })}
            inputMode="numeric"
            value={form.capacityOrders}
            error={shown.capacityOrders}
            onChange={(e) => set({ capacityOrders: e.currentTarget.value })}
          />
        </div>
        <RunBreakEven plan={plan} minOrders={n.minOrders ?? location?.defaultMinOrders ?? null} place={location?.name ?? ''} />
      </section>

      <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" disabled={lockedOpen}>
        <legend className="mb-1 p-0 text-sm font-semibold">{t('st.runs.lc.form.fee')}</legend>
        <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
          <input type="radio" name="fee-mode" className="size-5 shrink-0 cursor-pointer" checked={form.feeMode === 'RULE'} onChange={() => set({ feeMode: 'RULE' })} />
          {ruleFee ? t('st.runs.lc.form.fee.rule', { fee: ruleFee }) : t('st.runs.lc.form.fee.ruleUnknown')}
        </label>
        <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
          <input type="radio" name="fee-mode" className="size-5 shrink-0 cursor-pointer" checked={form.feeMode === 'CUSTOM'} onChange={() => set({ feeMode: 'CUSTOM' })} />
          {t('st.runs.lc.form.fee.custom')}
        </label>
        {form.feeMode === 'CUSTOM' ? (
          <TextField
            label={t('st.runs.lc.form.fee.amount')}
            inputMode="decimal"
            value={form.fee}
            error={shown.fee}
            className="max-w-xs"
            onChange={(e) => set({ fee: e.currentTarget.value })}
          />
        ) : null}
        <p className="m-0 text-xs text-muted">{lockedOpen ? t('st.runs.lc.form.fixedOnceOpen') : t('st.runs.lc.form.fee.help')}</p>
      </fieldset>

      <section aria-label={t('st.runs.lc.form.rules')} className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
        <Select
          label={t('st.runs.lc.form.dropMode')}
          value={form.dropMode}
          disabled={lockedOpen}
          options={[
            { value: 'DOORSTEP', label: t('st.runs.drop.DOORSTEP') },
            { value: 'PICKUP_POINT', label: t('st.runs.drop.PICKUP_POINT') }
          ]}
          error={shown.dropMode}
          onChange={(e) => set({ dropMode: e.currentTarget.value as RunForm['dropMode'] })}
        />
        <Select
          label={t('st.runs.lc.form.policy')}
          value={form.cutoffPolicy}
          options={[
            { value: 'EXTEND', label: t('st.runs.lc.form.policy.EXTEND') },
            { value: 'CANCEL', label: t('st.runs.lc.form.policy.CANCEL') }
          ]}
          error={shown.cutoffPolicy}
          onChange={(e) => set({ cutoffPolicy: e.currentTarget.value as RunForm['cutoffPolicy'] })}
        />
        {form.cutoffPolicy === 'EXTEND' ? (
          <TextField label={t('st.runs.lc.form.extendDays')} inputMode="numeric" value={form.extendDays} error={shown.extendDays} onChange={(e) => set({ extendDays: e.currentTarget.value })} />
        ) : null}
      </section>

      <div className="flex flex-col gap-1">
        <label htmlFor={`${formId}-notes`} className="text-sm font-semibold">
          {t('st.runs.lc.form.notes')}
        </label>
        <textarea
          id={`${formId}-notes`}
          value={form.notes}
          rows={3}
          maxLength={600}
          aria-invalid={shown.notes ? true : undefined}
          aria-describedby={`${formId}-notes-help`}
          onChange={(e) => set({ notes: e.currentTarget.value })}
          className={`${controlClass} h-auto py-2`}
        />
        <span id={`${formId}-notes-help`} className={`text-xs font-normal ${shown.notes ? 'text-danger' : 'text-muted'}`}>
          {shown.notes ?? t('st.runs.lc.form.notesHelp')}
        </span>
      </div>

      {failure ? (
        <p role="alert" className="m-0 text-sm text-danger" data-testid="run-form-failure">
          {failure}
        </p>
      ) : null}
      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <Button variant="neutral" onClick={onClose}>
          {t('st.runs.lc.form.discard')}
        </Button>
        <Button type="submit" loading={save.isPending} disabled={!changed}>
          {t(creating ? 'st.runs.lc.form.save.create' : 'st.runs.lc.form.save.edit')}
        </Button>
      </div>
    </form>
  );
}

function RunFormBody({ runId, defaultLocationId, onClose, onSaved }: { runId: string | null; defaultLocationId: string | null; onClose: () => void; onSaved: (run: StaffRunDetail) => void }) {
  const api = useRunsApi();
  const locations = useQuery(staffRunQueries.locations(api));
  // The run is read afresh when the form opens (a run opened since the sheet loaded must show its locked fields), and once:
  // what the form holds is never replaced under the person typing.
  const run = useQuery({
    queryKey: [...staffRunKeys.run(runId ?? ''), 'to-edit'],
    queryFn: ({ signal }) => api.run(runId ?? '', signal),
    enabled: runId !== null,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 0,
    refetchOnWindowFocus: false
  });
  if (locations.isPending || (runId !== null && run.isPending)) return <Spinner />;
  if (locations.isError) return <ErrorState error={locations.error} onRetry={() => void locations.refetch()} />;
  if (runId !== null && run.isError) return <ErrorState error={run.error} onRetry={() => void run.refetch()} />;
  const items = locations.data.items;
  const detail = runId !== null ? (run.data ?? null) : null;
  const initial = detail ? runFormOf(detail, items.find((l) => l.id === detail.locationId)) : newRunForm(items.find((l) => l.id === defaultLocationId), Date.now());
  return <RunFormFields initial={initial} run={detail} locations={items} onClose={onClose} onSaved={onSaved} />;
}

/**
 * The right sheet that schedules a run (New run) or changes one before it opens (Edit): the location and its area from
 * geo, the delivery window, when orders close, the minimum with its break-even beside it, capacity, the delivery fee rule
 * or a custom fee, and a note. Saving stays off until something changed.
 */
export function RunFormSheet({
  open,
  runId,
  defaultLocationId,
  onClose,
  onSaved
}: {
  open: boolean;
  /** The run being edited; null schedules a new one. */
  runId: string | null;
  defaultLocationId: string | null;
  onClose: () => void;
  onSaved: (run: StaffRunDetail) => void;
}) {
  const t = useT();
  return (
    <Sheet open={open} onOpenChange={(o) => (o ? undefined : onClose())} title={t(runId ? 'st.runs.lc.form.editTitle' : 'st.runs.lc.form.createTitle')} closeLabel={t('st.runs.reason.close')}>
      <RunFormBody key={runId ?? 'new'} runId={runId} defaultLocationId={defaultLocationId} onClose={onClose} onSaved={onSaved} />
    </Sheet>
  );
}
