'use client';

import { isApiError, PICKUP_DAYS, type PickupDay, type PickupWindow, sellerKeys, sellerQueries, type ShipFromAddress } from '@afrotransact/api-client';
import { Button, Checkbox, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { blankWindow, previewZip } from '@/lib/pickup-prompt';
import { draftOf, type PickupDraft, pickupHint, pickupSave } from '@/lib/pickup-settings';
import { useSeller } from '@/lib/session';

import { BusinessAddressStart } from './BusinessAddressStart';
import { SettingsPage, useSectionAccess } from './SettingsShell';

/**
 * One location's pickup: the address is shown (read-only, changed under Locations), the seller turns pickup on, types the
 * opening hours and the ready time, and ticks "someone will be there". Nothing is switched on or filled in for them.
 */
function PickupLocationForm({ a, editable }: { a: ShipFromAddress; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const saved = a.customerPickup;
  const [draft, setDraft] = useState<PickupDraft>(() => draftOf(saved));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const wasOn = saved?.enabled ?? false;
  const save = useMutation({
    mutationFn: (req: Parameters<ReturnType<typeof sellerApi>['setCustomerPickup']>[1]) => sellerApi().setCustomerPickup(a.id, req),
    onSuccess: (next) => {
      setProblem(null);
      setErrors({});
      setDraft(draftOf(next.customerPickup));
      toast.show(t('sc.cpickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.pickupPrompt(orgId) });
    },
    onError: (e) => {
      setProblem(isApiError(e) && (e.code === 'PICKUP_INVALID_HOURS' || e.code === 'PICKUP_HOURS_NOT_CONFIRMED') ? t('sc.settings.pickup.err.confirm') : problemMessage(e));
      setErrors(isApiError(e) ? e.fieldErrors : {});
    }
  });
  const msgs = { ready: t('sc.cpickup.readyInvalid'), confirm: t('sc.pickupCard.needConfirm') };
  const hint = pickupHint(saved, draft);
  const submit = () => {
    const r = pickupSave(saved, draft, msgs);
    if (!r.ok) {
      setErrors(r.errors);
      setProblem(t('sc.pickup.err.hours'));
      return;
    }
    setErrors({});
    setProblem(null);
    save.mutate(r.request);
  };
  const set = (patch: Partial<PickupDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const setWindow = (i: number, w: Partial<PickupWindow>) => setDraft((d) => ({ ...d, hours: d.hours.map((h, j) => (j === i ? { ...h, ...w } : h)) }));
  const dayOptions = PICKUP_DAYS.map((d) => ({ value: d, label: t(`sc.day.${d}`) }));
  const id = (s: string) => `${a.id}-pk-${s}`;
  const zip = previewZip(a);
  const busy = save.isPending;
  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      className="flex flex-col gap-3"
      aria-label={t('sc.settings.pickup.formLabel', { name: a.label || a.city })}
      data-testid="pickup-location"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="m-0 flex-1 text-md font-semibold">{a.label || a.city}</h2>
        {wasOn ? <StatusPill tone="good">{t('sc.settings.pickup.on')}</StatusPill> : <StatusPill tone="info">{t('sc.settings.pickup.off')}</StatusPill>}
      </div>
      <dl className="m-0 grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]" data-testid="pickup-address">
        <dt className="font-semibold">{t('sc.pickupCard.address')}</dt>
        <dd className="m-0">
          {a.name}, {[a.line1, a.line2].filter(Boolean).join(', ')}, {a.city}, {a.state} {a.postalCode}
          {a.phone ? ` · ${a.phone}` : ''}{' '}
          <Link href="/settings/locations" className="text-link underline">
            {t('sc.settings.editAddress')}
          </Link>
        </dd>
      </dl>
      {(saved?.issues ?? []).length > 0 && wasOn ? (
        <div className="flex flex-col gap-2" data-testid="customer-pickup-issues">
          {(saved?.issues ?? []).map((issue) => (
            <Notice key={issue.code} tone={issue.blocking ? 'warning' : 'info'} title={issue.message} />
          ))}
        </div>
      ) : null}
      <Checkbox
        id={id('enabled')}
        checked={draft.enabled}
        disabled={!editable || busy}
        onChange={(e) => {
          const enabled = e.currentTarget.checked;
          set({ enabled });
        }}
        label={t('sc.settings.pickup.toggle')}
        description={t('sc.settings.pickup.toggleHelp')}
      />
      {draft.enabled ? (
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={!editable || busy}>
          <legend className="mb-1 text-sm font-semibold">{t('sc.cpickup.hours')}</legend>
          <p className="m-0 text-sm text-muted">{draft.hours.length === 0 ? t('sc.pickupCard.hoursNone') : t('sc.pickupCard.hoursHelp')}</p>
          {errors.hours ? <p className="m-0 text-sm text-danger">{errors.hours}</p> : null}
          {draft.hours.map((h, i) => (
            <div key={`${a.id}-pk-${i}`} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_auto]" data-testid="customer-pickup-window">
              <Select
                className="col-span-3 sm:col-span-1"
                id={id(`day-${i}`)}
                label={<span className="at-visually-hidden">{t('sc.pickup.dayN', { n: i + 1 })}</span>}
                value={h.day}
                options={dayOptions}
                error={errors[`hours.${i}.day`]}
                onChange={(e) => {
                  const day = e.currentTarget.value as PickupDay;
                  setWindow(i, { day });
                }}
              />
              <TextField
                id={id(`opens-${i}`)}
                type="time"
                label={<span className="at-visually-hidden">{t('sc.pickup.opensN', { n: i + 1 })}</span>}
                value={h.opens}
                error={errors[`hours.${i}.opens`]}
                onChange={(e) => {
                  const opens = e.currentTarget.value;
                  setWindow(i, { opens });
                }}
              />
              <TextField
                id={id(`closes-${i}`)}
                type="time"
                label={<span className="at-visually-hidden">{t('sc.pickup.closesN', { n: i + 1 })}</span>}
                value={h.closes}
                error={errors[`hours.${i}.closes`]}
                onChange={(e) => {
                  const closes = e.currentTarget.value;
                  setWindow(i, { closes });
                }}
              />
              <Button size="sm" variant="neutral" aria-label={t('sc.pickup.removeN', { n: i + 1 })} onClick={() => setDraft((d) => ({ ...d, hours: d.hours.filter((_, j) => j !== i) }))}>
                <Trash size={16} aria-hidden />
              </Button>
            </div>
          ))}
          {draft.hours.length < 14 ? (
            <div>
              <Button size="sm" variant="neutral" onClick={() => setDraft((d) => ({ ...d, hours: [...d.hours, blankWindow()] }))}>
                <Plus size={16} aria-hidden /> {t('sc.pickup.addWindow')}
              </Button>
            </div>
          ) : null}
          <TextField
            id={id('ready')}
            type="number"
            inputMode="numeric"
            min={0}
            max={168}
            label={t('sc.cpickup.ready')}
            help={t('sc.cpickup.readyHelp')}
            value={draft.ready}
            error={errors.readyHours}
            onChange={(e) => {
              const ready = e.currentTarget.value;
              set({ ready });
            }}
          />
          {!wasOn ? (
            <Checkbox
              id={id('confirm')}
              checked={draft.confirmed}
              disabled={!editable || busy}
              onChange={(e) => {
                const confirmed = e.currentTarget.checked;
                set({ confirmed });
              }}
              label={t('sc.pickupCard.confirm')}
            />
          ) : null}
          {errors.confirmed ? <p className="m-0 text-sm text-danger">{errors.confirmed}</p> : null}
          {zip ? <p className="m-0 text-sm" data-testid="pickup-preview">{t('sc.pickupCard.preview', { zip })}</p> : null}
        </fieldset>
      ) : (
        <p className="m-0 text-sm text-muted">{t('sc.pickupCard.offNote')}</p>
      )}
      {wasOn && saved?.enabledAt ? <p className="m-0 text-xs text-muted">{t('sc.settings.pickup.since', { date: shortDate(saved.enabledAt) })}</p> : null}
      {problem ? (
        <Notice tone="danger" title={t('sc.cpickup.errTitle')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {hint && editable ? <p className="m-0 text-sm text-muted">{hint === 'hours' ? t('sc.pickupCard.needHours') : hint === 'ready' ? t('sc.cpickup.readyInvalid') : t('sc.pickupCard.needConfirm')}</p> : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={busy} disabled={(!wasOn && !draft.enabled) || (!wasOn && hint !== null)}>
            {!wasOn && draft.enabled ? t('sc.pickupCard.turnOn') : t('sc.cpickup.save')}
          </Button>
        </div>
      ) : null}
    </form>
  );
}

/** Settings > Pickup: customers collecting orders, per location. */
export function PickupView() {
  const t = useT();
  const { orgId } = useSeller();
  const { canEdit } = useSectionAccess('pickup');
  const q = useQuery(sellerQueries.shipFrom(sellerApi(), orgId));
  return (
    <SettingsPage section="pickup">
      <p className="m-0 max-w-[80ch] text-sm" data-testid="pickup-intro">
        {t('sc.pickupCard.intro')}
      </p>
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
      {q.data && q.data.length === 0 && canEdit ? <div><BusinessAddressStart /></div> : null}
      <ul className="m-0 flex list-none flex-col p-0">
        {q.data?.map((a) => (
          <li key={a.id} className="border-b border-border py-4 first:border-t" data-tour="settings-pickup-location">
            <PickupLocationForm key={`${a.id}-${a.customerPickup?.enabled ? 'on' : 'off'}-${a.customerPickup?.enabledAt ?? ''}`} a={a} editable={canEdit} />
          </li>
        ))}
      </ul>
    </SettingsPage>
  );
}
