'use client';

import { type Carrier, type RateQuote, sellerKeys, type SellerOrderDetail, type ShipFromForm, ShipFromFormSchema, toSellerAddress } from '@afrotransact/api-client';
import { Button, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { idempotencyKey, money } from '@/lib/format';
import { useSeller } from '@/lib/session';

const SHIP_FROM_KEY = 'at.seller.shipFrom';
const EMPTY: ShipFromForm = { name: '', phone: '', line1: '', line2: '', city: '', state: '', postalCode: '' };

function loadShipFrom(): ShipFromForm {
  try {
    const raw = window.localStorage.getItem(SHIP_FROM_KEY);
    const parsed = raw ? ShipFromFormSchema.safeParse(JSON.parse(raw)) : null;
    return parsed?.success ? parsed.data : EMPTY;
  } catch {
    return EMPTY;
  }
}

const CARRIER_NAMES: Record<Carrier, string> = { usps: 'USPS', ups: 'UPS', fedex: 'FedEx', dhl: 'DHL' };

/** Buy a shipping label: ship-from address, parcel, carrier rates (carrier-gateway), then buy with an Idempotency-Key. */
export function LabelForm({ order, shipmentId }: { order: SellerOrderDetail; shipmentId: string }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [from, setFrom] = useState<ShipFromForm>(() => (typeof window === 'undefined' ? EMPTY : loadShipFrom()));
  const [weight, setWeight] = useState('');
  const [dims, setDims] = useState({ l: '', w: '', h: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [quotes, setQuotes] = useState<RateQuote[] | null>(null);
  const [choice, setChoice] = useState<string>('');
  const key = useRef(idempotencyKey());

  const parcel = () => ({
    weightLb: Number(weight),
    ...(dims.l ? { lengthIn: Number(dims.l) } : {}),
    ...(dims.w ? { widthIn: Number(dims.w) } : {}),
    ...(dims.h ? { heightIn: Number(dims.h) } : {})
  });

  const validate = (): ShipFromForm | null => {
    const errs: Record<string, string> = {};
    const f = ShipFromFormSchema.safeParse(from);
    if (!f.success) f.error.issues.forEach((i) => (errs[String(i.path[0])] = i.message));
    const w = Number(weight);
    if (!(w > 0 && w <= 150)) errs.weight = 'Enter a weight between 0 and 150 lb';
    setErrors(errs);
    return f.success && !errs.weight ? f.data : null;
  };

  const rates = useMutation({
    mutationFn: (f: ShipFromForm) => api.quoteRates(order.orderNumber, shipmentId, parcel(), toSellerAddress(f)),
    onSuccess: (r) => {
      setQuotes(r.quotes);
      setChoice(r.quotes[0] ? `${r.quotes[0].carrier}:${r.quotes[0].service}` : '');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const buy = useMutation({
    mutationFn: (f: ShipFromForm) => {
      const [carrier, service] = choice.split(':');
      return api.buyLabel(order.orderNumber, shipmentId, { carrier: carrier ?? '', service: service ?? '', parcel: parcel(), shipFrom: toSellerAddress(f) }, key.current);
    },
    onSuccess: (res) => {
      qc.setQueryData(sellerKeys.order(orgId, order.orderNumber), res.order);
      void qc.invalidateQueries({ queryKey: sellerKeys.orders(orgId) });
      key.current = idempotencyKey();
      toast.show(`Label bought · ${CARRIER_NAMES[res.carrier]} ${res.trackingNumber}`);
      if (res.documentUrl) window.open(res.documentUrl, '_blank', 'noopener');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const getRates = () => {
    const f = validate();
    if (!f) return;
    try {
      window.localStorage.setItem(SHIP_FROM_KEY, JSON.stringify(f));
    } catch {
      /* storage blocked */
    }
    rates.mutate(f);
  };
  const field = (k: keyof ShipFromForm, label: string, extra: { autoComplete?: string; inputMode?: 'numeric' } = {}) => (
    <TextField label={label} value={from[k]} error={errors[k]} onChange={(e) => setFrom({ ...from, [k]: e.target.value })} {...extra} />
  );

  return (
    <div className="flex flex-col gap-3 rounded-md border border-border p-4">
      <strong>Buy a shipping label</strong>
      <fieldset className="m-0 grid grid-cols-2 gap-x-3 gap-y-1 border-0 p-0">
        <legend className="mb-1 text-sm font-semibold">Ship from</legend>
        <div className="col-span-2">{field('name', 'Sender name', { autoComplete: 'organization' })}</div>
        <div className="col-span-2">{field('line1', 'Street address', { autoComplete: 'address-line1' })}</div>
        <div className="col-span-2">{field('line2', 'Apt, suite (optional)', { autoComplete: 'address-line2' })}</div>
        {field('city', 'City', { autoComplete: 'address-level2' })}
        {field('state', 'State', { autoComplete: 'address-level1' })}
        {field('postalCode', 'ZIP code', { autoComplete: 'postal-code', inputMode: 'numeric' })}
        {field('phone', 'Phone (optional)', { autoComplete: 'tel' })}
      </fieldset>
      <fieldset className="m-0 grid grid-cols-4 gap-x-3 border-0 p-0">
        <legend className="mb-1 text-sm font-semibold">Package</legend>
        <TextField label="Weight (lb)" inputMode="decimal" value={weight} error={errors.weight} onChange={(e) => setWeight(e.target.value)} />
        <TextField label="Length (in)" inputMode="decimal" value={dims.l} onChange={(e) => setDims({ ...dims, l: e.target.value })} />
        <TextField label="Width (in)" inputMode="decimal" value={dims.w} onChange={(e) => setDims({ ...dims, w: e.target.value })} />
        <TextField label="Height (in)" inputMode="decimal" value={dims.h} onChange={(e) => setDims({ ...dims, h: e.target.value })} />
      </fieldset>
      <div>
        <Button variant="neutral" size="sm" loading={rates.isPending} onClick={getRates}>
          Get rates
        </Button>
      </div>
      {quotes ? (
        quotes.length ? (
          <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
            <legend className="mb-1 text-sm font-semibold">Choose a rate (cheapest first)</legend>
            {quotes.map((q) => {
              const id = `${q.carrier}:${q.service}`;
              return (
                <label key={id} className="flex cursor-pointer items-center gap-3 rounded-sm border border-border px-3 py-2 text-sm has-[:checked]:border-gold-500 has-[:checked]:bg-gold-50">
                  <input type="radio" name={`rate-${shipmentId}`} value={id} checked={choice === id} onChange={() => setChoice(id)} />
                  <span className="flex-1">
                    <strong>{q.serviceName}</strong>
                    <span className="block text-xs text-muted">
                      {CARRIER_NAMES[q.carrier]}
                      {q.transitDays ? ` · ${q.transitDays} business days` : ''}
                    </span>
                  </span>
                  <strong>{money(q.amountMinor, q.currency)}</strong>
                </label>
              );
            })}
            <div className="pt-2">
              <Button
                variant="secondary"
                loading={buy.isPending}
                disabled={!choice}
                onClick={() => {
                  const f = validate();
                  if (f) buy.mutate(f);
                }}
              >
                Buy label
              </Button>
            </div>
          </fieldset>
        ) : (
          <p className="m-0 text-sm text-muted">No carrier can ship this package. Check the weight and address.</p>
        )
      ) : null}
    </div>
  );
}
