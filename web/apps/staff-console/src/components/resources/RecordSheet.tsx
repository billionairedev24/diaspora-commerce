'use client';

import { definitiveFailure, type FieldValue, isApiError, type RecordAction, type RecordDetail, type ResourceKey, type StaffForm, staffKeys, staffQueries } from '@afrotransact/api-client';
import { Button, Dialog, Sheet, SheetTableScroll, useToast } from '@afrotransact/ui';
import { LockSimple, PencilSimple } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, humanize, idempotencyKey } from '@/lib/format';

import { Cell, fieldText } from './cells';
import { FormFields } from './FormFields';

/** An open dialog. An action keeps one Idempotency-Key from opening until it is done, so a retry never repeats it. */
type Pending = { kind: 'action'; action: RecordAction; key: string } | { kind: 'edit'; form: StaffForm };

/** The BFF's answer when what the action would do already happened (e.g. an application already approved). */
const isAlreadyDone = (e: unknown): boolean => isApiError(e) && e.status === 409 && e.code === 'ALREADY_DONE';

function Details({ record }: { record: RecordDetail }) {
  return (
    <>
      {record.sections.map((s) => (
        <section key={s.title} aria-label={s.title} className="flex flex-col gap-2">
          <h3 className="m-0 text-sm font-bold">{s.title}</h3>
          <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1.5 text-sm">
            {s.fields.map((f) => (
              <div key={f.label} className="contents">
                <dt className="text-muted">{f.label}</dt>
                <dd className="m-0 [overflow-wrap:anywhere]">
                  {f.kind === 'status' ? (
                    <StatusPill tone={record.tone}>{humanize(f.value)}</StatusPill>
                  ) : f.kind === 'link' && f.href?.startsWith('https://') ? (
                    <a href={f.href} target="_blank" rel="noreferrer">
                      {f.value}
                    </a>
                  ) : (
                    <span className={f.kind === 'mono' ? 'font-mono text-xs' : undefined}>{fieldText(f.kind, f.value)}</span>
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
      {record.tables
        .filter((t) => t.rows.length > 0)
        .map((t) => (
          <section key={t.title} aria-label={t.title} className="flex flex-col gap-2">
            <h3 className="m-0 text-sm font-bold">{t.title}</h3>
            <div className="overflow-hidden rounded-md border border-border">
              <SheetTableScroll>
              <table className="at-table text-xs" aria-label={t.title}>
                <colgroup>
                  {t.columns.map((c) => (
                    <col key={c.key} style={{ minWidth: `${Math.max(72, c.width * 8)}px` }} />
                  ))}
                </colgroup>
                <thead className="bg-surface-muted text-left">
                  <tr>
                    {t.columns.map((c) => (
                      <th key={c.key} scope="col" className="px-2 py-1.5 font-bold text-muted">
                        {c.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {t.rows.map((r) => (
                    <tr key={r.id} className="border-t border-border">
                      {t.columns.map((c) => (
                        <td key={c.key} className="px-2 py-1.5">
                          <Cell kind={c.kind} value={r.cells[c.key]} tone={r.tone} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              </SheetTableScroll>
            </div>
          </section>
        ))}
    </>
  );
}

/**
 * Detail sheet of one record: every field, nested tables, activity and notes, and the actions the BFF allows for the
 * staff member's roles (disabled ones say why). Forms are validated before they are sent; the BFF validates again.
 *
 * Actions (e.g. application review decisions) run once: one Idempotency-Key per opened dialog, never retried by the
 * client, every action button disabled while one is in flight. When the BFF answers ALREADY_DONE the current state is
 * shown as information and the record and its table are refreshed.
 */
export function RecordSheet({ resource, id, onClose }: { resource: ResourceKey; id: string | null; onClose: () => void }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const [pending, setPending] = useState<Pending | null>(null);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [info, setInfo] = useState<{ id: string; text: string } | null>(null);
  const q = useQuery({ ...staffQueries.record(api, resource, id ?? ''), enabled: id !== null });
  const record = q.data;

  /** The record, its table pages and the console's queue counts (nav badges) after a change. */
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: staffKeys.resource(resource), predicate: (query) => query.queryKey[3] === 'page' });
    void qc.invalidateQueries({ queryKey: staffKeys.session() });
  };
  const done = (r: RecordDetail, message: string) => {
    if (r.removed) {
      void qc.removeQueries({ queryKey: staffKeys.record(resource, r.id) });
      refresh();
      setPending(null);
      setInfo(null);
      toast.show(message);
      onClose();
      return;
    }
    qc.setQueryData(staffKeys.record(resource, r.id), r);
    refresh();
    setPending(null);
    setInfo(null);
    toast.show(message);
  };
  const failed = (e: unknown) => {
    if (isApiError(e) && e.status === 422) {
      setServerErrors(e.fieldErrors);
      setFormError(e.problem.detail ?? null);
    } else setFormError(problemMessage(e));
  };
  const act = useMutation({
    mutationFn: ({ action, values, key }: { action: RecordAction; values: Record<string, FieldValue>; key: string }) => api.runAction(resource, id ?? '', action.key, values, key),
    // A decision is never retried by the client: a retry after a timeout could repeat it (the BFF and the upstream
    // replay the same Idempotency-Key instead).
    retry: false,
    onSuccess: (r, v) => done(r, t('st.recordSheet.actionDone', { action: v.action.label })),
    onError: (e, v) => {
      if (isAlreadyDone(e)) {
        const text = (isApiError(e) && e.problem.detail) || t('st.recordSheet.alreadyDone');
        setPending(null);
        setInfo({ id: id ?? '', text });
        toast.show(text, 'info');
        void qc.invalidateQueries({ queryKey: staffKeys.record(resource, id ?? '') });
        refresh();
        return;
      }
      // Only an answer that proves the attempt is over allows a new key; otherwise the next try replays this one.
      if (definitiveFailure(e)) setPending((p) => (p?.kind === 'action' && p.key === v.key ? { ...p, key: idempotencyKey() } : p));
      failed(e);
    }
  });
  const edit = useMutation({
    mutationFn: (values: Record<string, FieldValue>) => api.updateRecord(resource, id ?? '', values, record?.version ?? null),
    retry: false,
    onSuccess: (r) => done(r, 'Saved.'),
    onError: failed
  });
  const busy = act.isPending || edit.isPending;
  const runAction = (action: RecordAction, values: Record<string, FieldValue>) => {
    if (busy || pending?.kind !== 'action') return;
    act.mutate({ action, values, key: pending.key });
  };
  const [note, setNote] = useState('');
  const addNote = useMutation({
    mutationFn: () => api.addNote(resource, id ?? '', note),
    onSuccess: (r) => {
      setNote('');
      done(r, 'Note added.');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const open = (p: Pending) => {
    if (busy) return;
    setServerErrors({});
    setFormError(null);
    setInfo(null);
    setPending(p);
  };
  const pendingForm = pending?.kind === 'edit' ? pending.form : pending?.kind === 'action' ? pending.action.form : null;

  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={record?.title ?? 'Details'} description={record?.subtitle} className="w-[min(720px,100vw)]">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {record ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="m-0 flex-1 text-xl font-bold">{record.title}</h2>
              {record.status ? <StatusPill tone={record.tone}>{humanize(record.status)}</StatusPill> : null}
            </div>
            {record.subtitle ? <p className="m-0 text-sm text-muted">{record.subtitle}</p> : null}
          </div>
          {info && info.id === record.id ? (
            <Notice tone="info" title={t('st.recordSheet.alreadyDoneTitle')}>
              {info.text}
            </Notice>
          ) : null}
          {record.actions.length || record.editForm ? (
            <div role="group" aria-label="Actions" className="flex flex-wrap gap-2">
              {record.editForm ? (
                <Button variant="neutral" size="sm" disabled={busy} icon={<PencilSimple size={14} aria-hidden />} onClick={() => record.editForm && open({ kind: 'edit', form: record.editForm })}>
                  Edit
                </Button>
              ) : null}
              {record.actions.map((a) => (
                <Button
                  key={a.key}
                  size="sm"
                  variant={a.tone === 'primary' ? 'primary' : 'neutral'}
                  className={a.tone === 'danger' ? 'text-danger' : undefined}
                  disabled={!a.allowed || busy}
                  title={a.reason ?? undefined}
                  icon={a.allowed ? undefined : <LockSimple size={14} aria-hidden />}
                  onClick={() => open({ kind: 'action', action: a, key: idempotencyKey() })}
                >
                  {a.label}
                  {a.reason ? <span className="at-visually-hidden"> — {a.reason}</span> : null}
                </Button>
              ))}
            </div>
          ) : null}
          {record.actions.some((a) => !a.allowed && a.reason?.startsWith('Needs')) ? (
            <p className="m-0 text-xs text-muted">Locked actions need a permission or access level your roles don’t have; hover them to see which.</p>
          ) : null}
          {record.links.length ? (
            <ul className="m-0 flex list-none flex-wrap gap-3 p-0 text-sm">
              {record.links.map((l) => (
                <li key={l.href}>
                  {l.href.startsWith('/') ? <Link href={l.href}>{l.label}</Link> : <a href={l.href}>{l.label}</a>}
                </li>
              ))}
            </ul>
          ) : null}
          <Details record={record} />
          <section aria-label="Activity" className="flex flex-col gap-2">
            <h3 className="m-0 text-sm font-bold">Activity</h3>
            {record.activity.length ? (
              <ol className="m-0 flex list-none flex-col gap-2 p-0">
                {record.activity.map((a, i) => (
                  <li key={`${a.at}-${i}`} className="flex gap-3 text-sm">
                    <span className="w-28 flex-none text-xs text-muted">{dateTime(a.at)}</span>
                    <span>
                      <strong className="font-semibold">{a.actor}</strong> {a.text}
                    </span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="m-0 text-sm text-muted">No activity recorded yet.</p>
            )}
          </section>
          <section aria-label="Notes" className="flex flex-col gap-2">
            <h3 className="m-0 text-sm font-bold">Notes</h3>
            {record.notes.map((n) => (
              <p key={n.id} className="m-0 text-sm">
                <strong>{n.author}</strong> · {dateTime(n.at)} — {n.text}
              </p>
            ))}
            {record.notesEnabled ? (
              <form
                className="flex gap-2"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (note.trim()) addNote.mutate();
                }}
              >
                <label className="at-visually-hidden" htmlFor="note-input">
                  Add a note
                </label>
                <input id="note-input" className="min-w-0 flex-1 rounded-sm border border-border-strong px-3 py-2 text-sm" maxLength={2000} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
                <Button type="submit" size="sm" loading={addNote.isPending}>
                  Add note
                </Button>
              </form>
            ) : (
              <Notice tone="info" title="Internal notes aren’t available yet.">
                No service stores notes on records yet; use the activity history and the audit log.
              </Notice>
            )}
          </section>
        </div>
      ) : null}
      <Dialog
        open={pending !== null}
        onOpenChange={(o) => !o && setPending(null)}
        title={pendingForm?.title ?? (pending?.kind === 'action' ? pending.action.label : 'Confirm')}
        width={460}
      >
        {pendingForm ? (
          <FormFields
            key={pending?.kind === 'action' ? pending.action.key : 'edit'}
            form={pendingForm}
            busy={busy}
            serverErrors={serverErrors}
            error={formError}
            onCancel={() => setPending(null)}
            onSubmit={(values) => {
              if (pending?.kind === 'action') runAction(pending.action, values);
              else if (pending?.kind === 'edit' && !busy) edit.mutate(values);
            }}
          />
        ) : pending?.kind === 'action' ? (
          <div className="flex flex-col gap-4">
            <p className="m-0">{pending.action.confirm ?? `${pending.action.label}?`}</p>
            {formError ? (
              <Notice tone="danger" title="Not done." role="alert">
                {formError}
              </Notice>
            ) : null}
            <div className="flex justify-end gap-2">
              <Button variant="neutral" onClick={() => setPending(null)}>
                Cancel
              </Button>
              <Button loading={act.isPending} onClick={() => runAction(pending.action, {})}>
                {pending.action.label}
              </Button>
            </div>
          </div>
        ) : null}
      </Dialog>
    </Sheet>
  );
}
