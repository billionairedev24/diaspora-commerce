'use client';

import { type ProductSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { ImagePlaceholder, MagnifyingGlass } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { type FormEvent, type KeyboardEvent, useEffect, useId, useMemo, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { DEPARTMENTS_LIST, searchHref, searchQueries } from '@/lib/api/queries';
import { productHref } from '@/lib/links';

import styles from './SearchBox.module.css';

type Option = { kind: 'phrase'; text: string; department: string | null; typed: number } | { kind: 'product'; product: ProductSummary };

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** Header search with typeahead: phrases (the first scoped to a department) plus up to five products. */
export function SearchBox() {
  const fmt = useFormatters();
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const listId = `sb${useId().replace(/:/g, '')}`;
  const onResults = pathname === '/s';
  const [query, setQuery] = useState(onResults ? (params.get('q') ?? '') : '');
  const [dept, setDept] = useState(onResults ? (params.get('dept') ?? '') : '');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const debounced = useDebounced(query.trim(), 120);
  const { data } = useQuery({ ...searchQueries.suggestions(browserApi(), debounced), enabled: open && debounced.length > 0 });

  useEffect(() => {
    if (pathname !== '/s') return;
    setQuery(params.get('q') ?? '');
    setDept(params.get('dept') ?? '');
  }, [pathname, params]);

  const options = useMemo<Option[]>(() => {
    if (!data || !query.trim()) return [];
    const typed = query.trim().toLowerCase();
    return [
      ...data.phrases.map((p) => ({ kind: 'phrase' as const, text: p.text, department: p.department, typed: p.text.toLowerCase().startsWith(typed) ? typed.length : 0 })),
      ...data.products.map((product) => ({ kind: 'product' as const, product }))
    ];
  }, [data, query]);

  const go = (o: Option | null) => {
    setOpen(false);
    setActive(-1);
    if (o?.kind === 'product') {
      setQuery('');
      router.push(productHref(o.product.atin));
      return;
    }
    const text = o ? o.text : query.trim();
    const department = o ? o.department : dept || null;
    router.push(searchHref({ q: text, department }));
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    const n = options.length;
    if (e.key === 'ArrowDown' && n) {
      e.preventDefault();
      setOpen(true);
      setActive((a) => (a + 1) % n);
    } else if (e.key === 'ArrowUp' && n) {
      e.preventDefault();
      setActive((a) => (a - 1 + n) % n);
    } else if (e.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    } else if (e.key === 'Enter' && open && active >= 0 && options[active]) {
      e.preventDefault();
      go(options[active] ?? null);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    go(null);
  };

  const show = open && options.length > 0;
  const phrases = options.filter((o) => o.kind === 'phrase');
  const products = options.filter((o) => o.kind === 'product');
  const optId = (i: number) => `${listId}-${i}`;

  return (
    <div className={styles.wrap}>
      <form role="search" className={styles.form} onSubmit={submit}>
        <select className={styles.dept} value={dept} onChange={(e) => setDept(e.target.value)} aria-label={t('header.department')}>
          <option value="">{t('header.all')}</option>
          {DEPARTMENTS_LIST.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <input
          className={styles.input}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKey}
          placeholder={t('header.searchPlaceholder')}
          aria-label={t('header.search')}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={show}
          aria-controls={listId}
          aria-activedescendant={show && active >= 0 ? optId(active) : undefined}
          autoComplete="off"
          enterKeyHint="search"
          name="q"
        />
        <button type="submit" className={styles.submit} aria-label={t('header.search')}>
          <MagnifyingGlass size={20} aria-hidden />
        </button>
      </form>
      {show && (
        <ul id={listId} role="listbox" className={styles.list} aria-label={t('search.suggestions')}>
          {phrases.length > 0 && (
            <li role="presentation" className={styles.head}>
              {t('search.suggestions')}
            </li>
          )}
          {phrases.map((o, i) =>
            o.kind === 'phrase' ? (
              <li
                key={`p-${o.text}-${o.department ?? ''}-${i}`}
                id={optId(i)}
                role="option"
                aria-selected={i === active}
                className={styles.option}
                onMouseDown={(e) => {
                  e.preventDefault();
                  go(o);
                }}
                onMouseEnter={() => setActive(i)}
              >
                <MagnifyingGlass className={styles.optionIcon} aria-hidden />
                <span className={styles.phrase}>
                  {o.text.slice(0, o.typed)}
                  <strong>{o.text.slice(o.typed)}</strong>
                </span>
                {o.department && <span className={styles.scope}>{t('search.in', { department: o.department })}</span>}
              </li>
            ) : null
          )}
          {products.length > 0 && (
            <li role="presentation" className={styles.head}>
              {t('search.products')}
            </li>
          )}
          {products.map((o, j) => {
            if (o.kind !== 'product') return null;
            const i = phrases.length + j;
            const p = o.product;
            const meta = !p.inStock ? t('search.unavailable') : p.fulfilment === 'AFROTRANSACT' ? t('search.fulfilled') : p.fulfilment === 'AFROTRANSACT_DELIVERY' ? t('ld.badge', { city: p.sellerCity }) : t('search.shipsFrom', { city: p.sellerCity });
            return (
              <li
                key={p.atin}
                id={optId(i)}
                role="option"
                aria-selected={i === active}
                className={`${styles.option} ${styles.product}`}
                onMouseDown={(e) => {
                  e.preventDefault();
                  go(o);
                }}
                onMouseEnter={() => setActive(i)}
              >
                <span className={styles.thumb}>
                  <ImagePlaceholder alt="" src={p.imageUrl} />
                </span>
                <span>
                  <span className={styles.pname}>{p.title}</span>
                  <span className={styles.pmeta}>
                    {p.community} · {meta}
                  </span>
                </span>
                <strong className={styles.price}>{fmt.money(p.priceMinor, p.currency)}</strong>
              </li>
            );
          })}
          <li role="presentation" className={styles.foot}>
            {t('search.hint')}
          </li>
        </ul>
      )}
    </div>
  );
}
