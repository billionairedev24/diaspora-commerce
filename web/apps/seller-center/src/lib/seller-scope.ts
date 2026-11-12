'use client';

import type { SellerCatalogScope } from '@afrotransact/api-client';

import { useSeller } from './session';

/** What the screens need to know about the departments the organisation may sell in. */
export interface SellerScopeView {
  /** Not loaded yet. */
  isPending: boolean;
  /** Always false: nothing is read. */
  isError: boolean;
  /** AfroTransact Retail: every department. */
  exempt: boolean;
  departments: SellerCatalogScope['departments'];
  requests: SellerCatalogScope['requests'];
  /** Codes of departments with a request waiting for review. */
  pendingCodes: string[];
  /** Whether the organisation may sell in this department code. */
  allows: (code: string) => boolean;
}

/**
 * Every organisation sells in every department (owner decision): a seller is not limited to the departments it
 * registered for, so this never asks the server and cannot fail. `exempt` is always true, which hides every
 * "request a department" prompt and the Departments settings. The registered departments stay on the seller profile
 * as an optional hint only. Services are the one separate application (see the Services gate).
 */
export function useSellerScopeOf(orgId: string | null | undefined, enabled: boolean): SellerScopeView {
  void orgId;
  void enabled;
  return {
    isPending: false,
    isError: false,
    exempt: true,
    departments: [],
    requests: [],
    pendingCodes: [],
    allows: () => true
  };
}

/** The active organisation's scope (inside the portal shell): always every department. */
export function useSellerScope(): SellerScopeView {
  const { orgId, isProvider } = useSeller();
  return useSellerScopeOf(orgId, !isProvider);
}
