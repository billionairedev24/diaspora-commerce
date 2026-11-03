'use client';

import { addressLookup, keepIdempotencyKey, staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import {
  LATE_ADD_REASON_LABEL,
  lateAddAddressSnapshot,
  type LateAddDraft,
  type LateAddPayment,
  lateAddProblems,
  LateAddReasonSchema,
  type LateAddRequest,
  newLateAddKey,
  type PreorderOrder,
  type PreorderWindow
} from '@afrotransact/api-client/staff-preorders';
import { AddressAutocomplete, Dialog, type SearchAddresses, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { useDebounced } from '@/lib/use-debounced';

import { money2, PAY_NOW_LINK_LABEL } from './model';
import { Chip, controlClass, labelClass, selectClass, usePreordersApi } from './primitives';

/** Street suggestions (geo: Google Places with session tokens, else OSM), as in the storefront. */
const staffAddressSearch: SearchAddresses = (q, options) => addressLookup(staffApi())(q, options);

const EMPTY: LateAddDraft = { customerId: '', addressId: '', newAddress: null, phoneConfirmed: false, items: [], payment: 'SAVED_CARD', reason: 'CUSTOMER_CALLED', note: '' };

/**
 * Late add (customer service): customer, saved or verified new address, phone read back, items, payment, reason, note.
 * One Idempotency-Key per dialog: made when it opens, reused on every retry until the order is added, so a retry after
 * a timeout can never charge the saved card twice. After a timeout or server error the dialog first looks the late add
 * up (it may have gone through) and only then offers to retry, with the same key.
 */
export function LateAddDialog({ w, open, onOpenChange, onAdded }: { w: PreorderWindow; open: boolean; onOpenChange: (open: boolean) => void; onAdded: (number: string) => void }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [d, setD] = useState<LateAddDraft>(EMPTY);
  const keyRef = useRef<string | null>(null);
  const startedAt = useRef<string>(new Date().toISOString());
  const [checking, setChecking] = useState(false);
  const [unsure, setUnsure] = useState(false);
  useEffect(() => {
    if (open && !keyRef.current) {
      keyRef.current = newLateAddKey();
      // Generous: the server's clock decides placedAt; the item set and phone narrow the match.
      startedAt.current = new Date(Date.now() - 15 * 60_000).toISOString();
    }
  }, [open]);
  const [find, setFind] = useState('');
  const q = useDebounced(find.trim(), 250);
  const customers = useQuery({ ...staffPreorderQueries.customers(api, w.id, q), enabled: open });
  const products = useQuery({ ...staffPreorderQueries.products(api, w.id), enabled: open });
  const settings = useQuery({ ...staffPreorderQueries.settings(api), enabled: open });
  const up = (patch: Partial<LateAddDraft>) => setD((cur) => ({ ...cur, ...patch }));
  const cust = customers.data?.items.find((c) => c.id === d.customerId) ?? null;
  const lateAdd = settings.data?.payment.lateAdd;
  const payOptions: [LateAddPayment, string][] = [
    ...(lateAdd?.savedCard !== false ? [['SAVED_CARD', 'Charge the saved card on file'] as [LateAddPayment, string]] : []),
    ...(lateAdd?.paymentLink !== false ? [['PAYMENT_LINK', `${PAY_NOW_LINK_LABEL}${lateAdd ? ` · expires in ${lateAdd.linkExpiryHours} h` : ''}`] as [LateAddPayment, string]] : [])
  ];
  const payment: LateAddPayment = payOptions.some(([k]) => k === d.payment) ? d.payment : (payOptions[0]?.[0] ?? 'SAVED_CARD');
  const sub = (products.data ?? []).filter((p) => d.items.includes(p.atin)).reduce((a, p) => a + p.price, 0);
  const total = sub + (d.items.length ? w.effectiveDoorFee : 0);
  const problems = lateAddProblems({ ...d, payment }, w.status);
  const close = (o: boolean) => {
    if (!o) {
      setD(EMPTY);
      setFind('');
      keyRef.current = null;
      setUnsure(false);
    }
    onOpenChange(o);
  };
  const verify = useMutation({
    mutationFn: () => {
      const na = d.newAddress;
      if (!cust || !na) throw new Error('No address');
      return api.verifyAddress({ line1: na.line1, zip: na.zip, city: cust.city, region: cust.region, country: cust.country });
    },
    onSuccess: (r) => {
      if (!r.verified || !r.placeId) {
        toast.show('We couldn’t verify that address. Check the street and postal code.', 'error');
        return;
      }
      setD((cur) => (cur.newAddress ? { ...cur, newAddress: { ...cur.newAddress, placeId: r.placeId } } : cur));
      toast.show('Address verified with Google Places');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const save = useMutation({
    mutationFn: () => {
      const body: LateAddRequest = {
        customerId: d.customerId,
        ...(d.newAddress?.placeId
          ? {
              newAddress: { line1: d.newAddress.line1.trim(), zip: d.newAddress.zip.trim(), placeId: d.newAddress.placeId },
              ...(cust ? { address: { fullName: cust.name, phone: cust.phone } } : {})
            }
          : { addressId: d.addressId, address: lateAddAddressSnapshot(cust, d.addressId) }),
        phoneConfirmed: true,
        items: d.items.map((atin) => ({ atin, qty: 1 })),
        payment,
        reason: d.reason,
        note: d.note.trim()
      };
      keyRef.current ??= newLateAddKey();
      return api.addLateOrder(w.id, body, keyRef.current);
    },
    onSuccess: (o) => added(o),
    onError: (e) => {
      if (!keepIdempotencyKey(e)) {
        // Refused (validation, declined card, duplicate…): nothing was added, a corrected attempt is a new one.
        keyRef.current = newLateAddKey();
        toast.show(problemMessage(e), 'error');
        return;
      }
      // Timeout or server error: it may have gone through. Look it up before allowing a retry (same key).
      void checkOutcome();
    }
  });
  const checkOutcome = async () => {
    setChecking(true);
    try {
      const found = cust ? await api.findLateAdd(w.id, cust.phone, d.items, startedAt.current) : null;
      if (found) return added(found);
      setUnsure(true);
      toast.show('We couldn’t confirm the late add. Retry: it is sent with the same key, so it can’t be added or charged twice.', 'error');
    } catch {
      setUnsure(true);
      toast.show('We couldn’t reach the pre-order service to check. Retry when it’s back: it can’t be charged twice.', 'error');
    } finally {
      setChecking(false);
    }
  };
  function added(o: PreorderOrder) {
    void qc.invalidateQueries({ queryKey: staffPreorderKeys.window(w.id) });
    void qc.invalidateQueries({ queryKey: staffPreorderKeys.lists() });
    toast.show(`Late order ${o.number} added and logged`);
    close(false);
    onAdded(o.number);
  }
  const onVerify = () => {
    const na = d.newAddress;
    if (!na || !na.line1.trim() || na.zip.trim().length < 3) {
      toast.show('Enter the street and postal code first', 'error');
      return;
    }
    verify.mutate();
  };
  return (
    <Dialog
      open={open}
      onOpenChange={close}
      width={520}
      title={`Late add · ${w.name}`}
      description="For customers who call or chat after the closing date. Every late add is logged with your name and the reason."
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className={labelClass}>
            Customer
            <input type="search" value={find} onChange={(e) => setFind(e.currentTarget.value)} placeholder="Find the customer by name, phone or email…" className={controlClass} />
          </label>
          <select
            aria-label="Matching customers"
            value={d.customerId}
            onChange={(e) => up({ customerId: e.currentTarget.value, addressId: '', newAddress: null, phoneConfirmed: false })}
            className={selectClass}
          >
            <option value="">{customers.data?.items.length === 0 ? 'No customer matches' : 'Choose the customer…'}</option>
            {(customers.data?.items ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {`${c.name} · ${c.phone} · ${c.email}`}
              </option>
            ))}
          </select>
        </div>
        {cust ? (
          <>
            <label className={labelClass}>
              Deliver to
              <select
                value={d.newAddress ? 'new' : d.addressId}
                onChange={(e) => {
                  const v = e.currentTarget.value;
                  up(v === 'new' ? { addressId: '', newAddress: { line1: '', zip: '', placeId: null } } : { addressId: v, newAddress: null });
                }}
                className={selectClass}
              >
                <option value="">Choose a saved address…</option>
                {cust.addresses.map((a) => (
                  <option key={a.id} value={a.id}>
                    {`${a.label} · ${a.line1}, ${a.city} ${a.zip}`}
                  </option>
                ))}
                <option value="new">+ New address (verify before saving)</option>
              </select>
            </label>
            {d.newAddress ? (
              <div className="grid grid-cols-[minmax(0,2fr)_minmax(0,1fr)_auto] items-end gap-2">
                <AddressAutocomplete
                  label="Street"
                  value={d.newAddress.line1}
                  onChange={(line1) => up({ newAddress: { line1, zip: d.newAddress?.zip ?? '', placeId: null } })}
                  onSelect={(sug) => up({ newAddress: { line1: sug.address.line1 || sug.label, zip: sug.address.zip || (d.newAddress?.zip ?? ''), placeId: null } })}
                  searchAddresses={staffAddressSearch}
                  placeholder="Start typing the street address"
                />
                <label className={labelClass}>
                  Postal code
                  <input
                    value={d.newAddress.zip}
                    onChange={(e) => up({ newAddress: { line1: d.newAddress?.line1 ?? '', zip: e.currentTarget.value, placeId: null } })}
                    className={`${controlClass} min-w-0`}
                  />
                </label>
                <button type="button" onClick={onVerify} disabled={verify.isPending} className="h-10 cursor-pointer rounded-md border border-border-strong bg-ink-0 px-3 font-semibold">
                  {d.newAddress.placeId ? 'Verified ✓' : 'Verify address'}
                </button>
                <span className="col-span-full text-xs text-muted">City: {`${cust.city}, ${cust.region}`} · checked against the address service before it can be saved</span>
              </div>
            ) : null}
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={d.phoneConfirmed} onChange={(e) => up({ phoneConfirmed: e.currentTarget.checked })} className="size-[18px] accent-green-600" />
              Phone read back and confirmed: <strong>{cust.phone}</strong>
            </label>
            <label className={labelClass}>
              Payment
              <select
                value={payment}
                onChange={(e) => up({ payment: e.currentTarget.value === 'PAYMENT_LINK' ? 'PAYMENT_LINK' : 'SAVED_CARD' })}
                className={selectClass}
              >
                {payOptions.map(([k, label]) => (
                  <option key={k} value={k}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </>
        ) : null}
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-sm font-semibold">Items</legend>
          <div className="flex flex-wrap gap-2">
            {(products.data ?? []).map((p) => {
              const on = d.items.includes(p.atin);
              return (
                <Chip key={p.atin} on={on} onClick={() => up({ items: on ? d.items.filter((x) => x !== p.atin) : [...d.items, p.atin] })}>
                  {`${p.name} · ${money2(p.price, w.currency)}`}
                </Chip>
              );
            })}
          </div>
        </fieldset>
        <label className={labelClass}>
          Reason
          <select
            value={d.reason}
            onChange={(e) => {
              const r = LateAddReasonSchema.safeParse(e.currentTarget.value);
              if (r.success) up({ reason: r.data });
            }}
            className={selectClass}
          >
            {LateAddReasonSchema.options.map((r) => (
              <option key={r} value={r}>
                {LATE_ADD_REASON_LABEL[r]}
              </option>
            ))}
          </select>
        </label>
        <label className={labelClass}>
          Delivery note
          <input value={d.note} onChange={(e) => up({ note: e.currentTarget.value })} maxLength={200} className={controlClass} />
        </label>
        {w.status !== 'OPEN' && w.status !== 'CLOSED' ? (
          <p role="alert" className="m-0 text-sm text-danger">
            Late adds are possible while a pre-order is open or closed and delivering.
          </p>
        ) : null}
        <div className="-mx-5 -mb-5 flex justify-end gap-2 border-t border-border px-5 py-4">
          <button type="button" onClick={() => close(false)} className="h-10 cursor-pointer border-0 bg-transparent px-4 font-semibold">
            Cancel
          </button>
          <button
            type="button"
            onClick={() => save.mutate()}
            disabled={problems.length > 0 || save.isPending || checking}
            data-problems={problems.join(' ')}
            className="h-10 cursor-pointer rounded-md border-0 bg-green-600 px-5 font-bold text-ink-0 disabled:cursor-not-allowed disabled:opacity-45"
          >
            {checking ? 'Checking…' : unsure ? `Retry · add ${money2(total, w.currency)} order` : `Add ${money2(total, w.currency)} order`}
          </button>
        </div>
      </div>
    </Dialog>
  );
}
