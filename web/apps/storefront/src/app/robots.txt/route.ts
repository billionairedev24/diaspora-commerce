import { NOINDEX_HEADER } from '@/lib/seo/policy';
import { buildRobotsTxt } from '@/lib/seo/robots';
import { policyForRequest } from '@/lib/seo/server';

export const dynamic = 'force-dynamic';

/** robots.txt: prod allows crawling and names the sitemap index; every other host answers Disallow: / with no sitemap. */
export function GET(request: Request): Response {
  const policy = policyForRequest(request);
  return new Response(buildRobotsTxt(policy), {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      // Short edge cache so a launch-day flip is picked up within the hour; crawlers re-fetch robots.txt about daily.
      'Cache-Control': policy.indexable ? 'public, max-age=300, s-maxage=3600, stale-while-revalidate=86400' : 'no-store',
      'X-Robots-Tag': NOINDEX_HEADER
    }
  });
}
