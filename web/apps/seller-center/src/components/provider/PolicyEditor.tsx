'use client';

import { type CancellationPolicy, type CancellationPolicyInput, type PolicyKind, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, Checkbox, cx, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

type Custom = Required<Omit<CancellationPolicyInput, 'kind'>>;

const PRESETS: { kind: Exclude<PolicyKind, 'CUSTOM'>; label: string; detail: string }[] = [
  { kind: 'FLEXIBLE', label: 'Flexible', detail: 'Free cancellation up to 24 hours before.' },
  { kind: 'MODERATE', label: 'Moderate', detail: 'Free up to 48 hours; 50% of the deposit back from 24 hours.' },
  { kind: 'STRICT', label: 'Strict', detail: 'Free up to 7 days before; the deposit is kept after that.' }
];

const customOf = (p: CancellationPolicy): Custom => ({
  freeHours: p.freeHours, partialRefund: p.partialRefund, partialFromHours: p.partialFromHours, partialPercent: p.partialPercent, noShow: p.noShow,
  rescheduleHours: p.rescheduleHours, maxReschedules: p.maxReschedules
});

/** Client-side checks matching the BFF's PolicyInput.Validate; returns field → message. */
export function validateCustom(c: Custom): Record<string, string> {
  const e: Record<string, string> = {};
  const hours = (k: 'freeHours' | 'partialFromHours' | 'rescheduleHours') => {
    if (!Number.isInteger(c[k]) || c[k] < 0 || c[k] > 720) e[k] = '0–720 hours';
  };
  hours('freeHours');
  hours('rescheduleHours');
  if (c.partialRefund) {
    hours('partialFromHours');
    if (!e.partialFromHours && c.partialFromHours >= c.freeHours) e.partialFromHours = 'Must be less than the free-cancellation window';
    if (!Number.isInteger(c.partialPercent) || c.partialPercent < 1 || c.partialPercent > 99) e.partialPercent = '1–99%';
  }
  if (!Number.isInteger(c.maxReschedules) || c.maxReschedules < 0 || c.maxReschedules > 10) e.maxReschedules = '0–10';
  return e;
}

const num = (v: string) => Number(v.replace(/\D/g, '') || '0');

/** Cancellation policy: three templates or a custom policy, with the customer-facing refund timeline. */
export function PolicyEditor() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('policy');
  const q = useQuery(sellerQueries.policy(api, orgId));
  const [kind, setKind] = useState<PolicyKind | null>(null);
  const [custom, setCustom] = useState<Custom | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => {
    if (q.data && !kind) {
      setKind(q.data.kind);
      setCustom(customOf(q.data));
    }
  }, [q.data, kind]);
  const save = useMutation({
    mutationFn: (input: CancellationPolicyInput) => api.saveCancellationPolicy(input),
    onSuccess: (p) => {
      qc.setQueryData(sellerKeys.policy(orgId), p);
      setCustom(customOf(p));
      toast.show(`${p.label} policy saved. It applies to new bookings.`);
    },
    onError: (e) => setErrors({ form: problemMessage(e) })
  });
  const policy = q.data;
  if (!policy || !kind || !custom) return <ModulePage module="policy">{q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <Spinner />}</ModulePage>;
  const set = (p: Partial<Custom>) => setCustom({ ...custom, ...p });
  const submit = () => {
    if (kind !== 'CUSTOM') return save.mutate({ kind });
    const e = validateCustom(custom);
    setErrors(e);
    if (Object.keys(e).length === 0) save.mutate({ kind, ...custom });
  };
  return (
    <ModulePage module="policy">
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <fieldset disabled={!editable} className="m-0 flex min-w-0 flex-col gap-3 border-0 p-0">
          <legend className="at-visually-hidden">Policy template</legend>
          <div role="radiogroup" aria-label="Policy" className="grid gap-3 sm:grid-cols-2">
            {[...PRESETS, { kind: 'CUSTOM' as const, label: 'Custom', detail: 'Set your own windows, refunds and no-show charge.' }].map((p) => (
              <label key={p.kind} className={cx('flex cursor-pointer flex-col gap-1 rounded-md border p-4', kind === p.kind ? 'border-primary bg-surface-muted' : 'border-border')}>
                <span className="flex items-center gap-2">
                  <input type="radio" name="policy" value={p.kind} checked={kind === p.kind} onChange={() => { setKind(p.kind); setErrors({}); }} />
                  <strong>{p.label}</strong>
                  {policy.kind === p.kind ? <span className="text-xs text-muted">· current</span> : null}
                </span>
                <span className="text-sm text-muted">{p.detail}</span>
              </label>
            ))}
          </div>
          {kind === 'CUSTOM' ? (
            <Card title="Custom policy">
              <div className="grid gap-x-3 sm:grid-cols-2">
                <TextField label="Free cancellation up to (hours before)" inputMode="numeric" value={String(custom.freeHours)} error={errors.freeHours} onChange={(e) => set({ freeHours: num(e.target.value) })} />
                <TextField label="Free reschedule up to (hours before)" inputMode="numeric" value={String(custom.rescheduleHours)} error={errors.rescheduleHours} onChange={(e) => set({ rescheduleHours: num(e.target.value) })} />
              </div>
              <Checkbox label="Give a partial refund after the free window" checked={custom.partialRefund} onChange={(e) => set({ partialRefund: e.target.checked })} />
              {custom.partialRefund ? (
                <div className="grid gap-x-3 sm:grid-cols-2">
                  <TextField label="Partial refund until (hours before)" inputMode="numeric" value={String(custom.partialFromHours)} error={errors.partialFromHours} onChange={(e) => set({ partialFromHours: num(e.target.value) })} />
                  <TextField label="Deposit refunded (%)" inputMode="numeric" value={String(custom.partialPercent)} error={errors.partialPercent} onChange={(e) => set({ partialPercent: num(e.target.value) })} />
                </div>
              ) : null}
              <div className="grid gap-x-3 sm:grid-cols-2">
                <Select label="No-shows are charged" value={custom.noShow} onChange={(e) => set({ noShow: e.target.value === 'FULL' ? 'FULL' : 'DEPOSIT' })}
                  options={[{ value: 'DEPOSIT', label: 'The deposit' }, { value: 'FULL', label: 'The full price' }]} />
                <TextField label="Most reschedules per booking" inputMode="numeric" value={String(custom.maxReschedules)} error={errors.maxReschedules} onChange={(e) => set({ maxReschedules: num(e.target.value) })} />
              </div>
            </Card>
          ) : null}
          {errors.form ? <span role="alert" className="text-sm text-danger">{errors.form}</span> : null}
          {editable ? <Button className="self-start" onClick={submit} loading={save.isPending}>Save policy</Button> : null}
        </fieldset>
        <Card title={`What customers see · ${policy.label}`}>
          <p className="m-0 text-sm">{policy.summary}</p>
          <ol aria-label="Refund timeline" className="m-0 flex list-none flex-col gap-2 p-0">
            {policy.timeline.map((t) => (
              <li key={t.label} className="flex items-center gap-3 text-sm">
                <span className={cx('w-14 flex-none rounded-xs px-2 py-1 text-center text-xs font-bold', t.refundPercent === 100 ? 'bg-success-bg text-success' : t.refundPercent > 0 ? 'bg-warning-bg text-warning' : 'bg-danger-bg text-danger')}>
                  {t.refundPercent}%
                </span>
                <span>{t.label}</span>
              </li>
            ))}
          </ol>
          <p className="m-0 text-xs text-muted">Refund percentages apply to the deposit. If you cancel within 24 hours you pay a $15 late-cancellation fee and the customer gets everything back.</p>
        </Card>
      </div>
    </ModulePage>
  );
}
