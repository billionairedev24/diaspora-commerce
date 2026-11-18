'use client';

import { isApiError, sellerKeys, sellerQueries, type ShipFromAddress } from '@afrotransact/api-client';
import { Button, fieldStyles, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { FulfilmentTip } from '@/components/ui/FulfilmentTip';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { draftOf, pickupCanSave, type PickupDraft, pickupProblem, pickupSave } from '@/lib/pickup-settings';
import { useSeller } from '@/lib/session';
import { READY_CHOICES } from '@/lib/weekly-hours';

import { BusinessAddressStart } from './BusinessAddressStart';
import { SettingsPage, useSectionAccess } from './SettingsShell';
import { WeeklyHours } from './WeeklyHours';

/**
 * One location's pickup, kept small: one switch (turning it on is the confirmation that someone will be there), the address
 * as one read-only line, the week's hours, how soon an order is ready, and one Save. Nothing is switched on or filled in for them.
 */
function PickupLocationForm({ a, editable }: { a: ShipFromAddress; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const saved = a.customerPickup;
  const [draft, setDraft] = useState<PickupDraft>(() => draftOf(saved));
  const [problem, setProblem] = useState<string | null>(null);
  const wasOn = saved?.enabled ?? false;
  const save = useMutation({
    mutationFn: (req: Parameters<ReturnType<typeof sellerApi>['setCustomerPickup']>[1]) => sellerApi().setCustomerPickup(a.id, req),
    onSuccess: (next) => {
      setProblem(null);
      setDraft(draftOf(next.customerPickup));
      toast.show(t('sc.cpickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.pickupPrompt(orgId) });
    },
    onError: (e) => setProblem(isApiError(e) && (e.code === 'PICKUP_INVALID_HOURS' || e.code === 'PICKUP_HOURS_NOT_CONFIRMED') ? t('sc.settings.pickup.err.confirm') : problemMessage(e))
  });
  const msgs = { none: t('sc.weekly.errNone'), missing: t('sc.weekly.errMissing'), order: t('sc.weekly.errOrder'), ready: t('sc.cpickup.readyInvalid') };
  const issue = draft.enabled ? pickupProblem(draft, msgs) : null;
  const canSave = pickupCanSave(saved, draft, msgs);
  const set = (patch: Partial<PickupDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const busy = save.isPending;
  const readyOptions = READY_CHOICES.map((n) => ({ value: String(n), label: t(`sc.ready.${n}` as const) }));
  const savedReady = Number(draft.ready);
  if (draft.ready !== '' && !readyOptions.some((o) => o.value === draft.ready)) readyOptions.push({ value: draft.ready, label: t('sc.ready.n', { n: savedReady }) });
  const id = (s: string) => `${a.id}-pk-${s}`;
  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        const r = pickupSave(saved, draft, msgs);
        if (!r.ok) {
          setProblem(r.message);
          return;
        }
        setProblem(null);
        save.mutate(r.request);
      }}
      className="flex flex-col gap-3"
      aria-label={t('sc.settings.pickup.formLabel', { name: a.label || a.city })}
      data-testid="pickup-location"
    >
      <h2 className="m-0 text-md font-semibold">{a.label || a.city}</h2>
      <p className="m-0 text-sm" data-testid="pickup-address">
        {[a.name, a.line1, a.line2, a.city, `${a.state} ${a.postalCode}`.trim()].filter(Boolean).join(', ')} ·{' '}
        <Link href="/settings/locations" className="text-link underline">
          {t('sc.settings.pickup.changeAddress')}
        </Link>
      </p>
      {(saved?.issues ?? []).length > 0 && wasOn ? (
        <div className="flex flex-col gap-2" data-testid="customer-pickup-issues">
          {(saved?.issues ?? []).map((i) => (
            <Notice key={i.code} tone={i.blocking ? 'warning' : 'info'} title={i.message} />
          ))}
        </div>
      ) : null}
      <div className="flex flex-col">
        <Switch id={id('enabled')} checked={draft.enabled} disabled={!editable || busy} onChange={(enabled) => set({ enabled })} label={t('sc.settings.pickup.toggle')} />
        <FulfilmentTip mode="pickup" />
        <p className="m-0 text-sm text-muted" data-testid="pickup-confirm-line">
          {wasOn && saved?.enabledAt ? t('sc.settings.pickup.since', { date: shortDate(saved.enabledAt) }) : t('sc.settings.pickup.confirmLine')}
        </p>
      </div>
      {draft.enabled ? (
        <>
          <WeeklyHours week={draft.week} onChange={(week) => set({ week })} disabled={!editable || busy} invalidDays={issue?.days ?? []} legend={t('sc.cpickup.hours')} />
          <div className="flex min-h-(--at-size-control) items-center gap-3">
            <label htmlFor={id('ready')} className="text-sm font-semibold">
              {t('sc.ready.label')}
            </label>
            <div className="w-40">
              <select id={id('ready')} className={fieldStyles.control} disabled={!editable || busy} value={draft.ready} onChange={(e) => set({ ready: e.currentTarget.value })}>
                <option value="">{t('sc.ready.choose')}</option>
                {readyOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </>
      ) : null}
      {problem ? (
        <Notice tone="danger" title={t('sc.cpickup.errTitle')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {editable ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" size="sm" loading={busy} disabled={!canSave}>
            {t('sc.cpickup.save')}
          </Button>
          {issue ? (
            <p className="m-0 text-sm text-muted" data-testid="pickup-validation">
              {issue.message}
            </p>
          ) : null}
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
      <ul className="m-0 flex max-w-[40rem] list-none flex-col p-0">
        {q.data?.map((a) => (
          <li key={a.id} className="border-b border-border py-4 first:border-t" data-tour="settings-pickup-location">
            <PickupLocationForm key={`${a.id}-${a.customerPickup?.enabled ? 'on' : 'off'}-${a.customerPickup?.enabledAt ?? ''}`} a={a} editable={canEdit} />
          </li>
        ))}
      </ul>
    </SettingsPage>
  );
}
