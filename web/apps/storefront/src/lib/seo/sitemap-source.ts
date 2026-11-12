import { type ApiClient, SearchQuerySchema } from '@afrotransact/api-client';

import { categoryPath, productPath } from './canonical';
import { sitemapProductEligible } from './eligibility';
import { chunkUrls, lastmodOf, type SitemapKind, type SitemapSet, type SitemapUrl } from './sitemap';

/* Reads live data through the gateway BFF (the same public endpoints the storefront uses: search index, content, services) and
   turns it into sitemap URLs. Gaps are recorded in docs/engineering/seo.md: the search index carries no update time (so product
   and category URLs have no lastmod rather than an invented one), there is no store/seller listing endpoint (the `stores`
   sitemap stays empty), and an index query is capped by the search engine's maximum window (partitioned by department and
   community below). */

export type SeoApi = Pick<ApiClient, 'searchOrganic' | 'getHelpArticles' | 'getLegalIndex' | 'searchServices'>;

const PAGE_SIZE = 60;
/** Pages read per partition: 60 x 50 = 3,000 products; beyond the search window a partition is split by community. */
const MAX_PAGES = 50;
const SPLIT_ABOVE = 900;
const MAX_HELP_PAGES = 20;

/** Pages that exist as stand-alone public content (Plus membership, company, help hub, services hub, careers). */
export const STATIC_CONTENT_PATHS = ['/', '/about', '/membership', '/help', '/services', '/careers'] as const;

const query = (patch: Record<string, unknown>) => SearchQuerySchema.parse({ size: PAGE_SIZE, ...patch });

interface Collected {
  urls: SitemapUrl[];
  truncated: boolean;
}

async function pagedProducts(api: SeoApi, origin: string, base: Record<string, unknown>, seen: Set<string>, out: SitemapUrl[]): Promise<{ total: number; truncated: boolean; communities: string[] }> {
  let total = 0;
  let communities: string[] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await api.searchOrganic(query({ ...base, page }));
    if (page === 0) {
      total = res.total;
      communities = res.facets.community.filter((b) => b.count > 0).map((b) => b.value);
    }
    for (const h of res.hits) {
      if (h.sponsor !== null || seen.has(h.atin) || !sitemapProductEligible(h)) continue;
      seen.add(h.atin);
      out.push({ loc: `${origin}${productPath(h.atin)}`, images: h.imageUrl ? [{ loc: h.imageUrl, title: h.title }] : [] });
    }
    if (res.hits.length === 0 || page + 1 >= res.pageCount) return { total, truncated: false, communities };
  }
  return { total, truncated: true, communities };
}

/** Live, in-stock, real-photo products (never sponsored duplicates), partitioned by department and, for big ones, community. */
export async function collectProducts(api: SeoApi, origin: string): Promise<Collected> {
  const first = await api.searchOrganic(query({ size: 1 }));
  const seen = new Set<string>();
  const urls: SitemapUrl[] = [];
  let truncated = false;
  for (const dept of first.facets.department.filter((b) => b.count > 0).map((b) => b.value)) {
    const r = await pagedProducts(api, origin, { department: dept }, seen, urls);
    if (r.total > SPLIT_ABOVE || r.truncated) {
      for (const community of r.communities) {
        const rc = await pagedProducts(api, origin, { department: dept, community }, seen, urls);
        truncated ||= rc.truncated;
      }
    }
  }
  return { urls, truncated };
}

/** One category landing page per department and per community that has live products. */
export async function collectCategories(api: SeoApi, origin: string): Promise<SitemapUrl[]> {
  const first = await api.searchOrganic(query({ size: 1 }));
  const urls: SitemapUrl[] = [];
  for (const b of first.facets.department) if (b.count > 0) urls.push({ loc: `${origin}${categoryPath('dept', b.value)}` });
  for (const b of first.facets.community) if (b.count > 0) urls.push({ loc: `${origin}${categoryPath('community', b.value)}` });
  return urls;
}

/** Service offerings and their providers' pages. */
export async function collectServices(api: SeoApi, origin: string): Promise<SitemapUrl[]> {
  const res = await api.searchServices({});
  const urls: SitemapUrl[] = [];
  const orgs = new Set<string>();
  for (const s of res.services) {
    urls.push({ loc: `${origin}/services/${encodeURIComponent(s.serviceId)}`, images: s.imageUrl && /^https?:\/\//i.test(s.imageUrl) ? [{ loc: s.imageUrl, title: s.name }] : [] });
    orgs.add(s.orgId);
  }
  for (const org of orgs) urls.push({ loc: `${origin}/services/providers/${encodeURIComponent(org)}` });
  return urls;
}

/** Static pages, published help articles (lastmod = the article's update time) and legal documents (lastmod = effective date). */
export async function collectContent(api: SeoApi, origin: string): Promise<SitemapUrl[]> {
  const urls: SitemapUrl[] = STATIC_CONTENT_PATHS.map((p) => ({ loc: p === '/' ? origin : `${origin}${p}` }));
  let cursor: string | undefined;
  for (let i = 0; i < MAX_HELP_PAGES; i++) {
    const page = await api.getHelpArticles({ limit: 50, ...(cursor ? { cursor } : {}) });
    for (const a of page.items) {
      const lastmod = lastmodOf(a.updatedAt);
      urls.push({ loc: `${origin}/help/${encodeURIComponent(a.slug)}`, ...(lastmod ? { lastmod } : {}) });
    }
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  const legal = await api.getLegalIndex();
  for (const d of legal.documents) {
    const lastmod = lastmodOf(d.effectiveDate);
    urls.push({ loc: `${origin}/legal/${encodeURIComponent(d.key)}`, ...(lastmod ? { lastmod } : {}) });
  }
  return urls;
}

export interface BuildResult {
  set: SitemapSet;
  /** Kinds whose collection failed (the previous version of that kind is kept by the cache). */
  failed: SitemapKind[];
  truncated: boolean;
}

/** Collects every kind (a failing kind does not take the others down) and chunks it into child sitemaps. */
export async function buildSitemapSet(api: SeoApi, origin: string, now: Date = new Date()): Promise<BuildResult> {
  const failed: SitemapKind[] = [];
  let truncated = false;
  const run = async (kind: SitemapKind, f: () => Promise<SitemapUrl[]>): Promise<SitemapUrl[] | undefined> => {
    try {
      return await f();
    } catch {
      failed.push(kind);
      return undefined;
    }
  };
  const [products, categories, services, content] = await Promise.all([
    run('products', async () => {
      const c = await collectProducts(api, origin);
      truncated ||= c.truncated;
      return c.urls;
    }),
    run('categories', () => collectCategories(api, origin)),
    run('services', () => collectServices(api, origin)),
    run('content', () => collectContent(api, origin))
  ]);
  const set: SitemapSet = { generatedAt: now.toISOString(), kinds: {} };
  const put = (kind: SitemapKind, urls: SitemapUrl[] | undefined) => {
    if (urls && urls.length > 0) set.kinds[kind] = chunkUrls(urls);
  };
  put('products', products);
  put('categories', categories);
  put('services', services);
  put('content', content);
  // stores: no listing endpoint yet (docs/engineering/seo.md, gap list), so the kind stays empty and is not in the index.
  return { set, failed, truncated };
}
