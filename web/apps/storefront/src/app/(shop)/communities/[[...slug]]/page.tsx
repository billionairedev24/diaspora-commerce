import { isApiError } from '@afrotransact/api-client';
import { notFound, permanentRedirect, redirect } from 'next/navigation';

import { searchHref } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';

/**
 * /communities and /communities/<key>. A community is real only while it is above the staff thresholds (default 3 communities,
 * each with 8 eligible products and 2 sellers), so the route asks the gateway: a hidden or unknown community is a plain 404
 * (never a "0 results" page); an alias or a case variant (`Nigerian`, `naija`) redirects to the canonical key; a visible one
 * opens its products. `/communities` itself goes home.
 */
export default async function CommunityPage({ params }: { params: Promise<{ slug?: string[] }> }) {
  const { slug } = await params;
  const raw = decodeURIComponent(slug?.[0] ?? '').trim();
  if (!raw || (slug?.length ?? 0) > 1) {
    if (!raw) redirect('/');
    notFound();
  }
  const api = await serverApi();
  let resolved;
  try {
    resolved = await api.getCommunity(raw);
  } catch (e) {
    if (isApiError(e) && (e.status === 404 || e.status === 410)) notFound();
    throw e;
  }
  const { community, canonical } = resolved;
  if (!canonical) permanentRedirect(`/communities/${encodeURIComponent(community.key)}`);
  // The search facet value is the community's English name (the product attribute), not its key.
  redirect(searchHref({ community: community.labels.en || community.label }));
}
