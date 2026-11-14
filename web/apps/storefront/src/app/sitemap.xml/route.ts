import { NOINDEX_HEADER } from '@/lib/seo/policy';
import { sitemapCache, sitemapOriginForRequest } from '@/lib/seo/server';
import { indexOfSet, renderIndex } from '@/lib/seo/sitemap';

export const dynamic = 'force-dynamic';

const XML = { 'Content-Type': 'application/xml; charset=utf-8', 'X-Robots-Tag': NOINDEX_HEADER };

/** The sitemap index (prod robots.txt names it). Only the prod host and a local run (loopback host, noindex) have one: every other host answers 404, never a list of URLs. */
export async function GET(request: Request): Promise<Response> {
  const origin = sitemapOriginForRequest(request);
  if (!origin) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });
  try {
    const set = await sitemapCache(origin).get();
    return new Response(renderIndex(indexOfSet(set, origin)), {
      headers: { ...XML, 'Cache-Control': 'public, max-age=300, s-maxage=900, stale-while-revalidate=3600', 'Last-Modified': new Date(set.generatedAt).toUTCString() }
    });
  } catch {
    // Nothing generated yet and the gateway is down: tell crawlers to come back; never an empty "success".
    return new Response('Sitemap temporarily unavailable', { status: 503, headers: { 'Retry-After': '300', 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });
  }
}
