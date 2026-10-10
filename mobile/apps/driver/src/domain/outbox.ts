/* S087 offline-first outbox (pure; unit-tested). Every action becomes an event with a client-generated eventId and a
   per-device sequence number (1, 2, 3 … never reused; the counter is persisted with the queue). Events are sent in
   sequence order in batches of ≤ 200 and handled per result:
   APPLIED → done · REJECTED → done + shown to the driver (fix and queue a new event) · CONFLICT → done + route refetch
   · DEFERRED → an earlier sequence is missing on the server: resend from expectedSequence.
   Everything up to the server's lastSequence leaves the outbox; the queue never blocks on a rejected event. */
import { type DriverEventBody, type QueuedEvent, type SyncOutcome } from './types';

export const MAX_BATCH = 200;

export interface SyncIssue {
  eventId: string;
  sequence: number;
  type: string;
  outcome: Extract<SyncOutcome, 'REJECTED' | 'CONFLICT'> | 'GAP';
  code: string | null;
  message: string;
  at: string;
}

export interface OutboxState {
  deviceId: string;
  /** Driver (token `sub`) whose events are queued; another driver on this phone cannot send them. */
  ownerId: string | null;
  nextSequence: number;
  events: QueuedEvent[];
  issues: SyncIssue[];
  /** Event ids already raised through POST /v1/driver/sos. */
  sosSent: string[];
  lastSyncAt: string | null;
}

export function emptyOutbox(deviceId: string): OutboxState {
  return { deviceId, ownerId: null, nextSequence: 1, events: [], issues: [], sosSent: [], lastSyncAt: null };
}

export interface CaptureMeta {
  eventId: string;
  routeId: string;
  capturedAt: string;
  lat: number | null;
  lon: number | null;
  ownerId: string;
  note?: string;
}

/** Appends an event with the next sequence number. */
export function enqueue(state: OutboxState, body: DriverEventBody, meta: CaptureMeta): { state: OutboxState; event: QueuedEvent } {
  const event: QueuedEvent = { ...body, eventId: meta.eventId, sequence: state.nextSequence, routeId: meta.routeId, capturedAt: meta.capturedAt, lat: meta.lat, lon: meta.lon, ...(meta.note ? { note: meta.note } : {}) };
  return {
    event,
    state: { ...state, ownerId: state.events.length ? (state.ownerId ?? meta.ownerId) : meta.ownerId, nextSequence: state.nextSequence + 1, events: [...state.events, event].sort((a, b) => a.sequence - b.sequence) }
  };
}

/** The next batch to send: queued events in sequence order (resend from `fromSequence` after a DEFERRED). */
export function nextBatch(state: OutboxState, fromSequence = 0, max = MAX_BATCH): QueuedEvent[] {
  return state.events.filter((e) => e.sequence >= fromSequence).sort((a, b) => a.sequence - b.sequence).slice(0, max);
}

export interface EventResult {
  eventId?: string;
  sequence?: number;
  outcome?: string;
  code?: string | null;
  message?: string | null;
  replayed?: boolean;
  forwardPending?: boolean;
}

export interface SyncResponse {
  lastSequence?: number;
  expectedSequence?: number;
  signOut?: boolean;
  revokedAt?: string | null;
  results?: EventResult[];
}

export interface SyncApplication {
  state: OutboxState;
  newIssues: SyncIssue[];
  /** Resend from here (DEFERRED: the server is missing an earlier sequence). */
  resendFrom: number | null;
  signOut: boolean;
  revokedAt: string | null;
  /** A rejection or conflict: refetch the route from the server once the queue drains. */
  needsRefetch: boolean;
  applied: number;
}

export function applySyncResponse(state: OutboxState, res: SyncResponse, now: string): SyncApplication {
  const last = res.lastSequence ?? 0;
  const byId = new Map(state.events.map((e) => [e.eventId, e]));
  const newIssues: SyncIssue[] = [];
  let applied = 0;
  let deferred = false;
  for (const r of res.results ?? []) {
    const outcome = (r.outcome ?? '').toUpperCase();
    const ev = r.eventId ? byId.get(r.eventId) : undefined;
    if (outcome === 'APPLIED') applied += 1;
    if (outcome === 'DEFERRED') deferred = true;
    if (outcome === 'REJECTED' || outcome === 'CONFLICT') {
      newIssues.push({
        eventId: r.eventId ?? '',
        sequence: r.sequence ?? ev?.sequence ?? 0,
        type: ev?.type ?? 'unknown',
        outcome,
        code: r.code ?? null,
        message: r.message ?? (outcome === 'CONFLICT' ? 'The route changed on the server (for example, dispatch reassigned this stop).' : 'The server refused this action.'),
        at: now
      });
    }
  }
  const remaining = state.events.filter((e) => e.sequence > last);
  const expected = res.expectedSequence ?? last + 1;
  // The server expects a sequence we no longer hold (e.g. app reinstalled): report it instead of looping forever.
  const gap = deferred && remaining.length > 0 && !remaining.some((e) => e.sequence === expected) && (remaining[0]?.sequence ?? 0) > expected;
  if (gap) {
    newIssues.push({ eventId: '', sequence: expected, type: 'sync', outcome: 'GAP', code: 'fulfilment.sequence-gap', message: `Dispatch is missing event #${expected} from this phone. Contact dispatch.`, at: now });
  }
  return {
    state: { ...state, events: remaining, issues: [...newIssues, ...state.issues].slice(0, 50), lastSyncAt: now },
    newIssues,
    resendFrom: deferred && !gap && remaining.length ? expected : null,
    signOut: !!res.signOut,
    revokedAt: res.revokedAt ?? null,
    needsRefetch: newIssues.some((i) => i.outcome !== 'GAP'),
    applied
  };
}

/** SOS events go out immediately through POST /v1/driver/sos (and stay in the outbox with the same eventId). */
export function pendingSos(state: OutboxState): QueuedEvent[] {
  return state.events.filter((e) => e.type === 'sos.raised' && !state.sosSent.includes(e.eventId));
}

export function markSosSent(state: OutboxState, eventId: string): OutboxState {
  return state.sosSent.includes(eventId) ? state : { ...state, sosSent: [...state.sosSent, eventId].slice(-50) };
}

export type SyncStatus = { kind: 'synced' | 'syncing' | 'offline'; label: string; pending: number };

/** Header status (design: "Synced" / "Syncing 3" / "Offline · 3"). */
export function syncStatus(online: boolean, pending: number, syncing: boolean): SyncStatus {
  if (!online) return { kind: 'offline', label: `Offline · ${pending}`, pending };
  if (pending > 0 || syncing) return { kind: 'syncing', label: pending ? `Syncing ${pending}` : 'Syncing', pending };
  return { kind: 'synced', label: 'Synced', pending };
}

/** Retry delay after a failed sync attempt: 2 s, 4 s, 8 s … capped at 60 s. */
export function backoffMs(attempt: number): number {
  return Math.min(60_000, 2_000 * 2 ** Math.max(0, attempt));
}

/** Only the driver who captured the queued events may send them (their token, even after revocation). */
export function canSendAs(state: OutboxState, userId: string): boolean {
  return state.ownerId === null || state.ownerId === userId || state.events.length === 0;
}
