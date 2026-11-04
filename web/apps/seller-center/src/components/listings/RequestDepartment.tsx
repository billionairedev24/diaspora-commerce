'use client';

import { type DepartmentRequirement, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSellerScope } from '@/lib/seller-scope';
import { useSeller } from '@/lib/session';

const NEEDS_KEY: Record<DepartmentRequirement, 'sc.scope.needsFood' | 'sc.scope.needsBeauty'> = {
  FOOD_PERMIT: 'sc.scope.needsFood',
  BEAUTY_LABELLING: 'sc.scope.needsBeauty'
};

/**
 * "Need another department? Request it": a quiet text button under the category pickers. It opens the request: pick a
 * department the organisation cannot sell in yet, add a note, send. Staff approve; what the department asks for (a food
 * permit, beauty labelling) is said in the answer. Nothing is shown to organisations that already have every department.
 */
/** Whether the member may ask for another department (a provider organisation and Retail have none to ask for; a read-only role cannot). */
export function useCanRequestDepartment(): boolean {
  const { exempt } = useSellerScope();
  const { can, isProvider } = useSeller();
  return !exempt && !isProvider && can('listings');
}

/** The same request as a button ("Request a department"): the main action when the store has no departments, and in the settings. */
export function RequestDepartmentButton({ variant = 'primary' }: { variant?: 'primary' | 'neutral' }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const allowed = useCanRequestDepartment();
  if (!allowed) return null;
  return (
    <>
      <Button variant={variant} onClick={() => setOpen(true)}>{t('sc.scope.requestButton')}</Button>
      {open ? <RequestDepartmentDialog onClose={() => setOpen(false)} /> : null}
    </>
  );
}

export function RequestDepartmentLink({ className }: { className?: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const allowed = useCanRequestDepartment();
  if (!allowed) return null;
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className={className ?? 'w-fit cursor-pointer border-0 bg-transparent p-0 text-xs text-muted underline hover:text-text'}>
        {t('sc.scope.requestLink')}
      </button>
      {open ? <RequestDepartmentDialog onClose={() => setOpen(false)} /> : null}
    </>
  );
}

export function RequestDepartmentDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const api = sellerApi();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const scope = useSellerScope();
  const catalog = useQuery(sellerQueries.departments(api));
  const [code, setCode] = useState('');
  const [note, setNote] = useState('');
  const [needs, setNeeds] = useState<DepartmentRequirement[] | null>(null);
  const options = (catalog.data ?? [])
    .filter((d) => !scope.allows(d.code) && !scope.pendingCodes.includes(d.code))
    .map((d) => ({ value: d.code, label: d.name }));

  const send = useMutation({
    mutationFn: () => api.requestDepartment({ departmentCode: code, note: note.trim() || undefined }),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.scope(orgId) });
      toast.show(t('sc.scope.sent'));
      if (r.needs.length === 0) onClose();
      else setNeeds(r.needs);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const names = scope.departments.map((d) => d.name).join(', ');
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }} title={t('sc.scope.dialogTitle')} width={460} closeLabel={t('sc.scope.close')}
      description={names ? t('sc.scope.dialogIntro', { departments: names }) : t('sc.scope.dialogIntroNone')}>
      {needs ? (
        <div className="flex flex-col gap-3 pt-2">
          <p className="m-0 text-md">{t('sc.scope.sent')}</p>
          {needs.map((n) => (
            <p key={n} className="m-0 text-sm text-muted">{t(NEEDS_KEY[n])}</p>
          ))}
          <div className="flex justify-end">
            <Button variant="neutral" onClick={onClose}>{t('sc.scope.close')}</Button>
          </div>
        </div>
      ) : (
        <form className="flex flex-col gap-3 pt-2" onSubmit={(e) => { e.preventDefault(); if (code) send.mutate(); }}>
          {options.length === 0 && !catalog.isPending ? (
            <p className="m-0 text-sm text-muted">{t('sc.scope.none')}</p>
          ) : (
            <Select id="scope-department" label={t('sc.scope.department')} placeholder={t('sc.scope.choose')} options={options} value={code} onChange={(e) => setCode(e.target.value)} />
          )}
          <TextField label={t('sc.scope.note')} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          {scope.pendingCodes.length > 0 ? (
            <p className="m-0 text-xs text-muted">
              {t('sc.scope.pending', { departments: scope.requests.filter((r) => r.status === 'PENDING').map((r) => r.departmentName).join(', ') })}
            </p>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="neutral" onClick={onClose}>{t('sc.scope.cancel')}</Button>
            <Button type="submit" variant="primary" disabled={!code} loading={send.isPending}>{t('sc.scope.send')}</Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
