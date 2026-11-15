import { type BusinessPage, isApiError, type Storefront } from '@afrotransact/api-client';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { cache } from 'react';

import { BusinessPageView } from '@/components/business/BusinessPageView';
import { JsonLd } from '@/components/seo/JsonLd';
import { ShopChrome } from '@/components/shell/ShopChrome';
import { StorefrontClient } from '@/components/store/StorefrontClient';
import { getT } from '@/i18n/server';
import { serverApi } from '@/lib/api/server';
import { publicWebUrl } from '@/lib/config';
import { realPhotoUrls } from '@/lib/seo/eligibility';
import { storefrontJsonLd } from '@/lib/seo/jsonld';
import { pageMetadata } from '@/lib/seo/metadata';
import { getIndexingPolicy } from '@/lib/seo/server';
import { isDefaultQuery, storefrontQueryOf } from '@/lib/store/query';

type Props = { params: Promise<{ slug: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> };

type Loaded = { kind: 'store'; store: Storefront } | { kind: 'provider'; page: BusinessPage } | null;

/** A seller's storefront; a provider's plain page when the address is a provider's; null when there is nothing published (a 404). */
const load = cache(async (slug: string): Promise<Loaded> => {
  const api = await serverApi();
  try {
    return { kind: 'store', store: await api.getStorefront(slug) };
  } catch (e) {
    if (!isApiError(e) || e.status !== 404) throw e;
  }
  try {
    const page = await api.getBusinessPage(slug);
    // A seller whose page is unpublished (or not approved) has no storefront: a proper 404, never the old plain page.
    return page.kind === 'PROVIDER' ? { kind: 'provider', page } : null;
  } catch (e) {
    if (isApiError(e) && e.status === 404) return null;
    throw e;
  }
});

export async function generateMetadata({ params, searchParams }: Props): Promise<Metadata> {
  const slug = (await params).slug;
  const [loaded, policy, t] = await Promise.all([load(slug), getIndexingPolicy(), getT()]);
  if (!loaded) return { title: t('seo.notFound.title'), robots: { index: false, follow: false } };
  if (loaded.kind === 'provider') {
    const logo = realPhotoUrls([loaded.page.logoUrl], undefined)[0];
    return pageMetadata({ title: loaded.page.name, ...(loaded.page.description ? { description: loaded.page.description } : {}), canonicalPath: `/b/${loaded.page.slug}`, index: loaded.page.indexable, ...(logo ? { imageUrl: logo } : {}) }, policy);
  }
  const { store } = loaded;
  const filtered = !isDefaultQuery(storefrontQueryOf(await searchParams));
  const image = realPhotoUrls([store.config.hero.bannerUrl, store.logoUrl], undefined)[0];
  const description = store.config.hero.tagline || store.description || undefined;
  // Indexable only when approved + published + it has live products, and never a filtered or sorted view (canonical is the plain page).
  return pageMetadata({ title: store.name, ...(description ? { description } : {}), canonicalPath: `/b/${store.slug}`, index: store.indexable && !filtered, ...(image ? { imageUrl: image } : {}) }, policy);
}

/** A seller's branded storefront: its own header, hero, products and footer; nothing of the marketplace chrome. */
export default async function StorefrontPage({ params, searchParams }: Props) {
  const slug = (await params).slug;
  const loaded = await load(slug);
  if (!loaded) notFound();
  if (loaded.kind === 'provider') {
    return (
      <ShopChrome>
        <BusinessPageView page={loaded.page} />
      </ShopChrome>
    );
  }
  const query = storefrontQueryOf(await searchParams);
  let store = loaded.store;
  if (!isDefaultQuery(query)) {
    // A shared filtered link: the first HTML already has that grid.
    try {
      const products = await (await serverApi()).getStorefrontProducts(store.slug, { q: query.q, category: query.category, price: query.price, sort: query.sort, onSale: query.onSale });
      store = { ...store, products };
    } catch {
      /* keep the unfiltered grid; the client refetches */
    }
  }
  const policy = await getIndexingPolicy();
  const origin = publicWebUrl()?.origin;
  return (
    <>
      {origin && policy.indexable && store.indexable ? <JsonLd data={[storefrontJsonLd({ origin, store: { ...store, kind: store.kind } })]} /> : null}
      <StorefrontClient store={store} initialQuery={query} />
    </>
  );
}
