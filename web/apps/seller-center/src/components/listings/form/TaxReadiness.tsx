'use client';

import { type ProductTaxReadiness, type ProductTypeSchema, sellerKeys } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';

import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

/** "Tax readiness" of a saved product, per version, in plain words: what is still needed before it can go live, with the proof
    upload for exemption questions. Never shows a tax code. */
export function TaxReadiness({ productId, type }: { productId: string; type: ProductTypeSchema | undefined }) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const key = [...sellerKeys.product(orgId, productId), 'tax-readiness'] as const;
  const readiness = useQuery({ queryKey: key, queryFn: ({ signal }) => api.getTaxReadiness(productId, signal) });
  const upload = useMutation({
    mutationFn: (v: { atin: string; factId: string; file: File }) => api.uploadTaxEvidence(v.atin, v.factId, v.file),
    onSuccess: () => void qc.invalidateQueries({ queryKey: key })
  });
  const input = useRef<{ atin: string; factId: string } | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const r: ProductTaxReadiness | undefined = readiness.data;
  if (!r) return null;
  const ready = r.status === 'READY';
  const fileFacts = new Set((type?.attributes ?? []).filter((a) => a.kind === 'FILE').map((a) => a.key));
  return (
    <section aria-label={t('sc.pf.tax.readiness')} data-testid="tax-readiness" className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
      <h3 className="m-0 flex items-center gap-2 text-sm font-semibold">
        {ready ? <CheckCircle size={18} weight="fill" aria-hidden className="text-success" /> : <WarningCircle size={18} weight="fill" aria-hidden className="text-warning" />}
        {t('sc.pf.tax.readiness')}
      </h3>
      {r.categoryStatus && r.categoryStatus !== 'ACTIVE' ? <p className="m-0 text-xs text-muted">{t(`sc.pf.categoryGate.${r.categoryStatus}` as MessageKey)}</p> : null}
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {r.variants.map((v) => (
          <li key={v.atin} className="flex flex-col gap-1 text-sm" data-testid={`tax-variant-${v.sellerSku}`}>
            <span><strong>{v.sellerSku}</strong> · {t(`sc.pf.tax.status.${v.status}` as MessageKey)}</span>
            {v.missingTaxFacts.map((m) => (
              <span key={m.factId} className="flex flex-wrap items-center gap-2 text-xs text-muted">
                {m.message}
                {fileFacts.has(m.factId) ? (
                  <Button size="sm" variant="neutral" loading={upload.isPending} onClick={() => { input.current = { atin: v.atin, factId: m.factId }; picker.current?.click(); }}>{t('sc.pf.tax.upload')}</Button>
                ) : null}
              </span>
            ))}
          </li>
        ))}
      </ul>
      {ready ? <p className="m-0 text-xs text-muted">{t('sc.pf.tax.allReady')}</p> : null}
      <input ref={picker} type="file" hidden accept="application/pdf,image/jpeg,image/png" aria-label={t('sc.pf.tax.proofHelp')}
        onChange={(e) => { const f = e.target.files?.[0]; const target = input.current; e.target.value = ''; if (f && target) upload.mutate({ ...target, file: f }); }} />
      {upload.isError ? <p role="alert" className="m-0 text-xs text-danger">{problemMessage(upload.error)}</p> : null}
    </section>
  );
}
