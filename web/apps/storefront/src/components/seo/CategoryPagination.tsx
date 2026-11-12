import Link from 'next/link';

import { getT } from '@/i18n/server';
import { categoryPath } from '@/lib/seo/canonical';

import styles from './CategoryPagination.module.css';

/** Crawlable pagination of a category page: plain links to the previous and next page (rel=next/prev are not needed), in the
    first HTML response. The shopper's own scrolling keeps loading more results; these links are for crawlers and keyboard users. */
export async function CategoryPagination({ dimension, value, page, pageCount }: { dimension: 'dept' | 'community'; value: string; page: number; pageCount: number }) {
  if (pageCount <= 1) return null;
  const t = await getT();
  return (
    <nav className={styles.nav} aria-label={t('seo.pagination.label')}>
      {page > 1 ? (
        <Link href={categoryPath(dimension, value, page - 1)} className={styles.link}>
          {t('seo.pagination.prev')}
        </Link>
      ) : null}
      <span className={styles.status}>{t('seo.pagination.status', { page, count: pageCount })}</span>
      {page < pageCount ? (
        <Link href={categoryPath(dimension, value, page + 1)} className={styles.link}>
          {t('seo.pagination.next')}
        </Link>
      ) : null}
    </nav>
  );
}
