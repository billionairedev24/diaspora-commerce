'use client';

import { type NegotiatedPlan, staffPlanKeys, staffPlanQueries } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { type MessageKey, type Translator } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { errorsOf, feeRatesApi } from './NegotiatedRatesPanel';

const cents = (text: string): number | null => {
  const t = text.trim();
  if (!t) return null;
  const m = /^(\d{1,6})(?:\.(\d{1,2}))?$/.exec(t);
  return m ? Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0')) : Number.NaN;
};

/**
 * Negotiate a plan price: a private (invite-only) copy of a catalogue plan at the negotiated price, offered to this
 * organisation only. The seller then chooses it in Plan & billing.
 */
export function NegotiatePlanDialog({ orgId, orgName, kind, open, onClose, t }: {
  orgId: string;
  orgName: string;
  kind: 'SELLER' | 'PROVIDER';
  open: boolean;
  onClose: () => void;
  t: Translator<MessageKey>;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const plans = useQuery({ ...staffPlanQueries.plans(staffApi().plans), enabled: open });
  const offered = (plans.data ?? []).filter((p) => p.kind === kind && p.visibility === 'PUBLIC' && p.status === 'ACTIVE');
  const [base, setBase] = useState('');
  const [monthly, setMonthly] = useState('');
  const [quarterly, setQuarterly] = useState('');
  const [annual, setAnnual] = useState('');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [assign, setAssign] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState<NegotiatedPlan | null>(null);
  const save = useMutation({
    mutationFn: () => {
      const m = cents(monthly);
      const local: Record<string, string> = {};
      if (!base) local.basePlanCode = t('st.rates.pickPlan');
      if (m === null || Number.isNaN(m)) local.monthlyCents = t('st.rates.badPrice');
      if (Number.isNaN(cents(quarterly))) local.quarterlyCents = t('st.rates.badPrice');
      if (Number.isNaN(cents(annual))) local.annualCents = t('st.rates.badPrice');
      if (reason.trim().length < 3) local.reason = t('st.rates.reasonRequired');
      if (Object.keys(local).length) {
        setErrors(local);
        throw new Error('invalid');
      }
      return feeRatesApi().negotiatePlan(orgId, { basePlanCode: base, monthlyCents: m ?? 0, quarterlyCents: cents(quarterly), annualCents: cents(annual),
        reason: reason.trim(), reference: reference.trim() || null, assign });
    },
    onSuccess: (p) => {
      setDone(p);
      toast.show(t('st.rates.planCreated', { plan: p.planName }));
      void qc.invalidateQueries({ queryKey: staffPlanKeys.all });
    },
    onError: (e) => {
      if (e instanceof Error && e.message === 'invalid') return;
      setErrors(errorsOf(e));
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    save.mutate();
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={t('st.rates.planTitle', { org: orgName })} description={t('st.rates.planHelp')} width={520} closeLabel={t('st.rates.close')}>
      {done ? (
        <div className="flex flex-col gap-3" data-testid="negotiated-plan">
          <p className="m-0 text-sm"><strong>{done.planName}</strong> ({done.planCode})</p>
          <p className="m-0 text-sm">{done.note}</p>
          <div className="flex justify-end"><Button onClick={onClose}>{t('st.rates.close')}</Button></div>
        </div>
      ) : (
        <form noValidate onSubmit={submit} className="flex flex-col gap-2">
          <Select id="np-base" label={t('st.rates.basePlan')} value={base} error={errors.basePlanCode} onChange={(e) => setBase(e.currentTarget.value)}
            options={[{ value: '', label: t('st.rates.choosePlan') }, ...offered.map((p) => ({ value: p.code, label: p.name }))]} />
          <TextField id="np-monthly" label={t('st.rates.monthly')} inputMode="decimal" value={monthly} error={errors.monthlyCents} onChange={(e) => setMonthly(e.currentTarget.value)} />
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-2">
            <TextField id="np-quarterly" label={t('st.rates.quarterly')} inputMode="decimal" value={quarterly} error={errors.quarterlyCents} onChange={(e) => setQuarterly(e.currentTarget.value)} />
            <TextField id="np-annual" label={t('st.rates.annual')} inputMode="decimal" value={annual} error={errors.annualCents} onChange={(e) => setAnnual(e.currentTarget.value)} />
          </div>
          <TextField id="np-reason" label={t('st.rates.reason')} value={reason} error={errors.reason} onChange={(e) => setReason(e.currentTarget.value)} />
          <TextField id="np-ref" label={t('st.rates.reference')} value={reference} error={errors.reference} onChange={(e) => setReference(e.currentTarget.value)} />
          <Checkbox id="np-assign" label={t('st.rates.assignNow')} checked={assign} onChange={(e) => setAssign(e.currentTarget.checked)} />
          {errors._ || errors.orgId ? <p role="alert" className="m-0 text-sm text-danger">{errors._ ?? errors.orgId}</p> : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={onClose}>{t('st.rates.cancel')}</Button>
            <Button type="submit" loading={save.isPending}>{t('st.rates.createPlan')}</Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
