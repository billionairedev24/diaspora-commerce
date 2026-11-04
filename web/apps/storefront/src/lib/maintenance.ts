import 'server-only';

import { bffOrigin, mocksEnabled } from './config';

/** What GET /v1/maintenance answers (contracts/openapi/gateway-bff.yaml). */
export interface MaintenanceStatus {
  active: boolean;
  scope?: 'SHOPPERS' | 'ALL';
  title?: string;
  message?: string;
  expectedBackAt?: string;
}

/**
 * The platform's maintenance status, or null when it is not in maintenance. Never throws and never waits long: if the
 * BFF cannot be asked, the page is served normally (the BFF itself refuses calls while in maintenance).
 */
export async function maintenanceNow(): Promise<MaintenanceStatus | null> {
  if (mocksEnabled()) return null;
  try {
    const res = await fetch(`${bffOrigin()}/v1/maintenance`, { cache: 'no-store', signal: AbortSignal.timeout(1500) });
    if (!res.ok) return null;
    const s = (await res.json()) as MaintenanceStatus;
    return s.active ? s : null;
  } catch {
    return null;
  }
}
