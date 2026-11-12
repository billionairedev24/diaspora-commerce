import { type ProductDetail, type ProviderPage, type ServiceDetail } from '@afrotransact/api-client';
import { type Metadata } from 'next';

import { type MessageKey, type Translator } from '@/i18n/t';

import { absoluteUrl, canonicalAtin, categoryPath, productPath } from './canonical';
import { realPhoto, realPhotoUrls } from './eligibility';
import { type Availability, breadcrumbJsonLd, type JsonLdObject, organizationJsonLd, productJsonLd, serviceJsonLd, websiteJsonLd } from './jsonld';
import { clip, pageMetadata, SITE_NAME } from './metadata';
import { type IndexingPolicy } from './policy';

/* Page-level SEO from real catalog data: the unique title, description, canonical, Open Graph / Twitter card and JSON-LD of
   each template. Pure functions (translator and policy are passed in), unit tested in test/seo-pages.test.ts.

   Out-of-stock decision (docs/engineering/seo.md): a product that exists but cannot be bought stays 200 and indexable with
   availability OutOfStock (so ranking and links survive a restock); it is not in the sitemap. A product the catalog no longer
   has is a 404 and drops out of the sitemap with the next generation. */

type T = Translator<MessageKey>;

export interface PageSeo {
  metadata: Metadata;
  jsonLd: (JsonLdObject | undefined)[];
}

const availabilityOf = (status: 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK'): Availability => (status === 'OUT_OF_STOCK' ? 'OutOfStock' : status === 'LOW_STOCK' ? 'LimitedAvailability' : 'InStock');

export function productSeo(detail: ProductDetail, policy: IndexingPolicy, t: T): PageSeo {
  const p = detail.product;
  const origin = policy.origin;
  const canonical = productPath(canonicalAtin(p.atin, p.variants));
  const photos = realPhotoUrls(p.images.map((i) => i.url), origin);
  const mainAlt = p.images.find((i) => realPhoto(i.url))?.alt.trim() || p.title;
  const highlights = p.bullets.map((b) => b.trim()).filter(Boolean).join('. ');
  const buyBox = detail.buyBox;
  const base = p.brand.trim() ? t('seo.product.desc', { title: p.title, brand: p.brand, community: p.community, highlights }) : t('seo.product.descNoBrand', { title: p.title, community: p.community, highlights });
  const description = buyBox ? base : `${base} ${t('seo.product.outOfStock')}`;
  const title = p.brand.trim() && !p.title.toLowerCase().includes(p.brand.toLowerCase()) ? `${p.title} · ${p.brand}` : p.title;

  // The offer: the buy box when there is one; when nothing can be bought, the last known price of another offer with OutOfStock.
  const fallback = detail.otherOffers[0];
  const offer = buyBox
    ? { priceMinor: buyBox.priceMinor, currency: buyBox.currency, availability: availabilityOf(buyBox.stockStatus), sellerName: buyBox.seller.name }
    : fallback
      ? { priceMinor: fallback.priceMinor, currency: fallback.currency, availability: 'OutOfStock' as const, sellerName: fallback.seller.name }
      : undefined;
  // `condition` (new / used) is not in the catalog's product detail, so itemCondition is not emitted (gap: docs/engineering/seo.md).
  const url = absoluteUrl(origin, canonical);
  const crumbs = [
    { name: t('seo.breadcrumb.home'), url: absoluteUrl(origin, '/') },
    ...(p.department.trim() ? [{ name: p.department, url: absoluteUrl(origin, categoryPath('dept', p.department)) }] : []),
    { name: p.title, url }
  ];
  return {
    metadata: pageMetadata({ title, description, canonicalPath: canonical, index: true, ...(photos[0] ? { imageUrl: photos[0], imageAlt: mainAlt } : {}) }, policy),
    jsonLd: [
      productJsonLd({
        url,
        atin: p.atin,
        name: p.title,
        brand: p.brand,
        description: highlights || undefined,
        images: photos,
        ...(offer ? { offer } : {}),
        rating: { value: p.rating, count: p.reviewCount }
      }),
      breadcrumbJsonLd(crumbs)
    ]
  };
}

export function categorySeo(input: { dimension: 'dept' | 'community'; value: string; page: number; total: number; firstImage?: string | null }, policy: IndexingPolicy, t: T): PageSeo {
  const path = categoryPath(input.dimension, input.value, input.page);
  const name = input.page > 1 ? t('seo.category.page', { name: input.value, page: input.page }) : input.value;
  const description = input.total > 0 ? t('seo.category.desc', { name: input.value, count: input.total }) : t('seo.category.descNone', { name: input.value });
  const photos = realPhotoUrls([input.firstImage], policy.origin);
  return {
    // An empty category is noindex (thin page); a populated one is indexed, each page of it with its own canonical.
    metadata: pageMetadata({ title: name, description, canonicalPath: path, index: input.total > 0, ...(photos[0] ? { imageUrl: photos[0] } : {}) }, policy),
    jsonLd: [breadcrumbJsonLd([{ name: t('seo.breadcrumb.home'), url: absoluteUrl(policy.origin, '/') }, { name: input.value, url: absoluteUrl(policy.origin, categoryPath(input.dimension, input.value)) }])]
  };
}

export function serviceSeo(s: ServiceDetail, policy: IndexingPolicy, t: T): PageSeo {
  const path = `/services/${encodeURIComponent(s.id)}`;
  const url = absoluteUrl(policy.origin, path);
  const description = t('seo.service.desc', { name: s.name, provider: s.providerName, category: s.category, summary: clip(s.description, 90) });
  return {
    metadata: pageMetadata({ title: `${s.name} · ${s.providerName}`, description, canonicalPath: path, index: true }, policy),
    jsonLd: [
      serviceJsonLd({ url, name: s.name, description: s.description, category: s.category, providerName: s.providerName, providerUrl: absoluteUrl(policy.origin, `/services/providers/${encodeURIComponent(s.orgId)}`), priceMinor: s.priceMinor, currency: s.currency }),
      breadcrumbJsonLd([{ name: t('seo.breadcrumb.home'), url: absoluteUrl(policy.origin, '/') }, { name: t('title.services'), url: absoluteUrl(policy.origin, '/services') }, { name: s.name, url }])
    ]
  };
}

export function providerSeo(page: ProviderPage, policy: IndexingPolicy, t: T): PageSeo {
  const path = `/services/providers/${encodeURIComponent(page.orgId)}`;
  const names = page.services.map((s) => s.name).slice(0, 4).join(', ');
  return {
    metadata: pageMetadata({ title: t('seo.provider.title', { provider: page.displayName }), description: t('seo.provider.desc', { provider: page.displayName, services: names }), canonicalPath: path, index: page.services.length > 0 }, policy),
    jsonLd: page.services.map((s) => serviceJsonLd({ url: absoluteUrl(policy.origin, `/services/${encodeURIComponent(s.id)}`), name: s.name, description: s.description, category: s.category, providerName: page.displayName, providerUrl: absoluteUrl(policy.origin, path), priceMinor: s.priceMinor, currency: s.currency }))
  };
}

/** Organization + WebSite (with the SearchAction) for the home page. Undefined without a canonical origin (local runs). */
export function homeJsonLd(origin: string | undefined): (JsonLdObject | undefined)[] {
  if (!origin) return [];
  return [organizationJsonLd({ origin, name: SITE_NAME, logoUrl: `${origin}/icons/icon-512.png` }), websiteJsonLd({ origin, name: SITE_NAME })];
}
