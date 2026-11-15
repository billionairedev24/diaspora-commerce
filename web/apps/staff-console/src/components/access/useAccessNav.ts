'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';

export type AccessView = 'roles' | 'people' | 'log';

/** The address of Roles and permissions: which tab (`view`), the portal, the open role or person, and the person a log is for. */
export function useAccessNav() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const raw = params.get('view');
  const view: AccessView = raw === 'people' || raw === 'log' ? raw : 'roles';
  /** Keeps the other parameters (a table's search, sort and page) unless a key is set to null. */
  const go = (patch: Record<string, string | null>, replace = true) => {
    const next = new URLSearchParams(params.toString());
    Object.entries(patch).forEach(([k, v]) => (v === null ? next.delete(k) : next.set(k, v)));
    const href = `${pathname}${next.size ? `?${next.toString()}` : ''}`;
    if (replace) router.replace(href, { scroll: false });
    else router.push(href, { scroll: false });
  };
  return { view, portal: params.get('portal'), role: params.get('role'), person: params.get('person'), log: params.get('log'), go };
}
