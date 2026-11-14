'use client';

import {
  isApiError,
  PICKUP_DAYS,
  type PickupDay,
  type PickupWindow,
  sellerKeys,
  sellerQueries,
  type ShipFromAddress,
  validatePickupHours
} from '@afrotransact/api-client';
import { Button, Checkbox, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { SettingsPage, useSectionAccess } from './SettingsShell';

/** The pickup refusals of seller-service, in the seller's words (S255). */
function pickupProblem(t: Translator<MessageKey>, e: unknown): { message: string; fields: Record<string, string> } {
  if (!isApiError(e)) return { message: problemMessage(e), fields: {} };
  switch (e.code) {
    case 'PICKUP_ORG_NOT_APPROVED':
    case 'NOT_APPROVED':
      return { message: t('sc.pickup.err.notApproved'), fields: {} };
    case 'PICKUP_OUTSIDE_DELIVERY_AREA':
      return { message: `${t('sc.pickup.err.outside')} ${e.message}`, fields: {} };
    case 'PICKUP_NOT_GEOCODED':
      return { message: `${t('sc.pickup.err.geocoding')} ${e.message}`, fields: {} };
    case 'PICKUP_INVALID_HOURS':
      return { message: t('sc.pickup.err.hours'), fields: e.fieldErrors };
    default:
      return { message: problemMessage(e), fields: e.fieldErrors };
  }
}

/** "AfroTransact picks up here" with the pickup hours (1–14 windows, 24-hour local times). */
export function DriverPickupForm({ a, editable }: { a: ShipFromAddress; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [enabled, setEnabled] = useState(a.afrotransactPickup.enabled);
  const [hours, setHours] = useState<PickupWindow[]>(a.afrotransactPickup.hours);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => sellerApi().setAfrotransactPickup(a.id, { enabled, hours, version: a.version }),
    onSuccess: (saved) => {
      setProblem(null);
      setErrors({});
      setEnabled(saved.afrotransactPickup.enabled);
      setHours(saved.afrotransactPickup.hours);
      toast.show(t('sc.pickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
    },
    onError: (e) => {
      const p = pickupProblem(t, e);
      setProblem(p.message);
      setErrors(p.fields);
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs = validatePickupHours(enabled, hours);
    setErrors(errs);
    setProblem(Object.keys(errs).length ? t('sc.pickup.err.hours') : null);
    if (!Object.keys(errs).length) save.mutate();
  };
  const dayOptions = PICKUP_DAYS.map((d) => ({ value: d, label: t(`sc.day.${d}`) }));
  const setWindow = (i: number, w: Partial<PickupWindow>) => setHours((hs) => hs.map((h, j) => (j === i ? { ...h, ...w } : h)));
  const id = (s: string) => `${a.id}-${s}`;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border p-3" aria-label={t('sc.pickup.title')}>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="m-0 flex-1 text-md font-semibold">{t('sc.pickup.title')}</h3>
        {a.afrotransactPickup.enabled ? <StatusPill tone="good">{t('sc.ff.AFROTRANSACT_DELIVERY')}</StatusPill> : null}
      </div>
      <Checkbox
        id={id('pickup-enabled')}
        checked={enabled}
        disabled={!editable}
        onChange={(e) => {
          const on = e.currentTarget.checked;
          setEnabled(on);
          if (on && hours.length === 0) setHours([{ day: 'MONDAY', opens: '09:00', closes: '17:00' }]);
        }}
        label={t('sc.pickup.toggle')}
        description={t('sc.pickup.toggleHelp')}
      />
      {a.afrotransactPickup.zoneName ? (
        <p className="m-0 text-xs text-muted">
          {t('sc.pickup.zone', { zone: a.afrotransactPickup.zoneName })}
          {a.afrotransactPickup.enabledAt ? ` · ${t('sc.pickup.since', { date: shortDate(a.afrotransactPickup.enabledAt) })}` : ''}
        </p>
      ) : null}
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={!editable}>
        <legend className="mb-1 text-sm font-semibold">{t('sc.pickup.hours')}</legend>
        {errors.hours ? <p className="m-0 text-sm text-danger">{errors.hours}</p> : null}
        {hours.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.pickup.noWindows')}</p> : null}
        {hours.map((h, i) => (
          <div key={`${a.id}-${i}`} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2" data-testid="pickup-window">
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
            <Button size="sm" variant="neutral" onClick={() => setHours((hs) => [...hs, { day: 'SATURDAY', opens: '10:00', closes: '14:00' }])}>
              <Plus size={16} aria-hidden /> {t('sc.pickup.addWindow')}
            </Button>
          </div>
        ) : null}
      </fieldset>
      {problem ? (
        <Notice tone="danger" title={t('sc.pickup.err.title')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={save.isPending}>
            {t('sc.pickup.save')}
          </Button>
        </div>
      ) : null}
    </form>
  );
}


/** Settings > Shipping & delivery: who collects packed orders from each location (AfroTransact drivers, with their hours). */
export function ShippingView() {
  const t = useT();
  const { orgId } = useSeller();
  const { canEdit } = useSectionAccess('shipping');
  const q = useQuery(sellerQueries.shipFrom(sellerApi(), orgId));
  return (
    <SettingsPage section="shipping">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('sc.settings.shipping.intro')}</p>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.length === 0 ? (
        <p className="m-0 text-sm text-muted">
          {t('sc.settings.noLocations')}{' '}
          <Link href="/settings/locations" className="text-link underline">
            {t('sc.settings.goLocations')}
          </Link>
        </p>
      ) : null}
      <ul className="m-0 flex list-none flex-col p-0">
        {q.data?.map((a) => (
          <li key={a.id} className="flex flex-col gap-3 border-b border-border py-4 first:border-t" data-testid="shipping-location" data-tour="settings-shipping-location">
            <div className="flex flex-col gap-1">
              <h2 className="m-0 text-md font-semibold">{a.label || a.city}</h2>
              <p className="m-0 text-sm text-muted">
                {a.line1}, {a.city}, {a.state} {a.postalCode} ·{' '}
                <Link href="/settings/locations" className="text-link underline">
                  {t('sc.settings.editAddress')}
                </Link>
              </p>
            </div>
            <DriverPickupForm key={`${a.id}-${a.version}`} a={a} editable={canEdit} />
          </li>
        ))}
      </ul>
    </SettingsPage>
  );
}
