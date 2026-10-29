'use client';

import { type ProductTypeSchema } from '@afrotransact/api-client';
import { Button, cx } from '@afrotransact/ui';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { applyToAll, comboKey, combos, emptyRow, fieldId, fillSkus, type FormState, type Problems, type VariantRow } from '../form-model';

interface Props {
  s: FormState;
  type: ProductTypeSchema;
  set: (fn: (prev: FormState) => FormState) => void;
  problems: Problems;
  shown: (id: string) => boolean;
  /** Variant combinations a live listing already sells: they can't be removed. */
  locked: Set<string>;
}

type Cell = 'sku' | 'gtin' | 'price' | 'compareAt' | 'qty';

/** Variations: pick how the product varies (axes), the options of each axis, then fill the generated matrix (SKU, barcode, price, compare-at, stock, image per row). */
export function VariantsSection({ s, type, set, problems, shown, locked }: Props) {
  const t = useT();
  const theme = type.themes.find((th) => th.name === s.theme);
  const list = combos(type, s);
  const [all, setAll] = useState({ price: '', qty: '' });
  const err = (id: string) => (shown(id) && problems[id] ? t(problems[id].key, problems[id].vars) : undefined);
  const row = (k: string): VariantRow => s.rows[k] ?? emptyRow();
  const setRow = (k: string, p: Partial<VariantRow>) => set((prev) => ({ ...prev, rows: { ...prev.rows, [k]: { ...(prev.rows[k] ?? emptyRow()), ...p } } }));
  const lockedOption = (dim: number, option: string) => [...locked].some((k) => k.split(' / ')[dim] === option);
  const idLabel = type.identifierRule === 'isbn' ? t('sc.pf.field.isbn') : t('sc.pf.field.gtin');
  const columns: { field: Cell; label: string; mode?: 'decimal' | 'numeric'; mono?: boolean }[] = [
    { field: 'sku', label: t('sc.pf.field.sku'), mono: true },
    ...(s.noGtin ? [] : [{ field: 'gtin' as const, label: idLabel, mode: 'numeric' as const, mono: true }]),
    { field: 'price', label: t('sc.pf.field.priceUsd'), mode: 'decimal' },
    { field: 'compareAt', label: t('sc.pf.field.compareAtShort'), mode: 'decimal' },
    { field: 'qty', label: t('sc.pf.field.qty'), mode: 'numeric' }
  ];
  const grid = s.noGtin ? 'md:grid-cols-[minmax(84px,.9fr)_minmax(120px,1.4fr)_minmax(70px,.7fr)_minmax(70px,.7fr)_minmax(56px,.6fr)_minmax(96px,1fr)]'
    : 'md:grid-cols-[minmax(84px,.9fr)_minmax(110px,1.3fr)_minmax(120px,1.2fr)_minmax(70px,.7fr)_minmax(70px,.7fr)_minmax(56px,.6fr)_minmax(96px,1fr)]';
  return (
    <div className="flex flex-col gap-4">
      <div id={fieldId.theme} tabIndex={-1} className="flex flex-col gap-2 outline-none">
        <span className="text-sm font-semibold">{t('sc.pf.variants.how')}</span>
        <div role="group" aria-label={t('sc.pf.variants.how')} className="flex flex-wrap gap-2">
          {type.themes.map((th) => (
            <button key={th.name} type="button" aria-pressed={s.theme === th.name} disabled={locked.size > 0 && s.theme !== th.name}
              onClick={() => set((prev) => ({ ...prev, theme: th.name, selected: {}, rows: {} }))}
              className={cx('h-8 rounded-pill border px-4 text-sm font-semibold disabled:opacity-40', s.theme === th.name ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
              {th.name}
            </button>
          ))}
        </div>
        {err(fieldId.theme) ? <span role="alert" className="text-xs text-danger">{err(fieldId.theme)}</span> : null}
      </div>
      {theme?.dimensions.map((d, di) => (
        <div key={d.name} role="group" aria-label={d.name} className="flex flex-col gap-1">
          <span className="text-sm font-semibold">{d.name}</span>
          <div className="flex flex-wrap gap-1">
            {d.options.map((o) => {
              const on = (s.selected[d.name] ?? []).includes(o);
              return (
                <button key={o} type="button" aria-pressed={on} disabled={on && lockedOption(di, o)}
                  onClick={() => set((prev) => ({ ...prev, selected: { ...prev.selected, [d.name]: on ? (prev.selected[d.name] ?? []).filter((x) => x !== o) : [...(prev.selected[d.name] ?? []), o] } }))}
                  className={cx('h-[30px] rounded-pill border px-3 text-xs font-semibold disabled:cursor-not-allowed', on ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
                  {o}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      {list.length ? (
        <>
          <div className="flex flex-wrap items-end gap-2 rounded-sm bg-surface-muted p-2">
            <span className="basis-full text-xs font-semibold">{t('sc.pf.variants.applyAll', { count: list.length })}</span>
            <input aria-label={t('sc.pf.variants.allPrice')} placeholder={t('sc.pf.field.priceUsd')} inputMode="decimal" value={all.price} onChange={(e) => setAll({ ...all, price: e.target.value })}
              className="h-8 w-28 rounded-sm border border-border-strong bg-surface px-2 text-sm" />
            <Button size="sm" variant="neutral" disabled={!all.price.trim()} onClick={() => set((prev) => applyToAll(type, prev, 'price', all.price))}>{t('sc.pf.variants.apply')}</Button>
            <input aria-label={t('sc.pf.variants.allQty')} placeholder={t('sc.pf.field.qty')} inputMode="numeric" value={all.qty} onChange={(e) => setAll({ ...all, qty: e.target.value })}
              className="h-8 w-24 rounded-sm border border-border-strong bg-surface px-2 text-sm" />
            <Button size="sm" variant="neutral" disabled={!all.qty.trim()} onClick={() => set((prev) => applyToAll(type, prev, 'qty', all.qty))}>{t('sc.pf.variants.apply')}</Button>
            <Button size="sm" variant="neutral" onClick={() => set((prev) => fillSkus(type, prev))}>{t('sc.pf.variants.generateSkus')}</Button>
          </div>
          <div role="table" aria-label={t('sc.pf.variants.matrix')} className="flex flex-col rounded-md border border-border">
            <div role="row" className={cx('hidden gap-2 border-b border-border bg-surface-muted px-3 py-2 md:grid', grid)}>
              <span role="columnheader" className="text-xs font-semibold">{t('sc.pf.variants.variant')}</span>
              {columns.map((c) => <span key={c.field} role="columnheader" className="text-xs font-semibold">{c.label}</span>)}
              <span role="columnheader" className="text-xs font-semibold">{t('sc.pf.variants.image')}</span>
            </div>
            {list.map((c) => {
              const k = comboKey(c);
              const r = row(k);
              return (
                <div key={k} role="row" data-testid="variant-row" className={cx('grid grid-cols-2 gap-x-2 gap-y-1 border-b border-border px-3 py-2 last:border-b-0 md:items-start', grid)}>
                  <span role="rowheader" className="col-span-2 flex items-center gap-1 pt-1.5 text-sm font-semibold md:col-span-1">
                    {k}{locked.has(k) ? <span className="rounded-xs bg-surface-muted px-1 text-2xs font-normal text-muted">{t('sc.pf.variants.live')}</span> : null}
                  </span>
                  {columns.map((col) => {
                    const id = fieldId.variant(k, col.field);
                    const e = err(id);
                    return (
                      <span role="cell" key={col.field} className={cx('flex flex-col gap-0.5', col.field === 'sku' && 'col-span-2 md:col-span-1')}>
                        <label htmlFor={id} className="text-2xs text-muted md:sr-only">{col.label}</label>
                        <input id={id} aria-label={`${col.label} · ${k}`} aria-invalid={e ? true : undefined} inputMode={col.mode} value={r[col.field]}
                          readOnly={col.field === 'sku' && locked.has(k)} onChange={(ev) => setRow(k, { [col.field]: ev.target.value })}
                          className={cx('h-8 w-full min-w-0 rounded-sm border bg-surface px-1.5 text-sm read-only:bg-surface-muted', e ? 'border-danger' : 'border-border-strong', col.mono && 'font-mono text-xs')} />
                        {e ? <span className="text-2xs text-danger">{e}</span> : null}
                      </span>
                    );
                  })}
                  <span role="cell" className="col-span-2 flex flex-col gap-0.5 md:col-span-1">
                    <label htmlFor={fieldId.variant(k, 'image')} className="text-2xs text-muted md:sr-only">{t('sc.pf.variants.image')}</label>
                    <select id={fieldId.variant(k, 'image')} value={r.image} onChange={(ev) => setRow(k, { image: ev.target.value })}
                      className="h-8 w-full min-w-0 rounded-sm border border-border-strong bg-surface px-1 text-sm">
                      <option value="">{t('sc.pf.variants.mainImage')}</option>
                      {s.images.filter((i) => i.url).map((im, i) => <option key={im.id} value={im.id}>{t('sc.pf.images.alt', { n: i + 1 })}</option>)}
                    </select>
                  </span>
                </div>
              );
            })}
          </div>
        </>
      ) : null}
    </div>
  );
}
