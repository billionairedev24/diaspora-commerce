'use client';

import { type AccessLevel, type ModuleKey, type NavModule, type PlanCapability, type SellerSession } from '@afrotransact/api-client';
import { createContext, type ReactNode, useContext, useMemo } from 'react';

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

export interface SellerContextValue {
  session: SellerSession;
  orgId: string;
  isProvider: boolean;
  /** The member's level on a module (null when the role has no access). */
  level: (key: ModuleKey) => AccessLevel | null;
  /** Whether the role can change (EDIT or more) or at least view a module. */
  can: (key: ModuleKey, min?: AccessLevel) => boolean;
  module: (key: ModuleKey) => NavModule | null;
  /** Whether the organisation's plan includes a capability; what it does not include is not shown. */
  has: (capability: PlanCapability) => boolean;
}

const Ctx = createContext<SellerContextValue | null>(null);

export function buildSellerContext(session: SellerSession): SellerContextValue {
  const modules = new Map(session.navGroups.flatMap((g) => g.modules).map((m) => [m.key, m]));
  const level = (key: ModuleKey) => modules.get(key)?.level ?? null;
  return {
    session,
    orgId: session.activeOrg?.id ?? 'none',
    isProvider: session.portal === 'PROVIDER_HUB',
    level,
    can: (key, min = 'EDIT') => {
      const lv = level(key);
      return lv !== null && RANK[lv] >= RANK[min];
    },
    module: (key) => modules.get(key) ?? null,
    has: (capability) => session.capabilities.includes(capability)
  };
}

export function SellerSessionProvider({ session, children }: { session: SellerSession; children: ReactNode }) {
  const value = useMemo(() => buildSellerContext(session), [session]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** The signed-in member's session, active organisation and module access (inside the portal shell). */
export function useSeller(): SellerContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSeller must be used inside the portal shell');
  return v;
}
