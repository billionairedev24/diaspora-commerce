import { redirect } from 'next/navigation';

import { searchHref } from '@/lib/api/queries';

/** /communities and /communities/<name> (links from content placements) → search filtered by that community. */
export default async function CommunityPage({ params }: { params: Promise<{ slug?: string[] }> }) {
  const { slug } = await params;
  const raw = decodeURIComponent(slug?.[0] ?? '').trim();
  const community = raw ? raw.charAt(0).toUpperCase() + raw.slice(1).toLowerCase() : null;
  redirect(searchHref({ community }));
}
