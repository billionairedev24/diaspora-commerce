import { NOINDEX_HEADER } from '@/lib/seo/policy';
import { policyForRequest, sitemapCache } from '@/lib/seo/server';
import { indexOfSet, renderIndex } from '@/lib/seo/sitemap';

export const dynamic = 'force-dynamic';

const XML = { 'Content-Type': 'application/xml; charset=utf-8', 'X-Robots-Tag': NOINDEX_HEADER };

/** The sitemap index (robots.txt names it). Only the prod host has one: any other host answers 404, never a list of URLs. */
export async function GET(request: Request): Promise<Response> {
  const policy = policyForRequest(request);
  if (!policy.indexable || !policy.origin) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });
  try {
    const set = await sitemapCache(policy.origin).get();
    return new Response(renderIndex(indexOfSet(set, policy.origin)), {
      headers: { ...XML, 'Cache-Control': 'public, max-age=300, s-maxage=900, stale-while-revalidate=3600', 'Last-Modified': new Date(set.generatedAt).toUTCString() }
    });
  } catch {
    // Nothing generated yet and the gateway is down: tell crawlers to come back; never an empty "success".
    return new Response('Sitemap temporarily unavailable', { status: 503, headers: { 'Retry-After': '300', 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });
  }
}
