'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import { type PreorderRegion, type PreorderRegionInput } from '@afrotransact/api-client/staff-preorders';
import { cx, useToast } from '@afrotransact/ui';
import { MagnifyingGlass, PencilSimple, Warning, X } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';

import { NO_EDIT_TITLE } from './model';
import { greenButton, selectClass, smallButton, smallLabelClass, Toggle, usePreordersApi } from './primitives';

/** How many matching cities the picker lists at once (a state can have 1,000+). */
const SHOWN = 200;

interface Draft {
  everyCity: boolean | null;
  /** City name → on, in the order they were added. */
  cities: Map<string, boolean>;
}

const draftOf = (r: PreorderRegion): Draft => ({ everyCity: r.wholeRegion ? r.wholeRegion.on : null, cities: new Map(r.cities.map((c) => [c.name, c.on])) });
const lower = (s: string) => s.toLocaleLowerCase('en-US');

/** The body of the region PUT: what the editor shows (cities left out are removed). */
export const regionInput = (d: Draft): PreorderRegionInput => ({ everyCity: d.everyCity, cities: [...d.cities].map(([city, on]) => ({ city, on })) });

/**
 * Edit one state's / province's pre-order coverage in place: add or remove cities (searched from geo's full city list,
 * multi-select, "select all"), switch each on or off, and cover every city of the state. Saving sends the version it
 * loaded; a newer change by someone else is reported, not overwritten. Cities a live pre-order sells to can be switched
 * off but not removed.
 */
export function RegionCoverageEditor({ country, region, canEdit, onClose }: { country: string; region: string; canEdit: boolean; onClose: () => void }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const loaded = useQuery(staffPreorderQueries.region(api, country, region));
  const geo = useQuery(staffPreorderQueries.places(api, { country, region }));
  const [draft, setDraft] = useState<Draft | null>(null);
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [conflict, setConflict] = useState<string | null>(null);
  useEffect(() => {
    if (loaded.data && !draft) setDraft(draftOf(loaded.data));
  }, [loaded.data, draft]);
  const save = useMutation({
    mutationFn: ({ d, version }: { d: Draft; version: number }) => api.updateRegion(country, region, regionInput(d), version),
    onSuccess: (next) => {
      qc.setQueryData(staffPreorderKeys.region(country, region), next);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
      setDraft(draftOf(next));
      setConflict(null);
      toast.show(`Saved ${next.regionName}: ${next.cities.length} ${next.cities.length === 1 ? 'city' : 'cities'}${next.wholeRegion ? ` + every city (${next.wholeRegion.on ? 'on' : 'off'})` : ''}`);
    },
    onError: (e) => {
      const msg = problemMessage(e);
      setConflict(msg);
      toast.show(msg, 'error');
    }
  });
  const sellers = useMemo(() => new Map((loaded.data?.cities ?? []).map((c) => [lower(c.name), c.windows])), [loaded.data]);
  const geoCities = useMemo(() => (geo.data?.items ?? []).filter((p) => p.type === 'CITY').map((p) => p.name), [geo.data]);
  const listed = useMemo(() => new Set([...(draft?.cities.keys() ?? [])].map(lower)), [draft]);
  const matches = useMemo(() => {
    const t = lower(q.trim());
    return geoCities.filter((c) => !listed.has(lower(c)) && (!t || lower(c).includes(t)));
  }, [geoCities, listed, q]);
  if (loaded.isPending || !draft) return <Spinner />;
  if (loaded.isError) return <ErrorState error={loaded.error} onRetry={() => void loaded.refetch()} />;
  const r = loaded.data;
  const dirty = JSON.stringify(regionInput(draft)) !== JSON.stringify(regionInput(draftOf(r)));
  const update = (fn: (d: Draft) => Draft) => setDraft((d) => (d ? fn({ everyCity: d.everyCity, cities: new Map(d.cities) }) : d));
  const addCities = (names: string[]) => {
    update((d) => {
      names.forEach((n) => {
        if (![...d.cities.keys()].some((k) => lower(k) === lower(n))) d.cities.set(n, true);
      });
      return d;
    });
    setPicked(new Set());
    setQ('');
  };
  const shown = matches.slice(0, SHOWN);
  const exact = q.trim() && !geoCities.some((c) => lower(c) === lower(q.trim())) && !listed.has(lower(q.trim())) && !q.includes('/') ? q.trim() : null;
  return (
    <section aria-label={`Edit cities in ${r.regionName}`} className="flex flex-col gap-3 rounded-md border border-gold-400 bg-gold-50 p-3" data-testid="region-editor">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="m-0 text-sm font-bold">{`${r.regionName} · ${r.countryName}`}</h4>
        <span className="text-xs text-muted">{`${draft.cities.size} ${draft.cities.size === 1 ? 'city' : 'cities'} listed`}</span>
        <span className="flex-1" />
        <button type="button" onClick={onClose} aria-label="Close the city editor" className="size-7 cursor-pointer rounded-sm border-0 bg-transparent text-ink-500">
          <X aria-hidden />
        </button>
      </div>
      <fieldset disabled={!canEdit} title={canEdit ? undefined : NO_EDIT_TITLE} className="m-0 flex flex-col gap-3 border-0 p-0">
        <div className="flex flex-wrap items-center gap-3 rounded-sm bg-ink-0 p-2">
          <label className="flex items-center gap-2 text-sm font-semibold">
            <input
              type="checkbox"
              checked={draft.everyCity !== null}
              onChange={(e) => {
                const checked = e.currentTarget.checked;
                update((d) => ({ ...d, everyCity: checked ? true : null }));
              }}
            />
            {`Cover every city in ${r.regionName}`}
          </label>
          {draft.everyCity !== null ? (
            <span className="flex items-center gap-2">
              <Toggle on={draft.everyCity} size="sm" label={`Switch every city in ${r.regionName} on or off`} onClick={() => update((d) => ({ ...d, everyCity: !d.everyCity }))} />
              <span className="text-2xs text-muted">{draft.everyCity ? 'On' : 'Off'}</span>
            </span>
          ) : null}
          <span className="text-2xs text-muted">Listed cities override it (e.g. keep one city off).</span>
        </div>
        <div className="flex flex-col gap-2">
          <label className="flex h-9 max-w-[420px] items-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-2">
            <MagnifyingGlass aria-hidden className="text-ink-500" />
            <input
              value={q}
              onChange={(e) => setQ(e.currentTarget.value)}
              placeholder={geo.data ? `Search ${geoCities.length.toLocaleString('en-US')} cities in ${r.regionName}` : 'Loading cities…'}
              aria-label={`Search cities in ${r.regionName}`}
              className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
            />
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className={smallButton} disabled={!picked.size} onClick={() => addCities([...picked])}>
              {`Add ${picked.size || ''} selected`.replace('  ', ' ')}
            </button>
            <button type="button" className={smallButton} disabled={!matches.length} onClick={() => addCities(matches)}>
              {q.trim() ? `Select all ${matches.length.toLocaleString('en-US')} matching` : `Select all ${matches.length.toLocaleString('en-US')} cities in ${r.regionName}`}
            </button>
            {exact ? (
              <button type="button" className={smallButton} onClick={() => addCities([exact])}>
                {`Add “${exact}”`}
              </button>
            ) : null}
            {matches.length > SHOWN ? <span className="text-2xs text-muted">{`Showing ${SHOWN} of ${matches.length.toLocaleString('en-US')}; search to narrow`}</span> : null}
          </div>
          <ul aria-label="Cities to add" className="m-0 grid max-h-48 list-none grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-3 gap-y-1 overflow-auto rounded-sm border border-border bg-ink-0 p-2">
            {shown.map((c) => (
              <li key={c}>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={picked.has(c)}
                    onChange={() =>
                      setPicked((s) => {
                        const n = new Set(s);
                        if (n.has(c)) n.delete(c);
                        else n.add(c);
                        return n;
                      })
                    }
                  />
                  {c}
                </label>
              </li>
            ))}
            {geo.data && !shown.length ? <li className="text-xs text-muted">{q.trim() ? `No other city matches “${q.trim()}”.` : 'Every city is listed.'}</li> : null}
          </ul>
        </div>
        <ul aria-label={`Cities in ${r.regionName}`} className="m-0 flex list-none flex-col divide-y divide-border rounded-sm border border-border bg-ink-0 p-0">
          {[...draft.cities].map(([city, on]) => {
            const windows = sellers.get(lower(city)) ?? [];
            return (
              <li key={city} className="flex flex-wrap items-center gap-2 px-3 py-1.5" data-testid="region-city">
                <span className={cx('min-w-[140px] flex-1 text-sm font-semibold', !on && 'line-through')}>{city}</span>
                {windows.length ? (
                  <span className="flex items-center gap-1 text-2xs text-gold-900">
                    <Warning aria-hidden />
                    {`${windows.join(', ')} ${windows.length > 1 ? 'sell' : 'sells'} here: switch off instead of removing`}
                  </span>
                ) : null}
                <Toggle on={on} size="sm" label={`Switch ${city} on or off`} onClick={() => update((d) => (d.cities.set(city, !on), d))} />
                <span className="w-6 text-2xs text-muted">{on ? 'On' : 'Off'}</span>
                <button
                  type="button"
                  aria-label={`Remove ${city}`}
                  title={windows.length ? 'A live pre-order sells here; switch it off instead' : undefined}
                  disabled={windows.length > 0}
                  onClick={() => update((d) => (d.cities.delete(city), d))}
                  className="size-7 cursor-pointer rounded-sm border-0 bg-transparent text-ink-500 disabled:cursor-not-allowed disabled:opacity-45"
                >
                  <X aria-hidden />
                </button>
              </li>
            );
          })}
          {!draft.cities.size ? <li className="px-3 py-2 text-xs text-muted">No cities listed yet. Search above and add them.</li> : null}
        </ul>
        {conflict ? (
          <p role="alert" className="m-0 flex flex-wrap items-center gap-2 text-sm text-red-700">
            {conflict}
            <button
              type="button"
              className={smallButton}
              onClick={() => {
                setDraft(null);
                setConflict(null);
                void loaded.refetch();
              }}
            >
              Reload
            </button>
          </p>
        ) : null}
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={greenButton} disabled={!dirty || save.isPending} onClick={() => save.mutate({ d: draft, version: r.version })}>
            Save cities
          </button>
          <button type="button" className={smallButton} disabled={!dirty} onClick={() => setDraft(draftOf(r))}>
            Discard changes
          </button>
        </div>
      </fieldset>
    </section>
  );
}

/** Pick a country and a state / province to edit its cities (also how a new state gets its first cities). */
export function RegionEditorLauncher({ onOpen, disabled }: { onOpen: (country: string, region: string) => void; disabled?: boolean }) {
  const api = usePreordersApi();
  const [c, setC] = useState('US');
  const [r, setR] = useState('');
  const countries = useQuery(staffPreorderQueries.places(api, {}));
  const regions = useQuery(staffPreorderQueries.places(api, { country: c }));
  const label = regions.data?.regionLabel ?? 'State';
  return (
    <div className="flex max-w-[760px] flex-wrap items-end gap-2 [&>label]:min-w-[150px] [&>label]:flex-1">
      <label className={smallLabelClass}>
        Country
        <select
          value={c}
          onChange={(e) => {
            setC(e.currentTarget.value);
            setR('');
          }}
          className={selectClass}
          aria-label="Country to edit"
        >
          {(countries.data?.items ?? [{ code: 'US', name: 'United States', key: 'US' }]).map((o) => (
            <option key={o.key} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <label className={smallLabelClass}>
        {label}
        <select value={r} onChange={(e) => setR(e.currentTarget.value)} className={selectClass} aria-label={`${label} to edit`}>
          <option value="">{regions.data ? `Choose… (${regions.data.items.length})` : 'Loading…'}</option>
          {(regions.data?.items ?? []).map((o) => (
            <option key={o.key} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className={greenButton} disabled={!r || disabled} onClick={() => onOpen(c, r)}>
        <PencilSimple aria-hidden />
        Edit cities
      </button>
    </div>
  );
}
