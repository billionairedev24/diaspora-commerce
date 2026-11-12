'use client';

import { isApiError, PICKUP_DAYS, type PickupPromptAnswer, type PickupWindow, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, Checkbox, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Storefront, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { blankWindow, cardVisible, checkTurnOn, nudgeVisible, previewZip, turnOnRequest } from '@/lib/pickup-prompt';
import { useSeller } from '@/lib/session';

/**
 * The one-time "let customers collect orders here" card, shown to a seller who has an approved ship-from address and no
 * pickup place. It is pre-filled from the address and never switches anything on by itself: the seller types the opening
 * hours, reads the plain explanation, confirms someone will be there, then presses "Turn on pickup". "Not now" and "Don't
 * ask again" are remembered by the server per organisation, so they hold on every device.
 */
export function PickupPromptCard() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const prompt = useQuery(sellerQueries.pickupPrompt(sellerApi(), orgId));
  const [hours, setHours] = useState<PickupWindow[]>([]);
  const [ready, setReady] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: sellerKeys.pickupPrompt(orgId) });
    void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
  };
  const turnOn = useMutation({
    mutationFn: (req: NonNullable<ReturnType<typeof turnOnRequest>>) => {
      const address = prompt.data?.address;
      if (!address) throw new Error('no address');
      return sellerApi().setCustomerPickup(address.addressId, req);
    },
    onSuccess: () => {
      setProblem(null);
      toast.show(t('sc.cpickup.saved'));
      refresh();
    },
    onError: (e) => setProblem(isApiError(e) && e.code === 'PICKUP_INVALID_HOURS' ? t('sc.pickup.err.hours') : problemMessage(e))
  });
  const answer = useMutation({
    mutationFn: (a: PickupPromptAnswer) => sellerApi().answerPickupPrompt(a),
    onSuccess: refresh,
    onError: () => setProblem(t('sc.pickupCard.answerErr'))
  });

  const data = prompt.data;
  const address = data?.address;
  const editable = can('orders', 'EDIT');
  if (!data || !address || !editable || !cardVisible(data)) return null;

  const zip = previewZip(address);
  const check = checkTurnOn(hours, ready, confirmed, { ready: t('sc.cpickup.readyInvalid'), confirm: t('sc.pickupCard.needConfirm') });
  const request = turnOnRequest(hours, ready, confirmed);
  const dayOptions = PICKUP_DAYS.map((d) => ({ value: d, label: t(`sc.day.${d}`) }));
  const setWindow = (i: number, w: Partial<PickupWindow>) => setHours((hs) => hs.map((h, j) => (j === i ? { ...h, ...w } : h)));
  const hint = hours.length === 0 || Object.keys(check.errors).some((k) => k.startsWith('hours') || k === 'readyHours') ? t('sc.pickupCard.needHours') : !confirmed ? t('sc.pickupCard.needConfirm') : null;
  const busy = turnOn.isPending || answer.isPending;
  return (
    <Card
      title={
        <span className="flex items-center gap-2">
          <Storefront size={18} aria-hidden /> {t('sc.pickupCard.title')}
        </span>
      }
    >
      <div className="flex flex-col gap-3" data-testid="pickup-prompt" role="group" aria-label={t('sc.pickupCard.title')}>
        <p className="m-0 text-sm">{t('sc.pickupCard.intro')}</p>
        <dl className="m-0 grid gap-1 text-sm" data-testid="pickup-prompt-address">
          <dt className="font-semibold">{t('sc.pickupCard.address')}</dt>
          <dd className="m-0">
            {address.name}
            <br />
            {[address.line1, address.line2].filter(Boolean).join(', ')}
            <br />
            {address.city}, {address.state} {address.postalCode}
          </dd>
          {address.phone ? (
            <>
              <dt className="font-semibold">{t('sc.pickupCard.phone')}</dt>
              <dd className="m-0">{address.phone}</dd>
            </>
          ) : null}
        </dl>
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={busy}>
          <legend className="mb-1 text-sm font-semibold">{t('sc.cpickup.hours')}</legend>
          <p className="m-0 text-sm text-muted">{hours.length === 0 ? t('sc.pickupCard.hoursNone') : t('sc.pickupCard.hoursHelp')}</p>
          {hours.map((h, i) => (
            <div key={`pp-${i}`} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2" data-testid="pickup-prompt-window">
              <Select id={`pp-day-${i}`} label={<span className="at-visually-hidden">{t('sc.pickup.dayN', { n: i + 1 })}</span>} value={h.day} options={dayOptions} onChange={(e) => setWindow(i, { day: e.currentTarget.value as PickupWindow['day'] })} />
              <TextField id={`pp-opens-${i}`} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.opensN', { n: i + 1 })}</span>} value={h.opens} onChange={(e) => setWindow(i, { opens: e.currentTarget.value })} />
              <TextField id={`pp-closes-${i}`} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.closesN', { n: i + 1 })}</span>} value={h.closes} onChange={(e) => setWindow(i, { closes: e.currentTarget.value })} />
              <Button size="sm" variant="neutral" aria-label={t('sc.pickup.removeN', { n: i + 1 })} onClick={() => setHours((hs) => hs.filter((_, j) => j !== i))}>
                <Trash size={16} aria-hidden />
              </Button>
            </div>
          ))}
          {hours.length < 14 ? (
            <div>
              <Button size="sm" variant="neutral" onClick={() => setHours((hs) => [...hs, blankWindow()])}>
                <Plus size={16} aria-hidden /> {t('sc.pickup.addWindow')}
              </Button>
            </div>
          ) : null}
          <TextField id="pp-ready" type="number" inputMode="numeric" min={0} max={168} label={t('sc.cpickup.ready')} help={t('sc.cpickup.readyHelp')} value={ready} onChange={(e) => setReady(e.currentTarget.value)} />
        </fieldset>
        {zip ? (
          <p className="m-0 text-sm" data-testid="pickup-prompt-preview">
            {t('sc.pickupCard.preview', { zip })}
          </p>
        ) : null}
        <Checkbox id="pp-confirm" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.currentTarget.checked)} label={t('sc.pickupCard.confirm')} />
        <p className="m-0 text-sm text-muted">{t('sc.pickupCard.offNote')}</p>
        {problem ? (
          <Notice tone="danger" title={t('sc.cpickup.errTitle')} role="alert">
            {problem}
          </Notice>
        ) : null}
        {hint && !check.ok ? <p className="m-0 text-sm text-muted">{hint}</p> : null}
        <div className="flex flex-wrap gap-2">
          <Button
            loading={turnOn.isPending}
            disabled={!request || busy}
            onClick={() => {
              if (request) turnOn.mutate(request);
            }}
          >
            {t('sc.pickupCard.turnOn')}
          </Button>
          <Button variant="neutral" disabled={busy} onClick={() => answer.mutate('NOT_NOW')}>
            {t('sc.pickupCard.notNow')}
          </Button>
          <Button variant="neutral" disabled={busy} onClick={() => answer.mutate('DONT_ASK_AGAIN')}>
            {t('sc.pickupCard.dontAsk')}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/** A small inline reminder for checkout-related screens: shown only while the seller has no pickup place. */
export function PickupNudge() {
  const t = useT();
  const { orgId, can } = useSeller();
  const prompt = useQuery(sellerQueries.pickupPrompt(sellerApi(), orgId));
  if (!can('orders', 'VIEW') || !nudgeVisible(prompt.data)) return null;
  return (
    <Notice
      tone="info"
      title={t('sc.pickupCard.nudge')}
      action={
        <Link href="/orders/ship-from" className="text-link underline">
          {t('sc.pickupCard.nudgeLink')}
        </Link>
      }
    />
  );
}
