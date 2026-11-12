'use client';

import { type CatalogMatch } from '@afrotransact/api-client';
import { TextLinkButton } from '@afrotransact/ui';
import { useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { type ProductFormStart } from '../form-model';
import { ProductForm } from '../ProductForm';
import { BarcodeStep } from './BarcodeStep';
import { OfferOnlyForm } from './OfferOnlyForm';

type Step = { kind: 'barcode' } | { kind: 'offer'; match: CatalogMatch; atin: string } | { kind: 'create'; start: ProductFormStart };

/**
 * "Add a product" starts with the barcode (S382). A page that exists is sold with the offer-only form (no photo, title or attribute
 * field); no page, or no barcode, continues into the full product form with the barcode filled in and photos required. A saved draft
 * (`?draft=`) skips the step: it already chose.
 */
export function AddProductFlow() {
  const t = useT();
  const draft = useSearchParams().get('draft');
  const [step, setStep] = useState<Step>({ kind: 'barcode' });
  if (!draft && step.kind === 'barcode') return <BarcodeStep onSell={(match, atin) => setStep({ kind: 'offer', match, atin })} onCreate={(start) => setStep({ kind: 'create', start })} />;
  if (!draft && step.kind === 'offer') {
    return <OfferOnlyForm match={step.match} atin={step.atin} onBack={() => setStep({ kind: 'barcode' })} onAnother={() => setStep({ kind: 'barcode' })} />;
  }
  // The same two slots whether the form was reached from the step or from a draft: autosave replaces the URL with ?draft= and must not remount the form.
  return (
    <>
      {!draft ? <div><TextLinkButton onClick={() => setStep({ kind: 'barcode' })}>{t('sc.match.back')}</TextLinkButton></div> : null}
      <ProductForm start={step.kind === 'create' ? step.start : undefined} />
    </>
  );
}
