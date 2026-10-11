'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import {
  LINK_EXPIRY_MAX_HOURS,
  LINK_EXPIRY_MIN_HOURS,
  linkExpiryProblem,
  type PaymentMethod,
  type PlacementKey,
  type PreorderFallback,
  type PreorderPaymentSettings,
  type PreorderSettings,
  type PreorderSettingsInput
} from '@afrotransact/api-client/staff-preorders';
import { cx, useToast } from '@afrotransact/ui';
import { ListChecks, Package } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';

import { amountText, NO_SETTINGS_TITLE, parseAmount, PAY_NOW_LINK_LABEL } from './model';
import { PreorderAreasTree } from './PreorderAreasTree';
import { controlClass, labelClass, smallButton, Toggle, usePreorderAccess, usePreordersApi } from './primitives';

const FALLBACKS: [PreorderFallback, typeof Package, string, string][] = [
  ['SHIP', Package, 'Ship by UPS Ground', 'Shoppers can still buy, at the quoted rate (3–5 days).'],
  ['WAITLIST', ListChecks, 'Waitlist only', 'No checkout. Shoppers are told when the next pre-order opens.']
];
const PLACEMENTS: [PlacementKey, string, string, string][] = [
  ['ribbon', 'Header ribbon', 'Under the header on every page', 'Ribbon text'],
  ['hero', 'Home hero', 'Top of the home page + pre-order page banner', 'Storefront hero'],
  ['badge', 'Pre-order badge', 'On product cards and search results', '—'],
  ['pdp', 'Product page banner', 'Below the delivery box', 'Product page banner'],
  ['cart', 'Cart header', 'Groups pre-order items with the delivery date', '—'],
  ['family', 'Send to family', 'Metro shoppers with a saved run-city address', '—']
];
const METHODS: [PaymentMethod, string, string][] = [
  ['CARD', 'Card', 'Visa, Mastercard, Amex, Discover'],
  ['WALLET', 'Apple Pay, Google Pay, Link', 'Wallets on supported devices'],
  ['ACH', 'ACH bank debit', 'US bank accounts (settles in 3–5 days)']
];
const CURRENCIES = ['USD', 'CAD', 'GBP', 'EUR', 'NGN', 'GHS', 'KES', 'ZAR', 'JMD'];

function useSaveSettings() {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ current, patch }: { current: PreorderSettings; patch: Partial<PreorderSettingsInput>; message: string }) => {
      const { version, ...rest } = current;
      return api.updateSettings({ ...rest, ...patch }, version);
    },
    onSuccess: (next, v) => {
      qc.setQueryData(staffPreorderKeys.settings(), next);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.lists() });
      toast.show(v.message);
    },
    onError: (e) => {
      toast.show(problemMessage(e), 'error');
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.settings() });
    }
  });
}

/** Pre-order areas: the shared location tree (country → states → cities, each On or Off), edited in place. */
function Areas({ canEdit }: { canEdit: boolean }) {
  return (
    <section aria-labelledby="po-areas" className="flex flex-col gap-2">
      <h3 id="po-areas" className="m-0 text-sm font-bold">
        Pre-order areas
      </h3>
      <span className="text-xs text-muted">
        Only places listed here can get a pre-order. Open a country and a state to add cities, switch them on or off, or cover every city in the state. Off areas are hidden until you show them.
      </span>
      <PreorderAreasTree canEdit={canEdit} />
    </section>
  );
}

/** Payment & fees (Full level): door / pickup fees per currency, pickup window, methods, late-add payment, missed delivery. */
function PaymentFees({ s, canEdit }: { s: PreorderSettings; canEdit: boolean }) {
  const save = useSaveSettings();
  const [p, setP] = useState<PreorderPaymentSettings>(s.payment);
  const [text, setText] = useState<Record<string, { door: string; pickup: string }>>(() =>
    Object.fromEntries([...new Set([...Object.keys(s.payment.doorFee), ...Object.keys(s.payment.pickupFee)])].map((c) => [c, { door: amountText(s.payment.doorFee[c] ?? 0), pickup: amountText(s.payment.pickupFee[c] ?? 0) }]))
  );
  const [addCur, setAddCur] = useState('');
  const [hours, setHours] = useState(String(s.payment.lateAdd.linkExpiryHours));
  const hoursProblem = linkExpiryProblem(hours.trim() === '' ? Number.NaN : Number(hours));
  const [error, setError] = useState<string | null>(null);
  const currencies = Object.keys(text);
  const toggleMethod = (m: PaymentMethod) => setP((cur) => ({ ...cur, allowedMethods: cur.allowedMethods.includes(m) ? cur.allowedMethods.filter((x) => x !== m) : [...cur.allowedMethods, m] }));
  const submit = () => {
    const doorFee: Record<string, number> = {};
    const pickupFee: Record<string, number> = {};
    for (const [c, v] of Object.entries(text)) {
      const d = parseAmount(v.door);
      const k = parseAmount(v.pickup);
      if (d === null || Number.isNaN(d) || (k !== null && Number.isNaN(k))) {
        setError(`Enter the ${c} fees as amounts like 7.99.`);
        return;
      }
      doorFee[c] = d;
      pickupFee[c] = k ?? 0;
    }
    if (!p.allowedMethods.length) return setError('Allow at least one payment method.');
    if (!p.lateAdd.savedCard && !p.lateAdd.paymentLink) return setError('Keep the saved card or the pay-now link on for late adds.');
    if (!p.missedDelivery.offerRefund && !p.missedDelivery.offerNewDate) return setError('Offer a refund or a new date when a delivery is missed.');
    if (hoursProblem) return setError(`${hoursProblem} (${LINK_EXPIRY_MIN_HOURS}–${LINK_EXPIRY_MAX_HOURS}).`);
    setError(null);
    save.mutate({ current: s, patch: { payment: { ...p, doorFee, pickupFee, lateAdd: { ...p.lateAdd, linkExpiryHours: Number(hours) } } }, message: 'Payment & fees saved' });
  };
  const row = 'flex items-center gap-3 border-b border-border py-2';
  return (
    <section aria-labelledby="po-pay" className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h3 id="po-pay" className="m-0 text-sm font-bold">
          Payment &amp; fees
        </h3>
        <span className="text-xs text-muted">
          {canEdit ? 'Platform defaults for every pre-order; a pre-order can override its door and pickup fee.' : 'Only full Pre-orders access can change these.'}
        </span>
      </div>
      <fieldset disabled={!canEdit} className="m-0 flex flex-col gap-4 border-0 p-0">
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[420px] border-collapse text-sm" aria-label="Delivery fees by currency">
            <thead className="bg-surface-muted text-left text-2xs font-bold tracking-[0.06em] text-muted uppercase">
              <tr>
                <th scope="col" className="px-3 py-2">Currency</th>
                <th scope="col" className="px-3 py-2">Door delivery</th>
                <th scope="col" className="px-3 py-2">Pickup</th>
              </tr>
            </thead>
            <tbody>
              {currencies.map((c) => (
                <tr key={c} className="border-t border-border">
                  <th scope="row" className="px-3 py-2 text-left font-mono text-xs">{c}</th>
                  <td className="px-3 py-2">
                    <input aria-label={`${c} door delivery fee`} inputMode="decimal" value={text[c]?.door ?? ''} onChange={(e) => setText({ ...text, [c]: { door: e.currentTarget.value, pickup: text[c]?.pickup ?? '' } })} className={`${controlClass} h-8 w-28`} />
                  </td>
                  <td className="px-3 py-2">
                    <input aria-label={`${c} pickup fee`} inputMode="decimal" value={text[c]?.pickup ?? ''} onChange={(e) => setText({ ...text, [c]: { door: text[c]?.door ?? '', pickup: e.currentTarget.value } })} className={`${controlClass} h-8 w-28`} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
            Add a currency
            <select value={addCur} onChange={(e) => setAddCur(e.currentTarget.value)} className="h-8 rounded-sm border border-border-strong bg-ink-0 px-2 text-sm">
              <option value="">Choose…</option>
              {CURRENCIES.filter((c) => !currencies.includes(c)).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            disabled={!addCur}
            onClick={() => {
              setText({ ...text, [addCur]: { door: '', pickup: '0.00' } });
              setAddCur('');
            }}
            className={smallButton}
          >
            Add
          </button>
        </div>
        <label className={`${labelClass} max-w-sm`}>
          Pickup window
          <input value={p.pickupWindow} onChange={(e) => setP({ ...p, pickupWindow: e.currentTarget.value })} maxLength={40} className={controlClass} />
        </label>
        <div className="flex flex-col">
          <strong className="text-sm">Accepted payment methods</strong>
          {METHODS.map(([m, name, sub]) => (
            <div key={m} className={row}>
              <Toggle on={p.allowedMethods.includes(m)} label={name} onClick={() => toggleMethod(m)} />
              <span className="flex flex-col">
                <strong className="text-sm">{name}</strong>
                <span className="text-xs text-muted">{sub}</span>
              </span>
            </div>
          ))}
        </div>
        <p className="m-0 rounded-md bg-ink-50 px-3 py-2 text-xs text-muted">
          Refunds: shoppers are charged at checkout and can cancel pre-order items for a full refund, including delivery, until the pre-order closes.
        </p>
        <div className="flex flex-col">
          <strong className="text-sm">Late-add payment</strong>
          <div className={row}>
            <Toggle on={p.lateAdd.savedCard} label="Charge the saved card on file" onClick={() => setP({ ...p, lateAdd: { ...p.lateAdd, savedCard: !p.lateAdd.savedCard } })} />
            <strong className="text-sm">Charge the saved card on file</strong>
          </div>
          <div className={`${row} flex-wrap`}>
            <Toggle on={p.lateAdd.paymentLink} label={PAY_NOW_LINK_LABEL} onClick={() => setP({ ...p, lateAdd: { ...p.lateAdd, paymentLink: !p.lateAdd.paymentLink } })} />
            <strong className="flex-1 text-sm">{PAY_NOW_LINK_LABEL}</strong>
            <label className="flex items-center gap-2 text-xs text-muted">
              Link expires after
              <input
                type="number"
                inputMode="numeric"
                step={1}
                min={LINK_EXPIRY_MIN_HOURS}
                max={LINK_EXPIRY_MAX_HOURS}
                value={hours}
                aria-invalid={hoursProblem ? true : undefined}
                aria-describedby="po-link-hours"
                onChange={(e) => setHours(e.currentTarget.value)}
                className={`${controlClass} h-8 w-20`}
              />
              hours
            </label>
            <span id="po-link-hours" className={cx('w-full text-xs', hoursProblem ? 'text-red-700' : 'text-muted')}>
              {hoursProblem ?? `Between ${LINK_EXPIRY_MIN_HOURS} and ${LINK_EXPIRY_MAX_HOURS} hours: pay-now links can’t last longer than a day.`}
            </span>
          </div>
        </div>
        <div className="flex flex-col">
          <strong className="text-sm">If we miss the delivery date, the shopper can choose</strong>
          <div className={row}>
            <Toggle on={p.missedDelivery.offerRefund} label="A full refund" onClick={() => setP({ ...p, missedDelivery: { ...p.missedDelivery, offerRefund: !p.missedDelivery.offerRefund } })} />
            <strong className="text-sm">A full refund</strong>
          </div>
          <div className={row}>
            <Toggle on={p.missedDelivery.offerNewDate} label="Wait for the new date" onClick={() => setP({ ...p, missedDelivery: { ...p.missedDelivery, offerNewDate: !p.missedDelivery.offerNewDate } })} />
            <strong className="text-sm">Wait for the new date</strong>
          </div>
        </div>
        {error ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {error}
          </p>
        ) : null}
        {canEdit ? (
          <div>
            <button type="button" onClick={submit} disabled={save.isPending} className="h-10 cursor-pointer rounded-md border-0 bg-green-600 px-5 font-bold text-ink-0 disabled:opacity-45">
              Save payment &amp; fees
            </button>
          </div>
        ) : null}
      </fieldset>
    </section>
  );
}

/** Areas & settings: master switch, coverage areas, fallback, placements, payment & fees. */
export function PreorderSettingsPanel() {
  const api = usePreordersApi();
  const access = usePreorderAccess();
  const settings = useQuery(staffPreorderQueries.settings(api));
  const save = useSaveSettings();
  if (settings.isPending) return <Spinner />;
  if (settings.isError) return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />;
  const s = settings.data;
  const canEdit = access.canSettings;
  return (
    <div className="flex flex-col gap-6 p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h2 className="m-0 text-lg font-bold">Pre-order for the whole platform</h2>
          <span className="text-sm text-muted">Switches every city on or off at once. Takes effect right away, with no deployment.</span>
        </div>
        <Toggle
          size="lg"
          on={s.enabled}
          label="Master switch"
          disabled={!canEdit || save.isPending}
          title={canEdit ? undefined : NO_SETTINGS_TITLE}
          onClick={() => save.mutate({ current: s, patch: { enabled: !s.enabled }, message: `Pre-order switched ${s.enabled ? 'off' : 'on'} for all cities` })}
        />
      </div>
      {!canEdit ? (
        <p className="m-0 text-xs text-muted" data-testid="settings-note">
          {access.canEdit ? 'Pre-order managers with Edit access can add areas from a pre-order; switching areas, fallback, placements and fees needs Full access.' : 'View only for your role.'}
        </p>
      ) : null}
      <Areas canEdit={canEdit} />
      <section aria-labelledby="po-fallback" className="flex flex-col gap-2">
        <h3 id="po-fallback" className="m-0 text-sm font-bold">
          When pre-order is off for a city
        </h3>
        {FALLBACKS.map(([k, Icon, title, sub]) => (
          <button
            key={k}
            type="button"
            aria-pressed={s.fallback === k}
            disabled={!canEdit || save.isPending}
            onClick={() => s.fallback !== k && save.mutate({ current: s, patch: { fallback: k }, message: `Fallback: ${title.toLowerCase()}` })}
            className={cx('flex cursor-pointer items-start gap-3 rounded-md border-2 bg-ink-0 p-3 text-left text-text disabled:cursor-not-allowed', s.fallback === k ? 'border-green-600' : 'border-border')}
          >
            <Icon size={20} aria-hidden />
            <span className="flex flex-col">
              <strong className="text-sm">{title}</strong>
              <span className="text-xs text-muted">{sub}</span>
            </span>
          </button>
        ))}
      </section>
      <section aria-labelledby="po-placements" className="flex flex-col gap-2">
        <h3 id="po-placements" className="m-0 text-sm font-bold">
          Advertorial placements
        </h3>
        {PLACEMENTS.map(([k, name, where, creative]) => (
          <div key={k} className="flex items-center gap-3 border-b border-border py-2">
            <Toggle
              on={s.placements[k]}
              label={`Toggle placement ${name}`}
              disabled={!canEdit || save.isPending}
              onClick={() => save.mutate({ current: s, patch: { placements: { ...s.placements, [k]: !s.placements[k] } }, message: `${name} ${s.placements[k] ? 'off' : 'on'}` })}
            />
            <span className="flex flex-1 flex-col">
              <strong className="text-sm">{name}</strong>
              <span className="text-xs text-muted">{where}</span>
            </span>
            <span className="text-xs text-subtle">{creative}</span>
          </div>
        ))}
      </section>
      <PaymentFees key={s.version} s={s} canEdit={canEdit} />
    </div>
  );
}
