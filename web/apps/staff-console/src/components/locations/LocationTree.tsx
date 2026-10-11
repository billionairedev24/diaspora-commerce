'use client';

import { type StaffMarketsApi, type StaffPlace } from '@afrotransact/api-client/staff-markets';
import { cx, useToast } from '@afrotransact/ui';
import { CaretDown, CaretRight, Plus, Trash, Warning } from '@phosphor-icons/react/ssr';
import { type KeyboardEvent, type ReactNode, useCallback, useMemo, useRef, useState } from 'react';

import { problemMessage, Spinner } from '@/components/ui/States';

import { CityAdder, PlacePicker, treeButton } from './PlacePicker';
import {
  appendCities,
  cityKey,
  countrySummary,
  hiddenCount,
  isVersionConflict,
  newNames,
  type PlaceStatus,
  regionKey,
  regionRemovalBlock,
  regionSummary,
  removalBlock,
  statusLabel,
  type StatusOption,
  type TreeCity,
  type TreeCountry,
  type TreeRegion,
  visible,
  withCityStatus
} from './tree-model';

/** The edits a screen supports. Each resolves once the server answered and the screen's data shows the result. */
export interface TreeOps {
  setCountryStatus?: (country: TreeCountry, status: PlaceStatus) => Promise<unknown>;
  addCountry?: (place: StaffPlace) => Promise<unknown>;
  addRegion: (country: TreeCountry, place: StaffPlace) => Promise<unknown>;
  setRegionStatus: (country: TreeCountry, region: TreeRegion, status: PlaceStatus) => Promise<unknown>;
  /** Removes a state outright (screens without an OFF state switch remove instead). */
  removeRegion?: (country: TreeCountry, region: TreeRegion) => Promise<unknown>;
  /** Restores a removed state (undo). */
  restoreRegion?: (country: TreeCountry, region: TreeRegion) => Promise<unknown>;
  setEveryCity?: (country: TreeCountry, region: TreeRegion, on: boolean) => Promise<unknown>;
  addCities: (country: TreeCountry, region: TreeRegion, cities: { name: string; status: PlaceStatus }[]) => Promise<unknown>;
  setCityStatus: (country: TreeCountry, region: TreeRegion, names: string[], status: PlaceStatus) => Promise<unknown>;
  removeCity: (country: TreeCountry, region: TreeRegion, city: TreeCity) => Promise<unknown>;
  /** Reloads after someone else changed a place (412). */
  refresh: () => Promise<unknown> | void;
}

export interface LocationTreeProps {
  /** Accessible name of the tree. */
  label: string;
  countries: TreeCountry[] | undefined;
  statuses: { country?: StatusOption[]; region: StatusOption[]; city: StatusOption[] };
  /** Status of cities added inline. */
  newCityStatus: PlaceStatus;
  canEdit: boolean;
  readOnlyNote?: string;
  ops: TreeOps;
  places: Pick<StaffMarketsApi, 'places'>;
  /** Open countries ("US") and states ("US/TX"); the screen loads what an open node needs. */
  expanded: Set<string>;
  onExpand: (key: string, open: boolean) => void;
  /** Extra controls of an open state (e.g. Markets' capability switches). */
  regionExtra?: (country: TreeCountry, region: TreeRegion) => ReactNode;
  /** Shown under the state name while it is open (e.g. how the coverage works). */
  coverageHint?: (country: TreeCountry, region: TreeRegion) => string | null;
  /** Shown above the tree (e.g. Markets' publish bar). */
  toolbar?: ReactNode;
  emptyText?: string;
}

type Patch = (cs: TreeCountry[]) => TreeCountry[];

const mapCountry = (cs: TreeCountry[], code: string, fn: (c: TreeCountry) => TreeCountry) => cs.map((c) => (c.code === code ? fn(c) : c));
const mapRegion = (cs: TreeCountry[], country: string, region: string, fn: (r: TreeRegion) => TreeRegion) =>
  mapCountry(cs, country, (c) => ({ ...c, regions: c.regions ? c.regions.map((r) => (r.code === region ? fn(r) : r)) : c.regions }));

const CHIP: Record<PlaceStatus, string> = {
  LIVE: 'bg-green-50 text-green-800',
  PLANNED: 'bg-gold-100 text-gold-900',
  OFF: 'bg-ink-100 text-ink-700'
};

function Chip({ status, children }: { status: PlaceStatus; children: ReactNode }) {
  return <span className={cx('inline-flex items-center rounded-pill px-2 py-0.5 text-2xs font-bold whitespace-nowrap', CHIP[status])}>{children}</span>;
}

/** Live / Planned / Off as a labelled radio group (arrow keys move within it, not through the tree). */
export function StatusSwitch({
  label,
  value,
  options,
  onChange,
  disabled
}: {
  label: string;
  value: PlaceStatus;
  options: StatusOption[];
  onChange: (s: PlaceStatus) => void;
  disabled?: boolean;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const at = Math.max(0, options.findIndex((o) => o.value === value));
  const move = (e: KeyboardEvent<HTMLButtonElement>) => {
    const d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    e.stopPropagation();
    if (disabled) return;
    const next = (at + d + options.length) % options.length;
    const o = options[next];
    if (o) {
      onChange(o.value);
      refs.current[next]?.focus();
    }
  };
  return (
    <div role="radiogroup" aria-label={label} aria-disabled={disabled || undefined} className="inline-flex flex-none overflow-hidden rounded-sm border border-border-strong bg-ink-0">
      {options.map((o, i) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            disabled={disabled}
            onKeyDown={move}
            onClick={() => !on && onChange(o.value)}
            className={cx(
              'min-h-11 min-w-[64px] cursor-pointer border-0 px-3 text-sm font-semibold disabled:cursor-not-allowed sm:min-h-8 sm:min-w-[56px] sm:text-xs',
              i > 0 && 'border-l border-border-strong',
              on ? (o.value === 'LIVE' ? 'bg-green-600 text-ink-0' : o.value === 'PLANNED' ? 'bg-gold-400 text-ink-1000' : 'bg-ink-700 text-ink-0') : 'bg-ink-0 text-ink-700',
              disabled && !on && 'opacity-45'
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

/** Which tree nodes are open: countries ("US") and states ("US/TX"). */
export function useTreeExpansion(initial: string[] = []): [Set<string>, (key: string, open: boolean) => void] {
  const [open, setOpen] = useState(() => new Set(initial));
  const set = useCallback((key: string, on: boolean) => {
    setOpen((cur) => {
      if (cur.has(key) === on) return cur;
      const next = new Set(cur);
      if (on) next.add(key);
      else next.delete(key);
      return next;
    });
  }, []);
  return [open, set];
}

interface Row {
  id: string;
  level: 1 | 2 | 3;
  parent: string | null;
  expandable: boolean;
}

/**
 * Countries → states → cities, showing only what is live or planned (switched-off places behind a toggle). Every
 * edit happens in place and saves on its own: a status switch, adding states and cities from the gazetteer (cities
 * append to the list), covering a whole state, bulk switching, removing with undo. Edits show at once (optimistic) and
 * are rolled back with the server's reason when refused; a version conflict reloads with a clear message.
 * Keyboard: ↑ ↓ move, → opens / goes in, ← closes / goes out, Home / End, Enter or Space opens.
 */
export function LocationTree(props: LocationTreeProps) {
  const { label, countries, statuses, newCityStatus, canEdit, readOnlyNote, ops, places, expanded, onExpand, regionExtra, coverageHint, toolbar, emptyText } = props;
  const toast = useToast();
  const [showOff, setShowOff] = useState(false);
  const [patches, setPatches] = useState<{ id: number; fn: Patch }[]>([]);
  const seq = useRef(0);
  const [adding, setAdding] = useState<string | null>(null);
  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const [focusId, setFocusId] = useState<string | null>(null);
  const rowRefs = useRef(new Map<string, HTMLLIElement>());

  const shown = useMemo(() => (countries ? patches.reduce((cs, p) => p.fn(cs), countries) : undefined), [countries, patches]);

  /** Applies a patch at once, runs the edit, then drops the patch (the screen's data has the result by then). */
  const run = useCallback(
    async (patch: Patch, op: () => Promise<unknown>, done?: string | { text: string; undo: () => void }): Promise<boolean> => {
      seq.current += 1;
      const id = seq.current;
      setPatches((cur) => [...cur, { id, fn: patch }]);
      try {
        await op();
        if (typeof done === 'string') toast.show(done);
        else if (done) toast.show(done.text, 'info', { label: 'Undo', onClick: done.undo });
        return true;
      } catch (e) {
        if (isVersionConflict(e)) {
          toast.show(`${problemMessage(e)} We reloaded the latest; check it and try again.`, 'error');
          await ops.refresh();
        } else {
          toast.show(problemMessage(e), 'error');
        }
        return false;
      } finally {
        setPatches((cur) => cur.filter((p) => p.id !== id));
      }
    },
    [ops, toast]
  );

  const countryOptions = statuses.country;

  /* ── edits ── */
  const setCountryStatus = (c: TreeCountry, s: PlaceStatus) =>
    ops.setCountryStatus && void run((cs) => mapCountry(cs, c.code, (x) => ({ ...x, status: s, statusLabel: undefined })), () => ops.setCountryStatus?.(c, s) ?? Promise.resolve(), `${c.name}: ${statusLabel(countryOptions ?? [], s)}`);
  const addCountry = (p: StaffPlace) => {
    setAdding(null);
    void run((cs) => (cs.some((c) => c.code === p.code) ? cs : [...cs, { code: p.code, name: p.name, status: 'PLANNED', regionLabel: p.label ?? 'State', regions: [], draft: true }]), () => ops.addCountry?.(p) ?? Promise.resolve(), `${p.name} added as planned. Now add its states.`).then((ok) => {
      if (ok) onExpand(p.code, true);
    });
  };
  const addRegion = (c: TreeCountry, p: StaffPlace) => {
    setAdding(null);
    const rk = regionKey(c.code, p.code);
    void run(
      (cs) =>
        mapCountry(cs, c.code, (x) => ({
          ...x,
          regions: [...(x.regions ?? []).filter((r) => r.code !== p.code), { code: p.code, name: p.name, status: 'PLANNED' as const, everyCity: x.regions === null ? null : false, cities: [], dependents: 0 }].sort((a, b) => a.name.localeCompare(b.name))
        })),
      () => ops.addRegion(c, p),
      `${p.name} added as planned. Now add its cities.`
    ).then((ok) => {
      if (!ok) return;
      onExpand(rk, true);
      setAdding(`cities:${rk}`);
    });
  };
  const setRegionStatus = (c: TreeCountry, r: TreeRegion, s: PlaceStatus) => {
    const before = r.status;
    const hides = s === 'OFF' && !showOff;
    void run(
      (cs) => mapRegion(cs, c.code, r.code, (x) => ({ ...x, status: s, cities: s === 'LIVE' || !x.cities ? x.cities : x.cities.map((y) => (y.status === 'LIVE' ? { ...y, status: 'PLANNED' } : y)) })),
      () => ops.setRegionStatus(c, r, s),
      hides ? { text: `${r.name} switched off`, undo: () => void run((cs) => cs, () => ops.setRegionStatus(c, { ...r, status: 'OFF' }, before), `${r.name} is back`) } : `${r.name}: ${statusLabel(statuses.region, s)}`
    );
  };
  const removeRegion = (c: TreeCountry, r: TreeRegion) => {
    const block = regionRemovalBlock(r);
    if (block) {
      toast.show(block, 'error', r.status !== 'OFF' && statuses.region.some((o) => o.value === 'OFF') ? { label: 'Switch off', onClick: () => setRegionStatus(c, r, 'OFF') } : undefined);
      return;
    }
    if (ops.removeRegion) {
      void run(
        (cs) => mapCountry(cs, c.code, (x) => ({ ...x, regions: (x.regions ?? []).filter((y) => y.code !== r.code) })),
        () => ops.removeRegion?.(c, r) ?? Promise.resolve(),
        { text: `${r.name} removed`, undo: () => void run((cs) => cs, () => ops.restoreRegion?.(c, r) ?? Promise.resolve(), `${r.name} is back`) }
      );
    } else {
      setRegionStatus(c, r, 'OFF');
    }
  };
  const setEveryCity = (c: TreeCountry, r: TreeRegion, on: boolean) =>
    ops.setEveryCity &&
    void run((cs) => mapRegion(cs, c.code, r.code, (x) => ({ ...x, everyCity: on })), () => ops.setEveryCity?.(c, r, on) ?? Promise.resolve(), on ? `Every city in ${r.name} is covered` : `${r.name}: only the listed cities`);
  const addCities = (c: TreeCountry, r: TreeRegion, names: string[], status = newCityStatus) => {
    const fresh = newNames(r.cities ?? [], names);
    if (!fresh.length) {
      toast.show('Those cities are already listed.');
      return;
    }
    void run(
      (cs) => mapRegion(cs, c.code, r.code, (x) => ({ ...x, cities: appendCities(x.cities ?? [], fresh, status) })),
      () => ops.addCities(c, r, fresh.map((name) => ({ name, status }))),
      `${fresh.length === 1 ? fresh[0] : `${fresh.length} cities`} added to ${r.name} as ${statusLabel(statuses.city, status).toLocaleLowerCase('en-US')}`
    );
  };
  const setCityStatus = (c: TreeCountry, r: TreeRegion, names: string[], s: PlaceStatus) => {
    const rk = regionKey(c.code, r.code);
    void run(
      (cs) => mapRegion(cs, c.code, r.code, (x) => ({ ...x, cities: withCityStatus(x.cities ?? [], names, s) })),
      () => ops.setCityStatus(c, r, names, s),
      `${names.length === 1 ? names[0] : `${names.length} cities`}: ${statusLabel(statuses.city, s)}`
    ).then((ok) => {
      if (ok && names.length > 1) setSelected((cur) => ({ ...cur, [rk]: [] }));
    });
  };
  const removeCity = (c: TreeCountry, r: TreeRegion, city: TreeCity) => {
    const block = removalBlock(city);
    if (block) {
      toast.show(`${city.name}: ${block}`, 'error', city.status !== 'OFF' ? { label: 'Switch off', onClick: () => setCityStatus(c, r, [city.name], 'OFF') } : undefined);
      return;
    }
    void run(
      (cs) => mapRegion(cs, c.code, r.code, (x) => ({ ...x, cities: (x.cities ?? []).filter((y) => y.name !== city.name) })),
      () => ops.removeCity(c, r, city),
      { text: `${city.name} removed from ${r.name}`, undo: () => addCities(c, { ...r, cities: (r.cities ?? []).filter((y) => y.name !== city.name) }, [city.name], city.status) }
    );
  };

  /* ── keyboard navigation over the visible rows ── */
  const rows = useMemo(() => {
    const out: Row[] = [];
    for (const c of visible(shown ?? [], showOff)) {
      out.push({ id: c.code, level: 1, parent: null, expandable: true });
      if (!expanded.has(c.code)) continue;
      for (const r of visible(c.regions ?? [], showOff)) {
        const rk = regionKey(c.code, r.code);
        out.push({ id: rk, level: 2, parent: c.code, expandable: true });
        if (!expanded.has(rk)) continue;
        for (const city of visible(r.cities ?? [], showOff)) out.push({ id: cityKey(c.code, r.code, city.name), level: 3, parent: rk, expandable: false });
      }
    }
    return out;
  }, [shown, showOff, expanded]);
  const current = focusId && rows.some((r) => r.id === focusId) ? focusId : (rows[0]?.id ?? null);
  const focusRow = (id: string | null | undefined) => {
    if (!id) return;
    setFocusId(id);
    rowRefs.current.get(id)?.focus();
  };
  const onTreeKey = (e: KeyboardEvent<HTMLUListElement>) => {
    const el = e.target as HTMLElement;
    if (el.getAttribute('role') !== 'treeitem') return;
    const id = el.dataset.treeId ?? '';
    const i = rows.findIndex((r) => r.id === id);
    const row = rows[i];
    if (!row) return;
    const open = expanded.has(id);
    const key = e.key;
    if (key === 'ArrowDown') focusRow(rows[i + 1]?.id);
    else if (key === 'ArrowUp') focusRow(rows[i - 1]?.id);
    else if (key === 'Home') focusRow(rows[0]?.id);
    else if (key === 'End') focusRow(rows[rows.length - 1]?.id);
    else if (key === 'ArrowRight') {
      if (row.expandable && !open) onExpand(id, true);
      else if (row.expandable) focusRow(rows[i + 1]?.parent === id ? rows[i + 1]?.id : undefined);
    } else if (key === 'ArrowLeft') {
      if (row.expandable && open) onExpand(id, false);
      else focusRow(row.parent);
    } else if ((key === 'Enter' || key === ' ') && row.expandable) onExpand(id, !open);
    else return;
    e.preventDefault();
  };
  const treeItem = (id: string, level: 1 | 2 | 3, name: string, extra: Record<string, unknown> = {}) => ({
    role: 'treeitem' as const,
    'aria-level': level,
    'aria-label': name,
    'data-tree-id': id,
    tabIndex: current === id ? 0 : -1,
    onFocus: (e: { target: EventTarget; currentTarget: EventTarget }) => {
      if (e.target === e.currentTarget) setFocusId(id);
    },
    ref: (el: HTMLLIElement | null) => {
      if (el) rowRefs.current.set(id, el);
      else rowRefs.current.delete(id);
    },
    ...extra
  });

  if (!shown) return <Spinner />;
  const topHidden = hiddenCount(shown);
  const inUse = (code: string) => shown.some((c) => c.code === code && c.status !== 'OFF');

  const caret = (open: boolean) => (open ? <CaretDown aria-hidden /> : <CaretRight aria-hidden />);
  const rowClass = 'flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2 outline-none';
  const removeButton = (labelText: string, onClick: () => void, block: string | null) => (
    <button
      type="button"
      onClick={onClick}
      disabled={!canEdit}
      aria-label={labelText}
      title={block ?? labelText}
      className={cx('inline-flex size-11 flex-none cursor-pointer items-center justify-center rounded-sm border-0 bg-transparent text-ink-500 disabled:cursor-not-allowed disabled:opacity-45 sm:size-8', block && 'opacity-45')}
    >
      <Trash aria-hidden />
    </button>
  );

  return (
    <div className="flex flex-col gap-3" data-testid="location-tree">
      <div className="flex flex-wrap items-center gap-3">
        {toolbar}
        <span className="flex-1" />
        <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm sm:min-h-8">
          <input type="checkbox" className="size-5 sm:size-4" checked={showOff} onChange={(e) => setShowOff(e.currentTarget.checked)} />
          Show switched-off
          {topHidden && !showOff ? <span className="text-xs text-muted">{`(${topHidden.toLocaleString('en-US')} ${topHidden === 1 ? 'country' : 'countries'} hidden)`}</span> : null}
        </label>
      </div>
      {readOnlyNote ? <p className="m-0 text-xs text-muted">{readOnlyNote}</p> : null}
      <ul role="tree" aria-label={label} onKeyDown={onTreeKey} className="m-0 flex list-none flex-col rounded-md border border-border bg-ink-0 p-0">
        {visible(shown, showOff).map((c) => {
          const open = expanded.has(c.code);
          const regions = c.regions === null ? null : visible(c.regions, showOff);
          return (
            <li key={c.code} {...treeItem(c.code, 1, `${c.name}, ${c.statusLabel ?? statusLabel(countryOptions ?? statuses.region, c.status)}`, { 'aria-expanded': open })} className="border-b border-border last:border-b-0 focus-visible:outline-2 focus-visible:outline-gold-500" data-testid="tree-country">
              <div className={cx(rowClass, 'bg-ink-25')}>
                <button type="button" tabIndex={-1} onClick={() => onExpand(c.code, !open)} aria-label={`${open ? 'Close' : 'Open'} ${c.name}`} className="inline-flex min-h-11 flex-1 cursor-pointer items-center gap-2 border-0 bg-transparent p-0 text-left text-text sm:min-h-8">
                  {caret(open)}
                  <span className="w-7 font-mono text-2xs font-bold text-subtle">{c.code}</span>
                  <strong className="text-base">{c.name}</strong>
                  <Chip status={c.status}>{c.statusLabel ?? statusLabel(countryOptions ?? statuses.region, c.status)}</Chip>
                  <span className="hidden text-xs text-muted sm:inline">{countrySummary(c)}</span>
                </button>
                <span className="w-full text-xs text-muted sm:hidden">{countrySummary(c)}</span>
                {countryOptions && ops.setCountryStatus ? (
                  <StatusSwitch label={`${c.name} status`} value={c.status} options={countryOptions} disabled={!canEdit} onChange={(s) => setCountryStatus(c, s)} />
                ) : null}
              </div>
              {open ? (
                <ul role="group" className="m-0 flex list-none flex-col gap-0 p-0 sm:pl-6">
                  {regions === null ? (
                    <li role="none" className="p-3">
                      <Spinner />
                    </li>
                  ) : null}
                  {regions && !regions.length ? (
                    <li role="none" className="px-3 py-3 text-sm text-muted">{`Nothing live or planned in ${c.name} yet. Add a ${c.regionLabel.toLocaleLowerCase('en-US')} below.`}</li>
                  ) : null}
                  {(regions ?? []).map((r) => (
                    <RegionNode
                      key={r.code}
                      c={c}
                      r={r}
                      open={expanded.has(regionKey(c.code, r.code))}
                      showOff={showOff}
                      canEdit={canEdit}
                      statuses={statuses}
                      treeItem={treeItem}
                      selected={selected[regionKey(c.code, r.code)] ?? []}
                      onSelect={(names) => setSelected((cur) => ({ ...cur, [regionKey(c.code, r.code)]: names }))}
                      adding={adding === `cities:${regionKey(c.code, r.code)}`}
                      onAdding={(on) => setAdding(on ? `cities:${regionKey(c.code, r.code)}` : null)}
                      onExpand={onExpand}
                      onStatus={(s) => setRegionStatus(c, r, s)}
                      onRemove={() => removeRegion(c, r)}
                      onEveryCity={ops.setEveryCity ? (on) => setEveryCity(c, r, on) : null}
                      onAddCities={(names) => addCities(c, r, names)}
                      onCityStatus={(names, s) => setCityStatus(c, r, names, s)}
                      onRemoveCity={(city) => removeCity(c, r, city)}
                      places={places}
                      extra={regionExtra?.(c, r)}
                      hint={coverageHint?.(c, r) ?? null}
                      removeButton={removeButton}
                      newCityStatus={newCityStatus}
                    />
                  ))}
                  {regions !== null && canEdit ? (
                    <li role="none" className="px-3 py-2">
                      {adding === `region:${c.code}` ? (
                        <PlacePicker
                          api={places}
                          country={c.code}
                          label={`Add a ${c.regionLabel.toLocaleLowerCase('en-US')} to ${c.name}`}
                          exclude={(code) => (c.regions ?? []).some((r) => r.code === code && r.status !== 'OFF')}
                          onPick={(p) => addRegion(c, p)}
                          onClose={() => setAdding(null)}
                        />
                      ) : (
                        <button type="button" className={treeButton} onClick={() => setAdding(`region:${c.code}`)}>
                          <Plus aria-hidden />
                          {`Add a ${c.regionLabel.toLocaleLowerCase('en-US')}`}
                        </button>
                      )}
                    </li>
                  ) : null}
                </ul>
              ) : null}
            </li>
          );
        })}
        {!visible(shown, showOff).length ? <li role="none" className="px-3 py-6 text-center text-sm text-muted">{emptyText ?? 'Nothing is live or planned yet.'}</li> : null}
      </ul>
      {ops.addCountry && canEdit ? (
        adding === 'country' ? (
          <PlacePicker api={places} label="Add a country" exclude={inUse} onPick={addCountry} onClose={() => setAdding(null)} />
        ) : (
          <div>
            <button type="button" className={treeButton} onClick={() => setAdding('country')}>
              <Plus aria-hidden />
              Add a country
            </button>
          </div>
        )
      ) : null}
    </div>
  );
}

type TreeItemProps = (id: string, level: 1 | 2 | 3, name: string, extra?: Record<string, unknown>) => Record<string, unknown>;

function RegionNode({
  c,
  r,
  open,
  showOff,
  canEdit,
  statuses,
  treeItem,
  selected,
  onSelect,
  adding,
  onAdding,
  onExpand,
  onStatus,
  onRemove,
  onEveryCity,
  onAddCities,
  onCityStatus,
  onRemoveCity,
  places,
  extra,
  hint,
  removeButton,
  newCityStatus
}: {
  c: TreeCountry;
  r: TreeRegion;
  open: boolean;
  showOff: boolean;
  canEdit: boolean;
  statuses: LocationTreeProps['statuses'];
  treeItem: TreeItemProps;
  selected: string[];
  onSelect: (names: string[]) => void;
  adding: boolean;
  onAdding: (on: boolean) => void;
  onExpand: (key: string, open: boolean) => void;
  onStatus: (s: PlaceStatus) => void;
  onRemove: () => void;
  onEveryCity: ((on: boolean) => void) | null;
  onAddCities: (names: string[]) => void;
  onCityStatus: (names: string[], s: PlaceStatus) => void;
  onRemoveCity: (city: TreeCity) => void;
  places: Pick<StaffMarketsApi, 'places'>;
  extra: ReactNode;
  hint: string | null;
  removeButton: (label: string, onClick: () => void, block: string | null) => ReactNode;
  newCityStatus: PlaceStatus;
}) {
  const rk = regionKey(c.code, r.code);
  const cities = r.cities === null ? null : visible(r.cities, showOff);
  const hiddenCities = r.cities ? hiddenCount(r.cities) : 0;
  const allPicked = !!cities?.length && cities.every((x) => selected.includes(x.name));
  const regionBlock = regionRemovalBlock(r);
  return (
    <li {...treeItem(rk, 2, `${r.name}, ${statusLabel(statuses.region, r.status)}, ${regionSummary(r)}`, { 'aria-expanded': open })} className="border-t border-border outline-none focus-visible:outline-2 focus-visible:outline-gold-500" data-testid="tree-region">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2">
        <button type="button" tabIndex={-1} onClick={() => onExpand(rk, !open)} aria-label={`${open ? 'Close' : 'Open'} ${r.name}`} className="inline-flex min-h-11 flex-1 cursor-pointer items-center gap-2 border-0 bg-transparent p-0 text-left text-text sm:min-h-8">
          {open ? <CaretDown aria-hidden /> : <CaretRight aria-hidden />}
          <strong className="text-sm">{r.name}</strong>
          <span className="font-mono text-2xs text-subtle">{r.code}</span>
          <Chip status={r.status}>{statusLabel(statuses.region, r.status)}</Chip>
          <span className="text-xs text-muted" data-testid="region-summary">
            {r.cities === null ? '' : regionSummary(r)}
          </span>
          {r.dependents ? <span className="text-xs font-semibold text-gold-900">{`${r.dependents} open pre-order${r.dependents > 1 ? 's' : ''}`}</span> : null}
          {r.draft ? <span className="text-xs text-gold-900">Not saved until it has cities</span> : null}
        </button>
        <span className="flex items-center gap-1">
          <StatusSwitch label={`${r.name} status`} value={r.status} options={statuses.region} disabled={!canEdit || r.draft} onChange={onStatus} />
          {removeButton(`Remove ${r.name}`, onRemove, regionBlock)}
        </span>
      </div>
      {open ? (
        <div role="group" className="flex flex-col gap-3 border-t border-dashed border-border bg-ink-25 px-3 py-3 sm:pl-9">
          {onEveryCity && r.everyCity !== null ? (
            <label className="flex min-h-11 cursor-pointer flex-wrap items-center gap-2 text-sm sm:min-h-8">
              <input type="checkbox" className="size-5 sm:size-4" checked={r.everyCity} disabled={!canEdit || r.draft} onChange={(e) => onEveryCity(e.currentTarget.checked)} />
              <span className="font-semibold">{`Every city in ${r.name}`}</span>
              <span className="text-xs text-muted">{hint ?? (r.everyCity ? 'Listed cities override it (e.g. keep one off).' : 'Only the cities listed below.')}</span>
            </label>
          ) : null}
          {extra}
          {cities === null ? <Spinner /> : null}
          {cities && cities.length ? (
            <div className="flex flex-wrap items-center gap-2" data-testid="city-bulk">
              <label className="flex min-h-11 cursor-pointer items-center gap-2 text-xs font-semibold sm:min-h-8">
                <input type="checkbox" className="size-5 sm:size-4" checked={allPicked} disabled={!canEdit} onChange={() => onSelect(allPicked ? [] : cities.map((x) => x.name))} />
                {selected.length ? `${selected.length} selected` : 'Select all'}
              </label>
              {selected.length
                ? statuses.city.map((o) => (
                    <button key={o.value} type="button" className={treeButton} disabled={!canEdit} onClick={() => onCityStatus(selected, o.value)}>
                      {`Switch selected to ${o.label}`}
                    </button>
                  ))
                : null}
              {hiddenCities && !showOff ? <span className="text-xs text-muted">{`${hiddenCities} switched off (hidden)`}</span> : null}
            </div>
          ) : null}
          {cities ? (
            <ul role="group" aria-label={`Cities in ${r.name}`} className="m-0 flex list-none flex-col divide-y divide-border rounded-sm border border-border bg-ink-0 p-0">
              {cities.map((city) => {
                const block = removalBlock(city);
                const ck = cityKey(c.code, r.code, city.name);
                return (
                  <li key={city.name} {...treeItem(ck, 3, `${city.name}, ${statusLabel(statuses.city, city.status)}`)} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-2 py-1 outline-none focus-visible:outline-2 focus-visible:outline-gold-500" data-testid="tree-city">
                    <label className="flex min-h-11 flex-1 cursor-pointer items-center gap-2 text-sm sm:min-h-8">
                      <input
                        type="checkbox"
                        tabIndex={-1}
                        className="size-5 sm:size-4"
                        aria-label={`Select ${city.name}`}
                        checked={selected.includes(city.name)}
                        disabled={!canEdit}
                        onChange={() => onSelect(selected.includes(city.name) ? selected.filter((x) => x !== city.name) : [...selected, city.name])}
                      />
                      <span className={cx('font-semibold', city.status === 'OFF' && 'text-muted line-through')}>{city.name}</span>
                    </label>
                    {block ? (
                      <span className="flex items-center gap-1 text-2xs text-gold-900">
                        <Warning aria-hidden />
                        {city.dependents.join(', ')}
                      </span>
                    ) : null}
                    <span className="flex items-center gap-1">
                      <StatusSwitch label={`${city.name} status`} value={city.status} options={statuses.city} disabled={!canEdit} onChange={(s) => onCityStatus([city.name], s)} />
                      {removeButton(`Remove ${city.name}`, () => onRemoveCity(city), block)}
                    </span>
                  </li>
                );
              })}
              {!cities.length ? <li role="none" className="px-3 py-2 text-xs text-muted">{r.everyCity ? `Every city in ${r.name} is covered; list a city to switch it on its own.` : 'No cities yet.'}</li> : null}
            </ul>
          ) : null}
          {canEdit && cities !== null ? (
            adding ? (
              <CityAdder
                api={places}
                country={c.code}
                region={r.code}
                regionName={r.name}
                listed={(r.cities ?? []).map((x) => x.name)}
                onAdd={onAddCities}
                onEveryCity={onEveryCity && r.everyCity !== null ? () => onEveryCity(true) : null}
                everyCity={!!r.everyCity}
                onClose={() => onAdding(false)}
                hint={`New cities start ${statusLabel(statuses.city, newCityStatus).toLocaleLowerCase('en-US')}.`}
              />
            ) : (
              <div>
                <button type="button" className={treeButton} onClick={() => onAdding(true)}>
                  <Plus aria-hidden />
                  Add cities
                </button>
              </div>
            )
          ) : null}
        </div>
      ) : null}
    </li>
  );
}
