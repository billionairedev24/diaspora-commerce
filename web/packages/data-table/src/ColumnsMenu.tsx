'use client';

import { cx } from '@afrotransact/ui';
import { Columns } from '@phosphor-icons/react/ssr';
import { type Table } from '@tanstack/react-table';
import { useEffect, useId, useRef, useState } from 'react';

import { columnLabel } from './export';
import { SELECT_COLUMN } from './types';

export type Density = 'comfortable' | 'compact';

/** "Columns": show or hide columns, row density, and a reset of widths and visibility. */
export function ColumnsMenu<T>({ table, density, onDensity, onReset }: { table: Table<T>; density: Density; onDensity: (d: Density) => void; onReset: () => void }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e: MouseEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        button.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    wrap.current?.querySelector<HTMLInputElement>('input')?.focus();
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const hideable = table.getAllLeafColumns().filter((c) => c.id !== SELECT_COLUMN && c.getCanHide());
  const shown = hideable.filter((c) => c.getIsVisible()).length;
  return (
    <div ref={wrap} className="relative">
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((o) => !o)}
        className="flex h-9 items-center gap-1.5 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-hover"
      >
        <Columns size={16} aria-hidden />
        Columns
        <span className="text-xs font-normal text-muted">
          {shown}/{hideable.length}
        </span>
      </button>
      {open ? (
        <div id={panelId} role="group" aria-label="Columns and density" className="absolute right-0 z-30 mt-2 flex w-64 flex-col gap-3 rounded-md border border-border bg-surface p-3 shadow-md">
          <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
            <legend className="mb-1 text-xs font-bold text-muted">Show columns</legend>
            {hideable.map((c) => (
              <label key={c.id} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={c.getIsVisible()}
                  // Keep at least one column.
                  disabled={c.getIsVisible() && shown <= 1}
                  onChange={c.getToggleVisibilityHandler()}
                />
                {columnLabel(c)}
              </label>
            ))}
          </fieldset>
          <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
            <legend className="mb-1 text-xs font-bold text-muted">Density</legend>
            {(['comfortable', 'compact'] as const).map((d) => (
              <label key={d} className="flex items-center gap-2 text-sm">
                <input type="radio" name={`${panelId}-density`} checked={density === d} onChange={() => onDensity(d)} />
                {d === 'comfortable' ? 'Comfortable' : 'Compact'}
              </label>
            ))}
          </fieldset>
          <button type="button" onClick={onReset} className={cx('self-start text-sm font-semibold text-link hover:underline')}>
            Reset columns
          </button>
        </div>
      ) : null}
    </div>
  );
}
