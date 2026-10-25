/* Storefront delivery runs (S262, S263, ADR 0005): the locations with delivery runs and their pages, a shopper's
   alert subscriptions per location and channel (consent recorded per channel; email by double opt-in link for guests,
   active at once when signed in) and the location-targeted advertorials with their view and click counts. The
   gateway-bff routes are documented in contracts/openapi/gateway-bff.yaml (tag Delivery runs). Staff routes live in
   staff-runs.ts. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

/* ── Schemas ───────────────────────────────────────────────────── */

/** A way to reach a subscriber; each is consented to separately (WhatsApp only from approved templates). */
export const RUN_CHANNELS = ['EMAIL', 'SMS', 'PUSH', 'WHATSAPP'] as const;
export const RunChannelSchema = z.enum(RUN_CHANNELS);
export type RunChannel = z.infer<typeof RunChannelSchema>;

const DropModeSchema = z.enum(['DOORSTEP', 'PICKUP_POINT']);

export const RunLocationSchema = z.object({
  id: z.string(),
  slug: z.string(),
  name: z.string(),
  market: z.string(),
  state: z.string().nullable().default(null),
  timeZone: z.string(),
  area: z.record(z.string(), z.unknown()).optional(),
  defaultFeeMinor: z.number().int(),
  currency: z.string(),
  dropMode: DropModeSchema,
  pickupPoint: z.record(z.string(), z.unknown()).nullable().default(null),
  /** The location's WhatsApp community invite. */
  whatsappInviteUrl: z.string().nullable().default(null),
  socialLinks: z.record(z.string(), z.string()).default({})
});
export type RunLocation = z.infer<typeof RunLocationSchema>;

export const RunLocationListSchema = z.object({ items: z.array(RunLocationSchema) });
export type RunLocationList = z.infer<typeof RunLocationListSchema>;

/** A delivery run as shoppers see it. */
export const PublicRunSchema = z.object({
  runId: z.string(),
  locationId: z.string(),
  locationSlug: z.string().optional(),
  town: z.string().optional(),
  status: z.string(),
  windowStart: z.string(),
  windowEnd: z.string(),
  /** Orders close at this time. */
  cutoffAt: z.string(),
  feeMinor: z.number().int(),
  currency: z.string(),
  dropMode: DropModeSchema.optional(),
  pickupPoint: z.record(z.string(), z.unknown()).nullable().default(null),
  acceptingOrders: z.boolean(),
  /** Orders left (null = not limited). */
  ordersLeft: z.number().int().nullable().default(null),
  /** No room left; checkout offers the next run. */
  full: z.boolean(),
  runUrl: z.string().optional()
});
export type PublicRun = z.infer<typeof PublicRunSchema>;

export const RunLocationPageSchema = z.object({
  location: RunLocationSchema,
  runs: z.array(PublicRunSchema),
  locationUrl: z.string(),
  /** Share URLs per channel, each with UTM attribution. */
  shareLinks: z.record(z.string(), z.string()).default({})
});
export type RunLocationPage = z.infer<typeof RunLocationPageSchema>;

/** Consent for one channel with its record. */
export const RunConsentSchema = z.object({
  channel: RunChannelSchema,
  granted: z.boolean(),
  consentedAt: z.string().nullable().default(null),
  source: z.string().nullable().default(null),
  withdrawnAt: z.string().nullable().default(null)
});
export type RunConsent = z.infer<typeof RunConsentSchema>;

export const RunSubscriptionSchema = z.object({
  id: z.string(),
  locationId: z.string(),
  status: z.string(),
  consents: z.array(RunConsentSchema),
  createdAt: z.string().nullable().default(null),
  confirmedAt: z.string().nullable().default(null)
});
export type RunSubscription = z.infer<typeof RunSubscriptionSchema>;

export const RunSubscriptionListSchema = z.object({ items: z.array(RunSubscriptionSchema) });
export type RunSubscriptionList = z.infer<typeof RunSubscriptionListSchema>;

export const RunSubscribeAcceptedSchema = z.object({ status: z.literal('CONFIRMATION_SENT') });

/** An advertorial served to the shopper's place, one variant. */
export const RunAdvertorialSchema = z.object({
  id: z.string(),
  source: z.enum(['WINDOW', 'RUN']),
  sourceId: z.string(),
  variant: z.string().optional(),
  /** Window code; null for runs. */
  code: z.string().nullable().default(null),
  place: z.string().nullable().default(null),
  headline: z.string(),
  body: z.string().default(''),
  ctaLabel: z.string(),
  /** With UTM and order attribution (src=page&cr=adv-...). */
  ctaUrl: z.string(),
  imageUrl: z.string().nullable().default(null),
  /** The live countdown counts down to this. */
  countdownTarget: z.string().nullable().default(null),
  expiresAt: z.string(),
  deliveryDisplay: z.string().nullable().default(null),
  feeDisplay: z.string().nullable().default(null)
});
export type RunAdvertorial = z.infer<typeof RunAdvertorialSchema>;
export const RunAdvertorialListSchema = z.object({ items: z.array(RunAdvertorialSchema) });
export type RunAdvertorialList = z.infer<typeof RunAdvertorialListSchema>;

export type RunAdvertorialEvent = 'VIEW' | 'CLICK';

/** A place for the advertorials: ZIP, coordinates or country and region (and city). None = the delivery location. */
export interface RunAdvertorialQuery {
  zip?: string;
  lat?: number;
  lng?: number;
  country?: string;
  region?: string;
  city?: string;
  /** An anonymous id kept per browser so A/B variants stay stable. */
  visitor?: string;
  limit?: number;
}

/* ── Client methods (spread into createApiClient) ─────────────── */

const enc = encodeURIComponent;

export function deliveryRunMethods(request: Requester) {
  return {
    listRunLocations: (signal?: AbortSignal): Promise<RunLocationList> => request('/v1/delivery-runs/locations', RunLocationListSchema, { signal }),
    getRunLocation: (slug: string, signal?: AbortSignal): Promise<RunLocationPage> => request(`/v1/delivery-runs/locations/${enc(slug)}`, RunLocationPageSchema, { signal }),
    /** Email sign-up for one location; always accepted, the confirmation link follows (double opt-in). */
    subscribeRunAlerts: (body: { locationId: string; email: string; source?: string }): Promise<z.infer<typeof RunSubscribeAcceptedSchema>> =>
      request('/v1/delivery-runs/subscriptions', RunSubscribeAcceptedSchema, { method: 'POST', body }),
    /** The confirmation link (`/delivery-runs/subscriptions/confirm?token=`). */
    confirmRunAlerts: (token: string): Promise<RunSubscription> => request('/v1/delivery-runs/subscriptions/confirm', RunSubscriptionSchema, { method: 'POST', body: { token } }),
    /** The unsubscribe link of every message; no channels withdraws all of them. */
    unsubscribeRunAlerts: (token: string, channels?: RunChannel[]): Promise<RunSubscription> =>
      request('/v1/delivery-runs/subscriptions/unsubscribe', RunSubscriptionSchema, { method: 'POST', body: channels?.length ? { token, channels } : { token } }),
    listMyRunSubscriptions: (signal?: AbortSignal): Promise<RunSubscriptionList> => request('/v1/account/run-subscriptions', RunSubscriptionListSchema, { signal }),
    /** Signed in: consent for the channels, active at once. */
    subscribeMyRunAlerts: (body: { locationId: string; channels: RunChannel[]; source?: string }): Promise<RunSubscription> =>
      request('/v1/account/run-subscriptions', RunSubscriptionSchema, { method: 'POST', body }),
    /** Signed in: withdraw the channels (all when none are given); the withdrawal is recorded on the consent. */
    unsubscribeMyRunAlerts: (locationId: string, channels?: RunChannel[]): Promise<RunSubscription> => {
      const q = new URLSearchParams();
      channels?.forEach((c) => q.append('channel', c));
      return request(`/v1/account/run-subscriptions/${enc(locationId)}`, RunSubscriptionSchema, { method: 'DELETE', query: q });
    },
    listRunAdvertorials: (query: RunAdvertorialQuery = {}, signal?: AbortSignal): Promise<RunAdvertorialList> =>
      request('/v1/delivery-runs/advertorials', RunAdvertorialListSchema, {
        query: {
          zip: query.zip,
          lat: query.lat === undefined ? undefined : String(query.lat),
          lng: query.lng === undefined ? undefined : String(query.lng),
          country: query.country,
          region: query.region,
          city: query.city,
          visitor: query.visitor,
          limit: query.limit === undefined ? undefined : String(query.limit)
        },
        signal
      }),
    /** Best effort: VIEW once per visit, CLICK on the button; callers ignore a failure. */
    recordRunAdvertorialEvent: (id: string, type: RunAdvertorialEvent): Promise<void> =>
      request(`/v1/delivery-runs/advertorials/${enc(id)}/events`, z.void(), { method: 'POST', body: { type } })
  };
}

/* ── Query keys and options ────────────────────────────────────── */

export const deliveryRunKeys = {
  all: ['delivery-runs'] as const,
  locations: () => [...deliveryRunKeys.all, 'locations'] as const,
  location: (slug: string) => [...deliveryRunKeys.all, 'location', slug] as const,
  mine: () => [...deliveryRunKeys.all, 'my-subscriptions'] as const,
  /** No place = the shopper's delivery location (invalidate after the deliver-to place changes). */
  advertorials: (query: RunAdvertorialQuery = {}) => [...deliveryRunKeys.all, 'advertorials', query] as const
};

interface DeliveryRunApi {
  listRunLocations: (signal?: AbortSignal) => Promise<RunLocationList>;
  getRunLocation: (slug: string, signal?: AbortSignal) => Promise<RunLocationPage>;
  listMyRunSubscriptions: (signal?: AbortSignal) => Promise<RunSubscriptionList>;
  listRunAdvertorials: (query?: RunAdvertorialQuery, signal?: AbortSignal) => Promise<RunAdvertorialList>;
}

const MINUTE = 60_000;

export const deliveryRunQueries = {
  locations: (api: Pick<DeliveryRunApi, 'listRunLocations'>) => queryOptions({ queryKey: deliveryRunKeys.locations(), queryFn: ({ signal }) => api.listRunLocations(signal), staleTime: MINUTE }),
  location: (api: Pick<DeliveryRunApi, 'getRunLocation'>, slug: string) =>
    queryOptions({ queryKey: deliveryRunKeys.location(slug), queryFn: ({ signal }) => api.getRunLocation(slug, signal), staleTime: 30_000, enabled: slug.length > 0 }),
  mine: (api: Pick<DeliveryRunApi, 'listMyRunSubscriptions'>) => queryOptions({ queryKey: deliveryRunKeys.mine(), queryFn: ({ signal }) => api.listMyRunSubscriptions(signal), staleTime: 15_000 }),
  advertorials: (api: Pick<DeliveryRunApi, 'listRunAdvertorials'>, query: RunAdvertorialQuery = {}) =>
    queryOptions({ queryKey: deliveryRunKeys.advertorials(query), queryFn: ({ signal }) => api.listRunAdvertorials(query, signal), staleTime: 15_000 })
};

/* ── Pure helpers (storefront and mobile share them) ──────────── */

/** The channels a subscription currently consents to. */
export const grantedChannels = (s: Pick<RunSubscription, 'consents'> | null | undefined): RunChannel[] => (s?.consents ?? []).filter((c) => c.granted).map((c) => c.channel);

/** Whether a channel is on for a location in the shopper's subscriptions. */
export const isRunChannelOn = (subs: readonly RunSubscription[], locationId: string, channel: RunChannel): boolean =>
  subs.some((s) => s.locationId === locationId && s.consents.some((c) => c.channel === channel && c.granted));
