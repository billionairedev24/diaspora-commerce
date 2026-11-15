'use client';

import { ACCESS_REASON_MAX, ACCESS_REASON_MIN } from '@afrotransact/api-client';
import { Button, Dialog, DialogActions } from '@afrotransact/ui';
import { type ReactNode, useEffect, useState } from 'react';

import { ReasonField, reasonRule } from '@/components/ui/ReasonField';
import { useT } from '@/i18n/t';

/**
 * Confirms one access change and asks why. The reason (3 to 300 characters) is sent with the change and kept in the access change
 * log next to the staff member's name. Confirm stays disabled until the reason is long enough, and says why on screen.
 */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  children,
  confirmLabel,
  danger = false,
  pending,
  error,
  onConfirm
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  pending: boolean;
  error: string | null;
  onConfirm: (reason: string) => void;
}) {
  const t = useT();
  const [reason, setReason] = useState('');
  const rule = reasonRule(t, reason, ACCESS_REASON_MIN);
  // A reason belongs to one change: closing the dialog (cancelled or done) starts the next one empty.
  useEffect(() => {
    if (!open) setReason('');
  }, [open]);
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      width={460}
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (rule.ok && !pending) onConfirm(reason.trim());
        }}
      >
        <div className="text-sm text-muted">{children}</div>
        <ReasonField label={t('st.acc.reason.label')} value={reason} onChange={setReason} min={ACCESS_REASON_MIN} max={ACCESS_REASON_MAX} rows={3} error={error} />
        <DialogActions>
          <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
            {t('st.acc.cancel')}
          </Button>
          <Button type="submit" variant={danger ? 'danger' : 'primary'} disabled={!rule.ok} loading={pending}>
            {confirmLabel}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
