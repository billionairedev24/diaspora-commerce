'use client';

import { type CategoryNode, CategoryRequestInputSchema, sellerKeys } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { CaretRight, HourglassMedium, LockSimple, MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

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
  const hits = searchLeaves(leaves, query);
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
              autoComplete="off" role="combobox" aria-expanded={hits.length > 0} aria-controls="pf-category-results" onChange={(e) => setQuery(e.target.value)} />
            <MagnifyingGlass size={16} aria-hidden className="pointer-events-none absolute top-[34px] right-3 text-muted" />
          </div>
          {query.trim() ? (
            hits.length ? (
              <ul id="pf-category-results" role="listbox" aria-label={t('sc.pf.category.results')} className="m-0 flex list-none flex-col gap-1 p-0">
                {hits.map((l) => (
                  <li key={l.id} role="option" aria-selected={false}>
                    <button type="button" onClick={() => pick(l)} className="flex w-full flex-col items-start rounded-sm border border-border bg-surface px-3 py-2 text-left hover:border-gold-500">
                      <span className="text-sm font-semibold">{l.name}</span>
                      <span className="text-xs text-muted">{l.path}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="m-0 text-sm text-muted">{t('sc.pf.category.noMatch')}</p>
            )
          ) : null}
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
