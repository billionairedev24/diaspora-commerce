/* Mock of the staff console Delivery module (gateway-bff-staff.yaml, tag Delivery; docs/engineering/delivery-fulfilment.md):
   AfroTransact-delivered shipments (Fulfilled by AfroTransact, and sellers who opted into delivery by AfroTransact), the job
   (who has it), the proof of delivery (photo, optional recipient and note) and the audit trail. Seller-shipped orders and
   pickups are never in it. The rules mirror orders-service: the same steps, a photo to deliver, a reason to fail. */
import { type DeliveryAuditLine, type DeliveryDestination, type DeliveryMode, type DeliveryProofView, type DeliveryRow, type DeliveryStatus } from '../../schemas/staff-delivery';

export interface MockDelivery {
  row: Omit<DeliveryRow, 'assigneeMe'> & { assigneeId: string | null };
  destination: DeliveryDestination;
  items: { title: string; quantity: number }[];
  assignedAt: string | null;
  assignedBy: string | null;
  lastFailure: string | null;
  jobVersion: number;
  proof: DeliveryProofView | null;
  audit: DeliveryAuditLine[];
}

export interface MockDeliveryState {
  deliveries: MockDelivery[];
  /** Photos "uploaded" through the mock media upload: asset id → data URL. */
  photos: Record<string, string>;
  couriers: { id: string; name: string }[];
}

/** The picture a mock proof photo shows: a small door scene, so the pages have something to render. */
export const MOCK_PROOF_PHOTO =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="480"><rect width="640" height="480" fill="#e9e4d8"/><rect x="230" y="110" width="180" height="300" fill="#7a5a3a"/><circle cx="385" cy="270" r="9" fill="#d8b441"/><rect x="40" y="410" width="560" height="14" fill="#b9b19c"/><rect x="270" y="360" width="100" height="50" fill="#c9a36a"/></svg>'
  );

const MODE: Record<string, DeliveryMode> = { fba: 'FULFILLED_BY_AFROTRANSACT', store: 'AFROTRANSACT_DELIVERY' };

export function seedDelivery(now: Date): MockDeliveryState {
  const at = (minutesAgo: number) => new Date(now.getTime() - minutesAgo * 60_000).toISOString();
  const day = (d: number) => new Date(now.getTime() + d * 86_400_000).toISOString().slice(0, 10);
  const make = (
    n: number,
    order: string,
    kind: 'fba' | 'store',
    status: DeliveryStatus,
    seller: string,
    origin: string,
    customer: string,
    city: string,
    state: string,
    zip: string,
    extra: Partial<MockDelivery['row']> = {}
  ): MockDelivery => ({
    row: {
      shipmentId: `shp-dl-${n}`, orderNumber: order, shipmentLabel: 'Shipment 1 of 1', mode: MODE[kind] as DeliveryMode, status, sellerName: seller, originCity: origin, customerName: customer,
      customerCity: city, customerState: state, customerPostalCode: zip, units: 1 + (n % 3), perishable: n % 4 === 0, promisedBy: day(1 + (n % 3)), assigneeName: null, assigneeId: null, attempts: 0, version: 0,
      proofAttached: false, updatedAt: at(20 + n * 7), deliveredAt: null, ...extra
    },
    destination: { name: customer, line1: `${100 + n * 17} Main St`, line2: null, city, state, postalCode: zip, phone: `+1713555${String(1000 + n)}`, instructions: n % 2 ? 'Leave with the front desk' : null, handToCustomer: n % 4 === 0 },
    items: [{ title: n % 2 ? 'Jollof spice mix' : 'Shea butter, raw', quantity: 1 + (n % 3) }],
    assignedAt: null,
    assignedBy: null,
    lastFailure: null,
    jobVersion: 0,
    proof: null,
    audit: []
  });
  const mine = (d: MockDelivery, name: string, id: string, version = 1): MockDelivery => {
    d.row.assigneeName = name;
    d.row.assigneeId = id;
    d.row.version = version;
    d.jobVersion = version;
    d.assignedAt = at(90);
    d.assignedBy = name;
    d.audit.push({ action: 'TAKE', fromStatus: d.row.status, toStatus: d.row.status, actorName: name, note: '', at: at(90) });
    return d;
  };
  const d1 = make(1, 'AT-2026-004901', 'fba', 'ready', 'AfroTransact Retail', 'Houston', 'Ada Obi', 'Houston', 'TX', '77002');
  const d2 = make(2, 'AT-2026-004905', 'store', 'ready', "Mama Tee's Pantry", 'Houston', 'Ngozi Eze', 'Sugar Land', 'TX', '77478');
  const d3 = mine(make(3, 'AT-2026-004908', 'store', 'picked_up', "Ade's Kitchen", 'Katy', 'Tunde Bello', 'Houston', 'TX', '77036'), 'Segun Lawal', 'stf-dispatch-1', 2);
  const d4 = mine(make(4, 'AT-2026-004911', 'fba', 'out_for_delivery', 'Accra Spice Co.', 'Houston', 'Amara Nwosu', 'Pearland', 'TX', '77584'), 'Chidi Nwosu', 'stf-ops-1', 3);
  const d5 = mine(make(5, 'AT-2026-004913', 'fba', 'delivery_failed', 'Bonwire Weavers', 'Houston', 'Kofi Mensah', 'Missouri City', 'TX', '77459', { attempts: 1 }), 'Chidi Nwosu', 'stf-ops-1', 5);
  d5.lastFailure = 'Nobody home, left a card';
  d5.audit.push({ action: 'FAILED', fromStatus: 'out_for_delivery', toStatus: 'delivery_failed', actorName: 'Chidi Nwosu', note: 'Nobody home, left a card', at: at(40) });
  const d6 = mine(make(6, 'AT-2026-004890', 'store', 'delivered', "Mama Tee's Pantry", 'Houston', 'Efua Asante', 'Houston', 'TX', '77004', { proofAttached: true, deliveredAt: at(300) }), 'Segun Lawal', 'stf-dispatch-1', 4);
  d6.proof = {
    photoAssetIds: ['11111111-1111-4111-8111-111111111111'], photoUrls: [MOCK_PROOF_PHOTO], recipientName: 'Efua Asante', note: 'Handed over at the door', latitude: null, longitude: null, deliveredAt: at(300),
    recordedByName: 'Segun Lawal', correctedAt: null, correctedByName: null, corrections: 0, version: 0
  };
  d6.audit.push({ action: 'DELIVERED', fromStatus: 'out_for_delivery', toStatus: 'delivered', actorName: 'Segun Lawal', note: 'Handed over at the door', at: at(300) });
  return {
    deliveries: [d1, d2, d3, d4, d5, d6],
    photos: { '11111111-1111-4111-8111-111111111111': MOCK_PROOF_PHOTO },
    couriers: [
      { id: 'stf-dispatch-1', name: 'Segun Lawal' },
      { id: 'stf-ops-1', name: 'Chidi Nwosu' },
      { id: 'stf-fulfil-1', name: 'Musa Ibrahim' }
    ]
  };
}

/* ── handlers ──────────────────────────────────────────── */

import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import { type DeliveryDetail, type DeliveryList, DeliveryStatusSchema, type DeliveryUploadTicket } from '../../schemas/staff-delivery';
import { getStaffDb, type MockStaffDb } from './db';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const ACTIVE = ['ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed'];
const TAKEABLE = new Set(ACTIVE);
const NEXT: Record<string, string[]> = {
  ready: ['picked_up'],
  picked_up: ['out_for_delivery', 'delivery_failed'],
  in_transit: ['out_for_delivery', 'delivery_failed'],
  out_for_delivery: ['delivered', 'delivery_failed'],
  delivery_failed: ['out_for_delivery']
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function deliveryHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const notFound = () => problem(404, 'NOT_FOUND', 'Not found', 'No such delivery.');
  const conflict = (detail: string) => problem(409, 'CONFLICT', 'Can’t do that now', detail);
  const read = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'delivery')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open delivery.');
    if (!ctx.permissions.includes('delivery:read') && !ctx.permissions.includes('delivery:manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the delivery:read permission.');
  };
  const canManage = (ctx: PreorderCtx) => ctx.permissions.includes('delivery:manage') && allows(ctx.roles, 'delivery', 'EDIT');
  const manage = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'delivery', 'EDIT')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t change delivery.');
    if (!ctx.permissions.includes('delivery:manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the delivery:manage permission.');
  };
  const state = (ctx: PreorderCtx) => (ctx.db as MockStaffDb).delivery;
  const find = (ctx: PreorderCtx, id: string | undefined) => state(ctx).deliveries.find((d) => d.row.shipmentId === id) ?? (() => { throw notFound(); })();
  const me = (ctx: PreorderCtx) => ctx.db.user.id;
  const rowOf = (ctx: PreorderCtx, d: MockDelivery): DeliveryRow => {
    const { assigneeId, ...rest } = d.row;
    return { ...rest, assigneeMe: assigneeId !== null && assigneeId === me(ctx) };
  };
  const detail = (ctx: PreorderCtx, d: MockDelivery): DeliveryDetail => {
    const manager = canManage(ctx);
    const dest = manager ? d.destination : { ...d.destination, line1: null, line2: null, postalCode: null, phone: null, instructions: null };
    return {
      row: rowOf(ctx, d),
      destination: dest,
      items: d.items,
      job: { assigneeName: d.row.assigneeName, assignedAt: d.assignedAt, assignedBy: d.assignedBy, attempts: d.row.attempts, lastFailure: d.lastFailure, version: d.jobVersion },
      proof: d.proof ? { ...d.proof, photoUrls: d.proof.photoAssetIds.map((id) => state(ctx).photos[id] ?? ''), latitude: manager ? d.proof.latitude : null, longitude: manager ? d.proof.longitude : null } : null,
      audit: d.audit,
      nextStatuses: manager ? (NEXT[d.row.status] ?? []) : [],
      canManage: manager
    };
  };
  const stamp = (ctx: PreorderCtx, d: MockDelivery, action: MockDelivery['audit'][number]['action'], to: string, note = '') => {
    const from = d.row.status;
    d.audit.push({ action, fromStatus: from, toStatus: to, actorName: ctx.action.actor, note, at: ctx.now.toISOString() });
    d.row.status = to as DeliveryRow['status'];
    d.row.updatedAt = ctx.now.toISOString();
    d.jobVersion += 1;
    d.row.version = d.jobVersion;
  };
  const check = (d: MockDelivery, version: number | null | undefined) => {
    if (version !== undefined && version !== null && version !== d.jobVersion) throw conflict('Someone else changed this delivery. Reload and try again.');
  };
  const ActionBody = z.object({ version: z.number().int().nullish(), note: z.string().max(500).optional() }).default({});
  const step = (to: DeliveryRow['status'], action: MockDelivery['audit'][number]['action']) =>
    route(async (ctx, p) => {
      const b = await json(ctx.request, ActionBody);
      manage(ctx);
      const d = find(ctx, p.id);
      check(d, b.version);
      if (d.row.status === to) return detail(ctx, d);
      if (!d.row.assigneeName) throw conflict('Take this delivery, or assign it to someone, first');
      if (!(NEXT[d.row.status] ?? []).includes(to)) throw conflict(`This delivery can’t move from ${d.row.status.replace(/_/g, ' ')} to ${to.replace(/_/g, ' ')}.`);
      stamp(ctx, d, d.row.status === 'delivery_failed' && to === 'out_for_delivery' ? 'REATTEMPT' : action, to, b.note ?? '');
      return detail(ctx, d);
    });
  const assignTo = (ctx: PreorderCtx, d: MockDelivery, id: string, name: string, self: boolean) => {
    if (!TAKEABLE.has(d.row.status)) throw conflict('This delivery can be taken once the seller or the warehouse marks it ready');
    if (d.row.assigneeId === id) return;
    if (self && d.row.assigneeName) throw conflict(`${d.row.assigneeName} has this delivery. Assign it to yourself to take it over`);
    const had = d.row.assigneeName !== null;
    d.row.assigneeId = id;
    d.row.assigneeName = name;
    d.assignedAt = ctx.now.toISOString();
    d.assignedBy = ctx.action.actor;
    d.audit.push({ action: self ? 'TAKE' : had ? 'REASSIGN' : 'ASSIGN', fromStatus: d.row.status, toStatus: d.row.status, actorName: ctx.action.actor, note: '', at: ctx.now.toISOString() });
    d.jobVersion += 1;
    d.row.version = d.jobVersion;
  };
  const photoProblem = (ctx: PreorderCtx, ids: string[]): string | null => {
    for (const id of ids) {
      if (!UUID.test(id)) return 'Attach a photo taken for this delivery';
      if (!state(ctx).photos[id]) return 'That photo was not found. Take it again.';
    }
    return null;
  };
  const ProofBody = z.object({
    photoAssetIds: z.array(z.string()).default([]),
    recipientName: z.string().trim().max(120, 'Up to 120 characters').optional(),
    note: z.string().trim().max(500, 'Up to 500 characters').optional(),
    latitude: z.number().min(-90).max(90).nullish(),
    longitude: z.number().min(-180).max(180).nullish(),
    reason: z.string().trim().optional(),
    version: z.number().int().nullish()
  });
  const proofChecks = (ctx: PreorderCtx, b: z.infer<typeof ProofBody>) => {
    const ids = [...new Set(b.photoAssetIds.map((i) => i.trim().toLowerCase()))];
    const errors: Record<string, string> = {};
    if (ids.length === 0) errors.photoAssetIds = 'Attach at least one photo as proof of delivery';
    else if (ids.length > 5) errors.photoAssetIds = 'Attach at most 5 photos';
    else {
      const bad = photoProblem(ctx, ids);
      if (bad) errors.photoAssetIds = bad;
    }
    if ((b.latitude === null || b.latitude === undefined) !== (b.longitude === null || b.longitude === undefined)) errors.latitude = 'Give both latitude and longitude, or neither';
    if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
    return ids;
  };

  return [
    http.get(
      `${W}/deliveries`,
      route((ctx): DeliveryList => {
        read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const wanted = (q.get('status') ?? '').split(',').map((s) => s.trim()).filter(Boolean);
        for (const s of wanted) if (!DeliveryStatusSchema.safeParse(s).success) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { status: `Unknown status ${s}` });
        const statuses = wanted.length ? wanted : ACTIVE;
        const text = (q.get('q') ?? '').trim().toLowerCase();
        const assignee = q.get('assignee');
        const city = (q.get('city') ?? '').trim().toLowerCase();
        const seller = (q.get('seller') ?? '').trim().toLowerCase();
        const mode = q.get('mode');
        let rows = state(ctx).deliveries.filter((d) => statuses.includes(d.row.status));
        if (mode) rows = rows.filter((d) => d.row.mode === mode);
        if (text) rows = rows.filter((d) => [d.row.orderNumber, d.row.sellerName, d.row.customerName, d.row.customerCity, d.row.assigneeName ?? ''].some((v) => v.toLowerCase().includes(text)));
        if (assignee === 'unassigned') rows = rows.filter((d) => d.row.assigneeId === null);
        else if (assignee === 'me') rows = rows.filter((d) => d.row.assigneeId === me(ctx));
        else if (assignee) rows = rows.filter((d) => d.row.assigneeId === assignee);
        if (city) rows = rows.filter((d) => d.row.customerCity.toLowerCase().includes(city));
        if (seller) rows = rows.filter((d) => d.row.sellerName.toLowerCase().includes(seller));
        const sortKey = q.get('sort') ?? 'promised';
        const dir = q.get('desc') === 'true' ? -1 : 1;
        const key = (d: MockDelivery): string =>
          sortKey === 'order' ? d.row.orderNumber : sortKey === 'seller' ? d.row.sellerName : sortKey === 'city' ? d.row.customerCity : sortKey === 'status' ? d.row.status : sortKey === 'updated' ? (d.row.updatedAt ?? '') : (d.row.promisedBy ?? '9999');
        rows = [...rows].sort((a, b) => dir * key(a).localeCompare(key(b)) || a.row.orderNumber.localeCompare(b.row.orderNumber));
        const size = Math.min(Math.max(Number(q.get('size') ?? 25) || 25, 1), 100);
        const page = Math.max(Number(q.get('page') ?? 0) || 0, 0);
        const counts: Record<string, number> = {};
        state(ctx).deliveries.forEach((d) => {
          counts[d.row.status] = (counts[d.row.status] ?? 0) + 1;
        });
        return { items: rows.slice(page * size, page * size + size).map((d) => rowOf(ctx, d)), page, size, total: rows.length, counts, canManage: canManage(ctx) };
      })
    ),
    http.get(
      `${W}/deliveries/couriers`,
      route((ctx) => {
        read(ctx);
        const people = state(ctx).couriers.some((c) => c.id === me(ctx)) ? state(ctx).couriers : [{ id: me(ctx), name: ctx.action.actor }, ...state(ctx).couriers];
        return people.map((c) => ({ id: c.id, name: c.name, me: c.id === me(ctx) }));
      })
    ),
    http.post(
      `${W}/deliveries/uploads`,
      route(async (ctx): Promise<DeliveryUploadTicket> => {
        const b = await json(ctx.request, z.object({ fileName: z.string().max(200), contentType: z.string(), size: z.number().int() }));
        manage(ctx);
        const errors: Record<string, string> = {};
        if (!/^image\/(jpeg|png|webp)$/.test(b.contentType)) errors.contentType = 'Use a JPEG, PNG or WebP photo';
        if (b.size < 1 || b.size > 10 * 1024 * 1024) errors.size = 'Photos up to 10 MB';
        if (/[/\\]/.test(b.fileName) || !b.fileName.trim()) errors.fileName = 'Choose a photo';
        if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        const assetId = crypto.randomUUID();
        return { assetId, upload: { method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-delivery-uploads/${assetId}`, headers: { 'Content-Type': b.contentType } } };
      }, 201)
    ),
    http.put('*/mock-delivery-uploads/:id', async ({ request, params }) => {
      const id = typeof params.id === 'string' ? params.id : '';
      if (!UUID.test(id)) return new HttpResponse(null, { status: 404 });
      const bytes = new Uint8Array(await request.arrayBuffer());
      let bin = '';
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      getStaffDb().delivery.photos[id] = `data:${request.headers.get('content-type') ?? 'image/jpeg'};base64,${btoa(bin)}`;
      return new HttpResponse(null, { status: 200 });
    }),
    http.get(
      `${W}/deliveries/:id`,
      route((ctx, p) => {
        read(ctx);
        return detail(ctx, find(ctx, p.id));
      })
    ),
    http.post(
      `${W}/deliveries/:id/take`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, ActionBody);
        manage(ctx);
        const d = find(ctx, p.id);
        check(d, b.version);
        assignTo(ctx, d, me(ctx), ctx.action.actor, true);
        return detail(ctx, d);
      })
    ),
    http.post(
      `${W}/deliveries/:id/assign`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ assigneeId: z.string().trim().min(1, 'Choose who delivers this').max(120), version: z.number().int().nullish(), note: z.string().max(500).optional() }));
        manage(ctx);
        const d = find(ctx, p.id);
        check(d, b.version);
        const person = [{ id: me(ctx), name: ctx.action.actor }, ...state(ctx).couriers].find((c) => c.id === b.assigneeId);
        if (!person) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'That person cannot be assigned deliveries (they need the delivery:manage permission).', { assigneeId: 'Choose someone who can deliver' });
        assignTo(ctx, d, person.id, person.name, false);
        return detail(ctx, d);
      })
    ),
    http.post(`${W}/deliveries/:id/picked-up`, step('picked_up', 'PICKED_UP')),
    http.post(`${W}/deliveries/:id/out-for-delivery`, step('out_for_delivery', 'OUT_FOR_DELIVERY')),
    http.post(
      `${W}/deliveries/:id/delivered`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, ProofBody);
        manage(ctx);
        const ids = proofChecks(ctx, b);
        const d = find(ctx, p.id);
        check(d, b.version);
        if (d.row.status === 'delivered') return detail(ctx, d);
        if (!d.row.assigneeName) throw conflict('Take this delivery, or assign it to someone, first');
        if (d.row.status !== 'out_for_delivery') throw conflict('Mark it out for delivery first.');
        stamp(ctx, d, 'DELIVERED', 'delivered', b.note ?? '');
        d.row.deliveredAt = ctx.now.toISOString();
        d.row.proofAttached = true;
        d.proof = {
          photoAssetIds: ids, photoUrls: ids.map((id) => state(ctx).photos[id] ?? ''), recipientName: b.recipientName || null, note: b.note || null, latitude: b.latitude ?? null, longitude: b.longitude ?? null,
          deliveredAt: ctx.now.toISOString(), recordedByName: ctx.action.actor, correctedAt: null, correctedByName: null, corrections: 0, version: 0
        };
        return detail(ctx, d);
      })
    ),
    http.post(
      `${W}/deliveries/:id/failed`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ reason: z.string().trim().min(3, 'Say why it failed (3 to 500 characters)').max(500, 'Say why it failed (3 to 500 characters)'), version: z.number().int().nullish() }));
        manage(ctx);
        const d = find(ctx, p.id);
        check(d, b.version);
        if (d.row.status === 'delivery_failed') return detail(ctx, d);
        if (!d.row.assigneeName) throw conflict('Take this delivery, or assign it to someone, first');
        if (!(NEXT[d.row.status] ?? []).includes('delivery_failed')) throw conflict('Only a delivery that was picked up or is out for delivery can fail.');
        stamp(ctx, d, 'FAILED', 'delivery_failed', b.reason);
        d.row.attempts += 1;
        d.lastFailure = b.reason;
        return detail(ctx, d);
      })
    ),
    http.put(
      `${W}/deliveries/:id/proof`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, ProofBody);
        manage(ctx);
        if (!b.reason || b.reason.length < 3) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { reason: 'Say why you correct the proof (3 to 500 characters)' });
        const ids = proofChecks(ctx, b);
        const d = find(ctx, p.id);
        if (!d.proof) throw conflict('This delivery has no saved proof to correct');
        if (b.version !== undefined && b.version !== null && b.version !== d.proof.version) throw conflict('Someone changed this proof; reload and try again');
        d.proof = {
          ...d.proof, photoAssetIds: ids, photoUrls: ids.map((id) => state(ctx).photos[id] ?? ''), recipientName: b.recipientName || null, note: b.note || null, latitude: b.latitude ?? null, longitude: b.longitude ?? null,
          correctedAt: ctx.now.toISOString(), correctedByName: ctx.action.actor, corrections: d.proof.corrections + 1, version: d.proof.version + 1
        };
        d.audit.push({ action: 'PROOF_CORRECTED', fromStatus: d.row.status, toStatus: d.row.status, actorName: ctx.action.actor, note: b.reason, at: ctx.now.toISOString() });
        return detail(ctx, d);
      })
    )
  ];
}
