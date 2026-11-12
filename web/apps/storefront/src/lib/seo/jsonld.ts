/* schema.org JSON-LD builders. Rules (owner, docs/engineering/seo.md):
   - only facts the catalog really holds: no invented ratings, no "0 reviews", no price or condition we do not have;
   - NO tax claims, NO carrier names, NO statement that AfroTransact collects payment or delivers (structured data describes
     the item, its price and its availability, nothing about how it is fulfilled);
   - shippingDetails / hasMerchantReturnPolicy only from structured data (the catalog has free text only, so never today);
   - a builder returns undefined when the page could not make a valid entity (the caller then emits nothing). */

export type JsonLdValue = string | number | boolean | JsonLdObject | JsonLdValue[];
export interface JsonLdObject {
  [key: string]: JsonLdValue;
}

const CONTEXT = 'https://schema.org';

export type Availability = 'InStock' | 'OutOfStock' | 'LimitedAvailability';
export type ItemCondition = 'NewCondition' | 'UsedCondition' | 'RefurbishedCondition';

/** Decimal price string of a minor-unit amount ("1999" USD cents -> "19.99"; zero-decimal currencies keep no fraction). */
export function priceString(minor: number, currency: string): string {
  let digits = 2;
  try {
    digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    digits = 2;
  }
  return (minor / 10 ** digits).toFixed(digits);
}

/** Serialises JSON-LD for a <script type="application/ld+json">: "<" is escaped so no value can close the tag. */
export function jsonLdScript(value: JsonLdObject | undefined): string | undefined {
  return value ? JSON.stringify(value).replace(/</g, '\\u003c').replace(/[\u2028\u2029]/g, (c) => `\\u${c.charCodeAt(0).toString(16)}`) : undefined;
}

const nonEmpty = (v: string | undefined | null): v is string => typeof v === 'string' && v.trim() !== '';

export interface ProductOfferInput {
  priceMinor: number;
  currency: string;
  availability: Availability;
  sellerName?: string;
  /** Only with real data from the catalog; undefined = not stated. */
  condition?: ItemCondition;
}

export interface ProductLdInput {
  url: string;
  atin: string;
  name: string;
  brand?: string;
  description?: string;
  /** Absolute URLs of real photographs only (never icons, placeholders or emoji). */
  images: readonly string[];
  offer?: ProductOfferInput;
  rating?: { value: number; count: number };
}

/** Product + Offer (+ AggregateRating when real reviews exist). Undefined when there is neither an offer nor a rating to describe. */
export function productJsonLd(i: ProductLdInput): JsonLdObject | undefined {
  const rating = i.rating && i.rating.count > 0 && i.rating.value > 0 && i.rating.value <= 5 ? i.rating : undefined;
  if (!i.offer && !rating) return undefined;
  const ld: JsonLdObject = { '@context': CONTEXT, '@type': 'Product', '@id': `${i.url}#product`, url: i.url, name: i.name, sku: i.atin };
  if (nonEmpty(i.brand)) ld['brand'] = { '@type': 'Brand', name: i.brand };
  if (nonEmpty(i.description)) ld['description'] = i.description;
  if (i.images.length > 0) ld['image'] = [...i.images];
  if (i.offer) {
    const offer: JsonLdObject = {
      '@type': 'Offer',
      url: i.url,
      price: priceString(i.offer.priceMinor, i.offer.currency),
      priceCurrency: i.offer.currency,
      availability: `${CONTEXT}/${i.offer.availability}`
    };
    if (i.offer.condition) offer['itemCondition'] = `${CONTEXT}/${i.offer.condition}`;
    if (nonEmpty(i.offer.sellerName)) offer['seller'] = { '@type': 'Organization', name: i.offer.sellerName };
    ld['offers'] = offer;
  }
  if (rating) ld['aggregateRating'] = { '@type': 'AggregateRating', ratingValue: Number(rating.value.toFixed(1)), reviewCount: rating.count, bestRating: 5, worstRating: 1 };
  return ld;
}

export interface Crumb {
  name: string;
  /** Absolute URL; the last crumb (the current page) may omit it. */
  url?: string;
}

export function breadcrumbJsonLd(crumbs: readonly Crumb[]): JsonLdObject | undefined {
  const real = crumbs.filter((c) => nonEmpty(c.name));
  if (real.length < 2) return undefined;
  return {
    '@context': CONTEXT,
    '@type': 'BreadcrumbList',
    itemListElement: real.map((c, idx): JsonLdObject => ({ '@type': 'ListItem', position: idx + 1, name: c.name, ...(c.url ? { item: c.url } : {}) }))
  };
}

export function organizationJsonLd(i: { origin: string; name: string; logoUrl: string; sameAs?: readonly string[] }): JsonLdObject {
  return {
    '@context': CONTEXT,
    '@type': 'Organization',
    '@id': `${i.origin}/#organization`,
    name: i.name,
    url: i.origin,
    logo: i.logoUrl,
    ...(i.sameAs && i.sameAs.length > 0 ? { sameAs: [...i.sameAs] } : {})
  };
}

/** WebSite with the sitelinks SearchAction (Google retired the sitelinks search box in 2024; the markup is still valid schema.org). */
export function websiteJsonLd(i: { origin: string; name: string }): JsonLdObject {
  return {
    '@context': CONTEXT,
    '@type': 'WebSite',
    '@id': `${i.origin}/#website`,
    url: i.origin,
    name: i.name,
    publisher: { '@id': `${i.origin}/#organization` },
    potentialAction: { '@type': 'SearchAction', target: { '@type': 'EntryPoint', urlTemplate: `${i.origin}/s?q={search_term_string}` }, 'query-input': 'required name=search_term_string' }
  };
}

export interface LocalBusinessInput {
  url: string;
  name: string;
  image?: string;
  telephone?: string;
  address: { street: string; city: string; region: string; postalCode: string; country: string };
}

/** LocalBusiness for a physical store with a real street address; undefined without one. */
export function localBusinessJsonLd(i: LocalBusinessInput): JsonLdObject | undefined {
  const a = i.address;
  if (![a.street, a.city, a.region, a.postalCode, a.country].every(nonEmpty) || !nonEmpty(i.name)) return undefined;
  return {
    '@context': CONTEXT,
    '@type': 'LocalBusiness',
    '@id': `${i.url}#business`,
    name: i.name,
    url: i.url,
    ...(nonEmpty(i.image) ? { image: i.image } : {}),
    ...(nonEmpty(i.telephone) ? { telephone: i.telephone } : {}),
    address: { '@type': 'PostalAddress', streetAddress: a.street, addressLocality: a.city, addressRegion: a.region, postalCode: a.postalCode, addressCountry: a.country }
  };
}

export interface ServiceLdInput {
  url: string;
  name: string;
  description?: string;
  category?: string;
  providerName: string;
  providerUrl?: string;
  priceMinor?: number;
  currency?: string;
  rating?: { value: number; count: number };
}

/** Service for a service-provider offering: provider, category and the from-price when the provider states one. */
export function serviceJsonLd(i: ServiceLdInput): JsonLdObject | undefined {
  if (!nonEmpty(i.name) || !nonEmpty(i.providerName)) return undefined;
  const rating = i.rating && i.rating.count > 0 && i.rating.value > 0 && i.rating.value <= 5 ? i.rating : undefined;
  const ld: JsonLdObject = {
    '@context': CONTEXT,
    '@type': 'Service',
    '@id': `${i.url}#service`,
    url: i.url,
    name: i.name,
    provider: { '@type': 'Organization', name: i.providerName, ...(nonEmpty(i.providerUrl) ? { url: i.providerUrl } : {}) }
  };
  if (nonEmpty(i.description)) ld['description'] = i.description;
  if (nonEmpty(i.category)) ld['serviceType'] = i.category;
  if (i.priceMinor !== undefined && i.priceMinor > 0 && nonEmpty(i.currency)) {
    ld['offers'] = { '@type': 'Offer', url: i.url, price: priceString(i.priceMinor, i.currency), priceCurrency: i.currency };
  }
  if (rating) ld['aggregateRating'] = { '@type': 'AggregateRating', ratingValue: Number(rating.value.toFixed(1)), reviewCount: rating.count, bestRating: 5, worstRating: 1 };
  return ld;
}
