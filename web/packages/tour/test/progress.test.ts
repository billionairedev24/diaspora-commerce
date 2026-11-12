import { describe, expect, it } from 'vitest';

import { createMemoryProgressStore, createTourProgressStore, fromRemote, progressKey, replaces, toRemote, type TourStorage } from '../src/progress';

class MapStorage implements TourStorage {
  readonly map = new Map<string, string>();
  get length() {
    return this.map.size;
  }
  getItem = (k: string) => this.map.get(k) ?? null;
  setItem = (k: string, v: string) => void this.map.set(k, v);
  removeItem = (k: string) => void this.map.delete(k);
  key = (i: number) => [...this.map.keys()][i] ?? null;
}

const done = { status: 'completed' as const, version: 1, at: '2026-01-01T00:00:00.000Z' };

describe('tour progress cache', () => {
  it('is keyed by user and tour', () => {
    expect(progressKey('u1', 'products')).toBe('at.tour.v2:u1:products');
  });

  it('remembers per user', () => {
    const store = createTourProgressStore({ storage: new MapStorage() });
    store.set('u1', 'products', done);
    expect(store.get('u1', 'products')).toEqual(done);
    expect(store.get('u2', 'products')).toBeUndefined();
  });

  it('lists, removes and clears one person without touching others', () => {
    const store = createTourProgressStore({ storage: new MapStorage() });
    store.set('u1', 'a', done);
    store.set('u1', 'b', done);
    store.set('u2', 'a', done);
    expect(Object.keys(store.all('u1')).sort()).toEqual(['a', 'b']);
    store.remove('u1', 'a');
    expect(store.get('u1', 'a')).toBeUndefined();
    store.clear('u1');
    expect(store.all('u1')).toEqual({});
    expect(store.get('u2', 'a')).toEqual(done);
  });

  it('ignores damaged entries', () => {
    const storage = new MapStorage();
    storage.setItem('at.tour.v2:u1:a', '{"status":"nope"}');
    storage.setItem('at.tour.v2:u1:b', 'not json');
    const store = createTourProgressStore({ storage });
    expect(store.get('u1', 'a')).toBeUndefined();
    expect(store.get('u1', 'b')).toBeUndefined();
    expect(store.all('u1')).toEqual({});
  });

  it('reads the first version\'s keys once, for the upload to the server', () => {
    const storage = new MapStorage();
    storage.setItem('at.tour.v1:u1:products:2', JSON.stringify({ outcome: 'never', at: 't' }));
    storage.setItem('at.tour.v1:u1:orders:1', JSON.stringify({ outcome: 'completed', at: 't' }));
    storage.setItem('at.tour.v1:u2:orders:1', JSON.stringify({ outcome: 'completed', at: 't' }));
    const store = createTourProgressStore({ storage });
    expect(store.legacy('u1')).toEqual(expect.arrayContaining([{ tourId: 'products', version: 2, outcome: 'never', at: 't' }, { tourId: 'orders', version: 1, outcome: 'completed', at: 't' }]));
    expect(store.migrated('u1')).toBe(false);
    store.markMigrated('u1');
    store.dropLegacy('u1');
    expect(store.legacy('u1')).toEqual([]);
    expect(store.legacy('u2')).toHaveLength(1);
    expect(store.migrated('u1')).toBe(true);
  });

  it('survives blocked storage', () => {
    const blocked: TourStorage = {
      length: 0,
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
      key: () => { throw new Error('blocked'); }
    };
    const store = createTourProgressStore({ storage: blocked });
    expect(() => store.set('u1', 'a', done)).not.toThrow();
    expect(store.get('u1', 'a')).toBeUndefined();
    expect(store.all('u1')).toEqual({});
    expect(() => store.clear('u1')).not.toThrow();
    expect(store.migrated('u1')).toBe(false);
  });

  it('the in-memory store behaves the same', () => {
    const store = createMemoryProgressStore();
    store.set('u1', 'a', done);
    expect(store.all('u1')).toEqual({ a: done });
    store.clear('u1');
    expect(store.get('u1', 'a')).toBeUndefined();
  });
});

describe('write rules shared with the server', () => {
  it('a newer version wins, an older never does, and in progress never undoes a final status', () => {
    expect(replaces(undefined, 1, 'in-progress')).toBe(true);
    expect(replaces(done, 2, 'in-progress')).toBe(true);
    expect(replaces({ ...done, version: 2 }, 1, 'completed')).toBe(false);
    expect(replaces(done, 1, 'in-progress')).toBe(false);
    expect(replaces(done, 1, 'dismissed')).toBe(true);
    expect(replaces({ status: 'in-progress', version: 1, at: 'x' }, 1, 'completed')).toBe(true);
  });

  it('maps to and from the server\'s record with the app namespace', () => {
    const remote = toRemote('sc', 'orders', { status: 'in-progress', version: 3, lastStep: 'table', at: 't' });
    expect(remote).toEqual({ tourId: 'sc.orders', contentVersion: 3, status: 'IN_PROGRESS', lastStep: 'table' });
    expect(fromRemote('sc', { ...remote, updatedAt: 'u' })).toEqual({ tourId: 'orders', progress: { status: 'in-progress', version: 3, lastStep: 'table', at: 'u' } });
    expect(fromRemote('st', remote)).toBeUndefined();
  });
});
