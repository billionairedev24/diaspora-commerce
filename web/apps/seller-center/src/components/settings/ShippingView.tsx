'use client';

import { isApiError, sellerKeys, sellerQueries, type ShipFromAddress } from '@afrotransact/api-client';
import { Button, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { FulfilmentTip } from '@/components/ui/FulfilmentTip';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { checkWeek, type DayRow, sameWeek, weekOf, windowsOf } from '@/lib/weekly-hours';

import { SelfShippingSection } from './SelfShippingSection';
import { SettingsPage, useSectionAccess } from './SettingsShell';
import { WeeklyHours } from './WeeklyHours';

/** The pickup refusals of seller-service, in the seller's words (S255). */
function pickupProblem(t: Translator<MessageKey>, e: unknown): string {
  if (!isApiError(e)) return problemMessage(e);
  switch (e.code) {
    case 'PICKUP_ORG_NOT_APPROVED':
    case 'NOT_APPROVED':
      return t('sc.pickup.err.notApproved');
    case 'PICKUP_OUTSIDE_DELIVERY_AREA':
      return `${t('sc.pickup.err.outside')} ${e.message}`;
    case 'PICKUP_NOT_GEOCODED':
      return `${t('sc.pickup.err.geocoding')} ${e.message}`;
    case 'PICKUP_INVALID_HOURS':
      return t('sc.pickup.err.hours');
    default:
      return problemMessage(e);
  }
}

/** "AfroTransact picks up here": one switch and the week's hours (same list as Pickup), one Save. */
export function DriverPickupForm({ a, editable, sellerPickupOn = false }: { a: ShipFromAddress; editable: boolean; sellerPickupOn?: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const saved = a.afrotransactPickup;
  const [enabled, setEnabled] = useState(saved.enabled);
  const [week, setWeek] = useState<DayRow[]>(() => weekOf(saved.hours));
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    // Off sends the saved hours back untouched; on sends one window per open day.
    mutationFn: () => sellerApi().setAfrotransactPickup(a.id, { enabled, hours: enabled ? windowsOf(week) : saved.hours, version: a.version }),
    onSuccess: (next) => {
      setProblem(null);
      setEnabled(next.afrotransactPickup.enabled);
      setWeek(weekOf(next.afrotransactPickup.hours));
      toast.show(t('sc.pickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
    },
    onError: (e) => setProblem(pickupProblem(t, e))
  });
  const check = checkWeek(week, { none: t('sc.weekly.errNone'), missing: t('sc.weekly.errMissing'), order: t('sc.weekly.errOrder') });
  const dirty = enabled !== saved.enabled || (enabled && !sameWeek(week, weekOf(saved.hours)));
  // Sellers do not ship themselves: turning this off needs no confirmation, it only means customers can't buy from here unless
  // Seller pickup is on (the notice says so).
  const canSave = dirty && (!enabled || check.ok);
  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (canSave) save.mutate();
      }}
      className="flex flex-col gap-3"
      aria-label={t('sc.pickup.title')}
    >
      <div className="flex flex-col">
        <div className="flex items-center">
          <Switch id={`${a.id}-pickup-enabled`} checked={enabled} disabled={!editable || save.isPending} onChange={setEnabled} label={t('sc.pickup.toggle')} />
          <FulfilmentTip mode="fba" />
        </div>
        <p className="m-0 text-sm text-muted">
          {saved.zoneName ? t('sc.pickup.zone', { zone: saved.zoneName }) : t('sc.pickup.toggleHelp')}
          {saved.enabledAt ? ` · ${t('sc.pickup.since', { date: shortDate(saved.enabledAt) })}` : ''}
        </p>
        <p className="m-0 mt-1 text-sm" data-testid="fulfilment-mode">
          {enabled ? t('sc.pickup.modeOn') : t('sc.pickup.modeOff')}
        </p>
        {!enabled && !sellerPickupOn ? (
          <Notice tone="warning" title={t('sc.settings.shipping.neither')} role="status" />
        ) : null}
      </div>
      {enabled ? <WeeklyHours week={week} onChange={setWeek} disabled={!editable || save.isPending} invalidDays={check.days} legend={t('sc.pickup.hours')} /> : null}
      {problem ? (
        <Notice tone="danger" title={t('sc.pickup.err.title')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {editable ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" size="sm" loading={save.isPending} disabled={!canSave}>
            {t('sc.pickup.save')}
          </Button>
          {enabled && !check.ok ? (
            <p className="m-0 text-sm text-muted" data-testid="driver-validation">
              {check.message}
            </p>
          ) : null}
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
      <ul className="m-0 flex max-w-[40rem] list-none flex-col p-0">
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
            <DriverPickupForm key={`${a.id}-${a.version}`} a={a} editable={canEdit} sellerPickupOn={a.customerPickup?.ready === true} />
          </li>
        ))}
      </ul>
      <SelfShippingSection editable={canEdit} />
    </SettingsPage>
  );
}
