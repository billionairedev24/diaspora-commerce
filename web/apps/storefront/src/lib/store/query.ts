import { type StorefrontQueryState, type StorefrontSort } from '@afrotransact/ui';

const SORTS: readonly StorefrontSort[] = ['relevance', 'price_asc', 'price_desc', 'rating', 'newest'];
const one = (v: string | string[] | undefined): string => (Array.isArray(v) ? (v[0] ?? '') : (v ?? ''));

/** The All products grid state carried in the address (shared and reloaded links); anything unknown is ignored. */
export function storefrontQueryOf(sp: Record<string, string | string[] | undefined>): StorefrontQueryState {
  const sort = one(sp.sort) as StorefrontSort;
  return { q: one(sp.q).slice(0, 80), category: one(sp.category).slice(0, 80), price: one(sp.price).slice(0, 12), sort: SORTS.includes(sort) ? sort : 'relevance' };
}

export const isDefaultQuery = (q: StorefrontQueryState): boolean => !q.q && !q.category && !q.price && q.sort === 'relevance';
