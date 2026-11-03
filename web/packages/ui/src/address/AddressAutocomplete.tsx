'use client';

import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { Field, fieldStyles } from '../form/Field';
import { MapPin } from '../icons';
import styles from './AddressAutocomplete.module.css';
import { type AddressSuggestionItem, type SearchAddresses } from './types';

/** A new autocomplete session token: 32 URL-safe characters (the BFF and Google Places accept 8–36). */
export function newSessionToken(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID().replace(/-/g, '');
  let out = '';
  for (let i = 0; i < 32; i += 1) out += Math.floor(Math.random() * 16).toString(16);
  return out;
}

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
  /** Read out while a picked suggestion's full address loads (Google Place Details). */
  resolvingLabel?: string;
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
  minChars = 1,
  debounceMs = 200,
  emptyLabel = 'No matching addresses',
  errorLabel = 'Address suggestions are unavailable — keep typing your full address.',
  loadingLabel = 'Searching…',
  resolvingLabel = 'Loading the address…',
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
  /** The address entry's session token: created on the first search, reused until a suggestion is picked. */
  const session = useRef<string | null>(null);
  const [resolving, setResolving] = useState(false);

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
      session.current ??= newSessionToken();
      searchAddresses(q, { signal: ctrl.signal, sessionToken: session.current })
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
    setOpen(false);
    setActive(-1);
    // The pick ends the session (Place Details closes it); the next entry starts a new one.
    session.current = null;
    if (!s.resolve || s.resolved !== false) {
      onSelect(s);
      return;
    }
    setResolving(true);
    s.resolve()
      .then((address) => onSelect({ ...s, address, resolved: true }))
      // Details unavailable: keep the street line; the rest of the form stays editable.
      .catch(() => onSelect(s))
      .finally(() => setResolving(false));
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
            aria-busy={resolving || undefined}
            onChange={(e) => {
              onChange(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 120)}
            onKeyDown={onKeyDown}
          />
          {resolving && (
            <span className={styles.resolving} role="status">
              {resolvingLabel}
            </span>
          )}
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
