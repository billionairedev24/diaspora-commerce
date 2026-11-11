'use client';

import { useQuery } from '@tanstack/react-query';
import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './Services.module.css';

const DEBOUNCE_MS = 300;

/**
 * "What do you need?": ONE input. It filters as you type (debounced) and on Enter, and suggests service types and provider
 * names (ARIA 1.2 combobox: ↑/↓ move, Enter picks or searches, Esc closes). There is no Search button and no ZIP box: the
 * place is the deliver-to location.
 */
export function ServiceSearchBox({ value, onSearch }: { value: string; onSearch: (text: string) => void }) {
  const t = useT();
  const api = browserApi();
  const listId = useId();
  const [text, setText] = useState(value);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const suggestions = useQuery(shopperQueries.serviceSuggest(api, text.trim()));
  const options = open && text.trim().length >= 2 ? (suggestions.data ?? []) : [];

  useEffect(() => () => clearTimeout(timer.current), []);

  const commit = (next: string) => {
    clearTimeout(timer.current);
    setText(next);
    setOpen(false);
    setActive(-1);
    onSearch(next.trim());
  };

  const onChange = (next: string) => {
    setText(next);
    setOpen(true);
    setActive(-1);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => onSearch(next.trim()), DEBOUNCE_MS);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' && options.length) {
      e.preventDefault();
      setActive((i) => (i + 1) % options.length);
    } else if (e.key === 'ArrowUp' && options.length) {
      e.preventDefault();
      setActive((i) => (i <= 0 ? options.length - 1 : i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      commit(options[active] ?? text);
    } else if (e.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    }
  };

  return (
    <div className={styles.searchBox} role="search" aria-label={t('svc.searchAria')}>
      <label className={styles.searchLabel} htmlFor={`${listId}-input`}>
        {t('svc.what')}
      </label>
      <input
        id={`${listId}-input`}
        className={styles.searchInput}
        type="search"
        role="combobox"
        aria-expanded={options.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
        value={text}
        placeholder={t('svc.whatPlaceholder')}
        autoComplete="off"
        maxLength={120}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onFocus={() => setOpen(true)}
      />
      {options.length > 0 && (
        <ul id={listId} className={styles.suggestions} role="listbox" aria-label={t('svc.suggestions')}>
          {options.map((o, i) => (
            <li key={o} id={`${listId}-${i}`} role="option" aria-selected={i === active} className={styles.suggestion} onMouseDown={(e) => { e.preventDefault(); commit(o); }}>
              {o}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
