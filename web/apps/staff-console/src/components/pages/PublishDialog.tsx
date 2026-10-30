'use client';

import { type ContentKind } from '@afrotransact/api-client';
import { type ContentIssue } from '@afrotransact/content';
import { Button, Dialog, TextField } from '@afrotransact/ui';
import { type FormEvent, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';

import { issueText } from './MarkdownEditor';

export interface PublishDialogProps {
  open: boolean;
  onClose: () => void;
  kind: ContentKind;
  title: string;
  /** What shoppers get: "Version 3.3 · effective 2026-12-01 · final" or "Revision 4". */
  summary: string[];
  /** Problems that stop publication (shown instead of the button). */
  blocking: readonly ContentIssue[];
  /** Translations still marked for review (a warning, not a block). */
  needsReview: readonly string[];
  /** A legal document: someone other than the draft's last editor must publish. */
  secondApprover: boolean;
  /** This person saved the last change, so a legal document must be published by someone else. */
  isLastEditor: boolean;
  /** The access level reason, when this person may not publish. */
  deniedReason: string | null;
  busy: boolean;
  errors: Record<string, string>;
  onPublish: (reason: string) => void;
}

/** Publishing needs a reason (kept in the audit trail), and for legal documents a second approver. */
export function PublishDialog({ open, onClose, kind, title, summary, blocking, needsReview, secondApprover, isLastEditor, deniedReason, busy, errors, onPublish }: PublishDialogProps) {
  const t = useT();
  const [reason, setReason] = useState('');
  const blocked = blocking.length > 0 || (secondApprover && isLastEditor) || deniedReason !== null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!blocked && reason.trim().length >= 5) onPublish(reason.trim());
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={t('st.pages.publish.title', { title })} description={t('st.pages.publish.intro')} width={560} closeLabel={t('st.pages.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-sm" aria-label={t('st.pages.publish.what')}>
          {summary.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ul>
        {deniedReason ? (
          <Notice tone="warning" role="alert" title={t('st.pages.publish.notAllowed')}>
            {deniedReason}
          </Notice>
        ) : null}
        {secondApprover && !deniedReason ? (
          isLastEditor ? (
            <Notice tone="warning" role="alert" title={t('st.pages.publish.secondTitle')}>
              {t('st.pages.publish.secondBlocked')}
            </Notice>
          ) : (
            <Notice tone="info" title={t('st.pages.publish.secondTitle')}>
              {t('st.pages.publish.secondOk')}
            </Notice>
          )
        ) : null}
        {blocking.length > 0 ? (
          <div role="alert" className="flex flex-col gap-1 rounded-md border border-border bg-danger-bg p-3 text-sm">
            <strong>{t('st.pages.publish.blocking')}</strong>
            <ul className="m-0 list-disc ps-5">
              {blocking.map((i, n) => (
                <li key={`${i.code}-${n}`}>{issueText(t, i)}</li>
              ))}
            </ul>
          </div>
        ) : null}
        {needsReview.length > 0 ? (
          <Notice tone="warning" title={t('st.pages.publish.reviewTitle')}>
            {t('st.pages.publish.reviewBody', { languages: needsReview.join(', ') })}
          </Notice>
        ) : null}
        {kind === 'legal' && errors.bodies ? <p role="alert" className="m-0 text-sm text-danger">{errors.bodies}</p> : null}
        {errors.changeSummary ? <p role="alert" className="m-0 text-sm text-danger">{errors.changeSummary}</p> : null}
        <TextField
          id="publish-reason"
          label={t('st.pages.publish.reason')}
          help={t('st.pages.publish.reasonHelp')}
          value={reason}
          maxLength={500}
          error={errors.reason}
          onChange={(e) => setReason(e.currentTarget.value)}
        />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>
            {t('st.pages.cancel')}
          </Button>
          <Button type="submit" disabled={blocked || reason.trim().length < 5 || busy}>
            {busy ? t('st.pages.publish.publishing') : t('st.pages.publish.confirm')}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
