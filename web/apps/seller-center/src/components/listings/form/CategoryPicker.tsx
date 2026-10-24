'use client';

import { type CategoryNode, CategoryRequestInputSchema, sellerKeys } from '@afrotransact/api-client';
import { Button, Combobox, type ComboboxOption, TextField } from '@afrotransact/ui';
import { HourglassMedium, LockSimple } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { type CategoryLeaf, fieldId, type FormState, leavesOf } from '../form-model';

interface Props {
  tree: CategoryNode[];
  s: FormState;
  locked: boolean;
  error?: string;
  onChoose: (leaf: CategoryLeaf | null, request?: FormState['request']) => void;
}

const REQUESTED = '__requested__';

/** Category: one searchable list over every product type's full path (recent and popular ones first). A name that is not
    in the list can be requested as a new category. */
export function CategoryPicker({ tree, s, locked, error, onChoose }: Props) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const leaves = useMemo(() => leavesOf(tree), [tree]);
  const [use, setUse] = useState<CategoryUse>(() => readUse());
  const [reqOpen, setReqOpen] = useState(false);
  const [req, setReq] = useState({ parentPath: '', name: '', exampleProduct: '', detailsNeeded: '' });
  const [reqErr, setReqErr] = useState<Record<string, string>>({});
  const request = useMutation({
    mutationFn: () => api.requestCategory(req),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.categoryRequests(orgId) });
      setReqOpen(false);
      onChoose(null, { id: r.id, path: r.path, reference: r.reference });
    },
    onError: (e) => setReqErr({ name: problemMessage(e) })
  });

  const options = useMemo<ComboboxOption[]>(() => {
    const list = leaves.map((l) => ({ value: l.id, label: l.name, description: l.path, text: l.path }));
    return s.request ? [{ value: REQUESTED, label: s.request.path, text: s.request.path }, ...list] : list;
  }, [leaves, s.request]);

  if (locked) {
    return (
      <p id={fieldId.category} className="m-0 flex items-center gap-2 text-sm">
        <LockSimple size={16} aria-hidden /> <strong>{s.categoryPath}</strong>
        <span className="text-muted">{t('sc.pf.category.locked')}</span>
      </p>
    );
  }

  const known = new Set(leaves.map((l) => l.id));
  const recent = use.recent.filter((id) => known.has(id));
  const popular = Object.entries(use.counts).filter(([id]) => known.has(id)).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([id]) => id);
  const choose = (id: string) => {
    const leaf = leaves.find((l) => l.id === id);
    if (!leaf) return;
    onChoose(leaf);
    const next = { recent: [id, ...use.recent.filter((x) => x !== id)].slice(0, 5), counts: { ...use.counts, [id]: (use.counts[id] ?? 0) + 1 } };
    setUse(next);
    writeUse(next);
  };

  return (
    <div className="flex flex-col gap-3">
      <Combobox
        id={fieldId.category}
        label={t('sc.pf.field.category')}
        placeholder={t('sc.pf.category.searchPlaceholder')}
        listLabel={t('sc.pf.category.results')}
        options={options}
        value={s.request ? REQUESTED : (s.categoryId ?? '')}
        recent={recent}
        popular={popular}
        recentLabel={t('sc.pf.category.recent')}
        popularLabel={t('sc.pf.category.popular')}
        allLabel={t('sc.pf.category.all')}
        emptyLabel={t('sc.pf.category.noMatch')}
        resultsLabel={(count) => t('sc.pf.category.count', { count })}
        error={error}
        help={s.request ? undefined : t('sc.pf.category.help')}
        onChange={(v) => { if (v !== REQUESTED) choose(v); }}
        onCreate={(text) => { setReq((r) => ({ ...r, name: text })); setReqOpen(true); }}
        createLabel={(text) => t('sc.pf.category.requestNamed', { name: text })}
      />
      {s.request ? (
        <p className="m-0 flex items-center gap-2 text-xs text-muted">
          <HourglassMedium size={16} aria-hidden /> {t('sc.pf.category.requested', { reference: s.request.reference })}
        </p>
      ) : null}
      {reqOpen ? (
        <form
          className="flex flex-col gap-1 rounded-sm bg-surface-muted p-3"
          onSubmit={(e) => {
            e.preventDefault();
            const p = CategoryRequestInputSchema.safeParse(req);
            if (!p.success) return setReqErr(Object.fromEntries(p.error.issues.map((i) => [String(i.path[0]), i.message])));
            setReqErr({});
            request.mutate();
          }}
        >
          <TextField label={t('sc.pf.request.parent')} placeholder={t('sc.pf.request.parentPlaceholder')} value={req.parentPath} error={reqErr.parentPath} onChange={(e) => setReq({ ...req, parentPath: e.target.value })} />
          <TextField label={t('sc.pf.request.name')} value={req.name} error={reqErr.name} onChange={(e) => setReq({ ...req, name: e.target.value })} />
          <TextField label={t('sc.pf.request.example')} value={req.exampleProduct} error={reqErr.exampleProduct} onChange={(e) => setReq({ ...req, exampleProduct: e.target.value })} />
          <TextField label={t('sc.pf.request.details')} value={req.detailsNeeded} error={reqErr.detailsNeeded} onChange={(e) => setReq({ ...req, detailsNeeded: e.target.value })} />
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" size="sm" loading={request.isPending}>{t('sc.pf.request.send')}</Button>
            <Button type="button" size="sm" variant="neutral" onClick={() => setReqOpen(false)}>{t('sc.pf.request.cancel')}</Button>
            <span className="text-xs text-muted">{t('sc.pf.request.note')}</span>
          </div>
        </form>
      ) : null}
    </div>
  );
}

/** What this browser picked before: the last few categories and how often each was used (the "popular" ones). A convenience only. */
interface CategoryUse {
  recent: string[];
  counts: Record<string, number>;
}

const USE_KEY = 'sc.pf.categoryUse';

function readUse(): CategoryUse {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(USE_KEY);
    const v: unknown = raw ? JSON.parse(raw) : null;
    if (typeof v !== 'object' || v === null) return { recent: [], counts: {} };
    const o = v as Partial<CategoryUse>;
    const counts = Object.fromEntries(Object.entries(o.counts ?? {}).filter((e): e is [string, number] => typeof e[1] === 'number'));
    return { recent: Array.isArray(o.recent) ? o.recent.filter((x): x is string => typeof x === 'string').slice(0, 5) : [], counts };
  } catch {
    return { recent: [], counts: {} };
  }
}

function writeUse(use: CategoryUse) {
  try {
    window.localStorage.setItem(USE_KEY, JSON.stringify(use));
  } catch {
    /* private mode: recent picks are a convenience */
  }
}
