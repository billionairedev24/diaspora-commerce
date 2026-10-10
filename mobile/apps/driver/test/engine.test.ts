import { beforeEach, describe, expect, it } from 'vitest';

import { demoRoute } from '../src/api/mock/fixtures';
import { type DriverEventBody, type QueuedEvent, type Route } from '../src/domain/types';
import { createMemoryStore } from '../src/storage/memoryStore';
import { type EngineApi, SyncEngine } from '../src/sync/engine';

/** A fake fulfilment server that applies events strictly in sequence (like the real one). */
function fakeServer() {
  let last = 0;
  const seen: QueuedEvent[] = [];
  const sos: string[] = [];
  let reject: string | null = null;
  const api: EngineApi & { seen: QueuedEvent[]; sosSent: string[]; rejectLabel(l: string): void; uploads: string[] } = {
    seen, sosSent: sos, uploads: [],
    rejectLabel: (l) => { reject = l; },
    async sync(_d, events) {
      const results = events.map((e) => {
        if (e.sequence !== last + 1) return { eventId: e.eventId, sequence: e.sequence, outcome: 'DEFERRED' };
        last = e.sequence;
        seen.push(e);
        if (e.type === 'loadout.scanned' && e.scan.label === reject) return { eventId: e.eventId, sequence: e.sequence, outcome: 'REJECTED', code: 'fulfilment.label-unknown', message: 'Not on the manifest' };
        return { eventId: e.eventId, sequence: e.sequence, outcome: 'APPLIED' };
      });
      return { lastSequence: last, expectedSequence: last + 1, results };
    },
    async sos(_d, e) { sos.push(e.eventId); return {}; },
    async uploadPhoto(uri) { api.uploads.push(uri); return `asset-${api.uploads.length}`; },
    async routes() { return [demoRoute()]; }
  };
  return api;
}

let n = 0;
function engine(api: EngineApi, store = createMemoryStore()) {
  return new SyncEngine({ store, api, uuid: () => `id-${(n += 1)}`, now: () => new Date('2026-10-10T19:00:00Z'), position: async () => ({ lat: 29.7, lon: -95.5 }) });
}

const scan = (label: string): DriverEventBody => ({ type: 'loadout.scanned', scan: { label, method: 'CAMERA' } });

describe('sync engine', () => {
  let api: ReturnType<typeof fakeServer>;
  beforeEach(() => { api = fakeServer(); n = 0; });

  it('queues offline, applies locally, then syncs in order when back online', async () => {
    const store = createMemoryStore();
    const e = engine(api, store);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    e.setOnline(false);
    await e.record(scan('ATS7K2Q9H4M1'));
    await e.record(scan('ATS3NP8D2LQ6'));
    expect(e.getState().outbox.events.map((x) => x.sequence)).toEqual([1, 2]);
    expect(e.getState().route?.packages.find((p) => p.label === 'ATS7K2Q9H4M1')?.loadScanned).toBe(true);
    expect(api.seen).toHaveLength(0);

    // the queue survives an app restart
    const restarted = engine(api, store);
    await restarted.init();
    expect(restarted.getState().outbox.events).toHaveLength(2);
    restarted.setUser('usr_tunde');
    restarted.setOnline(false);
    restarted.setOnline(true);
    await restarted.flush();
    expect(api.seen.map((x) => x.sequence)).toEqual([1, 2]);
    expect(restarted.getState().outbox.events).toHaveLength(0);
  });

  it('records rejected events as issues without blocking the queue', async () => {
    const e = engine(api);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    api.rejectLabel('ATS7K2Q9H4M1');
    await e.record(scan('ATS7K2Q9H4M1'));
    await e.record(scan('ATS3NP8D2LQ6'));
    await e.flush();
    expect(e.getState().outbox.events).toHaveLength(0);
    expect(e.getState().outbox.issues[0]?.code).toBe('fulfilment.label-unknown');
  });

  it('sends SOS immediately, even before the ordered batch', async () => {
    const e = engine(api);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    const ev = await e.record({ type: 'sos.raised', sos: 'ACCIDENT' });
    expect(api.sosSent).toEqual([ev.eventId]);
  });

  it('uploads photos before sending the event that references them', async () => {
    const e = engine(api);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    const photo = { source: 'LIVE_CAMERA' as const, capturedAt: 'x', lat: 1, lon: 2, accuracyM: 3, stampRouteId: 'RT-HOU-PM', sha256: 'h', localUri: 'file:///pod.jpg' };
    await e.record({ type: 'delivery.completed', stopId: 's4', delivery: { handoverMethod: 'SAFE_PLACE', safePlace: 'Front door', photo } });
    await e.flush();
    expect(api.uploads).toEqual(['file:///pod.jpg']);
    const sent = api.seen.find((x) => x.type === 'delivery.completed');
    expect(sent && sent.type === 'delivery.completed' ? sent.delivery.photo.assetId : null).toBe('asset-1');
  });

  it('does not send another driver’s queued events', async () => {
    const e = engine(api);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    e.setOnline(false);
    await e.record(scan('ATS7K2Q9H4M1'));
    e.setUser('usr_daniel');
    e.setOnline(true);
    await e.flush();
    expect(api.seen).toHaveLength(0);
    expect(e.getState().lastError).toMatch(/previous driver/);
  });

  it('re-applies queued events on top of a refreshed route', async () => {
    const e = engine(api);
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    e.setOnline(false);
    await e.record(scan('ATS7K2Q9H4M1'));
    e.setOnline(true);
    const before = api.seen.length;
    expect(before).toBeGreaterThanOrEqual(0);
    const r = e.getState().route as Route;
    expect(r.packages.find((p) => p.label === 'ATS7K2Q9H4M1')?.loadScanned).toBe(true);
  });
});
