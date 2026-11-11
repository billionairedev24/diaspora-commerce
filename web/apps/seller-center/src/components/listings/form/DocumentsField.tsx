'use client';

import { type ListingRuleField } from '@afrotransact/api-client';
import { Button, Checkbox, TextField } from '@afrotransact/ui';
import { UploadSimple } from '@phosphor-icons/react/ssr';
import { useRef } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { fieldId, type FormDocument } from '../form-model';

const DOC_TYPES = ['application/pdf', 'image/jpeg', 'image/png', 'image/webp'] as const;
const isDocType = (t: string): t is (typeof DOC_TYPES)[number] => (DOC_TYPES as readonly string[]).includes(t);
const MAX_DOC_BYTES = 15 * 1024 * 1024;
const KINDS = ['permit', 'brand_authorization', 'safety_certificate', 'liability_insurance', 'authenticity_invoice'] as const;
const asKind = (id: string): FormDocument['kind'] => (KINDS.find((k) => k === id) ?? 'permit');

interface Props {
  fields: ListingRuleField[];
  documents: Record<string, FormDocument>;
  reasons: Record<string, string>;
  onDocument: (id: string, doc: FormDocument | null) => void;
  onReason: (id: string, reason: string | undefined) => void;
}

/**
 * Upload slots for the documents the approval team may ask for. The form only collects the file (private upload, marked
 * "waiting for review"); the approval engine reviews it. A document never holds up the listing. Where the rules allow it,
 * "Not needed" with a reason stands in for the file.
 */
export function DocumentsField({ fields, documents, reasons, onDocument, onReason }: Props) {
  const t = useT();
  const api = sellerApi();
  const inputs = useRef<Record<string, HTMLInputElement | null>>({});

  const pick = async (field: ListingRuleField, file: File | undefined) => {
    if (!file) return;
    const base: FormDocument = { kind: asKind(field.id), url: null, fileName: file.name, contentType: file.type, bytes: file.size, expiresOn: documents[field.id]?.expiresOn ?? '', status: 'uploading' };
    if (!isDocType(file.type) || file.size > MAX_DOC_BYTES) {
      onDocument(field.id, { ...base, status: 'error' });
      return;
    }
    onDocument(field.id, base);
    try {
      const ticket = await api.requestOnboardingUpload({ fileName: file.name, contentType: file.type, size: file.size });
      const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
      if (!put.ok) throw new Error('upload failed');
      const asset = await api.completeOnboardingUpload(ticket.assetId);
      onDocument(field.id, { ...base, url: asset.url, status: 'ready' });
    } catch {
      onDocument(field.id, { ...base, status: 'error' });
    }
  };

  return (
    <div className="flex flex-col gap-5" data-testid="documents">
      {fields.map((f) => {
        const doc = documents[f.id];
        const label = t(`sc.pf.doc.${f.id}` as MessageKey);
        const needsExpiry = f.id === 'liability_insurance' || f.id === 'permit';
        const canSkip = f.status === 'required_or_exemption';
        const skipped = canSkip && reasons[f.id] !== undefined;
        return (
          <div key={f.id} id={fieldId.document(f.id)} tabIndex={-1} className="flex flex-col gap-1.5 outline-none" data-document={f.id} data-status={f.status}>
            <span className="text-sm font-semibold">{f.status === 'document' || f.status === 'optional' ? label : label}</span>
            {f.note ? <span className="text-xs text-muted">{f.note}</span> : null}
            <div className="flex flex-wrap items-center gap-3">
              <input ref={(el) => { inputs.current[f.id] = el; }} type="file" accept={DOC_TYPES.join(',')} className="sr-only" tabIndex={-1} aria-label={label} data-testid={`doc-input-${f.id}`}
                onChange={(e) => { void pick(f, e.target.files?.[0]); e.target.value = ''; }} />
              <Button size="sm" variant="neutral" disabled={skipped} onClick={() => inputs.current[f.id]?.click()}>
                <UploadSimple size={14} aria-hidden /> {doc ? t('sc.pf.doc.replace') : t('sc.pf.doc.upload')}
              </Button>
              {doc ? <span className="text-xs">{doc.fileName}</span> : null}
              {doc?.status === 'uploading' ? <span role="status" className="text-xs text-muted">{t('sc.pf.doc.uploading')}</span> : null}
              {doc?.status === 'ready' ? <span className="text-xs text-muted">{t('sc.pf.doc.pending')}</span> : null}
              {doc?.status === 'error' ? <span role="alert" className="text-xs text-danger">{t('sc.pf.doc.failed')}</span> : null}
              {doc ? <Button size="sm" variant="neutral" onClick={() => onDocument(f.id, null)}>{t('sc.pf.doc.remove')}</Button> : null}
            </div>
            {doc && needsExpiry ? (
              <TextField id={`${fieldId.document(f.id)}-expires`} type="date" label={t('sc.pf.doc.expires')} value={doc.expiresOn} onChange={(e) => onDocument(f.id, { ...doc, expiresOn: e.target.value })} />
            ) : null}
            {canSkip && !doc ? (
              <div className="flex flex-col gap-1">
                <Checkbox label={t('sc.pf.exempt.toggle')} checked={skipped} onChange={(e) => onReason(f.id, e.target.checked ? '' : undefined)} />
                {skipped ? <TextField id={fieldId.exemption(f.id)} label={t('sc.pf.exempt.reason')} value={reasons[f.id] ?? ''} maxLength={300} onChange={(e) => onReason(f.id, e.target.value)} /> : null}
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
