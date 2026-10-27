/* Mock gateway-BFF delivery-run endpoints for the storefront (S262, S263): locations and their pages, a shopper's alert
   subscriptions per location and channel (consent records), the confirm and unsubscribe links, and the advertorials for
   the shopper's place with their view and click counts. Mock tokens: anything of 8+ characters works; `invalid...` acts
   like a token delivery-runs does not know (422 on token), as the real BFF answers. */
import { http } from 'msw';
import { z } from 'zod';

import { RunChannelSchema, type RunSubscription } from '../delivery-runs';
import { getDb } from './db';
import { body, json, requireUser, route } from './http-kit';
import { placeOf } from './preorders';
import { MockProblem } from './service';

const LOCATIONS = [
  { id: '4b2f9d3a-0000-4000-8000-00000000000a', slug: 'lubbock-tx', name: 'Lubbock', zip: '79401', city: 'Lubbock' },
  { id: '4b2f9d3a-0000-4000-8000-00000000000b', slug: 'amarillo-tx', name: 'Amarillo', zip: '79101', city: 'Amarillo' },
  { id: '4b2f9d3a-0000-4000-8000-00000000000c', slug: 'midland-tx', name: 'Midland', zip: '79701', city: 'Midland' }
] as const;

const DAY = 86_400_000;
const ADVERTORIAL_ID = '5c3a0e4b-0000-4000-8000-0000000000a1';
const URL_BASE = 'https://afrotransact.example';

const locationOf = (l: (typeof LOCATIONS)[number]) => ({
  id: l.id,
  slug: l.slug,
  name: l.name,
  market: 'US',
  state: 'TX',
  timeZone: 'America/Chicago',
  area: { kind: 'ZIP_LIST', zips: [l.zip] },
  defaultFeeMinor: 799,
  currency: 'USD',
  dropMode: 'DOORSTEP',
  pickupPoint: null,
  whatsappInviteUrl: `https://chat.example/join/${l.slug}`,
  socialLinks: { instagram: 'https://instagram.example/afrotransact' }
});

interface RunsMock {
  subs: Map<string, RunSubscription>;
  /** Email sign-ups waiting for their confirmation link. */
  pending: { email: string; locationId: string }[];
  events: { id: string; type: string }[];
  /** Visits counted with their UTM tags. */
  visits: { slug: string; utmSource?: string; utmMedium?: string; utmCampaign?: string; runId?: string }[];
}
const STATE = new WeakMap<object, RunsMock>();
const state = (): RunsMock => {
  const db = getDb();
  let s = STATE.get(db);
  if (!s) {
    s = { subs: new Map(), pending: [], events: [], visits: [] };
    STATE.set(db, s);
  }
  return s;
};

const consent = (channel: z.infer<typeof RunChannelSchema>, granted: boolean, source: string, now: Date) => ({
  channel,
  granted,
  consentedAt: granted ? now.toISOString() : null,
  source,
  withdrawnAt: granted ? null : now.toISOString()
});

const key = (userId: string, locationId: string) => `${userId}:${locationId}`;
const locationById = (id: string) => LOCATIONS.find((l) => l.id === id);

const badToken = (token: string) => token.length < 8 || token.startsWith('invalid');
const invalidToken = () => new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'This link is not valid.', { errors: { token: 'This link is not valid or was already replaced' } });

const SubscribeBody = z.object({ locationId: z.string().uuid(), email: z.string().email(), source: z.string().max(80).optional(), utmSource: z.string().max(80).optional(), utmMedium: z.string().max(80).optional(), utmCampaign: z.string().max(120).optional() });
const VisitBody = z.object({ runId: z.string().uuid().optional(), utmSource: z.string().max(80).optional(), utmMedium: z.string().max(80).optional(), utmCampaign: z.string().max(120).optional() });
const MineBody = z.object({ locationId: z.string().uuid(), channels: z.array(RunChannelSchema).min(1), source: z.string().max(80).optional() });
const TokenBody = z.object({ token: z.string().min(1).max(200) });
const UnsubscribeBody = z.object({ token: z.string().min(1).max(200), channels: z.array(RunChannelSchema).optional() });
const EventBody = z.object({ type: z.enum(['VIEW', 'CLICK']) });

export const deliveryRunHandlers = [
  http.get('*/v1/delivery-runs/locations', route(() => json({ items: LOCATIONS.map(locationOf) }))),
  http.post('*/v1/delivery-runs/locations/:slug/visits', route(async (ctx, p) => {
    const b = await body(ctx.request, VisitBody);
    const l = LOCATIONS.find((x) => x.slug === p.slug);
    if (!l) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that location.');
    if (b.utmSource || b.utmMedium || b.utmCampaign) state().visits.push({ slug: l.slug, ...b });
    return new Response(null, { status: 204 });
  })),
  http.get('*/v1/delivery-runs/locations/:slug', route((ctx, p) => {
    const l = LOCATIONS.find((x) => x.slug === p.slug);
    if (!l) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that location.');
    const t = ctx.now.getTime();
    const cutoff = new Date(t + 3 * DAY).toISOString();
    const url = `${URL_BASE}/delivery-runs/${l.slug}`;
    return json({
      location: locationOf(l),
      locationUrl: url,
      shareLinks: {
        whatsapp: `https://wa.me/?text=${encodeURIComponent(`${url}?utm_source=whatsapp&utm_medium=share&utm_campaign=location-page`)}`,
        facebook: `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(`${url}?utm_source=facebook&utm_medium=share&utm_campaign=location-page`)}`,
        copy: `${url}?utm_source=copy&utm_medium=share&utm_campaign=location-page`
      },
      runs: [{
        runId: `7e5c2a6d-0000-4000-8000-${l.slug === 'lubbock-tx' ? '000000000004' : '000000000006'}`,
        locationId: l.id,
        locationSlug: l.slug,
        town: l.name,
        status: 'OPEN',
        windowStart: new Date(t + 6 * DAY).toISOString(),
        windowEnd: new Date(t + 6 * DAY + 4 * 3_600_000).toISOString(),
        cutoffAt: cutoff,
        feeMinor: 799,
        currency: 'USD',
        dropMode: 'DOORSTEP',
        pickupPoint: null,
        acceptingOrders: true,
        ordersLeft: 21,
        full: false,
        runUrl: `${url}?run=7e5c`
      }]
    });
  })),

  /* Guest email sign-up with double opt-in. */
  http.post('*/v1/delivery-runs/subscriptions', route(async (ctx) => {
    const req = await body(ctx.request, SubscribeBody);
    if (!locationById(req.locationId)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a delivery location.', { errors: { locationId: 'Choose a delivery location' } });
    state().pending.push({ email: req.email, locationId: req.locationId });
    return json({ status: 'CONFIRMATION_SENT' }, 202);
  })),
  http.post('*/v1/delivery-runs/subscriptions/confirm', route(async (ctx) => {
    const { token } = await body(ctx.request, TokenBody);
    if (badToken(token)) throw invalidToken();
    const locationId = state().pending[0]?.locationId ?? LOCATIONS[0].id;
    return json(subscription(locationId, [consent('EMAIL', true, 'location-page', ctx.now)], ctx.now));
  })),
  http.post('*/v1/delivery-runs/subscriptions/unsubscribe', route(async (ctx) => {
    const { token, channels } = await body(ctx.request, UnsubscribeBody);
    if (badToken(token)) throw invalidToken();
    return json(subscription(LOCATIONS[0].id, (channels?.length ? channels : (['EMAIL'] as const)).map((c) => consent(c, false, 'message-link', ctx.now)), ctx.now));
  })),

  /* Signed in: the account's Delivery alerts screen. */
  http.get('*/v1/account/run-subscriptions', route((ctx) => {
    const u = requireUser(ctx);
    return json({ items: [...state().subs.entries()].filter(([k]) => k.startsWith(`${u.id}:`)).map(([, v]) => v) });
  })),
  http.post('*/v1/account/run-subscriptions', route(async (ctx) => {
    const u = requireUser(ctx);
    const req = await body(ctx.request, MineBody);
    if (!locationById(req.locationId)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a delivery location.', { errors: { locationId: 'Choose a delivery location' } });
    const k = key(u.id, req.locationId);
    const current = state().subs.get(k);
    const consents = new Map((current?.consents ?? []).map((c) => [c.channel, c]));
    new Set(req.channels).forEach((c) => consents.set(c, consent(c, true, req.source ?? 'account', ctx.now)));
    const next = subscription(req.locationId, [...consents.values()], ctx.now, current);
    state().subs.set(k, next);
    return json(next);
  })),
  http.delete('*/v1/account/run-subscriptions/:locationId', route((ctx, p) => {
    const u = requireUser(ctx);
    const k = key(u.id, p.locationId ?? '');
    const current = state().subs.get(k);
    if (!current) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'You have no alerts for that location.');
    const q = new URL(ctx.request.url).searchParams.getAll('channel').map((c) => c.toUpperCase());
    const bad = q.find((c) => !RunChannelSchema.safeParse(c).success);
    if (bad) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose from EMAIL, SMS, PUSH and WHATSAPP.', { errors: { channels: 'Choose from EMAIL, SMS, PUSH and WHATSAPP' } });
    const all = !q.length;
    const consents = current.consents.map((c) => (c.granted && (all || q.includes(c.channel)) ? consent(c.channel, false, c.source ?? 'account', ctx.now) : c));
    const next = subscription(p.locationId ?? '', consents, ctx.now, current);
    state().subs.set(k, next);
    return json(next);
  })),

  /* Advertorials of the shopper's place: Lubbock has the open pre-order window and a delivery run. */
  http.get('*/v1/delivery-runs/advertorials', route((ctx) => {
    const q = new URL(ctx.request.url).searchParams;
    const place = q.get('zip') || q.get('city') || q.get('lat') ? { city: q.get('city') ?? '', zip: q.get('zip') ?? '' } : { city: placeOf(ctx.session).city, zip: '' };
    const lubbock = place.zip === '79401' || place.city.toLowerCase() === 'lubbock';
    if (!lubbock) return json({ items: [] });
    const closes = new Date(Math.ceil((ctx.now.getTime() + 2 * DAY) / DAY) * DAY + 5 * 3_600_000).toISOString();
    const variant = (q.get('visitor') ?? '').length % 2 === 0 ? 'A' : 'B';
    return json({
      items: [{
        id: ADVERTORIAL_ID,
        source: 'WINDOW',
        sourceId: 'lbk-7',
        variant,
        code: 'LBK-7',
        place: 'Lubbock, TX',
        headline: variant === 'A' ? 'Lubbock pre-order is open' : 'Last days: Lubbock jollof and spice box',
        body: 'African groceries, delivered to Lubbock. Order before the close and pay the delivery fee once.',
        ctaLabel: 'Order now',
        ctaUrl: `${URL_BASE}/pre-orders/lbk-7?utm_source=advertorial&utm_medium=page&utm_campaign=lbk-7&src=page&cr=adv-0123456789abcdef0123456789abcdef&po=LBK-7`,
        imageUrl: null,
        countdownTarget: closes,
        expiresAt: closes,
        deliveryDisplay: 'Sunday',
        feeDisplay: '$7.99',
        featuredProducts: [
          { atin: 'ATSHEA0001', title: 'Shea butter', imageUrl: null },
          { atin: 'ATGARI0001', title: 'Garri', imageUrl: null }
        ]
      }]
    });
  })),
  http.post('*/v1/delivery-runs/advertorials/:id/events', route(async (ctx, p) => {
    const { type } = await body(ctx.request, EventBody);
    if (p.id !== ADVERTORIAL_ID) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that.');
    state().events.push({ id: p.id ?? '', type });
    return new Response(null, { status: 204 });
  }))
];

function subscription(locationId: string, consents: RunSubscription['consents'], now: Date, prior?: RunSubscription): RunSubscription {
  const anyOn = consents.some((c) => c.granted);
  return {
    id: prior?.id ?? `6d4b1f5c-0000-4000-8000-${String(Math.abs([...locationId].reduce((a, c) => a + c.charCodeAt(0), 0))).padStart(12, '0')}`,
    locationId,
    status: anyOn ? 'ACTIVE' : 'UNSUBSCRIBED',
    consents: consents.sort((a, b) => a.channel.localeCompare(b.channel)),
    createdAt: prior?.createdAt ?? now.toISOString(),
    confirmedAt: prior?.confirmedAt ?? now.toISOString()
  };
}

/** Test hook: the visits the mock counted. */
export const mockRunVisits = (): readonly RunsMock['visits'][number][] => state().visits;

/** Test hook: what the mock counted. */
export const mockRunAdvertorialEvents = (): readonly { id: string; type: string }[] => state().events;
