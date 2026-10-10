'use client';

import { type AccessLevel, type StaffModuleKey, type StaffNavModule, type StaffSession } from '@afrotransact/api-client';
import { createContext, type ReactNode, useContext, useMemo } from 'react';

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

export interface StaffContextValue {
  session: StaffSession;
  /** The staff member's level on a module (null when their roles have no access). */
  level: (key: StaffModuleKey) => AccessLevel | null;
  /** Whether the roles reach at least `min` on a module (default VIEW). */
  can: (key: StaffModuleKey, min?: AccessLevel) => boolean;
  /** Whether the staff member holds a permission (the upstream checks it again). */
  hasPermission: (permission: string) => boolean;
  module: (key: StaffModuleKey) => StaffNavModule | null;
}

const Ctx = createContext<StaffContextValue | null>(null);

export function buildStaffContext(session: StaffSession): StaffContextValue {
  const modules = new Map(session.navGroups.flatMap((g) => g.modules).map((m) => [m.key, m]));
  const perms = new Set(session.permissions);
  const level = (key: StaffModuleKey) => modules.get(key)?.level ?? null;
  return {
    session,
    level,
    can: (key, min = 'VIEW') => {
      const lv = level(key);
      return lv !== null && RANK[lv] >= RANK[min];
    },
    hasPermission: (p) => perms.has(p),
    module: (key) => modules.get(key) ?? null
  };
}

export function StaffSessionProvider({ session, children }: { session: StaffSession; children: ReactNode }) {
  const value = useMemo(() => buildStaffContext(session), [session]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** The signed-in staff member, their roles and module access (inside the console shell). */
export function useStaff(): StaffContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useStaff must be used inside the console shell');
  return v;
}
