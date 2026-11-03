/* Mock of Search analytics (/v1/staff/search/**, the search service through the BFF). Daily aggregates per normalised
   query are generated deterministically for any window (a weekly rhythm plus a stable per-query, per-day wobble), so
   the same dates always give the same numbers. Synonym rules live in the mock database. Like the Go BFF, the reports
   are whole windows capped at 500 queries that the mock filters, sorts and pages. */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import {
  type StaffSearchClickThrough,
  type StaffSearchDay,
  type StaffSearchMarkets,
  type StaffSearchQueryReport,
  type StaffSearchQueryStat,
  type StaffSynonymList,
  type StaffSynonymRule
} from '../../staff-search';
import { levelOf } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

export interface MockSearchState {
  rules: { id: string; synonyms: string }[];
}

export function seedSearch(): MockSearchState {
  return {
    rules: [
      { id: 'gari', synonyms: 'gari, garri, cassava flakes' },
      { id: 'zobo', synonyms: 'zobo, bissap, hibiscus tea' },
      { id: 'plantain-chips', synonyms: 'plantain chips, kpekere, ipekere' },
      { id: 'ankara', synonyms: 'ankara, african wax print, wax print fabric' }
    ]
  };
}

/** Base behaviour of a query: searches a day, share of searches with no results, clicks per search. */
const QUERIES: { q: string; perDay: number; zero: number; ctr: number }[] = [
  { q: 'shea butter', perDay: 182, zero: 0.0, ctr: 0.52 },
  { q: 'kente', perDay: 118, zero: 0.02, ctr: 0.44 },
  { q: 'gari', perDay: 104, zero: 0.0, ctr: 0.58 },
  { q: 'ankara fabric', perDay: 96, zero: 0.01, ctr: 0.41 },
  { q: 'black soap', perDay: 91, zero: 0.0, ctr: 0.49 },
  { q: 'palm oil', perDay: 84, zero: 0.0, ctr: 0.55 },
  { q: 'egusi', perDay: 77, zero: 0.04, ctr: 0.5 },
  { q: 'jollof rice', perDay: 72, zero: 0.03, ctr: 0.38 },
  { q: 'plantain chips', perDay: 66, zero: 0.0, ctr: 0.47 },
  { q: 'suya spice', perDay: 61, zero: 0.0, ctr: 0.53 },
  { q: 'fufu flour', perDay: 58, zero: 0.0, ctr: 0.51 },
  { q: 'hair braiding', perDay: 54, zero: 0.06, ctr: 0.36 },
  { q: 'dashiki', perDay: 49, zero: 0.02, ctr: 0.42 },
  { q: 'scotch bonnet', perDay: 46, zero: 0.0, ctr: 0.46 },
  { q: 'zobo', perDay: 41, zero: 0.0, ctr: 0.5 },
  { q: 'cassava flour', perDay: 39, zero: 0.0, ctr: 0.48 },
  { q: 'moi moi', perDay: 34, zero: 0.12, ctr: 0.31 },
  { q: 'cameroon pepper', perDay: 31, zero: 0.0, ctr: 0.45 },
  { q: 'indomie', perDay: 29, zero: 0.08, ctr: 0.4 },
  { q: 'agbada', perDay: 26, zero: 0.1, ctr: 0.33 },
  { q: 'shea butter bulk', perDay: 22, zero: 0.0, ctr: 0.39 },
  { q: 'bubu gown', perDay: 19, zero: 0.74, ctr: 0.12 },
  { q: 'agbada kids', perDay: 17, zero: 1, ctr: 0 },
  { q: 'grand boubou', perDay: 15, zero: 0.8, ctr: 0.1 },
  { q: 'suya spise', perDay: 13, zero: 1, ctr: 0 },
  { q: 'egussi seed', perDay: 12, zero: 0.9, ctr: 0.05 },
  { q: 'gele headtie', perDay: 11, zero: 0.7, ctr: 0.14 },
  { q: 'waakye leaves', perDay: 9, zero: 1, ctr: 0 },
  { q: 'fuful', perDay: 8, zero: 1, ctr: 0 },
  { q: 'nigerian wrapper', perDay: 7, zero: 0.86, ctr: 0.06 },
  { q: 'ogbono', perDay: 7, zero: 0.28, ctr: 0.3 },
  { q: 'yoruba aso oke', perDay: 6, zero: 0.67, ctr: 0.1 },
  { q: 'ewedu', perDay: 5, zero: 1, ctr: 0 },
  { q: 'kid agbada set', perDay: 4, zero: 1, ctr: 0 }
];

/** A stable number in [0, 1) from a string. */
function unit(seed: string): number {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i += 1) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  return ((h >>> 0) % 10000) / 10000;
}

const DAY_MS = 86_400_000;
const WEEKDAY = [0.92, 0.86, 0.9, 0.95, 1, 1.12, 1.25]; // Sunday first: shoppers search more on weekends

const dayOf = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (d: Date, n: number) => new Date(d.getTime() + n * DAY_MS);

/** Every day (UTC) from `from` to `to`, inclusive. */
export function daysBetween(from: string, to: string): string[] {
  const out: string[] = [];
  for (let d = new Date(`${from}T00:00:00Z`); dayOf(d) <= to; d = addDays(d, 1)) out.push(dayOf(d));
  return out;
}

function queryDay(q: (typeof QUERIES)[number], day: string): { searches: number; zero: number; clicks: number } {
  const weekday = WEEKDAY[new Date(`${day}T00:00:00Z`).getUTCDay()] ?? 1;
  const searches = Math.round(q.perDay * weekday * (0.8 + unit(`${q.q}|${day}|s`) * 0.4));
  const zero = q.zero >= 1 ? searches : Math.min(searches, Math.round(searches * q.zero * (0.85 + unit(`${q.q}|${day}|z`) * 0.3)));
  const clicks = Math.round((searches - zero) * q.ctr * (0.85 + unit(`${q.q}|${day}|c`) * 0.3));
  return { searches, zero, clicks };
}

function queryStats(days: string[]): StaffSearchQueryStat[] {
  return QUERIES.map((q) => {
    const sum = days.reduce(
      (a, day) => {
        const d = queryDay(q, day);
        return { searches: a.searches + d.searches, zero: a.zero + d.zero, clicks: a.clicks + d.clicks };
      },
      { searches: 0, zero: 0, clicks: 0 }
    );
    return { query: q.q, searches: sum.searches, zeroResults: sum.zero, clicks: sum.clicks, ctr: sum.searches ? sum.clicks / sum.searches : 0, zeroResultRate: sum.searches ? sum.zero / sum.searches : 0 };
  }).filter((s) => s.searches > 0);
}

function dayStats(days: string[]): StaffSearchDay[] {
  return days.map((day) => {
    const t = QUERIES.reduce(
      (a, q) => {
        const d = queryDay(q, day);
        return { searches: a.searches + d.searches, zero: a.zero + d.zero, clicks: a.clicks + d.clicks };
      },
      { searches: 0, zero: 0, clicks: 0 }
    );
    return { day, searches: t.searches, clicks: t.clicks, zeroResults: t.zero, ctr: t.searches ? t.clicks / t.searches : 0, zeroResultRate: t.searches ? t.zero / t.searches : 0 };
  });
}

type SortKey = { column: string; desc: boolean };
function parseSort(raw: string | null, def: SortKey): SortKey {
  if (!raw) return def;
  const [column = '', dir = 'desc'] = raw.split(',');
  return column ? { column, desc: dir.toLowerCase() !== 'asc' } : def;
}

const MARKETS = [{ code: 'us', name: 'United States' }];

export function searchHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const need = (ctx: PreorderCtx, perm?: string) => {
    const lv = levelOf(ctx.roles, 'search');
    if (!lv) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open search analytics.');
    if (perm && !ctx.permissions.includes(perm)) throw problem(403, 'FORBIDDEN', 'Forbidden', `This needs the ${perm} permission.`);
  };
  const canEdit = (ctx: PreorderCtx) => ctx.permissions.includes('search:synonyms.edit');
  const edit = (ctx: PreorderCtx) => {
    need(ctx, 'search:synonyms.edit');
  };
  const window = (ctx: PreorderCtx): { market: string; from: string; to: string; url: URL } => {
    const url = new URL(ctx.request.url);
    const errors: Record<string, string> = {};
    const market = (url.searchParams.get('market') || 'us').toLowerCase();
    if (!MARKETS.some((m) => m.code === market)) errors.market = 'Choose one of the listed markets.';
    const date = /^\d{4}-\d{2}-\d{2}$/;
    const today = dayOf(ctx.now);
    const to = url.searchParams.get('to') || today;
    const from = url.searchParams.get('from') || dayOf(addDays(new Date(`${to}T00:00:00Z`), -6));
    if (!date.test(to) || Number.isNaN(Date.parse(to))) errors.to = 'Use a date like 2026-10-31.';
    if (!date.test(from) || Number.isNaN(Date.parse(from))) errors.from = 'Use a date like 2026-10-31.';
    if (!errors.from && !errors.to) {
      if (from > to) errors.from = 'The first day is after the last day.';
      else if ((Date.parse(to) - Date.parse(from)) / DAY_MS >= 366) errors.to = 'Reports cover at most 366 days.';
    }
    if (Object.keys(errors).length > 0) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
    return { market, from, to, url };
  };
  const paging = (url: URL) => {
    const page = Number(url.searchParams.get('page') || 1);
    const size = Number(url.searchParams.get('size') || 25);
    if (!Number.isInteger(page) || page < 1 || !Number.isInteger(size) || size < 1 || size > 500) {
      throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { page: 'Out of range' });
    }
    return { page, size };
  };
  const marketName = (code: string) => MARKETS.find((m) => m.code === code)?.name ?? code.toUpperCase();
  const total = (items: StaffSearchQueryStat[]) => {
    const t = items.reduce((a, i) => ({ searches: a.searches + i.searches, zeroResults: a.zeroResults + i.zeroResults, clicks: a.clicks + i.clicks }), { searches: 0, zeroResults: 0, clicks: 0 });
    return { ...t, ctr: t.searches ? t.clicks / t.searches : 0, zeroResultRate: t.searches ? t.zeroResults / t.searches : 0 };
  };
  const report = (kind: 'top-queries' | 'zero-results') =>
    route((ctx): StaffSearchQueryReport => {
      need(ctx, 'search:analytics.view');
      const w = window(ctx);
      const { page, size } = paging(w.url);
      const all = queryStats(daysBetween(w.from, w.to));
      const t = total(all);
      const term = (w.url.searchParams.get('q') ?? '').trim().toLowerCase();
      let rows = all.filter((i) => (kind === 'zero-results' ? i.zeroResults > 0 : true) && (!term || i.query.includes(term)));
      const sort = parseSort(w.url.searchParams.get('sort'), { column: kind === 'zero-results' ? 'zeroResults' : 'searches', desc: true });
      const value = (i: StaffSearchQueryStat): number | string => (sort.column === 'query' ? i.query : sort.column in i ? (i[sort.column as keyof StaffSearchQueryStat] as number) : i.searches);
      rows = [...rows].sort((a, b) => {
        const x = value(a);
        const y = value(b);
        const c = typeof x === 'string' ? x.localeCompare(String(y)) : x - Number(y);
        return (sort.desc ? -c : c) || b.searches - a.searches || a.query.localeCompare(b.query);
      });
      return {
        market: w.market,
        marketName: marketName(w.market),
        from: w.from,
        to: w.to,
        items: rows.slice((page - 1) * size, page * size),
        total: rows.length,
        page,
        size,
        truncated: false,
        totals: t
      };
    });
  return [
    http.get(
      `${W}/search/markets`,
      route((ctx): StaffSearchMarkets => {
        need(ctx);
        return { items: MARKETS, default: 'us', canEditSynonyms: canEdit(ctx) };
      })
    ),
    http.get(`${W}/search/analytics/top-queries`, report('top-queries')),
    http.get(`${W}/search/analytics/zero-results`, report('zero-results')),
    http.get(
      `${W}/search/analytics/ctr`,
      route((ctx): StaffSearchClickThrough => {
        need(ctx, 'search:analytics.view');
        const w = window(ctx);
        const { page, size } = paging(w.url);
        const trend = dayStats(daysBetween(w.from, w.to));
        const sort = parseSort(w.url.searchParams.get('sort'), { column: 'day', desc: true });
        const term = (w.url.searchParams.get('q') ?? '').trim();
        const rows = trend.filter((d) => !term || d.day.includes(term)).sort((a, b) => {
          const key = sort.column in a && sort.column !== 'day' ? (sort.column as keyof StaffSearchDay) : 'day';
          const c = key === 'day' ? a.day.localeCompare(b.day) : Number(a[key]) - Number(b[key]);
          return (sort.desc ? -c : c) || (sort.desc ? b.day.localeCompare(a.day) : a.day.localeCompare(b.day));
        });
        const t = trend.reduce((a, d) => ({ searches: a.searches + d.searches, clicks: a.clicks + d.clicks, zeroResults: a.zeroResults + d.zeroResults }), { searches: 0, clicks: 0, zeroResults: 0 });
        return {
          market: w.market,
          marketName: marketName(w.market),
          from: w.from,
          to: w.to,
          items: rows.slice((page - 1) * size, page * size),
          total: rows.length,
          page,
          size,
          trend,
          totals: { day: 'total', ...t, ctr: t.searches ? t.clicks / t.searches : 0, zeroResultRate: t.searches ? t.zeroResults / t.searches : 0 }
        };
      })
    ),
    http.get(
      `${W}/search/synonyms`,
      route((ctx): StaffSynonymList => {
        const lv = levelOf(ctx.roles, 'search');
        if (!lv) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open search analytics.');
        const items = ctx.db.search.rules.map((r): StaffSynonymRule => ({ id: r.id, terms: r.synonyms.split(',').map((t) => t.trim()).filter(Boolean) }));
        items.sort((a, b) => a.terms.join(' ').localeCompare(b.terms.join(' ')));
        return { items, canEdit: canEdit(ctx) };
      })
    ),
    http.post(
      `${W}/search/synonyms`,
      route(async (ctx): Promise<StaffSynonymRule> => {
        const body = await json(ctx.request, z.object({ terms: z.array(z.string()).default([]) }));
        const seen = new Set<string>();
        const terms: string[] = [];
        body.terms.forEach((raw) => {
          const t = raw.trim().replace(/\s+/g, ' ');
          if (t && !seen.has(t.toLowerCase())) {
            seen.add(t.toLowerCase());
            terms.push(t);
          }
        });
        const invalid = (m: string) => problem(422, 'VALIDATION_FAILED', 'Check the words', m, { terms: m });
        if (terms.some((t) => t.includes(','))) throw invalid('Separate words with commas, one entry each.');
        if (terms.some((t) => t.length > 60)) throw invalid('Each word or phrase is at most 60 characters.');
        if (terms.length < 2 || terms.length > 20) throw invalid('Give 2 to 20 different words or phrases.');
        edit(ctx);
        const base = (terms[0] ?? 'rule').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 56) || 'rule';
        let id = base;
        for (let n = 2; ctx.db.search.rules.some((r) => r.id === id); n += 1) id = `${base}-${n}`;
        const synonyms = terms.join(', ');
        ctx.db.search.rules.push({ id, synonyms });
        ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action: 'search.synonym.added', summary: `Synonyms: ${synonyms}`, entityId: id });
        return { id, terms };
      }, 201)
    ),
    http.delete(
      `${W}/search/synonyms/:ruleId`,
      route((ctx, p) => {
        edit(ctx);
        const i = ctx.db.search.rules.findIndex((r) => r.id === p.ruleId);
        if (i < 0) throw problem(404, 'NOT_FOUND', 'Not found', 'That synonym rule is already gone.');
        const [gone] = ctx.db.search.rules.splice(i, 1);
        ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action: 'search.synonym.deleted', summary: `Removed: ${gone?.synonyms ?? ''}`, entityId: p.ruleId ?? '' });
        return new HttpResponse(null, { status: 204 });
      })
    )
  ];
}
