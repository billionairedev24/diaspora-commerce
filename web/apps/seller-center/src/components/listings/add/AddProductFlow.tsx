'use client';

import { type CatalogMatch } from '@afrotransact/api-client';
import { TextLinkButton } from '@afrotransact/ui';
import { useRouter, useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { type ProductFormStart } from '../form-model';
import { ProductForm } from '../ProductForm';
import { OfferOnlyForm } from './OfferOnlyForm';
import { EMPTY_SEARCH, type SearchMemo,SearchStep } from './SearchStep';

type Step = { kind: 'search' } | { kind: 'offer'; match: CatalogMatch; atin: string } | { kind: 'create'; start: ProductFormStart };

/**
 * "Add a product" starts with one search box, "What are you selling?" (S382). A page that exists is sold with the price-and-stock form (no
 * photo, title or attribute field); "Not here, add it as new" continues into the full product form with the name and the category chip
 * filled in and photos required. The barcode is an optional exact shortcut inside the search step. A saved draft (`?draft=`) skips the step.
 */
export function AddProductFlow() {
  const t = useT();
  const router = useRouter();
  const param = useSearchParams().get('draft');
  const [step, setStep] = useState<Step>({ kind: 'search' });
  // The seller can always go back to the search, even from a draft of a new product that autosave has already given an address: the draft
  // stays in Drafts, and the search is found as it was left (what was typed and the category chosen).
  const [left, setLeft] = useState(false);
  const [memo, setMemo] = useState<SearchMemo>(EMPTY_SEARCH);
  const draft = param && !left ? param : null;
  const search = () => {
    setLeft(true);
    setStep({ kind: 'search' });
    if (param) router.replace('/listings/new', { scroll: false });
  };
  if (!draft && step.kind === 'search') {
    return <SearchStep memo={memo} onMemo={setMemo} draftKept={left && Boolean(param)} onSell={(match, atin) => setStep({ kind: 'offer', match, atin })} onCreate={(start) => setStep({ kind: 'create', start })} />;
  }
  if (!draft && step.kind === 'offer') {
    return <OfferOnlyForm match={step.match} atin={step.atin} onBack={search} onAnother={search} />;
  }
  // The same two slots whether the form was reached from the step or from a draft: autosave replaces the URL with ?draft= and must not remount the form.
  return (
    <>
      <div><TextLinkButton onClick={search}>{t('sc.match.back')}</TextLinkButton></div>
      <ProductForm start={step.kind === 'create' ? step.start : undefined} />
    </>
  );
}
