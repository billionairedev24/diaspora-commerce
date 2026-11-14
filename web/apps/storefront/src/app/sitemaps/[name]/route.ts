import { NOINDEX_HEADER } from '@/lib/seo/policy';
import { sitemapCache, sitemapOriginForRequest } from '@/lib/seo/server';
import { parseChildName } from '@/lib/seo/sitemap';

export const dynamic = 'force-dynamic';

const notFound = () => new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });

/** One child sitemap (/sitemaps/products-1.xml): at most 50,000 URLs and 50 MB, generated from live data and cached. */
export async function GET(request: Request, { params }: { params: Promise<{ name: string }> }): Promise<Response> {
  const origin = sitemapOriginForRequest(request);
  if (!origin) return notFound();
  const child = parseChildName((await params).name);
  if (!child) return notFound();
  try {
    const set = await sitemapCache(origin).get();
    const chunk = set.kinds[child.kind]?.[child.n - 1];
    if (!chunk) return notFound();
    return new Response(chunk.xml, {
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
        'Cache-Control': 'public, max-age=300, s-maxage=900, stale-while-revalidate=3600',
        'Last-Modified': new Date(set.generatedAt).toUTCString(),
        'X-Sitemap-Urls': String(chunk.urls.length),
        'X-Robots-Tag': NOINDEX_HEADER
      }
    });
  } catch {
    return new Response('Sitemap temporarily unavailable', { status: 503, headers: { 'Retry-After': '300', 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });
  }
}
