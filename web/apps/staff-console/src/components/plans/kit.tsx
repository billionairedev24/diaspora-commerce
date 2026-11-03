'use client';

import { isApiError, type RecordTone, staffPlanQueries, type StaffPlansApi } from '@afrotransact/api-client';
import { Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';

import { problemMessage } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';

/* Shared pieces of Plans & billing (S292–S297): the API, what the signed-in staff member may change, field errors from
   the seller service, the reason-code picker every adjustment needs, and amounts typed in dollars. */

export type T = Translator<MessageKey>;

export const plansApi = (): StaffPlansApi => staffApi().plans;

/** plans:manage changes the catalogue, campaigns and rules; billing:adjust an organisation's billing. */
export function usePlanRights() {
  const { hasPermission, can } = useStaff();
  const edit = can('plans', 'EDIT');
  return { manage: edit && hasPermission('plans:manage'), adjust: edit && hasPermission('billing:adjust') };
}

/** The seller service's field errors (422), or a single message under `_` for anything else. */
export function errorsOf(e: unknown): Record<string, string> {
  if (isApiError(e) && Object.keys(e.fieldErrors).length) return e.fieldErrors;
  return { _: problemMessage(e) };
}

/** "24.99" → 2499; '' → null; NaN when not an amount. */
export function centsOf(v: string): number | null {
  if (v.trim() === '') return null;
  const n = Number(v.replace(/[$,\s]/g, ''));
  return Number.isFinite(n) ? Math.round(n * 100) : Number.NaN;
}

export const dollarsOf = (cents: number | null | undefined): string => (cents == null ? '' : (cents / 100).toFixed(cents % 100 ? 2 : 0));

/** '' → null, otherwise the whole number typed. */
export function intOf(v: string): number | null {
  if (v.trim() === '') return null;
  const n = Number(v);
  return Number.isInteger(n) ? n : Number.NaN;
}

export const STATUS_TONE: Record<string, RecordTone> = {
  ACTIVE: 'good', PUBLISHED: 'good', APPLIED: 'good', IN_SYNC: 'good', FREE: 'info', DRAFT: 'info', PENDING: 'info', ADOPTABLE: 'info', PAUSED: 'warn', MISSING: 'warn',
  DRIFT: 'bad', FAILED: 'bad', RETIRED: 'brand', ENDED: 'brand', SUPERSEDED: 'brand', HIDDEN: 'brand', INVITE_ONLY: 'info', PUBLIC: 'good'
};

/** Reason code (required) and note (required for OTHER): kept with the grant and in the audit trail. */
export function ReasonFields({ reason, note, onReason, onNote, errors, idPrefix }: {
  reason: string;
  note: string;
  onReason: (v: string) => void;
  onNote: (v: string) => void;
  errors: Record<string, string>;
  idPrefix: string;
}) {
  const t = useT();
  const codes = useQuery(staffPlanQueries.reasonCodes(plansApi()));
  return (
    <>
      <Select id={`${idPrefix}-reason`} label={t('st.plans.reason')} placeholder={t('st.plans.chooseReason')} value={reason} error={errors.reasonCode}
        options={(codes.data ?? []).map((c) => ({ value: c.code, label: c.label }))} onChange={(e) => onReason(e.currentTarget.value)} />
      <TextField id={`${idPrefix}-note`} label={reason === 'OTHER' ? t('st.plans.noteRequired') : t('st.plans.note')} maxLength={500} value={note} error={errors.note}
        onChange={(e) => onNote(e.currentTarget.value)} />
    </>
  );
}
