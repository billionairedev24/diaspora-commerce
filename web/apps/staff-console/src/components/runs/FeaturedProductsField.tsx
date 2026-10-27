'use client';

import { ADVERTORIAL_LIMITS, type FeaturedProduct, staffRunQueries } from '@afrotransact/api-client';
import { TextField } from '@afrotransact/ui';
import { Plus, X } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { useDebounced } from '@/lib/use-debounced';

import { useRunsApi } from './shared';

/**
 * The products an advertorial's card features (up to four): chosen by name from the products shoppers can find, never by
 * code or price. The card links each to its product page, which shows today's price.
 */
export function FeaturedProductsField({ value, onChange, error }: { value: FeaturedProduct[]; onChange: (next: FeaturedProduct[]) => void; error?: string | null }) {
  const t = useT();
  const api = useRunsApi();
  const [text, setText] = useState('');
  const settled = useDebounced(text.trim(), 300);
  const full = value.length >= ADVERTORIAL_LIMITS.featured;
  const search = useQuery({ ...staffRunQueries.featuredSearch(api, settled), enabled: settled.length >= 2 && !full });
  const chosen = new Set(value.map((p) => p.atin));
  const hits = (search.data ?? []).filter((p) => !chosen.has(p.atin));
  return (
    <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" data-testid="featured-products">
      <legend className="mb-1 p-0 text-sm font-semibold">{t('st.runs.adv.featured.title')}</legend>
      <p className="m-0 text-xs text-muted">{t('st.runs.adv.featured.help', { max: ADVERTORIAL_LIMITS.featured })}</p>
      {value.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.runs.adv.featured.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-wrap gap-2 p-0" aria-label={t('st.runs.adv.featured.title')}>
          {value.map((p) => (
            <li key={p.atin} className="inline-flex min-h-11 items-center gap-1 rounded-pill border border-border bg-surface-muted ps-3 text-sm sm:min-h-8">
              <span>{p.title}</span>
              <button
                type="button"
                aria-label={t('st.runs.adv.featured.remove', { name: p.title })}
                className="inline-flex size-11 cursor-pointer items-center justify-center rounded-pill border-0 bg-transparent text-text sm:size-8"
                onClick={() => onChange(value.filter((x) => x.atin !== p.atin))}
              >
                <X aria-hidden size={14} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {full ? (
        <p className="m-0 text-xs text-muted">{t('st.runs.adv.featured.max', { max: ADVERTORIAL_LIMITS.featured })}</p>
      ) : (
        <>
          <TextField label={t('st.runs.adv.featured.search')} help={t('st.runs.adv.featured.searchHelp')} value={text} autoComplete="off" onChange={(e) => setText(e.currentTarget.value)} />
          {settled.length >= 2 && search.isSuccess && hits.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.runs.adv.featured.noMatches')}</p> : null}
          {search.isError ? (
            <p role="alert" className="m-0 text-sm text-danger">
              {t('st.runs.adv.featured.searchFailed')}
            </p>
          ) : null}
          {hits.length > 0 ? (
            <ul className="m-0 flex list-none flex-col gap-1 p-0" aria-label={t('st.runs.adv.featured.results')} data-testid="featured-hits">
              {hits.map((p) => (
                <li key={p.atin}>
                  <button
                    type="button"
                    className="flex min-h-11 w-full cursor-pointer items-center justify-between gap-2 rounded-md border border-border bg-surface px-3 text-start text-sm sm:min-h-9"
                    onClick={() => {
                      onChange([...value, { atin: p.atin, title: p.title, ...(p.imageUrl ? { imageUrl: p.imageUrl } : {}) }]);
                      setText('');
                    }}
                  >
                    <span className="min-w-0">
                      <span className="font-semibold">{p.title}</span>
                      {p.sellerName ? <span className="text-muted"> {t('st.runs.adv.featured.seller', { seller: p.sellerName })}</span> : null}
                    </span>
                    <span className="inline-flex shrink-0 items-center gap-1 font-semibold">
                      <Plus aria-hidden size={14} />
                      {t('st.runs.adv.featured.add')}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </>
      )}
      {error ? (
        <p role="alert" className="m-0 text-xs text-danger">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}
