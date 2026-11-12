import { describe, expect, it } from 'vitest';

import { createMemoryProgressStore, createTourProgressStore, progressKey, type TourStorage } from '../src/progress';

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

const done = { outcome: 'completed' as const, at: '2026-01-01T00:00:00.000Z' };

describe('tour progress', () => {
  it('is keyed by user, tour and version', () => {
    expect(progressKey('u1', 'products', 2)).toBe('at.tour.v1:u1:products:2');
  });

  it('remembers per user', () => {
    const storage = new MapStorage();
    const store = createTourProgressStore({ storage });
    store.set('u1', 'products', 1, done);
    expect(store.get('u1', 'products', 1)).toEqual(done);
    expect(store.get('u2', 'products', 1)).toBeUndefined();
  });

  it('a version bump shows the tour again', () => {
    const store = createTourProgressStore({ storage: new MapStorage() });
    store.set('u1', 'products', 1, done);
    expect(store.get('u1', 'products', 2)).toBeUndefined();
  });

  it('clears one tour (every version) or everything of one person, and no one else', () => {
    const storage = new MapStorage();
    const store = createTourProgressStore({ storage });
    store.set('u1', 'a', 1, done);
    store.set('u1', 'a', 2, done);
    store.set('u1', 'b', 1, done);
    store.set('u2', 'a', 1, done);
    store.clear('u1', 'a');
    expect(store.get('u1', 'a', 1)).toBeUndefined();
    expect(store.get('u1', 'a', 2)).toBeUndefined();
    expect(store.get('u1', 'b', 1)).toEqual(done);
    store.clear('u1');
    expect(store.get('u1', 'b', 1)).toBeUndefined();
    expect(store.get('u2', 'a', 1)).toEqual(done);
  });

  it('survives storage that throws (private windows, blocked storage)', () => {
    const broken: TourStorage = {
      length: 0,
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('quota');
      },
      removeItem: () => {
        throw new Error('denied');
      },
      key: () => {
        throw new Error('denied');
      }
    };
    const store = createTourProgressStore({ storage: broken });
    expect(() => store.set('u1', 'a', 1, done)).not.toThrow();
    expect(store.get('u1', 'a', 1)).toBeUndefined();
    expect(() => store.clear('u1')).not.toThrow();
  });

  it('ignores corrupt values', () => {
    const storage = new MapStorage();
    storage.setItem(progressKey('u1', 'a', 1), '{not json');
    storage.setItem(progressKey('u1', 'b', 1), JSON.stringify({ outcome: 'maybe', at: 1 }));
    const store = createTourProgressStore({ storage });
    expect(store.get('u1', 'a', 1)).toBeUndefined();
    expect(store.get('u1', 'b', 1)).toBeUndefined();
  });

  it('calls the optional remote port after a save and never fails because of it', async () => {
    const saved: string[] = [];
    const store = createTourProgressStore({
      storage: new MapStorage(),
      remote: {
        load: async () => ({}),
        save: async (u, t, v) => {
          saved.push(`${u}:${t}:${v}`);
          throw new Error('offline');
        }
      }
    });
    store.set('u1', 'a', 1, done);
    await Promise.resolve();
    expect(saved).toEqual(['u1:a:1']);
    expect(store.get('u1', 'a', 1)).toEqual(done);
  });

  it('the memory store behaves the same', () => {
    const store = createMemoryProgressStore();
    store.set('u1', 'a', 1, done);
    expect(store.get('u1', 'a', 1)).toEqual(done);
    store.clear('u1');
    expect(store.get('u1', 'a', 1)).toBeUndefined();
  });
});
