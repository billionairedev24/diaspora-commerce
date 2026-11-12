import { type ProductDetail, SearchQuerySchema } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { tEn } from '@/i18n/t';
import { absoluteUrl, canonicalAtin, categoryPath, classifySearch, facetCanonicalPath } from '@/lib/seo/canonical';
import { realPhoto, realPhotoUrls, sitemapProductEligible } from '@/lib/seo/eligibility';
import { breadcrumbJsonLd, jsonLdScript, localBusinessJsonLd, organizationJsonLd, priceString, productJsonLd, serviceJsonLd, websiteJsonLd } from '@/lib/seo/jsonld';
import { clip, pageMetadata } from '@/lib/seo/metadata';
import { categorySeo, homeJsonLd, productSeo } from '@/lib/seo/pages';
import { indexingPolicy } from '@/lib/seo/policy';

import { forbiddenClaims, validateJsonLd } from './schemaOrgValidator';

const prod = indexingPolicy({ APP_ENV: 'prod', PUBLIC_WEB_URL: 'https://afrotransact.com' }, 'afrotransact.com');
const uat = indexingPolicy({ APP_ENV: 'uat', PUBLIC_WEB_URL: 'https://uat.afrotransact.com' }, 'uat.afrotransact.com');

const delivery = { days: 2, label: 'Friday', prefix: 'AfroTransact collects from the store and delivers', free: true, feeMinor: 0, cutoff: null };
const offer = (over: Partial<ProductDetail['otherOffers'][number]> = {}): ProductDetail['otherOffers'][number] => ({
  offerId: 'o1',
  seller: { id: 's1', name: 'Mama Nkechi Foods', kind: 'SELLER', city: 'Houston' },
  priceMinor: 1999,
  listPriceMinor: null,
  currency: 'USD',
  fulfilment: 'SELLER',
  availableQty: 12,
  stockStatus: 'IN_STOCK',
  delivery,
  ...over
});
const detail = (over: Partial<ProductDetail> = {}, product: Partial<ProductDetail['product']> = {}): ProductDetail => ({
  sizeChart: null,
  product: {
    atin: 'AT000000B2',
    title: 'Ofada Rice 2 kg',
    brand: 'Mama Nkechi',
    community: 'Yoruba',
    department: 'Groceries',
    unit: '2 kg',
    origin: 'Nigeria',
    bullets: ['Unpolished local rice', 'Stone-free'],
    rating: 4.6,
    reviewCount: 23,
    images: [{ url: 'https://media.afrotransact.com/p/b2-1.jpg', alt: 'A bag of Ofada rice' }],
    variants: [],
    ...product
  },
  buyBox: offer(),
  otherOffers: [],
  shipsFrom: 'Houston, TX',
  soldBy: 'Mama Nkechi Foods',
  returnsPolicy: 'Returns accepted within 30 days',
  unitPriceLabel: '$10.00 / kg',
  ...over
});

describe('canonical URLs', () => {
  it('a variant canonicalises to the smallest ATIN of its group, whatever the order; a plain product to itself', () => {
    const vs = [{ atin: 'AT000000C3' }, { atin: 'AT000000A1' }, { atin: 'AT000000B2' }];
    expect(canonicalAtin('AT000000B2', vs)).toBe('AT000000A1');
    expect(canonicalAtin('AT000000A1', [...vs].reverse())).toBe('AT000000A1');
    expect(canonicalAtin('AT000000B2', [])).toBe('AT000000B2');
  });

  it('only ONE department or ONE community, unfiltered, is a category page; every facet is noindex with its category as canonical', () => {
    const q = (o: Record<string, unknown>) => SearchQuerySchema.parse(o);
    expect(classifySearch(q({ department: 'Fashion' }))).toEqual({ kind: 'category', dimension: 'dept', value: 'Fashion', page: 1 });
    expect(classifySearch(q({ community: 'Igbo', page: 2 }))).toEqual({ kind: 'category', dimension: 'community', value: 'Igbo', page: 3 });
    for (const o of [{}, { q: 'rice' }, { department: 'Fashion', sort: 'price_asc' }, { department: 'Fashion', price: 'u10' }, { department: 'Fashion', community: 'Igbo' }, { department: 'Fashion', minRating: 4 }, { department: 'Fashion', fulfilment: 'SELLER' }, { department: 'Fashion', maxDays: 1 }]) {
      expect(classifySearch(q(o)), JSON.stringify(o)).toEqual({ kind: 'noindex' });
    }
    expect(facetCanonicalPath(q({ department: 'Fashion', sort: 'rating' }))).toBe('/s?dept=Fashion');
    expect(facetCanonicalPath(q({ q: 'rice' }))).toBeUndefined();
    expect(categoryPath('dept', 'Home & Art', 3)).toBe('/s?dept=Home+%26+Art&page=3');
    expect(absoluteUrl('https://afrotransact.com/', '/p/AT1')).toBe('https://afrotransact.com/p/AT1');
  });
});

describe('real photos and sitemap eligibility (same rule as home/rails)', () => {
  it('never icons, emoji, svg, data URIs or placeholders', () => {
    for (const bad of [null, undefined, '', '   ', 'data:image/png;base64,AAAA', '🍚', 'https://x.test/a.svg', 'https://x.test/favicon.ico', 'https://x.test/placeholder.png', 'https://x.test/icons/rice.png', 'https://x.test/no-image.jpg', 'ftp://x.test/a.jpg', 'a.jpg']) expect(realPhoto(bad), String(bad)).toBe(false);
    for (const good of ['https://media.afrotransact.com/p/a.jpg', 'http://localhost:9000/media/a.webp', '/media/a.jpg']) expect(realPhoto(good), good).toBe(true);
    expect(realPhotoUrls(['/m/a.jpg', 'https://x.test/placeholder.png', '/m/a.jpg', 'https://y.test/b.jpg'], 'https://afrotransact.com')).toEqual(['https://afrotransact.com/m/a.jpg', 'https://y.test/b.jpg']);
  });

  it('a product is listed only when in stock with a real photo', () => {
    const base = { atin: 'AT000000B2', inStock: true, imageUrl: 'https://m.test/a.jpg', availableQty: 3, stockStatus: 'IN_STOCK' as const };
    expect(sitemapProductEligible(base)).toBe(true);
    expect(sitemapProductEligible({ ...base, inStock: false })).toBe(false);
    expect(sitemapProductEligible({ ...base, stockStatus: 'OUT_OF_STOCK' })).toBe(false);
    expect(sitemapProductEligible({ ...base, availableQty: 0 })).toBe(false);
    expect(sitemapProductEligible({ ...base, imageUrl: 'https://m.test/icons/x.png' })).toBe(false);
    expect(sitemapProductEligible({ ...base, imageUrl: null })).toBe(false);
  });
});

describe('metadata', () => {
  it('title and description are clipped at a word boundary; one canonical; OG and Twitter carry the main image', () => {
    expect(clip('a '.repeat(200), 160).length).toBeLessThanOrEqual(160);
    expect(clip('short', 160)).toBe('short');
    const md = pageMetadata({ title: 'Ofada Rice', description: 'Good rice. '.repeat(40), canonicalPath: '/p/AT000000B2', index: true, imageUrl: 'https://media.afrotransact.com/p/b2-1.jpg', imageAlt: 'Rice' }, prod);
    expect(md.description!.length).toBeLessThanOrEqual(160);
    expect(md.alternates?.canonical).toBe('/p/AT000000B2');
    expect(md.openGraph).toMatchObject({ type: 'website', url: '/p/AT000000B2', images: [{ url: 'https://media.afrotransact.com/p/b2-1.jpg', alt: 'Rice' }] });
    expect(md.twitter).toMatchObject({ card: 'summary_large_image', images: ['https://media.afrotransact.com/p/b2-1.jpg'] });
    expect(pageMetadata({ title: 'No image', canonicalPath: '/x', index: true }, prod).twitter).toMatchObject({ card: 'summary' });
  });
});

describe('product SEO from real data', () => {
  it('unique title and description from the product, one canonical, noindex never on a live product', () => {
    const seo = productSeo(detail(), prod, tEn);
    expect(seo.metadata.title).toBe('Ofada Rice 2 kg · Mama Nkechi');
    expect(seo.metadata.description).toContain('Ofada Rice 2 kg by Mama Nkechi');
    expect(seo.metadata.description).toContain('Unpolished local rice');
    expect(seo.metadata.alternates?.canonical).toBe('/p/AT000000B2');
    expect(seo.metadata.robots).toMatchObject({ index: true });
    const other = productSeo(detail({}, { atin: 'AT000000C3', title: 'Ofada Rice 5 kg' }), prod, tEn);
    expect(other.metadata.description).not.toBe(seo.metadata.description);
  });

  it('a variant page names its parent as canonical', () => {
    const d = detail({}, { atin: 'AT000000C3', variants: [{ atin: 'AT000000A1', label: '1 kg', selected: false, available: true, priceMinor: 999, options: {} }, { atin: 'AT000000C3', label: '5 kg', selected: true, available: true, priceMinor: 4999, options: {} }] });
    expect(productSeo(d, prod, tEn).metadata.alternates?.canonical).toBe('/p/AT000000A1');
  });

  it('Product + Offer JSON-LD is valid schema.org, with real price, currency, availability and images only', () => {
    const [product, crumbs] = productSeo(detail(), prod, tEn).jsonLd;
    expect(validateJsonLd(product!)).toEqual([]);
    expect(validateJsonLd(crumbs!)).toEqual([]);
    expect(product).toMatchObject({
      '@type': 'Product',
      sku: 'AT000000B2',
      url: 'https://afrotransact.com/p/AT000000B2',
      image: ['https://media.afrotransact.com/p/b2-1.jpg'],
      offers: { '@type': 'Offer', price: '19.99', priceCurrency: 'USD', availability: 'https://schema.org/InStock' },
      aggregateRating: { '@type': 'AggregateRating', ratingValue: 4.6, reviewCount: 23 }
    });
    expect(crumbs).toMatchObject({ itemListElement: [{ position: 1, name: 'Home' }, { position: 2, name: 'Groceries', item: 'https://afrotransact.com/s?dept=Groceries' }, { position: 3, name: 'Ofada Rice 2 kg' }] });
  });

  it('no invented facts: no rating without reviews, no itemCondition, no shipping or return details, no 0-review markup', () => {
    const [product] = productSeo(detail({}, { rating: 0, reviewCount: 0 }), prod, tEn).jsonLd;
    expect(product).not.toHaveProperty('aggregateRating');
    expect(JSON.stringify(product)).not.toMatch(/reviewCount|"review"|itemCondition|shippingDetails|hasMerchantReturnPolicy/);
    // ratings of 0 with reviews, or reviews with a 0 rating, are not emitted either
    expect(productJsonLd({ url: 'https://x.test/p', atin: 'AT1', name: 'n', images: [], offer: { priceMinor: 100, currency: 'USD', availability: 'InStock' }, rating: { value: 0, count: 5 } })).not.toHaveProperty('aggregateRating');
    // a condition only when the catalog states one
    expect(productJsonLd({ url: 'https://x.test/p', atin: 'AT1', name: 'n', images: [], offer: { priceMinor: 100, currency: 'USD', availability: 'InStock', condition: 'UsedCondition' } })).toMatchObject({ offers: { itemCondition: 'https://schema.org/UsedCondition' } });
  });

  it('no tax claims, carrier names or "AfroTransact delivers/collects" anywhere in the structured data', () => {
    const d = detail({ returnsPolicy: 'Free returns via UPS', shipsFrom: 'Shipped by FedEx, tax included' }, { bullets: ['Rice'] });
    for (const ld of productSeo(d, prod, tEn).jsonLd) if (ld) expect(forbiddenClaims(ld as never)).toEqual([]);
    expect(JSON.stringify(productSeo(d, prod, tEn).jsonLd)).not.toMatch(/UPS|FedEx|AfroTransact collects|delivers|tax/i);
    expect(forbiddenClaims({ a: 'AfroTransact delivers to you' })).toHaveLength(1);
  });

  it('only real photos are structured-data images (never an icon or placeholder), none when there are none', () => {
    const d = detail({}, { images: [{ url: 'https://media.afrotransact.com/icons/rice.png', alt: '' }, { url: null, alt: '' }] });
    const seo = productSeo(d, prod, tEn);
    expect(seo.jsonLd[0]).not.toHaveProperty('image');
    expect(seo.metadata.openGraph).not.toHaveProperty('images');
  });

  it('out of stock: stays 200 and indexable, availability OutOfStock with the last known price; nothing known = no Offer, never a fake one', () => {
    const oos = detail({ buyBox: null, otherOffers: [offer({ priceMinor: 2500, stockStatus: 'OUT_OF_STOCK', availableQty: 0 })] });
    const seo = productSeo(oos, prod, tEn);
    expect(seo.metadata.robots).toMatchObject({ index: true });
    expect(seo.metadata.description).toContain('Currently unavailable');
    expect(seo.jsonLd[0]).toMatchObject({ offers: { availability: 'https://schema.org/OutOfStock', price: '25.00' } });
    expect(validateJsonLd(seo.jsonLd[0]!)).toEqual([]);
    const nothing = productSeo(detail({ buyBox: null, otherOffers: [] }, { rating: 0, reviewCount: 0 }), prod, tEn);
    expect(nothing.jsonLd[0]).toBeUndefined();
    expect(validateJsonLd(productSeo(detail({ buyBox: offer({ stockStatus: 'LOW_STOCK', availableQty: 2 }) }), prod, tEn).jsonLd[0]!)).toEqual([]);
    expect(productSeo(detail({ buyBox: offer({ stockStatus: 'LOW_STOCK', availableQty: 2 }) }), prod, tEn).jsonLd[0]).toMatchObject({ offers: { availability: 'https://schema.org/LimitedAvailability' } });
  });

  it('on a non-prod host the page is noindex whatever the data says', () => {
    expect(productSeo(detail(), uat, tEn).metadata.robots).toEqual({ index: false, follow: false });
  });
});

describe('category, home, service and store structured data', () => {
  it('category page: unique title/description, BreadcrumbList, page 2 has its own canonical, empty category is noindex', () => {
    const c1 = categorySeo({ dimension: 'dept', value: 'Fashion', page: 1, total: 42 }, prod, tEn);
    expect(c1.metadata.title).toBe('Fashion');
    expect(c1.metadata.description).toContain('42 products');
    expect(validateJsonLd(c1.jsonLd[0]!)).toEqual([]);
    const c2 = categorySeo({ dimension: 'dept', value: 'Fashion', page: 2, total: 42 }, prod, tEn);
    expect(c2.metadata.alternates?.canonical).toBe('/s?dept=Fashion&page=2');
    expect(c2.metadata.title).toBe('Fashion · page 2');
    expect(categorySeo({ dimension: 'dept', value: 'Fashion', page: 1, total: 0 }, prod, tEn).metadata.robots).toEqual({ index: false, follow: false });
  });

  it('home: Organization and WebSite with a SearchAction, both valid', () => {
    const [org, site] = homeJsonLd('https://afrotransact.com');
    expect(validateJsonLd(org!)).toEqual([]);
    expect(validateJsonLd(site!)).toEqual([]);
    expect(site).toMatchObject({ potentialAction: { '@type': 'SearchAction', target: { urlTemplate: 'https://afrotransact.com/s?q={search_term_string}' } } });
    expect(homeJsonLd(undefined)).toEqual([]);
    expect(organizationJsonLd({ origin: 'https://afrotransact.com', name: 'AfroTransact', logoUrl: 'https://afrotransact.com/icons/icon-512.png' })).not.toHaveProperty('sameAs');
    expect(websiteJsonLd({ origin: 'https://afrotransact.com', name: 'AfroTransact' })['@type']).toBe('WebSite');
  });

  it('service provider: Service with provider and from-price; LocalBusiness only with a real address', () => {
    const svc = serviceJsonLd({ url: 'https://afrotransact.com/services/s1', name: 'Agbada tailoring', providerName: 'Ade Tailors', category: 'tailoring', priceMinor: 12000, currency: 'USD', description: 'Bespoke agbada' });
    expect(validateJsonLd(svc!)).toEqual([]);
    expect(svc).toMatchObject({ '@type': 'Service', serviceType: 'tailoring', provider: { name: 'Ade Tailors' }, offers: { price: '120.00', priceCurrency: 'USD' } });
    expect(svc).not.toHaveProperty('aggregateRating');
    expect(serviceJsonLd({ url: 'u', name: 'x', providerName: '' })).toBeUndefined();
    const lb = localBusinessJsonLd({ url: 'https://afrotransact.com/b/ade', name: 'Ade Foods', address: { street: '1 Main St', city: 'Houston', region: 'TX', postalCode: '77001', country: 'US' } });
    expect(validateJsonLd(lb!)).toEqual([]);
    expect(localBusinessJsonLd({ url: 'u', name: 'Ade Foods', address: { street: '', city: 'Houston', region: 'TX', postalCode: '77001', country: 'US' } })).toBeUndefined();
  });

  it('BreadcrumbList needs two crumbs; prices keep the currency’s decimals; the script cannot close its own tag', () => {
    expect(breadcrumbJsonLd([{ name: 'Home', url: 'https://x.test/' }])).toBeUndefined();
    expect(priceString(1999, 'USD')).toBe('19.99');
    expect(priceString(1500, 'JPY')).toBe('1500');
    const s = jsonLdScript({ '@type': 'Product', name: '</script><script>alert(1)</script>' })!;
    expect(s).not.toContain('</script>');
    expect(JSON.parse(s)).toMatchObject({ name: '</script><script>alert(1)</script>' });
  });
});

describe('the validator itself catches broken markup (so the checks above mean something)', () => {
  it('flags missing required fields and bad values', () => {
    expect(validateJsonLd({ '@context': 'https://schema.org', '@type': 'Product', name: 'x' })).toEqual(['$(Product): needs offers, review or aggregateRating']);
    expect(validateJsonLd({ '@context': 'https://schema.org', '@type': 'Product', name: 'x', offers: { '@type': 'Offer', price: '12', availability: 'InStock' } }).length).toBeGreaterThanOrEqual(2);
    expect(validateJsonLd({ '@context': 'https://schema.org', '@type': 'Product', name: 'x', aggregateRating: { '@type': 'AggregateRating', ratingValue: 4, reviewCount: 0 } }).length).toBe(1);
    expect(validateJsonLd({ '@context': 'https://schema.org', '@type': 'BreadcrumbList', itemListElement: [{ '@type': 'ListItem', position: 1, name: 'a' }] }).length).toBeGreaterThan(0);
    expect(validateJsonLd({ '@type': 'Organization', name: 'x', url: 'https://x.test' })).toEqual(['$: @context must be https://schema.org']);
  });
});

