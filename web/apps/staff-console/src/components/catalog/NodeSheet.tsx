'use client';

import {
  isApiError,
  NEW_PRODUCT_TYPE,
  type StaffForm,
  staffTaxonomyKeys,
  staffTaxonomyQueries,
  type TaxonomyLevel,
  type TaxonomyNode,
  type TaxonomyProductType
} from '@afrotransact/api-client';
import { Button, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useState } from 'react';

import { FormFields } from '@/components/resources/FormFields';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { canHaveChildren, CHILD_LEVEL, moveTargets, productsInUse } from './model';
import { TypeEditor } from './TypeEditor';

export type SheetMode = 'view' | 'rename' | 'move' | 'retire' | 'restore' | 'add' | 'details';

export interface SheetTarget {
  /** The node being looked at; null when adding a department. */
  node: TaxonomyNode | null;
  mode: SheetMode;
}

const LEVEL_LABEL: Record<TaxonomyLevel, MessageKey> = {
  DEPARTMENT: 'st.catalog.level.department',
  CATEGORY: 'st.catalog.level.category',
  PRODUCT_TYPE: 'st.catalog.level.productType'
};
const ADD_TITLE: Record<TaxonomyLevel, MessageKey> = {
  DEPARTMENT: 'st.catalog.add.department',
  CATEGORY: 'st.catalog.add.category',
  PRODUCT_TYPE: 'st.catalog.add.productType'
};

/** The level of what is added under a node (a department when nothing is selected). */
const addLevel = (parent: TaxonomyNode | null): TaxonomyLevel => (parent ? (CHILD_LEVEL[parent.level] ?? 'CATEGORY') : 'DEPARTMENT');

const field = (key: string, label: string, type: StaffForm['fields'][number]['type'], required: boolean, extra: Partial<StaffForm['fields'][number]> = {}): StaffForm['fields'][number] => ({ key, label, type, required, ...extra });

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold text-muted">{label}</dt>
      <dd className="m-0 text-sm">{children}</dd>
    </div>
  );
}

function History({ node }: { node: TaxonomyNode }) {
  const t = useT();
  const api = staffApi().taxonomy;
  const q = useQuery(staffTaxonomyQueries.audit(api, node.id));
  if (q.isPending) return <Spinner label={t('st.catalog.loading')} />;
  if (q.isError || !q.data.items.length) return <p className="m-0 text-sm text-muted">{t('st.catalog.history.none')}</p>;
  return (
    <ol className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('st.catalog.history.title')}>
      {q.data.items.map((a) => (
        <li key={a.id} className="flex flex-col gap-0.5 border-l-2 border-border pl-3">
          <span className="text-sm font-semibold">{a.summary}</span>
          <span className="text-xs text-muted">{t('st.catalog.history.by', { name: a.actorName, when: new Date(a.at).toLocaleString('en-US') })}</span>
          <span className="text-xs text-muted">{t('st.catalog.history.reason', { reason: a.reason })}</span>
        </li>
      ))}
    </ol>
  );
}

/**
 * One node of the tree in the shared right-hand sheet: what it is, what depends on it, and its changes (add a child,
 * rename, move, retire or restore, edit the details a product type asks for). Each change is a form with a reason; a
 * form with nothing changed cannot be saved. The server refuses a stale version and a category that has products.
 */
export function NodeSheet({ target, nodes, canEdit, onTarget, onClose }: { target: SheetTarget | null; nodes: TaxonomyNode[]; canEdit: boolean; onTarget: (t: SheetTarget | null) => void; onClose: () => void }) {
  const t = useT();
  const api = staffApi().taxonomy;
  const qc = useQueryClient();
  const toast = useToast();
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const live = target?.node ? (nodes.find((n) => n.id === target.node?.id) ?? target.node) : null;
  const mode = target?.mode ?? 'view';
  const types = useQuery({ ...staffTaxonomyQueries.types(api), enabled: target !== null && (mode === 'add' || mode === 'details' || mode === 'view') });
  const typeCode = live?.productType ?? null;
  const typeQ = useQuery({ ...staffTaxonomyQueries.type(api, typeCode ?? ''), enabled: typeCode !== null && mode === 'details' });

  const refresh = () => void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
  const back = (node: TaxonomyNode | null = live) => {
    setServerErrors({});
    setFormError(null);
    onTarget(node ? { node, mode: 'view' } : null);
  };
  const failed = (e: unknown) => {
    if (isApiError(e) && e.status === 422) {
      setServerErrors(e.fieldErrors);
      setFormError(Object.keys(e.fieldErrors).length ? null : (e.problem.detail ?? null));
    } else {
      setServerErrors({});
      setFormError(problemMessage(e));
      if (isApiError(e) && e.status === 412) refresh();
    }
  };

  const change = useMutation({
    mutationFn: (run: () => Promise<TaxonomyNode>) => run(),
    retry: false,
    onError: failed
  });
  const run = (run: () => Promise<TaxonomyNode>, done: string, next: 'view' | 'close' | 'details' = 'view') =>
    change.mutate(run, {
      onSuccess: (saved) => {
        refresh();
        setServerErrors({});
        setFormError(null);
        toast.show(done);
        if (next === 'close') onClose();
        else onTarget({ node: saved, mode: next });
      }
    });

  const title = !target ? '' : mode === 'add' ? t(ADD_TITLE[addLevel(live)]) : (live?.name ?? '');

  const addForm = (parent: TaxonomyNode | null): StaffForm => {
    const level = addLevel(parent);
    const fields = [field('name', t('st.catalog.field.name'), 'text', true, { maxLength: 80 })];
    if (level === 'PRODUCT_TYPE') {
      const existing = (types.data?.items ?? []).filter((x) => x.code !== 'custom').map((x) => ({ value: x.code, label: x.label }));
      fields.push(
        field('productType', t('st.catalog.field.productType'), 'select', true, {
          options: [{ value: NEW_PRODUCT_TYPE, label: t('st.catalog.add.newType') }, ...existing],
          value: NEW_PRODUCT_TYPE,
          help: t('st.catalog.add.typeHelp')
        }),
        field('basedOn', t('st.catalog.add.basedOn'), 'select', false, {
          options: [{ value: 'custom', label: t('st.catalog.add.general') }, ...existing],
          value: 'custom',
          help: t('st.catalog.add.basedOnHelp')
        })
      );
    }
    fields.push(field('reason', t('st.catalog.field.reason'), 'textarea', true, { maxLength: 1000, help: t('st.catalog.reasonHelp') }));
    return { title: t(ADD_TITLE[level]), submitLabel: t('st.catalog.add.submit'), intro: parent ? t('st.catalog.add.under', { path: parent.path }) : t('st.catalog.add.top'), fields };
  };

  const onAdd = (parent: TaxonomyNode | null, v: Record<string, unknown>) => {
    const level = addLevel(parent);
    const name = String(v.name ?? '');
    const reason = String(v.reason ?? '');
    if (level !== 'PRODUCT_TYPE') {
      run(() => api.createNode({ level, ...(parent ? { parentId: parent.id } : {}), name, reason }), t('st.catalog.add.done', { name }));
      return;
    }
    const choice = String(v.productType ?? NEW_PRODUCT_TYPE);
    if (choice !== NEW_PRODUCT_TYPE) {
      run(() => api.createNode({ level, parentId: parent?.id ?? '', name, reason, productType: choice }), t('st.catalog.add.done', { name }));
      return;
    }
    // A new schema starts from the chosen type's details; the editor opens next to refine them.
    const template = types.data?.items.find((x) => x.code === String(v.basedOn ?? 'custom'));
    run(
      () =>
        api.createNode({
          level,
          parentId: parent?.id ?? '',
          name,
          reason,
          newProductType: { label: name, identifierRule: template?.identifierRule ?? 'GTIN', traits: template?.traits, attributes: template?.attributes ?? [], basedOn: template?.code ?? 'custom' }
        }),
      t('st.catalog.add.doneType', { name }),
      'details'
    );
  };

  const renameForm = (n: TaxonomyNode): StaffForm => ({
    title: t('st.catalog.rename.title'),
    submitLabel: t('st.catalog.rename.submit'),
    intro: t('st.catalog.rename.intro', { path: n.path }),
    changeKeys: ['name'],
    fields: [field('name', t('st.catalog.field.name'), 'text', true, { maxLength: 80, value: n.name }), field('reason', t('st.catalog.field.reason'), 'textarea', true, { maxLength: 1000, help: t('st.catalog.reasonHelp') })]
  });
  const moveForm = (n: TaxonomyNode): StaffForm => ({
    title: t('st.catalog.move.title'),
    submitLabel: t('st.catalog.move.submit'),
    intro: t('st.catalog.move.intro', { path: n.path }),
    changeKeys: ['parentId'],
    fields: [
      field('parentId', t(n.level === 'CATEGORY' ? 'st.catalog.move.toDepartment' : 'st.catalog.move.toCategory'), 'select', true, { options: moveTargets(nodes, n).map((p) => ({ value: p.id, label: p.path })) }),
      field('reason', t('st.catalog.field.reason'), 'textarea', true, { maxLength: 1000, help: t('st.catalog.reasonHelp') })
    ]
  });
  const reasonForm = (title: string, submit: string, intro: string): StaffForm => ({
    title,
    submitLabel: submit,
    intro,
    fields: [field('reason', t('st.catalog.field.reason'), 'textarea', true, { maxLength: 1000, help: t('st.catalog.reasonHelp') })]
  });

  const renderBody = () => {
    if (!target) return null;
    if (mode === 'add') {
      return <FormFields key={`add-${live?.id ?? 'top'}-${types.data ? 'ready' : 'wait'}`} form={addForm(live)} busy={change.isPending} serverErrors={serverErrors} error={formError} onCancel={() => back(live)} onSubmit={(v) => onAdd(live, v)} />;
    }
    if (!live) return null;
    if (mode === 'rename') {
      return <FormFields form={renameForm(live)} busy={change.isPending} serverErrors={serverErrors} error={formError} onCancel={() => back()} onSubmit={(v) => run(() => api.renameNode(live.id, { name: String(v.name), reason: String(v.reason) }, live.version), t('st.catalog.rename.done', { name: String(v.name) }))} />;
    }
    if (mode === 'move') {
      return <FormFields form={moveForm(live)} busy={change.isPending} serverErrors={serverErrors} error={formError} onCancel={() => back()} onSubmit={(v) => run(() => api.moveNode(live.id, { parentId: String(v.parentId), reason: String(v.reason) }, live.version), t('st.catalog.move.done', { name: live.name }))} />;
    }
    if (mode === 'retire') {
      return (
        <FormFields
          form={reasonForm(t('st.catalog.retire.title'), t('st.catalog.retire.submit'), t('st.catalog.retire.intro', { path: live.path }))}
          busy={change.isPending}
          serverErrors={serverErrors}
          error={formError}
          onCancel={() => back()}
          onSubmit={(v) => run(() => api.retireNode(live.id, String(v.reason), live.version), t('st.catalog.retire.done', { name: live.name }))}
        />
      );
    }
    if (mode === 'restore') {
      return (
        <FormFields
          form={reasonForm(t('st.catalog.restore.title'), t('st.catalog.restore.submit'), t('st.catalog.restore.intro', { path: live.path }))}
          busy={change.isPending}
          serverErrors={serverErrors}
          error={formError}
          onCancel={() => back()}
          onSubmit={(v) => run(() => api.restoreNode(live.id, String(v.reason), live.version), t('st.catalog.restore.done', { name: live.name }))}
        />
      );
    }
    if (mode === 'details') {
      if (typeQ.isError) return <ErrorState error={typeQ.error} onRetry={() => void typeQ.refetch()} />;
      const type: TaxonomyProductType | undefined = typeQ.data;
      if (!type) return <Spinner label={t('st.catalog.loading')} />;
      return (
        <div className="flex flex-col gap-3">
          <div>
            <Button variant="link" onClick={() => back()}>
              {t('st.catalog.back')}
            </Button>
          </div>
          <TypeEditor key={`${type.code}-${type.version}`} type={type} canEdit={canEdit} />
        </div>
      );
    }
    return <NodeDetails node={live} nodes={nodes} canEdit={canEdit} onMode={(m) => onTarget({ node: live, mode: m })} />;
  };

  return (
    <Sheet
      open={target !== null}
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
      title={title}
      description={live?.path ?? t('st.catalog.sheet.top')}
    >
      <div className="flex flex-col gap-4" data-testid="node-sheet">
        {mode !== 'view' && mode !== 'details' && live ? (
          <p className="m-0 text-xs text-muted">{t('st.catalog.sheet.editing', { path: live.path })}</p>
        ) : null}
        {renderBody()}
      </div>
    </Sheet>
  );
}

function NodeDetails({ node, nodes, canEdit, onMode }: { node: TaxonomyNode; nodes: TaxonomyNode[]; canEdit: boolean; onMode: (m: SheetMode) => void }) {
  const t = useT();
  const inUse = productsInUse(node);
  const parent = nodes.find((n) => n.id === node.parentId);
  const canMove = node.level !== 'DEPARTMENT' && node.active && moveTargets(nodes, node).length > 0;
  return (
    <>
      <dl className="m-0 grid gap-3 sm:grid-cols-2">
        <Detail label={t('st.catalog.detail.level')}>{t(LEVEL_LABEL[node.level])}</Detail>
        <Detail label={t('st.catalog.detail.path')}>{node.path}</Detail>
        {parent ? <Detail label={t('st.catalog.detail.parent')}>{parent.name}</Detail> : null}
        <Detail label={t('st.catalog.detail.code')}>
          <span className="font-mono text-xs">{node.code}</span>
        </Detail>
        {node.productTypeLabel ? <Detail label={t('st.catalog.detail.productType')}>{node.productTypeLabel}</Detail> : null}
        <Detail label={t('st.catalog.detail.products')}>{t('st.catalog.detail.productsValue', { own: node.productCount, below: node.subtreeProductCount })}</Detail>
        <Detail label={t('st.catalog.detail.status')}>{node.active ? t('st.catalog.status.active') : t('st.catalog.status.retired')}</Detail>
        {!node.active && node.retiredReason ? <Detail label={t('st.catalog.detail.retiredFor')}>{node.retiredReason}</Detail> : null}
      </dl>
      {canEdit ? (
        <div className="flex flex-wrap gap-2">
          {canHaveChildren(node) ? (
            <Button size="sm" onClick={() => onMode('add')}>
              {t(ADD_TITLE[CHILD_LEVEL[node.level] ?? 'CATEGORY'])}
            </Button>
          ) : null}
          {node.level === 'PRODUCT_TYPE' ? (
            <Button size="sm" variant="secondary" onClick={() => onMode('details')}>
              {t('st.catalog.detail.editDetails')}
            </Button>
          ) : null}
          <Button size="sm" variant="neutral" onClick={() => onMode('rename')}>
            {t('st.catalog.rename.title')}
          </Button>
          {canMove ? (
            <Button size="sm" variant="neutral" onClick={() => onMode('move')}>
              {t('st.catalog.move.title')}
            </Button>
          ) : null}
          {node.active ? (
            <Button size="sm" variant="neutral" disabled={inUse > 0} onClick={() => onMode('retire')}>
              {t('st.catalog.retire.title')}
            </Button>
          ) : (
            <Button size="sm" variant="neutral" onClick={() => onMode('restore')}>
              {t('st.catalog.restore.title')}
            </Button>
          )}
        </div>
      ) : (
        <Notice tone="info" title={t('st.catalog.readOnly')} />
      )}
      {node.active && inUse > 0 && canEdit ? <p className="m-0 text-xs text-muted">{t('st.catalog.retire.blocked', { n: inUse })}</p> : null}
      {node.level === 'PRODUCT_TYPE' && node.productType ? (
        <p className="m-0 text-xs text-muted">{t('st.catalog.detail.sharedType')}</p>
      ) : null}
      <section className="flex flex-col gap-2">
        <h3 className="m-0 text-sm font-semibold">{t('st.catalog.history.title')}</h3>
        <History node={node} />
      </section>
    </>
  );
}
