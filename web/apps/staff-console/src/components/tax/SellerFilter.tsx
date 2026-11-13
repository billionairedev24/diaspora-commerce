'use client';

import { staffTaxQueries } from '@afrotransact/api-client';
import { TextField } from '@afrotransact/ui';
import { X } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useDebounced } from '@/lib/use-debounced';

/**
 * The seller filter of the tax review queue: type part of a seller's name, choose one, and its name shows with a way to
 * clear it. The filter works on the seller's id; staff only ever read the name.
 */
export function SellerFilter({ id, name, onChange }: { id: string; name: string; onChange: (seller: { id: string; name: string } | null) => void }) {
  const t = useT();
  const [text, setText] = useState('');
  const settled = useDebounced(text.trim(), 300);
  const typed = settled.length === 1 ? '' : settled;
  const search = useQuery({ ...staffTaxQueries.reviewSellers(staffApi().tax, typed), placeholderData: keepPreviousData, enabled: !id && text.length > 0 });
  const hits = text.length > 0 ? (search.data?.items.slice(0, 6) ?? []) : [];
  if (id) {
    return (
      <div className="flex min-w-48 flex-col gap-1" data-testid="review-seller-filter">
        <span className="text-sm font-semibold">{t('st.tax.review.filter.seller')}</span>
        <div className="flex min-h-10 items-center justify-between gap-2 border-b border-border-strong text-sm">
          <strong className="min-w-0 break-words font-semibold">{name}</strong>
          <button
            type="button"
            aria-label={t('st.tax.review.filter.sellerClear')}
            className="inline-flex size-10 shrink-0 cursor-pointer items-center justify-center border-0 bg-transparent text-text"
            onClick={() => {
              setText('');
              onChange(null);
            }}
          >
            <X aria-hidden size={16} />
          </button>
        </div>
      </div>
    );
  }
  return (
    <div className="flex min-w-48 flex-col gap-1" data-testid="review-seller-filter">
      <TextField
        label={t('st.tax.review.filter.seller')}
        placeholder={t('st.tax.review.filter.sellerPlaceholder')}
        value={text}
        autoComplete="off"
        onChange={(e) => setText(e.currentTarget.value)}
      />
      {text.length > 0 && search.isSuccess && hits.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.tax.review.filter.sellerNone')}</p> : null}
      {hits.length > 0 ? (
        <ul className="m-0 flex list-none flex-col p-0" aria-label={t('st.tax.review.filter.sellerHits')}>
          {hits.map((h) => (
            <li key={h.id}>
              <button
                type="button"
                className="flex min-h-9 w-full cursor-pointer items-center border-0 border-b border-border bg-transparent px-1 text-start text-sm font-semibold text-text hover:bg-surface-hover"
                onClick={() => {
                  setText('');
                  onChange(h);
                }}
              >
                {h.name}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
