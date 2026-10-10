/* Dev mock of fulfilment-service's driver API, media-service uploads and identity devices (EXPO_PUBLIC_MOCKS=1).
   It keeps its own copy of the route and applies synced events with the same rules as the server README:
   strictly in sequence per device, at most once per eventId, outcomes APPLIED / REJECTED / CONFLICT / DEFERRED. */
import { decodeJwt } from '@/domain/jwt';
import { applyLocal } from '@/domain/routeState';
import { checkScan } from '@/domain/scan';
import { canArrive, fcPackages, stopPackages } from '@/domain/shift';
import { type DriverEventBody, type Route } from '@/domain/types';

import { DEMO_DRIVERS, demoRoute } from './fixtures';

/** Handover codes the customers received by SMS (orders-service verifies them in production). */
const CODES: Record<string, string> = { s3: '4821', s4: '1934', s5: '5507' };

interface MockServer {
  route: Route;
  devices: Record<string, { last: number; outcomes: Record<string, { sequence: number; outcome: string; code: string | null; message: string | null }> }>;
  revoked: Record<string, string>;
  sos: string[];
  /** Last location ping per driver (fulfilment keeps one row per driver). */
  positions: Record<string, { lat: number; lon: number; accuracyM: number | null; recordedAt: string }>;
}

let server: MockServer = fresh();

function fresh(): MockServer {
  return { route: demoRoute(), devices: {}, revoked: {}, sos: [], positions: {} };
}

export function resetMockServer(): void {
  server = fresh();
}

/** Dev/test helper: the last position the mock fulfilment service holds for a driver. */
export function mockDriverPosition(sub: string) {
  return server.positions[sub] ?? null;
}

/** Dev helper: simulate an administrator removing delivery:operate (Backoffice → Access control). */
export function mockRevokeAccess(sub: string): void {
  server.revoked[sub] = new Date().toISOString();
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
/** RFC 9457 problem types share one base in every environment (docs/engineering/environments.md). */
const PROBLEM_TYPE_BASE = 'https://developers.afrotransact.com/problems/';
const problem = (status: number, code: string, title: string, extra: Record<string, unknown> = {}) => json({ type: `${PROBLEM_TYPE_BASE}${code}`, title, status, code, detail: title, ...extra }, status);

function toContract(r: Route) {
  return { ...r, depotNodeId: 'HOU-FC1', loadOutCompletedAt: r.loadOutCompleted ? new Date().toISOString() : undefined, endedAt: r.ended ? new Date().toISOString() : undefined };
}

type Wire = DriverEventBody & { eventId: string; sequence: number; routeId: string; capturedAt: string };

function rule(r: Route, e: Wire): { code: string; message: string } | null {
  const stop = 'stopId' in e ? r.stops.find((s) => s.id === e.stopId) : undefined;
  if ('stopId' in e && !stop) return { code: 'fulfilment.stop-unknown', message: 'That stop is not on your route.' };
  switch (e.type) {
    case 'loadout.scanned': {
      const c = checkScan(e.scan.label, fcPackages(r).map((p) => p.label), fcPackages(r).filter((p) => p.loadScanned).map((p) => p.label));
      return c.ok ? null : { code: c.message.includes('already') ? 'fulfilment.label-already-scanned' : 'fulfilment.label-unknown', message: c.message };
    }
    case 'route.started':
      return r.blocked ? { code: 'fulfilment.route-blocked', message: 'The route is blocked by a vehicle defect.' } : null;
    case 'stop.arrived':
      return stop ? (canArrive(r, stop) ? { code: 'fulfilment.pickup-first', message: canArrive(r, stop) ?? '' } : null) : null;
    case 'stop.scanned': {
      if (!stop) return null;
      const pk = stopPackages(r, stop);
      const scanned = pk.filter((p) => (stop.kind === 'PICKUP' ? p.pickupScanned : p.dropScanned)).map((p) => p.label);
      const c = checkScan(e.scan.label, pk.map((p) => p.label), scanned);
      return c.ok ? null : { code: 'fulfilment.label-unknown', message: c.message };
    }
    case 'delivery.completed':
      if (e.delivery.handoverMethod === 'CUSTOMER' && e.delivery.handoverCode !== CODES[e.stopId]) return { code: 'orders.handover-code-invalid', message: 'That code doesn’t match. Ask for the 4-digit code from their SMS.' };
      if (e.delivery.handoverMethod === 'SAFE_PLACE' && stop && (!stop.safePlaceAllowed || stopPackages(r, stop).some((p) => p.perishable))) return { code: 'orders.safe-place-not-allowed', message: 'Safe place isn’t allowed for this delivery.' };
      return null;
    default:
      return null;
  }
}

function sync(sub: string, body: { deviceId: string; events: Wire[] }) {
  const dev = (server.devices[body.deviceId] ??= { last: 0, outcomes: {} });
  const revokedAt = server.revoked[sub] ?? null;
  const results: Array<Record<string, unknown>> = [];
  const events = body.events.slice().sort((a, b) => a.sequence - b.sequence);
  for (const e of events) {
    const known = dev.outcomes[e.eventId];
    if (known) { results.push({ eventId: e.eventId, ...known, replayed: true, forwardPending: false }); continue; }
    if (e.sequence <= dev.last) { results.push({ eventId: e.eventId, sequence: e.sequence, outcome: 'CONFLICT', code: 'fulfilment.sequence-reused', message: 'Sequence number already used.' }); continue; }
    if (e.sequence !== dev.last + 1) { results.push({ eventId: e.eventId, sequence: e.sequence, outcome: 'DEFERRED', code: 'fulfilment.sequence-gap', message: null }); continue; }
    let outcome = 'APPLIED';
    let code: string | null = null;
    let message: string | null = null;
    if (revokedAt && e.capturedAt > revokedAt) { outcome = 'REJECTED'; code = 'fulfilment.driver-access-revoked'; message = 'Driver access was removed before this action.'; }
    else if (e.routeId !== server.route.id) { outcome = 'CONFLICT'; code = 'fulfilment.route-reassigned'; message = 'This route is no longer assigned to you.'; }
    else {
      const broken = rule(server.route, e);
      if (broken) { outcome = 'REJECTED'; code = broken.code; message = broken.message; }
      else server.route = applyLocal(server.route, e, e.capturedAt);
    }
    dev.last = e.sequence;
    dev.outcomes[e.eventId] = { sequence: e.sequence, outcome, code, message };
    results.push({ eventId: e.eventId, sequence: e.sequence, outcome, code, message, replayed: false, forwardPending: false });
  }
  return { deviceId: body.deviceId, lastSequence: dev.last, expectedSequence: dev.last + 1, signOut: !!revokedAt, revokedAt, results };
}

const latency = () => new Promise((r) => setTimeout(r, 150));

export async function mockFetch(req: Request): Promise<Response> {
  await latency();
  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/(fulfilment|media|identity)(?=\/)/, '');
  const claims = decodeJwt((req.headers.get('Authorization') ?? '').replace(/^Bearer /, ''));
  if (!claims?.sub) return problem(401, 'UNAUTHENTICATED', 'Sign in required');
  const text = req.method === 'GET' ? '' : await req.text();
  const body: unknown = text ? JSON.parse(text) : undefined;
  const driver = DEMO_DRIVERS.find((d) => d.sub === claims.sub);
  const allowed = !!driver?.perms.includes('delivery:operate');
  const grantHint = 'A developer or super admin can grant delivery:operate in Backoffice → Access control, either by giving you the Driver role or as a direct permission.';

  if (path === '/v1/driver/me' && req.method === 'GET') {
    if (server.revoked[claims.sub]) return problem(403, 'fulfilment.driver-access-revoked', 'Driver access revoked', { signOut: true, revokedAt: server.revoked[claims.sub], grantHint });
    if (!allowed) return problem(403, 'fulfilment.no-driver-access', 'No driver access', { grantHint });
    return json({ driverId: `drv_${claims.sub}`, access: 'ACTIVE', permissions: driver?.perms ?? [], routeIds: [server.route.id], checkedAt: new Date().toISOString() });
  }
  if (path === '/v1/driver/routes' && req.method === 'GET') {
    if (!allowed || server.revoked[claims.sub]) return problem(403, 'fulfilment.no-driver-access', 'No driver access', { grantHint });
    return json([toContract(server.route)]);
  }
  if (path === '/v1/driver/sync' && req.method === 'POST') return json(sync(claims.sub, body as { deviceId: string; events: Wire[] }));
  if (path === '/v1/driver/sos' && req.method === 'POST') {
    const e = (body as { event: Wire }).event;
    if (!server.sos.includes(e.eventId)) server.sos.push(e.eventId);
    return json({ eventId: e.eventId, sequence: e.sequence, outcome: 'APPLIED', replayed: false });
  }
  if (path === '/v1/driver/location' && req.method === 'POST') {
    if (!allowed || server.revoked[claims.sub]) return problem(403, 'fulfilment.no-driver-access', 'No driver access', { grantHint });
    const p = (body ?? {}) as { lat?: unknown; lon?: unknown; accuracyM?: unknown; capturedAt?: unknown };
    if (typeof p.lat !== 'number' || typeof p.lon !== 'number' || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) {
      return problem(400, 'VALIDATION_FAILED', 'lat and lon are required');
    }
    const at = typeof p.capturedAt === 'string' ? p.capturedAt : new Date().toISOString();
    server.positions[claims.sub] = { lat: p.lat, lon: p.lon, accuracyM: typeof p.accuracyM === 'number' ? p.accuracyM : null, recordedAt: at };
    return json({ driverId: `drv_${claims.sub}`, lat: p.lat, lon: p.lon, ...(typeof p.accuracyM === 'number' ? { accuracyM: p.accuracyM } : {}), recordedAt: at, source: 'PING' });
  }
  if (path === '/v1/uploads' && req.method === 'POST') {
    const id = `00000000-0000-4000-8000-${String(Date.now()).slice(-12).padStart(12, '0')}`;
    return json({ asset: { id, status: 'PENDING', purpose: 'proof-photo' }, upload: { method: 'PUT', url: `https://uploads.mock/${id}`, headers: {} } }, 201);
  }
  if (/^\/v1\/assets\/[^/]+\/complete$/.test(path) && req.method === 'POST') return json({ id: path.split('/')[3], status: 'READY' });
  if (path === '/v1/me/devices' && req.method === 'POST') return allowed ? json({ id: 'dev-1', app: 'driver' }, 201) : problem(403, 'identity.permission-required', 'delivery:operate required');
  if (path === '/v1/me/devices' && req.method === 'DELETE') return new Response(null, { status: 204 });
  return problem(404, 'NOT_FOUND', `No mock for ${req.method} ${path}`);
}
