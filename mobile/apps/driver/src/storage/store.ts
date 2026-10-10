/* On-device persistence (SQLite via expo-sqlite): the outbox with its sequence counter, the cached route and small
   key/values. The sequence counter and the events are written in one transaction, so a number is never reused. */
import * as SQLite from 'expo-sqlite';

import { type OutboxState } from '@/domain/outbox';
import { type QueuedEvent } from '@/domain/types';

export interface DriverStore {
  loadOutbox(): Promise<OutboxState | null>;
  saveOutbox(s: OutboxState): Promise<void>;
  getKv<T>(key: string): Promise<T | null>;
  setKv(key: string, value: unknown): Promise<void>;
  removeKv(key: string): Promise<void>;
}

let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

async function db(): Promise<SQLite.SQLiteDatabase> {
  dbPromise ??= (async () => {
    const d = await SQLite.openDatabaseAsync('afrotransact-driver.db');
    await d.execAsync(`
      PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS outbox (sequence INTEGER PRIMARY KEY NOT NULL, event_id TEXT NOT NULL UNIQUE, body TEXT NOT NULL);
    `);
    return d;
  })();
  return dbPromise;
}

const META = 'outbox.meta';

export const sqliteStore: DriverStore = {
  async loadOutbox() {
    const d = await db();
    const meta = await d.getFirstAsync<{ value: string }>('SELECT value FROM kv WHERE key = ?', META);
    if (!meta) return null;
    const rows = await d.getAllAsync<{ body: string }>('SELECT body FROM outbox ORDER BY sequence ASC');
    const m = JSON.parse(meta.value) as Omit<OutboxState, 'events'>;
    return { ...m, events: rows.map((r) => JSON.parse(r.body) as QueuedEvent) };
  },
  async saveOutbox(s) {
    const d = await db();
    const { events, ...meta } = s;
    await d.withExclusiveTransactionAsync(async (tx) => {
      await tx.runAsync('INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)', META, JSON.stringify(meta));
      const keep = events.map((e) => e.sequence);
      if (keep.length) await tx.runAsync(`DELETE FROM outbox WHERE sequence NOT IN (${keep.map(() => '?').join(',')})`, ...keep);
      else await tx.runAsync('DELETE FROM outbox');
      for (const e of events) await tx.runAsync('INSERT OR REPLACE INTO outbox (sequence, event_id, body) VALUES (?, ?, ?)', e.sequence, e.eventId, JSON.stringify(e));
    });
  },
  async getKv<T>(key: string) {
    const d = await db();
    const row = await d.getFirstAsync<{ value: string }>('SELECT value FROM kv WHERE key = ?', key);
    return row ? (JSON.parse(row.value) as T) : null;
  },
  async setKv(key, value) {
    const d = await db();
    await d.runAsync('INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)', key, JSON.stringify(value));
  },
  async removeKv(key) {
    const d = await db();
    await d.runAsync('DELETE FROM kv WHERE key = ?', key);
  }
};
