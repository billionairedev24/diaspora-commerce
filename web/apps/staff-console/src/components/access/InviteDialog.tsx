'use client';

import { ACCESS_REASON_MAX, ACCESS_REASON_MIN, isApiError, staffAccessKeys, staffAccessQueries } from '@afrotransact/api-client';
import { Button, Dialog, DialogActions, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { ReasonField, reasonRule } from '@/components/ui/ReasonField';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

const EMPTY = { firstName: '', lastName: '', email: '', roleKey: '', reason: '' };

/**
 * Invite a person to be staff: name, work email, one role and why. Nothing is created here: identity stores a pending invitation
 * and emails a link; the person chooses their own password from it and the account opens then.
 */
export function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const roles = useQuery({ ...staffAccessQueries.roles(api.access, 'platform'), enabled: open });
  const [v, setV] = useState(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    if (!open) {
      setV(EMPTY);
      setErrors({});
      setFailure(null);
    }
  }, [open]);
  const invite = useMutation({
    mutationFn: () => api.access.invite({ firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim(), roleKey: v.roleKey, reason: v.reason.trim() }),
    retry: false,
    onSuccess: (done) => {
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t('st.inv.toast.sent', { email: done.email }));
      onOpenChange(false);
    },
    onError: (e) => {
      setErrors(isApiError(e) ? e.fieldErrors : {});
      setFailure(isApiError(e) && Object.keys(e.fieldErrors).length ? null : problemMessage(e));
    }
  });
  const set = (k: keyof typeof EMPTY, value: string) => setV((p) => ({ ...p, [k]: value }));
  const ready = v.firstName.trim() !== '' && v.lastName.trim() !== '' && v.email.trim() !== '' && v.roleKey !== '' && reasonRule(t, v.reason, ACCESS_REASON_MIN).ok;
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('st.inv.invite.title')} width={520}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (ready && !invite.isPending) invite.mutate();
        }}
      >
        <p className="m-0 text-sm text-muted">{t('st.inv.invite.intro')}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label={t('st.inv.field.firstName')} required value={v.firstName} maxLength={80} autoComplete="off" error={errors.firstName} onChange={(e) => set('firstName', e.currentTarget.value)} />
          <TextField label={t('st.inv.field.lastName')} required value={v.lastName} maxLength={80} autoComplete="off" error={errors.lastName} onChange={(e) => set('lastName', e.currentTarget.value)} />
        </div>
        <TextField label={t('st.inv.field.email')} type="email" required value={v.email} maxLength={254} autoComplete="off" error={errors.email} help={t('st.inv.field.emailHelp')} onChange={(e) => set('email', e.currentTarget.value)} />
        <Select
          label={t('st.inv.field.role')}
          required
          value={v.roleKey}
          error={errors.roleKey}
          onChange={(e) => set('roleKey', e.currentTarget.value)}
          options={[{ value: '', label: t('st.inv.field.rolePick') }, ...(roles.data?.items ?? []).map((r) => ({ value: r.key, label: r.name }))]}
        />
        <ReasonField label={t('st.inv.field.reason')} value={v.reason} onChange={(r) => set('reason', r)} min={ACCESS_REASON_MIN} max={ACCESS_REASON_MAX} rows={3} error={errors.reason ?? null} />
        {failure ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {failure}
          </p>
        ) : null}
        <DialogActions>
          <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
            {t('st.acc.cancel')}
          </Button>
          <Button type="submit" disabled={!ready} loading={invite.isPending}>
            {t('st.inv.invite.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
