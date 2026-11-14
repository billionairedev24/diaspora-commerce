import { type ProductSummary } from '@afrotransact/api-client';

import { recommendable } from '@/lib/seo/eligibility';

/** How many related products the cart rail shows at most. */
export const RELATED_SHOWN = 4;

export interface RelatedInput {
  /** Search hits of the cart's departments, in rank order per department (the lists are interleaved fairly). */
  pools: readonly (readonly ProductSummary[])[];
  /** ATINs already in the cart or saved for later. */
  exclude: ReadonlySet<string>;
  /** Names of the sellers already in the cart. */
  cartSellers: ReadonlySet<string>;
  limit?: number;
}

/**
 * Real, related products for a cart: same department as a cart item (the pools come from search), in stock with a real
 * photograph, not in the cart, never sponsored. Products of sellers who are not yet in the cart come first. When everything
 * left is sold by one seller that is already in the cart it is not a recommendation but that seller's shelf, so nothing is shown.
 * Returns [] when there is nothing real to show.
 */
export function pickRelated({ pools, exclude, cartSellers, limit = RELATED_SHOWN }: RelatedInput): ProductSummary[] {
  const seen = new Set<string>();
  const merged: ProductSummary[] = [];
  const longest = Math.max(0, ...pools.map((p) => p.length));
  for (let i = 0; i < longest; i += 1) {
    for (const pool of pools) {
      const p = pool[i];
      if (!p || seen.has(p.atin)) continue;
      seen.add(p.atin);
      if (exclude.has(p.atin) || !recommendable(p)) continue;
      merged.push(p);
    }
  }
  const fresh = merged.filter((p) => !cartSellers.has(p.sellerName));
  const known = merged.filter((p) => cartSellers.has(p.sellerName));
  const out = [...fresh, ...known].slice(0, limit);
  const sellers = new Set(out.map((p) => p.sellerName));
  if (sellers.size === 1 && cartSellers.has(out[0]?.sellerName ?? '')) return [];
  return out;
}
