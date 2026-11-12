'use client';

import { type CatalogMatch, type ChangeRequestInput, isApiError, matchingKeys } from '@afrotransact/api-client';
import { Button, Field, fieldStyles, FormActions, FormFields, Message, Select, TextField, TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { digitsOf } from './match-model';

export type ReportKind = 'wrong' | 'duplicate' | 'suggest';
type Suggest = 'TITLE' | 'DESCRIPTION' | 'BRAND';
const SUGGESTIONS: Suggest[] = ['TITLE', 'DESCRIPTION', 'BRAND'];

const PROBLEMS: Record<string, MessageKey> = {
  CHANGE_REQUEST_EXISTS: 'sc.match.err.requestExists',
  NOT_YOUR_OFFER: 'sc.match.err.notYourOffer',
  WRONG_MATCH_OPEN: 'sc.match.blocker.wrongOpen'
};

interface Props {
  kind: ReportKind;
  /** The page the seller is looking at (the card, or the offer form). */
  match: CatalogMatch;
  /** The variant the report is about. */
  atin: string;
  onClose: () => void;
}

/**
 * The three reports on a page that already exists, flat and short: "Suggest a change" (title, description or brand), "This is the wrong
 * product" and "This page is a duplicate". Staff decide; nothing changes on the page now, and the seller reads Waiting, Applied or Not applied
 * because ... in the list below the barcode. The other page of a duplicate is found by its barcode, so no id is ever typed.
 */
export function ReportPanel({ kind, match, atin, onClose }: Props) {
  const t = useT();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const api = sellerApi().matching;
  const [what, setWhat] = useState<Suggest>('TITLE');
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const [other, setOther] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);
  const page = match.parentAtin ?? match.atin ?? atin;

  const send = useMutation({
    mutationFn: async () => {
      let body: ChangeRequestInput;
      if (kind === 'wrong') body = { type: 'WRONG_MATCH', payload: { variantAtin: atin, ...(match.digits ? { gtin: match.digits } : {}) }, note: note.trim() || undefined };
      else if (kind === 'suggest') body = { type: what, payload: { value: value.trim() }, note: note.trim() || undefined };
      else {
        // The other page is found by its barcode, like everywhere else: no id is typed.
        const found = await api.match({ gtin: digitsOf(other) });
        if (found.result !== 'MATCH' || !found.parentAtin) throw new ReportError('sc.match.dup.notFound');
        if (found.parentAtin === page) throw new ReportError('sc.match.dup.same');
        body = { type: 'DUPLICATE_PAGE', payload: { duplicateOfAtin: found.parentAtin }, note: note.trim() || undefined };
      }
      return api.fileChangeRequest(page, body);
    },
    onSuccess: () => {
      setError('');
      setDone(true);
      void qc.invalidateQueries({ queryKey: matchingKeys.changeRequests(orgId) });
    },
    onError: (e) => {
      if (e instanceof ReportError) setError(t(e.key));
      else {
        const known = isApiError(e) ? PROBLEMS[e.code ?? ''] : undefined;
        setError(known ? t(known) : problemMessage(e));
      }
    }
  });

  const submit = () => {
    if (kind === 'suggest' && !value.trim()) return setError(t('sc.match.suggest.valueMissing'));
    if (kind === 'duplicate' && digitsOf(other).length < 8) return setError(t('sc.match.dup.codeMissing'));
    setError('');
    send.mutate();
  };

  if (done) {
    return (
      <div className="flex flex-col gap-2" data-testid="report-done">
        <Message tone="success">{t(kind === 'wrong' ? 'sc.match.wrongSent' : kind === 'duplicate' ? 'sc.match.dupSent' : 'sc.match.suggest.sent')}</Message>
        <div><TextLinkButton onClick={onClose}>{t('sc.match.report.close')}</TextLinkButton></div>
      </div>
    );
  }
  const heading = kind === 'wrong' ? 'sc.match.wrongTitle' : kind === 'duplicate' ? 'sc.match.dupTitle' : 'sc.match.suggest.title';
  const lead = kind === 'wrong' ? 'sc.match.wrongBody' : kind === 'duplicate' ? 'sc.match.dupBody' : 'sc.match.suggest.body';
  return (
    <FormFields label={t(heading)} onSubmit={(e) => { e.preventDefault(); submit(); }} testId={`report-${kind}`}>
      <h3 className="m-0 text-base font-semibold">{t(heading)}</h3>
      <p className="m-0 text-sm text-muted">{t(lead)}</p>
      {kind === 'suggest' ? (
        <>
          <Select id="report-what" label={t('sc.match.suggest.what')} value={what} onChange={(e) => setWhat(e.currentTarget.value as Suggest)}
            options={SUGGESTIONS.map((s) => ({ value: s, label: t(`sc.change.type.${s}` as MessageKey) }))} />
          <TextField id="report-value" label={t('sc.match.suggest.value')} value={value} maxLength={2000} onChange={(e) => setValue(e.target.value)} />
        </>
      ) : null}
      {kind === 'duplicate' ? (
        <TextField id="report-other" label={t('sc.match.dup.code')} help={t('sc.match.dup.codeHelp')} inputMode="numeric" autoComplete="off" value={other} onChange={(e) => setOther(e.target.value)} />
      ) : null}
      <Field label={t(kind === 'wrong' ? 'sc.match.wrongNote' : 'sc.match.report.note')} id="report-note">
        {(a11y) => <textarea className={fieldStyles.control} rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} {...a11y} />}
      </Field>
      {error ? <Message tone="error">{error}</Message> : null}
      <FormActions>
        <Button type="submit" variant="primary" loading={send.isPending}>{t('sc.match.report.send')}</Button>
        <TextLinkButton onClick={onClose}>{t('sc.match.report.cancel')}</TextLinkButton>
      </FormActions>
    </FormFields>
  );
}

class ReportError extends Error {
  constructor(readonly key: MessageKey) {
    super(key);
  }
}

/** "No, this is not my product" on the match card. */
export function ReportWrongProduct({ match, atin, onClose }: { match: CatalogMatch; atin: string; onClose: () => void }) {
  return <ReportPanel kind="wrong" match={match} atin={atin} onClose={onClose} />;
}
