import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { demoRoute } from '../src/api/mock/fixtures';
import { createMemoryStore } from '../src/storage/memoryStore';
import { type EngineApi, SyncEngine } from '../src/sync/engine';

/* The app lock is a layer: it must never stop the sync engine, the outbox or location pings for a driver on shift, and
   signing out (including the five-wrong-passcodes sign-out) must keep the outbox on the phone. The lock modules are
   plain code, so this is proven structurally (they cannot reach the engine) and behaviourally (the sign-out path the
   lock uses keeps queued events). */
const root = join(__dirname, '..', 'src');
const read = (p: string) => readFileSync(join(root, p), 'utf8');
const lockFiles = [
  'session/LockProvider.tsx',
  'domain/lockPolicy.ts',
  'domain/passcode.ts',
  'auth/biometrics.ts',
  'ui/PinPad.tsx',
  'ui/usePinEntry.ts',
  ...readdirSync(join(root, 'features/lock')).map((f) => `features/lock/${f}`)
];

describe('the lock never touches sync', () => {
  it.each(lockFiles)('%s does not import or call the engine, the outbox store, the pinger or the API', (file) => {
    const src = read(file).replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    expect(src).not.toMatch(/@\/sync|@\/storage|@\/api|@\/lib\/location/);
    expect(src).not.toMatch(/\bengine\b|\bpinger\b|driverStore|\.flush\(|\.setOnline\(|\.setUser\(|\.record\(/);
  });

  it('the session provider does not depend on the lock (pings and sync follow the session and the app state only)', () => {
    const src = read('session/DriverProvider.tsx');
    expect(src).not.toMatch(/LockProvider|useLock|useAppLocked/);
    expect(src).toMatch(/shiftOn && appActive/);
  });

  it('the lock keeps its secrets in the secure store only, never in the outbox database', () => {
    const src = read('session/LockProvider.tsx');
    expect(src).toMatch(/secureStorage/);
    expect(src).toMatch(/at\.driver\.passcode/);
  });

  it('five wrong passcodes sign out through the same signOut as Profile → Sign out', () => {
    const src = read('session/LockProvider.tsx');
    expect(src).toMatch(/locked-out'\) await signOut\(/);
    // The provider's signOut flushes, ends the session and leaves the SQLite outbox alone (session/endSession.ts).
    const driver = read('session/DriverProvider.tsx');
    expect(driver).toMatch(/await endSession\(endParts\)/);
    expect(read('session/endSession.ts')).toMatch(/p\.engine\.setUser\(null\); \/\/ the outbox stays on the phone/);
  });

  it('the navigator stays mounted under the lock (overlay, not a screen swap)', () => {
    const layout = read('app/_layout.tsx');
    expect(layout).toMatch(/<LockLayer \/>/);
    expect(layout).not.toMatch(/phase === 'locked'/);
  });
});

describe('sign-out keeps the outbox (the path the lock uses)', () => {
  const api: EngineApi = {
    async sync() { throw new Error('offline'); },
    async sos() { return {}; },
    async uploadPhoto() { return 'asset-1'; },
    async routes() { return [demoRoute()]; }
  };

  it('queued events survive engine.setUser(null) and a restart', async () => {
    const store = createMemoryStore();
    const make = () => new SyncEngine({ store, api, uuid: () => 'id-1', now: () => new Date('2026-10-10T19:00:00Z'), position: async () => ({ lat: 29.7, lon: -95.5 }) });
    const e = make();
    await e.init();
    e.setUser('usr_tunde');
    await e.refreshRoute();
    await e.record({ type: 'loadout.scanned', scan: { label: 'AT-1', method: 'CAMERA' } });
    expect(e.getState().outbox.events).toHaveLength(1);
    e.setUser(null); // what session/endSession.ts does on sign-out
    expect(e.getState().outbox.events).toHaveLength(1);
    const restarted = make();
    await restarted.init();
    expect(restarted.getState().outbox.events).toHaveLength(1);
  });
});
