'use client';

import { Alert, AlertAction, Badge } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';

import { RequestDepartmentButton } from '@/components/listings/RequestDepartment';
import { Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { useSellerScope } from '@/lib/seller-scope';
import { useSeller } from '@/lib/session';

/**
 * Settings › Departments: the departments the store sells in (approved), the ones waiting for review (pending) and the ones staff declined, with the
 * way to ask for another. Nothing is shown for a service provider (no departments) or AfroTransact Retail (all of them).
 */
export function DepartmentsSettings() {
  const t = useT();
  const qc = useQueryClient();
  const { isProvider } = useSeller();
  const scope = useSellerScope();
  if (isProvider || scope.exempt) return null;
  const pending = scope.requests.filter((r) => r.status === 'PENDING');
  const declined = scope.requests.filter((r) => r.status === 'REJECTED' && !scope.allows(r.departmentCode));
  return (
    <section aria-labelledby="settings-departments" className="flex flex-col gap-3 pt-6">
      <h2 id="settings-departments" className="m-0 text-xl font-semibold">{t('sc.settings.departments')}</h2>
      <p className="m-0 max-w-[70ch] text-sm text-muted">{t('sc.settings.departmentsHint')}</p>
      {scope.isPending ? <Spinner label={t('sc.scope.loading')} center={false} /> : null}
      {scope.isError ? (
        <Alert variant="danger" title={t('sc.settings.departmentsFailed')} action={<AlertAction onClick={() => void qc.invalidateQueries()}>{t('sc.settings.retry')}</AlertAction>} />
      ) : (
        <ul className="m-0 flex list-none flex-col border-t border-border p-0">
          {scope.departments.map((d) => (
            <li key={d.code} className="flex min-h-11 items-center justify-between gap-3 border-b border-border py-2 text-sm">
              <span>{d.name}</span>
              <Badge tone="success">{t('sc.settings.approved')}</Badge>
            </li>
          ))}
          {pending.map((r) => (
            <li key={`p-${r.departmentCode}`} className="flex min-h-11 items-center justify-between gap-3 border-b border-border py-2 text-sm">
              <span>{r.departmentName}</span>
              <Badge tone="warning">{t('sc.settings.pending')}</Badge>
            </li>
          ))}
          {declined.map((r) => (
            <li key={`d-${r.departmentCode}`} className="flex min-h-11 items-center justify-between gap-3 border-b border-border py-2 text-sm">
              <span>{r.departmentName}</span>
              <Badge tone="danger">{t('sc.settings.declined')}</Badge>
            </li>
          ))}
          {!scope.isPending && !scope.departments.length && !pending.length && !declined.length ? (
            <li className="py-2 text-sm text-muted">{t('sc.settings.departmentsNone')}</li>
          ) : null}
        </ul>
      )}
      <div>
        <RequestDepartmentButton variant="neutral" />
      </div>
    </section>
  );
}
