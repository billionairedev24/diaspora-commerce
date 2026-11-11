'use client';

import { type ApplyToSell, isApiError, sellerQueries, type SellingApprovalRow, type SellingDocumentInput, type SellingDocumentSlot } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, TextField, useToast } from '@afrotransact/ui';
import { type QueryKey, useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { DocumentField } from '@/components/onboarding/WizardKit';
import { problemMessage } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

interface Draft {
  assetId: string;
  issuedOn: string;
  expiresOn: string;
  names: boolean;
}

const empty = (): Draft => ({ assetId: '', issuedOn: '', expiresOn: '', names: false });

/**
 * Apply to sell (or renew, or answer a request for more): one upload per document the approval asks for, with the date
 * read off it: an invoice dated within 180 days, a permit or insurance policy with its expiry date, insurance confirmed to
 * name AfroTransact as additional insured. Files are private (media, kyc-document); only reviewers see them. Staff answer
 * within 2 business days.
 */
export function ApplyToSellDialog({ row, onClose, invalidate }: { row: SellingApprovalRow; onClose: () => void; invalidate: QueryKey }) {
  const api = sellerApi();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = (kind: string, patch: Partial<Draft>) => setDrafts((d) => ({ ...d, [kind]: { ...(d[kind] ?? empty()), ...patch } }));

  const send = useMutation({
    mutationFn: () => {
      const documents: SellingDocumentInput[] = row.documents.flatMap((slot) => {
        const d = drafts[slot.kind];
        if (!d?.assetId) return [];
        return [{ kind: slot.kind, assetId: d.assetId, issuedOn: d.issuedOn || undefined, expiresOn: d.expiresOn || undefined, namesAfroTransact: slot.namesAfroTransact ? d.names : undefined }];
      });
      if (row.applicationStatus === 'INFO_REQUESTED' && row.applicationId) return api.resubmitSellingApplication(row.applicationId, { note: note.trim() || undefined, documents });
      const input: ApplyToSell = { scopeType: row.scopeType, scopeKey: row.scopeKey, note: note.trim() || undefined, documents };
      return api.applyToSell(input);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: invalidate });
      void qc.invalidateQueries({ queryKey: sellerQueries.sellingApplications(api, orgId).queryKey });
      void qc.invalidateQueries({ queryKey: [...invalidate.slice(0, 2), 'selling'] });
      toast.show('Sent. We aim to answer within 2 business days.');
      onClose();
    },
    onError: (e) => {
      setErrors(isApiError(e) ? e.fieldErrors : {});
      toast.show(problemMessage(e), 'error');
    }
  });

  const missingRequired = row.documents.some((s) => !s.optional && !drafts[s.kind]?.assetId);
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }} title={`Apply to sell: ${row.label}`} width={560}
      description="Apply once. After you are approved, every listing in this scope goes live after the usual checks. Your files are private: only our reviewers see them.">
      <form className="flex flex-col gap-4 pt-2" onSubmit={(e) => { e.preventDefault(); setErrors({}); send.mutate(); }}>
        {row.message ? <p className="m-0 text-sm text-muted">{row.message}</p> : null}
        {row.documents.length === 0 ? <p className="m-0 text-sm">No documents are needed. Tell us a little about your store below.</p> : null}
        {row.documents.map((slot) => (
          <SlotFields key={slot.kind} slot={slot} draft={drafts[slot.kind] ?? empty()} error={errors[`documents.${slot.kind}`]} onChange={(p) => set(slot.kind, p)} />
        ))}
        <TextField label="Anything we should know? (optional)" value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="neutral" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={missingRequired} loading={send.isPending}>{row.applicationStatus === 'INFO_REQUESTED' ? 'Send to our team' : 'Apply to sell'}</Button>
        </div>
      </form>
    </Dialog>
  );
}

function SlotFields({ slot, draft, error, onChange }: { slot: SellingDocumentSlot; draft: Draft; error?: string; onChange: (p: Partial<Draft>) => void }) {
  return (
    <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
      <DocumentField name={`selling-${slot.kind}`} label={`${slot.label}${slot.optional ? ' (optional)' : ''}`} assetId={draft.assetId} onChange={(assetId) => onChange({ assetId })} error={error} />
      {slot.dated ? <TextField label="Date on the document" type="date" value={draft.issuedOn} onChange={(e) => onChange({ issuedOn: e.target.value })} /> : null}
      {slot.expiryRequired ? <TextField label="Expiry date" type="date" value={draft.expiresOn} onChange={(e) => onChange({ expiresOn: e.target.value })} /> : null}
      {slot.namesAfroTransact ? (
        <Checkbox label="This policy names AfroTransact as additional insured" checked={draft.names} onChange={(e) => onChange({ names: e.target.checked })} />
      ) : null}
    </fieldset>
  );
}
