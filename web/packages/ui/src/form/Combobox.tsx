'use client';

import { type KeyboardEvent, type ReactNode, useId, useMemo, useRef, useState } from 'react';

import { CaretDown } from '../icons';
import styles from './Combobox.module.css';
import { Field, fieldStyles } from './Field';

export interface ComboboxOption {
  value: string;
  /** The primary line of the list ("Dresses"). */
  label: string;
  /** A second, muted line that is searched too ("Fashion › Women › Dresses"). */
  description?: string;
  /** What the input shows once chosen; defaults to the label. */
  text?: string;
  /** Extra words that match but are not shown. */
  keywords?: string;
}

export interface ComboboxProps {
  label: ReactNode;
  options: ComboboxOption[];
  /** The chosen option's value ('' = none). With `onCreate`, a typed name that is not an option is a value too. */
  value: string;
  onChange: (value: string) => void;
  /** Values listed first, under their own headings, while nothing is typed. */
  recent?: string[];
  popular?: string[];
  /** Offers "Add “text”" for a name that is not in the list; the parent decides what adding means (and then sets the value). */
  onCreate?: (text: string) => void;
  createLabel?: (text: string) => string;
  /** Typing then leaving the field empty clears the value (an optional field). */
  clearable?: boolean;
  placeholder?: string;
  error?: string | null;
  help?: ReactNode;
  id?: string;
  name?: string;
  disabled?: boolean;
  /** Most options listed at once (default 50). */
  limit?: number;
  recentLabel?: string;
  popularLabel?: string;
  allLabel?: string;
  emptyLabel?: string;
  /** Read out as the list changes ("3 results"). */
  resultsLabel?: (count: number) => string;
  /** Accessible name of the list. */
  listLabel?: string;
}

const fold = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

interface Row {
  kind: 'option' | 'create' | 'heading';
  key: string;
  option?: ComboboxOption;
  text: string;
}

/**
 * Searchable select (ARIA 1.2 combobox with a list box). Type to filter over the label and the muted second line (a
 * category's full path); ↑/↓ move, Enter chooses, Esc closes, Tab leaves. Recent and popular values lead the list while
 * nothing is typed. Use a native Select for a handful of choices; this is for long lists.
 */
export function Combobox({
  label, options, value, onChange, recent = [], popular = [], onCreate, createLabel = (t) => `Add “${t}”`, clearable = false, placeholder, error, help, id, name, disabled,
  limit = 50, recentLabel = 'Recent', popularLabel = 'Popular', allLabel = 'All', emptyLabel = 'No matches', resultsLabel = (n) => `${n} results`, listLabel
}: ComboboxProps) {
  const auto = useId().replace(/:/g, '');
  const listId = `cb${auto}-list`;
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);

  const chosen = options.find((o) => o.value === value);
  const shownText = editing ? query : (chosen?.text ?? chosen?.label ?? value);

  const rows = useMemo<Row[]>(() => {
    const q = query.trim();
    const out: Row[] = [];
    const add = (o: ComboboxOption) => out.push({ kind: 'option', key: `o-${o.value}`, option: o, text: o.label });
    if (editing && q) {
      const words = fold(q).split(/\s+/).filter(Boolean);
      const first = words[0] ?? '';
      const score = (o: ComboboxOption) => (fold(o.label).startsWith(first) ? 0 : fold(o.label).includes(first) ? 1 : 2);
      options
        .filter((o) => {
          const hay = fold(`${o.label} ${o.description ?? ''} ${o.keywords ?? ''}`);
          return words.every((w) => hay.includes(w));
        })
        .map((o, i) => ({ o, i, s: score(o) }))
        .sort((a, b) => a.s - b.s || a.i - b.i)
        .slice(0, limit)
        .forEach(({ o }) => add(o));
      if (onCreate && !options.some((o) => fold(o.label) === fold(q))) out.push({ kind: 'create', key: 'create', text: q });
      return out;
    }
    const byValue = new Map(options.map((o) => [o.value, o]));
    const shown = new Set<string>();
    const group = (heading: string, values: string[]) => {
      const list: ComboboxOption[] = [];
      values.forEach((v) => {
        const o = byValue.get(v);
        if (o && !shown.has(v)) list.push(o);
      });
      if (!list.length) return;
      out.push({ kind: 'heading', key: `h-${heading}`, text: heading });
      list.forEach((o) => {
        shown.add(o.value);
        add(o);
      });
    };
    group(recentLabel, recent);
    group(popularLabel, popular);
    const rest = options.filter((o) => !shown.has(o.value)).slice(0, limit);
    if (rest.length) {
      if (shown.size) out.push({ kind: 'heading', key: 'h-all', text: allLabel });
      rest.forEach(add);
    }
    return out;
  }, [options, query, editing, recent, popular, onCreate, limit, recentLabel, popularLabel, allLabel]);

  const selectable = rows.flatMap((r, i) => (r.kind === 'heading' ? [] : [i]));
  const count = selectable.length;
  const activeRow = open && count ? selectable[Math.min(active, count - 1)] : undefined;
  const optionId = (i: number) => `cb${auto}-o${i}`;

  const close = () => {
    setOpen(false);
    setEditing(false);
    setQuery('');
  };
  const choose = (row: Row | undefined) => {
    if (!row) return;
    if (row.kind === 'create') onCreate?.(row.text);
    else if (row.option) onChange(row.option.value);
    close();
  };
  const move = (to: number) => {
    const next = Math.max(0, Math.min(count - 1, to));
    setActive(next);
    const i = selectable[next];
    if (i !== undefined) document.getElementById(optionId(i))?.scrollIntoView?.({ block: 'nearest' });
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(0);
      } else move(active + 1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(Math.max(0, count - 1));
      } else move(active - 1);
    } else if (e.key === 'Home' && open && e.ctrlKey) {
      e.preventDefault();
      move(0);
    } else if (e.key === 'End' && open && e.ctrlKey) {
      e.preventDefault();
      move(count - 1);
    } else if (e.key === 'Enter') {
      if (open && activeRow !== undefined) {
        e.preventDefault();
        choose(rows[activeRow]);
      }
    } else if (e.key === 'Escape') {
      if (open || editing) {
        e.preventDefault();
        e.stopPropagation();
        close();
      }
    }
  };
  const onBlur = () => {
    if (clearable && editing && !query.trim() && value) onChange('');
    close();
  };

  return (
    <Field label={label} error={error} help={help} id={id}>
      {(a11y) => (
        <div className={styles.wrap}>
          <input
            {...a11y}
            name={name}
            type="text"
            role="combobox"
            autoComplete="off"
            disabled={disabled}
            placeholder={placeholder}
            aria-expanded={open}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={activeRow !== undefined ? optionId(activeRow) : undefined}
            className={`${fieldStyles.control} ${styles.input}`}
            value={shownText}
            onFocus={(e) => {
              setOpen(true);
              setActive(0);
              e.currentTarget.select();
            }}
            onClick={() => {
              if (!open) {
                setOpen(true);
                setActive(0);
              }
            }}
            onBlur={onBlur}
            onKeyDown={onKeyDown}
            onChange={(e) => {
              setQuery(e.target.value);
              setEditing(true);
              setOpen(true);
              setActive(0);
            }}
          />
          <CaretDown size={14} aria-hidden className={styles.caret} />
          {open ? (
            <ul ref={listRef} id={listId} role="listbox" aria-label={listLabel} className={styles.list}>
              {rows.map((r, i) =>
                r.kind === 'heading' ? (
                  <li key={r.key} role="presentation" className={styles.heading}>{r.text}</li>
                ) : (
                  // Options are not focusable: the keyboard works on the input (aria-activedescendant), the click is for the pointer.
                  // eslint-disable-next-line jsx-a11y/click-events-have-key-events
                  <li
                    key={r.key}
                    id={optionId(i)}
                    role="option"
                    aria-selected={r.option ? r.option.value === value : false}
                    data-active={i === activeRow}
                    className={styles.option}
                    onMouseDown={(e) => e.preventDefault()}
                    onMouseEnter={() => setActive(selectable.indexOf(i))}
                    onClick={() => choose(r)}
                  >
                    {r.kind === 'create' ? (
                      <span className={`${styles.label} ${styles.create}`}>{createLabel(r.text)}</span>
                    ) : (
                      <>
                        <span className={styles.label}>{r.option?.label}</span>
                        {r.option?.description ? <span className={styles.description}>{r.option.description}</span> : null}
                      </>
                    )}
                  </li>
                )
              )}
              {count === 0 ? <li role="presentation" className={styles.empty}>{emptyLabel}</li> : null}
            </ul>
          ) : null}
          <span role="status" aria-live="polite" className="at-visually-hidden">{open && editing ? resultsLabel(count) : ''}</span>
        </div>
      )}
    </Field>
  );
}
