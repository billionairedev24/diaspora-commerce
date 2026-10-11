/* Mock of Settings → Markets & locations (/v1/staff/markets/**, content's draft markets through the BFF) and the shared
   place pickers (/v1/staff/places, geo's gazetteer). Countries, states and cities with LIVE / PLANNED / OFF, versions
   for If-Match (412 VERSION_CONFLICT) and the open pre-orders that depend on each city (from the pre-order mock). */
import { http } from 'msw';
import { z } from 'zod';

import {
  type MarketChanges,
  type MarketCity,
  type MarketCountries,
  type MarketCountry,
  type MarketCountryStatus,
  type MarketRegion,
  type PlaceStatus,
  PlaceStatusSchema,
  type StaffPlaceList
} from '../../staff-markets';
import { keyWithin } from '../../staff-preorders';
import { allows, levelOf } from './model';
import { GEO } from './preorder-geo';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

interface MockRegion {
  code: string;
  name: string;
  kind: string;
  status: PlaceStatus;
  everyCity: boolean;
  version: number;
  cities: { name: string; status: PlaceStatus }[];
  capabilities: MarketRegion['capabilities'];
}

interface MockCountry {
  code: string;
  name: string;
  status: MarketCountryStatus;
  launchNote: string | null;
  currency: string | null;
  version: number;
  regions: MockRegion[];
}

export interface MockMarketsState {
  countries: MockCountry[];
  /** Countries with unpublished changes. */
  pending: string[];
  version: number;
}

const ON = { marketplace: true, services: true, food: true, pickup: false, taxRegistration: true };
const OFF = { marketplace: false, services: false, food: false, pickup: false, taxRegistration: false };

const SEED: Record<string, { status: MarketCountryStatus; note?: string; regions: Record<string, [PlaceStatus, string?]> }> = {
  US: {
    status: 'LIVE',
    note: 'Live: marketplace, services and local delivery in the launch states.',
    regions: {
      TX: ['LIVE', 'Houston:LIVE|Dallas:LIVE|Austin:LIVE|San Antonio:LIVE|Lubbock:LIVE|El Paso:PLANNED'],
      GA: ['LIVE', 'Atlanta:LIVE'],
      NY: ['LIVE'],
      NJ: ['LIVE'],
      MD: ['LIVE'],
      IL: ['LIVE'],
      OH: ['PLANNED', 'Columbus:PLANNED']
    }
  },
  CA: { status: 'COMING_SOON', note: 'Early access for sellers in 2027 — 90 days at 0% commission.', regions: { ON: ['PLANNED', 'Toronto:PLANNED|Mississauga:PLANNED'] } },
  GB: { status: 'PLANNED', regions: {} },
  NG: { status: 'PLANNED', regions: {} },
  GH: { status: 'PAUSED', regions: { AA: ['OFF'] } },
  KE: { status: 'PLANNED', regions: {} },
  ZA: { status: 'PLANNED', regions: {} },
  JM: { status: 'PLANNED', regions: {} },
  IE: { status: 'PLANNED', regions: {} }
};

const cityList = (spec?: string) =>
  (spec ? spec.split('|') : []).map((c) => {
    const [name = '', status = 'PLANNED'] = c.split(':');
    return { name, status: status as PlaceStatus };
  });

export function seedMarkets(): MockMarketsState {
  const countries = Object.entries(GEO).map(([code, g]): MockCountry => {
    const seed = SEED[code] ?? { status: 'PLANNED' as const, regions: {} };
    return {
      code,
      name: g.name,
      status: seed.status,
      launchNote: seed.note ?? null,
      currency: g.currency,
      version: 1,
      regions: Object.entries(g.regions).map(([rc, r]): MockRegion => {
        const [status, cities] = seed.regions[rc] ?? (['OFF'] as [PlaceStatus]);
        return {
          code: rc,
          name: r.name,
          kind: g.regionLabel.toUpperCase(),
          status,
          everyCity: status === 'LIVE' && !cities,
          version: 1,
          cities: cityList(cities),
          capabilities: status === 'LIVE' ? { ...ON } : { ...OFF }
        };
      })
    };
  });
  return { countries, pending: [], version: 1 };
}

const lower = (s: string) => s.trim().toLowerCase();

export function marketHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'markets')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open markets & locations.');
  };
  const canEdit = (ctx: PreorderCtx) => {
    const lv = levelOf(ctx.roles, 'markets');
    return ctx.permissions.includes('content:markets.edit') && (lv === 'EDIT' || lv === 'APPROVE' || lv === 'FULL');
  };
  const edit = (ctx: PreorderCtx) => {
    view(ctx);
    if (!canEdit(ctx)) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the content:markets.edit permission.');
  };
  const ifMatch = (ctx: PreorderCtx, version: number, what: string) => {
    const raw = ctx.request.headers.get('if-match');
    if (raw && raw.replace(/^W\//, '').replace(/"/g, '') !== String(version)) {
      throw problem(412, 'VERSION_CONFLICT', 'Changed by someone else', `${what} was changed by someone else. Refresh to see the latest.`);
    }
  };
  const countryOf = (ctx: PreorderCtx, code: string | undefined) => {
    const c = ctx.db.markets.countries.find((x) => x.code === String(code ?? '').toUpperCase());
    if (!c) throw problem(404, 'NOT_FOUND', 'Not found', 'No such country, state or province.');
    return c;
  };
  const regionOf = (c: MockCountry, code: string | undefined) => {
    const r = c.regions.find((x) => x.code === String(code ?? '').toUpperCase());
    if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'No such country, state or province.');
    return r;
  };
  const audit = (ctx: PreorderCtx, action: string, entityId: string, summary: string) =>
    ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });
  /** Open pre-orders (not drafts, not delivered) whose targets cover a place key. */
  const sellers = (ctx: PreorderCtx, key: string) =>
    ctx.db.preorders.windows
      .filter((w) => !w.draft && w.active && Date.parse(`${w.deliversOn}T23:59:59Z`) >= ctx.now.getTime() && w.targets.some((t) => keyWithin(t, key) || keyWithin(key, t)))
      .map((w) => w.code);
  const dto = (ctx: PreorderCtx, c: MockCountry): MarketCountry => ({
    code: c.code,
    name: c.name,
    status: c.status,
    launchNote: c.launchNote,
    currency: c.currency,
    liveRegions: c.regions.filter((r) => r.status === 'LIVE').length,
    plannedRegions: c.regions.filter((r) => r.status === 'PLANNED').length,
    version: c.version,
    canEdit: canEdit(ctx),
    regions: [...c.regions]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((r) => ({
        ...r,
        capabilities: { ...r.capabilities },
        cityCount: r.cities.length,
        liveCities: r.cities.filter((x) => x.status === 'LIVE').length,
        preorders: new Set(sellers(ctx, `${c.code}/${r.code}`)).size,
        cities: r.cities.map((x): MarketCity => ({ ...x, preorders: sellers(ctx, `${c.code}/${r.code}/${x.name}`) }))
      }))
  });
  const changes = (ctx: PreorderCtx): MarketChanges => ({ pending: ctx.db.markets.pending.length > 0, changedCountries: [...ctx.db.markets.pending], newlyLive: [], disabledRegions: [], enabledRegions: [] });
  const touch = (ctx: PreorderCtx, c: MockCountry, r?: MockRegion) => {
    if (!ctx.db.markets.pending.includes(c.code)) ctx.db.markets.pending.push(c.code);
    if (r) r.version += 1;
    else c.version += 1;
  };
  const requireLive = (r: MockRegion, status: PlaceStatus) => {
    if (status === 'LIVE' && r.status !== 'LIVE') {
      throw problem(422, 'VALIDATION_FAILED', 'Validation failed', `Make ${r.name} live before making its cities live`, { status: `${r.name} is not live` });
    }
  };
  const M = `${W}/markets/countries`;
  return [
    http.get(
      M,
      route((ctx): MarketCountries => {
        view(ctx);
        return {
          items: ctx.db.markets.countries.map((c) => ({
            code: c.code,
            name: c.name,
            status: c.status,
            regionCount: c.regions.length,
            liveRegions: c.regions.filter((r) => r.status === 'LIVE').length,
            plannedRegions: c.regions.filter((r) => r.status === 'PLANNED').length,
            cityCount: c.regions.reduce((n, r) => n + r.cities.length, 0),
            liveCities: c.regions.reduce((n, r) => n + r.cities.filter((x) => x.status === 'LIVE').length, 0),
            version: c.version
          })),
          changes: changes(ctx),
          canEdit: canEdit(ctx)
        };
      })
    ),
    http.get(
      `${M}/:code`,
      route((ctx, p) => {
        view(ctx);
        return dto(ctx, countryOf(ctx, p.code));
      })
    ),
    http.patch(
      `${M}/:code`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ status: z.enum(['LIVE', 'PAUSED', 'COMING_SOON', 'PLANNED']).optional(), launchNote: z.string().max(500).optional() }));
        edit(ctx);
        const c = countryOf(ctx, p.code);
        ifMatch(ctx, c.version, c.name);
        if (b.status) c.status = b.status;
        if (b.launchNote !== undefined) c.launchNote = b.launchNote || null;
        touch(ctx, c);
        audit(ctx, 'market.country.updated', c.code, `${c.name}: ${c.status}`);
        return dto(ctx, c);
      })
    ),
    http.post(
      `${M}/:code/regions`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ code: z.string().regex(/^[A-Za-z0-9]{1,10}$/), name: z.string().trim().min(1).max(80) }));
        edit(ctx);
        const c = countryOf(ctx, p.code);
        const code = b.code.toUpperCase();
        const existing = c.regions.find((r) => r.code === code);
        if (existing && existing.status !== 'OFF') throw problem(409, 'CONFLICT', 'Conflict', `${existing.name} is already ${existing.status.toLowerCase()}`);
        if (existing) {
          existing.status = 'PLANNED';
          existing.everyCity = existing.cities.length ? existing.everyCity : false;
          touch(ctx, c, existing);
        } else {
          c.regions.push({ code, name: b.name, kind: 'STATE', status: 'PLANNED', everyCity: false, version: 1, cities: [], capabilities: { ...OFF } });
          touch(ctx, c);
        }
        audit(ctx, 'market.region.added', `${c.code}-${code}`, `${b.name} added as planned`);
        return dto(ctx, c);
      }, 201)
    ),
    http.patch(
      `${M}/:code/regions/:region`,
      route(async (ctx, p) => {
        const b = await json(
          ctx.request,
          z.object({
            status: PlaceStatusSchema.optional(),
            everyCity: z.boolean().optional(),
            marketplace: z.boolean().optional(),
            services: z.boolean().optional(),
            food: z.boolean().optional(),
            pickup: z.boolean().optional(),
            taxRegistration: z.boolean().optional()
          })
        );
        edit(ctx);
        const c = countryOf(ctx, p.code);
        const r = regionOf(c, p.region);
        ifMatch(ctx, r.version, r.name);
        if (b.status && b.status !== r.status) {
          r.status = b.status;
          r.capabilities = b.status === 'LIVE' ? { ...r.capabilities, marketplace: true } : { ...OFF };
          if (b.status !== 'LIVE') r.cities = r.cities.map((x) => (x.status === 'LIVE' ? { ...x, status: 'PLANNED' } : x));
        }
        if (b.everyCity !== undefined) r.everyCity = b.everyCity;
        for (const k of ['services', 'food', 'pickup', 'taxRegistration'] as const) {
          const v = b[k];
          if (v !== undefined) {
            if (v && r.status !== 'LIVE') throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Turn the marketplace on in this state before enabling other capabilities');
            r.capabilities = { ...r.capabilities, [k]: v };
          }
        }
        touch(ctx, c, r);
        audit(ctx, 'market.region.updated', `${c.code}-${r.code}`, `${r.name}: ${r.status}${r.everyCity ? ', every city' : ''}`);
        return dto(ctx, c);
      })
    ),
    http.post(
      `${M}/:code/regions/:region/cities`,
      route(async (ctx, p) => {
        const b = await json(
          ctx.request,
          z.object({ cities: z.array(z.object({ name: z.string().trim().min(1).max(80).regex(/^[^/]+$/), status: PlaceStatusSchema.default('PLANNED') })).min(1).max(2000) })
        );
        edit(ctx);
        const c = countryOf(ctx, p.code);
        const r = regionOf(c, p.region);
        ifMatch(ctx, r.version, r.name);
        const have = new Set(r.cities.map((x) => lower(x.name)));
        for (const x of b.cities) {
          requireLive(r, x.status);
          if (!have.has(lower(x.name))) {
            r.cities.push({ name: x.name, status: x.status });
            have.add(lower(x.name));
          }
        }
        touch(ctx, c, r);
        audit(ctx, 'market.cities.added', `${c.code}-${r.code}`, `${b.cities.length} cities added to ${r.name}`);
        return dto(ctx, c);
      })
    ),
    http.patch(
      `${M}/:code/regions/:region/cities`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ names: z.array(z.string()).min(1).max(2000), status: PlaceStatusSchema }));
        edit(ctx);
        const c = countryOf(ctx, p.code);
        const r = regionOf(c, p.region);
        ifMatch(ctx, r.version, r.name);
        requireLive(r, b.status);
        const keys = new Set(b.names.map(lower));
        const unknown = [...keys].filter((k) => !r.cities.some((x) => lower(x.name) === k));
        if (unknown.length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', `Not listed in ${r.name}: ${unknown.join(', ')}`);
        r.cities = r.cities.map((x) => (keys.has(lower(x.name)) ? { ...x, status: b.status } : x));
        touch(ctx, c, r);
        audit(ctx, 'market.cities.status', `${c.code}-${r.code}`, `${b.names.join(', ')} → ${b.status}`);
        return dto(ctx, c);
      })
    ),
    http.delete(
      `${M}/:code/regions/:region/cities/:city`,
      route((ctx, p) => {
        edit(ctx);
        const c = countryOf(ctx, p.code);
        const r = regionOf(c, p.region);
        ifMatch(ctx, r.version, r.name);
        const name = decodeURIComponent(p.city ?? '');
        const city = r.cities.find((x) => lower(x.name) === lower(name));
        if (!city) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', `Not listed in ${r.name}: ${name}`);
        const using = sellers(ctx, `${c.code}/${r.code}/${city.name}`);
        if (using.length) {
          throw problem(409, 'markets.city-in-use', 'City in use', `${city.name} has ${using.length} open pre-order${using.length > 1 ? 's' : ''} (${using.join(', ')}). Switch it off instead of removing it.`);
        }
        r.cities = r.cities.filter((x) => x !== city);
        touch(ctx, c, r);
        audit(ctx, 'market.cities.removed', `${c.code}-${r.code}`, `${city.name} removed from ${r.name}`);
        return dto(ctx, c);
      })
    ),
    http.post(
      `${W}/markets/publications`,
      route(async (ctx) => {
        await json(ctx.request, z.object({ note: z.string().max(500).optional() }));
        edit(ctx);
        if (!ctx.db.markets.pending.length) throw problem(409, 'CONFLICT', 'Conflict', 'There are no unpublished changes');
        const out = { version: ctx.db.markets.version + 1, changes: changes(ctx) };
        ctx.db.markets.version += 1;
        ctx.db.markets.pending = [];
        audit(ctx, 'market.published', `v${out.version}`, `Published markets v${out.version}`);
        return out;
      }, 201)
    ),
    http.get(
      `${W}/places`,
      route((ctx): StaffPlaceList => {
        if (!allows(ctx.roles, 'markets') && !allows(ctx.roles, 'preorders')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open markets & locations.');
        const qs = new URL(ctx.request.url).searchParams;
        const country = (qs.get('country') ?? '').toUpperCase();
        const region = (qs.get('region') ?? '').toUpperCase();
        const q = lower(qs.get('q') ?? '');
        if (!country) {
          const items = Object.entries(GEO).map(([code, g]) => ({ code, name: g.name, label: g.regionLabel })).sort((a, b) => a.name.localeCompare(b.name));
          return { items, label: 'Country', total: items.length };
        }
        const g = GEO[country];
        if (!g) throw problem(404, 'NOT_FOUND', 'Not found', 'No such country, state or province.');
        if (!region) {
          const items = Object.entries(g.regions).map(([code, r]) => ({ code, name: r.name, cityCount: r.cities.length })).sort((a, b) => a.name.localeCompare(b.name));
          return { items, label: g.regionLabel, total: items.length };
        }
        const r = g.regions[region];
        if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'No such country, state or province.');
        const all = [...r.cities].sort((a, b) => a.localeCompare(b)).filter((n) => !q || lower(n).includes(q));
        const limit = Math.min(Number(qs.get('limit') ?? 200) || 200, 5000);
        return { items: all.slice(0, limit).map((n) => ({ code: n, name: n })), label: 'City', total: all.length };
      })
    )
  ];
}
