'use client';

import { buildTaxonomyTree, type TaxonomyNode, type TaxonomyTreeNode } from '@afrotransact/api-client';
import { Checkbox, cx, TextField } from '@afrotransact/ui';
import { type ReactNode, useMemo, useState } from 'react';

import { useT } from '@/i18n/t';

const INDENT = ['pl-0', 'pl-6', 'pl-12'];

/**
 * Where a brand applies: a multi-select over the department > category > product type tree. A department covers
 * everything under it; selected places are listed by name below, and a search narrows the tree.
 */
export function ScopePicker({ nodes, value, onChange, error, disabled = false }: { nodes: TaxonomyNode[]; value: string[]; onChange: (next: string[]) => void; error?: string | null; disabled?: boolean }) {
  const t = useT();
  const [q, setQ] = useState('');
  const roots = useMemo(() => buildTaxonomyTree(nodes, false), [nodes]);
  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes]);
  const needle = q.trim().toLowerCase();
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);

  /** A branch is shown when it, or anything under it, matches the search. */
  const shows = (b: TaxonomyTreeNode): boolean => !needle || b.node.path.toLowerCase().includes(needle) || b.children.some(shows);
  const render = (branches: TaxonomyTreeNode[], depth: number): ReactNode =>
    branches.filter(shows).map((b) => (
      <li key={b.node.id}>
        <div className={cx('py-0.5', INDENT[depth] ?? 'pl-12')}>
          <Checkbox label={b.node.name} checked={value.includes(b.node.id)} disabled={disabled} onChange={() => toggle(b.node.id)} />
        </div>
        {b.children.length ? <ul className="m-0 list-none p-0">{render(b.children, depth + 1)}</ul> : null}
      </li>
    ));

  return (
    <div className="flex flex-col gap-2" data-testid="scope-picker">
      <span className="text-sm font-semibold">{t('st.brand.scope.label')}</span>
      <span className="text-xs text-muted">{t('st.brand.scope.help')}</span>
      <TextField label={t('st.brand.scope.search')} type="search" value={q} onChange={(e) => setQ(e.currentTarget.value)} />
      <ul className="m-0 max-h-64 list-none overflow-y-auto rounded-md border border-border bg-ink-0 p-2" aria-label={t('st.brand.scope.label')}>
        {render(roots, 0)}
      </ul>
      <p className="m-0 text-xs text-muted" data-testid="scope-selected">
        {value.length ? t('st.brand.scope.selected', { n: value.length, names: value.map((id) => byId.get(id)?.path ?? '').filter(Boolean).join(', ') }) : t('st.brand.scope.none')}
      </p>
      {error ? (
        <p className="m-0 text-xs text-danger" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
