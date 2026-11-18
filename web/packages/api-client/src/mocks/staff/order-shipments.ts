/* The order sheet of the mock staff BFF: packages with the statuses a caller may set on each, the facts grouped for
   support, and the "shipment-status" action. It mirrors go/services/gateway-bff/internal/app/staff_orders_sheet.go and
   the orders service's state machine (java/services/orders ... DefaultShipmentTransitionPolicy) for a staff caller. */
import { type DetailField, type DetailSection, type FieldValue, type RecordShipment, type ShipmentBlocked, type ShipmentChange, type ShipmentMode, type ShipmentStep } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';

type Line = Record<string, Scalar>;

const FORWARD = ['CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'] as const;
const EXCEPTIONS = ['DELIVERY_FAILED', 'RETURNED', 'CANCELLED'] as const;
const TERMINAL = ['DELIVERED', 'RETURNED', 'CANCELLED'];
const BLOCKED_TEXT: Record<ShipmentBlocked, string> = {
  PERMISSION: 'No permission',
  DELIVERY_PERMISSION: 'You need the delivery permission',
  SELLER: 'Only the seller does this step',
  SELLER_UPDATES: 'Updated by the seller: use Override status',
  PICKUP_ORDER: 'Not available for pickup orders',
  SEQUENCE: 'Earlier steps come first',
  STATE: 'Not available from here'
};
/** The steps of an AfroTransact-delivered shipment taken through the Delivery module (the BFF's deliverySteps). */
const DESK_STEPS = ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNED'];
/** The delivery steps the orders service allows from each status (DefaultShipmentTransitionPolicy.deliveryStaffSteps). */
const DESK_NEXT: Record<string, string[]> = {
  READY: ['PICKED_UP'],
  PICKED_UP: ['IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED'],
  IN_TRANSIT: ['OUT_FOR_DELIVERY', 'DELIVERY_FAILED'],
  OUT_FOR_DELIVERY: ['DELIVERED', 'DELIVERY_FAILED'],
  DELIVERY_FAILED: ['OUT_FOR_DELIVERY', 'RETURNED']
};
const RANK: Record<string, number> = { PLACED: 1, CONFIRMED: 2, PACKED: 3, READY: 4, PICKED_UP: 5, IN_TRANSIT: 6, OUT_FOR_DELIVERY: 7, DELIVERY_FAILED: 8, DELIVERED: 9, RETURNED: 9, CANCELLED: 9 };

/** What the signed-in staff member holds beyond the order permission: the delivery steps and the seller-shipment override. */
export interface ShipmentCaps {
  deliver: boolean;
  override: boolean;
}
const NO_CAPS: ShipmentCaps = { deliver: false, override: false };

/** Steps that need a reason (the BFF's reasonRequired): the proof photo replaces the reason when AfroTransact delivers. */
const needsNote = (to: string, mode: ShipmentMode): boolean =>
  to === 'CANCELLED' || to === 'DELIVERY_FAILED' || to === 'RETURNED' || (to === 'DELIVERED' && mode !== 'afrotransact_delivery' && mode !== 'fulfilled_by_afrotransact');

const s = (v: Scalar | undefined): string => (typeof v === 'string' ? v : '');
const humanize = (v: string): string => {
  const t = v.replace(/_/g, ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
};
const TONES: Record<string, RecordShipment['tone']> = {
  PLACED: 'brand', CONFIRMED: 'info', PACKED: 'info', READY: 'info', PICKED_UP: 'info', IN_TRANSIT: 'info', OUT_FOR_DELIVERY: 'info', DELIVERED: 'good',
  DELIVERY_FAILED: 'bad', RETURNED: 'warn', CANCELLED: 'bad'
};

/** Who moves the shipment (the BFF's FulfilmentModeOf): `localDelivery` marks a seller who opted into delivery by AfroTransact. */
export function modeOf(line: Line): ShipmentMode {
  const type = s(line.fulfilmentType);
  const method = s(line.deliveryMethod);
  if (type === 'PREORDER') return 'preorder_run';
  if (method === 'PICKUP') return 'pickup';
  if (method === 'SELLER_SHIPS' || method === 'CARRIER') return 'legacy_seller_shipped';
  if (type === 'FBA_AFROTRANSACT' || type === 'RETAIL_1P') return 'fulfilled_by_afrotransact';
  return method === 'AFROTRANSACT_DELIVERY' && line.localDelivery === true ? 'afrotransact_delivery' : 'legacy_seller_shipped';
}
export const staffDelivers = (line: Line): boolean => ['afrotransact_delivery', 'fulfilled_by_afrotransact'].includes(modeOf(line));
/** An old seller-shipped shipment (history, self-shipping is retired): seller-packed, moved only by the override. */
const sellerResponsible = (line: Line): boolean => modeOf(line) === 'legacy_seller_shipped' && s(line.fulfilmentType) === 'SELLER_FULFILLED';
const isDeskStep = (line: Line, to: string): boolean => staffDelivers(line) && DESK_STEPS.includes(to) && (to !== 'RETURNED' || s(line.status) !== 'DELIVERED');

/** The statuses a platform admin / fulfilment ops may move a shipment to, by the orders state machine. */
function sources(to: string, line: Line): string[] {
  const sellerFulfilled = s(line.fulfilmentType) === 'SELLER_FULFILLED';
  const pickup = s(line.deliveryMethod) === 'PICKUP';
  const onTheWay = ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED'];
  const oversight: Record<string, string[]> = {
    DELIVERED: pickup ? [...onTheWay, 'READY'] : onTheWay,
    DELIVERY_FAILED: ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'],
    RETURNED: pickup ? ['IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED', 'DELIVERED', 'READY'] : ['IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED', 'DELIVERED'],
    CANCELLED: ['PLACED', 'CONFIRMED', 'PACKED', 'READY'],
    ...(pickup ? { READY: ['CONFIRMED', 'PACKED'] } : {})
  };
  const everyStep: Record<string, string[]> = {
    ...oversight,
    CONFIRMED: ['PLACED'],
    PACKED: ['CONFIRMED'],
    READY: ['PACKED'],
    PICKED_UP: ['READY'],
    IN_TRANSIT: ['PICKED_UP', 'DELIVERY_FAILED'],
    OUT_FOR_DELIVERY: ['PICKED_UP', 'IN_TRANSIT', 'DELIVERY_FAILED']
  };
  return (sellerFulfilled ? oversight : everyStep)[to] ?? [];
}

/**
 * What the orders service lists as the shipment's `nextActions` for the staff caller. By mode: AfroTransact-delivered
 * shipments offer the delivery steps to holders of delivery:manage (everything else there is cancelling), seller-shipped
 * legacy seller-shipped history only cancelling, pickup shipments ready, collected and not collected.
 */
export function nextStatuses(line: Line, caps: ShipmentCaps = NO_CAPS): string[] {
  const status = s(line.status);
  let next: string[] = [...FORWARD, ...EXCEPTIONS].filter((to) => to !== status && sources(to, line).includes(status));
  if (staffDelivers(line)) {
    next = next.filter((to) => !isDeskStep(line, to));
    if (caps.deliver) next = [...next, ...(DESK_NEXT[status] ?? [])];
  } else if (sellerResponsible(line)) next = next.filter((to) => to === 'CANCELLED');
  return [...FORWARD, ...EXCEPTIONS].filter((to) => next.includes(to));
}

/** What an override may set on a Fulfilled by AfroTransact or old seller-shipped shipment: later steps only, a delivered one can still be returned. */
export function overrideStatuses(line: Line, caps: ShipmentCaps): string[] {
  if (!caps.override || !(sellerResponsible(line) || staffDelivers(line))) return [];
  const status = s(line.status);
  if (status === 'DELIVERED') return ['RETURNED'];
  if (TERMINAL.includes(status) || ['PENDING_PAYMENT'].includes(status)) return [];
  const beforePickup = ['CONFIRMED', 'PACKED', 'READY'].includes(status);
  return [...FORWARD, ...EXCEPTIONS].filter((to) => (RANK[to] ?? 0) > (RANK[status] ?? 0) && (to !== 'CANCELLED' || beforePickup) && (RANK[to] ?? 0) >= 2);
}

function transitionsOf(line: Line, may: boolean, caps: ShipmentCaps): ShipmentStep[] {
  const status = s(line.status);
  const next = nextStatuses(line, caps);
  if (TERMINAL.includes(status) && next.length === 0) return [];
  const mode = modeOf(line);
  const pos = (FORWARD as readonly string[]).indexOf(status);
  const firstOpen = FORWARD.findIndex((to) => next.includes(to));
  const sellerFulfilled = s(line.fulfilmentType) === 'SELLER_FULFILLED';
  const candidates: string[] = [...FORWARD.filter((to, i) => i > pos || next.includes(to)), ...EXCEPTIONS.filter((to) => to !== status)];
  if (status === 'DELIVERY_FAILED' && !candidates.includes('OUT_FOR_DELIVERY')) candidates.push('OUT_FOR_DELIVERY');
  const delivers = staffDelivers(line);
  return candidates.map((to): ShipmentStep => {
    const desk = isDeskStep(line, to);
    const blocked: ShipmentBlocked | null =
      sellerResponsible(line) && to !== 'CANCELLED'
        ? 'SELLER_UPDATES'
        : mode === 'pickup' && ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED'].includes(to)
          ? 'PICKUP_ORDER'
          : desk && !caps.deliver
            ? 'DELIVERY_PERMISSION'
            : !desk && !may
              ? 'PERMISSION'
              : next.includes(to)
                ? null
                : sellerFulfilled && ['CONFIRMED', 'PACKED', 'READY'].includes(to) && mode !== 'pickup'
                  ? 'SELLER'
                  : firstOpen >= 0 && (FORWARD as readonly string[]).indexOf(to) > firstOpen
                    ? 'SEQUENCE'
                    : 'STATE';
    return {
      to,
      allowed: blocked === null,
      reason: blocked ? BLOCKED_TEXT[blocked] : null,
      blocked,
      noteRequired: needsNote(to, mode),
      codeAccepted: to === 'DELIVERED' && line.handoverRequired === true && s(line.deliveryMethod) === 'PICKUP',
      driverStep: false,
      via: desk ? 'delivery' : 'orders',
      proofRequired: to === 'DELIVERED' && delivers
    };
  });
}

const historyOf = (line: Line): ShipmentChange[] => {
  try {
    const parsed: unknown = JSON.parse(s(line.history) || '[]');
    return Array.isArray(parsed) ? (parsed as ShipmentChange[]) : [];
  } catch {
    return [];
  }
};

/** The packages of an order for the sheet. `may`: the caller holds the permission and module level to change them. */
export function shipmentsOf(r: MockRecord, may: boolean, caps: ShipmentCaps = NO_CAPS): RecordShipment[] {
  return (r.lines ?? []).map((line): RecordShipment => {
    const status = s(line.status);
    const overrides = overrideStatuses(line, caps);
    return {
      id: s(line.id),
      label: s(line.label),
      sellerName: s(line.sellerName),
      status,
      tone: TONES[status] ?? null,
      fulfilmentLabel: s(line.fulfilmentType) === 'SELLER_FULFILLED' ? 'Seller' : 'AfroTransact',
      deliveryLabel: { CARRIER: 'Carrier', AFROTRANSACT_DELIVERY: 'AfroTransact Delivery', PICKUP: 'Pickup' }[s(line.deliveryMethod)] ?? humanize(s(line.deliveryMethod)),
      mode: modeOf(line),
      canOverride: overrides.length > 0,
      overrideSteps: overrides,
      itemCount: Number(line.itemCount ?? 0),
      itemTitles: s(line.itemTitles) ? s(line.itemTitles).split('|') : [],
      carrier: s(line.carrier) || null,
      trackingNumber: s(line.trackingNumber) || null,
      transitions: transitionsOf(line, may, caps),
      history: historyOf(line).slice().reverse()
    };
  });
}

/** Why no package can be moved now (null when at least one can). Delivery steps are taken with the sheet's menu, not this form. */
export const noStepReason = (r: MockRecord): string | null => {
  if (!(r.lines ?? []).length) return 'This order has no shipments.';
  return (r.lines ?? []).some((l) => nextStatuses(l).length > 0) ? null : 'No shipment steps are available for your role on this order.';
};

/** The select options of the action's form: "<shipment id>|<STATUS>". */
export const stepOptions = (r: MockRecord): { value: string; label: string }[] =>
  (r.lines ?? []).flatMap((l) => nextStatuses(l).map((to) => ({ value: `${s(l.id)}|${to}`, label: `${s(l.label)} · ${humanize(to)}` })));

/** The override action's options, for holders of orders:override-shipment. */
export const overrideOptions = (r: MockRecord, caps: ShipmentCaps): { value: string; label: string }[] =>
  (r.lines ?? []).flatMap((l) => overrideStatuses(l, caps).map((to) => ({ value: `${s(l.id)}|${to}`, label: `${s(l.label)} · ${humanize(to)}` })));

/** Brings the order's own status in line with its packages after one of them moved. */
export function rollUp(r: MockRecord): void {
  const all = r.lines ?? [];
  const open = all.find((l) => !TERMINAL.includes(s(l.status)));
  r.fields.shipmentStatus = s((open ?? all[all.length - 1])?.status);
  if (all.every((l) => s(l.status) === 'CANCELLED')) r.fields.status = 'CANCELLED';
  else if (all.every((l) => TERMINAL.includes(s(l.status)))) r.fields.status = 'COMPLETED';
  else r.fields.status = 'OPEN';
}

/** Records one status change on a shipment: its history (who, when, why) and the order's activity. */
export function recordStep(r: MockRecord, line: Line, to: string, actor: string, now: Date, note: string): void {
  const from = s(line.status);
  line.status = to;
  line.cancellable = false;
  line.history = JSON.stringify([...historyOf(line), { at: now.toISOString(), from, to, actor, note: note || null } satisfies ShipmentChange]);
  rollUp(r);
  r.activity.unshift({ at: now.toISOString(), actor, text: `${s(line.label)}: ${humanize(to).toLowerCase()}${note ? ` — ${note}` : ''}` });
}

/** The seller-shipped override: forward only, a reason is required, kept with who and when. */
export function applyShipmentOverride(
  r: MockRecord,
  values: Record<string, FieldValue>,
  actor: string,
  now: Date,
  caps: ShipmentCaps,
  fail: (errors: Record<string, string>) => never
): void {
  const [shipmentId = '', to = ''] = String(values.step ?? '').split('|');
  const line = (r.lines ?? []).find((l) => l.id === shipmentId);
  if (!line || !to) return fail({ step: 'Choose the new status' });
  if (!overrideStatuses(line, caps).includes(to)) return fail({ step: 'That status can not be set for this shipment' });
  const why = String(values.reason ?? '').trim();
  if (why.length < 3 || why.length > 500) return fail({ reason: 'Say why (3 to 500 characters)' });
  recordStep(r, line, to, actor, now, why);
}

/** Applies one status change like the orders service: checks the step, the note rule and the handover code. */
export function applyShipmentStep(
  r: MockRecord,
  values: Record<string, FieldValue>,
  actor: string,
  now: Date,
  fail: (errors: Record<string, string>) => never
): void {
  const [shipmentId = '', to = ''] = String(values.step ?? '').split('|');
  const line = (r.lines ?? []).find((l) => l.id === shipmentId);
  if (!line || !to) return fail({ step: 'Choose the next step' });
  if (isDeskStep(line, to)) return fail({ step: 'AfroTransact delivers this order: use the delivery steps in the order sheet or in Delivery' });
  if (!nextStatuses(line).includes(to)) return fail({ step: 'That step is not available for this shipment' });
  const note = String(values.reason ?? '').trim();
  const code = String(values.handoverCode ?? '').trim();
  if (code && !/^\d{4}$/.test(code)) return fail({ handoverCode: 'Enter the 4 digits' });
  if (code && code === '0000') return fail({ handoverCode: 'The handover code does not match; ask the customer for the 4-digit code' });
  if (needsNote(to, modeOf(line)) && !note && !(to === 'DELIVERED' && code)) return fail({ reason: `Say why (required to ${humanize(to).toLowerCase()})` });
  if (to === 'PICKED_UP' && !line.trackingNumber && s(line.deliveryMethod) === 'CARRIER') {
    line.carrier = 'UPS';
    line.trackingNumber = `1Z${String(9_100_000 + Math.floor(now.getTime() / 1000) % 10_000)}`;
  }
  recordStep(r, line, to, 'Platform admin', now, note);
  r.activity[0] = { ...(r.activity[0] as NonNullable<(typeof r.activity)[0]>), actor };
}

/** Seeds the packages of the i-th mock order; every fourth order has two (AfroTransact and a seller). */
export function seedShipments(i: number, status: string, amounts: { items: number; shipping: number; tax: number }, ago: (days: number, hours?: number) => string): Line[] {
  const total = amounts.items + amounts.shipping + amounts.tax;
  const history = (steps: string[], days: number, notes: Record<string, string> = {}): string =>
    JSON.stringify(
      steps.map((to, k) => ({ at: ago(days - k * 0.2), from: k === 0 ? null : (steps[k - 1] ?? null), to, actor: k === 0 ? 'System' : to === 'PACKED' || to === 'CONFIRMED' ? 'Seller' : 'Platform admin', note: notes[to] ?? null }))
    );
  const path = (to: string): string[] => {
    const upTo = (FORWARD as readonly string[]).indexOf(to);
    if (to === 'PLACED') return ['PLACED'];
    if (to === 'CANCELLED') return ['PLACED', 'CANCELLED'];
    if (to === 'DELIVERY_FAILED') return ['PLACED', 'CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'DELIVERY_FAILED'];
    return ['PLACED', ...FORWARD.slice(0, upTo + 1)];
  };
  const ship = (n: number, count: number, seller: string, fulfilment: string, method: string, st: string, titles: string[], units: number, share: number, extra: Line = {}): Line => ({
    id: `shp-${i}-${n}`,
    label: `Shipment ${n} of ${count} · ${seller}`,
    sellerName: seller,
    fulfilmentType: fulfilment,
    deliveryMethod: method,
    status: st,
    carrier: null,
    trackingNumber: ['PLACED', 'CONFIRMED', 'PACKED', 'READY', 'CANCELLED'].includes(st) || method !== 'SELLER_SHIPS' ? null : `1Z${String(9_000_000 + i * 13 + n)}`,
    cancellable: ['PLACED', 'CONFIRMED'].includes(st),
    totalMinor: Math.round(total * share),
    itemTitles: titles.join('|'),
    itemCount: units,
    handoverRequired: method === 'PICKUP',
    history: history(path(st), 3, st === 'CANCELLED' ? { CANCELLED: 'Customer asked to cancel' } : st === 'DELIVERY_FAILED' ? { DELIVERY_FAILED: 'Nobody home' } : {}),
    ...extra
  });
  if (i % 4 === 0) {
    // The first of them is the case of the owner's screenshot: one package AfroTransact packs, one the seller packs.
    const [first, second] = ([['READY', 'PACKED'], ['CONFIRMED', 'IN_TRANSIT'], ['DELIVERED', 'PICKED_UP'], ['PICKED_UP', 'CONFIRMED']] as const)[(i / 4) % 4] ?? ['READY', 'PACKED'];
    return [
      ship(1, 2, 'AfroTransact', 'FBA_AFROTRANSACT', 'AFROTRANSACT_DELIVERY', first, ['Jollof seasoning 200 g', 'Shea butter, raw 500 g', 'Black soap bar'], 4, 0.6),
      ship(2, 2, 'Patel Grocers', 'SELLER_FULFILLED', 'SELLER_SHIPS', second, ['Basmati rice 5 kg', 'Ghee 500 g'], 2, 0.4)
    ];
  }
  // The three ways an order is fulfilled, so every mode is on screen in the demo data: Fulfilled by AfroTransact (the
  // warehouse, or a seller's store AfroTransact collects from), seller pickup, and a seller who ships it themselves.
  const titles = ['Palm oil 1 L', 'Suya spice 100 g'];
  switch (i % 6) {
    case 1:
      return [ship(1, 1, 'AfroTransact', 'FBA_AFROTRANSACT', 'AFROTRANSACT_DELIVERY', status, titles, 3, 1)];
    case 3:
      return [ship(1, 1, 'Mama Put Pantry', 'SELLER_FULFILLED', 'PICKUP', ['PLACED', 'CONFIRMED', 'PACKED', 'READY', 'DELIVERED', 'CANCELLED'].includes(status) ? status : 'READY', titles, 3, 1)];
    case 5:
      return [ship(1, 1, 'Mama Put Pantry', 'SELLER_FULFILLED', 'AFROTRANSACT_DELIVERY', status, titles, 3, 1, { localDelivery: true })];
    default:
      return [ship(1, 1, 'Kente Naturals', 'SELLER_FULFILLED', 'SELLER_SHIPS', status, titles, 3, 1)];
  }
}

const fieldOf = (label: string, value: string, kind: DetailField['kind'] = 'text'): DetailField => ({ label, value, kind });

/** The order sheet's facts, grouped; empty facts are left out and the ids sit apart under "Technical details". */
export function orderSections(r: MockRecord): DetailSection[] {
  const f = r.fields;
  const lines = r.lines ?? [];
  const captured = lines.filter((l) => l.handedOver === true || ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'].includes(s(l.status))).length;
  const payment =
    s(f.status) === 'CANCELLED'
      ? 'Not charged'
      : captured === 0
        ? 'Authorised, captured when shipped'
        : captured === lines.length
          ? 'Captured'
          : `Captured for ${captured} of ${lines.length} shipments`;
  const groups: [string, DetailField[]][] = [
    ['Order', [fieldOf('Placed', s(f.placedAt), 'datetime')]],
    ['Customer', [fieldOf('Name', s(f.customer)), fieldOf('Ship to', s(f.shipTo)), fieldOf('Phone', s(f.customerPhone))]],
    [
      'Payment',
      [
        fieldOf('Status', payment),
        fieldOf('Items', String(f.itemsMinor), 'money'),
        fieldOf('Shipping', String(f.shippingMinor), 'money'),
        fieldOf('Tax', String(f.taxMinor), 'money'),
        fieldOf('Total', String(f.totalMinor), 'money')
      ]
    ],
    ['Origin', [fieldOf('Placed via', s(f.channel)), fieldOf('Source', s(f.attributionSource))]],
    ['Notes and tags', [fieldOf('Tags', s(f.tags))]],
    ['Technical details', [fieldOf('Order ID', `0b7c1c2e-${String(f.orderNumber).replace(/\D/g, '').padStart(4, '0')}-4d6a-9a53-5f1e7b9c2d10`, 'mono'), fieldOf('Customer ID', s(f.customerId), 'mono')]]
  ];
  return groups.map(([title, fields]) => ({ title, fields: fields.filter((x) => x.value.trim() !== '') })).filter((g) => g.fields.length > 0);
}

/** The order's own status, shipment column and seller column follow its packages (an order's status is worked out from them). */
export function syncOrder(r: MockRecord): void {
  const all = r.lines ?? [];
  if (all.length < 2) return;
  const open = all.find((l) => !TERMINAL.includes(s(l.status)));
  r.fields.shipmentStatus = s((open ?? all[all.length - 1])?.status);
  r.fields.status = all.every((l) => s(l.status) === 'CANCELLED') ? 'CANCELLED' : all.every((l) => TERMINAL.includes(s(l.status))) ? 'COMPLETED' : 'OPEN';
  r.fields.seller = [...new Set(all.map((l) => s(l.sellerName)))].join(', ');
}
