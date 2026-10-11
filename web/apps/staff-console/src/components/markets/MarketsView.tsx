'use client';

import { staffMarketKeys, staffMarketQueries } from '@afrotransact/api-client';
import {
  type MarketCountry,
  type MarketCountryStatus,
  type MarketCountrySummary,
  type MarketRegion,
  type MarketRegionChange,
  type PlaceStatus,
  type StaffMarketsApi
} from '@afrotransact/api-client/staff-markets';
import { useToast } from '@afrotransact/ui';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useRef } from 'react';

import { LocationTree, type TreeOps, useTreeExpansion } from '@/components/locations/LocationTree';
import { treePrimary } from '@/components/locations/PlacePicker';
import { type StatusOption, type TreeCountry, type TreeRegion } from '@/components/locations/tree-model';
import { ModulePage } from '@/components/module/ModulePage';
import { Toggle } from '@/components/preorders/primitives';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

/* Settings → Markets & locations as the shared location tree. A country's status in content (LIVE, COMING_SOON, PAUSED,
   PLANNED) maps to the tree's Live / Planned (coming soon) / Off; edits go to the draft and Publish makes them live. */

const COUNTRY_STATUSES: StatusOption[] = [
  { value: 'LIVE', label: 'Live' },
  { value: 'PLANNED', label: 'Coming soon' },
  { value: 'OFF', label: 'Off' }
];
const PLACE_STATUSES: StatusOption[] = [
  { value: 'LIVE', label: 'Live' },
  { value: 'PLANNED', label: 'Planned' },
  { value: 'OFF', label: 'Off' }
];
const CAPABILITIES: [keyof Omit<MarketRegionChange, 'status' | 'everyCity' | 'marketplace'>, string][] = [
  ['services', 'Services'],
  ['food', 'Food & perishables'],
  ['pickup', 'Pickup points'],
  ['taxRegistration', 'Tax registered']
];
const REGION_LABEL: Record<string, string> = { STATE: 'State', PROVINCE: 'Province', TERRITORY: 'Province', NATION: 'Nation', DISTRICT: 'State', COUNTY: 'County', PARISH: 'Parish', REGION: 'Region' };
const KNOWN_LABEL: Record<string, string> = { CA: 'Province', GB: 'Nation', GH: 'Region', KE: 'County', JM: 'Parish', IE: 'County', ZA: 'Province' };

export const treeStatusOf = (s: MarketCountryStatus): PlaceStatus => (s === 'LIVE' ? 'LIVE' : s === 'COMING_SOON' ? 'PLANNED' : 'OFF');
const countryLabel = (s: MarketCountryStatus) => (s === 'LIVE' ? 'Live' : s === 'COMING_SOON' ? 'Coming soon' : s === 'PAUSED' ? 'Paused' : 'Not launched');
/** The content status a tree status becomes: Off pauses a live country, otherwise it is "not launched". */
export const marketStatusOf = (s: PlaceStatus, current: MarketCountryStatus): MarketCountryStatus =>
  s === 'LIVE' ? 'LIVE' : s === 'PLANNED' ? 'COMING_SOON' : current === 'LIVE' || current === 'PAUSED' ? 'PAUSED' : 'PLANNED';

export function regionOf(r: MarketRegion): TreeRegion {
  return {
    code: r.code,
    name: r.name,
    status: r.status,
    everyCity: r.everyCity,
    cities: r.cities.map((c) => ({ name: c.name, status: c.status, dependents: c.preorders })),
    dependents: r.preorders
  };
}

export function countryOf(s: MarketCountrySummary, detail: MarketCountry | undefined): TreeCountry {
  const kind = detail?.regions[0]?.kind;
  return {
    code: s.code,
    name: s.name,
    status: treeStatusOf(detail?.status ?? s.status),
    statusLabel: countryLabel(detail?.status ?? s.status),
    regionLabel: (kind && REGION_LABEL[kind]) || KNOWN_LABEL[s.code] || 'State',
    regions: detail ? detail.regions.map(regionOf) : null,
    summary: { live: s.liveRegions, planned: s.plannedRegions, cities: s.cityCount, liveCities: s.liveCities }
  };
}

/** Runs edits of one place one after another, so each sends the version the previous one returned. */
function useSerial() {
  const chains = useRef(new Map<string, Promise<unknown>>());
  return <T,>(key: string, fn: () => Promise<T>): Promise<T> => {
    const prev = chains.current.get(key) ?? Promise.resolve();
    const next = prev.catch(() => undefined).then(fn);
    chains.current.set(key, next);
    return next;
  };
}

export function MarketsTree({ api }: { api: StaffMarketsApi }) {
  const qc = useQueryClient();
  const toast = useToast();
  const serial = useSerial();
  const list = useQuery(staffMarketQueries.countries(api));
  const [expanded, setExpanded] = useTreeExpansion();
  const openCountries = useMemo(() => [...expanded].filter((k) => !k.includes('/')), [expanded]);
  const details = useQueries({ queries: openCountries.map((code) => staffMarketQueries.country(api, code)) });
  const detailOf = (code: string) => qc.getQueryData<MarketCountry>(staffMarketKeys.country(code));

  const countries = useMemo(() => {
    if (!list.data) return undefined;
    const byCode = new Map(openCountries.map((code, i) => [code, details[i]?.data]));
    return list.data.items.map((s) => countryOf(s, byCode.get(s.code)));
  }, [list.data, openCountries, details]);

  const saved = (next: MarketCountry) => {
    qc.setQueryData(staffMarketKeys.country(next.code), next);
    void qc.invalidateQueries({ queryKey: staffMarketKeys.countries() });
  };
  const regionVersion = (country: string, region: string) => detailOf(country)?.regions.find((r) => r.code === region)?.version ?? 0;
  const countryVersion = (code: string) => detailOf(code)?.version ?? list.data?.items.find((c) => c.code === code)?.version ?? 0;
  const currentStatus = (code: string): MarketCountryStatus => detailOf(code)?.status ?? list.data?.items.find((c) => c.code === code)?.status ?? 'PLANNED';
  const edit = (country: string, region: string, fn: (version: number) => Promise<MarketCountry>) =>
    serial(`${country}/${region}`, async () => saved(await fn(regionVersion(country, region))));

  const ops: TreeOps = {
    setCountryStatus: (c, s) => serial(c.code, async () => saved(await api.updateCountry(c.code, { status: marketStatusOf(s, currentStatus(c.code)) }, countryVersion(c.code)))),
    addCountry: (p) => serial(p.code, async () => saved(await api.updateCountry(p.code, { status: 'COMING_SOON' }, countryVersion(p.code)))),
    addRegion: (c, p) => serial(`${c.code}/${p.code}`, async () => saved(await api.addRegion(c.code, { code: p.code, name: p.name }))),
    setRegionStatus: (c, r, s) => edit(c.code, r.code, (v) => api.updateRegion(c.code, r.code, { status: s }, v)),
    setEveryCity: (c, r, on) => edit(c.code, r.code, (v) => api.updateRegion(c.code, r.code, { everyCity: on }, v)),
    addCities: (c, r, cities) => edit(c.code, r.code, (v) => api.addCities(c.code, r.code, cities, v)),
    setCityStatus: (c, r, names, s) => edit(c.code, r.code, (v) => api.setCities(c.code, r.code, names, s, v)),
    removeCity: (c, r, city) => edit(c.code, r.code, (v) => api.removeCity(c.code, r.code, city.name, v)),
    refresh: () => qc.invalidateQueries({ queryKey: staffMarketKeys.all })
  };

  const publish = useMutation({
    mutationFn: () => api.publish(),
    onSuccess: (p) => {
      toast.show(`Published version ${p.version}: eligibility, checkout and the deliver-to check use it now`);
      void qc.invalidateQueries({ queryKey: staffMarketKeys.all });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  if (list.isError) return <ErrorState error={list.error} onRetry={() => void list.refetch()} />;
  const canEdit = list.data?.canEdit ?? false;
  const changes = list.data?.changes;
  const capability = (c: TreeCountry, r: TreeRegion) => {
    const raw = detailOf(c.code)?.regions.find((x) => x.code === r.code);
    if (!raw || r.status !== 'LIVE') return null;
    return (
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1" role="group" aria-label={`What ${r.name} offers`}>
        {CAPABILITIES.map(([k, labelText]) => (
          <span key={k} className="flex min-h-11 items-center gap-2 text-xs sm:min-h-8">
            <Toggle
              on={raw.capabilities[k]}
              size="sm"
              label={`${labelText} in ${r.name}`}
              disabled={!canEdit}
              onClick={() =>
                void edit(c.code, r.code, (v) => api.updateRegion(c.code, r.code, { [k]: !raw.capabilities[k] }, v)).catch((e: unknown) => {
                  toast.show(problemMessage(e), 'error');
                  void qc.invalidateQueries({ queryKey: staffMarketKeys.country(c.code) });
                })
              }
            />
            {labelText}
          </span>
        ))}
      </div>
    );
  };
  return (
    <LocationTree
      label="Markets & locations"
      countries={countries}
      statuses={{ country: COUNTRY_STATUSES, region: PLACE_STATUSES, city: PLACE_STATUSES }}
      newCityStatus="PLANNED"
      canEdit={canEdit}
      readOnlyNote={list.data && !canEdit ? 'View only: changing markets needs Edit access and content:markets.edit.' : undefined}
      ops={ops}
      places={api}
      expanded={expanded}
      onExpand={setExpanded}
      regionExtra={capability}
      coverageHint={(_c, r) => (r.everyCity ? 'Every city takes orders unless it is listed Planned or Off.' : 'Only the cities listed Live take orders.')}
      emptyText="No country is live or coming soon. Add a country below."
      toolbar={
        <div className="flex flex-wrap items-center gap-2" data-testid="markets-publish">
          <span className="text-sm text-muted">
            {changes?.pending ? `Unpublished changes: ${changes.changedCountries.join(', ')}` : 'Everything is published.'}
          </span>
          {canEdit ? (
            <button type="button" className={treePrimary} disabled={!changes?.pending || publish.isPending} onClick={() => publish.mutate()}>
              Publish changes
            </button>
          ) : null}
        </div>
      }
    />
  );
}

/** Settings → Markets & locations. */
export function MarketsView() {
  return (
    <ModulePage module="markets">
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        Only what is live or planned shows: countries, their states and their cities. Change a status, add states and cities, or remove one right in the tree; every change is a draft until you publish it.
      </p>
      <MarketsTree api={staffApi().markets} />
    </ModulePage>
  );
}
