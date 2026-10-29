'use client';

import {
  buildTaxonomyTree,
  isApiError,
  siblingsOf,
  staffTaxonomyKeys,
  staffTaxonomyQueries,
  type TaxonomyLevel,
  type TaxonomyNode,
  type TaxonomyTreeNode
} from '@afrotransact/api-client';
import { Button, cx, Dialog, TextField, useToast } from '@afrotransact/ui';
import { ArrowDown, ArrowUp, CaretDown, CaretRight, Plus } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { EmptyState, ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { moveAmongVisible } from './model';
import { NodeSheet, type SheetTarget } from './NodeSheet';

const LEVEL_LABEL: Record<TaxonomyLevel, MessageKey> = {
  DEPARTMENT: 'st.catalog.level.department',
  CATEGORY: 'st.catalog.level.category',
  PRODUCT_TYPE: 'st.catalog.level.productType'
};
const INDENT = ['pl-2', 'pl-8', 'pl-14'];

interface Staged {
  parentId: string | null;
  ids: string[];
}

/** A row's children in the order shown: the staged order of that parent when one is being arranged. */
function ordered(children: TaxonomyTreeNode[], staged: Staged | null, parentId: string | null): TaxonomyTreeNode[] {
  if (!staged || staged.parentId !== parentId) return children;
  return children.slice().sort((a, b) => staged.ids.indexOf(a.node.id) - staged.ids.indexOf(b.node.id));
}

function matches(n: TaxonomyNode, q: string): boolean {
  return n.name.toLowerCase().includes(q) || n.path.toLowerCase().includes(q) || (n.productTypeLabel ?? '').toLowerCase().includes(q);
}

/**
 * Catalog → Categories: the department › category › product type tree with the number of products under each, where
 * staff add, rename, move, reorder, retire and restore, and edit the details each product type asks sellers for.
 * A category that products still use cannot be retired; retired ones are hidden until asked for.
 */
export function CategoriesView() {
  const t = useT();
  const api = staffApi().taxonomy;
  const qc = useQueryClient();
  const toast = useToast();
  const tree = useQuery(staffTaxonomyQueries.tree(api));
  const [target, setTarget] = useState<SheetTarget | null>(null);
  const [expanded, setExpanded] = useState<Set<string> | null>(null);
  const [showRetired, setShowRetired] = useState(false);
  const [search, setSearch] = useState('');
  const [staged, setStaged] = useState<Staged | null>(null);
  const [reasonOpen, setReasonOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [reorderError, setReorderError] = useState<string | null>(null);

  const nodes = useMemo(() => tree.data?.nodes ?? [], [tree.data]);
  const roots = useMemo(() => buildTaxonomyTree(nodes, showRetired), [nodes, showRetired]);
  const open = expanded ?? new Set(nodes.filter((n) => n.level !== 'PRODUCT_TYPE').map((n) => n.id));
  const q = search.trim().toLowerCase();
  const canEdit = tree.data?.canEdit ?? false;
  const retiredCount = nodes.filter((n) => !n.active).length;

  const save = useMutation({
    mutationFn: (v: { staged: Staged; reason: string }) => api.reorder({ parentId: v.staged.parentId, ids: v.staged.ids, reason: v.reason }),
    retry: false,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
      toast.show(t('st.catalog.reorder.done'));
      setStaged(null);
      setReasonOpen(false);
      setReason('');
      setReorderError(null);
    },
    onError: (e) => {
      setReorderError(problemMessage(e));
      if (isApiError(e) && e.status === 412) {
        setStaged(null);
        void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
      }
    }
  });

  const toggle = (id: string) => {
    const next = new Set(open);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setExpanded(next);
  };
  const allIds = nodes.filter((n) => n.level !== 'PRODUCT_TYPE').map((n) => n.id);

  const stage = (node: TaxonomyNode, visibleIds: string[], by: -1 | 1) => {
    const parentId = node.parentId;
    const base = staged && staged.parentId === parentId ? staged.ids : siblingsOf(nodes, parentId).map((n) => n.id);
    const ids = moveAmongVisible(base, visibleIds, node.id, by);
    const original = siblingsOf(nodes, parentId).map((n) => n.id);
    setStaged(ids.join() === original.join() ? null : { parentId, ids });
  };

  const renderLevel = (children: TaxonomyTreeNode[], depth: number, parentId: string | null): ReactNode => {
    const shown = ordered(children, staged, parentId);
    const visibleIds = shown.map((c) => c.node.id);
    return shown.map(({ node, children: kids }, i) => {
      const isOpen = open.has(node.id);
      const hasKids = kids.length > 0;
      return (
        <li key={node.id} role="treeitem" aria-selected={target?.node?.id === node.id} aria-expanded={hasKids ? isOpen : undefined} aria-level={depth + 1} data-testid="tree-node">
          <div className={cx('flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border py-1 pr-2', INDENT[depth] ?? 'pl-14', !node.active && 'bg-ink-25 text-muted')}>
            {hasKids ? (
              <button type="button" onClick={() => toggle(node.id)} aria-label={t(isOpen ? 'st.catalog.tree.close' : 'st.catalog.tree.open', { name: node.name })} className="inline-flex size-8 flex-none cursor-pointer items-center justify-center border-0 bg-transparent p-0 text-ink-500">
                {isOpen ? <CaretDown size={14} aria-hidden /> : <CaretRight size={14} aria-hidden />}
              </button>
            ) : (
              <span className="size-8 flex-none" aria-hidden />
            )}
            <button type="button" onClick={() => setTarget({ node, mode: 'view' })} className="inline-flex min-h-9 min-w-0 flex-1 cursor-pointer flex-wrap items-center gap-x-2 border-0 bg-transparent p-0 text-left text-text" aria-label={t('st.catalog.tree.openNode', { name: node.name })}>
              <strong className={cx('text-sm', node.level === 'DEPARTMENT' && 'text-base')}>{node.name}</strong>
              <span className="text-xs text-muted">{t(LEVEL_LABEL[node.level])}</span>
              {node.productTypeLabel ? <span className="text-xs text-muted">· {node.productTypeLabel}</span> : null}
              {!node.active ? <span className="rounded-pill bg-ink-100 px-2 py-0.5 text-2xs font-bold text-muted">{t('st.catalog.status.retired')}</span> : null}
            </button>
            <span className="w-28 text-right text-xs text-muted" data-testid="node-count">
              {t('st.catalog.tree.products', { n: node.subtreeProductCount })}
            </span>
            {canEdit && !q ? (
              <span className="flex items-center gap-1">
                <Button variant="neutral" size="sm" disabled={i === 0 || save.isPending} onClick={() => stage(node, visibleIds, -1)} aria-label={t('st.catalog.tree.moveUp', { name: node.name })}>
                  <ArrowUp size={14} aria-hidden />
                </Button>
                <Button variant="neutral" size="sm" disabled={i === shown.length - 1 || save.isPending} onClick={() => stage(node, visibleIds, 1)} aria-label={t('st.catalog.tree.moveDown', { name: node.name })}>
                  <ArrowDown size={14} aria-hidden />
                </Button>
              </span>
            ) : null}
          </div>
          {hasKids && isOpen ? <ul role="group" className="m-0 list-none p-0">{renderLevel(kids, depth + 1, node.id)}</ul> : null}
        </li>
      );
    });
  };

  if (tree.isPending) return <Spinner label={t('st.catalog.loading')} />;
  if (tree.isError) return <ErrorState error={tree.error} onRetry={() => void tree.refetch()} />;

  const found = q ? nodes.filter((n) => (showRetired || n.active) && matches(n, q)).sort((a, b) => a.path.localeCompare(b.path)) : [];
  const stagedParent = staged ? (staged.parentId ? (nodes.find((n) => n.id === staged.parentId)?.path ?? '') : t('st.catalog.reorder.departments')) : '';

  return (
    <div className="flex flex-col gap-3" data-testid="categories-view">
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-48 flex-1">
          <TextField label={t('st.catalog.search')} type="search" value={search} onChange={(e) => setSearch(e.currentTarget.value)} placeholder={t('st.catalog.searchPlaceholder')} />
        </div>
        <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm sm:min-h-8">
          <input type="checkbox" className="size-5 sm:size-4" checked={showRetired} onChange={(e) => setShowRetired(e.currentTarget.checked)} />
          {t('st.catalog.showRetired')}
          {!showRetired && retiredCount > 0 ? <span className="text-xs text-muted">{t('st.catalog.retiredHidden', { n: retiredCount })}</span> : null}
        </label>
        {!q ? (
          <>
            <Button variant="neutral" size="sm" onClick={() => setExpanded(new Set(allIds))}>
              {t('st.catalog.expandAll')}
            </Button>
            <Button variant="neutral" size="sm" onClick={() => setExpanded(new Set())}>
              {t('st.catalog.collapseAll')}
            </Button>
          </>
        ) : null}
        {canEdit ? (
          <Button size="sm" icon={<Plus size={14} aria-hidden />} onClick={() => setTarget({ node: null, mode: 'add' })}>
            {t('st.catalog.add.department')}
          </Button>
        ) : null}
      </div>
      {!canEdit ? <Notice tone="info" icon="eye" title={t('st.catalog.readOnly')} /> : null}
      {staged ? (
        <Notice
          tone="info"
          title={t('st.catalog.reorder.staged', { parent: stagedParent })}
          action={
            <span className="flex gap-2">
              <Button size="sm" onClick={() => setReasonOpen(true)}>
                {t('st.catalog.reorder.save')}
              </Button>
              <Button size="sm" variant="neutral" onClick={() => setStaged(null)}>
                {t('st.catalog.discard')}
              </Button>
            </span>
          }
        />
      ) : null}
      {q ? (
        found.length ? (
          <ul className="m-0 flex list-none flex-col rounded-md border border-border bg-ink-0 p-0" aria-label={t('st.catalog.treeLabel')}>
            {found.map((n) => (
              <li key={n.id} className="border-b border-border last:border-b-0">
                <button type="button" onClick={() => setTarget({ node: n, mode: 'view' })} className="flex min-h-11 w-full cursor-pointer flex-wrap items-center gap-x-3 border-0 bg-transparent px-3 py-1 text-left text-text">
                  <strong className="text-sm">{n.name}</strong>
                  <span className="text-xs text-muted">{n.path}</span>
                  <span className="flex-1" />
                  <span className="text-xs text-muted">{t('st.catalog.tree.products', { n: n.subtreeProductCount })}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState title={t('st.catalog.noMatch')}>{t('st.catalog.noMatchBody', { q: search.trim() })}</EmptyState>
        )
      ) : roots.length ? (
        <ul role="tree" aria-label={t('st.catalog.treeLabel')} className="m-0 flex list-none flex-col rounded-md border border-border bg-ink-0 p-0">
          {renderLevel(roots, 0, null)}
        </ul>
      ) : (
        <EmptyState title={t('st.catalog.empty')} />
      )}
      <NodeSheet target={target} nodes={nodes} canEdit={canEdit} onTarget={setTarget} onClose={() => setTarget(null)} />
      <Dialog open={reasonOpen} onOpenChange={(o) => !o && setReasonOpen(false)} title={t('st.catalog.reorder.title')} description={t('st.catalog.reorder.intro', { parent: stagedParent })} closeLabel={t('st.catalog.close')}>
        <form
          noValidate
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (staged && reason.trim().length >= 3 && !save.isPending) save.mutate({ staged, reason: reason.trim() });
          }}
        >
          <TextField label={t('st.catalog.field.reason')} help={t('st.catalog.reasonHelp')} value={reason} maxLength={1000} onChange={(e) => setReason(e.currentTarget.value)} />
          {reorderError ? (
            <Notice tone="danger" title={t('st.catalog.notSaved')} role="alert">
              {reorderError}
            </Notice>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={() => setReasonOpen(false)}>
              {t('st.catalog.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending} disabled={reason.trim().length < 3}>
              {t('st.catalog.reorder.save')}
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
