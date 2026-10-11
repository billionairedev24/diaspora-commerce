import { isApiError } from '@afrotransact/api-client';
import { type PlaceStatus } from '@afrotransact/api-client/staff-markets';

/*
 * The location tree shared by Settings → Markets & locations and Pre-orders → Pre-order areas: country → states →
 * cities, each LIVE / PLANNED / OFF. Only places in use (LIVE or PLANNED) show unless "Show switched-off" is on.
 * Each screen maps its own data onto these nodes and supplies the edits (TreeOps); the tree does the rest: inline
 * pickers, optimistic status, removal guard, undo, version conflicts, keyboard navigation.
 */

export type { PlaceStatus };

export interface TreeCity {
  name: string;
  status: PlaceStatus;
  /** What depends on the city (open pre-orders): it can be switched off but not removed while any do. */
  dependents: string[];
}

export interface TreeRegion {
  code: string;
  name: string;
  status: PlaceStatus;
  /** Every city of the state is covered; null when the screen has no such switch. */
  everyCity: boolean | null;
  /** Listed cities; null while they load. */
  cities: TreeCity[] | null;
  /** Open pre-orders targeting the state (or its cities). */
  dependents: number;
  /** Not saved yet (a state added where nothing exists until its first cities are). */
  draft?: boolean;
}

export interface TreeCountry {
  code: string;
  name: string;
  status: PlaceStatus;
  /** Chip text when the screen's word differs from the tree's (Coming soon, Paused, Not launched…). */
  statusLabel?: string;
  /** What the country calls its regions (State, Province…). */
  regionLabel: string;
  /** States; null until the country is opened (or while they load). */
  regions: TreeRegion[] | null;
  /** Counts shown before the country is opened. */
  summary?: { live: number; planned: number; cities: number; liveCities?: number };
  draft?: boolean;
}

export interface StatusOption {
  value: PlaceStatus;
  label: string;
}

export const DEFAULT_STATUSES: StatusOption[] = [
  { value: 'LIVE', label: 'Live' },
  { value: 'PLANNED', label: 'Planned' },
  { value: 'OFF', label: 'Off' }
];

const lower = (s: string) => s.trim().toLocaleLowerCase('en-US');

export const regionKey = (country: string, region: string) => `${country}/${region}`;
export const cityKey = (country: string, region: string, city: string) => `${country}/${region}/${lower(city)}`;

/** Only what is in use (LIVE or PLANNED) unless switched-off places are asked for. */
export function visible<T extends { status: PlaceStatus }>(nodes: T[], showOff: boolean): T[] {
  return showOff ? nodes : nodes.filter((n) => n.status !== 'OFF');
}

/** How many places a filter hides (the "Show switched-off (3)" count). */
export const hiddenCount = (nodes: { status: PlaceStatus }[]) => nodes.filter((n) => n.status === 'OFF').length;

const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;

/** "6 cities · 5 live", "Every city · 2 listed · 1 live", "No cities yet". */
export function regionSummary(r: TreeRegion): string {
  const cities = r.cities ?? [];
  const live = cities.filter((c) => c.status === 'LIVE').length;
  if (r.everyCity) return cities.length ? `Every city · ${cities.length} listed · ${live} live` : 'Every city';
  if (!cities.length) return 'No cities yet';
  return `${plural(cities.length, 'city', 'cities')} · ${live} live`;
}

/** "5 states live · 1 planned · 14 cities". */
export function countrySummary(c: TreeCountry): string {
  const label = c.regionLabel.toLocaleLowerCase('en-US');
  const s = c.regions
    ? {
        live: c.regions.filter((r) => r.status === 'LIVE').length,
        planned: c.regions.filter((r) => r.status === 'PLANNED').length,
        cities: c.regions.reduce((n, r) => n + (r.cities?.length ?? 0), 0)
      }
    : c.summary;
  if (!s) return '';
  const parts = [`${plural(s.live, label)} live`];
  if (s.planned) parts.push(`${s.planned} planned`);
  parts.push(plural(s.cities, 'city', 'cities'));
  return parts.join(' · ');
}

/** Appends names that are not listed yet (case-insensitive, first spelling wins); listed cities keep their status. */
export function appendCities(existing: TreeCity[], names: string[], status: PlaceStatus): TreeCity[] {
  const seen = new Set(existing.map((c) => lower(c.name)));
  const out = [...existing];
  for (const raw of names) {
    const name = raw.trim();
    if (!name || seen.has(lower(name))) continue;
    seen.add(lower(name));
    out.push({ name, status, dependents: [] });
  }
  return out;
}

/** The new names of an add (what appendCities would append). */
export const newNames = (existing: TreeCity[], names: string[]) => appendCities(existing, names, 'PLANNED').slice(existing.length).map((c) => c.name);

export function withCityStatus(cities: TreeCity[], names: string[], status: PlaceStatus): TreeCity[] {
  const keys = new Set(names.map(lower));
  return cities.map((c) => (keys.has(lower(c.name)) ? { ...c, status } : c));
}

/** Why a city can't be removed (dependency guard), or null. */
export function removalBlock(city: TreeCity): string | null {
  if (!city.dependents.length) return null;
  const n = city.dependents.length;
  return `${city.dependents.join(', ')} ${n > 1 ? 'sell' : 'sells'} here. Switch it off instead of removing it.`;
}

/** Why a state can't be removed, or null. */
export function regionRemovalBlock(r: TreeRegion): string | null {
  if (r.dependents > 0) return `${plural(r.dependents, 'open pre-order')} ${r.dependents > 1 ? 'use' : 'uses'} ${r.name}. Switch it off instead.`;
  const used = (r.cities ?? []).filter((c) => c.dependents.length);
  return used.length ? `${used.map((c) => c.name).join(', ')} ${used.length > 1 ? 'are' : 'is'} used by open pre-orders. Switch ${r.name} off instead.` : null;
}

/** A 412 answer: someone else changed the place since it was loaded. */
export const isVersionConflict = (e: unknown): boolean => isApiError(e) && e.status === 412;

export const statusLabel = (options: StatusOption[], s: PlaceStatus) => (options.find((o) => o.value === s) ?? DEFAULT_STATUSES.find((o) => o.value === s))?.label ?? s;
