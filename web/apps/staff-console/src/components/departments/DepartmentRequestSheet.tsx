'use client';

import { definitiveFailure, isApiError, staffDepartmentKeys, staffDepartmentQueries, type StaffDepartmentRequest, staffKeys } from '@afrotransact/api-client';
import { Button, Dialog, Field, fieldStyles, Sheet, useToast } from '@afrotransact/ui';
import { CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { recordHref } from '@/lib/modules';

import { PROMPT_LABEL, STATUS_LABEL, STATUS_TONE } from './labels';

type Decision = 'approve' | 'reject' | null;
const MIN_REASON = 3;

/** One department request in the shared Sheet: who asked for what, what the department needs and whether the organisation has it, and the decision. */
export function DepartmentRequestSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [decision, setDecision] = useState<Decision>(null);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({ ...staffDepartmentQueries.request(api.departments, id ?? ''), enabled: id !== null });
  const r = q.data;

  const close = () => {
    setDecision(null);
    setText('');
    setError(null);
  };
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: staffDepartmentKeys.all });
    void qc.invalidateQueries({ queryKey: staffKeys.session() });
  };
  const run = useMutation({
    mutationFn: ({ kind, value }: { kind: 'approve' | 'reject'; value: string }) => (kind === 'approve' ? api.departments.approve(id ?? '', value) : api.departments.reject(id ?? '', value)),
    retry: false,
    onSuccess: (done, v) => {
      qc.setQueryData(staffDepartmentKeys.request(done.id), done);
      refresh();
      toast.show(t(v.kind === 'approve' ? 'st.depts.approved' : 'st.depts.rejected', { org: done.orgName, department: done.departmentName }));
      close();
    },
    onError: (e) => {
      if (isApiError(e) && e.status === 409) {
        // Somebody decided first: show the current state instead of an error.
        toast.show(e.problem.detail ?? t('st.depts.alreadyDecided'), 'info');
        refresh();
        close();
        return;
      }
      setError(isApiError(e) && e.status === 422 ? problemMessage(e) : definitiveFailure(e) || isApiError(e) ? problemMessage(e) : t('st.depts.failed'));
    }
  });
  const reason = text.trim();
  const canSubmit = decision === 'approve' || reason.length >= MIN_REASON;
  const submit = () => {
    if (!decision || run.isPending || !canSubmit) return;
    run.mutate({ kind: decision, value: reason });
  };
  const missing = r?.prompts.filter((p) => p.satisfied === false) ?? [];

  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={r ? r.orgName : t('st.depts.sheet.title')} description={r ? t('st.depts.sheet.asks', { department: r.departmentName }) : undefined} closeLabel={t('st.depts.close')}>
      {q.isPending && id !== null ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {r ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="m-0 flex-1 text-xl font-bold">{r.orgName}</h2>
              <StatusPill tone={STATUS_TONE[r.status]}>{t(STATUS_LABEL[r.status])}</StatusPill>
            </div>
            <p className="m-0 text-sm text-muted">{t('st.depts.sheet.asks', { department: r.departmentName })}</p>
          </div>
          {r.status === 'PENDING' ? (
            r.canDecide ? (
              <div role="group" aria-label={t('st.depts.decision')} className="flex flex-wrap gap-2">
                <Button size="sm" onClick={() => setDecision('approve')}>
                  {t('st.depts.approve')}
                </Button>
                <Button size="sm" variant="neutral" className="text-danger" onClick={() => setDecision('reject')}>
                  {t('st.depts.reject')}
                </Button>
              </div>
            ) : (
              <Notice tone="info" icon="eye" title={t('st.depts.readOnly')}>
                {t('st.depts.readOnlyBody')}
              </Notice>
            )
          ) : null}
          <Details r={r} />
          <section aria-label={t('st.depts.sheet.note')} className="flex flex-col gap-2">
            <h3 className="m-0 text-sm font-bold">{t('st.depts.sheet.note')}</h3>
            <p className="m-0 text-sm [overflow-wrap:anywhere]">{r.note ?? <span className="text-muted">{t('st.depts.sheet.noNote')}</span>}</p>
          </section>
          <Prompts r={r} />
          <section aria-label={t('st.depts.sheet.current')} className="flex flex-col gap-2">
            <h3 className="m-0 text-sm font-bold">{t('st.depts.sheet.current')}</h3>
            {r.exempt ? (
              <p className="m-0 text-sm">{t('st.depts.everyDepartment')}</p>
            ) : r.currentDepartments.length ? (
              <ul className="m-0 flex list-disc flex-col gap-0.5 pl-5 text-sm">
                {r.currentDepartments.map((d) => (
                  <li key={d.code}>{d.name}</li>
                ))}
              </ul>
            ) : (
              <p className="m-0 text-sm text-muted">{t('st.depts.noneYet')}</p>
            )}
          </section>
          {recordHref('organisations', r.orgId) ? (
            <p className="m-0 text-sm">
              <Link href={recordHref('organisations', r.orgId) ?? '#'}>{t('st.depts.openOrganisation')}</Link>
            </p>
          ) : null}
        </div>
      ) : null}
      <Dialog open={decision !== null} onOpenChange={(o) => !o && !run.isPending && close()} title={decision === 'approve' ? t('st.depts.approveTitle') : t('st.depts.rejectTitle')} width={460} closeLabel={t('st.depts.close')}>
        {r ? (
          <form
            className="flex flex-col gap-4"
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <p className="m-0">{t(decision === 'approve' ? 'st.depts.approveBody' : 'st.depts.rejectBody', { org: r.orgName, department: r.departmentName })}</p>
            {decision === 'approve' && missing.length ? (
              <Notice tone="warning" title={t('st.depts.missingTitle')}>
                {t('st.depts.missingBody', { items: missing.map((p) => t(PROMPT_LABEL[p.requirement])).join(', ') })}
              </Notice>
            ) : null}
            <Field label={t(decision === 'approve' ? 'st.depts.noteOptional' : 'st.depts.reasonRequired')} help={t(decision === 'approve' ? 'st.depts.noteHelp' : 'st.depts.reasonHelp')} id="dept-decision-text">
              {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={4} maxLength={500} value={text} onChange={(e) => setText(e.currentTarget.value)} />}
            </Field>
            {error ? (
              <Notice tone="danger" title={t('st.depts.notDone')} role="alert">
                {error}
              </Notice>
            ) : null}
            <div className="flex justify-end gap-2">
              <Button type="button" variant="neutral" disabled={run.isPending} onClick={close}>
                {t('st.depts.cancel')}
              </Button>
              <Button type="submit" loading={run.isPending} disabled={!canSubmit || run.isPending}>
                {decision === 'approve' ? t('st.depts.approve') : t('st.depts.reject')}
              </Button>
            </div>
          </form>
        ) : null}
      </Dialog>
    </Sheet>
  );
}

function Details({ r }: { r: StaffDepartmentRequest }) {
  const t = useT();
  const rows: [string, string][] = [
    [t('st.depts.col.organisation'), r.orgName],
    [t('st.depts.col.department'), r.departmentName],
    [t('st.depts.col.requestedBy'), r.requestedByName],
    [t('st.depts.col.requestedAt'), dateTime(r.requestedAt)]
  ];
  if (r.decidedByName) rows.push([t('st.depts.col.decidedBy'), r.decidedByName]);
  if (r.decidedAt) rows.push([t('st.depts.col.decidedAt'), dateTime(r.decidedAt)]);
  if (r.decisionNote) rows.push([t(r.status === 'REJECTED' ? 'st.depts.sheet.rejectReason' : 'st.depts.sheet.decisionNote'), r.decisionNote]);
  return (
    <section aria-label={t('st.depts.sheet.request')} className="flex flex-col gap-2">
      <h3 className="m-0 text-sm font-bold">{t('st.depts.sheet.request')}</h3>
      <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1.5 text-sm">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted">{k}</dt>
            <dd className="m-0 [overflow-wrap:anywhere]">{v}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/** What the department asks the seller to have on file, with a clear (not colour-only) satisfied / not satisfied mark. */
function Prompts({ r }: { r: StaffDepartmentRequest }) {
  const t = useT();
  return (
    <section aria-label={t('st.depts.sheet.prompts')} className="flex flex-col gap-2">
      <h3 className="m-0 text-sm font-bold">{t('st.depts.sheet.prompts')}</h3>
      {r.prompts.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.depts.sheet.noPrompts')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-1.5 p-0 text-sm">
          {r.prompts.map((p) => (
            <li key={p.requirement} className="flex items-start gap-2">
              {p.satisfied === true ? <CheckCircle size={18} weight="fill" aria-hidden className="mt-px flex-none text-success" /> : <WarningCircle size={18} weight={p.satisfied === false ? 'fill' : 'regular'} aria-hidden className={p.satisfied === false ? 'mt-px flex-none text-warning' : 'mt-px flex-none text-muted'} />}
              <span>
                <strong className="font-semibold">{t(PROMPT_LABEL[p.requirement])}</strong>
                {' — '}
                {p.satisfied === true ? t('st.depts.satisfied') : p.satisfied === false ? t('st.depts.notSatisfied') : t('st.depts.notChecked')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
