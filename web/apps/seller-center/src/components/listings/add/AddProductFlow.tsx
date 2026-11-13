'use client';

import { type CatalogMatch } from '@afrotransact/api-client';
import { TextLinkButton } from '@afrotransact/ui';
import { useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { type ProductFormStart } from '../form-model';
import { ProductForm } from '../ProductForm';
import { OfferOnlyForm } from './OfferOnlyForm';
import { SearchStep } from './SearchStep';

type Step = { kind: 'search' } | { kind: 'offer'; match: CatalogMatch; atin: string } | { kind: 'create'; start: ProductFormStart };

/**
 * "Add a product" starts with one search box, "What are you selling?" (S382). A page that exists is sold with the price-and-stock form (no
 * photo, title or attribute field); "Not here, add it as new" continues into the full product form with the name and the category chip
 * filled in and photos required. The barcode is an optional exact shortcut inside the search step. A saved draft (`?draft=`) skips the step.
 */
export function AddProductFlow() {
  const t = useT();
  const draft = useSearchParams().get('draft');
  const [step, setStep] = useState<Step>({ kind: 'search' });
  if (!draft && step.kind === 'search') return <SearchStep onSell={(match, atin) => setStep({ kind: 'offer', match, atin })} onCreate={(start) => setStep({ kind: 'create', start })} />;
  if (!draft && step.kind === 'offer') {
    return <OfferOnlyForm match={step.match} atin={step.atin} onBack={() => setStep({ kind: 'search' })} onAnother={() => setStep({ kind: 'search' })} />;
  }
  // The same two slots whether the form was reached from the step or from a draft: autosave replaces the URL with ?draft= and must not remount the form.
  return (
    <>
      {!draft ? <div><TextLinkButton onClick={() => setStep({ kind: 'search' })}>{t('sc.match.back')}</TextLinkButton></div> : null}
      <ProductForm start={step.kind === 'create' ? step.start : undefined} />
    </>
  );
}
