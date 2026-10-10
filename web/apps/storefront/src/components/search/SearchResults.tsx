'use client';

import { type FacetBucket, parseSearchUrl, PRICE_BUCKETS, type ProductSummary, type SearchQuery, type SearchSort } from '@afrotransact/api-client';
import { Button, Skeleton, Stars } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { type ReactNode, useEffect, useMemo, useState } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { type MessageKey, t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchHref, searchQueries, siteQueries } from '@/lib/api/queries';

import styles from './Search.module.css';

const SORTS: SearchSort[] = ['relevance', 'price_asc', 'price_desc', 'rating', 'fastest'];

const countOf = (buckets: FacetBucket[] | undefined, value: string) => buckets?.find((b) => b.value === value)?.count;

/** Search results: URL-synced filters and sort, facet counts from the API, pagination with keepPreviousData. */
export function SearchResults() {
  const params = useSearchParams();
  const router = useRouter();
  const api = browserApi();
  const urlQuery = useMemo(() => parseSearchUrl(params), [params]);
  // Filters reflect a click immediately; the URL (source of truth) catches up after navigation.
  const [optimistic, setOptimistic] = useState<SearchQuery | null>(null);
  useEffect(() => setOptimistic(null), [urlQuery]);
  const query = optimistic ?? urlQuery;
  const { data, isFetching, isPlaceholderData, isError, refetch, isPending } = useQuery(searchQueries.results(api, urlQuery));
  const { data: location } = useQuery(siteQueries.deliveryLocation(api));
  const [filtersOpen, setFiltersOpen] = useState(false);
  // Result clicks feed search analytics and charge sponsored products (S240); fire and forget.
  const reportClick = (p: ProductSummary, position: number) => {
    if (!data?.searchId) return;
    api.recordSearchClick({ searchId: data.searchId, q: query.q || undefined, atin: p.atin, position, adToken: p.sponsor?.adToken }).catch(() => undefined);
  };

  const href = (patch: Partial<SearchQuery>) => searchHref({ ...query, page: 0, ...patch });
  const go = (patch: Partial<SearchQuery>) => {
    setOptimistic({ ...query, page: 0, ...patch });
    router.push(href(patch), { scroll: false });
  };

  const f = data?.facets;
  const total = data?.total ?? 0;
  const from = total ? (data?.page ?? 0) * query.size + 1 : 0;
  const to = Math.min(total, from + (data?.hits.length ?? 0) - 1);
  const resultsFor = query.q ? `"${query.q}"` : query.department ?? query.community ?? t('results.allProducts');

  const radio = (name: string, value: string | null, current: string | null, labelText: string, count: number | undefined, onPick: () => void) => (
    <label className={styles.check} key={`${name}-${value ?? 'any'}`}>
      <input type="radio" name={name} checked={current === value} onChange={onPick} />
      <span>
        {labelText} {count !== undefined && value !== null && <span className={styles.count}>({count})</span>}
      </span>
    </label>
  );

  const facetLink = (key: string, labelNode: ReactNode, to: string, current: boolean, count?: number) => (
    <li key={key}>
      <Link href={to} scroll={false} className={`${styles.facet} ${count === 0 && !current ? styles.facetDisabled : ''}`} aria-current={current ? 'true' : undefined}>
        {labelNode} {count !== undefined && <span className={styles.count}>({count})</span>}
      </Link>
    </li>
  );

  return (
    <div>
      <div className={`${styles.topbar} full-bleed`}>
        <p style={{ margin: 0 }} aria-live="polite">
          {total ? t('results.summary', { from, to, total }) : t('results.summaryEmpty')} <strong>{resultsFor}</strong>
        </p>
        <label className={styles.sortLabel}>
          {t('results.sortBy')}
          <select className={styles.sort} value={query.sort} onChange={(e) => go({ sort: e.target.value as SearchSort })}>
            {SORTS.map((s) => (
              <option key={s} value={s}>
                {t(`results.sort.${s}` as MessageKey)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className={styles.layout}>
        <div>
          <Button variant="neutral" size="sm" className={styles.filtersToggle} aria-expanded={filtersOpen} aria-controls="search-filters" onClick={() => setFiltersOpen((o) => !o)}>
            {t('results.filters')}
          </Button>
          <aside id="search-filters" className={styles.aside} data-open={filtersOpen ? 'true' : 'false'} aria-label={t('results.filters')}>
            <fieldset className={styles.group}>
              <legend className={styles.legend}>{t('results.delivery')}</legend>
              <label className={styles.check}>
                <input type="checkbox" checked={query.maxDays === 1} onChange={(e) => go({ maxDays: e.target.checked ? 1 : null })} />
                <span>
                  {f?.delivery[0]?.label ?? 'Get it by tomorrow'} <span className={styles.count}>({countOf(f?.delivery, '1') ?? 0})</span>
                </span>
              </label>
            </fieldset>
            <fieldset className={styles.group}>
              <legend className={styles.legend}>{t('results.fulfilment')}</legend>
              {radio('ff', null, query.fulfilment, t('results.any'), undefined, () => go({ fulfilment: null }))}
              {(f?.fulfilment ?? []).map((b) => radio('ff', b.value, query.fulfilment, b.label, b.count, () => go({ fulfilment: b.value as SearchQuery['fulfilment'] })))}
            </fieldset>
            <fieldset className={styles.group}>
              <legend className={styles.legend}>{t('results.soldBy')}</legend>
              {radio('sold', null, query.sellerKind, t('results.any'), undefined, () => go({ sellerKind: null }))}
              {(f?.sellerKind ?? []).map((b) => radio('sold', b.value, query.sellerKind, b.label, b.count, () => go({ sellerKind: b.value as SearchQuery['sellerKind'] })))}
            </fieldset>
            <nav className={styles.group} aria-label={t('results.reviews')}>
              <strong>{t('results.reviews')}</strong>
              <ul className={styles.facetList}>
                {(f?.rating ?? []).map((b) =>
                  facetLink(
                    `r${b.value}`,
                    <>
                      <Stars rating={Number(b.value)} size={14} /> <span>{t('results.andUp')}</span>
                    </>,
                    href({ minRating: query.minRating === Number(b.value) ? null : Number(b.value) }),
                    query.minRating === Number(b.value),
                    b.count
                  )
                )}
              </ul>
            </nav>
            <nav className={styles.group} aria-label={t('results.department')}>
              <strong>{t('results.department')}</strong>
              <ul className={styles.facetList}>
                {facetLink('d-all', t('results.allDepartments'), href({ department: null }), !query.department)}
                {(f?.department ?? []).map((b) => facetLink(`d${b.value}`, b.label, href({ department: b.value }), query.department === b.value, b.count))}
              </ul>
            </nav>
            <nav className={styles.group} aria-label={t('results.community')}>
              <strong>{t('results.community')}</strong>
              <ul className={styles.facetList}>
                {facetLink('c-all', t('results.allCommunities'), href({ community: null }), !query.community)}
                {(f?.community ?? []).map((b) => facetLink(`c${b.value}`, b.label, href({ community: b.value }), query.community === b.value, b.count))}
              </ul>
            </nav>
            <nav className={styles.group} aria-label={t('results.price')}>
              <strong>{t('results.price')}</strong>
              <ul className={styles.facetList}>
                {facetLink('p-any', t('results.anyPrice'), href({ price: null }), !query.price)}
                {PRICE_BUCKETS.map((b) => facetLink(`p${b.id}`, b.label, href({ price: b.id }), query.price === b.id, countOf(f?.price, b.id)))}
              </ul>
            </nav>
            <Link href={searchHref({ q: query.q, sort: query.sort })} scroll={false} className={styles.clear}>
              {t('results.clearAll')}
            </Link>
          </aside>
        </div>
        <div className={styles.main}>
          <h1 className={styles.title}>{t('results.title')}</h1>
          <p className={styles.note}>{t('results.note', { location: location?.label ?? data?.deliverToLabel ?? '' })}</p>
          {isPending && !data ? (
            <ul className={styles.grid} aria-busy="true" aria-label={t('results.loading')}>
              {Array.from({ length: 8 }, (_, i) => (
                <li key={i} style={{ display: 'grid', gap: 8 }}>
                  <Skeleton height={220} />
                  <Skeleton width="85%" />
                  <Skeleton width="50%" />
                  <Skeleton height={32} radius="999px" />
                </li>
              ))}
            </ul>
          ) : isError ? (
            <div className={styles.empty} role="alert">
              <strong>{t('error.title')}</strong> <button type="button" className="link-button" onClick={() => void refetch()}>{t('error.retry')}</button>
            </div>
          ) : total === 0 ? (
            <div className={styles.empty}>
              <strong>{t('results.empty')}</strong> {t('results.emptyHint')}
            </div>
          ) : (
            <ul className={`${styles.grid} ${isPlaceholderData && isFetching ? styles.stale : ''}`} aria-busy={isFetching} data-testid="results-grid">
              {data?.hits.map((p, i) => (
                <li
                  key={p.atin}
                  style={{ display: 'grid' }}
                  onClickCapture={(e) => {
                    if ((e.target as Element).closest('a')) reportClick(p, data.page * query.size + i + 1);
                  }}
                >
                  <CartAwareProductCard product={p} />
                </li>
              ))}
            </ul>
          )}
          {data && data.pageCount > 1 && (
            <nav className={styles.pager} aria-label={t('results.pagination')}>
              <Link href={href({ page: Math.max(0, data.page - 1) })} className={`${styles.pageLink} ${data.page === 0 ? styles.pageDisabled : ''}`} aria-disabled={data.page === 0} scroll>
                {t('results.previous')}
              </Link>
              {Array.from({ length: data.pageCount }, (_, i) => (
                <Link key={i} href={href({ page: i })} className={styles.pageLink} aria-current={i === data.page ? 'page' : undefined} aria-label={t('results.page', { n: i + 1 })}>
                  {i + 1}
                </Link>
              ))}
              <Link href={href({ page: Math.min(data.pageCount - 1, data.page + 1) })} className={`${styles.pageLink} ${data.page >= data.pageCount - 1 ? styles.pageDisabled : ''}`} aria-disabled={data.page >= data.pageCount - 1}>
                {t('results.next')}
              </Link>
            </nav>
          )}
        </div>
      </div>
    </div>
  );
}
