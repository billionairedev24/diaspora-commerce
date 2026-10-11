'use client';

import { staffPlaceQueries } from '@afrotransact/api-client';
import { type StaffMarketsApi, type StaffPlace } from '@afrotransact/api-client/staff-markets';
import { cx } from '@afrotransact/ui';
import { MagnifyingGlass, Plus, X } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useMemo, useRef, useState } from 'react';

import { useDebounced } from '@/lib/use-debounced';

const lower = (s: string) => s.trim().toLocaleLowerCase('en-US');
/** How many matches a picker lists at once (a state can have 1,000+ cities: search narrows). */
const SHOWN = 60;

/** Focuses the picker's search field when it opens in place. */
function useFocusOnOpen() {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return ref;
}

export const treeButton =
  'inline-flex min-h-11 cursor-pointer items-center justify-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-3 text-sm font-semibold text-text disabled:cursor-not-allowed disabled:opacity-45 sm:min-h-8 sm:text-xs';
export const treePrimary =
  'inline-flex min-h-11 cursor-pointer items-center justify-center gap-1 rounded-sm border-0 bg-green-600 px-3 text-sm font-bold text-ink-0 disabled:cursor-not-allowed disabled:opacity-45 sm:min-h-8 sm:text-xs';

type PlacesApi = Pick<StaffMarketsApi, 'places'>;

/**
 * Inline single-choice picker (a country or a state) searched from geo's gazetteer, shown in place in the tree:
 * type to filter, pick one and it is added straight away. Places already in use are left out.
 */
export function PlacePicker({
  api,
  country,
  label,
  exclude,
  onPick,
  onClose,
  busy
}: {
  api: PlacesApi;
  country?: string;
  label: string;
  exclude: (code: string) => boolean;
  onPick: (place: StaffPlace) => void;
  onClose: () => void;
  busy?: boolean;
}) {
  const id = useId();
  const input = useFocusOnOpen();
  const [q, setQ] = useState('');
  const list = useQuery(staffPlaceQueries.list(api, { country }));
  const matches = useMemo(() => {
    const t = lower(q);
    return (list.data?.items ?? []).filter((p) => !exclude(p.code) && (!t || lower(p.name).includes(t) || lower(p.code) === t));
  }, [list.data, q, exclude]);
  return (
    <div className="flex flex-col gap-2 rounded-md border border-dashed border-border-strong bg-ink-25 p-3" data-testid="place-picker">
      <div className="flex items-center gap-2">
        <label htmlFor={id} className="text-sm font-semibold">
          {label}
        </label>
        <span className="flex-1" />
        <button type="button" onClick={onClose} aria-label={`Close: ${label}`} className="inline-flex size-11 cursor-pointer items-center justify-center rounded-sm border-0 bg-transparent text-ink-500 sm:size-8">
          <X aria-hidden />
        </button>
      </div>
      <span className="flex min-h-11 items-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-2 sm:min-h-9">
        <MagnifyingGlass aria-hidden className="text-ink-500" />
        <input
          id={id}
          ref={input}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onClose();
            if (e.key === 'Enter' && matches[0]) {
              e.preventDefault();
              onPick(matches[0]);
            }
          }}
          placeholder={list.data ? `Search ${list.data.items.length.toLocaleString('en-US')} ${list.data.label === 'Country' ? 'countries' : `${lower(list.data.label)}s`}` : 'Loading…'}
          className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
          role="combobox"
          aria-expanded={matches.length > 0}
          aria-controls={`${id}-list`}
          aria-autocomplete="list"
        />
      </span>
      <ul id={`${id}-list`} role="listbox" aria-label={label} className="m-0 flex max-h-64 list-none flex-col overflow-auto p-0">
        {matches.slice(0, SHOWN).map((p) => (
          <li key={p.code} role="option" aria-selected={false}>
            <button
              type="button"
              disabled={busy}
              onClick={() => onPick(p)}
              className="flex min-h-11 w-full cursor-pointer items-center gap-2 border-0 border-b border-border bg-transparent px-2 text-left text-sm disabled:opacity-45 sm:min-h-9"
            >
              <Plus aria-hidden className="text-green-700" />
              <span className="flex-1">{p.name}</span>
              <span className="font-mono text-2xs text-subtle">{p.code}</span>
            </button>
          </li>
        ))}
        {list.data && !matches.length ? <li className="px-2 py-2 text-xs text-muted">{q ? `Nothing matches “${q.trim()}”.` : 'Every one is already in use.'}</li> : null}
      </ul>
    </div>
  );
}

/**
 * Inline multi-select city search for one state (geo's cities), appending to what is listed: tick several, add the
 * ticked ones, add every match, or add a typed name that geo doesn't know. Listed cities are left out.
 */
export function CityAdder({
  api,
  country,
  region,
  regionName,
  listed,
  onAdd,
  onEveryCity,
  everyCity,
  onClose,
  busy,
  hint
}: {
  api: PlacesApi;
  country: string;
  region: string;
  regionName: string;
  listed: string[];
  onAdd: (names: string[]) => void;
  /** Cover every city of the state instead of listing them (null when the screen has no such switch). */
  onEveryCity: (() => void) | null;
  everyCity: boolean;
  onClose: () => void;
  busy?: boolean;
  hint?: string;
}) {
  const id = useId();
  const input = useFocusOnOpen();
  const [q, setQ] = useState('');
  const dq = useDebounced(q.trim(), 200);
  const [picked, setPicked] = useState<string[]>([]);
  const list = useQuery(staffPlaceQueries.list(api, { country, region, q: dq || undefined, limit: 500 }));
  const have = useMemo(() => new Set(listed.map(lower)), [listed]);
  const matches = useMemo(() => (list.data?.items ?? []).map((p) => p.name).filter((n) => !have.has(lower(n))), [list.data, have]);
  const typed = q.trim();
  const unknown = typed && !q.includes('/') && typed.length <= 80 && !have.has(lower(typed)) && !matches.some((m) => lower(m) === lower(typed)) ? typed : null;
  const toggle = (n: string) => setPicked((cur) => (cur.includes(n) ? cur.filter((x) => x !== n) : [...cur, n]));
  const add = (names: string[]) => {
    if (!names.length) return;
    onAdd(names);
    setPicked([]);
    setQ('');
  };
  return (
    <div className="flex flex-col gap-2 rounded-md border border-dashed border-border-strong bg-ink-25 p-3" data-testid="city-adder">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={id} className="text-sm font-semibold">{`Add cities to ${regionName}`}</label>
        {hint ? <span className="text-xs text-muted">{hint}</span> : null}
        <span className="flex-1" />
        <button type="button" onClick={onClose} aria-label={`Close: add cities to ${regionName}`} className="inline-flex size-11 cursor-pointer items-center justify-center rounded-sm border-0 bg-transparent text-ink-500 sm:size-8">
          <X aria-hidden />
        </button>
      </div>
      <span className="flex min-h-11 items-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-2 sm:min-h-9">
        <MagnifyingGlass aria-hidden className="text-ink-500" />
        <input
          id={id}
          ref={input}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onClose();
            if (e.key === 'Enter') {
              e.preventDefault();
              add(picked.length ? picked : unknown ? [unknown] : matches.slice(0, 1));
            }
          }}
          placeholder={list.data ? `Search ${list.data.total.toLocaleString('en-US')} cities in ${regionName}` : 'Loading cities…'}
          className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
        />
      </span>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className={treePrimary} disabled={busy || !picked.length} onClick={() => add(picked)}>
          {picked.length ? `Add ${picked.length} ${picked.length === 1 ? 'city' : 'cities'}` : 'Add selected'}
        </button>
        <button type="button" className={treeButton} disabled={busy || !matches.length} onClick={() => add(matches)}>
          {dq ? `Add all ${matches.length.toLocaleString('en-US')} matching` : `Add all ${matches.length.toLocaleString('en-US')} listed below`}
        </button>
        {unknown ? (
          <button type="button" className={treeButton} disabled={busy} onClick={() => add([unknown])}>
            {`Add “${unknown}”`}
          </button>
        ) : null}
        {onEveryCity && !everyCity ? (
          <button type="button" className={treeButton} disabled={busy} onClick={onEveryCity}>
            {`Cover every city in ${regionName}`}
          </button>
        ) : null}
      </div>
      <ul aria-label={`Cities you can add to ${regionName}`} className="m-0 grid max-h-56 list-none grid-cols-1 gap-x-3 overflow-auto rounded-sm border border-border bg-ink-0 p-1 sm:grid-cols-[repeat(auto-fill,minmax(170px,1fr))]">
        {matches.slice(0, 200).map((n) => (
          <li key={n}>
            <label className={cx('flex min-h-11 cursor-pointer items-center gap-2 px-2 text-sm sm:min-h-8', picked.includes(n) && 'font-semibold')}>
              <input type="checkbox" className="size-5 sm:size-4" checked={picked.includes(n)} onChange={() => toggle(n)} />
              {n}
            </label>
          </li>
        ))}
        {list.data && !matches.length ? <li className="px-2 py-2 text-xs text-muted">{dq ? `No other city matches “${dq}”.` : 'Every city is listed.'}</li> : null}
      </ul>
    </div>
  );
}
