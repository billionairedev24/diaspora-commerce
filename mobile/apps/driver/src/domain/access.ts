/* S081 access check: GET /v1/driver/me → 200 (has delivery:operate) or 403 problems. */

export const DEFAULT_GRANT_HINT = 'A developer or super admin can grant delivery:operate in Backoffice → Access control, either by giving you the Driver role or as a direct permission.';

export type AccessDecision =
  | { kind: 'ok'; driverId: string; routeIds: string[]; permissions: string[] }
  | { kind: 'denied'; message: string; grantHint: string }
  | { kind: 'revoked'; message: string; revokedAt: string | null; grantHint: string }
  | { kind: 'unknown'; message: string };

export interface ProblemLike {
  status?: number;
  code?: string;
  detail?: string;
  title?: string;
  grantHint?: string;
  revokedAt?: string;
  signOut?: boolean;
}

export function decideAccess(status: number, body: unknown, displayName: string): AccessDecision {
  if (status === 200 && body && typeof body === 'object') {
    const b = body as { driverId?: string; routeIds?: string[]; permissions?: string[] };
    if ((b.permissions ?? ['delivery:operate']).includes('delivery:operate')) return { kind: 'ok', driverId: b.driverId ?? '', routeIds: b.routeIds ?? [], permissions: b.permissions ?? [] };
  }
  const p = (body && typeof body === 'object' ? body : {}) as ProblemLike;
  if (status === 403 && (p.code === 'fulfilment.driver-access-revoked' || p.signOut)) {
    return { kind: 'revoked', message: 'Your driver access was just removed by an administrator. Unsynced events were kept on this device.', revokedAt: p.revokedAt ?? null, grantHint: p.grantHint ?? DEFAULT_GRANT_HINT };
  }
  if (status === 403 || status === 200) {
    return { kind: 'denied', message: `${displayName} is signed in, but doesn’t have the “Use the driver app” permission.`, grantHint: p.grantHint ?? DEFAULT_GRANT_HINT };
  }
  return { kind: 'unknown', message: p.detail ?? p.title ?? 'Couldn’t check your driver access.' };
}

/** Tokens carry `perms`; the app can pre-check before calling /me (the server decides). */
export function tokenHasDriverPermission(perms: string[] | undefined): boolean {
  return (perms ?? []).includes('delivery:operate');
}
