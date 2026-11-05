'use client';

import { isApiError, staffDepartmentKeys, staffDepartmentQueries,type StaffOrgDepartments } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, useToast } from '@afrotransact/ui';
import { PencilSimple } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { MIN_REASON, ReasonField, reasonRule } from '@/components/ui/ReasonField';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';

import { PROMPT_LABEL } from './labels';

/**
 * "Departments" on an organisation's sheet: the departments it may sell in, by name, and (with applications:approve)
 * an Edit action that replaces them with a reason. Hidden for staff without applications:review.
 */
export function OrgDepartmentsSection({ orgId }: { orgId: string }) {
  const { hasPermission } = useStaff();
  if (!hasPermission('applications:review') && !hasPermission('applications:approve')) return null;
  return <Loaded orgId={orgId} />;
}

function Loaded({ orgId }: { orgId: string }) {
  const t = useT();
  const api = staffApi();
  const q = useQuery(staffDepartmentQueries.org(api.departments, orgId));
  const [editing, setEditing] = useState(false);
  const o = q.data;
  return (
    <section aria-label={t('st.depts.org.title')} className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <h3 className="m-0 flex-1 text-sm font-bold">{t('st.depts.org.title')}</h3>
        {o?.canEdit ? (
          <Button variant="neutral" size="sm" icon={<PencilSimple size={14} aria-hidden />} onClick={() => setEditing(true)}>
            {t('st.depts.org.edit')}
          </Button>
        ) : null}
      </div>
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {o ? (
        <>
          {o.exempt ? (
            <p className="m-0 text-sm">{t('st.depts.everyDepartment')}</p>
          ) : o.departments.length ? (
            <ul className="m-0 flex list-disc flex-col gap-0.5 pl-5 text-sm">
              {o.departments.map((d) => (
                <li key={d.code}>{d.name}</li>
              ))}
            </ul>
          ) : (
            <p className="m-0 text-sm text-muted">{t('st.depts.org.none')}</p>
          )}
          {o.pendingRequests > 0 ? (
            <p className="m-0 text-sm">
              <Link href="/department-requests">{t('st.depts.org.pending', { count: String(o.pendingRequests) })}</Link>
            </p>
          ) : null}
          <EditDepartments org={o} open={editing} onClose={() => setEditing(false)} />
        </>
      ) : null}
    </section>
  );
}

/** Multi-select from the catalog's departments with a required reason; Save stays off until something changed. */
function EditDepartments({ org, open, onClose }: { org: StaffOrgDepartments; open: boolean; onClose: () => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const initial = org.departments.map((d) => d.code);
  const [chosen, setChosen] = useState<string[]>(initial);
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  const changed = chosen.length !== initial.length || chosen.some((c) => !initial.includes(c));
  const reasonOk = reasonRule(t, reason, MIN_REASON).ok;
  const reset = () => {
    setChosen(initial);
    setReason('');
    setErrors({});
    setError(null);
  };
  const save = useMutation({
    mutationFn: () => api.departments.setOrgDepartments(org.orgId, chosen, reason),
    retry: false,
    onSuccess: (saved) => {
      qc.setQueryData(staffDepartmentKeys.org(org.orgId), saved);
      void qc.invalidateQueries({ queryKey: staffDepartmentKeys.all });
      toast.show(t('st.depts.org.saved', { org: saved.orgName }));
      onClose();
    },
    onError: (e) => {
      setErrors(isApiError(e) ? e.fieldErrors : {});
      setError(problemMessage(e));
    }
  });
  const toggle = (code: string, on: boolean) => setChosen((c) => (on ? [...c, code] : c.filter((x) => x !== code)));
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o && !save.isPending) {
          reset();
          onClose();
        }
      }}
      title={t('st.depts.org.editTitle', { org: org.orgName })}
      width={460}
      closeLabel={t('st.depts.close')}
    >
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (changed && reasonOk && !save.isPending) save.mutate();
        }}
      >
        <p className="m-0 text-muted">{t('st.depts.org.editIntro')}</p>
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0" aria-describedby={errors.departments ? 'dept-set-err' : undefined}>
          <legend className="mb-1 text-sm font-semibold">{t('st.depts.org.title')}</legend>
          {org.catalog.map((d) => (
            <Checkbox
              key={d.code}
              label={d.name}
              description={d.requires.length ? `(${d.requires.map((r) => t(PROMPT_LABEL[r])).join(', ')})` : undefined}
              checked={chosen.includes(d.code)}
              onChange={(e) => toggle(d.code, e.currentTarget.checked)}
            />
          ))}
          {errors.departments ? (
            <span id="dept-set-err" role="alert" className="text-xs text-danger">
              {errors.departments}
            </span>
          ) : null}
        </fieldset>
        {changed && chosen.length === 0 ? <Notice tone="warning" title={t('st.depts.org.emptyTitle')}>{t('st.depts.org.emptyBody')}</Notice> : null}
        {!changed ? <p className="m-0 text-sm text-muted">{t('st.depts.org.noChange')}</p> : null}
        <ReasonField id="dept-set-reason" label={t('st.depts.org.reason')} help={t('st.depts.org.reasonHelp')} min={MIN_REASON} value={reason} onChange={setReason} error={errors.reason} />
        {error && !errors.departments && !errors.reason ? (
          <Notice tone="danger" title={t('st.depts.notDone')} role="alert">
            {error}
          </Notice>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button
            type="button"
            variant="neutral"
            disabled={save.isPending}
            onClick={() => {
              reset();
              onClose();
            }}
          >
            {t('st.depts.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!changed || !reasonOk || save.isPending}>
            {t('st.depts.org.save')}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
