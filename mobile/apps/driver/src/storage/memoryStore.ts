/* Same contract as the SQLite store, backed by a Map (tests) or Web Storage (web preview). */
import { type OutboxState } from '@/domain/outbox';

import { type DriverStore } from './store';

export function createMemoryStore(backing?: { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void }): DriverStore {
  const map = new Map<string, string>();
  const get = (k: string) => (backing ? backing.getItem(k) : (map.get(k) ?? null));
  const set = (k: string, v: string) => (backing ? backing.setItem(k, v) : map.set(k, v));
  const del = (k: string) => (backing ? backing.removeItem(k) : map.delete(k));
  return {
    loadOutbox: async () => { const v = get('outbox'); return v ? (JSON.parse(v) as OutboxState) : null; },
    saveOutbox: async (s) => { set('outbox', JSON.stringify(s)); },
    getKv: async <T,>(k: string) => { const v = get(`kv.${k}`); return v ? (JSON.parse(v) as T) : null; },
    setKv: async (k, v) => { set(`kv.${k}`, JSON.stringify(v)); },
    removeKv: async (k) => { del(`kv.${k}`); }
  };
}
