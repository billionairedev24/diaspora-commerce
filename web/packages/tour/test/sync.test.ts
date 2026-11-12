import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createMemoryProgressStore, type LegacyProgress, type RemoteTourProgress, type RemoteTourSnapshot, type TourProgressRemote } from '../src/progress';
import { defineTour, type Tour } from '../src/schema';
import { createTourService, DO_IT_ADVANCE_MS, STEP_SAVE_DELAY_MS, SYNC_RETRY_MS } from '../src/service';
import { createFakeEngine, createFakeEnvironment, TEST_LABELS } from '../src/testing';

const subject = { userId: 'u1', roles: [], permissions: [], flags: [] };

/** A server with the same rules as identity, that can be made to fail. */
function fakeServer(initial: RemoteTourProgress[] = []) {
  const rows = new Map<string, RemoteTourProgress>(initial.map((r) => [r.tourId, r]));
  const calls: string[] = [];
  const state = { down: false, autoStart: true };
  const guard = () => {
    if (state.down) throw new Error('offline');
  };
  const apply = (r: RemoteTourProgress) => {
    const have = rows.get(r.tourId);
    const final = have && have.status !== 'IN_PROGRESS';
    if (have && (r.contentVersion < have.contentVersion || (r.contentVersion === have.contentVersion && final && r.status === 'IN_PROGRESS'))) return have;
    rows.set(r.tourId, { ...r, updatedAt: 'server-time' });
    return rows.get(r.tourId) as RemoteTourProgress;
  };
  const snapshot = (): RemoteTourSnapshot => ({ items: [...rows.values()], autoStartEnabled: state.autoStart });
  const remote: TourProgressRemote = {
    load: async () => {
      calls.push('load');
      guard();
      return snapshot();
    },
    save: async (r) => {
      calls.push(`save ${r.tourId} ${r.status} v${r.contentVersion}`);
      guard();
      return apply(r);
    },
    merge: async (items) => {
      calls.push(`merge ${items.map((i) => `${i.tourId}:${i.status}`).join(',')}`);
      guard();
      for (const r of items) {
        const have = rows.get(r.tourId);
        if (!have || r.contentVersion > have.contentVersion) apply(r);
      }
      return snapshot();
    },
    reset: async (id) => {
      calls.push(`reset ${id ?? 'all'}`);
      guard();
      if (id) rows.delete(id);
      else rows.clear();
    }
  };
  return { rows, calls, state, remote };
}

function tour(over: Partial<Parameters<typeof defineTour>[0]> = {}): Tour {
  return defineTour({
    id: 'products',
    version: 1,
    title: 'x.t.title',
    summary: 'x.t.summary',
    autoStart: { routes: ['/listings'] },
    steps: [
      { id: 'header', target: 'page-listings', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'add', target: 'add-product', title: 'x.s2.title', body: 'x.s2.body' },
      { id: 'table', target: 'table-listings', title: 'x.s3.title', body: 'x.s3.body' }
    ],
    ...over
  });
}

function setup(server: ReturnType<typeof fakeServer>, opts: { tours?: Tour[]; legacy?: Record<string, LegacyProgress[]> } = {}) {
  const engine = createFakeEngine();
  const env = createFakeEnvironment(['page-listings', 'add-product', 'table-listings']);
  const store = createMemoryProgressStore({ legacy: opts.legacy });
  let path = '/listings';
  const service = createTourService({
    engine,
    env,
    store,
    remote: server.remote,
    namespace: 'sc',
    subject: () => subject,
    translate: (k) => `T(${k})`,
    labels: () => TEST_LABELS,
    navigate: (r) => (path = r),
    pathname: () => path
  });
  service.register(opts.tours ?? [tour()]);
  return { engine, env, store, service };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('server-side progress', () => {
  it('loads the server copy BEFORE a first-time tour may start, so nothing flashes and disappears', async () => {
    const server = fakeServer([{ tourId: 'sc.products', contentVersion: 1, status: 'COMPLETED', lastStep: null }]);
    const { service, engine } = setup(server);
    // not loaded yet: nothing starts by itself
    expect(service.getSync()).toBe('idle');
    expect(await service.autoStart()).toBeUndefined();
    expect(await service.sync()).toBe(true);
    // finished on another device: it does not start here either
    expect(await service.autoStart()).toBeUndefined();
    expect(engine.shown).toHaveLength(0);
    expect(service.info('products').status).toBe('completed');
  });

  it('a tour nobody has seen starts after the load and is recorded on the server at once', async () => {
    const server = fakeServer();
    const { service } = setup(server);
    await service.sync();
    expect((await service.autoStart())?.id).toBe('products');
    await vi.advanceTimersByTimeAsync(0);
    expect(server.rows.get('sc.products')).toMatchObject({ status: 'IN_PROGRESS', lastStep: 'header' });
  });

  it('fails quiet: when the server cannot be reached nothing starts by itself, and the load is retried later', async () => {
    const server = fakeServer();
    server.state.down = true;
    const { service, engine } = setup(server);
    expect(await service.sync()).toBe(false);
    expect(service.getSync()).toBe('failed');
    expect(await service.autoStart()).toBeUndefined();
    expect(engine.shown).toHaveLength(0);
    server.state.down = false;
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[0]);
    expect(service.getSync()).toBe('ready');
    expect((await service.autoStart())?.id).toBe('products');
  });

  it('retries with growing waits while the server stays down', async () => {
    const server = fakeServer();
    server.state.down = true;
    const { service } = setup(server);
    await service.sync();
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[0]);
    expect(server.calls.filter((c) => c === 'load')).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[0]);
    expect(server.calls.filter((c) => c === 'load')).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[1] - SYNC_RETRY_MS[0]);
    expect(server.calls.filter((c) => c === 'load')).toHaveLength(3);
    service.destroy();
  });

  it('the server is the truth: the cache takes its copy, and drops what the server no longer has', async () => {
    const server = fakeServer([{ tourId: 'sc.products', contentVersion: 1, status: 'DISMISSED', lastStep: 'add' }]);
    const { service, store } = setup(server);
    store.set('u1', 'products', { status: 'completed', version: 1, at: 'old' });
    store.set('u1', 'orders', { status: 'completed', version: 1, at: 'old' });
    await service.sync();
    expect(store.get('u1', 'products')).toMatchObject({ status: 'dismissed', lastStep: 'add' });
    // reset on another device: gone here too
    expect(store.get('u1', 'orders')).toBeUndefined();
  });

  it('a platform flag can turn automatic tours off for everyone; manual start and replay still work', async () => {
    const server = fakeServer();
    server.state.autoStart = false;
    const { service } = setup(server);
    await service.sync();
    expect(service.autoStartEnabled()).toBe(false);
    expect(await service.autoStart()).toBeUndefined();
    expect(await service.start('products')).toBe('started');
  });

  it('copies what this browser kept to the server once, without overwriting what the server holds', async () => {
    const server = fakeServer([{ tourId: 'sc.orders', contentVersion: 1, status: 'COMPLETED', lastStep: null }]);
    const legacy = {
      u1: [
        { tourId: 'products', version: 1, outcome: 'never' as const, at: 't' },
        { tourId: 'orders', version: 1, outcome: 'skipped' as const, at: 't' }
      ]
    };
    const { service, store } = setup(server, { tours: [tour(), tour({ id: 'orders', autoStart: undefined })], legacy });
    await service.sync();
    expect(server.calls.filter((c) => c.startsWith('merge'))).toHaveLength(1);
    expect(server.rows.get('sc.products')?.status).toBe('DISMISSED');
    expect(server.rows.get('sc.orders')?.status).toBe('COMPLETED');
    expect(store.legacy('u1')).toEqual([]);
    expect(store.migrated('u1')).toBe(true);
    // a later sync does not upload again
    const again = setup(server, { legacy });
    again.store.markMigrated('u1');
    await again.service.sync();
    expect(server.calls.filter((c) => c.startsWith('merge'))).toHaveLength(1);
  });

  it('a failed upload is not marked as done, so the next load tries again', async () => {
    const server = fakeServer();
    const legacy = { u1: [{ tourId: 'products', version: 1, outcome: 'completed' as const, at: 't' }] };
    const { service, store } = setup(server, { legacy });
    const merge = server.remote.merge;
    server.remote.merge = async () => {
      throw new Error('boom');
    };
    expect(await service.sync()).toBe(false);
    expect(store.migrated('u1')).toBe(false);
    server.remote.merge = merge;
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[0]);
    expect(server.rows.get('sc.products')?.status).toBe('COMPLETED');
  });

  it('completion and "Don\'t show again" are saved to the server and survive a new browser', async () => {
    const server = fakeServer();
    const a = setup(server);
    await a.service.sync();
    await a.service.start('products');
    a.engine.callbacks?.setDontShowAgain(true);
    a.service.close();
    await vi.advanceTimersByTimeAsync(0);
    expect(server.rows.get('sc.products')?.status).toBe('DISMISSED');

    const other = setup(server);
    await other.service.sync();
    expect(await other.service.autoStart()).toBeUndefined();
    expect(other.service.info('products').status).toBe('dismissed');

    // Replay always works
    expect(await other.service.start('products')).toBe('started');
  });

  it('steps are saved together a moment after the person moves on, not once per step', async () => {
    const server = fakeServer();
    const { service } = setup(server);
    await service.sync();
    await service.start('products');
    await service.next();
    await service.next();
    expect(server.calls.filter((c) => c.startsWith('save'))).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(STEP_SAVE_DELAY_MS);
    expect(server.calls.filter((c) => c.startsWith('save'))).toEqual(['save sc.products IN_PROGRESS v1']);
    expect(server.rows.get('sc.products')?.lastStep).toBe('table');
  });

  it('a write that could not be sent stays pending and goes out at the next load', async () => {
    const server = fakeServer();
    const { service, store } = setup(server);
    await service.sync();
    server.state.down = true;
    await service.start('products');
    service.skip();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.get('u1', 'products')).toMatchObject({ status: 'dismissed', dirty: true });
    expect(server.rows.get('sc.products')).toBeUndefined();
    server.state.down = false;
    await vi.advanceTimersByTimeAsync(SYNC_RETRY_MS[0]);
    expect(server.rows.get('sc.products')?.status).toBe('DISMISSED');
    expect(store.get('u1', 'products')?.dirty).toBeUndefined();
  });

  it('a new content version is a "What\'s new" mark, never an automatic start', async () => {
    const server = fakeServer([{ tourId: 'sc.products', contentVersion: 1, status: 'COMPLETED', lastStep: null }]);
    const { service } = setup(server, { tours: [tour({ version: 2 })] });
    await service.sync();
    expect(await service.autoStart()).toBeUndefined();
    expect(service.info('products')).toMatchObject({ status: 'completed', hasUpdate: true });
  });

  it('reset clears the person\'s progress on the server and here; when the server is down nothing changes', async () => {
    const server = fakeServer([{ tourId: 'sc.products', contentVersion: 1, status: 'COMPLETED', lastStep: null }]);
    const { service } = setup(server);
    await service.sync();
    server.state.down = true;
    expect(await service.reset()).toBe(false);
    expect(service.info('products').status).toBe('completed');
    server.state.down = false;
    expect(await service.reset()).toBe(true);
    expect(server.rows.size).toBe(0);
    expect(service.info('products').status).toBe('not-started');
  });
});

describe('"do it" steps', () => {
  const doIt = () =>
    tour({
      steps: [
        { id: 'add', target: 'add-product', title: 'x.s1.title', body: 'x.s1.body', doIt: true, success: { target: 'add-product', when: 'click' } },
        { id: 'table', target: 'table-listings', title: 'x.s2.title', body: 'x.s2.body' }
      ]
    });

  function withClick() {
    const server = fakeServer();
    const s = setup(server, { tours: [doIt()] });
    let click: (() => void) | undefined;
    s.env.onClick = (_t, listener) => {
      click = listener;
      return () => (click = undefined);
    };
    return { ...s, click: () => click?.() };
  }

  it('waits for the person\'s own click: the button offers to skip, and the tour never clicks anything', async () => {
    const { service, engine } = withClick();
    await service.start('products');
    expect(engine.last).toMatchObject({ waiting: true, succeeded: false, title: 'T(x.s1.title)' });
    // nothing moves by itself
    await vi.advanceTimersByTimeAsync(DO_IT_ADVANCE_MS * 4);
    expect(engine.last?.title).toBe('T(x.s1.title)');
  });

  it('moves on by itself after the person clicked', async () => {
    const { service, engine, click } = withClick();
    await service.start('products');
    click();
    await vi.advanceTimersByTimeAsync(0);
    expect(engine.last).toMatchObject({ succeeded: true, waiting: false });
    await vi.advanceTimersByTimeAsync(DO_IT_ADVANCE_MS);
    expect(engine.last?.title).toBe('T(x.s2.title)');
  });

  it('never blocks: Skip this step (Next) moves on without the click', async () => {
    const { service, engine } = withClick();
    await service.start('products');
    await service.next();
    expect(engine.last?.title).toBe('T(x.s2.title)');
  });

  it('the schema refuses a doIt step that does not wait for a click', () => {
    expect(() => tour({ steps: [{ id: 'a', target: 'x', title: 'x.s.title', body: 'x.s.body', doIt: true }] })).toThrow(/doIt/);
  });
});
