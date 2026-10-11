'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import { type StaffPlace } from '@afrotransact/api-client/staff-markets';
import { type PreorderAreaPage, type PreorderRegion, type PreorderRegionInput, type StaffPreordersApi } from '@afrotransact/api-client/staff-preorders';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';

import { LocationTree, type TreeOps, useTreeExpansion } from '@/components/locations/LocationTree';
import { type PlaceStatus, type StatusOption, type TreeCity, type TreeCountry, type TreeRegion } from '@/components/locations/tree-model';
import { ErrorState } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

/* Pre-orders → Areas & settings → Pre-order areas as the shared location tree. delivery-runs owns the coverage (a city
   row overrides its state's "Every city" row); each state is edited with one versioned PUT (If-Match). Areas are On or
   Off: Off pauses an area without losing it. A state picked before it has cities is kept here until its first save. */

const ON_OFF: StatusOption[] = [
  { value: 'LIVE', label: 'On' },
  { value: 'OFF', label: 'Off' }
];
const PAGE = { page: 0, size: 100 };
const lower = (s: string) => s.trim().toLocaleLowerCase('en-US');

/** The region PUT body that keeps everything as it is (edits change one part of it). */
export const regionBody = (r: PreorderRegion): PreorderRegionInput => ({ everyCity: r.wholeRegion ? r.wholeRegion.on : null, cities: r.cities.map((c) => ({ city: c.name, on: c.on })) });

export function regionCities(r: PreorderRegion): TreeCity[] {
  return r.cities.map((c) => ({ name: c.name, status: c.on ? 'LIVE' : 'OFF', dependents: c.windows }));
}

const statusOfRow = (row: { on: number }): PlaceStatus => (row.on > 0 ? 'LIVE' : 'OFF');

export function PreorderAreasTree({ canEdit, api = staffApi().preorders }: { canEdit: boolean; api?: StaffPreordersApi }) {
  const qc = useQueryClient();
  const places = staffApi().markets;
  const [expanded, setExpanded] = useTreeExpansion();
  const [draftCountries, setDraftCountries] = useState<StaffPlace[]>([]);
  const [draftRegions, setDraftRegions] = useState<Record<string, StaffPlace[]>>({});
  const removed = useRef(new Map<string, PreorderRegionInput>());
  const chains = useRef(new Map<string, Promise<unknown>>());

  const root = useQuery(staffPreorderQueries.areas(api, PAGE));
  const openCountries = useMemo(() => [...expanded].filter((k) => !k.includes('/')), [expanded]);
  const openRegions = useMemo(() => [...expanded].filter((k) => k.includes('/')).map((k) => k.split('/') as [string, string]), [expanded]);
  const regionPages = useQueries({ queries: openCountries.map((c) => staffPreorderQueries.areas(api, { ...PAGE, country: c })) });
  const regionViews = useQueries({ queries: openRegions.map(([c, r]) => staffPreorderQueries.region(api, c, r)) });

  const countries = useMemo((): TreeCountry[] | undefined => {
    const page = root.data;
    if (!page) return undefined;
    const pages = new Map<string, PreorderAreaPage | undefined>(openCountries.map((c, i) => [c, regionPages[i]?.data]));
    const views = new Map<string, PreorderRegion | undefined>(openRegions.map(([c, r], i) => [`${c}/${r}`, regionViews[i]?.data]));
    const regionsOf = (code: string): TreeRegion[] | null => {
      const p = pages.get(code);
      if (!p) return draftRegions[code] && !page.rows.some((r) => r.key === code) ? draftRegionsOf(code) : null;
      const saved = p.rows.map((row): TreeRegion => {
        const rc = row.key.split('/')[1] ?? row.key;
        const view = views.get(row.key);
        return {
          code: rc,
          name: row.name,
          status: statusOfRow(row),
          everyCity: view ? !!view.wholeRegion?.on : row.coverage.startsWith('Every city'),
          cities: view ? regionCities(view) : null,
          dependents: row.windows
        };
      });
      return [...saved, ...draftRegionsOf(code).filter((d) => !saved.some((s) => s.code === d.code))].sort((a, b) => a.name.localeCompare(b.name));
    };
    const draftRegionsOf = (code: string): TreeRegion[] =>
      (draftRegions[code] ?? []).map((p) => {
        const view = views.get(`${code}/${p.code}`);
        return { code: p.code, name: p.name, status: 'PLANNED', everyCity: false, cities: view ? regionCities(view) : [], dependents: 0, draft: true };
      });
    const out: TreeCountry[] = page.rows.map((row) => ({
      code: row.key,
      name: row.name,
      status: statusOfRow(row),
      statusLabel: row.on > 0 ? `On · ${row.on} of ${row.total}` : 'Off',
      regionLabel: row.key === 'CA' ? 'Province' : 'State',
      regions: regionsOf(row.key),
      summary: { live: 0, planned: 0, cities: 0 }
    }));
    for (const p of draftCountries) {
      if (out.some((c) => c.code === p.code)) continue;
      out.push({ code: p.code, name: p.name, status: 'PLANNED', statusLabel: 'New', regionLabel: p.label ?? 'State', regions: draftRegionsOf(p.code), draft: true });
    }
    return out.map((c) => (c.regions ? { ...c, summary: undefined } : { ...c, summary: summaryOf(page, c.code) }));
  }, [root.data, openCountries, openRegions, regionPages, regionViews, draftRegions, draftCountries]);

  /** One region's coverage as stored (fetched when the state is closed). */
  const current = (c: string, r: string) => qc.fetchQuery({ ...staffPreorderQueries.region(api, c, r), staleTime: 0 });
  const put = (c: string, r: string, change: (body: PreorderRegionInput, now: PreorderRegion) => PreorderRegionInput) => {
    const key = `${c}/${r}`;
    const prev = chains.current.get(key) ?? Promise.resolve();
    const next = prev
      .catch(() => undefined)
      .then(async () => {
        const now = qc.getQueryData<PreorderRegion>(staffPreorderKeys.region(c, r)) ?? (await current(c, r));
        const saved = await api.updateRegion(c, r, change(regionBody(now), now), now.version);
        qc.setQueryData(staffPreorderKeys.region(c, r), saved);
        if (saved.cities.length || saved.wholeRegion) {
          setDraftRegions((cur) => ({ ...cur, [c]: (cur[c] ?? []).filter((p) => p.code !== r) }));
          setDraftCountries((cur) => cur.filter((p) => p.code !== c));
        }
        await qc.invalidateQueries({ queryKey: staffPreorderKeys.areas(), predicate: (q) => !(q.queryKey[3] === 'region' && q.queryKey[4] === c && q.queryKey[5] === r) });
      });
    chains.current.set(key, next);
    return next;
  };
  const on = (s: PlaceStatus) => s === 'LIVE';

  const ops: TreeOps = {
    addCountry: async (p) => {
      setDraftCountries((cur) => (cur.some((x) => x.code === p.code) ? cur : [...cur, p]));
    },
    addRegion: async (c, p) => {
      setDraftRegions((cur) => ({ ...cur, [c.code]: [...(cur[c.code] ?? []).filter((x) => x.code !== p.code), p] }));
    },
    setRegionStatus: (c, r, s) =>
      put(c.code, r.code, (b) => ({ everyCity: b.everyCity === null ? null : on(s), cities: b.cities.map((x) => ({ ...x, on: on(s) })) })),
    removeRegion: (c, r) =>
      put(c.code, r.code, (b) => {
        removed.current.set(`${c.code}/${r.code}`, b);
        return { everyCity: null, cities: [] };
      }),
    restoreRegion: (c, r) => put(c.code, r.code, () => removed.current.get(`${c.code}/${r.code}`) ?? { everyCity: null, cities: [] }),
    setEveryCity: (c, r, everyCity) => put(c.code, r.code, (b) => ({ ...b, everyCity: everyCity ? true : null })),
    addCities: (c, r, cities) =>
      put(c.code, r.code, (b) => ({ ...b, cities: [...b.cities, ...cities.filter((x) => !b.cities.some((y) => lower(y.city) === lower(x.name))).map((x) => ({ city: x.name, on: on(x.status) }))] })),
    setCityStatus: (c, r, names, s) => {
      const keys = new Set(names.map(lower));
      return put(c.code, r.code, (b) => ({ ...b, cities: b.cities.map((x) => (keys.has(lower(x.city)) ? { ...x, on: on(s) } : x)) }));
    },
    removeCity: (c, r, city) => put(c.code, r.code, (b) => ({ ...b, cities: b.cities.filter((x) => lower(x.city) !== lower(city.name)) })),
    refresh: () => qc.invalidateQueries({ queryKey: staffPreorderKeys.areas() })
  };

  if (root.isError) return <ErrorState error={root.error} onRetry={() => void root.refetch()} />;
  return (
    <LocationTree
      label="Pre-order areas"
      countries={countries}
      statuses={{ region: ON_OFF, city: ON_OFF }}
      newCityStatus="LIVE"
      canEdit={canEdit}
      ops={ops}
      places={places}
      expanded={expanded}
      onExpand={setExpanded}
      coverageHint={(_c, r) => (r.everyCity ? 'Every city can get a pre-order; listed cities override it (e.g. keep one off).' : 'Only the cities listed On can get a pre-order.')}
      emptyText="No pre-order areas yet. Add a country, then its states and cities."
      toolbar={root.data ? <span className="text-xs text-muted" data-testid="area-count">{`${root.data.onCount} on · ${root.data.offCount} off`}</span> : null}
    />
  );
}

function summaryOf(page: PreorderAreaPage, code: string): TreeCountry['summary'] {
  const row = page.rows.find((r) => r.key === code);
  const m = row ? /^(\d+) \S+ · (\d+) cities$/.exec(row.coverage) : null;
  return { live: m ? Number(m[1]) : 0, planned: 0, cities: m ? Number(m[2]) : 0 };
}
