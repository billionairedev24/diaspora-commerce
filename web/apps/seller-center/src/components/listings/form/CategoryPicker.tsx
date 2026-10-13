'use client';

import { type CategoryNode, CategoryRequestInputSchema, sellerKeys } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { CaretRight, HourglassMedium, LockSimple, MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type KeyboardEvent, useMemo, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { type CategoryLeaf, fieldId, type FormState, leavesOf, searchLeaves } from '../form-model';

interface Props {
  tree: CategoryNode[];
  s: FormState;
  locked: boolean;
  error?: string;
  onChoose: (leaf: CategoryLeaf | null, request?: FormState['request']) => void;
}

/** Category first: search every product type, or browse the tree (department › category › product type); request one that is missing. */
export function CategoryPicker({ tree, s, locked, error, onChoose }: Props) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const leaves = useMemo(() => leavesOf(tree), [tree]);
  const [query, setQuery] = useState('');
  const [browse, setBrowse] = useState(false);
  const [open, setOpen] = useState<string[]>([]);
  const [reqOpen, setReqOpen] = useState(false);
  const [req, setReq] = useState({ parentPath: '', name: '', exampleProduct: '', detailsNeeded: '' });
  const [reqErr, setReqErr] = useState<Record<string, string>>({});
  const [focused, setFocused] = useState(false);
  const [active, setActive] = useState(0);
  const [recent, setRecent] = useState<CategoryLeaf[]>(() => readRecent());
  const searched = query.trim() ? searchLeaves(leaves, query) : [];
  const hits = query.trim() ? searched : [...recent.filter((r) => leaves.some((l) => l.id === r.id)), ...leaves.filter((l) => !recent.some((r) => r.id === l.id))].slice(0, 40);
  const listOpen = focused && hits.length > 0;
  const chosen = Boolean(s.categoryId || s.request);
  const request = useMutation({
    mutationFn: () => api.requestCategory(req),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.categoryRequests(orgId) });
      setReqOpen(false);
      onChoose(null, { id: r.id, path: r.path, reference: r.reference });
    },
    onError: (e) => setReqErr({ name: problemMessage(e) })
  });

  if (locked) {
    return (
      <p id={fieldId.category} className="m-0 flex items-center gap-2 text-sm">
        <LockSimple size={16} aria-hidden /> <strong>{s.categoryPath}</strong>
        <span className="text-muted">{t('sc.pf.category.locked')}</span>
      </p>
    );
  }

  const pick = (l: CategoryLeaf) => {
    onChoose(l);
    setQuery('');
    setBrowse(false);
    setFocused(false);
    const next = [l, ...recent.filter((r) => r.id !== l.id)].slice(0, 5);
    setRecent(next);
    writeRecent(next);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setFocused(true); setActive((a) => Math.min(a + 1, hits.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter' && listOpen) { e.preventDefault(); const h = hits[active]; if (h) pick(h); }
    else if (e.key === 'Escape') setFocused(false);
  };
  const toggle = (id: string) => setOpen((o) => (o.includes(id) ? o.filter((x) => x !== id) : [...o, id]));
  const branch = (nodes: CategoryNode[], depth: number) => (
    <ul className={cx('m-0 list-none p-0', depth > 0 && 'ml-4 border-l border-border pl-2')}>
      {nodes.map((n) =>
        n.level === 'PRODUCT_TYPE' ? (
          <li key={n.id}>
            <button type="button" onClick={() => pick({ id: n.id, name: n.name, path: n.path, productType: n.productType ?? '' })}
              className={cx('w-full rounded-sm border-0 px-2 py-1.5 text-left text-sm hover:bg-surface-hover', s.categoryId === n.id ? 'bg-gold-50 font-semibold' : 'bg-transparent')}>
              {n.name}
            </button>
          </li>
        ) : (
          <li key={n.id}>
            <button type="button" aria-expanded={open.includes(n.id)} onClick={() => toggle(n.id)}
              className="flex w-full items-center gap-1 rounded-sm border-0 bg-transparent px-2 py-1.5 text-left text-sm font-semibold hover:bg-surface-hover">
              <CaretRight size={12} aria-hidden className={cx('transition-transform', open.includes(n.id) && 'rotate-90')} /> {n.name}
            </button>
            {open.includes(n.id) ? branch(n.children, depth + 1) : null}
          </li>
        )
      )}
    </ul>
  );

  return (
    <div className="flex flex-col gap-3">
      {chosen ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-sm bg-surface-muted px-3 py-2 text-sm">
          {s.request ? <HourglassMedium size={16} aria-hidden /> : null}
          <strong data-testid="chosen-category">{s.categoryPath}</strong>
          {s.request ? <span className="text-xs text-muted">{t('sc.pf.category.requested', { reference: s.request.reference })}</span> : null}
          <button type="button" className="link-button ml-auto border-0 bg-transparent p-0 text-sm text-link hover:underline" onClick={() => onChoose(null)}>
            {t('sc.pf.category.change')}
          </button>
        </div>
      ) : (
        <>
          <div className="relative">
            <TextField id={fieldId.category} label={t('sc.pf.category.search')} placeholder={t('sc.pf.category.searchPlaceholder')} value={query} error={error}
              autoComplete="off" role="combobox" aria-expanded={listOpen} aria-controls="pf-category-results" aria-autocomplete="list"
              aria-activedescendant={listOpen ? `pf-cat-${active}` : undefined}
              onFocus={() => setFocused(true)} onBlur={() => window.setTimeout(() => setFocused(false), 150)} onKeyDown={onKey}
              onChange={(e) => { setQuery(e.target.value); setActive(0); setFocused(true); }} />
            <MagnifyingGlass size={16} aria-hidden className="pointer-events-none absolute top-[34px] right-3 text-muted" />
            {listOpen ? (
              <ul id="pf-category-results" role="listbox" aria-label={t('sc.pf.category.results')}
                className="absolute inset-x-0 top-full z-20 m-0 mt-1 flex max-h-80 list-none flex-col overflow-y-auto rounded-sm border border-border bg-surface p-1 shadow-lg">
                {hits.map((l, i) => (
                  <li key={l.id} id={`pf-cat-${i}`} role="option" aria-selected={i === active}>
                    <button type="button" tabIndex={-1} onMouseDown={(e) => e.preventDefault()} onClick={() => pick(l)} onMouseEnter={() => setActive(i)}
                      className={cx('flex w-full flex-col items-start rounded-xs border-0 px-3 py-1.5 text-left', i === active ? 'bg-gold-50' : 'bg-transparent')}>
                      <span className="text-sm font-semibold">{l.name}{!query.trim() && recent.some((r) => r.id === l.id) ? <span className="ml-2 text-2xs font-normal text-muted">{t('sc.pf.category.recent')}</span> : null}</span>
                      <span className="text-xs text-muted">{l.path}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
          {query.trim() && !searched.length ? <p className="m-0 text-sm text-muted">{t('sc.pf.category.noMatch')}</p> : null}
          <div className="flex flex-wrap gap-x-4 gap-y-1">
            <button type="button" className="link-button border-0 bg-transparent p-0 text-sm text-link hover:underline" aria-expanded={browse} onClick={() => setBrowse(!browse)}>
              {t('sc.pf.category.browse')}
            </button>
            <button type="button" className="link-button border-0 bg-transparent p-0 text-sm text-link hover:underline" aria-expanded={reqOpen} onClick={() => setReqOpen(!reqOpen)}>
              {t('sc.pf.category.request')}
            </button>
          </div>
          {browse ? <nav aria-label={t('sc.pf.category.tree')} className="rounded-sm border border-border p-2">{branch(tree, 0)}</nav> : null}
        </>
      )}
      {reqOpen && !chosen ? (
        <form
          className="grid gap-x-3 rounded-sm bg-surface-muted p-3 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            const p = CategoryRequestInputSchema.safeParse(req);
            if (!p.success) return setReqErr(Object.fromEntries(p.error.issues.map((i) => [String(i.path[0]), i.message])));
            setReqErr({});
            request.mutate();
          }}
        >
          <div className="sm:col-span-2"><TextField label={t('sc.pf.request.parent')} placeholder={t('sc.pf.request.parentPlaceholder')} value={req.parentPath} error={reqErr.parentPath} onChange={(e) => setReq({ ...req, parentPath: e.target.value })} /></div>
          <TextField label={t('sc.pf.request.name')} value={req.name} error={reqErr.name} onChange={(e) => setReq({ ...req, name: e.target.value })} />
          <TextField label={t('sc.pf.request.example')} value={req.exampleProduct} error={reqErr.exampleProduct} onChange={(e) => setReq({ ...req, exampleProduct: e.target.value })} />
          <div className="sm:col-span-2"><TextField label={t('sc.pf.request.details')} value={req.detailsNeeded} error={reqErr.detailsNeeded} onChange={(e) => setReq({ ...req, detailsNeeded: e.target.value })} /></div>
          <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
            <Button type="submit" size="sm" loading={request.isPending}>{t('sc.pf.request.send')}</Button>
            <span className="text-xs text-muted">{t('sc.pf.request.note')}</span>
          </div>
        </form>
      ) : null}
    </div>
  );
}

const RECENT_KEY = 'sc.pf.recentCategories';

function readRecent(): CategoryLeaf[] {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(RECENT_KEY);
    const v: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(v) ? (v as CategoryLeaf[]).filter((x) => typeof x?.id === 'string').slice(0, 5) : [];
  } catch {
    return [];
  }
}

function writeRecent(list: CategoryLeaf[]) {
  try {
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    /* private mode: recent picks are a convenience */
  }
}
