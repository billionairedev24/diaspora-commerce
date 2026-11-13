'use client';

import { sellerQueries, type SellingFacts,type SellingMissing } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { sellerApi } from './api';
import { useSeller } from './session';

/**
 * What the seller may do with a category and brand: sell it now, apply once, or not at all (the approval states of
 * docs/engineering/listing-rules.md section 2). seller-service decides (`GET /v1/seller/selling-status`); catalog refuses to
 * take a listing live whatever this says. The product form shows the answer as a chip: "Sell this product", "Apply to
 * sell" with a button, or "Not available" with the reason.
 */
export type SellingStatus = 'SELL' | 'APPLY' | 'NOT_AVAILABLE';

export interface SellingStatusResult {
  status: SellingStatus;
  /** Why the state is APPLY or NOT_AVAILABLE, in plain words; null for SELL. */
  reason: string | null;
  loading: boolean;
  /** Each scope in the way (what to apply for, or why it cannot be sold); empty for SELL. */
  missing: SellingMissing[];
  /** The scope the Apply to sell button applies for first; null when nothing can be applied for. */
  applyTo: SellingMissing | null;
  /** Where the seller applies: the Selling approvals page with the first scope open; null when there is nothing to apply for. */
  applyHref: string | null;
  /** Facts the answer still depends on (jewelry: "material"): fill them in and the chip updates. */
  pendingFacts: string[];
}

/** Optional facts beyond category and brand that decide the answer for some rules. */
export interface SellingFactsExtra {
  productTypeCode?: string;
  /** Jewelry: gold, silver, a gemstone or a watch need approval. */
  material?: string;
  gtin?: string;
}

/**
 * The selling status of a listing being filled in. Every argument may be empty while the form is half done; the query
 * waits for a category and keeps the previous answer while the next one loads (no flicker while typing a brand).
 * `category` may be a comma-separated list: the category and its parents.
 */
export function useSellingStatus(category: string, brand: string, extra: SellingFactsExtra = {}): SellingStatusResult {
  const { orgId, isProvider } = useSeller();
  const facts: SellingFacts = {
    categoryCode: category.trim() || undefined,
    brand: brand.trim() || undefined,
    productTypeCode: extra.productTypeCode?.trim() || undefined,
    material: extra.material?.trim() || undefined,
    gtin: extra.gtin?.trim() || undefined
  };
  const enabled = !isProvider && (facts.categoryCode !== undefined || facts.brand !== undefined);
  const q = useQuery({ ...sellerQueries.sellingStatus(sellerApi(), orgId, facts), enabled });
  const data = enabled ? q.data : undefined;
  const missing = data?.missing ?? [];
  const applyTo = data?.level === 'APPLY' ? (missing.find((m) => m.canApply) ?? null) : null;
  return {
    status: data?.level ?? 'SELL',
    reason: data?.reason ?? null,
    loading: enabled && q.isPending,
    missing,
    applyTo,
    applyHref: applyTo ? sellingApplyHref(applyTo.scope.type, applyTo.scope.key) : null,
    pendingFacts: data?.pendingFacts ?? []
  };
}

/** The Selling approvals page with this scope's application open. */
export const sellingApplyHref = (scopeType: string, scopeKey: string): string => `/listings/approvals?apply=${encodeURIComponent(`${scopeType}:${scopeKey}`)}`;
