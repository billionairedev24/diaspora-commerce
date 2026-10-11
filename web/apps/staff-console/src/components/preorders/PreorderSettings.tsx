'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import {
  LINK_EXPIRY_MAX_HOURS,
  LINK_EXPIRY_MIN_HOURS,
  linkExpiryProblem,
  type PaymentMethod,
  type PlacementKey,
  type PreorderAreaRow,
  type PreorderFallback,
  type PreorderPaymentSettings,
  type PreorderSettings,
  type PreorderSettingsInput
} from '@afrotransact/api-client/staff-preorders';
import { cx, useToast } from '@afrotransact/ui';
import { CaretRight, FunnelSimple, ListChecks, Package, X } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';

import { AreaPicker } from './AreaPicker';
import { amountText, NO_SETTINGS_TITLE, parseAmount } from './model';
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

/** Coverage areas: add via dropdowns or "City, ST", drill down country → region → city with on/off toggles. */
function Areas({ canEdit, canAdd }: { canEdit: boolean; canAdd: boolean }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [nav, setNav] = useState<{ c?: string; r?: string }>({});
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [free, setFree] = useState('');
  const query = useQuery(staffPreorderQueries.areas(api, { country: nav.c, region: nav.r, q: q.trim() || undefined, page }));
  const root = useQuery(staffPreorderQueries.areas(api, { page: 0 }));
  const refresh = () => qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
  const fail = (e: unknown) => toast.show(problemMessage(e), 'error');
  const add = useMutation({
    mutationFn: (key: string) => api.addArea(key),
    onSuccess: (p, key) => {
      void refresh();
      setNav({ c: p.country ?? undefined, r: p.region ?? undefined });
      setQ('');
      setPage(0);
      const [, r, city] = key.split('/');
      toast.show(`${city ? `${city}, ${r ?? ''}` : `All of ${p.regionName ?? r ?? ''}`} added to pre-order areas`);
    },
    onError: fail
  });
  const toggle = useMutation({ mutationFn: (row: PreorderAreaRow) => api.setAreas([row.key], row.on === 0), onSuccess: () => void refresh(), onError: fail });
  const remove = useMutation({ mutationFn: (row: PreorderAreaRow) => api.removeArea(row.key), onSuccess: () => void refresh(), onError: fail });
  const p = query.data;
  const saved = new Set((root.data?.saved ?? []).map((s) => s.key));
  const has = (k: string) => saved.has(k) || (p?.level === 'AREA' && p.rows.some((r) => r.key === k));
  const freeKey = (() => {
    const m = /^(.+?),\s*([A-Za-z]{2,3})$/.exec(free.trim());
    return m ? { city: (m[1] ?? '').trim(), region: (m[2] ?? '').toUpperCase() } : null;
  })();
  const colName = !p || p.level === 'COUNTRY' ? 'Country' : p.level === 'REGION' ? p.regionLabel : 'City';
  const crumbs: { label: string; go: () => void }[] = [{ label: 'All countries', go: () => setNav({}) }];
  if (p?.country) crumbs.push({ label: p.countryName ?? p.country, go: () => setNav({ c: p.country ?? undefined }) });
  if (p?.region) crumbs.push({ label: p.regionName ?? p.region, go: () => undefined });
  const pages = p ? Math.max(1, Math.ceil(p.total / p.size)) : 1;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <h3 className="m-0 text-sm font-bold">Pre-order areas</h3>
        <span className="text-xs text-muted" data-testid="area-count">
          {p ? `${p.onCount} on · ${p.offCount} off` : ''}
        </span>
      </div>
      <span className="text-xs text-muted">
        Only places you add here can get a pre-order. Pick a country, then a state or province, then a city (or every city in it). Switch an area off to pause it without losing its settings.
      </span>
      <div className="flex max-w-[720px] flex-col gap-2 rounded-md border border-border bg-ink-25 p-3">
        <AreaPicker onAdd={(k) => add.mutate(k)} exclude={has} disabled={!canAdd} pending={add.isPending} addLabel="Add area" />
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!freeKey || !canAdd) return;
            void api
              .places({ q: freeKey.region })
              .then((r) => {
                const region = r.items.find((x) => x.type === 'REGION' && x.code === freeKey.region);
                if (!region) {
                  toast.show(`No state, province or region “${freeKey.region}”. Type it as “City, ST”.`, 'error');
                  return;
                }
                add.mutate(`${region.key}/${freeKey.city}`);
                setFree('');
              })
              .catch(fail);
          }}
        >
          <label className="flex min-w-[220px] flex-1 flex-col gap-1 text-xs font-semibold text-muted">
            Or type a city that isn&apos;t in the list
            <input value={free} onChange={(e) => setFree(e.currentTarget.value)} placeholder="City, ST" disabled={!canAdd} className={controlClass} />
          </label>
          <button type="submit" disabled={!canAdd || !freeKey} className={smallButton}>
            Add city
          </button>
        </form>
      </div>
      <div className="flex flex-col overflow-hidden rounded-md border border-border">
        <div className="flex flex-wrap items-center gap-2 bg-ink-50 px-3 py-2">
          <nav aria-label="Area level" className="flex flex-wrap items-center gap-1 text-sm">
            {crumbs.map((c, i) => {
              const cur = i === crumbs.length - 1;
              return (
                <span key={c.label} className="flex items-center gap-1">
                  <button
                    type="button"
                    onClick={() => {
                      c.go();
                      setQ('');
                      setPage(0);
                    }}
                    disabled={cur}
                    aria-current={cur ? 'location' : undefined}
                    className={cx('cursor-pointer border-0 bg-transparent px-1 py-0.5 text-sm', cur ? 'font-bold text-text' : 'font-normal text-link')}
                  >
                    {c.label}
                  </button>
                  {!cur ? <span className="text-subtle">›</span> : null}
                </span>
              );
            })}
          </nav>
          <span className="flex-1" />
          <label className="flex h-8 min-w-[180px] items-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-2">
            <FunnelSimple aria-hidden className="text-ink-500" />
            <input
              value={q}
              onChange={(e) => {
                setQ(e.currentTarget.value);
                setPage(0);
              }}
              placeholder={!p || p.level === 'COUNTRY' ? 'Filter countries' : p.level === 'REGION' ? `Filter ${p.regionLabel.toLowerCase()}s` : 'Filter cities'}
              aria-label="Filter this list"
              className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
            />
          </label>
        </div>
        {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
        <table className="w-full border-collapse text-sm" aria-label="Pre-order areas" aria-busy={query.isFetching || undefined}>
          <colgroup>
            <col style={{ width: '34%' }} />
            <col style={{ width: '30%' }} />
            <col style={{ width: '12%' }} />
            <col style={{ width: '17%' }} />
            <col style={{ width: '7%' }} />
          </colgroup>
          <thead>
            <tr className="border-t border-border text-left text-2xs font-bold tracking-[0.06em] text-subtle uppercase">
              <th scope="col" className="px-3 py-2">{colName}</th>
              <th scope="col" className="px-3 py-2">Coverage</th>
              <th scope="col" className="px-3 py-2">Pre-orders</th>
              <th scope="col" className="px-3 py-2">On / off</th>
              <th scope="col" className="px-3 py-2">
                <span className="at-visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {(p?.rows ?? []).map((a) => {
              const on = a.on > 0;
              const open = () => {
                const [c, r] = a.key.split('/');
                setNav(r ? { c, r } : { c });
                setQ('');
                setPage(0);
              };
              return (
                <tr key={a.key} className="border-t border-border bg-ink-0" data-testid="area-row">
                  <td className="px-3 py-2">
                    {a.leaf ? (
                      <span className={cx('font-semibold', !on && 'line-through')}>{a.name}</span>
                    ) : (
                      <button type="button" onClick={open} className={cx('cursor-pointer border-0 bg-transparent p-0 text-left text-sm font-semibold text-text', !on && 'line-through')}>
                        {a.name}
                      </button>
                    )}
                  </td>
                  <td className="truncate px-3 py-2 text-xs text-muted">{a.coverage}</td>
                  <td className="px-3 py-2 text-xs">{a.windows ? a.windows : '—'}</td>
                  <td className="px-3 py-2">
                    <span className="flex items-center gap-2">
                      <Toggle on={on} label={`Switch ${a.name} on or off`} disabled={!canEdit || toggle.isPending} title={canEdit ? undefined : NO_SETTINGS_TITLE} onClick={() => toggle.mutate(a)} />
                      <span className="text-2xs text-muted">{a.leaf ? (on ? 'On' : 'Off') : `${a.on} of ${a.total}`}</span>
                    </span>
                  </td>
                  <td className="px-1 py-2">
                    {a.leaf ? (
                      <button type="button" onClick={() => remove.mutate(a)} disabled={!canEdit} aria-label={`Remove ${a.name}`} className="size-7 cursor-pointer rounded-sm border-0 bg-transparent text-ink-500 disabled:opacity-45">
                        <X aria-hidden />
                      </button>
                    ) : (
                      <button type="button" onClick={open} aria-label={`Open ${a.name}`} className="size-7 cursor-pointer rounded-sm border-0 bg-transparent text-ink-500">
                        <CaretRight aria-hidden />
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {p && !p.rows.length ? (
              <tr>
                <td colSpan={5} className="border-t border-border p-5 text-sm text-muted">
                  {q.trim() ? `Nothing here matches “${q.trim()}”.` : 'No pre-order areas yet. Use the dropdowns above to add the first one.'}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
        {!p ? <Spinner /> : null}
        {p && p.total > p.size ? (
          <div className="flex items-center justify-between border-t border-border px-3 py-2 text-xs text-muted">
            <span>{`${p.page * p.size + 1}–${Math.min(p.total, p.page * p.size + p.size)} of ${p.total}`}</span>
            <span className="flex gap-1">
              <button type="button" onClick={() => setPage(Math.max(0, page - 1))} className={smallButton}>
                Prev
              </button>
              <button type="button" onClick={() => setPage(Math.min(pages - 1, page + 1))} className={smallButton}>
                Next
              </button>
            </span>
          </div>
        ) : null}
      </div>
    </div>
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
    if (!p.lateAdd.savedCard && !p.lateAdd.paymentLink) return setError('Keep the saved card or the payment link on for late adds.');
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
            <Toggle on={p.lateAdd.paymentLink} label="Send a payment link by SMS" onClick={() => setP({ ...p, lateAdd: { ...p.lateAdd, paymentLink: !p.lateAdd.paymentLink } })} />
            <strong className="flex-1 text-sm">Send a payment link by SMS</strong>
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
              {hoursProblem ?? `Between ${LINK_EXPIRY_MIN_HOURS} and ${LINK_EXPIRY_MAX_HOURS} hours: payment links can’t last longer than a day.`}
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
      <Areas canEdit={canEdit} canAdd={canEdit} />
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
