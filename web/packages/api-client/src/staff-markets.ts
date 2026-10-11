/* Settings → Markets & locations (contracts/openapi/gateway-bff-staff.yaml, tag Markets): countries → states → cities,
   each LIVE / PLANNED / OFF, edited in place on content's draft with If-Match, then published. Plus the shared place
   pickers (`GET /v1/staff/places`, geo's gazetteer) that Markets and Pre-order areas both use. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const PlaceStatusSchema = z.enum(['LIVE', 'PLANNED', 'OFF']);
/** LIVE serves orders; PLANNED is on the roadmap (shown, not serving); OFF is switched off (hidden by default). */
export type PlaceStatus = z.infer<typeof PlaceStatusSchema>;

export const MarketCountryStatusSchema = z.enum(['LIVE', 'PAUSED', 'COMING_SOON', 'PLANNED']);
export type MarketCountryStatus = z.infer<typeof MarketCountryStatusSchema>;

export const MarketCitySchema = z.object({
  name: z.string(),
  status: PlaceStatusSchema,
  /** Open pre-orders selling here: removing the city is refused while any do. */
  preorders: z.array(z.string()).default([])
});
export type MarketCity = z.infer<typeof MarketCitySchema>;

export const MarketCapabilitiesSchema = z.object({
  marketplace: z.boolean().default(false),
  services: z.boolean().default(false),
  food: z.boolean().default(false),
  pickup: z.boolean().default(false),
  taxRegistration: z.boolean().default(false)
});
export type MarketCapabilities = z.infer<typeof MarketCapabilitiesSchema>;

export const MarketRegionSchema = z.object({
  code: z.string(),
  name: z.string(),
  kind: z.string().default('STATE'),
  status: PlaceStatusSchema,
  /** Every city is covered (listed cities are overrides); false = only the cities listed LIVE. */
  everyCity: z.boolean(),
  version: z.number().int(),
  cityCount: z.number().int().default(0),
  liveCities: z.number().int().default(0),
  preorders: z.number().int().default(0),
  capabilities: MarketCapabilitiesSchema.default({ marketplace: false, services: false, food: false, pickup: false, taxRegistration: false }),
  cities: z.array(MarketCitySchema).default([])
});
export type MarketRegion = z.infer<typeof MarketRegionSchema>;

export const MarketCountrySchema = z.object({
  code: z.string(),
  name: z.string(),
  status: MarketCountryStatusSchema,
  launchNote: z.string().nullable().default(null),
  currency: z.string().nullable().default(null),
  liveRegions: z.number().int().default(0),
  plannedRegions: z.number().int().default(0),
  version: z.number().int(),
  canEdit: z.boolean().default(false),
  regions: z.array(MarketRegionSchema)
});
export type MarketCountry = z.infer<typeof MarketCountrySchema>;

export const MarketCountrySummarySchema = z.object({
  code: z.string(),
  name: z.string(),
  status: MarketCountryStatusSchema,
  regionCount: z.number().int().default(0),
  liveRegions: z.number().int().default(0),
  plannedRegions: z.number().int().default(0),
  cityCount: z.number().int().default(0),
  liveCities: z.number().int().default(0),
  version: z.number().int().default(0)
});
export type MarketCountrySummary = z.infer<typeof MarketCountrySummarySchema>;

export const MarketChangesSchema = z.object({
  pending: z.boolean().default(false),
  changedCountries: z.array(z.string()).default([]),
  newlyLive: z.array(z.string()).default([]),
  disabledRegions: z.array(z.string()).default([]),
  enabledRegions: z.array(z.string()).default([])
});
export type MarketChanges = z.infer<typeof MarketChangesSchema>;

export const MarketCountriesSchema = z.object({
  items: z.array(MarketCountrySummarySchema),
  changes: MarketChangesSchema,
  canEdit: z.boolean()
});
export type MarketCountries = z.infer<typeof MarketCountriesSchema>;

export const MarketPublicationSchema = z.object({ version: z.number().int(), changes: MarketChangesSchema.optional() });
export type MarketPublication = z.infer<typeof MarketPublicationSchema>;

export const StaffPlaceSchema = z.object({ code: z.string(), name: z.string(), label: z.string().optional(), cityCount: z.number().int().optional() });
export type StaffPlace = z.infer<typeof StaffPlaceSchema>;
export const StaffPlaceListSchema = z.object({ items: z.array(StaffPlaceSchema), label: z.string(), total: z.number().int() });
export type StaffPlaceList = z.infer<typeof StaffPlaceListSchema>;

export interface StaffPlacesQuery {
  country?: string;
  region?: string;
  q?: string;
  limit?: number;
}

export interface MarketRegionChange {
  status?: PlaceStatus;
  everyCity?: boolean;
  marketplace?: boolean;
  services?: boolean;
  food?: boolean;
  pickup?: boolean;
  taxRegistration?: boolean;
}

/* ── Client ────────────────────────────────────────────── */

const enc = encodeURIComponent;
const M = '/v1/staff/markets';
const ifMatch = (version: number): Record<string, string> => ({ 'If-Match': `"${version}"` });
const regionPath = (country: string, region: string) => `${M}/countries/${enc(country)}/regions/${enc(region)}`;

/** Markets & locations routes of the staff BFF (mounted as `staffApi().markets`). */
export function createStaffMarketsApi(request: Requester) {
  return {
    countries: (signal?: AbortSignal): Promise<MarketCountries> => request(`${M}/countries`, MarketCountriesSchema, { signal }),
    country: (code: string, signal?: AbortSignal): Promise<MarketCountry> => request(`${M}/countries/${enc(code)}`, MarketCountrySchema, { signal }),
    /** 412 when the country changed since `version`. */
    updateCountry: (code: string, body: { status?: MarketCountryStatus; launchNote?: string }, version: number): Promise<MarketCountry> =>
      request(`${M}/countries/${enc(code)}`, MarketCountrySchema, { method: 'PATCH', body, headers: ifMatch(version) }),
    /** Adds a state / province picked from the gazetteer; it starts PLANNED (409 when already in use). */
    addRegion: (country: string, place: { code: string; name: string }): Promise<MarketCountry> =>
      request(`${M}/countries/${enc(country)}/regions`, MarketCountrySchema, { method: 'POST', body: place }),
    updateRegion: (country: string, region: string, body: MarketRegionChange, version: number): Promise<MarketCountry> =>
      request(regionPath(country, region), MarketCountrySchema, { method: 'PATCH', body, headers: ifMatch(version) }),
    /** Appends cities (listed ones keep their status; new ones default to PLANNED). */
    addCities: (country: string, region: string, cities: { name: string; status?: PlaceStatus }[], version: number): Promise<MarketCountry> =>
      request(`${regionPath(country, region)}/cities`, MarketCountrySchema, { method: 'POST', body: { cities }, headers: ifMatch(version) }),
    setCities: (country: string, region: string, names: string[], status: PlaceStatus, version: number): Promise<MarketCountry> =>
      request(`${regionPath(country, region)}/cities`, MarketCountrySchema, { method: 'PATCH', body: { names, status }, headers: ifMatch(version) }),
    /** 409 while an open pre-order sells there (switch it off instead). */
    removeCity: (country: string, region: string, city: string, version: number): Promise<MarketCountry> =>
      request(`${regionPath(country, region)}/cities/${enc(city)}`, MarketCountrySchema, { method: 'DELETE', headers: ifMatch(version) }),
    publish: (note?: string): Promise<MarketPublication> => request(`${M}/publications`, MarketPublicationSchema, { method: 'POST', body: note ? { note } : {} }),
    places: (q: StaffPlacesQuery, signal?: AbortSignal): Promise<StaffPlaceList> =>
      request('/v1/staff/places', StaffPlaceListSchema, {
        query: { country: q.country, region: q.region, q: q.q || undefined, limit: q.limit ? String(q.limit) : undefined },
        signal
      })
  };
}

export type StaffMarketsApi = ReturnType<typeof createStaffMarketsApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffMarketKeys = {
  all: ['staff', 'markets'] as const,
  countries: () => [...staffMarketKeys.all, 'countries'] as const,
  country: (code: string) => [...staffMarketKeys.all, 'country', code] as const
};

/** Gazetteer lookups are shared by every location picker (Markets, Pre-order areas). */
export const staffPlaceKeys = {
  all: ['staff', 'places'] as const,
  list: (q: StaffPlacesQuery) => [...staffPlaceKeys.all, q] as const
};

export const staffMarketQueries = {
  countries: (api: StaffMarketsApi) => queryOptions({ queryKey: staffMarketKeys.countries(), queryFn: ({ signal }) => api.countries(signal), staleTime: 10_000 }),
  country: (api: StaffMarketsApi, code: string) => queryOptions({ queryKey: staffMarketKeys.country(code), queryFn: ({ signal }) => api.country(code, signal), staleTime: 10_000 })
};

export const staffPlaceQueries = {
  list: (api: Pick<StaffMarketsApi, 'places'>, q: StaffPlacesQuery) =>
    queryOptions({ queryKey: staffPlaceKeys.list(q), queryFn: ({ signal }) => api.places(q, signal), placeholderData: keepPreviousData, staleTime: 5 * 60_000 })
};
