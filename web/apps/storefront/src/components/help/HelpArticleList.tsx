'use client';

import { helpHref } from '@afrotransact/api-client';
import { buttonClassName, ChipGroup } from '@afrotransact/ui';
import { useInfiniteQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { helpQueries } from '@/lib/api/queries';
import { HELP_PAGE_SIZE, helpTopicOf } from '@/lib/help';

import styles from './Help.module.css';

/** "Browse help topics" under the hub: topic chips and every article, the next page loading as the shopper scrolls. */
export function HelpArticleList() {
  const t = useT();
  const api = browserApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const topic = helpTopicOf(params.get('topic'));
  const list = useInfiniteQuery(helpQueries.infiniteArticles(api, { topic, limit: HELP_PAGE_SIZE }));
  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = list;
  const items = useMemo(() => list.data?.pages.flatMap((p) => p.items) ?? [], [list.data]);
  const topics = list.data?.pages[0]?.topics ?? [];
  const sentinel = useRef<HTMLDivElement>(null);
  const loadedPages = list.data?.pages.length ?? 0;

  // Re-observed after every page: the sentinel may still be on screen when a fast page arrives, and an observer only
  // reports changes, so a fresh one is needed to load the next page.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasNextPage || isFetchNextPageError || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !isFetchingNextPage) void fetchNextPage();
    }, { rootMargin: '600px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage, loadedPages]);

  const choose = (value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value === 'ALL') next.delete('topic');
    else next.set('topic', value);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  return (
    <section className={styles.browse} aria-labelledby="help-browse">
      <h2 id="help-browse" className={styles.h2}>
        {t('help.browse')}
      </h2>
      {topics.length > 0 && (
        <ChipGroup
          label={t('help.topicsAria')}
          options={[{ value: 'ALL', label: t('help.allTopics') }, ...topics.map((x) => ({ value: x.key, label: x.title }))]}
          value={topic ?? 'ALL'}
          onChange={choose}
        />
      )}
      {list.isError && !list.data ? (
        <div className={styles.section} role="alert">
          <p className={styles.status}>{t('help.loadError')}</p>
          <button type="button" className={buttonClassName({ variant: 'neutral' })} onClick={() => void list.refetch()}>
            {t('help.retry')}
          </button>
        </div>
      ) : list.isPending ? (
        <p className={styles.status} role="status">
          {t('common.loading')}
        </p>
      ) : items.length === 0 ? (
        <p className={styles.status}>{t('help.empty')}</p>
      ) : (
        <ul className={styles.list} data-testid="help-articles">
          {items.map((a) => (
            <li key={a.slug}>
              <Link href={helpHref(a.slug)} className={styles.card}>
                <span className={styles.cardTopic}>{a.topicTitle}</span>
                <strong className={styles.cardTitle}>{a.title}</strong>
                <span className={styles.cardSummary}>{a.summary}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <div ref={sentinel} className={styles.sentinel} aria-hidden />
      {isFetchingNextPage && (
        <p className={styles.status} role="status">
          {t('help.loadingMore')}
        </p>
      )}
      {isFetchNextPageError && (
        <button type="button" className={buttonClassName({ variant: 'neutral' })} onClick={() => void fetchNextPage()}>
          {t('help.retry')}
        </button>
      )}
    </section>
  );
}
