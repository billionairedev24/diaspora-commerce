'use client';

import { type CatalogMatch, matchingQueries, sellerQueries } from '@afrotransact/api-client';
import { Button, TextField, TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { type CategoryLeaf, leavesOf, type ProductFormStart } from '../form-model';
import { BarcodeShortcut } from './BarcodeShortcut';
import { CandidateCard } from './CandidateCard';
import { CategoryChip } from './CategoryChip';
import { ChangeRequestsList } from './ChangeRequestsList';
import { SEARCH_DEBOUNCE_MS, searchTextOf } from './match-model';

/** What the seller had typed and chosen, kept by the flow so going back from a later step finds the search as it was. */
export interface SearchMemo {
  text: string;
  category: CategoryLeaf | null;
}
export const EMPTY_SEARCH: SearchMemo = { text: '', category: null };

interface Props {
  /** The seller confirmed a page: the price-and-stock form. */
  onSell: (match: CatalogMatch, atin: string) => void;
  /** "Not here, add it as new" (or no page for a barcode): the full form, started with what was typed. */
  onCreate: (start: ProductFormStart) => void;
  /** The search as the seller left it, and how to keep it. */
  memo?: SearchMemo;
  onMemo?: (memo: SearchMemo) => void;
  /** Set when the seller came back from a draft of a new product: it is kept, and the seller is told so. */
  draftKept?: boolean;
}

/**
 * Step 0 of "Add a product": first the choice, sell a product already on AfroTransact (the search, the default) or create a new one (straight
 * to the full form, no search first). Then one search box, "What are you selling?". The name (with the size and the brand inside it) is searched while the
 * seller types; the pages that look like it show with photo, brand, size and the number of sellers, and every one needs the seller's click.
 * The category is a suggested chip the seller may change, never a required first step. A seller with a barcode may use it as an exact
 * shortcut. Nothing here shows an id or another seller.
 */
export function SearchStep({ onSell, onCreate, memo = EMPTY_SEARCH, onMemo, draftKept = false }: Props) {
  const t = useT();
  const { orgId } = useSeller();
  const api = sellerApi();
  const [text, setText] = useState(memo.text);
  const [typed, setTyped] = useState(memo.text);
  const [chosen, setChosen] = useState<CategoryLeaf | null>(memo.category);
  const [barcode, setBarcode] = useState(false);
  useEffect(() => {
    const h = window.setTimeout(() => setTyped(text), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(h);
  }, [text]);
  useEffect(() => {
    onMemo?.({ text, category: chosen });
    // The memo only follows what the seller typed and chose; the callback is the flow's setter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, chosen]);
  const query = searchTextOf(typed);
  const categoryId = chosen?.id;
  const tree = useQuery(sellerQueries.categories(api, orgId));
  const q = useQuery(matchingQueries.candidates(api.matching, orgId, { query: query ?? '', ...(categoryId ? { categoryId } : {}) }, query !== null));
  const data = query !== null && !q.isError ? q.data : undefined;
  const leaves = tree.data ? leavesOf(tree.data) : [];
  const suggested = !chosen && data?.suggestedCategory ? data.suggestedCategory : null;
  const leaf: CategoryLeaf | null = chosen ?? (suggested ? (leaves.find((l) => l.id === suggested.id) ?? null) : null);
  const chip = chosen ? { path: chosen.path, suggested: false } : suggested ? { path: suggested.path, suggested: true } : null;
  const none = useMutation({ mutationFn: () => api.matching.decide({ choice: 'NONE_OF_THESE', query: query ?? text.trim(), ...(categoryId ? { categoryId } : {}) }) });
  const addNew = () => {
    const name = text.trim().replace(/\s+/g, ' ');
    // "None of these" is kept for staff to compare; it never holds the seller back, so a failure to record it does not either.
    if (query !== null && data) none.mutate();
    onCreate({ noGtin: true, title: name, ...(leaf ? { category: { id: leaf.id, path: leaf.path, productType: leaf.productType } } : {}) });
  };
  // "Create a new product" skips the search: the form starts with whatever was typed, the chip's category too.
  const createNew = () => {
    const name = text.trim().replace(/\s+/g, ' ');
    onCreate({ ...(name ? { title: name } : {}), ...(leaf ? { category: { id: leaf.id, path: leaf.path, productType: leaf.productType } } : {}) });
  };
  const waiting = query !== null && q.isFetching && !q.data;
  const searched = data !== undefined && !q.isFetching;

  return (
    <>
      <PageHeader title={t('sc.match.title')} description={t('sc.match.lead')} />
      <div className="flex max-w-3xl flex-col gap-6" data-tour="add-product-step">
        {draftKept ? <p className="m-0 text-sm" data-testid="draft-kept">{t('sc.match.draftKept')}</p> : null}
        <fieldset className="m-0 flex flex-col gap-3 border-0 p-0" data-testid="add-mode">
          <legend className="mb-2 text-sm font-semibold">{t('sc.match.mode.legend')}</legend>
          <div className="flex items-start gap-2 text-sm">
            <input type="radio" id="add-mode-existing" name="add-mode" value="existing" defaultChecked className="mt-1 shrink-0" />
            <label htmlFor="add-mode-existing" className="cursor-pointer">
              <span className="block font-semibold">{t('sc.match.mode.existing')}</span>
              <span className="block text-muted">{t('sc.match.mode.existingHelp')}</span>
            </label>
          </div>
          <div className="flex items-start gap-2 text-sm">
            <input type="radio" id="add-mode-new" name="add-mode" value="new" onChange={createNew} className="mt-1 shrink-0" />
            <label htmlFor="add-mode-new" className="cursor-pointer">
              <span className="block font-semibold">{t('sc.match.mode.new')}</span>
              <span className="block text-muted">{t('sc.match.mode.newHelp')}</span>
            </label>
          </div>
        </fieldset>
        <TextField
          id="match-search"
          data-tour="search-input"
          label={t('sc.match.search.label')}
          help={t('sc.match.search.help')}
          autoComplete="off"
          maxLength={200}
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        {text.trim() ? <CategoryChip tree={tree.data} value={chip} onPick={setChosen} /> : null}
        <div aria-live="polite" className="flex flex-col gap-4" data-testid="search-result">
          {text.trim() && query === null ? <p className="m-0 text-sm text-muted">{t('sc.match.search.short')}</p> : null}
          {waiting ? <Spinner label={t('sc.match.checking')} /> : null}
          {query !== null && q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
          {data?.searchUnavailable ? <p className="m-0 text-sm">{t('sc.match.search.unavailable')}</p> : null}
          {searched && !data.searchUnavailable && !data.candidates.length ? <p className="m-0 text-sm">{t('sc.match.search.none')}</p> : null}
          {data?.candidates.length ? (
            <section className="flex flex-col gap-4" aria-label={t('sc.match.search.results')}>
              <h2 className="m-0 text-lg font-semibold">{t('sc.match.search.results')}</h2>
              <ul className="m-0 flex list-none flex-col gap-4 p-0">
                {data.candidates.map((c) => <CandidateCard key={c.parentAtin} candidate={c} query={query ?? ''} categoryId={categoryId} onSell={onSell} onNew={addNew} />)}
              </ul>
            </section>
          ) : null}
          {query !== null && (searched || q.isError) ? (
            <div className="flex flex-col gap-2">
              <div><Button variant={data?.candidates.length ? 'neutral' : 'primary'} onClick={addNew}>{t('sc.match.new.button')}</Button></div>
              <p className="m-0 text-sm text-muted">{t('sc.match.new.help')}</p>
            </div>
          ) : null}
        </div>
        <div className="flex flex-col gap-3">
          <div><TextLinkButton aria-expanded={barcode} onClick={() => setBarcode((b) => !b)}>{barcode ? t('sc.match.byName') : t('sc.match.haveBarcode')}</TextLinkButton></div>
          {barcode ? <BarcodeShortcut onSell={onSell} onCreate={onCreate} /> : null}
        </div>
        <ChangeRequestsList onSell={onSell} />
      </div>
    </>
  );
}
