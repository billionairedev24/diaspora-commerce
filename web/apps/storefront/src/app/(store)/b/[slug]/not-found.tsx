import Link from 'next/link';

import { getT } from '@/i18n/server';

/** An unpublished, unapproved, suspended or unknown store: an honest page with a way out, not a broken or empty storefront. */
export default async function StoreNotFound() {
  const t = await getT();
  return (
    <main id="main" tabIndex={-1} className="store-gone" data-testid="store-not-found">
      <h1>{t('store.gone.title')}</h1>
      <p>{t('store.gone.body')}</p>
      <p className="store-gone-links">
        <Link href="/">{t('store.gone.home')}</Link>
        <Link href="/s">{t('store.gone.search')}</Link>
      </p>
    </main>
  );
}
