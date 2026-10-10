/* Web preview: expo-sqlite's web build needs WASM + cross-origin isolation, so the preview keeps the outbox in
   localStorage instead. Native builds use SQLite (./store.ts). */
import { createMemoryStore } from './memoryStore';

function storage() {
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage;
  } catch {
    return undefined;
  }
}

export const driverStore = createMemoryStore(storage());
