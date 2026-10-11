'use client';

import { type FieldValue, isApiError, type ResourceKey, type ResourceRow, staffKeys } from '@afrotransact/api-client';
import { Button, Dialog, useToast } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { staffApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';

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
  products: [{ key: 'approve', label: 'Approve', tone: 'primary', done: 'approved' }],
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
  if (!actions.length) return null;

  const run = async (a: BulkAction) => {
    const values: Record<string, FieldValue> = a.field ? { [a.field.key]: text.trim() } : {};
    const failures: string[] = [];
    let ok = 0;
    setProgress({ done: 0, total: rows.length });
    for (const [i, r] of rows.entries()) {
      try {
        await api.runAction(resource, r.id, a.key, values, idempotencyKey());
        ok++;
      } catch (e) {
        failures.push(isApiError(e) ? (e.problem.detail ?? e.problem.title) : 'failed');
      }
      setProgress({ done: i + 1, total: rows.length });
    }
    setProgress(null);
    setPending(null);
    setText('');
    void qc.invalidateQueries({ queryKey: staffKeys.resource(resource) });
    const reasons = [...new Set(failures)].slice(0, 2).join(' · ');
    if (failures.length) toast.show(`${ok} of ${rows.length} ${a.done}; ${failures.length} skipped${reasons ? ` — ${reasons}` : ''}`, ok ? 'info' : 'error');
    else toast.show(`${ok} ${ok === 1 ? 'row' : 'rows'} ${a.done}.`);
    onDone();
  };

  return (
    <>
      {actions.map((a) => (
        <Button key={a.key} size="sm" variant={a.tone === 'primary' ? 'primary' : 'neutral'} onClick={() => setPending(a)}>
          {a.label}
        </Button>
      ))}
      <Dialog open={pending !== null} onOpenChange={(o) => (!o && !progress ? setPending(null) : undefined)} title={pending ? `${pending.label} · ${rows.length} selected` : ''} width={440}>
        {pending ? (
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              if (pending.field?.required && !text.trim()) return;
              void run(pending);
            }}
          >
            <p className="m-0 text-sm text-muted">Runs on each selected row; rows where it isn’t possible (state or permission) are skipped and reported.</p>
            {pending.field ? (
              <label htmlFor={fieldId} className="flex flex-col gap-1 text-sm font-semibold">
                {pending.field.label}
                {pending.field.required ? '' : ' (optional)'}
                <textarea id={fieldId} className="min-h-20 rounded-sm border border-border-strong p-2 text-sm font-normal" maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} required={pending.field.required} />
              </label>
            ) : null}
            {progress ? (
              <p role="status" className="m-0 text-sm">
                {progress.done} of {progress.total} done…
              </p>
            ) : null}
            <span className="flex justify-end gap-2">
              <Button type="button" variant="neutral" disabled={!!progress} onClick={() => setPending(null)}>
                Cancel
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
