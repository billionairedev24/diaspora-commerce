import { describe, expect, it } from 'vitest';

import { applySyncResponse, backoffMs, canSendAs, emptyOutbox, enqueue, markSosSent, MAX_BATCH, nextBatch, pendingSos, syncStatus } from '../src/domain/outbox';
import { type DriverEventBody } from '../src/domain/types';

const meta = (n: number, ownerId = 'usr_tunde') => ({ eventId: `e${n}`, routeId: 'RT-1', capturedAt: `2026-10-10T19:0${n % 10}:00Z`, lat: 29.7, lon: -95.5, ownerId });
const scan = (label: string): DriverEventBody => ({ type: 'loadout.scanned', scan: { label, method: 'CAMERA' } });

function queue(n: number) {
  let s = emptyOutbox('ios-1');
  for (let i = 1; i <= n; i += 1) s = enqueue(s, scan(`ATS00000000${i % 10}`), meta(i)).state;
  return s;
}

describe('outbox', () => {
  it('assigns increasing, never reused sequence numbers', () => {
    let s = queue(3);
    expect(s.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
    s = applySyncResponse(s, { lastSequence: 3, expectedSequence: 4, results: [] }, 'now').state;
    expect(s.events).toEqual([]);
    s = enqueue(s, scan('ATS000000009'), meta(9)).state;
    expect(s.events[0]?.sequence).toBe(4);
  });

  it('sends in order, in batches of at most 200', () => {
    const s = queue(250);
    const b = nextBatch(s);
    expect(b).toHaveLength(MAX_BATCH);
    expect(b[0]?.sequence).toBe(1);
    expect(b.every((e, i) => i === 0 || e.sequence > (b[i - 1]?.sequence ?? 0))).toBe(true);
  });

  it('drops applied, rejected and conflicting events and reports the issues', () => {
    const s = queue(3);
    const r = applySyncResponse(s, {
      lastSequence: 3,
      expectedSequence: 4,
      results: [
        { eventId: 'e1', sequence: 1, outcome: 'APPLIED' },
        { eventId: 'e2', sequence: 2, outcome: 'REJECTED', code: 'fulfilment.label-unknown', message: 'Label not on the manifest' },
        { eventId: 'e3', sequence: 3, outcome: 'CONFLICT', code: 'fulfilment.stop-reassigned', message: null }
      ]
    }, '2026-10-10T19:10:00Z');
    expect(r.state.events).toEqual([]);
    expect(r.applied).toBe(1);
    expect(r.newIssues.map((i) => [i.outcome, i.code])).toEqual([['REJECTED', 'fulfilment.label-unknown'], ['CONFLICT', 'fulfilment.stop-reassigned']]);
    expect(r.needsRefetch).toBe(true);
    expect(r.state.issues).toHaveLength(2);
  });

  it('keeps deferred events and resends from the expected sequence', () => {
    const s = queue(4);
    const r = applySyncResponse(s, { lastSequence: 1, expectedSequence: 2, results: [{ eventId: 'e1', outcome: 'APPLIED' }, { eventId: 'e3', outcome: 'DEFERRED' }, { eventId: 'e4', outcome: 'DEFERRED' }] }, 'now');
    expect(r.state.events.map((e) => e.sequence)).toEqual([2, 3, 4]);
    expect(r.resendFrom).toBe(2);
    expect(nextBatch(r.state, r.resendFrom ?? 0)[0]?.sequence).toBe(2);
  });

  it('reports a gap when the server expects an event this phone no longer has', () => {
    const s = { ...queue(4), events: queue(4).events.filter((e) => e.sequence > 2) };
    const r = applySyncResponse(s, { lastSequence: 1, expectedSequence: 2, results: [{ eventId: 'e3', outcome: 'DEFERRED' }] }, 'now');
    expect(r.resendFrom).toBeNull();
    expect(r.newIssues[0]?.outcome).toBe('GAP');
  });

  it('surfaces revocation from the sync response', () => {
    const r = applySyncResponse(queue(1), { lastSequence: 1, signOut: true, revokedAt: '2026-10-10T19:00:00Z', results: [{ eventId: 'e1', outcome: 'APPLIED' }] }, 'now');
    expect(r.signOut).toBe(true);
    expect(r.revokedAt).toBe('2026-10-10T19:00:00Z');
  });

  it('raises SOS immediately once', () => {
    let s = enqueue(queue(1), { type: 'sos.raised', sos: 'ACCIDENT' }, meta(7)).state;
    expect(pendingSos(s).map((e) => e.eventId)).toEqual(['e7']);
    s = markSosSent(s, 'e7');
    expect(pendingSos(s)).toEqual([]);
    expect(s.events.some((e) => e.eventId === 'e7')).toBe(true);
  });

  it('shows Synced / Syncing / Offline', () => {
    expect(syncStatus(true, 0, false).label).toBe('Synced');
    expect(syncStatus(true, 3, true).label).toBe('Syncing 3');
    expect(syncStatus(false, 3, false).label).toBe('Offline · 3');
  });

  it('backs off and keeps queued events with their driver', () => {
    expect(backoffMs(0)).toBe(2000);
    expect(backoffMs(10)).toBe(60000);
    const s = queue(1);
    expect(canSendAs(s, 'usr_tunde')).toBe(true);
    expect(canSendAs(s, 'usr_daniel')).toBe(false);
  });
});
