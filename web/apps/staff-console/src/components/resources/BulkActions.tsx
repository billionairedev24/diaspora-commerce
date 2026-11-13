'use client';

import { type FieldValue, isApiError, type ResourceKey, type ResourceRow, staffKeys } from '@afrotransact/api-client';
import { Button, Dialog, useToast } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { humanize,idempotencyKey } from '@/lib/format';

/** A detail-sheet action that also makes sense for many rows at once (the BFF runs it per record). */
interface BulkAction {
  key: string;
  label: string;
  tone: 'primary' | 'neutral' | 'danger';
  /** The form field the action needs (reasonForm → reason, noteForm → note). */
  field?: { key: 'reason' | 'note'; label: string; required: boolean };
  done: string;
}

const reason = (label = 'Reason') => ({ key: 'reason' as const, label, required: true });
const note = (required = false) => ({ key: 'note' as const, label: 'Note', required });

/** Bulk actions per table: only actions the sheets already offer (no bulk endpoint; one call per selected row). */
export const BULK_ACTIONS: Partial<Record<ResourceKey, BulkAction[]>> = {
  inventory: [
    { key: 'quarantine', label: 'Quarantine', tone: 'danger', field: reason(), done: 'quarantined' },
    { key: 'release', label: 'Release to sale', tone: 'neutral', field: reason(), done: 'released' }
  ],
  products: [
    { key: 'approve', label: 'Approve', tone: 'primary', done: 'approved' },
    { key: 'reject', label: 'Reject', tone: 'danger', field: note(true), done: 'rejected' },
    { key: 'restore', label: 'Restore', tone: 'neutral', field: note(), done: 'restored' }
  ],
  moderation: [
    { key: 'assign', label: 'Assign to me', tone: 'neutral', done: 'assigned to you' },
    { key: 'approve', label: 'Allow', tone: 'primary', field: note(), done: 'allowed' }
  ],
  payouts: [
    { key: 'approve', label: 'Approve & pay', tone: 'primary', done: 'approved' },
    { key: 'hold', label: 'Hold', tone: 'danger', field: reason(), done: 'put on hold' }
  ],
  returns: [
    { key: 'approve', label: 'Approve', tone: 'primary', field: note(), done: 'approved' },
    { key: 'receive', label: 'Mark received', tone: 'neutral', done: 'marked received' }
  ],
  'purchase-orders': [{ key: 'approve', label: 'Approve', tone: 'primary', field: note(), done: 'approved' }],
  'support-cases': [{ key: 'assign', label: 'Assign to me', tone: 'neutral', done: 'assigned to you' }],
  placements: [{ key: 'approve', label: 'Approve', tone: 'primary', done: 'approved' }],
  announcements: [{ key: 'approve', label: 'Approve', tone: 'primary', done: 'approved' }]
};

/**
 * Tables whose rows carry the state machine's answer (row.actions, already checked against state and permission): a bulk
 * action runs only on rows that list it, and the rest are skipped with the reason (their status) before anything is sent.
 */
const ROW_ELIGIBLE: ReadonlySet<ResourceKey> = new Set<ResourceKey>(['products']);

/** The name staff know a row by (never its id). */
function rowName(r: ResourceRow): string {
  for (const k of ['title', 'name', 'atin']) {
    const v = r.cells[k];
    if (typeof v === 'string' && v) return v;
  }
  return r.id;
}

const statusOf = (r: ResourceRow): string => humanize(String(r.cells.status ?? '')).toLowerCase();

interface Outcome {
  name: string;
  reason?: string;
}

/** The selection bar's actions: confirm (with the reason or note the action needs), run per row, report the result. */
export function BulkActions({ resource, rows, onDone }: { resource: ResourceKey; rows: ResourceRow[]; onDone: () => void }) {
  const actions = BULK_ACTIONS[resource] ?? [];
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const fieldId = useId();
  const [pending, setPending] = useState<BulkAction | null>(null);
  const [text, setText] = useState('');
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [result, setResult] = useState<{ action: BulkAction; done: Outcome[]; skipped: Outcome[]; failed: Outcome[] } | null>(null);
  const t = useT();
  if (!actions.length) return null;

  const gated = ROW_ELIGIBLE.has(resource);
  /** Who the action can run on, who it cannot (with their status as the reason), and the "2 already live" summary. */
  const split = (a: BulkAction) => {
    const eligible = gated ? rows.filter((r) => r.actions?.some((x) => x.key === a.key)) : rows;
    const skipped = gated ? rows.filter((r) => !eligible.includes(r)) : [];
    const counts = new Map<string, number>();
    for (const r of skipped) counts.set(statusOf(r), (counts.get(statusOf(r)) ?? 0) + 1);
    const list = [...counts].map(([status, n]) => t('st.bulk.skipItem', { n, status })).join(', ');
    return { eligible, skipped, list };
  };

  const close = () => {
    const finished = result !== null;
    setPending(null);
    setResult(null);
    setText('');
    if (finished) onDone();
  };

  const run = async (a: BulkAction) => {
    const { eligible, skipped } = split(a);
    const values: Record<string, FieldValue> = a.field ? { [a.field.key]: text.trim() } : {};
    const done: Outcome[] = [];
    const failed: Outcome[] = [];
    setProgress({ done: 0, total: eligible.length });
    for (const [i, r] of eligible.entries()) {
      try {
        await api.runAction(resource, r.id, a.key, values, idempotencyKey());
        done.push({ name: rowName(r) });
      } catch (e) {
        failed.push({ name: rowName(r), reason: isApiError(e) ? (e.problem.detail ?? e.problem.title) : t('st.bulk.failedGeneric') });
      }
      setProgress({ done: i + 1, total: eligible.length });
    }
    setProgress(null);
    void qc.invalidateQueries({ queryKey: staffKeys.resource(resource) });
    const outcome = skipped.map((r) => ({ name: rowName(r), reason: t('st.bulk.skipItem', { n: '', status: statusOf(r) }).trim() }));
    if (!gated) {
      // Tables without per-row eligibility: the old summary, a toast.
      setPending(null);
      setText('');
      const reasons = [...new Set(failed.map((f) => f.reason))].slice(0, 2).join(' · ');
      if (failed.length) toast.show(`${done.length} of ${eligible.length} ${a.done}; ${failed.length} skipped${reasons ? ` — ${reasons}` : ''}`, done.length ? 'info' : 'error');
      else toast.show(`${done.length} ${done.length === 1 ? 'row' : 'rows'} ${a.done}.`);
      onDone();
      return;
    }
    setResult({ action: a, done, skipped: outcome, failed });
  };

  const open = pending ? split(pending) : null;
  return (
    <>
      {actions.map((a) => {
        const { eligible, list } = split(a);
        const none = gated && eligible.length === 0;
        const label = gated && !none && eligible.length < rows.length ? t('st.bulk.someOf', { label: a.label, n: eligible.length, m: rows.length }) : a.label;
        return (
          <Button key={a.key} size="sm" variant={a.tone === 'primary' ? 'primary' : 'neutral'} disabled={none} title={none ? t('st.bulk.noneEligible', { label: a.label, list }) : undefined} onClick={() => setPending(a)}>
            {label}
          </Button>
        );
      })}
      <Dialog open={pending !== null} onOpenChange={(o) => (!o && !progress ? close() : undefined)} title={pending && open ? (gated && open.eligible.length < rows.length ? t('st.bulk.someOf', { label: pending.label, n: open.eligible.length, m: rows.length }) : t('st.bulk.selected', { label: pending.label, n: rows.length })) : ''} width={440}>
        {pending && open && result ? (
          <div className="flex flex-col gap-3" data-testid="bulk-result">
            {(
              [
                [t('st.bulk.doneHeading', { n: result.done.length }), result.done],
                [t('st.bulk.skippedHeading', { n: result.skipped.length }), result.skipped],
                [t('st.bulk.failedHeading', { n: result.failed.length }), result.failed]
              ] as const
            ).map(([heading, items]) =>
              items.length ? (
                <section key={heading} className="flex flex-col gap-1">
                  <h3 className="m-0 text-sm font-semibold">{heading}</h3>
                  <ul className="m-0 flex max-h-40 list-none flex-col gap-0.5 overflow-y-auto p-0 text-sm">
                    {items.map((o, i) => (
                      <li key={`${o.name}-${i}`}>{o.reason ? t('st.bulk.rowReason', { name: o.name, reason: o.reason }) : o.name}</li>
                    ))}
                  </ul>
                </section>
              ) : null
            )}
            <span className="flex justify-end">
              <Button type="button" variant="primary" onClick={close}>
                {t('st.bulk.close')}
              </Button>
            </span>
          </div>
        ) : pending && open ? (
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              if (pending.field?.required && !text.trim()) return;
              void run(pending);
            }}
          >
            <p className="m-0 text-sm text-muted">
              {gated && open.skipped.length
                ? t('st.bulk.runsOnEligible', { n: open.eligible.length, m: rows.length, list: open.list })
                : t('st.bulk.runsOnAll')}
            </p>
            {pending.field ? (
              <label htmlFor={fieldId} className="flex flex-col gap-1 text-sm font-semibold">
                {pending.field.required ? pending.field.label : t('st.bulk.optional', { label: pending.field.label })}
                <textarea id={fieldId} className="min-h-20 rounded-sm border border-border-strong p-2 text-sm font-normal" maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} required={pending.field.required} />
              </label>
            ) : null}
            {progress ? (
              <p role="status" className="m-0 text-sm">
                {t('st.bulk.progress', { done: progress.done, total: progress.total })}
              </p>
            ) : null}
            <span className="flex justify-end gap-2">
              <Button type="button" variant="neutral" disabled={!!progress} onClick={close}>
                {t('st.bulk.cancel')}
              </Button>
              <Button type="submit" variant={pending.tone === 'primary' ? 'primary' : 'neutral'} loading={!!progress}>
                {pending.label}
              </Button>
            </span>
          </form>
        ) : null}
      </Dialog>
    </>
  );
}
