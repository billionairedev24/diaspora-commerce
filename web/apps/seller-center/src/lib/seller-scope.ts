'use client';

import { type SellerCatalogScope, sellerQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { sellerApi } from './api';
import { useSeller } from './session';

/** What the screens need to know about the departments the organisation may sell in. */
export interface SellerScopeView {
  /** Not loaded yet. */
  isPending: boolean;
  /** The scope could not be read (the server still enforces it on every write). */
  isError: boolean;
  /** AfroTransact Retail: every department. */
  exempt: boolean;
  departments: SellerCatalogScope['departments'];
  /** An allowed department has a product type with sizes: Size charts makes sense. */
  hasSizing: boolean;
  requests: SellerCatalogScope['requests'];
  /** Codes of departments with a request waiting for review. */
  pendingCodes: string[];
  /** Whether the organisation may sell in this department code. */
  allows: (code: string) => boolean;
}

/**
 * The organisation's department scope from `GET /v1/seller/catalog-scope`, cached with the query key factory
 * (`sellerKeys.scope(orgId)`). The one place the pickers, the sidebar and the page guards read it from. UI gating
 * is a convenience: catalog-service refuses writes outside the departments whatever this says.
 */
export function useSellerScopeOf(orgId: string | null | undefined, enabled: boolean): SellerScopeView {
  const q = useQuery({ ...sellerQueries.scope(sellerApi(), orgId ?? 'none'), enabled: enabled && !!orgId });
  const scope = q.data;
  return {
    isPending: enabled && !!orgId && q.isPending,
    isError: q.isError,
    exempt: scope?.exempt ?? false,
    departments: scope?.departments ?? [],
    hasSizing: scope?.hasSizing ?? false,
    requests: scope?.requests ?? [],
    pendingCodes: (scope?.requests ?? []).filter((r) => r.status === 'PENDING').map((r) => r.departmentCode),
    allows: (code) => scope?.exempt === true || (scope?.departments ?? []).some((d) => d.code === code)
  };
}

/** The active organisation's scope (inside the portal shell). A service-provider organisation has none. */
export function useSellerScope(): SellerScopeView {
  const { orgId, isProvider } = useSeller();
  return useSellerScopeOf(orgId, !isProvider);
}
