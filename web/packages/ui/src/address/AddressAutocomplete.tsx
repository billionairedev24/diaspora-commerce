'use client';

import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { Field, fieldStyles } from '../form/Field';
import { MapPin } from '../icons';
import styles from './AddressAutocomplete.module.css';
import { type AddressSuggestionItem, type SearchAddresses } from './types';

export interface AddressAutocompleteProps {
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  onSelect: (suggestion: AddressSuggestionItem) => void;
  /** Pluggable search: geo service (Google Places / OSM), createNominatimSearch() or createFixtureAddressSearch(). */
  searchAddresses: SearchAddresses;
  placeholder?: string;
  error?: string | null;
  help?: ReactNode;
  attribution?: ReactNode;
  minChars?: number;
  debounceMs?: number;
  emptyLabel?: string;
  errorLabel?: string;
  loadingLabel?: string;
  id?: string;
  name?: string;
  autoFocus?: boolean;
}

type Status = 'idle' | 'loading' | 'ready' | 'error';

/** Address input with suggestions (ARIA 1.2 combobox): ↑/↓ to move, Enter to pick, Esc to close. */
export function AddressAutocomplete({
  label,
  value,
  onChange,
  onSelect,
  searchAddresses,
  placeholder = 'Start typing an address',
  error,
  help,
  attribution,
  minChars = 2,
  debounceMs = 200,
  emptyLabel = 'No matching addresses',
  errorLabel = 'Address suggestions are unavailable — keep typing your full address.',
  loadingLabel = 'Searching…',
  id,
  name,
  autoFocus
}: AddressAutocompleteProps) {
  const listId = `al${useId().replace(/:/g, '')}`;
  const [items, setItems] = useState<AddressSuggestionItem[]>([]);
  const [status, setStatus] = useState<Status>('idle');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const skipNext = useRef(false);

  useEffect(() => {
    if (skipNext.current) {
      skipNext.current = false;
      return;
    }
    const q = value.trim();
    if (q.length < minChars) {
      setItems([]);
      setStatus('idle');
      return;
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      setStatus('loading');
      searchAddresses(q, { signal: ctrl.signal })
        .then((r) => {
          setItems(r);
          setStatus('ready');
          setActive(-1);
        })
        .catch((e: unknown) => {
          if ((e as { name?: string })?.name === 'AbortError') return;
          setItems([]);
          setStatus('error');
        });
    }, debounceMs);
    return () => {
      clearTimeout(t);
      ctrl.abort();
    };
  }, [value, minChars, debounceMs, searchAddresses]);

  const pick = (s: AddressSuggestionItem) => {
    skipNext.current = true;
    onSelect(s);
    setOpen(false);
    setActive(-1);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const n = items.length;
    if (e.key === 'ArrowDown' && n) {
      e.preventDefault();
      setOpen(true);
      setActive((a) => (a + 1) % n);
    } else if (e.key === 'ArrowUp' && n) {
      e.preventDefault();
      setActive((a) => (a - 1 + n) % n);
    } else if (e.key === 'Enter' && open && active >= 0 && items[active]) {
      e.preventDefault();
      pick(items[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const showList = open && value.trim().length >= minChars && status !== 'idle';
  return (
    <Field label={label} error={error} help={help} id={id}>
      {(a11y) => (
        <div className={styles.wrap}>
          <input
            {...a11y}
            name={name}
            className={fieldStyles.control}
            value={value}
            placeholder={placeholder}
            autoComplete="off"
            // eslint-disable-next-line jsx-a11y/no-autofocus -- only used inside dialogs that ask for an address
            autoFocus={autoFocus}
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={showList}
            aria-controls={listId}
            aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
            onChange={(e) => {
              onChange(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 120)}
            onKeyDown={onKeyDown}
          />
          {showList && (
            <ul id={listId} role="listbox" className={styles.list} aria-label="Address suggestions">
              {status === 'loading' && items.length === 0 && <li className={styles.status} role="presentation">{loadingLabel}</li>}
              {status === 'error' && <li className={styles.status} role="presentation">{errorLabel}</li>}
              {status === 'ready' && items.length === 0 && <li className={styles.status} role="presentation">{emptyLabel}</li>}
              {items.map((s, i) => (
                <li
                  key={s.id}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className={styles.option}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(s);
                  }}
                  onMouseEnter={() => setActive(i)}
                >
                  <MapPin className={styles.pin} aria-hidden />
                  <span>
                    <strong>{s.label}</strong> <span className={styles.secondary}>{s.secondary}</span>
                  </span>
                </li>
              ))}
              {attribution && items.length > 0 && (
                <li className={styles.footer} role="presentation">
                  {attribution}
                </li>
              )}
            </ul>
          )}
        </div>
      )}
    </Field>
  );
}
