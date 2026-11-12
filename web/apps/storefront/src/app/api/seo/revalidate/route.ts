import { NOINDEX_HEADER } from '@/lib/seo/policy';
import { policyForRequest, sitemapCache } from '@/lib/seo/server';

export const dynamic = 'force-dynamic';

const reply = (status: number, body: string) => new Response(body, { status, headers: { 'Cache-Control': 'no-store', 'X-Robots-Tag': NOINDEX_HEADER } });

/** Regenerate the sitemaps on change: a catalog/content job calls this with `Authorization: Bearer $SEO_REVALIDATE_TOKEN`.
    Without the variable the route does not exist (404). Per isolate; the TTL covers the others. */
export function POST(request: Request): Response {
  const token = process.env['SEO_REVALIDATE_TOKEN'];
  if (!token) return reply(404, 'Not found');
  if (request.headers.get('authorization') !== `Bearer ${token}`) return reply(401, 'Unauthorized');
  const policy = policyForRequest(request);
  if (!policy.indexable || !policy.origin) return reply(404, 'Not found');
  sitemapCache(policy.origin).invalidate();
  return reply(202, 'Sitemaps will be regenerated on the next request');
}
