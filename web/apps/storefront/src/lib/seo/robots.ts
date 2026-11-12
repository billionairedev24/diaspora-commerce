import { type IndexingPolicy } from './policy';

/** Query parameters that only track, sort, filter or paginate a duplicate view; they never make a page of their own. */
export const TRACKING_PARAMS = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content', 'gclid', 'fbclid', 'msclkid', 'ref', 'source'] as const;
/** Search filter and sort keys (the storefront URL vocabulary, @afrotransact/api-client search-params). */
export const FACET_PARAMS = ['q', 'sort', 'price', 'fulfilment', 'soldBy', 'rating', 'delivery'] as const;

/** robots.txt of a non-indexable host: everything disallowed, no sitemap. */
export const DISALLOW_ALL_ROBOTS = 'User-agent: *\nDisallow: /\n';

/** Paths crawlers are kept out of in prod (transactional, per-shopper, machine). */
const DISALLOWED_PATHS = ['/account', '/cart', '/checkout', '/orders', '/signin', '/signup', '/r/', '/api/', '/back-office', '/sell', '/provide', '/offline', '/search', '/gift-cards', '/delivery-runs/subscriptions', '/pre-orders/waitlist'];

/**
 * robots.txt. Prod: crawl allowed (static assets, scripts and images are explicitly allowed so Google can render pages),
 * cart/checkout/account out, search-result facets and tracking parameters out (they create duplicates; category pages
 * /s?dept=… and /s?community=… and their page= links stay crawlable), and the sitemap index named. Any other host: Disallow: /.
 */
export function buildRobotsTxt(policy: IndexingPolicy): string {
  if (!policy.indexable || !policy.origin) return DISALLOW_ALL_ROBOTS;
  const lines = ['User-agent: *', 'Allow: /', 'Allow: /_next/static/', 'Allow: /_next/image'];
  for (const p of DISALLOWED_PATHS) lines.push(`Disallow: ${p}`);
  // Internal search (a free-text query), sorts and filters are duplicates of category pages: keep crawlers out of them.
  for (const k of FACET_PARAMS) lines.push(`Disallow: /*?*${k}=`);
  for (const k of TRACKING_PARAMS) lines.push(`Disallow: /*?*${k}=`);
  lines.push('', `Sitemap: ${policy.origin}/sitemap.xml`, '');
  return lines.join('\n');
}
