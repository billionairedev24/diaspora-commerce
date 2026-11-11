/**
 * What the seller may do with a category and brand: sell it now, apply once, or not at all (the approval states of
 * docs/engineering/listing-rules.md section 2).
 *
 * PLACEHOLDER. The approval engine (seller and staff services) decides the state and will expose an endpoint for it.
 * Until that lands this always answers SELL, so the product form works exactly as it did without approvals. When the
 * endpoint exists, only the body of this hook changes (a query on category and brand); the form and the chip already
 * render whatever it returns.
 */
export type SellingStatus = 'SELL' | 'APPLY' | 'NOT_AVAILABLE';

export interface SellingStatusResult {
  status: SellingStatus;
  /** Why the state is APPLY or NOT_AVAILABLE, in plain words; null for SELL. */
  reason: string | null;
  loading: boolean;
  /** True while the answer is the placeholder and not the approval engine's. */
  placeholder: boolean;
}

export function useSellingStatus(category: string, brand: string): SellingStatusResult {
  // The arguments are part of the contract (the approval engine decides per category and brand): not used yet.
  void category;
  void brand;
  return { status: 'SELL', reason: null, loading: false, placeholder: true };
}
