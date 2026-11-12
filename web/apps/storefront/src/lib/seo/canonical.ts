import { type SearchQuery } from '@afrotransact/api-client';

/** Path of a product page. */
export const productPath = (atin: string): string => `/p/${encodeURIComponent(atin)}`;

/** Path of a category landing page: the search page filtered to ONE department or ONE community (the first page has no page=). */
export function categoryPath(kind: 'dept' | 'community', value: string, page = 1): string {
  const p = new URLSearchParams({ [kind]: value });
  if (page > 1) p.set('page', String(page));
  return `/s?${p.toString()}`;
}

export type SearchPageKind =
  | { kind: 'category'; dimension: 'dept' | 'community'; value: string; page: number }
  | { kind: 'noindex' };

/**
 * What a /s URL is for the search engines. A category page is exactly ONE of department / community, optionally paginated
 * with page=. Free-text searches, sorts and every filter (price, rating, fulfilment, seller kind, delivery) are faceted views
 * of those pages: noindex, canonical to the category page when there is one (or no canonical when it is a free-text search).
 */
export function classifySearch(query: SearchQuery): SearchPageKind {
  const filtered = query.q.trim() !== '' || query.sort !== 'relevance' || query.price !== null || query.minRating !== null || query.fulfilment !== null || query.sellerKind !== null || query.maxDays !== null;
  if (filtered) return { kind: 'noindex' };
  const page = query.page + 1;
  if (query.department && !query.community) return { kind: 'category', dimension: 'dept', value: query.department, page };
  if (query.community && !query.department) return { kind: 'category', dimension: 'community', value: query.community, page };
  return { kind: 'noindex' };
}

/** The canonical path of a faceted search: its category page when exactly one category is set, else none. */
export function facetCanonicalPath(query: SearchQuery): string | undefined {
  if (query.department && !query.community) return categoryPath('dept', query.department);
  if (query.community && !query.department) return categoryPath('community', query.community);
  return undefined;
}

/**
 * The page a variant's canonical points to: the variant with the smallest ATIN of the product's variant group, so every
 * sibling (size, colour…) names the same parent whatever order the API lists them in. A product without variants is its own
 * canonical. (The catalog has no explicit parent id on the product detail yet: backlog S-SEO-parent.)
 */
export function canonicalAtin(atin: string, variants: readonly { atin: string }[]): string {
  const group = [atin, ...variants.map((v) => v.atin)];
  return group.reduce((a, b) => (b < a ? b : a));
}

/** Absolute URL of a path on the canonical origin, or the path itself when no origin is configured (local runs). */
export function absoluteUrl(origin: string | undefined, path: string): string {
  return origin ? `${origin.replace(/\/+$/, '')}${path}` : path;
}
