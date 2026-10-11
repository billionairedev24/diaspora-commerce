'use client';

import { isApiError, PICKUP_DAYS, type PickupDay, type PickupWindow, sellerKeys, type ShipFromAddress, validatePickupHours } from '@afrotransact/api-client';
import { Button, Checkbox, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Storefront, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

/**
 * "Customers can pick up their orders here" for one store or pickup address: checkout then offers this address as a
 * free pickup point for the seller's items, with these hours and the ready time.
 */
export function CustomerPickupSettings({ a, editable }: { a: ShipFromAddress; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const current = a.customerPickup ?? { enabled: false, hours: [], readyHours: 0 };
  const [enabled, setEnabled] = useState(current.enabled);
  const [hours, setHours] = useState<PickupWindow[]>(current.hours);
  const [ready, setReady] = useState(String(current.readyHours));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => sellerApi().setCustomerPickup(a.id, { enabled, hours, readyHours: Number(ready) || 0 }),
    onSuccess: (saved) => {
      setProblem(null);
      setErrors({});
      if (saved.customerPickup) {
        setEnabled(saved.customerPickup.enabled);
        setHours(saved.customerPickup.hours);
      }
      toast.show(t('sc.cpickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
    },
    onError: (e) => {
      setProblem(isApiError(e) && e.code === 'PICKUP_INVALID_HOURS' ? t('sc.pickup.err.hours') : problemMessage(e));
      setErrors(isApiError(e) ? e.fieldErrors : {});
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs = validatePickupHours(enabled, hours);
    const n = Number(ready);
    if (!Number.isInteger(n) || n < 0 || n > 168) errs.readyHours = t('sc.cpickup.readyInvalid');
    setErrors(errs);
    setProblem(Object.keys(errs).length ? t('sc.pickup.err.hours') : null);
    if (!Object.keys(errs).length) save.mutate();
  };
  const dayOptions = PICKUP_DAYS.map((d) => ({ value: d, label: t(`sc.day.${d}`) }));
  const setWindow = (i: number, w: Partial<PickupWindow>) => setHours((hs) => hs.map((h, j) => (j === i ? { ...h, ...w } : h)));
  const id = (s: string) => `${a.id}-cp-${s}`;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border p-3" aria-label={t('sc.cpickup.title')} data-testid="customer-pickup">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="m-0 flex flex-1 items-center gap-2 text-md font-semibold">
          <Storefront size={18} aria-hidden /> {t('sc.cpickup.title')}
        </h3>
        {current.enabled ? <StatusPill tone="good">{t('sc.cpickup.on')}</StatusPill> : null}
      </div>
      <Checkbox
        id={id('enabled')}
        checked={enabled}
        disabled={!editable}
        onChange={(e) => {
          const on = e.currentTarget.checked;
          setEnabled(on);
          if (on && hours.length === 0) setHours([{ day: 'MONDAY', opens: '10:00', closes: '18:00' }]);
        }}
        label={t('sc.cpickup.toggle')}
        description={t('sc.cpickup.toggleHelp')}
      />
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={!editable}>
        <legend className="mb-1 text-sm font-semibold">{t('sc.cpickup.hours')}</legend>
        {errors.hours ? <p className="m-0 text-sm text-danger">{errors.hours}</p> : null}
        {hours.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.pickup.noWindows')}</p> : null}
        {hours.map((h, i) => (
          <div key={`${a.id}-cp-${i}`} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2" data-testid="customer-pickup-window">
            <Select id={id(`day-${i}`)} label={<span className="at-visually-hidden">{t('sc.pickup.dayN', { n: i + 1 })}</span>} value={h.day} options={dayOptions} error={errors[`hours.${i}.day`]} onChange={(e) => setWindow(i, { day: e.currentTarget.value as PickupDay })} />
            <TextField id={id(`opens-${i}`)} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.opensN', { n: i + 1 })}</span>} value={h.opens} error={errors[`hours.${i}.opens`]} onChange={(e) => setWindow(i, { opens: e.currentTarget.value })} />
            <TextField id={id(`closes-${i}`)} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.closesN', { n: i + 1 })}</span>} value={h.closes} error={errors[`hours.${i}.closes`]} onChange={(e) => setWindow(i, { closes: e.currentTarget.value })} />
            <Button size="sm" variant="neutral" aria-label={t('sc.pickup.removeN', { n: i + 1 })} onClick={() => setHours((hs) => hs.filter((_, j) => j !== i))}>
              <Trash size={16} aria-hidden />
            </Button>
          </div>
        ))}
        {hours.length < 14 ? (
          <div>
            <Button size="sm" variant="neutral" onClick={() => setHours((hs) => [...hs, { day: 'SATURDAY', opens: '10:00', closes: '16:00' }])}>
              <Plus size={16} aria-hidden /> {t('sc.pickup.addWindow')}
            </Button>
          </div>
        ) : null}
        <TextField id={id('ready')} type="number" inputMode="numeric" min={0} max={168} label={t('sc.cpickup.ready')} help={t('sc.cpickup.readyHelp')} value={ready} error={errors.readyHours} onChange={(e) => setReady(e.currentTarget.value)} />
      </fieldset>
      {problem ? (
        <Notice tone="danger" title={t('sc.cpickup.errTitle')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={save.isPending}>
            {t('sc.cpickup.save')}
          </Button>
        </div>
      ) : null}
    </form>
  );
}
