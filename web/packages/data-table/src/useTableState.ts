'use client';

import { type OnChangeFn, type PaginationState, type SortingState } from '@tanstack/react-table';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

/* Table state that belongs in the address bar: the search (`?q=`), the sort (`?sort=placedAt,desc`, repeated), the
   page (`?page=`, 1-based) and the page size (`?size=`). A second table on the same page uses a prefix (`orders.q`).
   The URL is written with history.replaceState, which Next.js keeps in sync with useSearchParams. */

/** What the hook reads the initial state from (Next's useSearchParams(), or URLSearchParams in tests). */
export interface SearchParamsLike {
  get(name: string): string | null;
  getAll(name: string): string[];
}

export interface TableStateOptions {
  /** The current URL search params (pass Next's useSearchParams() so server and client render alike). */
  searchParams?: SearchParamsLike | null;
  /** Prefix of the URL params when a page shows more than one table. */
  prefix?: string;
  defaultPageSize?: number;
  defaultSort?: SortingState;
  /** Debounce of the search box (ms). */
  debounceMs?: number;
  /** Write the state into the URL (default true). */
  syncUrl?: boolean;
}

export interface TableState {
  /** What the search box shows (immediate). */
  searchInput: string;
  setSearchInput: (v: string) => void;
  /** The committed (debounced, trimmed) search: send it to the server as `q`. */
  q: string;
  sorting: SortingState;
  setSorting: OnChangeFn<SortingState>;
  pagination: PaginationState;
  setPagination: OnChangeFn<PaginationState>;
  /** The sort as the API takes it: `<column>,asc|desc`. */
  sort: string[];
  /** Cursor of the current page (cursor-paged APIs). */
  cursor: string | undefined;
  /** Remembers the cursor that opens a page (cursor-paged APIs). */
  setCursor: (pageIndex: number, cursor: string | undefined) => void;
  /** Whether a page's cursor is known (cursor-paged APIs can only jump to pages already reached). */
  cursorKnown: (pageIndex: number) => boolean;
  /** Back to the first page (and forget cursors). */
  resetPaging: () => void;
  /** The page size came from the URL (it then wins over the remembered one). */
  sizeFromUrl: boolean;
}

export const PAGE_SIZES = [10, 25, 50, 100];

export function sortToParams(sorting: SortingState): string[] {
  return sorting.map((s) => `${s.id},${s.desc ? 'desc' : 'asc'}`);
}

export function paramsToSort(values: readonly string[]): SortingState {
  const out: SortingState = [];
  values.forEach((v) => {
    const [id = '', dir = 'asc'] = v.split(',');
    if (id && !out.some((s) => s.id === id)) out.push({ id, desc: dir.toLowerCase() === 'desc' });
  });
  return out.slice(0, 3);
}

function writeUrl(update: (p: URLSearchParams) => void) {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  update(url.searchParams);
  const next = `${url.pathname}${url.searchParams.toString() ? `?${url.searchParams.toString()}` : ''}${url.hash}`;
  if (next !== `${window.location.pathname}${window.location.search}${window.location.hash}`) window.history.replaceState(window.history.state, '', next);
}

/** Search, sort and paging of one table, kept in the URL. */
export function useTableState(options: TableStateOptions = {}): TableState {
  const { searchParams, prefix = '', defaultPageSize = 25, defaultSort = [], debounceMs = 300, syncUrl = true } = options;
  const k = useCallback((name: string) => (prefix ? `${prefix}.${name}` : name), [prefix]);
  const initial = useRef({
    q: searchParams?.get(k('q')) ?? '',
    sort: searchParams?.getAll(k('sort')).length ? paramsToSort(searchParams.getAll(k('sort'))) : defaultSort,
    page: Math.max(0, (Number(searchParams?.get(k('page')) ?? '1') || 1) - 1),
    size: Number(searchParams?.get(k('size'))) || defaultPageSize,
    sizeFromUrl: !!searchParams?.get(k('size'))
  }).current;

  const [searchInput, setSearchInputRaw] = useState(initial.q);
  const [q, setQ] = useState(initial.q.trim());
  const [sorting, setSortingRaw] = useState<SortingState>(initial.sort);
  const [pagination, setPaginationRaw] = useState<PaginationState>({ pageIndex: initial.page, pageSize: PAGE_SIZES.includes(initial.size) ? initial.size : defaultPageSize });
  const [cursors, setCursors] = useState<Record<number, string | undefined>>({});

  // Commit the search after the debounce; a new search starts on page one.
  useEffect(() => {
    const next = searchInput.trim();
    if (next === q) return undefined;
    const t = setTimeout(() => {
      setQ(next);
      setPaginationRaw((p) => ({ ...p, pageIndex: 0 }));
      setCursors({});
    }, debounceMs);
    return () => clearTimeout(t);
  }, [searchInput, q, debounceMs]);

  // Mirror the committed state in the URL.
  useEffect(() => {
    if (!syncUrl) return;
    writeUrl((p) => {
      if (q) p.set(k('q'), q);
      else p.delete(k('q'));
      p.delete(k('sort'));
      sortToParams(sorting).forEach((s) => p.append(k('sort'), s));
      if (pagination.pageIndex > 0) p.set(k('page'), String(pagination.pageIndex + 1));
      else p.delete(k('page'));
      if (pagination.pageSize !== defaultPageSize) p.set(k('size'), String(pagination.pageSize));
      else p.delete(k('size'));
    });
  }, [q, sorting, pagination, syncUrl, k, defaultPageSize]);

  const setSorting: OnChangeFn<SortingState> = useCallback((u) => {
    setSortingRaw((old) => (typeof u === 'function' ? u(old) : u));
    setPaginationRaw((p) => ({ ...p, pageIndex: 0 }));
    setCursors({});
  }, []);
  const setPagination: OnChangeFn<PaginationState> = useCallback((u) => {
    setPaginationRaw((old) => {
      const next = typeof u === 'function' ? u(old) : u;
      if (next.pageSize !== old.pageSize) {
        setCursors({});
        return { pageIndex: 0, pageSize: next.pageSize };
      }
      return next;
    });
  }, []);
  const setCursor = useCallback((pageIndex: number, cursor: string | undefined) => setCursors((c) => ({ ...c, [pageIndex]: cursor })), []);
  const resetPaging = useCallback(() => {
    setPaginationRaw((p) => ({ ...p, pageIndex: 0 }));
    setCursors({});
  }, []);

  const sort = useMemo(() => sortToParams(sorting), [sorting]);
  return {
    searchInput,
    setSearchInput: setSearchInputRaw,
    q,
    sorting,
    setSorting,
    pagination,
    setPagination,
    sort,
    cursor: cursors[pagination.pageIndex],
    setCursor,
    cursorKnown: (i: number) => i === 0 || cursors[i] !== undefined,
    resetPaging,
    sizeFromUrl: initial.sizeFromUrl
  };
}
