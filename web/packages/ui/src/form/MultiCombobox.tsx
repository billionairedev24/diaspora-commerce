'use client';

import { type KeyboardEvent, type ReactNode, useId, useMemo, useRef, useState } from 'react';

import { CaretDown, Check, X } from '../icons';
import { Field } from './Field';
import { useFormLabels } from './FormLabels';
import styles from './MultiCombobox.module.css';

export interface MultiOption {
  value: string;
  label: string;
}

export interface MultiComboboxProps {
  label: ReactNode;
  options: MultiOption[];
  /** The chosen values, in the order they were chosen. */
  values: string[];
  onChange: (values: string[]) => void;
  /** A choice that stands alone ("None of these"): choosing it clears the others, choosing any other clears it. */
  exclusive?: MultiOption;
  placeholder?: string;
  required?: boolean;
  tip?: ReactNode;
  help?: ReactNode;
  error?: string | null;
  id?: string;
  disabled?: boolean;
  emptyLabel?: string;
  /** Read out as the list changes ("3 results"). */
  resultsLabel?: (count: number) => string;
}

const fold = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

/**
 * Dropdown multi-select: a field of removable chips with a search box (ARIA 1.2 combobox over a multi-select list box). Type to filter,
 * Up and Down move, Enter or a tap picks or unpicks and keeps the list open, Backspace on an empty box removes the last chip, Escape
 * closes. Chips have their own remove buttons (44px on phones). One exclusive choice ("None") replaces everything else.
 */
export function MultiCombobox({ label, options, values, onChange, exclusive, placeholder, required, tip, help, error, id, disabled, emptyLabel = 'No matches', resultsLabel = (n) => `${n} results`}: MultiComboboxProps) {
  const labels = useFormLabels();
  const auto = useId().replace(/:/g, '');
  const listId = `mc${auto}-list`;
  const input = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);

  const all = useMemo(() => (exclusive ? [...options, exclusive] : options), [options, exclusive]);
  const nameOf = (v: string) => all.find((o) => o.value === v)?.label ?? v;
  const rows = useMemo(() => {
    const words = fold(query.trim()).split(/\s+/).filter(Boolean);
    return all.filter((o) => words.every((w) => fold(o.label).includes(w)));
  }, [all, query]);
  const current = Math.min(active, Math.max(0, rows.length - 1));
  const optionId = (i: number) => `mc${auto}-o${i}`;

  const toggle = (value: string) => {
    if (values.includes(value)) onChange(values.filter((v) => v !== value));
    else if (exclusive && value === exclusive.value) onChange([value]);
    else onChange([...values.filter((v) => v !== exclusive?.value), value]);
    setQuery('');
  };
  const close = () => {
    setOpen(false);
    setQuery('');
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) setOpen(true);
      else setActive(Math.min(rows.length - 1, current + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) setOpen(true);
      else setActive(Math.max(0, current - 1));
    } else if (e.key === 'Enter') {
      if (open && rows[current]) {
        e.preventDefault();
        toggle(rows[current].value);
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault();
        e.stopPropagation();
        close();
      }
    } else if (e.key === 'Backspace' && !query && values.length) {
      onChange(values.slice(0, -1));
    }
  };

  return (
    <Field label={label} error={error} help={help} required={required} tip={tip} id={id}>
      {(a11y) => (
        <div className={styles.wrap} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) close(); }}>
          {/* The box is a click target for the whole row; the input and the chip buttons inside it are what the keyboard reaches. */}
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div className={styles.box} data-invalid={error ? 'true' : undefined} data-disabled={disabled ? 'true' : undefined} onClick={() => { input.current?.focus(); setOpen(true); }}>
            {values.map((v) => (
              <span key={v} className={styles.chip}>
                {nameOf(v)}
                <button type="button" className={styles.remove} disabled={disabled} aria-label={labels.remove(nameOf(v))} onClick={(e) => { e.stopPropagation(); onChange(values.filter((x) => x !== v)); input.current?.focus(); }}>
                  <X size={12} aria-hidden />
                </button>
              </span>
            ))}
            <input
              {...a11y}
              ref={input}
              type="text"
              role="combobox"
              autoComplete="off"
              disabled={disabled}
              placeholder={values.length ? undefined : placeholder}
              aria-expanded={open}
              aria-controls={listId}
              aria-autocomplete="list"
              aria-activedescendant={open && rows[current] ? optionId(current) : undefined}
              className={styles.input}
              value={query}
              onFocus={() => setOpen(true)}
              onKeyDown={onKeyDown}
              onChange={(e) => { setQuery(e.target.value); setOpen(true); setActive(0); }}
            />
            <CaretDown size={14} aria-hidden className={styles.caret} />
          </div>
          {open ? (
            <ul id={listId} role="listbox" aria-multiselectable="true" aria-label={typeof label === 'string' ? label : undefined} className={styles.list}>
              {rows.map((o, i) => {
                const on = values.includes(o.value);
                return (
                  // Options are not focusable: the keyboard works on the input (aria-activedescendant), the tap is for the pointer.
                  // eslint-disable-next-line jsx-a11y/click-events-have-key-events
                  <li key={o.value} id={optionId(i)} role="option" aria-selected={on} data-active={i === current} data-exclusive={exclusive?.value === o.value ? 'true' : undefined}
                    className={styles.option} onMouseDown={(e) => e.preventDefault()} onMouseEnter={() => setActive(i)} onClick={() => toggle(o.value)}>
                    <span className={styles.tick} aria-hidden>{on ? <Check size={14} weight="bold" /> : null}</span>
                    <span>{o.label}</span>
                  </li>
                );
              })}
              {rows.length === 0 ? <li role="presentation" className={styles.empty}>{emptyLabel}</li> : null}
            </ul>
          ) : null}
          <span role="status" aria-live="polite" className="at-visually-hidden">{open && query ? resultsLabel(rows.length) : ''}</span>
        </div>
      )}
    </Field>
  );
}

