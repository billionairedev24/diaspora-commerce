'use client';

import {
  type BrandBulkResult,
  brandChanged,
  isApiError,
  type StaffBrand,
  staffTaxonomyKeys,
  staffTaxonomyQueries,
  type TaxonomyNode
} from '@afrotransact/api-client';
import { Button, Combobox, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type ReactNode, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { type BrandAction, brandActions, previewBrandList } from './brandModel';
import { ScopePicker } from './ScopePicker';

export type BrandMode = 'view' | BrandAction | 'add' | 'bulk';

export interface BrandTarget {
  /** The brand being looked at; null when adding. */
  brand: StaffBrand | null;
  mode: BrandMode;
}

export const STATUS_LABEL: Record<StaffBrand['status'], MessageKey> = {
  PENDING: 'st.brand.status.PENDING',
  APPROVED: 'st.brand.status.APPROVED',
  REJECTED: 'st.brand.status.REJECTED',
  MERGED_INTO: 'st.brand.status.MERGED_INTO',
  RETIRED: 'st.brand.status.RETIRED'
};

const ACTION_LABEL: Record<BrandAction, MessageKey> = {
  approve: 'st.brand.approve.title',
  reject: 'st.brand.reject.title',
  merge: 'st.brand.merge.title',
  edit: 'st.brand.edit.title',
  retire: 'st.brand.retire.title'
};

export const when = (iso: string): string => new Date(iso).toLocaleString('en-US');

const isReason = (s: string): boolean => s.trim().length >= 3;

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold text-muted">{label}</dt>
      <dd className="m-0 text-sm">{children}</dd>
    </div>
  );
}

function History({ brand }: { brand: StaffBrand }) {
  const t = useT();
  const q = useQuery(staffTaxonomyQueries.audit(staffApi().taxonomy, brand.id));
  if (q.isPending) return <Spinner label={t('st.brand.loading')} />;
  if (q.isError || !q.data.items.length) return <p className="m-0 text-sm text-muted">{t('st.catalog.history.none')}</p>;
  return (
    <ol className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('st.catalog.history.title')}>
      {q.data.items.map((a) => (
        <li key={a.id} className="flex flex-col gap-0.5 border-l-2 border-border pl-3">
          <span className="text-sm font-semibold">{a.summary}</span>
          <span className="text-xs text-muted">{t('st.catalog.history.by', { name: a.actorName, when: when(a.at) })}</span>
          <span className="text-xs text-muted">{t('st.catalog.history.reason', { reason: a.reason })}</span>
        </li>
      ))}
    </ol>
  );
}

interface FormShellProps {
  intro: string;
  submit: string;
  busy: boolean;
  canSubmit: boolean;
  error: string | null;
  hint?: string | null;
  onSubmit: () => void;
  onCancel: () => void;
  children: ReactNode;
}

/** The frame every brand form shares: intro, fields, the server's refusal, and Cancel / submit. */
function FormShell({ intro, submit, busy, canSubmit, error, hint, onSubmit, onCancel, children }: FormShellProps) {
  const t = useT();
  const send = (e: FormEvent) => {
    e.preventDefault();
    if (canSubmit && !busy) onSubmit();
  };
  return (
    <form noValidate className="flex flex-col gap-3" onSubmit={send}>
      <p className="m-0 text-sm text-muted">{intro}</p>
      {children}
      {error ? (
        <Notice tone="danger" title={t('st.catalog.notSaved')} role="alert">
          {error}
        </Notice>
      ) : null}
      {hint ? <p className="m-0 text-xs text-muted">{hint}</p> : null}
      <div className="flex justify-end gap-2">
        <Button variant="neutral" onClick={onCancel}>
          {t('st.catalog.cancel')}
        </Button>
        <Button type="submit" loading={busy} disabled={!canSubmit}>
          {submit}
        </Button>
      </div>
    </form>
  );
}

function ReasonField({ value, onChange, error }: { value: string; onChange: (v: string) => void; error?: string }) {
  const t = useT();
  return <TextField label={t('st.catalog.field.reason')} help={t('st.catalog.reasonHelp')} value={value} maxLength={1000} error={error} onChange={(e) => onChange(e.currentTarget.value)} />;
}

interface FormProps {
  busy: boolean;
  error: string | null;
  fieldErrors: Record<string, string>;
  onCancel: () => void;
}

function ReasonForm({ title, intro, submit, onSubmit, ...rest }: FormProps & { title: string; intro: string; submit: string; onSubmit: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  return (
    <FormShell intro={intro} submit={submit} busy={rest.busy} canSubmit={isReason(reason)} error={rest.error} onSubmit={() => onSubmit(reason.trim())} onCancel={rest.onCancel}>
      <h3 className="m-0 text-sm font-semibold">{title}</h3>
      <ReasonField value={reason} onChange={setReason} error={rest.fieldErrors.reason} />
    </FormShell>
  );
}

function MergeForm({ brand, onSubmit, ...rest }: FormProps & { brand: StaffBrand; onSubmit: (targetId: string, targetName: string, reason: string) => void }) {
  const t = useT();
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  const approved = useQuery(staffTaxonomyQueries.brands(staffApi().taxonomy, { status: ['APPROVED'], limit: 200 }));
  const options = (approved.data?.brands ?? [])
    .filter((b) => b.id !== brand.id)
    .map((b) => ({ value: b.id, label: b.name, description: `${b.scope.map((s) => s.path).join(', ')} · ${t('st.brand.productsCount', { n: b.products })}`, text: b.name }));
  const chosen = options.find((o) => o.value === target);
  return (
    <FormShell
      intro={t('st.brand.merge.intro', { name: brand.name })}
      submit={t('st.brand.merge.submit')}
      busy={rest.busy}
      canSubmit={chosen !== undefined && isReason(reason)}
      error={rest.error}
      hint={chosen ? (brand.products > 0 ? t('st.brand.merge.moves', { n: brand.products, target: chosen.label }) : t('st.brand.merge.movesNone', { name: brand.name })) : null}
      onSubmit={() => chosen && onSubmit(chosen.value, chosen.label, reason.trim())}
      onCancel={rest.onCancel}
    >
      {approved.isPending ? (
        <Spinner label={t('st.brand.loading')} />
      ) : options.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.brand.merge.none')}</p>
      ) : (
        <Combobox label={t('st.brand.merge.target')} help={t('st.brand.merge.targetHelp')} placeholder={t('st.brand.merge.placeholder')} options={options} value={target} onChange={setTarget} error={rest.fieldErrors.targetId} />
      )}
      <ReasonField value={reason} onChange={setReason} error={rest.fieldErrors.reason} />
    </FormShell>
  );
}

function EditForm({ brand, nodes, onSubmit, ...rest }: FormProps & { brand: StaffBrand; nodes: TaxonomyNode[]; onSubmit: (body: { name?: string; scope?: string[] }, reason: string) => void }) {
  const t = useT();
  const [name, setName] = useState(brand.name);
  const [scope, setScope] = useState(brand.scope.map((s) => s.nodeId));
  const [reason, setReason] = useState('');
  const changed = brandChanged(brand, name, scope);
  const tidy = name.trim().replace(/\s+/g, ' ');
  const sameScope = !brandChanged({ name: tidy, scope: brand.scope }, tidy, scope);
  return (
    <FormShell
      intro={t('st.brand.edit.intro', { name: brand.name })}
      submit={t('st.brand.edit.submit')}
      busy={rest.busy}
      canSubmit={changed && tidy.length >= 2 && scope.length > 0 && isReason(reason)}
      error={rest.error}
      hint={changed ? null : t('st.brand.edit.nochange')}
      onSubmit={() => onSubmit({ ...(tidy !== brand.name ? { name: tidy } : {}), ...(sameScope ? {} : { scope }) }, reason.trim())}
      onCancel={rest.onCancel}
    >
      <TextField label={t('st.brand.field.name')} value={name} maxLength={120} error={rest.fieldErrors.name} onChange={(e) => setName(e.currentTarget.value)} />
      <ScopePicker nodes={nodes} value={scope} onChange={setScope} error={rest.fieldErrors.scope} />
      <ReasonField value={reason} onChange={setReason} error={rest.fieldErrors.reason} />
    </FormShell>
  );
}

function AddForm({ nodes, onSubmit, ...rest }: FormProps & { nodes: TaxonomyNode[]; onSubmit: (name: string, scope: string[], reason: string) => void }) {
  const t = useT();
  const [name, setName] = useState('');
  const [scope, setScope] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const tidy = name.trim().replace(/\s+/g, ' ');
  return (
    <FormShell intro={t('st.brand.add.intro')} submit={t('st.brand.add.submit')} busy={rest.busy} canSubmit={tidy.length >= 2 && scope.length > 0 && isReason(reason)} error={rest.error} onSubmit={() => onSubmit(tidy, scope, reason.trim())} onCancel={rest.onCancel}>
      <TextField label={t('st.brand.field.name')} value={name} maxLength={120} error={rest.fieldErrors.name} onChange={(e) => setName(e.currentTarget.value)} />
      <ScopePicker nodes={nodes} value={scope} onChange={setScope} error={rest.fieldErrors.scope} />
      <ReasonField value={reason} onChange={setReason} error={rest.fieldErrors.reason} />
    </FormShell>
  );
}

const OUTCOME_LABEL = { NEW: 'st.brand.bulk.new', EXISTS: 'st.brand.bulk.exists', INVALID: 'st.brand.bulk.invalid' } as const;

function BulkForm({ nodes, onSubmit, result, onDone, ...rest }: FormProps & { nodes: TaxonomyNode[]; onSubmit: (text: string, scope: string[], reason: string) => void; result: BrandBulkResult | null; onDone: () => void }) {
  const t = useT();
  const [text, setText] = useState('');
  const [scope, setScope] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const known = useQuery(staffTaxonomyQueries.brands(staffApi().taxonomy, { status: ['APPROVED', 'PENDING'], limit: 200 }));
  const preview = previewBrandList(text, known.data?.brands ?? []);
  if (result) {
    return (
      <div className="flex flex-col gap-3" data-testid="bulk-result">
        <Notice tone="success" title={t('st.brand.bulk.result', { created: result.created, existing: result.existing, invalid: result.invalid })} />
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {result.results.map((r) => (
            <li key={r.name} className="flex flex-wrap gap-x-2 text-sm">
              <strong>{r.name}</strong>
              <span className="text-muted">{r.message ?? t('st.brand.bulk.created')}</span>
            </li>
          ))}
        </ul>
        <div className="flex justify-end">
          <Button onClick={onDone}>{t('st.brand.bulk.done')}</Button>
        </div>
      </div>
    );
  }
  const tooMany = preview.rows.length > 500;
  return (
    <FormShell
      intro={t('st.brand.bulk.intro')}
      submit={t('st.brand.bulk.submit', { n: preview.fresh })}
      busy={rest.busy}
      canSubmit={preview.fresh > 0 && !tooMany && scope.length > 0 && isReason(reason)}
      error={rest.error ?? (tooMany ? t('st.brand.bulk.tooMany') : null)}
      onSubmit={() => onSubmit(text, scope, reason.trim())}
      onCancel={rest.onCancel}
    >
      <div className="flex flex-col gap-1">
        <label htmlFor="brand-bulk-text" className="text-sm font-semibold">
          {t('st.brand.bulk.paste')}
        </label>
        <textarea id="brand-bulk-text" rows={6} className="rounded-sm border border-border-strong bg-ink-0 p-2 text-sm" value={text} onChange={(e) => setText(e.currentTarget.value)} />
        <span className="text-xs text-muted">{rest.fieldErrors.names ?? t('st.brand.bulk.pasteHelp')}</span>
      </div>
      <ScopePicker nodes={nodes} value={scope} onChange={setScope} error={rest.fieldErrors.scope} />
      {preview.rows.length ? (
        <section className="flex flex-col gap-2" aria-label={t('st.brand.bulk.preview')} data-testid="bulk-preview">
          <h3 className="m-0 text-sm font-semibold">{t('st.brand.bulk.preview')}</h3>
          <p className="m-0 text-xs text-muted">{t('st.brand.bulk.counts', { fresh: preview.fresh, existing: preview.existing, invalid: preview.invalid })}</p>
          {preview.repeated > 0 ? <p className="m-0 text-xs text-muted">{t('st.brand.bulk.repeated', { n: preview.repeated })}</p> : null}
          <ul className="m-0 flex max-h-56 list-none flex-col gap-1 overflow-y-auto p-0">
            {preview.rows.map((r) => (
              <li key={r.name} className="flex flex-wrap items-baseline gap-x-2 text-sm" data-outcome={r.outcome}>
                <strong>{r.name}</strong>
                <span className="text-xs text-muted">{t(OUTCOME_LABEL[r.outcome], { name: r.match ?? '' })}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      <ReasonField value={reason} onChange={setReason} error={rest.fieldErrors.reason} />
    </FormShell>
  );
}

/**
 * One brand (or a new one) in the shared right-hand sheet: who typed it in, where it applies, how many listings hold
 * it, its history by name, and the decisions: approve, reject, merge into another brand, edit, retire. Every decision
 * carries a reason and the brand's version (If-Match); a form with nothing changed cannot be saved.
 */
export function BrandSheet({ target, brands, nodes, canEdit, onTarget, onClose }: { target: BrandTarget | null; brands: StaffBrand[]; nodes: TaxonomyNode[]; canEdit: boolean; onTarget: (t: BrandTarget | null) => void; onClose: () => void }) {
  const t = useT();
  const api = staffApi().taxonomy;
  const qc = useQueryClient();
  const toast = useToast();
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [bulk, setBulk] = useState<BrandBulkResult | null>(null);
  const live = target?.brand ? (brands.find((b) => b.id === target.brand?.id) ?? target.brand) : null;
  const mode = target?.mode ?? 'view';

  const refresh = () => void qc.invalidateQueries({ queryKey: staffTaxonomyKeys.all });
  const clear = () => {
    setFieldErrors({});
    setError(null);
  };
  const back = () => {
    clear();
    setBulk(null);
    onTarget(live ? { brand: live, mode: 'view' } : null);
  };
  const failed = (e: unknown) => {
    if (isApiError(e) && e.status === 422) {
      setFieldErrors(e.fieldErrors);
      setError(Object.keys(e.fieldErrors).length ? null : (e.problem.detail ?? null));
    } else {
      setFieldErrors({});
      setError(problemMessage(e));
      if (isApiError(e) && (e.status === 412 || e.status === 409)) refresh();
    }
  };

  const change = useMutation({ mutationFn: (run: () => Promise<StaffBrand>) => run(), retry: false, onError: failed });
  const run = (go: () => Promise<StaffBrand>, done: string, next: 'view' | 'close' = 'view') =>
    change.mutate(go, {
      onSuccess: (saved) => {
        refresh();
        clear();
        toast.show(done);
        if (next === 'close') onClose();
        else onTarget({ brand: saved, mode: 'view' });
      }
    });
  const many = useMutation({
    mutationFn: (v: { text: string; scope: string[]; reason: string }) => api.createBrands(v),
    retry: false,
    onError: failed,
    onSuccess: (r) => {
      refresh();
      clear();
      setBulk(r);
      toast.show(t('st.brand.bulk.result', { created: r.created, existing: r.existing, invalid: r.invalid }));
    }
  });

  const form = { busy: change.isPending, error, fieldErrors, onCancel: back };

  const title = !target ? '' : mode === 'add' ? t('st.brand.add.title') : mode === 'bulk' ? t('st.brand.bulk.title') : (live?.name ?? '');

  const renderBody = () => {
    if (!target) return null;
    if (mode === 'add') {
      return <AddForm {...form} onCancel={() => onClose()} nodes={nodes} onSubmit={(name, scope, reason) => run(() => api.createBrand({ name, scope, reason }), t('st.brand.add.done', { name }), 'close')} />;
    }
    if (mode === 'bulk') {
      return <BulkForm {...form} busy={many.isPending} onCancel={() => onClose()} nodes={nodes} result={bulk} onDone={onClose} onSubmit={(text, scope, reason) => many.mutate({ text, scope, reason })} />;
    }
    if (!live) return null;
    if (mode === 'approve') {
      return <ReasonForm {...form} title={t('st.brand.approve.title')} intro={t('st.brand.approve.intro', { name: live.name })} submit={t('st.brand.approve.submit')} onSubmit={(reason) => run(() => api.approveBrand(live.id, reason, live.version), t('st.brand.approve.done', { name: live.name }))} />;
    }
    if (mode === 'reject') {
      return <ReasonForm {...form} title={t('st.brand.reject.title')} intro={t('st.brand.reject.intro', { name: live.name })} submit={t('st.brand.reject.submit')} onSubmit={(reason) => run(() => api.rejectBrand(live.id, reason, live.version), t('st.brand.reject.done', { name: live.name }))} />;
    }
    if (mode === 'retire') {
      return <ReasonForm {...form} title={t('st.brand.retire.title')} intro={t('st.brand.retire.intro', { name: live.name })} submit={t('st.brand.retire.submit')} onSubmit={(reason) => run(() => api.retireBrand(live.id, reason, live.version), t('st.brand.retire.done', { name: live.name }))} />;
    }
    if (mode === 'merge') {
      return <MergeForm {...form} brand={live} onSubmit={(targetId, targetName, reason) => run(() => api.mergeBrand(live.id, targetId, reason, live.version), t('st.brand.merge.done', { name: live.name, target: targetName }))} />;
    }
    if (mode === 'edit') {
      return <EditForm {...form} brand={live} nodes={nodes} onSubmit={(body, reason) => run(() => api.updateBrand(live.id, { ...body, reason }, live.version), t('st.brand.edit.done', { name: body.name ?? live.name }))} />;
    }
    return <BrandDetails brand={live} canEdit={canEdit} onMode={(m) => onTarget({ brand: live, mode: m })} />;
  };

  return (
    <Sheet open={target !== null} onOpenChange={(o) => !o && onClose()} title={title} description={live ? t(STATUS_LABEL[live.status]) : t('st.brand.sheet.description')}>
      <div className="flex flex-col gap-4" data-testid="brand-sheet">
        {renderBody()}
      </div>
    </Sheet>
  );
}

function BrandDetails({ brand, canEdit, onMode }: { brand: StaffBrand; canEdit: boolean; onMode: (m: BrandAction) => void }) {
  const t = useT();
  const actions = brandActions(brand.status);
  return (
    <>
      <dl className="m-0 grid gap-3 sm:grid-cols-2">
        <Detail label={t('st.brand.col.status')}>{t(STATUS_LABEL[brand.status])}</Detail>
        <Detail label={t('st.brand.col.seller')}>{brand.createdByName ?? t('st.brand.byStaff')}</Detail>
        <Detail label={t('st.brand.col.requested')}>{when(brand.createdAt)}</Detail>
        <Detail label={t('st.brand.col.products')}>{t('st.brand.detail.usage', { n: brand.products, s: brand.sellers })}</Detail>
        <Detail label={t('st.brand.col.scope')}>
          <ul className="m-0 list-none p-0">
            {brand.scope.map((s) => (
              <li key={s.nodeId}>{s.path}</li>
            ))}
          </ul>
        </Detail>
        {brand.mergedIntoName ? <Detail label={t('st.brand.detail.mergedInto')}>{brand.mergedIntoName}</Detail> : null}
        {brand.decidedBy && brand.decidedAt ? <Detail label={t('st.brand.detail.decided')}>{t('st.catalog.history.by', { name: brand.decidedBy, when: when(brand.decidedAt) })}</Detail> : null}
        {brand.decisionReason ? <Detail label={t('st.catalog.field.reason')}>{brand.decisionReason}</Detail> : null}
      </dl>
      {canEdit ? (
        actions.length ? (
          <div className="flex flex-wrap gap-2">
            {actions.map((a) => (
              <Button key={a} size="sm" variant={a === 'approve' ? 'primary' : 'neutral'} onClick={() => onMode(a)}>
                {t(ACTION_LABEL[a])}
              </Button>
            ))}
          </div>
        ) : null
      ) : (
        <Notice tone="info" title={t('st.brand.readOnly')} />
      )}
      <section className="flex flex-col gap-2">
        <h3 className="m-0 text-sm font-semibold">{t('st.catalog.history.title')}</h3>
        <History brand={brand} />
      </section>
    </>
  );
}
