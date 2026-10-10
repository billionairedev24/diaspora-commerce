'use client';

import { staffPreorderQueries } from '@afrotransact/api-client';
import { Plus } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { greenButton, selectClass, smallLabelClass, usePreordersApi } from './primitives';

/**
 * Country → state / province / region → city (or "Every city in …") dropdowns backed by
 * /v1/staff/preorders/places/search. Calls onAdd with the area key (country/region or country/region/city).
 */
export function AreaPicker({ onAdd, exclude, disabled, addLabel, pending }: { onAdd: (key: string) => void; exclude: (key: string) => boolean; disabled?: boolean; addLabel: string; pending?: boolean }) {
  const api = usePreordersApi();
  const [c, setC] = useState('US');
  const [r, setR] = useState('');
  const [x, setX] = useState('');
  const countries = useQuery(staffPreorderQueries.places(api, {}));
  const regions = useQuery(staffPreorderQueries.places(api, { country: c }));
  const cities = useQuery({ ...staffPreorderQueries.places(api, { country: c, region: r }), enabled: !!r });
  const regionLabel = regions.data?.regionLabel ?? 'State';
  const regionName = regions.data?.items.find((i) => i.code === r)?.name ?? r;
  const key = r && x ? `${c}/${r}${x === '*' ? '' : `/${x}`}` : '';
  const cantAdd = !!disabled || !key || exclude(key) || !!pending;
  const cityList = (cities.data?.items ?? []).filter((i) => !exclude(i.key));
  return (
    <div className="flex max-w-[760px] flex-wrap items-end gap-2 [&>label]:min-w-[150px] [&>label]:flex-1">
      <label className={smallLabelClass}>
        Country
        <select
          value={c}
          disabled={disabled}
          onChange={(e) => {
            setC(e.currentTarget.value);
            setR('');
            setX('');
          }}
          className={selectClass}
        >
          {(countries.data?.items ?? [{ code: 'US', name: 'United States', key: 'US' }]).map((o) => (
            <option key={o.key} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <label className={smallLabelClass}>
        {regionLabel}
        <select
          value={r}
          disabled={disabled}
          onChange={(e) => {
            setR(e.currentTarget.value);
            setX('');
          }}
          className={selectClass}
        >
          <option value="">{regions.data ? `Choose… (${regions.data.items.length})` : 'Loading…'}</option>
          {(regions.data?.items ?? []).map((o) => (
            <option key={o.key} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <label className={smallLabelClass}>
        City
        <select value={x} disabled={disabled || !r} onChange={(e) => setX(e.currentTarget.value)} className={selectClass}>
          <option value="">{!r ? `Choose a ${regionLabel.toLowerCase()} first` : cities.data ? `Choose… (${cities.data.items.length} cities)` : 'Loading cities…'}</option>
          <option value="*">{r ? `Every city in ${regionName}` : 'Every city'}</option>
          {cityList.map((o) => (
            <option key={o.key} value={o.name}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        disabled={cantAdd}
        onClick={() => {
          if (cantAdd) return;
          onAdd(key);
          setX('');
        }}
        className={greenButton}
      >
        <Plus aria-hidden />
        {addLabel}
      </button>
    </div>
  );
}
